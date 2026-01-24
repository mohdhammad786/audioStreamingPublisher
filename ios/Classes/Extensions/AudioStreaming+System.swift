import Foundation
import UIKit

extension AudioStreaming: SystemNotificationObserverDelegate {
    public func audioInterruptionBegan() {
        print("🔔 System Audio Interruption Began (AVAudioSession)")
        
        // Distinguish between actual Phone Call and other System Interruptions (Camera, Alarm, Siri)
        if phoneMonitor.hasActiveCallKitCall {
             print("🔔 Identified as Phone Call (CallKit)")
             handlePhoneInterruptionBegan()
        } else {
             print("🔔 Identified as System Resource Interruption (e.g. Camera/Other App)")
             handleSystemInterruptionBegan()
        }
    }

    public func audioInterruptionEnded(shouldResume: Bool) {
        print("🔔 System Audio Interruption Ended. Should Resume: \(shouldResume)")
        
        // CRITICAL FIX: Prevent background resumption which can kill the app.
        if UIApplication.shared.applicationState != .active {
            print("🔔 Ignoring interruption ended while not active (state: \(UIApplication.shared.applicationState.rawValue)) - waiting for DidBecomeActive")
            return
        }

        if shouldResume {
             stateLock.lock()
             let currentSource = interruptionManager.currentSource
             stateLock.unlock()
             
             if currentSource == .phoneCall {
                 handlePhoneInterruptionEnded()
             } else if currentSource == .systemResource {
                 handleSystemInterruptionEnded()
             }
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
                if self.phoneMonitor.hasActiveCallKitCall {
                    print("📱 Actual phone call still active - ignoring")
                    return
                }
                self.handlePhoneInterruptionEnded()
            } else if currentSource == .systemResource {
                 print("📱 Active while interrupted by SystemResource - forcing resume check")
                 self.handleSystemInterruptionEnded()
            } else if currentSource == .network {
                print("📱 Active while interrupted by Network - forcing resume check")
                 if self.networkMonitor.isNetworkAvailable {
                     self.handleNetworkAvailable()
                 }
            }
        }
    }

    public func applicationDidEnterBackground() {
        print("📱 Application Did Enter Background")

        guard stateMachine.currentState == .streaming ||
                stateMachine.currentState == .connecting ||
                stateMachine.currentState == .reconnecting else {
            return
        }

        if interruptionManager.currentSource == .none {
            interruptionManager.setCurrentSource(.systemResource)
        }

        handleSystemInterruptionBegan()
    }
}
