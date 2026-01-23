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
        
        // Fix: Do NOT auto-resume if we were stopped/failed
        // Only resume if we were in an INTERRUPTED state
        guard stateMachine.currentState == .interrupted else {
             print("📱 Active but not in INTERRUPTED state (current: \(stateMachine.currentState.description)) - Ignoring auto-resume")
             return
        }
        
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
