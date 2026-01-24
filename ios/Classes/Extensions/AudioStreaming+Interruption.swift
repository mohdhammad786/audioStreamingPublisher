import Foundation
import AVFoundation
import UIKit

// MARK: - InterruptionManagerDelegate
extension AudioStreaming: InterruptionManagerDelegate {
    public func interruptionTimedOut(source: InterruptionSource) {
        print("⏱️ Interruption timeout for \(source)")

        guard stateMachine.currentState == .interrupted else {
            print("⚠️ Timeout but not in interrupted state")
            return
        }

        print("❌ Interruption timed out - terminating stream")
        _ = stateMachine.transitionTo(.failed)
        sendEvent(event: "rtmp_stopped", message: "Stream stopped due to prolonged interruption")
        
        rtmpService.close()
        stateLock.lock()
        streamingContext.clear()
        stateLock.unlock()
    }
}
