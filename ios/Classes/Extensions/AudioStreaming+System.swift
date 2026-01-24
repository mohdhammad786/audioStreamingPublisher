import Foundation
import UIKit

extension AudioStreaming: SystemNotificationObserverDelegate {
    public func audioInterruptionBegan() {
        print("🔔 System Audio Interruption Began (AVAudioSession)")
        DiagnosticsStore.append("AVAudioSession interruption began appState=\(UIApplication.shared.applicationState.rawValue) streamState=\(stateMachine.currentState.rawValue)")
        
        // Distinguish between actual Phone Call and other System Interruptions (Camera, Alarm, Siri)
        if phoneMonitor.hasActiveCallKitCall {
             print("🔔 Identified as Phone Call (CallKit)")
             DiagnosticsStore.append("interruption classified phoneCall")
             handlePhoneInterruptionBegan()
        } else {
             print("🔔 Identified as System Resource Interruption (e.g. Camera/Other App)")
             DiagnosticsStore.append("interruption classified systemResource")
             handleSystemInterruptionBegan()
        }
    }
    
    public func mediaServicesWereLost() {
        print("☠️ Media Services Lost - Emergency Cleanup")
        DiagnosticsStore.append("mediaServicesWereLost streamState=\(stateMachine.currentState.rawValue)")
        
        // CRITICAL FIX: Use forceRelease to abandon dead HaishinKit objects.
        // This prevents the app from crashing by not touching invalid Core Audio pointers.
        rtmpService.forceRelease()
        
        // Ensure we are in interrupted state so recovery can happen on Reset
        if stateMachine.currentState == .streaming || stateMachine.currentState == .connecting || stateMachine.currentState == .reconnecting {
             beginInterruption(source: .systemResource)
        }
    }
    
    public func mediaServicesWereReset() {
        print("🔄 Media Services Reset - Re-initializing Audio Engine")
        DiagnosticsStore.append("mediaServicesWereReset streamState=\(stateMachine.currentState.rawValue)")
        
        // 1. Rebuild the HaishinKit stack (Connection/Stream)
        rtmpService.reinitialize()
        
        // 2. Re-configure Audio Session from scratch
        audioSessionManager.configureAudioSession { [weak self] success, error in
            guard let self = self else { return }
            
            if !success {
                print("❌ Failed to re-configure audio session after reset: \(String(describing: error))")
                DiagnosticsStore.append("audio session reconfigure failed after reset")
                // If session configuration fails, we move to failed state
                _ = self.stateMachine.transitionTo(.failed)
                return
            }
            
            // 3. Re-attach audio to the NEW RtmpStream
            self.rtmpService.attachAudio { [weak self] attached, error in
                guard let self = self else { return }
                
                if attached {
                    print("✅ Audio successfully re-attached after Media Services Reset")
                    DiagnosticsStore.append("audio re-attached after reset")
                    
                    // 4. Attempt to resume if we were interrupted
                    if self.stateMachine.currentState == .interrupted {
                         print("🔄 Triggering resumption from Media Services Reset...")
                         DiagnosticsStore.append("resume from mediaServicesWereReset")
                         if UIApplication.shared.applicationState == .active {
                             self.endInterruption(source: .systemResource)
                         } else {
                             DiagnosticsStore.append("mediaServicesWereReset resume deferred because app not active")
                         }
                    }
                } else {
                    print("❌ Failed to attach audio after reset: \(String(describing: error))")
                    DiagnosticsStore.append("audio attach failed after reset")
                    _ = self.stateMachine.transitionTo(.failed)
                }
            }
        }
    }

    public func audioInterruptionEnded(shouldResume: Bool) {
        print("🔔 System Audio Interruption Ended. Should Resume: \(shouldResume)")
        DiagnosticsStore.append("AVAudioSession interruption ended shouldResume=\(shouldResume) appState=\(UIApplication.shared.applicationState.rawValue) streamState=\(stateMachine.currentState.rawValue)")
        
        // CRITICAL FIX: Prevent background resumption which can kill the app.
        if UIApplication.shared.applicationState != .active {
            print("🔔 Ignoring interruption ended while not active (state: \(UIApplication.shared.applicationState.rawValue)) - waiting for DidBecomeActive")
            DiagnosticsStore.append("ignored interruption ended because app not active")
            return
        }

        if shouldResume {
             stateLock.lock()
             let currentSource = interruptionManager.currentSource
             stateLock.unlock()
             
             if currentSource == .phoneCall {
                DiagnosticsStore.append("resume path phoneCall")
                 handlePhoneInterruptionEnded()
             } else if currentSource == .systemResource {
                DiagnosticsStore.append("resume path systemResource")
                 handleSystemInterruptionEnded()
             }
        }
    }

    public func applicationDidBecomeActive() {
        print("📱 Application Did Become Active")
        DiagnosticsStore.append("UIApplication didBecomeActive streamState=\(stateMachine.currentState.rawValue)")
        
        // Resumption is now handled primarily by 'audioInterruptionEnded'
        // But we keep this as a safeguard for edge cases where the OS interruption ended logic
        // might have been missed or if we need to sync state.
        
        // Only resume if we were in an INTERRUPTED state
        guard stateMachine.currentState == .interrupted else {
             print("📱 Active but not in INTERRUPTED state (current: \(stateMachine.currentState.description)) - Ignoring auto-resume")
             DiagnosticsStore.append("didBecomeActive ignored because not interrupted")
             return
        }
        
        // Add safety delay to allow previous app (e.g. Camera) to fully release audio session resources
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
            guard let self = self else { return }
            
            // Re-check state after delay
            guard self.stateMachine.currentState == .interrupted else {
                print("📱 State changed during safety delay (current: \(self.stateMachine.currentState.description)) - aborting resume")
                DiagnosticsStore.append("didBecomeActive resume aborted due to state change")
                return
            }

            self.stateLock.lock()
            let currentSource = self.interruptionManager.currentSource
            self.stateLock.unlock()
            
            if currentSource == .phoneCall {
                print("📱 Active while interrupted by PhoneCall - forcing resume check")
                DiagnosticsStore.append("didBecomeActive forcing phoneCall resume check")
                if self.phoneMonitor.hasActiveCallKitCall {
                    print("📱 Actual phone call still active - ignoring")
                    DiagnosticsStore.append("didBecomeActive phoneCall still active")
                    return
                }
                self.handlePhoneInterruptionEnded()
            } else if currentSource == .systemResource {
                 print("📱 Active while interrupted by SystemResource - forcing resume check")
                 DiagnosticsStore.append("didBecomeActive forcing systemResource resume check")
                 self.handleSystemInterruptionEnded()
            } else if currentSource == .network {
                print("📱 Active while interrupted by Network - forcing resume check")
                 DiagnosticsStore.append("didBecomeActive forcing network resume check")
                 if self.networkMonitor.isNetworkAvailable {
                     self.handleNetworkAvailable()
                 }
            }
        }
    }

    public func applicationDidEnterBackground() {
        print("📱 Application Did Enter Background")
        DiagnosticsStore.append("UIApplication didEnterBackground streamState=\(stateMachine.currentState.rawValue)")
        
        // CRITICAL FIX: We do NOT force an interruption here anymore.
        // Reason: Audio Streaming apps are expected to continue in the background.
        // If the user opens another app that uses audio (like Camera), AVAudioSession
        // will send us a real 'audioInterruptionBegan' event, which we already handle.
        // By removing this, we fix the issue where minimizing the app kills the stream unnecessarily.
    }

    public func applicationWillResignActive() {
        print("📱 Application Will Resign Active")
        DiagnosticsStore.append("UIApplication willResignActive streamState=\(stateMachine.currentState.rawValue)")
    }

    public func applicationWillEnterForeground() {
        print("📱 Application Will Enter Foreground")
        DiagnosticsStore.append("UIApplication willEnterForeground streamState=\(stateMachine.currentState.rawValue)")
    }

    public func applicationWillTerminate() {
        print("📱 Application Will Terminate")
        DiagnosticsStore.append("UIApplication willTerminate streamState=\(stateMachine.currentState.rawValue)")
        DiagnosticsStore.markGracefulEnd()
    }

    public func applicationDidReceiveMemoryWarning() {
        print("📱 Application Did Receive Memory Warning")
        DiagnosticsStore.append("UIApplication didReceiveMemoryWarning streamState=\(stateMachine.currentState.rawValue) appState=\(UIApplication.shared.applicationState.rawValue)")

        if stateMachine.currentState == .streaming || stateMachine.currentState == .connecting || stateMachine.currentState == .reconnecting {
            beginInterruption(source: .systemResource)
        }
    }

    public func audioRouteChanged(reasonRawValue: UInt) {
        print("🎧 Audio Route Changed reason=\(reasonRawValue)")
        DiagnosticsStore.append("AVAudioSession routeChange reason=\(reasonRawValue) appState=\(UIApplication.shared.applicationState.rawValue) streamState=\(stateMachine.currentState.rawValue)")
    }
}
