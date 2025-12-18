package com.resideo.flutter_audio_streaming

import android.content.Context
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations

/**
 * Example Unit Test for AudioStreaming.
 * This proves the refactor effectively enables unit testing via mocks.
 */
class AudioStreamingTest {

    @Mock lateinit var mockContext: Context
    @Mock lateinit var mockClient: StreamingClient
    @Mock lateinit var mockPhoneMonitor: PhoneCallMonitorInterface
    @Mock lateinit var mockNetworkMonitor: NetworkMonitorInterface
    @Mock lateinit var mockAudioFocus: AudioFocusMonitorInterface
    @Mock lateinit var mockDartMessenger: DartMessenger

    private lateinit var audioStreaming: AudioStreaming

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        `when`(mockContext.applicationContext).thenReturn(mockContext)
        
        audioStreaming = AudioStreaming(
            mockContext,
            mockDartMessenger,
            mockClient,
            mockPhoneMonitor,
            mockNetworkMonitor,
            mockAudioFocus
        )
    }

    @Test
    fun `test phone call interruption stops stream`() {
        // 1. Setup - Mocking startStreaming
        `when`(mockPhoneMonitor.isCallActive).thenReturn(false)
        `when`(mockAudioFocus.requestFocus()).thenReturn(true)
        `when`(mockClient.prepareAudio(anyInt(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean())).thenReturn(true)
        
        audioStreaming.startStreaming("rtsp://test", null)
        
        // Verify it started
        verify(mockClient).startStream("rtsp://test")
        
        // 2. Trigger Interruption
        audioStreaming.onPhoneInterruptionBegan()
        
        // 3. Verify side effects
        verify(mockClient).stopStream()
        verify(mockAudioFocus).abandonFocus()
        verify(mockDartMessenger).send(eq(DartMessenger.EventType.AUDIO_INTERRUPTED), anyString())
    }

    @Test
    fun `test network loss while on phone call priorities phone`() {
        // 1. Trigger Phone Call
        audioStreaming.onPhoneInterruptionBegan()
        
        // 2. Trigger Network Loss
        audioStreaming.onNetworkLost()
        
        // 3. Verify sequence - should still be in phone interruption logic
        // This test would check internal state if we exposed it, or verify no multiple events
        verify(mockDartMessenger, times(1)).send(eq(DartMessenger.EventType.AUDIO_INTERRUPTED), anyString())
        verify(mockDartMessenger, never()).send(eq(DartMessenger.EventType.NETWORK_INTERRUPTED), anyString())
    }
}
