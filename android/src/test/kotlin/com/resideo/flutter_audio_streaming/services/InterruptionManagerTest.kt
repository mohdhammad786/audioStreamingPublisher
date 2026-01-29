package com.resideo.flutter_audio_streaming.services

import android.os.Handler
import com.resideo.flutter_audio_streaming.models.*
import com.resideo.flutter_audio_streaming.utils.DartMessenger
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockedStatic
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import org.mockito.ArgumentCaptor
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.mockito.kotlin.verify

class InterruptionManagerTest {

    private lateinit var streamingContext: StreamingContext
    @Mock lateinit var mockDartMessenger: DartMessenger
    @Mock lateinit var mockDelegate: InterruptionDelegate
    @Mock lateinit var mockHandler: Handler
    
    private lateinit var mockedLog: MockedStatic<android.util.Log>

    private lateinit var interruptionManager: InterruptionManager

    @Before
    fun setup() {
        mockedLog = mockStatic(android.util.Log::class.java)
        mockedLog.`when`<Int> { android.util.Log.i(anyString(), anyString()) }.thenReturn(0)
        mockedLog.`when`<Int> { android.util.Log.d(anyString(), anyString()) }.thenReturn(0)
        mockedLog.`when`<Int> { android.util.Log.e(anyString(), anyString()) }.thenReturn(0)
        mockedLog.`when`<Int> { android.util.Log.w(anyString(), anyString()) }.thenReturn(0)

        MockitoAnnotations.openMocks(this)
        streamingContext = StreamingContext()
        interruptionManager = InterruptionManager(streamingContext, mockDartMessenger, mockHandler)
        interruptionManager.delegate = mockDelegate
        
        // Default behavior
        whenever(mockDelegate.getStreamState()).thenReturn(StreamState.STREAMING)
        whenever(mockDelegate.transitionTo(any())).thenReturn(true)
    }

    @After
    fun tearDown() {
        mockedLog.close()
    }

    @Test
    fun `test handlePhoneInterruptionBegan adds interruption and stops stream`() {
        // Act
        interruptionManager.handlePhoneInterruptionBegan()

        // Assert
        verify(mockDelegate).transitionTo(StreamEvent.InterruptionBegan)
        verify(mockDelegate).stopStreamForInterruption()
        // Ensure NO event is sent directly from Manager (Duplicate prevention)
        verify(mockDartMessenger, never()).send(any(), any(), any())
        assert(streamingContext.currentInterruptionSource == InterruptionSource.PHONE_CALL)
    }

    @Test
    fun `test handlePhoneInterruptionEnded reconnects even if in background`() {
        // Arrange
        whenever(mockDelegate.getStreamState()).thenReturn(StreamState.INTERRUPTED)
        streamingContext.isInForeground = false
        
        // First add interruption to set up state
        interruptionManager.handlePhoneInterruptionBegan()
        
        // Act
        interruptionManager.handlePhoneInterruptionEnded()

        // Assert
        // Should reconnect immediately because Service handles background
        verify(mockDelegate).reconnectStream()
        assert(streamingContext.currentInterruptionSource == InterruptionSource.NONE)
    }

    @Test
    fun `test handleNetworkLost adds interruption`() {
        // Act
        interruptionManager.handleNetworkLost()

        // Assert
        verify(mockDelegate).transitionTo(StreamEvent.InterruptionBegan)
        assert(streamingContext.currentInterruptionSource == InterruptionSource.NETWORK)
    }

    @Test
    fun `test handleNetworkAvailable posts delayed runnable`() {
        // Arrange
        interruptionManager.handleNetworkLost()
        
        // Act
        interruptionManager.handleNetworkAvailable()

        // Assert
        // Verify postDelayed was called with a runnable and 1000ms delay
        verify(mockHandler).postDelayed(any(Runnable::class.java), eq(1000L))
    }
    
    @Test
    fun `test resume logic from background - no pending flag needed`() {
        // Simulate background phone interruption ending
        whenever(mockDelegate.getStreamState()).thenReturn(StreamState.INTERRUPTED)
        streamingContext.isInForeground = false
        interruptionManager.handlePhoneInterruptionBegan()
        interruptionManager.handlePhoneInterruptionEnded()
        
        // Since we removed the pending flag logic, we expect immediate reconnection
        verify(mockDelegate).reconnectStream()
        assert(!streamingContext.pendingReconnectOnResume)
    }

    @Test
    fun `test overlapping interruptions (phone then network) prevents resume until both clear`() {
        // Arrange
        whenever(mockDelegate.getStreamState()).thenReturn(StreamState.STREAMING)
        streamingContext.isInForeground = true

        // 1. Phone Interruption
        interruptionManager.handlePhoneInterruptionBegan()
        assert(streamingContext.currentInterruptionSource == InterruptionSource.PHONE_CALL)
        whenever(mockDelegate.getStreamState()).thenReturn(StreamState.INTERRUPTED)

        // 2. Network Lost
        interruptionManager.handleNetworkLost()
        // Priority check: Phone is usually higher priority or just first in list
        // In implementation: Phone check comes before Network check in 'when', so Phone is effective source.
        assert(streamingContext.currentInterruptionSource == InterruptionSource.PHONE_CALL)

        // 3. Phone Ended
        interruptionManager.handlePhoneInterruptionEnded()
        // Should still be interrupted by Network
        verify(mockDelegate, never()).reconnectStream()
        assert(streamingContext.currentInterruptionSource == InterruptionSource.NETWORK)

        // 4. Network Available (Simulate Runnable execution)
        val captor = ArgumentCaptor.forClass(Runnable::class.java)
        interruptionManager.handleNetworkAvailable()
        verify(mockHandler).postDelayed(captor.capture(), eq(1000L))
        captor.value.run()

        // Should reconnect now
        verify(mockDelegate).reconnectStream()
        assert(streamingContext.currentInterruptionSource == InterruptionSource.NONE)
    }

    @Test
    fun `test handleSystemInterruptionBegan adds interruption`() {
        interruptionManager.handleSystemInterruptionBegan()
        verify(mockDelegate).transitionTo(StreamEvent.InterruptionBegan)
        assert(streamingContext.currentInterruptionSource == InterruptionSource.SYSTEM_RESOURCE)
    }

    @Test
    fun `test interruption starts timeout`() {
        interruptionManager.handlePhoneInterruptionBegan()
        // Verify timeout runnable posted (30s for phone)
        verify(mockHandler).postDelayed(any(Runnable::class.java), eq(30000L))
    }

    @Test
    fun `test handleResumeFromInterruption does not resume if call is still active`() {
        // Arrange
        whenever(mockDelegate.getStreamState()).thenReturn(StreamState.INTERRUPTED)
        streamingContext.isInForeground = true
        
        // Interrupted by phone
        interruptionManager.handlePhoneInterruptionBegan()
        
        // Act: Resume called, but call is still active
        interruptionManager.handleResumeFromInterruption(true)
        
        // Assert: Should NOT clear phone interruption or reconnect
        verify(mockDelegate, never()).reconnectStream()
        assert(streamingContext.currentInterruptionSource == InterruptionSource.PHONE_CALL)
    }

    @Test
    fun `test triple interruption (Phone + System + Network) priority and resume`() {
        // Arrange
        whenever(mockDelegate.getStreamState()).thenReturn(StreamState.STREAMING)
        streamingContext.isInForeground = true

        // 1. Network Lost (Lowest Priority)
        interruptionManager.handleNetworkLost()
        assert(streamingContext.currentInterruptionSource == InterruptionSource.NETWORK)
        whenever(mockDelegate.getStreamState()).thenReturn(StreamState.INTERRUPTED)

        // 2. System Resource (Medium Priority)
        interruptionManager.handleSystemInterruptionBegan()
        assert(streamingContext.currentInterruptionSource == InterruptionSource.SYSTEM_RESOURCE)

        // 3. Phone Call (Highest Priority)
        interruptionManager.handlePhoneInterruptionBegan()
        assert(streamingContext.currentInterruptionSource == InterruptionSource.PHONE_CALL)

        // 4. Remove Phone Call -> Should fallback to System
        interruptionManager.handlePhoneInterruptionEnded()
        assert(streamingContext.currentInterruptionSource == InterruptionSource.SYSTEM_RESOURCE)
        verify(mockDelegate, never()).reconnectStream()

        // 5. Remove System -> Should fallback to Network
        interruptionManager.handleSystemInterruptionEnded()
        assert(streamingContext.currentInterruptionSource == InterruptionSource.NETWORK)
        verify(mockDelegate, never()).reconnectStream()

        // 6. Network Available -> Should Resume
        interruptionManager.handleNetworkAvailable()
        // Simulate delayed runnable execution
        val captor = ArgumentCaptor.forClass(Runnable::class.java)
        verify(mockHandler, atLeastOnce()).postDelayed(captor.capture(), eq(1000L))
        captor.value.run()

        assert(streamingContext.currentInterruptionSource == InterruptionSource.NONE)
        verify(mockDelegate).reconnectStream()
    }

    @Test
    fun `test duplicate interruption ignored`() {
        // Arrange
        whenever(mockDelegate.getStreamState()).thenReturn(StreamState.STREAMING)

        // Act
        interruptionManager.handlePhoneInterruptionBegan()
        
        // Update mock state to reflect interruption
        whenever(mockDelegate.getStreamState()).thenReturn(StreamState.INTERRUPTED)
        
        interruptionManager.handlePhoneInterruptionBegan() // Duplicate

        // Assert
        // Should only have transitioned once
        verify(mockDelegate, times(1)).transitionTo(StreamEvent.InterruptionBegan)
    }

    @Test
    fun `test stopStreamForInterruption skipped if already interrupted`() {
        // Arrange
        whenever(mockDelegate.getStreamState()).thenReturn(StreamState.INTERRUPTED) // Already interrupted

        // Act
        interruptionManager.handlePhoneInterruptionBegan()

        // Assert
        verify(mockDelegate, never()).stopStreamForInterruption()
        // But source should still be updated
        assert(streamingContext.currentInterruptionSource == InterruptionSource.PHONE_CALL)
    }

    @Test
    fun `test timeout expiry triggers failure and clears stack`() {
        // Arrange
        whenever(mockDelegate.getStreamState()).thenReturn(StreamState.STREAMING)
        
        // Act: Start interruption
        interruptionManager.handlePhoneInterruptionBegan()
        
        // Capture timeout runnable
        val captor = ArgumentCaptor.forClass(Runnable::class.java)
        verify(mockHandler).postDelayed(captor.capture(), eq(30000L))
        
        // Simulate Timeout Expiry
        captor.value.run()
        
        // Assert
        verify(mockDelegate).transitionTo(StreamEvent.TimeoutExpired)  // Changed from ReconnectionFailed
        assert(streamingContext.lastError == "Stream stopped due to prolonged interruption")
        
        // Verify stack cleared
        // Since interruptions are private, we check effective source
        assert(streamingContext.currentInterruptionSource == InterruptionSource.NONE)
    }

    @Test
    fun `test timer preserved when switching sources`() {
        // Arrange
        whenever(mockDelegate.getStreamState()).thenReturn(StreamState.STREAMING)
        
        // 1. Start Network Interruption (Starts 30s timer)
        interruptionManager.handleNetworkLost()
        verify(mockHandler, times(1)).postDelayed(any(Runnable::class.java), eq(30000L))
        
        // 2. Add Phone Interruption (Higher priority, also has 30s timer)
        // Should NOT start a new 30s timer because one is already running
        interruptionManager.handlePhoneInterruptionBegan()
        
        // Verify 30s timer was NOT called again (total times still 1)
        // Note: 500ms postDelayed calls for safety flag reset are separate
        verify(mockHandler, times(1)).postDelayed(any(Runnable::class.java), eq(30000L))
    }
}
