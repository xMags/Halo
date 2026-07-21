import ComposeApp
import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    private let playerHost = MPVPlayerHost()
    // Default is the hermetic fake; the OIDC test opts into the real host with a
    // launch env so it never touches the already-green ownership/playback suites.
    private let authHost: HaloIosAuthHost = {
        if ProcessInfo.processInfo.environment["HALO_AUTH_HOST"] == "oidc" {
            return OidcAuthHost()
        }
        return FakeAuthHost()
    }()

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        let env = ProcessInfo.processInfo.environment
        let initialServerUrl = env["HALO_SERVER_URL"] ?? "https://halo.ditto.moe"
        // Media bases seed the player shell's harness fields. The local base has
        // no portable default (it names files on the machine running the tests),
        // so it is env-only and blank otherwise.
        let mediaHttpBase = env["HALO_MEDIA_HTTP_BASE"] ?? "http://127.0.0.1:18787/media"
        let mediaLocalBase = env["HALO_MEDIA_LOCAL_BASE"] ?? ""
        window.rootViewController = MainViewControllerKt.MainViewController(
            authHost: authHost,
            playerHost: playerHost,
            initialServerUrl: initialServerUrl,
            mediaHttpBase: mediaHttpBase,
            mediaLocalBase: mediaLocalBase,
            // UI-test escape hatch: a Keychain session survives reinstall and
            // would strand suites that expect the login form.
            resetPersistedSession: env["HALO_RESET_SESSION"] == "1"
        )
        window.makeKeyAndVisible()
        self.window = window
        // The OIDC host anchors its ASWebAuthenticationSession sheet to the key
        // window; the fake host ignores this.
        (authHost as? OidcAuthHost)?.anchorWindow = window
        return true
    }
}
