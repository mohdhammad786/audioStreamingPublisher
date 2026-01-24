import Foundation
import AVFoundation
import UIKit

// MARK: - InterruptionManagerDelegate
extension AudioStreaming: InterruptionManagerDelegate {
    public func interruptionTimedOut(source: InterruptionSource) {
        print("⏱️ Interruption timeout for \(source)")
        DiagnosticsStore.append("interruptionTimedOut source=\(source) streamState=\(stateMachine.currentState.rawValue)")

        guard stateMachine.currentState == .interrupted else {
            print("⚠️ Timeout but not in interrupted state")
            DiagnosticsStore.append("timeout ignored because not interrupted")
            return
        }

        print("❌ Interruption timed out - terminating stream")
        DiagnosticsStore.append("timeout -> transition failed and close")
        _ = stateMachine.transitionTo(.failed)
        sendEvent(event: "rtmp_stopped", message: "Stream stopped due to prolonged interruption")
        
        rtmpService.close()
        stateLock.lock()
        streamingContext.clear()
        stateLock.unlock()
    }
}
