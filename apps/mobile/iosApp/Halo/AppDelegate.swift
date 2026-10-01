import ComposeApp
import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    private let playerHost = MPVPlayerHost()
    private let playerSystemHost = PlayerSystemHost()
    private let backgroundDownloadService = BackgroundDownloadService()
    // A normal launch gets the real sign-in. Only the literal "fake" selects the
    // hermetic host, so a typo or a missing launch env fails towards production
    // auth rather than towards a build that can never sign in.
    private let authHost: HaloIosAuthHost = {
        if ProcessInfo.processInfo.environment["HALO_AUTH_HOST"] == "fake" {
            return FakeAuthHost()
        }
        return OidcAuthHost()
    }()

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        let env = ProcessInfo.processInfo.environment
        // Matches PlatformDependencies.DefaultServerUrl on the Kotlin side. That
        // constant is internal, so Swift cannot read it; the two are kept in
        // step by hand.
        let initialServerUrl = env["HALO_SERVER_URL"] ?? "https://halo.ditto.moe"
        // Media bases seed the player shell's harness fields. The local base has
        // no portable default (it names files on the machine running the tests),
        // so it is env-only and blank otherwise.
        let mediaHttpBase = env["HALO_MEDIA_HTTP_BASE"] ?? "http://127.0.0.1:18787/media"
        let mediaLocalBase = env["HALO_MEDIA_LOCAL_BASE"] ?? ""
        let composeController = MainViewControllerKt.MainViewController(
            authHost: authHost,
            playerHost: playerHost,
            playerSystemHost: playerSystemHost,
            backgroundDownloadHost: backgroundDownloadService,
            initialServerUrl: initialServerUrl,
            mediaHttpBase: mediaHttpBase,
            mediaLocalBase: mediaLocalBase,
            // UI-test escape hatch: a Keychain session survives reinstall and
            // would strand suites that expect the login form.
            resetPersistedSession: env["HALO_RESET_SESSION"] == "1"
        )
        window.rootViewController = PlayerRootViewController(
            contentController: composeController,
            systemHost: playerSystemHost
        )
        window.makeKeyAndVisible()
        self.window = window
        // The OIDC host anchors its ASWebAuthenticationSession sheet to the key
        // window; the fake host ignores this.
        (authHost as? OidcAuthHost)?.anchorWindow = window
        return true
    }

    func application(
        _ application: UIApplication,
        handleEventsForBackgroundURLSession identifier: String,
        completionHandler: @escaping () -> Void
    ) {
        guard identifier == BackgroundDownloadService.sessionIdentifier else {
            completionHandler()
            return
        }
        backgroundDownloadService.retainBackgroundCompletionHandler(completionHandler)
    }
}
