package com.resideo.flutter_audio_streaming.services

import com.resideo.flutter_audio_streaming.models.*
import com.resideo.flutter_audio_streaming.utils.DartMessenger
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class FlutterEventMapperTest {

    @Mock lateinit var mockDartMessenger: DartMessenger
    @Mock lateinit var mockInterruptionManager: InterruptionManager
    
    private lateinit var streamingContext: StreamingContext
    private lateinit var eventMapper: FlutterEventMapper

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        streamingContext = StreamingContext()
        eventMapper = FlutterEventMapper(mockDartMessenger, streamingContext, mockInterruptionManager)
    }

    @Test
    fun `test StartSuccess sends RTMP_STARTED`() {
        eventMapper.handleStateTransition(StreamState.PREPARING, StreamState.STREAMING, StreamEvent.StartSuccess)
        verify(mockDartMessenger).send(eq(DartMessenger.EventType.RTMP_STARTED), any())
    }

    @Test
    fun `test ReconnectionSuccess with Phone source sends AUDIO_RESUMED`() {
        streamingContext.reconnectionSource = InterruptionSource.PHONE_CALL
        
        eventMapper.handleStateTransition(StreamState.RECONNECTING, StreamState.STREAMING, StreamEvent.ReconnectionSuccess)
        
        verify(mockDartMessenger).send(eq(DartMessenger.EventType.AUDIO_RESUMED), any())
        assert(streamingContext.reconnectionSource == InterruptionSource.NONE) // Should clear source
    }

    @Test
    fun `test ReconnectionSuccess with Network source sends NETWORK_RESUMED`() {
        streamingContext.reconnectionSource = InterruptionSource.NETWORK
        
        eventMapper.handleStateTransition(StreamState.RECONNECTING, StreamState.STREAMING, StreamEvent.ReconnectionSuccess)
        
        verify(mockDartMessenger).send(eq(DartMessenger.EventType.NETWORK_RESUMED), any())
        assert(streamingContext.reconnectionSource == InterruptionSource.NONE)
    }

    @Test
    fun `test Interrupted by Phone sends AUDIO_INTERRUPTED`() {
        streamingContext.currentInterruptionSource = InterruptionSource.PHONE_CALL
        whenever(mockInterruptionManager.getRemainingInterruptionSeconds()).thenReturn(30)
        
        eventMapper.handleStateTransition(StreamState.STREAMING, StreamState.INTERRUPTED, StreamEvent.InterruptionBegan)
        
        verify(mockDartMessenger).send(eq(DartMessenger.EventType.AUDIO_INTERRUPTED), any(), any())
    }

    @Test
    fun `test Interrupted by Network sends NETWORK_INTERRUPTED`() {
        streamingContext.currentInterruptionSource = InterruptionSource.NETWORK
        
        eventMapper.handleStateTransition(StreamState.STREAMING, StreamState.INTERRUPTED, StreamEvent.InterruptionBegan)
        
        verify(mockDartMessenger).send(eq(DartMessenger.EventType.NETWORK_INTERRUPTED), any(), any())
    }

    @Test
    fun `test Failure sends RTMP_STOPPED with error`() {
        streamingContext.lastError = "Connection timed out"
        
        eventMapper.handleStateTransition(StreamState.PREPARING, StreamState.FAILED, StreamEvent.StartFailed)
        
        verify(mockDartMessenger).send(eq(DartMessenger.EventType.RTMP_STOPPED), eq("Connection timed out"))
        assert(streamingContext.lastError == null) // Should clear error
    }

    @Test
    fun `test Explicit Stop sends RTMP_STOPPED`() {
        eventMapper.handleStateTransition(StreamState.STREAMING, StreamState.IDLE, StreamEvent.ExplicitStop)
        
        verify(mockDartMessenger).send(eq(DartMessenger.EventType.RTMP_STOPPED), eq("Stream stopped"))
    }
    
    @Test
    fun `test default case no event`() {
        // IDLE -> PREPARING (StartRequested) usually sends no event
        eventMapper.handleStateTransition(StreamState.IDLE, StreamState.PREPARING, StreamEvent.StartRequested)
        verifyNoInteractions(mockDartMessenger)
    }
}