import Foundation

extension AudioStreaming: SystemNotificationObserverDelegate {
    public func audioInterruptionBegan() {
        print("🔔 System Audio Interruption Began (AVAudioSession)")
        // Treat as phone call / high priority audio interruption
        handlePhoneInterruptionBegan()
    }

    public func audioInterruptionEnded(shouldResume: Bool) {
        print("🔔 System Audio Interruption Ended. Should Resume: \(shouldResume)")
        if shouldResume {
             handlePhoneInterruptionEnded()
        }
    }

    public func applicationDidBecomeActive() {
        print("📱 Application Did Become Active")
        
        guard stateMachine.currentState == .interrupted else { return }
        
        stateLock.lock()
        let currentSource = interruptionManager.currentSource
        stateLock.unlock()
        
        if currentSource == .phoneCall {
            print("📱 Active while interrupted by PhoneCall - forcing resume check")
            if phoneMonitor.isPhoneCallActive {
                print("📱 Actual phone call still active - ignoring")
                return
            }
            handlePhoneInterruptionEnded()
        } else if currentSource == .network {
            print("📱 Active while interrupted by Network - forcing resume check")
             if networkMonitor.isNetworkAvailable {
                 handleNetworkAvailable()
             }
        }
    }
}
