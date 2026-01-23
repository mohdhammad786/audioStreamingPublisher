import Foundation
import AVFoundation
import UIKit

protocol SystemNotificationObserverDelegate: AnyObject {
    func audioInterruptionBegan()
    func audioInterruptionEnded(shouldResume: Bool)
    func applicationDidBecomeActive()
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
            var shouldResume = false
            if let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt {
                let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                if options.contains(.shouldResume) {
                    shouldResume = true
                } else {
                     // Fallback check
                     if UIApplication.shared.applicationState == .active {
                         shouldResume = true
                     }
                }
            }
            delegate?.audioInterruptionEnded(shouldResume: shouldResume)
        @unknown default:
            break
        }
    }
    
    @objc private func handleApplicationDidBecomeActive() {
        delegate?.applicationDidBecomeActive()
    }
}
