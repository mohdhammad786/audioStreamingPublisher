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
    private let queue = DispatchQueue(label: "com.resideo.phonecallmonitor.queue")
    private let lock = NSLock()

    public override init() {}

    public var isPhoneCallActive: Bool {
        lock.lock()
        defer { lock.unlock() }
        
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
        lock.lock()
        // Improved logic: Count active calls correctly
        let activeCalls = callObserver.calls.filter { !$0.hasEnded }
        let wasActive = _hasActiveCall
        _hasActiveCall = !activeCalls.isEmpty
        lock.unlock()

        print("📞 CallKit: Call state changed - activeCount: \(activeCalls.count)")

        if wasActive && !activeCalls.isEmpty {
             // Still active - no change
             return
        }

        if !wasActive && !activeCalls.isEmpty {
            print("📞 CallKit: Phone interruption detected")
            delegate?.phoneCallDidBegin()
        } else if wasActive && activeCalls.isEmpty {
            print("📞 CallKit: Phone interruption ended")
            delegate?.phoneCallDidEnd()
        }
    }
}
