import Foundation

class DependencyFactory {
    
    func createAudioStreaming() -> AudioStreaming {
        let stateMachine = StreamStateMachineImpl()
        let phoneMonitor = PhoneCallMonitorImpl()
        let networkMonitor = NetworkMonitorImpl()
        let interruptionManager = InterruptionManagerImpl()
        let reconnectionManager = ReconnectionManagerImpl()
        let audioSessionManager = AudioSessionManager()
        let eventEmitter = StreamEventEmitter()
        let notificationObserver = SystemNotificationObserver()
        let rtmpService = RtmpService()
        
        return AudioStreaming(
            stateMachine: stateMachine,
            phoneMonitor: phoneMonitor,
            networkMonitor: networkMonitor,
            interruptionManager: interruptionManager,
            reconnectionManager: reconnectionManager,
            rtmpService: rtmpService,
            audioSessionManager: audioSessionManager,
            eventEmitter: eventEmitter,
            notificationObserver: notificationObserver
        )
    }
}
