import Foundation

extension AudioStreaming: ReconnectionManagerDelegate {
    public func reconnectionWillBegin(url: String, streamName: String) {
        print("🔄 Reconnection starting: \(url)/\(streamName)")
        sendEvent(event: "reconnecting", message: "Attempting to reconnect...")
    }

    public func reconnectionDidSucceed() {
        print("✅ Reconnection succeeded")
        // Logic handled in handleConnectionSuccess via RtmpServiceDelegate
    }

    public func reconnectionDidFail(error: String) {
        print("❌ Reconnection failed permanently: \(error)")
        _ = stateMachine.transitionTo(.failed)
        sendEvent(event: "error", message: "Reconnection failed: \(error)")
        
        // Ensure cleanup
        rtmpService.close()
        stateLock.lock()
        streamingContext.clear()
        stateLock.unlock()
    }

    public func reconnectionRetrying(attempt: Int, delay: TimeInterval) {
        print("⏳ Retry attempt \(attempt) in \(delay)s")
        eventEmitter.sendEvent(event: "reconnect_attempt", message: "Retrying connection...", details: ["attempt": attempt, "delay": delay])
    }
}
