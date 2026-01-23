import XCTest
import HaishinKit
@testable import flutter_audio_streaming

class InterruptionTests: XCTestCase {
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
        interruptionManager = InterruptionManagerImpl(config: InterruptionConfig(phoneCallTimeout: 2.0, networkTimeout: 2.0)) // Short timeouts for tests
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

    // MARK: - Test Cases

    func testNetworkInterruptionFlow() {
        // 1. GIVEN: Streaming is active
        _ = stateMachine.transitionTo(.connecting)
        _ = stateMachine.transitionTo(.streaming)
        
        // 2. WHEN: Network is lost
        networkMonitor.simulateNetworkLost()
        
        // 3. THEN: Should be in INTERRUPTED state
        XCTAssertEqual(stateMachine.currentState, .interrupted)
        
        // 4. WHEN: Network becomes available
        networkMonitor.simulateNetworkAvailable()
        
        // 5. THEN: Should transition to RECONNECTING
        XCTAssertEqual(stateMachine.currentState, .reconnecting)
    }

    func testProactiveRecoveryWhenNetworkAlreadyAvailable() {
        // 1. GIVEN: Streaming is active
        _ = stateMachine.transitionTo(.connecting)
        _ = stateMachine.transitionTo(.streaming)
        
        // 2. WHEN: Connection fails but network monitor thinks it's still available (SKIP logic)
        networkMonitor.isNetworkAvailable = true
        
        // Simulate rtmp status Connection Closed (The fluke)
        rtmpService.simulateStatus(code: RTMPConnection.Code.connectClosed.rawValue)
        
        // 3. THEN: Should immediately enter INTERRUPTED
        XCTAssertEqual(stateMachine.currentState, .interrupted)
        
        // 4. THEN: Proactive recovery should trigger reconnection after 1s
        let expectation = XCTestExpectation(description: "Proactive recovery triggers reconnection")
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            if self.stateMachine.currentState == .reconnecting {
                expectation.fulfill()
            }
        }
        wait(for: [expectation], timeout: 2.0)
    }

    func testPhoneCallInterruption() {
        // 1. GIVEN: Streaming is active
        _ = stateMachine.transitionTo(.connecting)
        _ = stateMachine.transitionTo(.streaming)
        
        // 2. WHEN: Phone call starts
        phoneMonitor.simulateCallStart()
        
        // 3. THEN: Should be in INTERRUPTED state
        XCTAssertEqual(stateMachine.currentState, .interrupted)
        XCTAssertEqual(interruptionManager.currentSource, .phoneCall)
        
        // 4. WHEN: Phone call ends
        phoneMonitor.simulateCallEnd()
    }
}
