package com.resideo.flutter_audio_streaming

import android.os.Handler
import com.resideo.flutter_audio_streaming.models.*
import com.resideo.flutter_audio_streaming.services.InterruptionManager
import com.resideo.flutter_audio_streaming.services.FlutterEventMapper
import com.resideo.flutter_audio_streaming.services.InterruptionDelegate
import com.resideo.flutter_audio_streaming.utils.DartMessenger
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import java.util.*

class DuplicateEventTest {

    private lateinit var interruptionManager: InterruptionManager
    private lateinit var streamingContext: StreamingContext
    private lateinit var mockDelegate: InterruptionDelegate
    private lateinit var mockDartMessenger: DartMessenger
    private lateinit var mockHandler: Handler
    private lateinit var flutterEventMapper: FlutterEventMapper

    @Before
    fun setup() {
        streamingContext = StreamingContext()
        mockDelegate = mock()
        mockDartMessenger = mock()
        mockHandler = mock()
        
        // Setup Handler to execute immediately
        whenever(mockHandler.post(any())).thenAnswer {
            (it.arguments[0] as Runnable).run()
            true
        }

        interruptionManager = InterruptionManager(streamingContext, mockDartMessenger, mockHandler)
        interruptionManager.delegate = mockDelegate
        
        flutterEventMapper = FlutterEventMapper(mockDartMessenger, streamingContext, interruptionManager)
    }

    @Test
    fun `test duplicate phone interruption calls do not trigger duplicate state transitions`() {
        // Arrange
        whenever(mockDelegate.getStreamState()).thenReturn(StreamState.STREAMING)
        whenever(mockDelegate.transitionTo(StreamEvent.InterruptionBegan)).thenAnswer {
            // Simulate state change
            whenever(mockDelegate.getStreamState()).thenReturn(StreamState.INTERRUPTED)
            true
        }

        // Act
        interruptionManager.handlePhoneInterruptionBegan()
        interruptionManager.handlePhoneInterruptionBegan() // Duplicate call

        // Assert
        // transitionTo should be called only ONCE
        verify(mockDelegate, times(1)).transitionTo(StreamEvent.InterruptionBegan)
    }

    @Test
    fun `test state transition to INTERRUPTED sends AUDIO_INTERRUPTED event only once`() {
        // Arrange
        streamingContext.currentInterruptionSource = InterruptionSource.PHONE_CALL
        
        // Act
        flutterEventMapper.handleStateTransition(StreamState.STREAMING, StreamState.INTERRUPTED, StreamEvent.InterruptionBegan)
        flutterEventMapper.handleStateTransition(StreamState.INTERRUPTED, StreamState.INTERRUPTED, StreamEvent.InterruptionBegan) // Should not happen in real machine but test safety

        // Assert
        verify(mockDartMessenger, times(1)).send(eq(DartMessenger.EventType.AUDIO_INTERRUPTED), any(), any())
    }
}
