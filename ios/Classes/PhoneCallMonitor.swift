import Foundation
import CallKit
import AVFoundation

/// Protocol for phone call monitoring
public protocol PhoneCallMonitor {
    var isPhoneCallActive: Bool { get }
    func startMonitoring()
    func stopMonitoring()
    func setDelegate(_ delegate: PhoneCallMonitorDelegate?)
}

/// Delegate for phone call events
public protocol PhoneCallMonitorDelegate: AnyObject {
    func phoneCallDidBegin()
    func phoneCallDidEnd()
}

/// Implementation of phone call monitoring using CallKit
public class PhoneCallMonitorImpl: NSObject, PhoneCallMonitor {
    // MARK: - Properties
    private var callObserver: CXCallObserver?
    private var _hasActiveCall: Bool = false
    private weak var delegate: PhoneCallMonitorDelegate?
    private let queue = DispatchQueue.main

    public var isPhoneCallActive: Bool {
        // Primary method: Use CallKit observer state
        if _hasActiveCall {
            return true
        }

        // Fallback: Use CXCallObserver's current calls
        if let calls = callObserver?.calls, !calls.isEmpty {
            return true
        }

        // Secondary fallback: AVAudioSession checks
        let audioSession = AVAudioSession.sharedInstance()
        if audioSession.isOtherAudioPlaying ||
           audioSession.mode == .voiceChat ||
           audioSession.mode == .videoChat {
            return true
        }

        // Check if audio route is to receiver (indicates phone call)
        let currentRoute = audioSession.currentRoute
        for output in currentRoute.outputs {
            if output.portType == .builtInReceiver {
                return true
            }
        }

        // Check if other audio is in use
        if #available(iOS 8.0, *) {
            if audioSession.secondaryAudioShouldBeSilencedHint {
                return true
            }
        }

        return false
    }

    // MARK: - PhoneCallMonitor Implementation
    public func startMonitoring() {
        callObserver = CXCallObserver()
        callObserver?.setDelegate(self, queue: queue)
        print("📞 PhoneCallMonitor: Started monitoring")
    }

    public func stopMonitoring() {
        callObserver?.setDelegate(nil, queue: nil)
        callObserver = nil
        _hasActiveCall = false
        print("📞 PhoneCallMonitor: Stopped monitoring")
    }

    public func setDelegate(_ delegate: PhoneCallMonitorDelegate?) {
        self.delegate = delegate
    }
}

// MARK: - CXCallObserverDelegate
extension PhoneCallMonitorImpl: CXCallObserverDelegate {
    public func callObserver(_ callObserver: CXCallObserver, callChanged call: CXCall) {
        print("📞 PhoneCallMonitor: Call state changed - hasEnded:\(call.hasEnded) hasConnected:\(call.hasConnected)")

        if call.hasEnded {
            // Call ended
            _hasActiveCall = false
            delegate?.phoneCallDidEnd()
        } else if !call.hasEnded && !call.hasConnected {
            // Call is ringing (not yet connected, not ended)
            print("📞 PhoneCallMonitor: Phone RINGING detected - triggering interruption IMMEDIATELY")
            _hasActiveCall = true
            delegate?.phoneCallDidBegin()
        } else if call.hasConnected {
            // Call was picked up (already handled by ringing state)
            print("📞 PhoneCallMonitor: Call connected (already interrupted from ringing)")
            _hasActiveCall = true
        }
    }
}
