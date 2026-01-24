import Foundation
import AVFoundation
import UIKit

protocol SystemNotificationObserverDelegate: AnyObject {
    func audioInterruptionBegan()
    func audioInterruptionEnded(shouldResume: Bool)
    func applicationDidBecomeActive()
    func applicationDidEnterBackground()
    func mediaServicesWereLost()
    func mediaServicesWereReset()
}

protocol SystemNotificationObserverProtocol: AnyObject {
    var delegate: SystemNotificationObserverDelegate? { get set }
    func startObserving()
    func stopObserving()
}

class SystemNotificationObserver: SystemNotificationObserverProtocol {
    weak var delegate: SystemNotificationObserverDelegate?
    
    func startObserving() {
        // Register for AVAudioSession interruptions
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleInterruption(_:)),
            name: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance()
        )
        
        // Register for App Lifecycle
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleApplicationDidBecomeActive),
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )
        
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleApplicationDidEnterBackground),
            name: UIApplication.didEnterBackgroundNotification,
            object: nil
        )
        
        // Media Services Lost/Reset
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleMediaServicesWereLost),
            name: AVAudioSession.mediaServicesWereLostNotification,
            object: AVAudioSession.sharedInstance()
        )
        
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleMediaServicesWereReset),
            name: AVAudioSession.mediaServicesWereResetNotification,
            object: AVAudioSession.sharedInstance()
        )
    }
    
    func stopObserving() {
        NotificationCenter.default.removeObserver(self)
    }
    
    // MARK: - Notification Handlers
    @objc private func handleInterruption(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else {
            return
        }

        print("🎧 AVAudioSession interruption: \(type == .began ? "BEGAN" : "ENDED")")

        switch type {
        case .began:
            delegate?.audioInterruptionBegan()
        case .ended:
            // CRITICAL FIX: The Camera app triggers an "Ended" interruption when it closes.
            // However, iOS often sets 'shouldResume' to false or sends no options when returning from Camera.
            // We must be careful NOT to resume if the user manually stopped the stream or if we are in a FAILED state.
            // The logic is now delegated entirely to the AudioStreaming class's state machine via 'audioInterruptionEnded'.
            // We just pass the raw signal.
            
            var shouldResume = false
            if let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt {
                let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                if options.contains(.shouldResume) {
                    shouldResume = true
                }
            }
            
            // Note: We REMOVED the "applicationState == .active" fallback here because it causes
            // false positives when returning from Camera. We let the Delegate decide based on State.
            
            delegate?.audioInterruptionEnded(shouldResume: shouldResume)
        @unknown default:
            break
        }
    }
    
    @objc private func handleApplicationDidBecomeActive() {
        delegate?.applicationDidBecomeActive()
    }
    
    @objc private func handleApplicationDidEnterBackground() {
        delegate?.applicationDidEnterBackground()
    }
    
    @objc private func handleMediaServicesWereLost(_ notification: Notification) {
        print("⚠️ AVAudioSession Media Services Were LOST")
        delegate?.mediaServicesWereLost()
    }
    
    @objc private func handleMediaServicesWereReset(_ notification: Notification) {
        print("🔄 AVAudioSession Media Services Were RESET")
        delegate?.mediaServicesWereReset()
    }
}
