import AVFAudio
import ComposeApp
import MediaPlayer
import UIKit

/// UIKit implementation of the device services used while video is visible.
/// Claims are counted because outgoing and incoming player screens overlap
/// during navigation, so one screen's release must not undo the other's claim.
final class PlayerSystemHost: NSObject, HaloIosPlayerSystemHost {
    private weak var presentationController: PlayerRootViewController?
    private let volumeView = MPVolumeView(frame: .zero)

    private var brightnessBeforeOverride: CGFloat?
    private var idleTimerBeforeClaim: Bool?
    private var landscapeHolders = 0
    private var screenOnHolders = 0
    private var systemBarHolders = 0

    var landscapeLocked: Bool { landscapeHolders > 0 }
    var systemBarsHidden: Bool { systemBarHolders > 0 }

    var diagnosticSummary: String {
        let landscape = landscapeLocked ? "locked" : "released"
        let screen = screenOnHolders > 0 ? "claimed" : "released"
        let bars = systemBarsHidden ? "hidden" : "visible"
        let idleTimer = UIApplication.shared.isIdleTimerDisabled ? "disabled" : "enabled"
        return "System claims: landscape \(landscape) · screen \(screen) · bars \(bars) · idle \(idleTimer)"
    }

    override init() {
        super.init()
        volumeView.showsRouteButton = false
        volumeView.showsVolumeSlider = true
        volumeView.isUserInteractionEnabled = false
    }

    func attach(to controller: PlayerRootViewController) {
        presentationController = controller

        // MPVolumeView must belong to a live hierarchy for its public slider to
        // control system volume. Keep it outside the visible bounds; alpha zero
        // can cause UIKit to stop updating it on some iOS releases.
        volumeView.frame = CGRect(x: -100, y: -100, width: 1, height: 1)
        volumeView.alpha = 0.01
        controller.view.addSubview(volumeView)
        controller.presentationClaimsDidChange()
    }

    func screenBrightness() -> Double {
        Double(UIScreen.main.brightness)
    }

    func setScreenBrightness(value: Double) {
        if brightnessBeforeOverride == nil {
            brightnessBeforeOverride = UIScreen.main.brightness
        }
        UIScreen.main.brightness = CGFloat(value.clamped(to: 0...1))
    }

    func clearScreenBrightnessOverride() {
        guard let brightnessBeforeOverride else { return }
        UIScreen.main.brightness = brightnessBeforeOverride
        self.brightnessBeforeOverride = nil
    }

    func volume() -> Double {
        Double(AVAudioSession.sharedInstance().outputVolume)
    }

    func setVolume(value: Double) {
        guard let slider = volumeView.subviews.compactMap({ $0 as? UISlider }).first else { return }
        slider.setValue(Float(value.clamped(to: 0...1)), animated: false)
        slider.sendActions(for: .valueChanged)
    }

    /// iOS exposes a continuous slider but hardware buttons use sixteen steps.
    func volumeSteps() -> Int32 { 16 }

    func lockLandscape() {
        landscapeHolders += 1
        guard landscapeHolders == 1 else { return }
        presentationController?.presentationClaimsDidChange()
    }

    func releaseLandscape() {
        guard landscapeHolders > 0 else { return }
        landscapeHolders -= 1
        guard landscapeHolders == 0 else { return }
        presentationController?.presentationClaimsDidChange()
    }

    func keepScreenOn() {
        screenOnHolders += 1
        guard screenOnHolders == 1 else { return }
        idleTimerBeforeClaim = UIApplication.shared.isIdleTimerDisabled
        UIApplication.shared.isIdleTimerDisabled = true
        presentationController?.refreshSystemDiagnostics()
    }

    func releaseScreenOn() {
        guard screenOnHolders > 0 else { return }
        screenOnHolders -= 1
        guard screenOnHolders == 0 else { return }
        UIApplication.shared.isIdleTimerDisabled = idleTimerBeforeClaim ?? false
        idleTimerBeforeClaim = nil
        presentationController?.refreshSystemDiagnostics()
    }

    func hideSystemBars() {
        systemBarHolders += 1
        guard systemBarHolders == 1 else { return }
        presentationController?.presentationClaimsDidChange()
    }

    func releaseSystemBars() {
        guard systemBarHolders > 0 else { return }
        systemBarHolders -= 1
        guard systemBarHolders == 0 else { return }
        presentationController?.presentationClaimsDidChange()
    }
}

/// Root wrapper required for dynamic orientation and system-bar decisions.
/// The Compose controller remains the only content child and keeps its normal
/// lifecycle; this wrapper owns presentation policy only.
final class PlayerRootViewController: UIViewController {
    private let contentController: UIViewController
    private let systemHost: PlayerSystemHost
    private var systemDiagnosticsView: UIView?

    init(contentController: UIViewController, systemHost: PlayerSystemHost) {
        self.contentController = contentController
        self.systemHost = systemHost
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        addChild(contentController)
        contentController.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(contentController.view)
        NSLayoutConstraint.activate([
            contentController.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            contentController.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            contentController.view.topAnchor.constraint(equalTo: view.topAnchor),
            contentController.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        contentController.didMove(toParent: self)

        if ProcessInfo.processInfo.environment["HALO_UI_TEST_SYSTEM_DIAGNOSTICS"] == "1" {
            let diagnostics = UIView(frame: CGRect(x: 0, y: 0, width: 1, height: 1))
            diagnostics.isAccessibilityElement = true
            diagnostics.accessibilityIdentifier = "Player system diagnostics"
            diagnostics.isUserInteractionEnabled = false
            diagnostics.backgroundColor = .clear
            view.addSubview(diagnostics)
            systemDiagnosticsView = diagnostics
        }
        systemHost.attach(to: self)
    }

    override var shouldAutorotate: Bool { true }

    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        systemHost.landscapeLocked ? .landscape : defaultInterfaceOrientations
    }

    override var prefersStatusBarHidden: Bool {
        systemHost.systemBarsHidden
    }

    override var prefersHomeIndicatorAutoHidden: Bool {
        systemHost.systemBarsHidden
    }

    override var preferredScreenEdgesDeferringSystemGestures: UIRectEdge {
        systemHost.systemBarsHidden ? .all : []
    }

    func presentationClaimsDidChange() {
        refreshSystemDiagnostics()
        setNeedsStatusBarAppearanceUpdate()
        setNeedsUpdateOfHomeIndicatorAutoHidden()
        setNeedsUpdateOfScreenEdgesDeferringSystemGestures()

        if #available(iOS 16.0, *) {
            setNeedsUpdateOfSupportedInterfaceOrientations()
            let requested: UIInterfaceOrientationMask = systemHost.landscapeLocked
                ? .landscape
                : defaultInterfaceOrientations
            guard let scene = viewIfLoaded?.window?.windowScene else { return }
            let preferences = UIWindowScene.GeometryPreferences.iOS(interfaceOrientations: requested)
            scene.requestGeometryUpdate(preferences) { error in
                print("orientation request failed: \(error.localizedDescription)")
            }
            return
        }

        if systemHost.landscapeLocked {
            UIDevice.current.setValue(UIDeviceOrientation.landscapeRight.rawValue, forKey: "orientation")
        }
        UIViewController.attemptRotationToDeviceOrientation()
    }

    func refreshSystemDiagnostics() {
        systemDiagnosticsView?.accessibilityLabel = systemHost.diagnosticSummary
    }

    private var defaultInterfaceOrientations: UIInterfaceOrientationMask {
        UIDevice.current.userInterfaceIdiom == .pad ? .all : .allButUpsideDown
    }
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
