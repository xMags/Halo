import ComposeApp
import AVFAudio
import UIKit

/// The one persistent player UIView. Its identity must survive Compose
/// attach/detach/recompose AND explicit core recreation — only the hosted
/// video layer (owned by the current core) is swapped.
final class PlayerSurfaceView: UIView {
    let instanceId = UUID().uuidString
    private weak var videoLayer: MetalLayer?

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        clipsToBounds = true
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func host(_ layer: MetalLayer) {
        videoLayer?.removeFromSuperlayer()
        layer.contentsScale = UIScreen.main.nativeScale
        layer.frame = bounds
        layer.backgroundColor = UIColor.black.cgColor
        layer.framebufferOnly = true
        self.layer.addSublayer(layer)
        videoLayer = layer
        syncDrawableSize()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        videoLayer?.frame = bounds
        syncDrawableSize()
        CATransaction.commit()
    }

    /// CAMetalLayer only derives drawableSize when contentsScale changes, and
    /// our layer gets its scale while bounds are still zero — so without this
    /// the property stays 0x0 forever and mpv's MoltenVK context (which treats
    /// drawableSize as the surface source of truth) sees no surface. The
    /// MetalLayer subclass still guards against MoltenVK's 1x1 stomp.
    private func syncDrawableSize() {
        guard let videoLayer else { return }
        let scale = videoLayer.contentsScale
        let size = CGSize(width: bounds.width * scale, height: bounds.height * scale)
        guard size.width > 1, size.height > 1 else { return }
        videoLayer.drawableSize = size
    }
}

/// Real libmpv implementation of the proven host boundary. Swift owns the
/// persistent surface view and the current `MPVCore`; Kotlin only ever talks
/// through `HaloIosPlayerHost` / `HaloIosPlayerEventSink`.
final class MPVPlayerHost: NSObject, HaloIosPlayerHost {
    let hostId = UUID().uuidString

    private let surfaceView = PlayerSurfaceView(frame: .zero)
    private var core: MPVCore
    private var sink: HaloIosPlayerEventSink?
    private var lastSize = CGSize.zero
    /// Test playback stays muted unless explicitly opted out — dev playback
    /// audio otherwise comes out of the host machine's speakers.
    private let muted = !ProcessInfo.processInfo.arguments.contains("--unmuted")

    var instanceId: String { core.id }
    var playerViewInstanceId: String { surfaceView.instanceId }
    private(set) var coreCreationCount: Int64 = 1
    private(set) var coreDestructionCount: Int64 = 0
    let playerViewCreationCount: Int64 = 1
    private(set) var attachCount: Int64 = 0
    private(set) var resizeCount: Int64 = 0
    private(set) var detachCount: Int64 = 0
    private(set) var loadCount: Int64 = 0
    private(set) var teardownCount: Int64 = 0

    override init() {
        core = MPVCore(muted: muted)
        super.init()
        surfaceView.host(core.videoLayer)
        bind(core)

        // Playback category is the background-audio prerequisite (with the
        // UIBackgroundModes=audio plist entry).
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)

        NotificationCenter.default.addObserver(
            self, selector: #selector(enteredBackground),
            name: UIApplication.didEnterBackgroundNotification, object: nil)
        NotificationCenter.default.addObserver(
            self, selector: #selector(enteringForeground),
            name: UIApplication.willEnterForegroundNotification, object: nil)
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    // MARK: surface lifecycle (Compose-driven)

    func playerView() -> UIView {
        surfaceView
    }

    func didAttachPlayerView() {
        attachCount += 1
    }

    func didResizePlayerView(widthPoints: Double, heightPoints: Double) {
        let nextSize = CGSize(width: widthPoints, height: heightPoints)
        guard nextSize != lastSize else { return }
        lastSize = nextSize
        resizeCount += 1
    }

    func didDetachPlayerView() {
        detachCount += 1
    }

    // MARK: events

    func setEventSink(sink: HaloIosPlayerEventSink?) {
        self.sink = sink
    }

    private func bind(_ core: MPVCore) {
        core.setEventHandler { [weak self] event in
            DispatchQueue.main.async {
                self?.forward(event)
            }
        }
    }

    private func forward(_ event: MPVCoreEvent) {
        guard let sink else { return }
        switch event {
        case .ready(let durationSeconds):
            sink.onReady(durationSeconds: durationSeconds)
        case .position(let seconds):
            sink.onPosition(positionSeconds: seconds)
        case .pauseChanged(let paused):
            sink.onPauseChanged(paused: paused)
        case .tracks(let json):
            sink.onTracks(tracksJson: json)
        case .ended:
            sink.onEnded()
        case .error(let message):
            sink.onError(message: message)
        }
    }

    // MARK: playback commands (Kotlin-driven)

    func load(id: String, title: String, url: String) {
        loadCount += 1
        core.load(url: url)
    }

    func setPaused(paused: Bool) {
        core.setPaused(paused)
    }

    func seekTo(positionSeconds: Double) {
        core.seekTo(seconds: positionSeconds)
    }

    func selectAudioTrack(id: String?) {
        core.selectTrack(kind: "aid", id: id)
    }

    func selectSubtitleTrack(id: String?) {
        core.selectTrack(kind: "sid", id: id)
    }

    func setSubtitleDelay(seconds: Double) {
        core.setSubtitleDelay(seconds: seconds)
    }

    func setSubtitleScale(scale: Double) {
        core.setSubtitleScale(scale)
    }

    func setSubtitleFont(font: String?) {
        core.setSubtitleFont(font)
    }

    func addSubtitle(url: String) {
        core.addSubtitle(url: url)
    }

    func teardown() {
        teardownCount += 1
        core.shutdown()
    }

    func destroyAndRecreateCore() {
        core.shutdown()
        coreDestructionCount += 1
        core = MPVCore(muted: muted)
        coreCreationCount += 1
        surfaceView.host(core.videoLayer)
        bind(core)
    }

    // MARK: background probe

    @objc private func enteredBackground() {
        // GPU work must stop in the background or the app is killed for Metal
        // use; dropping the video track keeps audio decoding alive.
        core.setVideoDecodingSuspended(true)
    }

    @objc private func enteringForeground() {
        core.setVideoDecodingSuspended(false)
    }
}
