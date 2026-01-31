import XCTest
import HaishinKit
@testable import flutter_audio_streaming

class TimeoutStopTests: XCTestCase {
    var audioStreaming: AudioStreaming!
    var stateMachine: StreamStateMachine!
    var networkMonitor: MockNetworkMonitor!
    var phoneMonitor: MockPhoneCallMonitor!
    var interruptionManager: InterruptionManager!
    var reconnectionManager: ReconnectionManager!
    var rtmpService: MockRtmpService!
    var audioSessionManager: MockAudioSessionManager!
    var eventEmitter: MockStreamEventEmitter!
    var notificationObserver: MockSystemNotificationObserver!
    var stateObserver: MockStreamStateObserver!

    override func setUp() {
        super.setUp()
        stateMachine = StreamStateMachineImpl()
        networkMonitor = MockNetworkMonitor()
        phoneMonitor = MockPhoneCallMonitor()
        interruptionManager = InterruptionManagerImpl(config: InterruptionConfig(phoneCallTimeout: 2.0, networkTimeout: 2.0))
        reconnectionManager = ReconnectionManagerImpl()
        rtmpService = MockRtmpService()
        audioSessionManager = MockAudioSessionManager()
        eventEmitter = MockStreamEventEmitter()
        notificationObserver = MockSystemNotificationObserver()
        stateObserver = MockStreamStateObserver()
        
        stateMachine.addObserver(stateObserver)
        
        audioStreaming = AudioStreaming(
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

    func testTimeoutExpiresTriggersRtmpStopped() {
        // 1. GIVEN: Stream interrupted by network
        _ = stateMachine.transitionTo(.connecting)
        _ = stateMachine.transitionTo(.streaming)
        networkMonitor.simulateNetworkLost()
        XCTAssertEqual(stateMachine.currentState, .interrupted)
        
        // 2. WHEN: Timeout expires (wait > 2.0s)
        let expectation = XCTestExpectation(description: "Timeout triggers FAILED state and rtmp_stopped")
        
        // Using a loop or observer to catch the transition
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
            if self.stateMachine.currentState == .failed {
                // Verify event was sent
                if self.eventEmitter.lastEvent == "rtmp_stopped" {
                    expectation.fulfill()
                }
            }
        }
        
        wait(for: [expectation], timeout: 3.5)
    }
    
    func testInterruptionDoesNotTriggerRtmpStopped() {
        // 1. GIVEN: Streaming
        _ = stateMachine.transitionTo(.streaming)
        
        // 2. WHEN: Interrupted
        networkMonitor.simulateNetworkLost()
        
        // 3. THEN: Should be INTERRUPTED, NOT stopped
        XCTAssertEqual(stateMachine.currentState, .interrupted)
        XCTAssertNotEqual(eventEmitter.lastEvent, "rtmp_stopped")
        XCTAssertEqual(eventEmitter.lastEvent, "audio_interrupted")
    }
    
    func testExplicitStopTriggersRtmpStopped() {
        // 1. GIVEN: Streaming
        _ = stateMachine.transitionTo(.streaming)
        
        // 2. WHEN: Explicit stop called
        audioStreaming.stopStreaming { _ in }
        
        // 3. THEN: Should send rtmp_stopped
        XCTAssertEqual(eventEmitter.lastEvent, "rtmp_stopped")
        XCTAssertEqual(stateMachine.currentState, .idle)
    }
}
