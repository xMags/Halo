import Foundation

enum BackgroundDownloadPolicy {
    static let maximumRetries = 5
    static let storedState = "stored"

    static func retryDelaySeconds(attempt: Int) -> TimeInterval {
        30.0 * pow(2.0, Double(max(attempt - 1, 0)))
    }

    static func failureCode(httpStatus: Int) -> String? {
        if httpStatus == 401 || httpStatus == 403 { return "source_expired" }
        if httpStatus == 408 || httpStatus == 429 || httpStatus >= 500 {
            return "server_unavailable"
        }
        if (200..<300).contains(httpStatus) { return nil }
        return "source_rejected"
    }

    static func failureCode(error: NSError) -> String {
        if error.domain == NSCocoaErrorDomain && error.code == NSFileWriteOutOfSpaceError {
            return "storage_full"
        }
        if error.domain == NSURLErrorDomain && error.code == URLError.Code.cannotWriteToFile.rawValue {
            return "storage_full"
        }
        return "network"
    }

    static func shouldRecoverMissingTask(recordState: String) -> Bool {
        recordState == "running" || recordState == "enqueued"
    }

    static func shouldExposeSnapshot(recordState: String, hasTask: Bool) -> Bool {
        hasTask || recordState != storedState
    }

    static func notificationMessage(for code: String) -> String {
        switch code {
        case "source_expired": return "Choose the source again to continue."
        case "storage_full": return "The device ran out of storage."
        case "server_unavailable": return "The source is still unavailable after repeated retries."
        case "network": return "The download could not continue after repeated network failures."
        default: return "Open Halo to review this download."
        }
    }
}
