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
}
