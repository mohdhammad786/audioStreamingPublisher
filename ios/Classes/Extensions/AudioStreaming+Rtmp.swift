import Foundation
import HaishinKit

// MARK: - RtmpServiceDelegate
extension AudioStreaming: RtmpServiceDelegate {
    func rtmpStatusReceived(code: String, description: String) {
        print("RTMP Status: \(code)")
        DiagnosticsStore.append("rtmp status code=\(code) streamState=\(stateMachine.currentState.rawValue)")

        if code == "NetConnection.Connect.Success" {
             handleConnectionSuccess()
        } else if code == "NetConnection.Connect.Failed" || code == "NetConnection.Connect.Closed" {
             handleConnectionFailure(description: code)
        }
    }

    func rtmpErrorReceived(code: String, description: String) {
        print("RTMP Error: \(description)")
        DiagnosticsStore.append("rtmp error desc=\(description) streamState=\(stateMachine.currentState.rawValue) networkAvailable=\(networkMonitor.isNetworkAvailable)")
        
        if isNetworkRelatedError(description: description) {
            let isOffline = !networkMonitor.isNetworkAvailable
            if isOffline {
                print("Network error confirmed offline - treating as network interruption")
                DiagnosticsStore.append("rtmp error treated as network interruption")
                handleNetworkLost()
                return
            }
        }

        handleConnectionFailure(description: description)
    }

    // MARK: - Connection Success/Failure
    internal func handleConnectionSuccess() {
        DiagnosticsStore.append("handleConnectionSuccess state=\(stateMachine.currentState.rawValue)")
        if stateMachine.currentState == .interrupted {
            print("Connection success arrived but we are INTERRUPTED - ignoring")
            DiagnosticsStore.append("connection success ignored because interrupted")
            rtmpService.close()
            return
        }

        guard stateMachine.currentState == .connecting || stateMachine.currentState == .reconnecting else {
            print("Connection success arrived but state is \(stateMachine.currentState.description) - closing zombie")
            DiagnosticsStore.append("connection success ignored invalid state=\(stateMachine.currentState.rawValue)")
            rtmpService.close()
            return
        }

        interruptionManager.clearAllInterruptions()
        let wasReconnecting = (stateMachine.currentState == .reconnecting)
        reconnectionManager.resetRetryCount()
        
        let streamName = streamingContext.savedName ?? streamingContext.name
        if let streamName = streamName {
             self.rtmpService.publish(streamName)
             DiagnosticsStore.append("rtmp publish name=\(streamName)")
        }

        _ = stateMachine.transitionTo(.streaming)

        if wasReconnecting {
            reconnectionManager.notifySuccess()
            DiagnosticsStore.append("reconnection success notify")

            stateLock.lock()
            let source = streamingContext.reconnectionSource
            streamingContext.reconnectionSource = .none
            streamingContext.savedUrl = nil
            streamingContext.savedName = nil
            stateLock.unlock()

            let event = source == .phoneCall ? "audio_resumed" : "network_resumed"
            let message = source == .phoneCall ?
                "Stream resumed after phone call" :
                "Stream resumed after network recovery"

            sendEvent(event: event, message: message)
            print("📢 Sent resume event: \(event)")
            DiagnosticsStore.append("resume event sent event=\(event) source=\(source)")
        }
    }

    internal func handleConnectionFailure(description: String) {
        print("❌ Connection failure: \(description)")
        DiagnosticsStore.append("handleConnectionFailure desc=\(description) state=\(stateMachine.currentState.rawValue)")

        if stateMachine.currentState == .streaming {
            print("Connection failed while streaming - treating as interruption")
            DiagnosticsStore.append("connection failure while streaming -> beginInterruption network")
            beginInterruption(source: .network)
            return
        }
        
        if stateMachine.currentState == .interrupted {
            print("Connection failed/closed while interrupted - ignoring (waiting for recovery)")
            DiagnosticsStore.append("connection failure ignored because interrupted")
            return
        }

        guard reconnectionManager.shouldRetry(error: description) else {
            print("Max retries reached - giving up")
            DiagnosticsStore.append("reconnection max retries reached")
            _ = stateMachine.transitionTo(.failed)
            sendEvent(event: "rtmp_stopped", message: "Connection failed after retries: \(description)")
            
            rtmpService.close()
            stateLock.lock()
            streamingContext.clear()
            stateLock.unlock()
            return
        }

        stateLock.lock()
        let retryUrl = streamingContext.url ?? ""
        stateLock.unlock()

        reconnectionManager.scheduleRetry(url: retryUrl) { [weak self] in
            guard let self = self else { return }

            if self.stateMachine.currentState == .interrupted {
                print("🔄 Transitioning from INTERRUPTED to RECONNECTING for retry")
                DiagnosticsStore.append("scheduleRetry moved interrupted->reconnecting")
                _ = self.stateMachine.transitionTo(.reconnecting)
            }

            guard self.stateMachine.currentState == .connecting || 
                  self.stateMachine.currentState == .reconnecting || 
                  self.stateMachine.currentState == .streaming else {
                print("Retry aborted - invalid state: \(self.stateMachine.currentState.description)")
                DiagnosticsStore.append("scheduleRetry aborted invalid state=\(self.stateMachine.currentState.rawValue)")
                return
            }

            self.stateLock.lock()
            let currentUrl = self.streamingContext.url ?? ""
            self.stateLock.unlock()
            
            self.rtmpService.connect(url: currentUrl)
            DiagnosticsStore.append("scheduleRetry connect url=\(currentUrl)")
        }
    }
    
    private func isNetworkRelatedError(description: String) -> Bool {
        let keywords = [
            "network", "timeout", "unreachable", "connection refused",
            "no route", "socket", "broken pipe", "failed to connect",
            "host", "resolve", "dns", "ioexception",
            "software", "abort", "connection reset"
        ]
        return keywords.contains { description.lowercased().contains($0) }
    }
}
