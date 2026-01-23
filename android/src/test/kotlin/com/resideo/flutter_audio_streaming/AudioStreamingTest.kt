package com.resideo.flutter_audio_streaming

import android.content.Context
import com.resideo.flutter_audio_streaming.core.AudioStreaming
import com.resideo.flutter_audio_streaming.interfaces.*
import com.resideo.flutter_audio_streaming.models.*
import com.resideo.flutter_audio_streaming.services.*
import com.resideo.flutter_audio_streaming.utils.DartMessenger

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
    
    // New Mocks
    @Mock lateinit var mockInterruptionManager: InterruptionManager
    @Mock lateinit var mockSystemLifecycleObserver: SystemLifecycleObserver
    @Mock lateinit var mockRtmpConnectionHandler: RtmpConnectionHandler
    @Mock lateinit var mockFlutterEventMapper: FlutterEventMapper
    @Mock lateinit var mockStateMachine: StreamStateMachine
    @Mock lateinit var mockReconnectionService: ReconnectionService

    private lateinit var audioStreaming: AudioStreaming
    private lateinit var streamingContext: StreamingContext

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        `when`(mockContext.applicationContext).thenReturn(mockContext)
        
        streamingContext = StreamingContext()
        
        audioStreaming = AudioStreaming(
            mockContext,
            streamingContext,
            mockDartMessenger,
            mockInterruptionManager,
            mockSystemLifecycleObserver,
            mockRtmpConnectionHandler,
            mockClient,
            mockAudioFocus,
            mockPhoneMonitor,
            mockNetworkMonitor,
            mockFlutterEventMapper,
            mockStateMachine,
            mockReconnectionService
        )
    }

    @Test
    fun `test startStreaming initializes components correctly`() {
        // 1. Setup
        `when`(mockPhoneMonitor.isCallActive).thenReturn(false)
        `when`(mockAudioFocus.requestFocus()).thenReturn(true)
        `when`(mockClient.prepareAudio(anyInt(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean())).thenReturn(true)
        `when`(mockClient.isStreaming).thenReturn(false)

        audioStreaming.startStreaming("rtmp://test", null)

        // 2. Verify
        verify(mockClient).startStream("rtmp://test")
        verify(mockStateMachine).transition(StreamEvent.StartRequested)
        verify(mockInterruptionManager).reset()
        verify(mockPhoneMonitor).startMonitoring()
        verify(mockNetworkMonitor).startMonitoring()
    }

    @Test
    fun `test stopStreamForInterruption stops rtmp client`() {
        audioStreaming.stopStreamForInterruption()
        verify(mockClient).stopStream()
    }

    @Test
    fun `test onPhoneInterruptionBegan delegates to manager`() {
        audioStreaming.onPhoneInterruptionBegan()
        verify(mockInterruptionManager).handlePhoneInterruptionBegan()
    }

    @Test
    fun `test onNetworkLost delegates to manager`() {
        audioStreaming.onNetworkLost()
        verify(mockInterruptionManager).handleNetworkLost()
    }
}
