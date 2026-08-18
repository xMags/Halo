import ComposeApp
import Foundation
import Security
import UIKit
import UserNotifications

/// Swift-owned durable transfer service. Kotlin sees only opaque job IDs and
/// sanitized state; resolved URLs remain in ThisDeviceOnly Keychain records.
final class BackgroundDownloadService: NSObject, HaloIosBackgroundDownloadHost {
    static let sessionIdentifier = "moe.ditto.halo.background-downloads.v1"

    private enum JobState: String, Codable {
        case stored
        case enqueued
        case running
        case paused
        case succeeded
        case failed
        case cancelled
    }

    private struct StoredRequest: Codable {
        let jobId: String
        let sourceUrl: String
        let partFilePath: String
        let targetFilePath: String
        let resumeValidator: String?
        var state: JobState
        var downloadedBytes: Int64
        var totalBytes: Int64
        var attemptCount: Int
        var failureCode: String?
    }

    private struct Snapshot: Codable {
        let jobId: String
        let state: String
        let downloadedBytes: Int64
        let totalBytes: Int64
        let failureCode: String?
    }

    private let stateQueue = DispatchQueue(label: "moe.ditto.halo.background-download-state")
    private let delegateQueue: OperationQueue = {
        let queue = OperationQueue()
        queue.name = "moe.ditto.halo.background-download-delegate"
        queue.maxConcurrentOperationCount = 1
        return queue
    }()
    private var eventSink: HaloIosDownloadEventSink?
    private var lastProgressAt: [String: Date] = [:]
    private var handledTaskIdentifiers: Set<Int> = []
    private struct PendingCancellation {
        var taskIdentifiers: Set<Int>
        var completions: [() -> Void]
    }
    private var pendingCancellations: [String: PendingCancellation] = [:]
    private var backgroundCompletionHandler: (() -> Void)?

    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.background(withIdentifier: Self.sessionIdentifier)
        configuration.allowsCellularAccess = true
        configuration.waitsForConnectivity = true
        configuration.isDiscretionary = false
        configuration.sessionSendsLaunchEvents = true
        configuration.httpMaximumConnectionsPerHost = 1
        return URLSession(configuration: configuration, delegate: self, delegateQueue: delegateQueue)
    }()

    func setDownloadEventSink(sink: HaloIosDownloadEventSink?) {
        stateQueue.async { self.eventSink = sink }
    }

    func storeProtectedRequest(
        jobId: String,
        sourceUrl: String,
        partFilePath: String,
        targetFilePath: String,
        resumeValidator: String?
    ) -> Bool {
        stateQueue.sync {
            guard Self.validJobId(jobId),
                  Self.validHTTPURL(sourceUrl),
                  Self.validDownloadPaths(partFilePath: partFilePath, targetFilePath: targetFilePath)
            else { return false }
            let request = StoredRequest(
                jobId: jobId,
                sourceUrl: sourceUrl,
                partFilePath: partFilePath,
                targetFilePath: targetFilePath,
                resumeValidator: resumeValidator,
                state: .stored,
                downloadedBytes: 0,
                totalBytes: 0,
                attemptCount: 0,
                failureCode: nil
            )
            return write(request)
        }
    }

    func deleteProtectedRequest(jobId: String) {
        stateQueue.async { Self.deleteKeychainItem(jobId: jobId) }
    }

    func requestNotificationAuthorization() {
        let defaults = UserDefaults.standard
        let key = "halo.downloadNotificationsRequested.v1"
        guard !defaults.bool(forKey: key) else { return }
        defaults.set(true, forKey: key)
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    func enqueue(jobId: String) {
        startOrFind(jobId: jobId, resumeSuspended: false, resetAttempts: false)
    }

    func pause(jobId: String, completion: @escaping () -> Void) {
        guard Self.validJobId(jobId) else {
            completion()
            return
        }
        session.getAllTasks { tasks in
            let task = tasks.first(where: { $0.taskDescription == jobId })
            task?.suspend()
            self.stateQueue.async {
                defer { completion() }
                guard var request = self.read(jobId) else { return }
                request.state = .paused
                request.downloadedBytes = task?.countOfBytesReceived ?? request.downloadedBytes
                request.totalBytes = max(task?.countOfBytesExpectedToReceive ?? 0, request.totalBytes)
                _ = self.write(request)
                self.eventSink?.onPaused(
                    jobId: jobId,
                    downloadedBytes: request.downloadedBytes,
                    totalBytes: request.totalBytes
                )
            }
        }
    }

    func resume(jobId: String) {
        requestNotificationAuthorization()
        startOrFind(jobId: jobId, resumeSuspended: true, resetAttempts: true)
    }

    func cancel(jobId: String, completion: @escaping () -> Void) {
        guard Self.validJobId(jobId) else {
            completion()
            return
        }
        session.getAllTasks { tasks in
            let matching = tasks.filter { $0.taskDescription == jobId }
            self.stateQueue.async {
                guard matching.isEmpty == false else {
                    if var pending = self.pendingCancellations[jobId] {
                        pending.completions.append(completion)
                        self.pendingCancellations[jobId] = pending
                    } else {
                        completion()
                    }
                    return
                }
                var pending = self.pendingCancellations[jobId] ?? PendingCancellation(
                    taskIdentifiers: [],
                    completions: []
                )
                pending.taskIdentifiers.formUnion(matching.map(\.taskIdentifier))
                pending.completions.append(completion)
                self.pendingCancellations[jobId] = pending
                matching.forEach { $0.cancel() }
            }
        }
    }

    func reconcile(completion: @escaping (String) -> Void) {
        session.getAllTasks { tasks in
            self.stateQueue.async {
                var records = Self.readAllKeychainItems()
                let tasksByJob = Dictionary(
                    tasks.compactMap { task -> (String, URLSessionTask)? in
                        guard let jobId = task.taskDescription else { return nil }
                        return (jobId, task)
                    },
                    uniquingKeysWith: { first, _ in first }
                )

                // Recover the narrow crash window where the Keychain record was
                // durable but task creation had not completed.
                for index in records.indices {
                    let record = records[index]
                    if tasksByJob[record.jobId] == nil &&
                        BackgroundDownloadPolicy.shouldRecoverMissingTask(recordState: record.state.rawValue) {
                        self.createTask(for: record, earliestBeginDate: nil)
                        records[index].state = .enqueued
                        _ = self.write(records[index])
                    }
                }

                let snapshots = records.compactMap { record -> Snapshot? in
                    let task = tasksByJob[record.jobId]
                    guard BackgroundDownloadPolicy.shouldExposeSnapshot(
                        recordState: record.state.rawValue,
                        hasTask: task != nil
                    ) else { return nil }
                    guard let task = task else {
                        return Snapshot(
                            jobId: record.jobId,
                            state: record.state.rawValue,
                            downloadedBytes: record.downloadedBytes,
                            totalBytes: record.totalBytes,
                            failureCode: record.failureCode
                        )
                    }
                    let state: JobState
                    switch task.state {
                    case .running: state = .running
                    case .suspended: state = .paused
                    case .canceling: state = .cancelled
                    case .completed: state = record.state
                    @unknown default: state = record.state
                    }
                    return Snapshot(
                        jobId: record.jobId,
                        state: state.rawValue,
                        downloadedBytes: task.countOfBytesReceived,
                        totalBytes: max(task.countOfBytesExpectedToReceive, record.totalBytes),
                        failureCode: record.failureCode
                    )
                }
                let payload = (try? JSONEncoder().encode(snapshots))
                    .flatMap { String(data: $0, encoding: .utf8) } ?? "[]"
                completion(payload)
            }
        }
    }

    func retainBackgroundCompletionHandler(_ completionHandler: @escaping () -> Void) {
        stateQueue.async { self.backgroundCompletionHandler = completionHandler }
    }

    private func startOrFind(jobId: String, resumeSuspended: Bool, resetAttempts: Bool) {
        guard Self.validJobId(jobId) else { return }
        session.getAllTasks { tasks in
            if let task = tasks.first(where: { $0.taskDescription == jobId }) {
                if resumeSuspended && task.state == .suspended { task.resume() }
                self.stateQueue.async {
                    guard var record = self.read(jobId) else { return }
                    record.state = task.state == .suspended ? .paused : .running
                    record.failureCode = nil
                    if resetAttempts { record.attemptCount = 0 }
                    _ = self.write(record)
                }
                return
            }
            self.stateQueue.async {
                guard var request = self.read(jobId) else {
                    self.eventSink?.onFailed(jobId: jobId, failureCode: "protected_request_corrupt")
                    return
                }
                request.state = .running
                request.failureCode = nil
                if resetAttempts { request.attemptCount = 0 }
                guard self.write(request) else {
                    self.eventSink?.onFailed(jobId: jobId, failureCode: "protected_request_corrupt")
                    return
                }
                self.createTask(for: request, earliestBeginDate: nil)
            }
        }
    }

    private func createTask(for request: StoredRequest, earliestBeginDate: Date?) {
        guard let url = URL(string: request.sourceUrl) else {
            fail(request, code: "protected_request_corrupt")
            return
        }
        let task = session.downloadTask(with: url)
        task.taskDescription = request.jobId
        task.earliestBeginDate = earliestBeginDate
        task.resume()
    }

    private func retry(_ request: StoredRequest, serverFailure: Bool) {
        guard request.attemptCount < BackgroundDownloadPolicy.maximumRetries else {
            fail(request, code: serverFailure ? "server_unavailable" : "network")
            return
        }
        var retry = request
        retry.attemptCount += 1
        retry.state = .enqueued
        retry.failureCode = nil
        guard write(retry) else {
            fail(request, code: "protected_request_corrupt")
            return
        }
        let seconds = BackgroundDownloadPolicy.retryDelaySeconds(attempt: retry.attemptCount)
        createTask(for: retry, earliestBeginDate: Date(timeIntervalSinceNow: seconds))
    }

    private func fail(_ request: StoredRequest, code: String) {
        var failed = request
        failed.state = .failed
        failed.failureCode = code
        _ = write(failed)
        eventSink?.onFailed(jobId: request.jobId, failureCode: code)
        notify(
            jobId: request.jobId,
            title: "Download stopped",
            body: BackgroundDownloadPolicy.notificationMessage(for: code)
        )
    }

    private func write(_ request: StoredRequest) -> Bool {
        guard let data = try? JSONEncoder().encode(request) else { return false }
        let query = Self.keychainQuery(jobId: request.jobId)
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let updateStatus = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if updateStatus == errSecSuccess { return true }
        guard updateStatus == errSecItemNotFound else { return false }
        var add = query
        attributes.forEach { add[$0.key] = $0.value }
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    private func read(_ jobId: String) -> StoredRequest? {
        var query = Self.keychainQuery(jobId: jobId)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        guard let request = try? JSONDecoder().decode(StoredRequest.self, from: data),
              request.jobId == jobId,
              Self.validJobId(request.jobId),
              Self.validHTTPURL(request.sourceUrl),
              Self.validDownloadPaths(
                  partFilePath: request.partFilePath,
                  targetFilePath: request.targetFilePath
              )
        else { return nil }
        return request
    }

    private static func readAllKeychainItems() -> [StoredRequest] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecReturnData as String: true,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll,
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return [] }
        let items: [[String: Any]]
        if let values = result as? [[String: Any]] {
            items = values
        } else if let value = result as? [String: Any] {
            items = [value]
        } else {
            return []
        }
        return items.compactMap { item in
            guard let account = item[kSecAttrAccount as String] as? String,
                  let data = item[kSecValueData as String] as? Data,
                  let request = try? JSONDecoder().decode(StoredRequest.self, from: data),
                  request.jobId == account,
                  validJobId(request.jobId),
                  validHTTPURL(request.sourceUrl),
                  validDownloadPaths(
                      partFilePath: request.partFilePath,
                      targetFilePath: request.targetFilePath
                  )
            else { return nil }
            return request
        }
    }

    private static func deleteKeychainItem(jobId: String) {
        SecItemDelete(keychainQuery(jobId: jobId) as CFDictionary)
    }

    private static func keychainQuery(jobId: String) -> [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: keychainService,
        kSecAttrAccount as String: jobId,
    ]

    private func notify(jobId: String, title: String, body: String) {
        DispatchQueue.main.async {
            guard UIApplication.shared.applicationState != .active else { return }
            let center = UNUserNotificationCenter.current()
            center.getNotificationSettings { settings in
                guard settings.authorizationStatus == .authorized ||
                    settings.authorizationStatus == .provisional
                else { return }
                let content = UNMutableNotificationContent()
                content.title = title
                content.body = body
                content.sound = .default
                center.add(
                    UNNotificationRequest(
                        identifier: "halo-download-\(jobId)",
                        content: content,
                        trigger: nil
                    )
                )
            }
        }
    }

    private func finishCancellation(jobId: String, taskIdentifier: Int) {
        guard var pending = pendingCancellations[jobId] else { return }
        pending.taskIdentifiers.remove(taskIdentifier)
        guard pending.taskIdentifiers.isEmpty else {
            pendingCancellations[jobId] = pending
            return
        }
        pendingCancellations.removeValue(forKey: jobId)
        pending.completions.forEach { $0() }
    }

    private static func validJobId(_ value: String) -> Bool {
        UUID(uuidString: value)?.uuidString.caseInsensitiveCompare(value) == .orderedSame
    }

    private static func validHTTPURL(_ value: String) -> Bool {
        guard let url = URL(string: value),
              let scheme = url.scheme?.lowercased(),
              url.host != nil
        else { return false }
        return scheme == "http" || scheme == "https"
    }

    private static func validDownloadPaths(partFilePath: String, targetFilePath: String) -> Bool {
        guard let documents = FileManager.default.urls(
            for: .documentDirectory,
            in: .userDomainMask
        ).first else { return false }
        let downloads = documents
            .appendingPathComponent("downloads", isDirectory: true)
            .standardizedFileURL
        let part = URL(fileURLWithPath: partFilePath).standardizedFileURL
        let target = URL(fileURLWithPath: targetFilePath).standardizedFileURL
        return part.deletingLastPathComponent() == downloads &&
            target.deletingLastPathComponent() == downloads &&
            part.lastPathComponent == target.lastPathComponent + ".part" &&
            target.lastPathComponent.isEmpty == false
    }

    private static let keychainService = "moe.ditto.halo.background-download-request.v1"
}

extension BackgroundDownloadService: URLSessionDownloadDelegate, URLSessionTaskDelegate {
    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        guard let jobId = downloadTask.taskDescription else { return }
        stateQueue.async {
            let now = Date()
            if let previous = self.lastProgressAt[jobId], now.timeIntervalSince(previous) < 0.5 { return }
            self.lastProgressAt[jobId] = now
            guard var request = self.read(jobId) else { return }
            request.state = .running
            request.downloadedBytes = totalBytesWritten
            request.totalBytes = max(totalBytesExpectedToWrite, 0)
            _ = self.write(request)
            self.eventSink?.onProgress(
                jobId: jobId,
                downloadedBytes: request.downloadedBytes,
                totalBytes: request.totalBytes
            )
        }
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        guard let jobId = downloadTask.taskDescription else { return }
        stateQueue.sync {
            guard let request = read(jobId) else {
                handledTaskIdentifiers.insert(downloadTask.taskIdentifier)
                return
            }
            let status = (downloadTask.response as? HTTPURLResponse)?.statusCode ?? 0
            if let failure = BackgroundDownloadPolicy.failureCode(httpStatus: status) {
                handledTaskIdentifiers.insert(downloadTask.taskIdentifier)
                if failure == "server_unavailable" {
                    retry(request, serverFailure: true)
                } else {
                    fail(request, code: failure)
                }
                return
            }

            do {
                let manager = FileManager.default
                let target = URL(fileURLWithPath: request.targetFilePath)
                try manager.createDirectory(
                    at: target.deletingLastPathComponent(),
                    withIntermediateDirectories: true
                )
                if manager.fileExists(atPath: target.path) {
                    _ = try manager.replaceItemAt(target, withItemAt: location)
                } else {
                    try manager.moveItem(at: location, to: target)
                }
                try? manager.removeItem(atPath: request.partFilePath)
                var completed = request
                completed.state = .succeeded
                completed.downloadedBytes = max(downloadTask.countOfBytesReceived, 0)
                completed.totalBytes = max(downloadTask.countOfBytesExpectedToReceive, completed.downloadedBytes)
                completed.failureCode = nil
                _ = write(completed)
                eventSink?.onCompleted(
                    jobId: jobId,
                    downloadedBytes: completed.downloadedBytes,
                    totalBytes: completed.totalBytes
                )
                notify(jobId: jobId, title: "Download complete", body: "Ready to watch offline")
            } catch let error as NSError {
                let code = error.code == NSFileWriteOutOfSpaceError ? "storage_full" : "missing_file"
                fail(request, code: code)
            }
            handledTaskIdentifiers.insert(downloadTask.taskIdentifier)
        }
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didCompleteWithError error: Error?
    ) {
        guard let jobId = task.taskDescription else { return }
        stateQueue.async {
            defer { self.finishCancellation(jobId: jobId, taskIdentifier: task.taskIdentifier) }
            if self.handledTaskIdentifiers.remove(task.taskIdentifier) != nil { return }
            guard let error = error as NSError?, var request = self.read(jobId) else { return }
            if error.domain == NSURLErrorDomain && error.code == NSURLErrorCancelled { return }
            request.downloadedBytes = max(task.countOfBytesReceived, request.downloadedBytes)
            request.totalBytes = max(task.countOfBytesExpectedToReceive, request.totalBytes)
            let code = BackgroundDownloadPolicy.failureCode(error: error)
            if code == "storage_full" {
                self.fail(request, code: code)
            } else {
                self.retry(request, serverFailure: false)
            }
        }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        stateQueue.async {
            let completion = self.backgroundCompletionHandler
            self.backgroundCompletionHandler = nil
            DispatchQueue.main.async { completion?() }
        }
    }
}
