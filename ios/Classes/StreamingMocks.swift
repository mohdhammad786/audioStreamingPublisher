import Foundation
import HaishinKit
import AVFoundation

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
    private weak var delegate: PhoneCallMonitorDelegate?
    
    func startMonitoring() {}
    func stopMonitoring() {}
    
    func setDelegate(_ delegate: PhoneCallMonitorDelegate?) {
        self.delegate = delegate
    }
    
    // Test helper
    func simulateCallStart() {
        isPhoneCallActive = true
        delegate?.phoneCallDidBegin()
    }
    
    func simulateCallEnd() {
        isPhoneCallActive = false
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
    
    override func attachAudio(_ device: AVCaptureDevice?, automaticallyConfiguresApplicationAudioSession: Bool = true, onError: ((Error) -> Void)? = nil) {
        attachAudioCalled = true
    }
}
