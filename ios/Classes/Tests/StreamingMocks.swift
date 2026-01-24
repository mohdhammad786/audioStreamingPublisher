import Foundation
import HaishinKit
import AVFoundation
import Flutter

// MARK: - Mock State Observer
class MockStreamStateObserver: StreamStateObserver {
    var stateChanges: [(from: StreamState, to: StreamState)] = []
    
    func streamStateDidChange(from oldState: StreamState, to newState: StreamState) {
        stateChanges.append((from: oldState, to: newState))
    }
}

// MARK: - Mock Network Monitor
class MockNetworkMonitor: NetworkMonitor {
    var isNetworkAvailable: Bool = true
    private weak var delegate: NetworkMonitorDelegate?
    
    func startMonitoring() {}
    func stopMonitoring() {}
    
    func setDelegate(_ delegate: NetworkMonitorDelegate?) {
        self.delegate = delegate
    }
    
    // Test helper
    func simulateNetworkLost() {
        isNetworkAvailable = false
        delegate?.networkBecameUnavailable()
    }
    
    func simulateNetworkAvailable() {
        isNetworkAvailable = true
        delegate?.networkBecameAvailable()
    }
}

// MARK: - Mock Phone Call Monitor
class MockPhoneCallMonitor: PhoneCallMonitor {
    var isPhoneCallActive: Bool = false
    var hasActiveCallKitCall: Bool = false
    var delegate: PhoneCallMonitorDelegate?
    var isMonitoring = false
    
    func startMonitoring() {
        isMonitoring = true
    }
    
    func stopMonitoring() {
        isMonitoring = false
    }
    
    func setDelegate(_ delegate: PhoneCallMonitorDelegate?) {
        self.delegate = delegate
    }
    
    // Test helpers
    func simulatePhoneCallBegan() {
        isPhoneCallActive = true
        hasActiveCallKitCall = true
        delegate?.phoneCallDidBegin()
    }
    
    func simulatePhoneCallEnded() {
        isPhoneCallActive = false
        hasActiveCallKitCall = false
        delegate?.phoneCallDidEnd()
    }
}

// MARK: - Mock RTMP objects
// Note: Inheriting from HaishinKit classes for convenience if possible
class MockRTMPConnection: RTMPConnection {
    var connectCalled = false
    var lastConnectedUrl: String?
    var closeCalled = false
    
    override func connect(_ command: String, arguments: Any?...) {
        connectCalled = true
        lastConnectedUrl = command
        // Connection success is usually async, but for unit tests we can fire it manually
    }
    
    override func close() {
        closeCalled = true
        super.close()
    }
    
    func simulateSuccess() {
        let notification = Notification(name: .rtmpStatus, object: self, userInfo: [
            "data": ["code": RTMPConnection.Code.connectSuccess.rawValue]
        ])
        NotificationCenter.default.post(notification)
    }
    
    func simulateFailure(code: String = RTMPConnection.Code.connectFailed.rawValue) {
        let notification = Notification(name: .rtmpStatus, object: self, userInfo: [
            "data": ["code": code]
        ])
        NotificationCenter.default.post(notification)
    }
}

class MockRTMPStream: RTMPStream {
    var publishCalled = false
    var lastPublishedName: String?
    var attachAudioCalled = false
    
    override func publish(_ name: String?, type: RTMPStream.HowToPublish = .live) {
        publishCalled = true
        lastPublishedName = name
    }
}

// MARK: - Mock Rtmp Service
class MockRtmpService: RtmpServiceProtocol {
    var delegate: RtmpServiceDelegate?
    
    var connectCalled = false
    var closeCalled = false
    var shutdownForInterruptionCalled = false
    var publishCalled = false
    var forceReleaseCalled = false
    var reinitializeCalled = false
    var lastUrl: String?
    
    func connect(url: String) {
        connectCalled = true
        lastUrl = url
    }
    
    func publish(_ name: String) {
        publishCalled = true
    }
    
    func close() {
        closeCalled = true
    }

    func shutdownForInterruption() {
        shutdownForInterruptionCalled = true
    }
    
    func mute() {}
    func unmute() {}
    func updateSettings(bitrate: Int?, sampleRate: Int?, isStereo: Bool?) {}
    
    func attachAudio(completion: @escaping (Bool, Error?) -> Void) {
        completion(true, nil)
    }
    
    func detachAudio(completion: (() -> Void)?) {
        completion?()
    }

    func forceRelease() {
        forceReleaseCalled = true
    }

    func reinitialize() {
        reinitializeCalled = true
    }
    
    func simulateStatus(code: String, description: String = "") {
        delegate?.rtmpStatusReceived(code: code, description: description)
    }
    
    func simulateError(code: String, description: String = "") {
        delegate?.rtmpErrorReceived(code: code, description: description)
    }
}

// MARK: - Mock Audio Session Manager
class MockAudioSessionManager: AudioSessionManagerProtocol {
    var configureCalled = false
    var deactivateCalled = false
    
    func configureAudioSession(completion: @escaping (Bool, Error?) -> Void) {
        configureCalled = true
        completion(true, nil)
    }
    
    func deactivateAudioSession() {
        deactivateCalled = true
    }
    
    func activateAudioSessionWithRetry(attempt: Int, maxAttempts: Int, completion: @escaping (Bool) -> Void) {
        completion(true)
    }
}

// MARK: - Mock Stream Event Emitter
class MockStreamEventEmitter: StreamEventEmitterProtocol {
    var lastEvent: String?
    var lastMessage: String?
    
    func setEventSink(_ sink: @escaping FlutterEventSink) {}
    
    func sendEvent(event: String, message: String, details: [String : Any]?) {
        lastEvent = event
        lastMessage = message
    }
}

// MARK: - Mock System Notification Observer
class MockSystemNotificationObserver: SystemNotificationObserverProtocol {
    var delegate: SystemNotificationObserverDelegate?
    var isObserving = false
    
    func startObserving() {
        isObserving = true
    }
    
    func stopObserving() {
        isObserving = false
    }
    
    // Test helpers
    func simulateInterruptionBegan() {
        delegate?.audioInterruptionBegan()
    }
    
    func simulateInterruptionEnded(shouldResume: Bool) {
        delegate?.audioInterruptionEnded(shouldResume: shouldResume)
    }
    
    func simulateAppDidBecomeActive() {
        delegate?.applicationDidBecomeActive()
    }
}
