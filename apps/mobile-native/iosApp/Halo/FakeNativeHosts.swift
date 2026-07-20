import ComposeApp
import UIKit

/// Default host for the ownership/playback suites: canned config, no browser,
/// no network — that is what keeps those gates hermetic and their "Continue"
/// taps instant. The real sign-in lives in `OidcAuthHost`, selected only when
/// `AppDelegate` sees `HALO_AUTH_HOST=oidc`.
final class FakeAuthHost: NSObject, HaloIosAuthHost {
    let hostId = UUID().uuidString
    private(set) var oidcRequestCount: Int64 = 0
    private(set) var lastOidcServerUrl: String?
    private var eventSink: HaloIosAuthEventSink?

    func setAuthEventSink(sink: HaloIosAuthEventSink?) {
        // Retained for protocol conformance; the fake never completes a flow.
        eventSink = sink
    }

    func fetchAuthConfig(serverUrl: String, completion: @escaping (String?, String?) -> Void) {
        // Synchronous canned response — the "local" substring still lets a manual
        // run demo both discovery branches on one build.
        if serverUrl.localizedCaseInsensitiveContains("local") {
            completion(#"{"mode":"local"}"#, nil)
            return
        }
        completion(
            #"{"mode":"oidc","issuer":"https://auth.example.test/application/o/halo/","clientId":"halo","scopes":["openid","profile","email","offline_access","groups"]}"#,
            nil
        )
    }

    func requestOidc(serverUrl: String, issuer: String, clientId: String, scopes: String) {
        oidcRequestCount += 1
        lastOidcServerUrl = serverUrl
    }
}
