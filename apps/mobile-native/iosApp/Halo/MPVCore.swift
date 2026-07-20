import Foundation
import UIKit
import AVFAudio
import Libmpv

/// Normalized playback events. `tracksJson` is the neutral schema the Kotlin
/// side parses (`PlayerTracksJson`) — mpv's own `track-list` shape stays here.
enum MPVCoreEvent {
    case ready(durationSeconds: Double)
    case position(seconds: Double)
    case pauseChanged(paused: Bool)
    case tracks(json: String)
    case ended
    case error(message: String)
}

/// One owned libmpv instance rendering via gpu-next/MoltenVK into a
/// `MetalLayer` handed to mpv as `wid`.
///
/// Ownership and threading rules:
/// - Every mpv call happens on the private serial `queue`; the wakeup callback
///   only schedules a drain onto that queue and never touches mpv itself.
/// - `shutdown()` is deterministic and idempotent: it clears the wakeup
///   callback, then runs `mpv_terminate_destroy` on the queue and blocks until
///   it returns. libmpv guarantees no callback runs after terminate_destroy
///   returns, so the owner may release the core immediately afterwards.
/// - After shutdown every command and event is rejected; an
///   unretained-callback / teardown-less lifecycle is deliberately not
///   supported.
final class MPVCore {
    let id = UUID().uuidString
    /// Owned by this core: a fresh layer per core keeps mpv's `wid` pointing
    /// at a surface whose lifetime exactly matches the mpv handle.
    let videoLayer = MetalLayer()

    private var mpv: OpaquePointer?
    private let queue = DispatchQueue(label: "halo-mpv-core", qos: .userInitiated)
    // Everything below is only touched on `queue` after init — including
    // `onEvent`: a fresh core emits initial property observations the moment
    // the wakeup callback registers, so a main-thread assignment would race
    // `emit()` invoking the closure on the queue (caught by TSan as a SEGV in
    // the released closure's context during recreate cycles). Handlers are
    // installed via `setEventHandler`, which hops onto the queue; events that
    // fire before the handler lands are dropped, matching the presenter's
    // Idle guard for initial observation spam.
    private var onEvent: ((MPVCoreEvent) -> Void)?
    private var isShutdown = false
    private var eofNotified = false

    private enum Observed: UInt64 {
        case timePos = 1
        case pause = 2
        case eofReached = 3
        case trackList = 4
        case audioId = 5
        case subtitleId = 6
    }

    init(muted: Bool) {
        guard let handle = mpv_create() else {
            fatalError("mpv_create failed")
        }
        mpv = handle

        check(mpv_request_log_messages(handle, "warn"))
        // Diagnostics: full mpv log per core. On the simulator /tmp is
        // the host's /tmp, so the file is directly readable on the Mac.
        check(mpv_set_option_string(handle, "log-file", "/tmp/halo-mpv-\(id).log"))

        // Output path: layer pointer as wid, gpu-next over
        // Vulkan/MoltenVK, VideoToolbox hardware decode.
        var layerRef = Int64(Int(bitPattern: Unmanaged.passUnretained(videoLayer).toOpaque()))
        check(mpv_set_option(handle, "wid", MPV_FORMAT_INT64, &layerRef))
        check(mpv_set_option_string(handle, "vo", "gpu-next"))
        check(mpv_set_option_string(handle, "gpu-api", "vulkan"))
        check(mpv_set_option_string(handle, "gpu-context", "moltenvk"))
        check(mpv_set_option_string(handle, "hwdec", "videotoolbox"))
        check(mpv_set_option_string(handle, "keep-open", "yes"))
        // Match the desktop client's subtitle defaults so behavior carries over.
        check(mpv_set_option_string(handle, "sub-auto", "no"))
        check(mpv_set_option_string(handle, "slang", "eng,en"))
        check(mpv_set_option_string(handle, "subs-fallback", "yes"))
        if muted {
            check(mpv_set_option_string(handle, "mute", "yes"))
        }
        check(mpv_initialize(handle))

        mpv_observe_property(handle, Observed.timePos.rawValue, "time-pos", MPV_FORMAT_DOUBLE)
        mpv_observe_property(handle, Observed.pause.rawValue, "pause", MPV_FORMAT_FLAG)
        mpv_observe_property(handle, Observed.eofReached.rawValue, "eof-reached", MPV_FORMAT_FLAG)
        mpv_observe_property(handle, Observed.trackList.rawValue, "track-list", MPV_FORMAT_NONE)
        mpv_observe_property(handle, Observed.audioId.rawValue, "aid", MPV_FORMAT_NONE)
        mpv_observe_property(handle, Observed.subtitleId.rawValue, "sid", MPV_FORMAT_NONE)

        // The wakeup callback runs on an mpv-internal thread. It must only
        // schedule a drain; the unretained pointer is safe because shutdown()
        // blocks on mpv_terminate_destroy before the owner can release us.
        mpv_set_wakeup_callback(handle, { ctx in
            guard let ctx else { return }
            let core = Unmanaged<MPVCore>.fromOpaque(ctx).takeUnretainedValue()
            core.scheduleDrain()
        }, Unmanaged.passUnretained(self).toOpaque())
    }

    /// Installs the event handler on the core queue, serialized with `emit`.
    /// Never assign the handler from another thread directly.
    func setEventHandler(_ handler: @escaping (MPVCoreEvent) -> Void) {
        queue.async { [weak self] in
            guard let self, !self.isShutdown else { return }
            self.onEvent = handler
        }
    }

    // MARK: playback surface commands (all async onto the core queue)

    /// Loads start paused: deterministic for automation (no wall-clock playback
    /// racing UI assertions) and no surprise streaming of the 700 MB sample.
    func load(url: String) {
        perform { handle in
            self.eofNotified = false
            self.setFlag(handle, "pause", true)
            self.command(handle, "loadfile", [url, "replace"])
        }
    }

    func setPaused(_ paused: Bool) {
        perform { handle in self.setFlag(handle, "pause", paused) }
    }

    func seekTo(seconds: Double) {
        perform { handle in self.command(handle, "seek", [String(seconds), "absolute"]) }
    }

    func selectTrack(kind: String, id: String?) {
        perform { handle in
            mpv_set_property_string(handle, kind, id ?? "no")
        }
    }

    func setSubtitleDelay(seconds: Double) {
        perform { handle in mpv_set_property_string(handle, "sub-delay", String(seconds)) }
    }

    func setSubtitleScale(_ scale: Double) {
        perform { handle in mpv_set_property_string(handle, "sub-scale", String(scale)) }
    }

    func setSubtitleFont(_ font: String?) {
        // "sans-serif" is mpv's documented sub-font default.
        perform { handle in mpv_set_property_string(handle, "sub-font", font ?? "sans-serif") }
    }

    func addSubtitle(url: String) {
        perform { handle in self.command(handle, "sub-add", [url, "select"]) }
    }

    func setVideoDecodingSuspended(_ suspended: Bool) {
        // Background probe: drop the video track so GPU work
        // stops (mandatory in the background) while audio keeps playing.
        perform { handle in mpv_set_property_string(handle, "vid", suspended ? "no" : "auto") }
    }

    // MARK: shutdown

    /// Blocks until the mpv core is fully destroyed. Safe to call repeatedly.
    func shutdown() {
        queue.sync {
            guard let handle = mpv, !isShutdown else { return }
            isShutdown = true
            onEvent = nil
            // Stop new wakeups, then terminate. mpv_terminate_destroy joins
            // mpv's threads; when it returns no callback can run again.
            mpv_set_wakeup_callback(handle, nil, nil)
            mpv_unobserve_property(handle, Observed.timePos.rawValue)
            mpv_unobserve_property(handle, Observed.pause.rawValue)
            mpv_unobserve_property(handle, Observed.eofReached.rawValue)
            mpv_unobserve_property(handle, Observed.trackList.rawValue)
            mpv_unobserve_property(handle, Observed.audioId.rawValue)
            mpv_unobserve_property(handle, Observed.subtitleId.rawValue)
            mpv_terminate_destroy(handle)
            mpv = nil
        }
    }

    // MARK: internals (queue only)

    private func perform(_ body: @escaping (OpaquePointer) -> Void) {
        queue.async { [weak self] in
            guard let self, let handle = self.mpv, !self.isShutdown else { return }
            body(handle)
        }
    }

    private func scheduleDrain() {
        queue.async { [weak self] in self?.drainEvents() }
    }

    private func drainEvents() {
        while let handle = mpv, !isShutdown {
            guard let event = mpv_wait_event(handle, 0),
                  event.pointee.event_id != MPV_EVENT_NONE else { return }
            handleEvent(handle, event.pointee)
        }
    }

    private func handleEvent(_ handle: OpaquePointer, _ event: mpv_event) {
        switch event.event_id {
        case MPV_EVENT_FILE_LOADED:
            let duration = getDouble(handle, "duration")
            emit(.ready(durationSeconds: duration > 0 ? duration : -1))
            // Ready forces the presenter to Playing; reassert the real pause
            // state (loads start paused) right after it.
            emit(.pauseChanged(paused: getFlag(handle, "pause")))
            emitTracks(handle)
        case MPV_EVENT_END_FILE:
            guard let data = event.data else { return }
            let endFile = data.assumingMemoryBound(to: mpv_event_end_file.self).pointee
            if endFile.reason == MPV_END_FILE_REASON_ERROR {
                emit(.error(message: String(cString: mpv_error_string(endFile.error))))
            }
        case MPV_EVENT_PROPERTY_CHANGE:
            handlePropertyChange(handle, event)
        case MPV_EVENT_LOG_MESSAGE:
            if let data = event.data {
                let msg = data.assumingMemoryBound(to: mpv_event_log_message.self).pointee
                if let level = msg.level, let text = msg.text {
                    print("[mpv \(String(cString: level))] \(String(cString: text))", terminator: "")
                }
            }
        default:
            break
        }
    }

    private func handlePropertyChange(_ handle: OpaquePointer, _ event: mpv_event) {
        guard let data = event.data else { return }
        let property = data.assumingMemoryBound(to: mpv_event_property.self).pointee
        switch Observed(rawValue: event.reply_userdata) {
        case .timePos:
            guard property.format == MPV_FORMAT_DOUBLE, let value = property.data else { return }
            emit(.position(seconds: value.assumingMemoryBound(to: Double.self).pointee))
        case .pause:
            guard property.format == MPV_FORMAT_FLAG, let value = property.data else { return }
            emit(.pauseChanged(paused: value.assumingMemoryBound(to: Int32.self).pointee != 0))
        case .eofReached:
            guard property.format == MPV_FORMAT_FLAG, let value = property.data else { return }
            let reached = value.assumingMemoryBound(to: Int32.self).pointee != 0
            if reached && !eofNotified {
                eofNotified = true
                emit(.ended)
            } else if !reached {
                eofNotified = false
            }
        case .trackList, .audioId, .subtitleId:
            emitTracks(handle)
        case nil:
            break
        }
    }

    /// Normalizes mpv's `track-list` into the boundary's neutral JSON schema.
    private func emitTracks(_ handle: OpaquePointer) {
        guard let raw = mpv_get_property_string(handle, "track-list") else { return }
        defer { mpv_free(raw) }
        let json = String(cString: raw)
        guard let data = json.data(using: .utf8),
              let entries = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return
        }

        var audio: [[String: Any]] = []
        var subtitles: [[String: Any]] = []
        var selectedAudioId: String?
        var selectedSubtitleId: String?
        for entry in entries {
            guard let type = entry["type"] as? String, let id = entry["id"] as? Int else { continue }
            let language = entry["lang"] as? String
            let title = entry["title"] as? String
            var track: [String: Any] = [
                "id": String(id),
                "label": title ?? language ?? "Track \(id)",
            ]
            if let language { track["language"] = language }
            let selected = entry["selected"] as? Bool ?? false
            if type == "audio" {
                audio.append(track)
                if selected { selectedAudioId = String(id) }
            } else if type == "sub" {
                subtitles.append(track)
                if selected { selectedSubtitleId = String(id) }
            }
        }

        var document: [String: Any] = ["audio": audio, "subtitles": subtitles]
        if let selectedAudioId { document["selectedAudioId"] = selectedAudioId }
        if let selectedSubtitleId { document["selectedSubtitleId"] = selectedSubtitleId }
        guard let normalized = try? JSONSerialization.data(withJSONObject: document),
              let normalizedJson = String(data: normalized, encoding: .utf8) else {
            return
        }
        emit(.tracks(json: normalizedJson))
    }

    private func emit(_ event: MPVCoreEvent) {
        guard !isShutdown else { return }
        onEvent?(event)
    }

    // MARK: mpv helpers (queue only)

    private func command(_ handle: OpaquePointer, _ name: String, _ args: [String]) {
        var cargs: [UnsafePointer<CChar>?] = ([name] + args).map { UnsafePointer(strdup($0)) }
        cargs.append(nil)
        defer { cargs.compactMap { $0 }.forEach { free(UnsafeMutablePointer(mutating: $0)) } }
        check(mpv_command(handle, &cargs))
    }

    private func setFlag(_ handle: OpaquePointer, _ name: String, _ value: Bool) {
        var flag: Int32 = value ? 1 : 0
        mpv_set_property(handle, name, MPV_FORMAT_FLAG, &flag)
    }

    private func getFlag(_ handle: OpaquePointer, _ name: String) -> Bool {
        var flag: Int32 = 0
        mpv_get_property(handle, name, MPV_FORMAT_FLAG, &flag)
        return flag != 0
    }

    private func getDouble(_ handle: OpaquePointer, _ name: String) -> Double {
        var value = Double(0)
        mpv_get_property(handle, name, MPV_FORMAT_DOUBLE, &value)
        return value
    }

    private func check(_ status: CInt) {
        if status < 0 {
            print("mpv error: \(String(cString: mpv_error_string(status)))")
        }
    }
}
