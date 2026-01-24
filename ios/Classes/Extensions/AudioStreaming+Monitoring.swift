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
        DiagnosticsStore.append("phoneCall began streamState=\(stateMachine.currentState.rawValue)")

        if stateMachine.currentState == .interrupted && interruptionManager.currentSource == .network {
            print("Switching from network to phone interruption")
            DiagnosticsStore.append("switch network->phoneCall while interrupted")
            interruptionManager.setCurrentSource(.phoneCall)
            interruptionManager.handleInterruptionBegan(source: .phoneCall)
            sendEvent(event: "audio_interrupted", message: "Phone call started during network interruption")
            return
        }

        if stateMachine.currentState == .reconnecting && interruptionManager.currentSource == .network {
            print("Phone call during network reconnection")
            DiagnosticsStore.append("phoneCall during network reconnection")
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
        DiagnosticsStore.append("systemResource began streamState=\(stateMachine.currentState.rawValue) currentSource=\(interruptionManager.currentSource)")

        if stateMachine.currentState == .interrupted && interruptionManager.currentSource == .network {
            print("Switching from network to system interruption")
            DiagnosticsStore.append("switch network->systemResource while interrupted")
            interruptionManager.setCurrentSource(.systemResource)
            interruptionManager.handleInterruptionBegan(source: .systemResource)
            sendEvent(event: "audio_interrupted", message: "Stream interrupted by system (e.g. Camera/Other App)")
            return
        }
        
        // Similar priority to phone call (overrides network)
        if stateMachine.currentState == .reconnecting && interruptionManager.currentSource == .network {
             print("System interruption during network reconnection")
             DiagnosticsStore.append("systemResource during network reconnection")
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
        DiagnosticsStore.append("phoneCall ended currentSource=\(interruptionManager.currentSource)")

        stateLock.lock()
        defer { stateLock.unlock() }

        guard interruptionManager.currentSource == .phoneCall else {
            print("⚠️ Phone interruption ended but current source is \(interruptionManager.currentSource)")
            DiagnosticsStore.append("phoneCall ended ignored due to currentSource mismatch")
            return
        }

        interruptionManager.handleInterruptionEnded(source: .phoneCall)
        
        if interruptionManager.currentSource == .network {
            print("🌐 Phone ended but network lost - switching to network interruption (Scenario 3)")
            DiagnosticsStore.append("phoneCall ended but network interruption remains")
            streamingContext.reconnectionSource = .network
            sendEvent(event: "network_interrupted", message: "Network unavailable after phone call ended")
            return
        }

        endInterruption(source: .phoneCall)
    }

    internal func handleSystemInterruptionEnded() {
        print("⚠️ System Resource Interruption Ended")
        DiagnosticsStore.append("systemResource ended currentSource=\(interruptionManager.currentSource)")

        stateLock.lock()
        defer { stateLock.unlock() }

        guard interruptionManager.currentSource == .systemResource else {
            print("⚠️ System interruption ended but current source is \(interruptionManager.currentSource)")
            DiagnosticsStore.append("systemResource ended ignored due to currentSource mismatch")
            return
        }

        interruptionManager.handleInterruptionEnded(source: .systemResource)
        
        if interruptionManager.currentSource == .network {
            print("🌐 System interruption ended but network lost - switching to network interruption (Scenario 3)")
            DiagnosticsStore.append("systemResource ended but network interruption remains")
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
        DiagnosticsStore.append("network lost streamState=\(stateMachine.currentState.rawValue) currentSource=\(interruptionManager.currentSource)")

        if interruptionManager.currentSource == .phoneCall {
            DiagnosticsStore.append("network lost during phoneCall")
            interruptionManager.setNetworkLostDuringPhoneCall(true)
            return
        }
        
        stateLock.lock()
        let hasSavedUrl = (streamingContext.savedUrl != nil)
        stateLock.unlock()

        if stateMachine.currentState == .reconnecting && hasSavedUrl {
            print("Network lost during reconnection")
            DiagnosticsStore.append("network lost during reconnecting")
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
        DiagnosticsStore.append("network available streamState=\(stateMachine.currentState.rawValue) currentSource=\(interruptionManager.currentSource)")

        guard stateMachine.currentState == .interrupted else {
            print("🌐 Network available but not in interrupted state (current: \(stateMachine.currentState.description))")
            DiagnosticsStore.append("network available ignored because not interrupted")
            return
        }

        stateLock.lock()
        defer { stateLock.unlock() }

        let currentSource = interruptionManager.currentSource

        if currentSource == .phoneCall {
            if interruptionManager.hasNetworkLossDuringPhoneCall {
                print("📞 Network came back during phone call - clearing flag, will reconnect after call")
                DiagnosticsStore.append("network available during phoneCall clearing flag")
                interruptionManager.setNetworkLostDuringPhoneCall(false)
            }
            return
        }

        guard currentSource == .network else {
            print("⚠️ Network available but current source is \(currentSource) - cannot reconnect")
            DiagnosticsStore.append("network available ignored due to currentSource mismatch")
            return
        }

        print("🌐 Ending network interruption - will reconnect")
        DiagnosticsStore.append("ending network interruption")
        
        interruptionManager.cancelTimer()
        
        // Reduced stabilization delay to 0.5s to improve responsiveness
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            guard let self = self else { return }
            
            guard self.networkMonitor.isNetworkAvailable else {
                print("🌐 Network became unavailable during stabilization delay - restarting interruption logic")
                DiagnosticsStore.append("network unstable during stabilization")
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
