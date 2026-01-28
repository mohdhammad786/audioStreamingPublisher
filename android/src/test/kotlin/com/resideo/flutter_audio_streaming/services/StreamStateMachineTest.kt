package com.resideo.flutter_audio_streaming.services

import com.resideo.flutter_audio_streaming.models.StreamEvent
import com.resideo.flutter_audio_streaming.models.StreamState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito

class StreamStateMachineTest {

    private lateinit var stateMachine: StreamStateMachine
    private var lastOldState: StreamState? = null
    private var lastNewState: StreamState? = null
    private var lastEvent: StreamEvent? = null
    private var callbackCount = 0
    
    private lateinit var mockedLog: MockedStatic<android.util.Log>

    @Before
    fun setup() {
        mockedLog = mockStatic(android.util.Log::class.java)
        mockedLog.`when`<Int> { android.util.Log.i(anyString(), anyString()) }.thenReturn(0)
        mockedLog.`when`<Int> { android.util.Log.d(anyString(), anyString()) }.thenReturn(0)
        mockedLog.`when`<Int> { android.util.Log.e(anyString(), anyString()) }.thenReturn(0)
        mockedLog.`when`<Int> { android.util.Log.w(anyString(), anyString()) }.thenReturn(0)

        lastOldState = null
        lastNewState = null
        lastEvent = null
        callbackCount = 0
        
        stateMachine = StreamStateMachine { oldState, newState, event ->
            lastOldState = oldState
            lastNewState = newState
            lastEvent = event
            callbackCount++
        }
    }

    @After
    fun tearDown() {
        mockedLog.close()
    }

    @Test
    fun `initial state is IDLE`() {
        assertEquals(StreamState.IDLE, stateMachine.getCurrentState())
    }

    @Test
    fun `test valid start flow`() {
        // IDLE -> PREPARING
        assertTrue(stateMachine.transition(StreamEvent.StartRequested))
        assertEquals(StreamState.PREPARING, stateMachine.getCurrentState())
        assertEquals(StreamState.IDLE, lastOldState)
        assertEquals(StreamState.PREPARING, lastNewState)

        // PREPARING -> STREAMING
        assertTrue(stateMachine.transition(StreamEvent.StartSuccess))
        assertEquals(StreamState.STREAMING, stateMachine.getCurrentState())
        assertEquals(StreamState.PREPARING, lastOldState)
        assertEquals(StreamState.STREAMING, lastNewState)
    }

    @Test
    fun `test interruption flow`() {
        // Setup STREAMING state
        stateMachine.transition(StreamEvent.StartRequested)
        stateMachine.transition(StreamEvent.StartSuccess)
        
        // STREAMING -> INTERRUPTED
        assertTrue(stateMachine.transition(StreamEvent.InterruptionBegan))
        assertEquals(StreamState.INTERRUPTED, stateMachine.getCurrentState())

        // INTERRUPTED -> RECONNECTING
        assertTrue(stateMachine.transition(StreamEvent.ReconnectionStarted))
        assertEquals(StreamState.RECONNECTING, stateMachine.getCurrentState())

        // RECONNECTING -> STREAMING
        assertTrue(stateMachine.transition(StreamEvent.ReconnectionSuccess))
        assertEquals(StreamState.STREAMING, stateMachine.getCurrentState())
    }

    @Test
    fun `test direct reconnection from INTERRUPTED (Bug Fix Case)`() {
        // Setup INTERRUPTED state
        stateMachine.transition(StreamEvent.StartRequested)
        stateMachine.transition(StreamEvent.StartSuccess)
        stateMachine.transition(StreamEvent.InterruptionBegan)
        assertEquals(StreamState.INTERRUPTED, stateMachine.getCurrentState())

        // INTERRUPTED -> STREAMING (Simulating fast recovery before ReconnectionService runs)
        assertTrue("Should allow transition from INTERRUPTED to STREAMING on ReconnectionSuccess", 
            stateMachine.transition(StreamEvent.ReconnectionSuccess))
        
        assertEquals(StreamState.STREAMING, stateMachine.getCurrentState())
    }

    @Test
    fun `test invalid transitions rejected`() {
        // IDLE cannot handle StartSuccess
        assertFalse(stateMachine.transition(StreamEvent.StartSuccess))
        assertEquals(StreamState.IDLE, stateMachine.getCurrentState())
        assertEquals(0, callbackCount) // Callback should not fire for invalid

        // IDLE cannot handle InterruptionBegan
        assertFalse(stateMachine.transition(StreamEvent.InterruptionBegan))
        assertEquals(StreamState.IDLE, stateMachine.getCurrentState())
    }

    @Test
    fun `test failure flow`() {
        stateMachine.transition(StreamEvent.StartRequested)
        
        // PREPARING -> FAILED
        assertTrue(stateMachine.transition(StreamEvent.StartFailed))
        assertEquals(StreamState.FAILED, stateMachine.getCurrentState())

        // FAILED -> PREPARING (Retry)
        assertTrue(stateMachine.transition(StreamEvent.StartRequested))
        assertEquals(StreamState.PREPARING, stateMachine.getCurrentState())
    }

    @Test
    fun `test explicit stop`() {
        stateMachine.transition(StreamEvent.StartRequested)
        stateMachine.transition(StreamEvent.StartSuccess)
        
        // STREAMING -> IDLE
        assertTrue(stateMachine.transition(StreamEvent.ExplicitStop))
        assertEquals(StreamState.IDLE, stateMachine.getCurrentState())
    }
    
    @Test
    fun `test redundant transition ignored`() {
        stateMachine.transition(StreamEvent.StartRequested)
        assertEquals(StreamState.PREPARING, stateMachine.getCurrentState())
        val countAfterFirst = callbackCount
        
        // Try requesting start again while already preparing
        // Implementation detail: StreamStateMachine usually allows staying in same state but returns true?
        // Let's check implementation:
        // if (newState != null && newState != oldState) -> change
        // else if (newState == null) -> return false
        // return true // Same state
        
        // StartRequested from PREPARING:
        // Code: if (oldState == StreamState.IDLE || oldState == StreamState.FAILED) StreamState.PREPARING else null
        // So StartRequested from PREPARING returns NULL -> False
        
        assertFalse(stateMachine.transition(StreamEvent.StartRequested))
        assertEquals(countAfterFirst, callbackCount) // No new callback
    }
}