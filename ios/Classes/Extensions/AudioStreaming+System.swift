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
        
        // Add safety delay to allow previous app (e.g. Camera) to fully release audio session resources
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
            guard let self = self else { return }
            
            // Re-check state after delay
            guard self.stateMachine.currentState == .interrupted else {
                print("📱 State changed during safety delay (current: \(self.stateMachine.currentState.description)) - aborting resume")
                return
            }

            self.stateLock.lock()
            let currentSource = self.interruptionManager.currentSource
            self.stateLock.unlock()
            
            if currentSource == .phoneCall {
                print("📱 Active while interrupted by PhoneCall - forcing resume check")
                if self.phoneMonitor.isPhoneCallActive {
                    print("📱 Actual phone call still active - ignoring")
                    return
                }
                self.handlePhoneInterruptionEnded()
            } else if currentSource == .network {
                print("📱 Active while interrupted by Network - forcing resume check")
                 if self.networkMonitor.isNetworkAvailable {
                     self.handleNetworkAvailable()
                 }
            }
        }
    }
}
