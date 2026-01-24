import Foundation

// MARK: - PhoneCallMonitorDelegate
extension AudioStreaming: PhoneCallMonitorDelegate {
    public func phoneCallDidBegin() {
        handlePhoneInterruptionBegan()
    }

    public func phoneCallDidEnd() {
        handlePhoneInterruptionEnded()
    }

    internal func handlePhoneInterruptionBegan() {
        print("📞 Phone Call Interruption Began")

        if stateMachine.currentState == .interrupted && interruptionManager.currentSource == .network {
            print("Switching from network to phone interruption")
            interruptionManager.setCurrentSource(.phoneCall)
            interruptionManager.handleInterruptionBegan(source: .phoneCall)
            sendEvent(event: "audio_interrupted", message: "Phone call started during network interruption")
            return
        }

        if stateMachine.currentState == .reconnecting && interruptionManager.currentSource == .network {
            print("Phone call during network reconnection")
            rtmpService.close()
            _ = stateMachine.transitionTo(.interrupted)
            interruptionManager.setCurrentSource(.phoneCall)
            interruptionManager.handleInterruptionBegan(source: .phoneCall)
            sendEvent(event: "audio_interrupted", message: "Phone call interrupted reconnection")
            return
        }

        interruptionManager.setCurrentSource(.phoneCall)
        beginInterruption(source: .phoneCall)
    }

    internal func handleSystemInterruptionBegan() {
        print("⚠️ System Resource Interruption Began (Camera/Other)")

        if stateMachine.currentState == .interrupted && interruptionManager.currentSource == .network {
            print("Switching from network to system interruption")
            interruptionManager.setCurrentSource(.systemResource)
            interruptionManager.handleInterruptionBegan(source: .systemResource)
            sendEvent(event: "audio_interrupted", message: "Stream interrupted by system (e.g. Camera/Other App)")
            return
        }
        
        // Similar priority to phone call (overrides network)
        if stateMachine.currentState == .reconnecting && interruptionManager.currentSource == .network {
             print("System interruption during network reconnection")
             rtmpService.close()
             _ = stateMachine.transitionTo(.interrupted)
             interruptionManager.setCurrentSource(.systemResource)
             interruptionManager.handleInterruptionBegan(source: .systemResource)
             sendEvent(event: "audio_interrupted", message: "Stream interrupted by system during reconnection")
             return
        }

        interruptionManager.setCurrentSource(.systemResource)
        beginInterruption(source: .systemResource)
    }

    internal func handlePhoneInterruptionEnded() {
        print("📞 Phone Call Interruption Ended")

        stateLock.lock()
        defer { stateLock.unlock() }

        guard interruptionManager.currentSource == .phoneCall else {
            print("⚠️ Phone interruption ended but current source is \(interruptionManager.currentSource)")
            return
        }

        interruptionManager.handleInterruptionEnded(source: .phoneCall)
        
        if interruptionManager.currentSource == .network {
            print("🌐 Phone ended but network lost - switching to network interruption (Scenario 3)")
            streamingContext.reconnectionSource = .network
            sendEvent(event: "network_interrupted", message: "Network unavailable after phone call ended")
            return
        }

        endInterruption(source: .phoneCall)
    }

    internal func handleSystemInterruptionEnded() {
        print("⚠️ System Resource Interruption Ended")

        stateLock.lock()
        defer { stateLock.unlock() }

        guard interruptionManager.currentSource == .systemResource else {
            print("⚠️ System interruption ended but current source is \(interruptionManager.currentSource)")
            return
        }

        interruptionManager.handleInterruptionEnded(source: .systemResource)
        
        if interruptionManager.currentSource == .network {
            print("🌐 System interruption ended but network lost - switching to network interruption (Scenario 3)")
            streamingContext.reconnectionSource = .network
            sendEvent(event: "network_interrupted", message: "Network unavailable after system interruption")
            return
        }

        endInterruption(source: .systemResource)
    }
}

// MARK: - NetworkMonitorDelegate
extension AudioStreaming: NetworkMonitorDelegate {
    public func networkBecameUnavailable() {
        handleNetworkLost()
    }

    public func networkBecameAvailable() {
        handleNetworkAvailable()
    }

    internal func handleNetworkLost() {
        print("🌐 Network Lost")

        if interruptionManager.currentSource == .phoneCall {
            interruptionManager.setNetworkLostDuringPhoneCall(true)
            return
        }
        
        stateLock.lock()
        let hasSavedUrl = (streamingContext.savedUrl != nil)
        stateLock.unlock()

        if stateMachine.currentState == .reconnecting && hasSavedUrl {
            print("Network lost during reconnection")
            rtmpService.close()
            _ = stateMachine.transitionTo(.interrupted)
            interruptionManager.setCurrentSource(.network)
            interruptionManager.handleInterruptionBegan(source: .network)
            sendEvent(event: "network_interrupted", message: "Network lost during reconnection")
            return
        }

        guard stateMachine.currentState == .streaming || stateMachine.currentState == .connecting else {
            return
        }

        interruptionManager.setCurrentSource(.network)
        beginInterruption(source: .network)
    }

    internal func handleNetworkAvailable() {
        print("🌐 Network Available")

        guard stateMachine.currentState == .interrupted else {
            print("🌐 Network available but not in interrupted state (current: \(stateMachine.currentState.description))")
            return
        }

        stateLock.lock()
        defer { stateLock.unlock() }

        let currentSource = interruptionManager.currentSource

        if currentSource == .phoneCall {
            if interruptionManager.hasNetworkLossDuringPhoneCall {
                print("📞 Network came back during phone call - clearing flag, will reconnect after call")
                interruptionManager.setNetworkLostDuringPhoneCall(false)
            }
            return
        }

        guard currentSource == .network else {
            print("⚠️ Network available but current source is \(currentSource) - cannot reconnect")
            return
        }

        print("🌐 Ending network interruption - will reconnect")
        
        interruptionManager.cancelTimer()
        
        // Reduced stabilization delay to 0.5s to improve responsiveness
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            guard let self = self else { return }
            
            guard self.networkMonitor.isNetworkAvailable else {
                print("🌐 Network became unavailable during stabilization delay - restarting interruption logic")
                self.interruptionManager.handleInterruptionBegan(source: .network)
                return
            }
            
            guard self.stateMachine.currentState == .interrupted else {
                return
            }
            
            self.endInterruption(source: .network)
        }
    }
}
