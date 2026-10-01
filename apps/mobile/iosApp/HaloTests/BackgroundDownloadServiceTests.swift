import XCTest
@testable import Halo

final class BackgroundDownloadServiceTests: XCTestCase {
    func testRetryClassificationAndBackoffAreBoundedAndDeterministic() {
        XCTAssertEqual(BackgroundDownloadPolicy.failureCode(httpStatus: 401), "source_expired")
        XCTAssertEqual(BackgroundDownloadPolicy.failureCode(httpStatus: 403), "source_expired")
        XCTAssertEqual(BackgroundDownloadPolicy.failureCode(httpStatus: 408), "server_unavailable")
        XCTAssertEqual(BackgroundDownloadPolicy.failureCode(httpStatus: 429), "server_unavailable")
        XCTAssertEqual(BackgroundDownloadPolicy.failureCode(httpStatus: 503), "server_unavailable")
        XCTAssertEqual(BackgroundDownloadPolicy.failureCode(httpStatus: 404), "source_rejected")
        XCTAssertNil(BackgroundDownloadPolicy.failureCode(httpStatus: 206))
        XCTAssertEqual((1...5).map { BackgroundDownloadPolicy.retryDelaySeconds(attempt: $0) }, [30, 60, 120, 240, 480])
        XCTAssertEqual(
            BackgroundDownloadPolicy.failureCode(
                error: NSError(
                    domain: NSURLErrorDomain,
                    code: URLError.Code.cannotWriteToFile.rawValue
                )
            ),
            "storage_full"
        )
    }

    func testStoredRequestsAreNotExposedOrRecoveredAsOperatingSystemJobs() {
        XCTAssertFalse(
            BackgroundDownloadPolicy.shouldExposeSnapshot(recordState: "stored", hasTask: false)
        )
        XCTAssertFalse(BackgroundDownloadPolicy.shouldRecoverMissingTask(recordState: "stored"))
        XCTAssertTrue(
            BackgroundDownloadPolicy.shouldExposeSnapshot(recordState: "stored", hasTask: true)
        )
        XCTAssertTrue(BackgroundDownloadPolicy.shouldRecoverMissingTask(recordState: "running"))
        XCTAssertTrue(BackgroundDownloadPolicy.shouldRecoverMissingTask(recordState: "enqueued"))
    }

    func testNotificationMessagesCannotEchoAResolvedSource() {
        let secret = "https://source.test/movie.mkv?token=notification-secret"
        for code in [
            "source_expired",
            "storage_full",
            "server_unavailable",
            "network",
            secret,
        ] {
            let message = BackgroundDownloadPolicy.notificationMessage(for: code)
            XCTAssertFalse(message.contains("source.test"))
            XCTAssertFalse(message.contains("notification-secret"))
        }
    }

    func testBackgroundCompletionWaitsForDidFinishEvents() {
        let service = BackgroundDownloadService()
        let completed = expectation(description: "system completion")
        var didComplete = false
        service.retainBackgroundCompletionHandler {
            didComplete = true
            completed.fulfill()
        }

        RunLoop.current.run(until: Date(timeIntervalSinceNow: 0.05))
        XCTAssertFalse(didComplete)
        service.urlSessionDidFinishEvents(forBackgroundURLSession: .shared)
        wait(for: [completed], timeout: 1)
    }

    func testProtectedRequestRejectsInvalidIdentityAndSource() {
        let service = BackgroundDownloadService()
        XCTAssertFalse(
            service.storeProtectedRequest(
                jobId: "not-a-uuid",
                sourceUrl: "https://source.test/movie.mkv",
                partFilePath: "/tmp/movie.part",
                targetFilePath: "/tmp/movie",
                resumeValidator: nil
            )
        )
        XCTAssertFalse(
            service.storeProtectedRequest(
                jobId: UUID().uuidString,
                sourceUrl: "file:///private/source",
                partFilePath: "/tmp/movie.part",
                targetFilePath: "/tmp/movie",
                resumeValidator: nil
            )
        )
    }

    func testProtectedRequestAcceptsOnlyTheApplicationDownloadDirectory() throws {
        let service = BackgroundDownloadService()
        let jobId = UUID().uuidString
        let documents = try XCTUnwrap(
            FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
        )
        let downloads = documents.appendingPathComponent("downloads", isDirectory: true)
        let target = downloads.appendingPathComponent("movie.mkv")
        let part = downloads.appendingPathComponent("movie.mkv.part")
        defer { service.deleteProtectedRequest(jobId: jobId) }

        XCTAssertTrue(
            service.storeProtectedRequest(
                jobId: jobId,
                sourceUrl: "https://source.test/movie.mkv",
                partFilePath: part.path,
                targetFilePath: target.path,
                resumeValidator: nil
            )
        )
        XCTAssertFalse(
            service.storeProtectedRequest(
                jobId: UUID().uuidString,
                sourceUrl: "https://source.test/movie.mkv",
                partFilePath: "/tmp/movie.mkv.part",
                targetFilePath: "/tmp/movie.mkv",
                resumeValidator: nil
            )
        )
    }
}
