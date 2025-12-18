import XCTest
@testable import flutter_audio_streaming

class InterruptionTests: XCTestCase {
    var audioStreaming: AudioStreaming!
    var stateMachine: StreamStateMachine!
    var networkMonitor: MockNetworkMonitor!
    var phoneMonitor: MockPhoneCallMonitor!
    var interruptionManager: InterruptionManager!
    var reconnectionManager: ReconnectionManager!
    var mockConnection: MockRTMPConnection!
    var mockStream: MockRTMPStream!
    var stateObserver: MockStreamStateObserver!

    override func setUp() {
        super.setUp()
        stateMachine = StreamStateMachineImpl()
        networkMonitor = MockNetworkMonitor()
        phoneMonitor = MockPhoneCallMonitor()
        interruptionManager = InterruptionManagerImpl(config: InterruptionConfig(phoneCallTimeout: 2.0, networkTimeout: 2.0)) // Short timeouts for tests
        reconnectionManager = ReconnectionManagerImpl()
        mockConnection = MockRTMPConnection()
        mockStream = MockRTMPStream(connection: mockConnection)
        stateObserver = MockStreamStateObserver()
        
        stateMachine.addObserver(stateObserver)
        
        audioStreaming = AudioStreaming(
            stateMachine: stateMachine,
            phoneMonitor: phoneMonitor,
            networkMonitor: networkMonitor,
            interruptionManager: interruptionManager,
            reconnectionManager: reconnectionManager,
            rtmpConnection: mockConnection,
            rtmpStream: mockStream
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
        let notification = Notification(name: .rtmpStatus, object: mockConnection, userInfo: [
            "data": ["code": RTMPConnection.Code.connectClosed.rawValue]
        ])
        NotificationCenter.default.post(notification)
        
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
        
        // 5. THEN: Should transition to RECONNECTING
        XCTAssertEqual(stateMachine.currentState, .reconnecting)
    }

    func testNetworkLossDuringPhoneCall() {
        // 1. GIVEN: Streaming is active and phone call starts
        _ = stateMachine.transitionTo(.streaming)
        phoneMonitor.simulateCallStart()
        
        // 2. WHEN: Network is lost during the call
        networkMonitor.simulateNetworkLost()
        
        // 3. THEN: Should remain in phoneCall source but set network loss flag
        XCTAssertEqual(interruptionManager.currentSource, .phoneCall)
        XCTAssertTrue(interruptionManager.hasNetworkLossDuringPhoneCall)
        
        // 4. WHEN: Phone call ends but network STILL unavailable
        phoneMonitor.simulateCallEnd()
        
        // 5. THEN: Should switch to network interruption
        XCTAssertEqual(interruptionManager.currentSource, .network)
        XCTAssertEqual(stateMachine.currentState, .interrupted)
        
        // 6. WHEN: Network finally becomes available
        networkMonitor.simulateNetworkAvailable()
        
        // 7. THEN: Should reconnect
        XCTAssertEqual(stateMachine.currentState, .reconnecting)
    }

    func testInterruptionTimeout() {
        // 1. GIVEN: Stream is interrupted
        _ = stateMachine.transitionTo(.streaming)
        networkMonitor.simulateNetworkLost()
        XCTAssertEqual(stateMachine.currentState, .interrupted)
        
        // 2. WHEN: Timeout period elapses (2.0s in this test)
        let expectation = XCTestExpectation(description: "Interruption times out")
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
            if self.stateMachine.currentState == .failed {
                expectation.fulfill()
            }
        }
        
        // 3. THEN: Should transition to FAILED
        wait(for: [expectation], timeout: 3.0)
    }
}
