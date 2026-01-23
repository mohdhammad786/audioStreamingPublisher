import Foundation

extension AudioStreaming: StreamStateObserver {
    public func streamStateDidChange(from oldState: StreamState, to newState: StreamState) {
        print("📊 State Machine: \(oldState.description) -> \(newState.description)")
    }
}
