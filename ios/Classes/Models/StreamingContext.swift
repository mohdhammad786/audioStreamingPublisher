import Foundation

struct StreamingContext {
    private static let defaults = UserDefaults.standard
    private static let keyIsStreamingRequested = "com.resideo.flutter_audio_streaming.isStreamingRequested"
    private static let keyConnectUrl = "com.resideo.flutter_audio_streaming.connectUrl"
    private static let keyStreamName = "com.resideo.flutter_audio_streaming.streamName"
    private static let keySavedAt = "com.resideo.flutter_audio_streaming.savedAt"

    // Current Active Config
    var url: String?
    var name: String?
    
    // Saved State for Reconnection
    var savedUrl: String?
    var savedName: String?
    var reconnectionSource: InterruptionSource = .none
    var requiresRtmpReinitialize: Bool = false
    
    mutating func clear() {
        url = nil
        name = nil
        savedUrl = nil
        savedName = nil
        reconnectionSource = .none
        requiresRtmpReinitialize = false
    }
    
    mutating func saveCurrent(source: InterruptionSource) {
        savedUrl = url
        savedName = name
        reconnectionSource = source
    }
    
    mutating func restore() -> (url: String?, name: String?) {
        return (savedUrl, savedName)
    }
}

extension StreamingContext {
    static func persistStreamingRequested(connectUrl: String?, streamName: String?) {
        defaults.set(true, forKey: keyIsStreamingRequested)
        defaults.set(connectUrl, forKey: keyConnectUrl)
        defaults.set(streamName, forKey: keyStreamName)
        defaults.set(Date().timeIntervalSince1970, forKey: keySavedAt)
    }

    static func clearPersistedStreamingRequest() {
        defaults.removeObject(forKey: keyIsStreamingRequested)
        defaults.removeObject(forKey: keyConnectUrl)
        defaults.removeObject(forKey: keyStreamName)
        defaults.removeObject(forKey: keySavedAt)
        clearPersistedInterruption()
    }

    static func loadPersistedStreamFullUrl(maxAgeSeconds: TimeInterval) -> String? {
        guard defaults.bool(forKey: keyIsStreamingRequested) else { return nil }

        let savedAt = defaults.double(forKey: keySavedAt)
        if savedAt > 0 {
            let age = Date().timeIntervalSince1970 - savedAt
            if age > maxAgeSeconds {
                clearPersistedStreamingRequest()
                return nil
            }
        }

        guard let connectUrl = defaults.string(forKey: keyConnectUrl),
              let streamName = defaults.string(forKey: keyStreamName),
              !connectUrl.isEmpty,
              !streamName.isEmpty else {
            return nil
        }

        let trimmed = connectUrl.hasSuffix("/") ? String(connectUrl.dropLast()) : connectUrl
        return "\(trimmed)/\(streamName)"
    }

    private static let keyInterruptionStartedAt = "com.resideo.flutter_audio_streaming.interruptionStartedAt"
    private static let keyInterruptionSource = "com.resideo.flutter_audio_streaming.interruptionSource"

    static func persistInterruptionBegan(source: InterruptionSource) {
        let raw: String
        switch source {
        case .phoneCall: raw = "phoneCall"
        case .network: raw = "network"
        case .systemResource: raw = "systemResource"
        case .none: return
        }

        defaults.set(Date().timeIntervalSince1970, forKey: keyInterruptionStartedAt)
        defaults.set(raw, forKey: keyInterruptionSource)
    }

    static func clearPersistedInterruption() {
        defaults.removeObject(forKey: keyInterruptionStartedAt)
        defaults.removeObject(forKey: keyInterruptionSource)
    }

    static func loadPersistedInterruptionAgeSeconds() -> TimeInterval? {
        let startedAt = defaults.double(forKey: keyInterruptionStartedAt)
        if startedAt <= 0 { return nil }
        return Date().timeIntervalSince1970 - startedAt
    }

    static func loadPersistedInterruptionSourceRaw() -> String? {
        return defaults.string(forKey: keyInterruptionSource)
    }
}

struct DiagnosticsStore {
    private static let defaults = UserDefaults.standard

    private static let keySessionId = "com.resideo.flutter_audio_streaming.diagnostics.sessionId"
    private static let keySessionStartedAt = "com.resideo.flutter_audio_streaming.diagnostics.sessionStartedAt"
    private static let keyLastHeartbeatAt = "com.resideo.flutter_audio_streaming.diagnostics.lastHeartbeatAt"
    private static let keyLastGracefulEndAt = "com.resideo.flutter_audio_streaming.diagnostics.lastGracefulEndAt"
    private static let keyLastKnownState = "com.resideo.flutter_audio_streaming.diagnostics.lastKnownState"
    private static let keyLogLines = "com.resideo.flutter_audio_streaming.diagnostics.logLines"

    static func beginSession() -> [String: Any] {
        let now = Date().timeIntervalSince1970
        let prevSessionId = defaults.string(forKey: keySessionId)
        let prevStartedAt = defaults.double(forKey: keySessionStartedAt)
        let prevHeartbeatAt = defaults.double(forKey: keyLastHeartbeatAt)
        let prevGracefulEndAt = defaults.double(forKey: keyLastGracefulEndAt)
        let prevState = defaults.string(forKey: keyLastKnownState)

        let hadPreviousSession = (prevSessionId != nil && prevStartedAt > 0)
        let previousEndedGracefully = (prevGracefulEndAt > 0 && prevGracefulEndAt >= prevHeartbeatAt)
        let previousLikelyUnexpected = hadPreviousSession && !previousEndedGracefully && prevHeartbeatAt > 0 && (now - prevHeartbeatAt) < 3600

        let newSessionId = UUID().uuidString
        defaults.set(newSessionId, forKey: keySessionId)
        defaults.set(now, forKey: keySessionStartedAt)
        defaults.set(now, forKey: keyLastHeartbeatAt)
        defaults.removeObject(forKey: keyLastGracefulEndAt)

        let info: [String: Any] = [
            "previousSessionId": prevSessionId as Any,
            "previousStartedAt": prevStartedAt,
            "previousLastHeartbeatAt": prevHeartbeatAt,
            "previousLastGracefulEndAt": prevGracefulEndAt,
            "previousLastKnownState": prevState as Any,
            "previousLikelyUnexpectedTermination": previousLikelyUnexpected,
            "currentSessionId": newSessionId,
            "currentSessionStartedAt": now
        ]
        return info
    }

    static func markGracefulEnd() {
        let now = Date().timeIntervalSince1970
        defaults.set(now, forKey: keyLastGracefulEndAt)
        defaults.set(now, forKey: keyLastHeartbeatAt)
    }

    static func setLastKnownState(_ state: String) {
        defaults.set(state, forKey: keyLastKnownState)
        defaults.set(Date().timeIntervalSince1970, forKey: keyLastHeartbeatAt)
    }

    static func heartbeat() {
        defaults.set(Date().timeIntervalSince1970, forKey: keyLastHeartbeatAt)
    }

    static func append(_ message: String) {
        let now = ISO8601DateFormatter().string(from: Date())
        let sessionId = defaults.string(forKey: keySessionId) ?? "unknown"
        let line = "\(now) | \(sessionId) | \(message)"

        let existing = (defaults.array(forKey: keyLogLines) as? [String]) ?? []
        var updated = existing
        updated.append(line)
        if updated.count > 300 {
            updated.removeFirst(updated.count - 300)
        }
        defaults.set(updated, forKey: keyLogLines)
        defaults.set(Date().timeIntervalSince1970, forKey: keyLastHeartbeatAt)
    }

    static func readLines() -> [String] {
        return (defaults.array(forKey: keyLogLines) as? [String]) ?? []
    }
}
