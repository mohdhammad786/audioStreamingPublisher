import Foundation
import Flutter

protocol StreamEventEmitterProtocol: AnyObject {
    func setEventSink(_ sink: @escaping FlutterEventSink)
    func sendEvent(event: String, message: String, details: [String: Any]?)
}

class StreamEventEmitter: StreamEventEmitterProtocol {
    private var eventSink: FlutterEventSink?
    private let queue = DispatchQueue.main
    
    func setEventSink(_ sink: @escaping FlutterEventSink) {
        self.eventSink = sink
    }
    
    func sendEvent(event: String, message: String, details: [String: Any]? = nil) {
        queue.async { [weak self] in
            guard let self = self else { return }
            var payload: [String: Any] = [
                "eventType": event,
                "errorDescription": message
            ]
            
            if let details = details {
                payload.merge(details) { (_, new) in new }
            }
            
            self.eventSink?(payload)
        }
    }
}
