package com.resideo.flutter_audio_streaming

import android.content.Context
import android.os.Handler
import com.resideo.flutter_audio_streaming.core.AudioStreaming
import com.resideo.flutter_audio_streaming.interfaces.*
import com.resideo.flutter_audio_streaming.models.*
import com.resideo.flutter_audio_streaming.services.*
import com.resideo.flutter_audio_streaming.utils.DartMessenger
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockedStatic
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import org.mockito.ArgumentCaptor
import org.mockito.kotlin.whenever
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.runBlocking

/**
 * Tests for the audio focus permanent loss (music player) scenario.
 * 
 * This test class specifically covers the bug where:
 * - User minimizes app to play music
 * - Music takes audio focus (AUDIOFOCUS_LOSS permanent)
 * - User stops music quickly
 * - Expected: audio_interrupted -> audio_resumed
 * - Bug was: audio_interrupted -> rtmp_stopped -> rtmp_stopped (duplicate)
 */
class AudioFocusPermanentLossTest {

    @Mock lateinit var mockContext: Context
    @Mock lateinit var mockClient: StreamingClient
    @Mock lateinit var mockPhoneMonitor: PhoneCallMonitorInterface
    @Mock lateinit var mockNetworkMonitor: NetworkMonitorInterface
    @Mock lateinit var mockAudioFocus: AudioFocusMonitorInterface
    @Mock lateinit var mockDartMessenger: DartMessenger
    @Mock lateinit var mockSystemLifecycleObserver: SystemLifecycleObserver
    @Mock lateinit var mockHandler: Handler

    private lateinit var audioStreaming: AudioStreaming
    private lateinit var streamingContext: StreamingContext
    private lateinit var interruptionManager: InterruptionManager
    private lateinit var stateMachine: StreamStateMachine
    private lateinit var flutterEventMapper: FlutterEventMapper
    private lateinit var rtmpConnectionHandler: RtmpConnectionHandler
    private lateinit var reconnectionService: ReconnectionService
    
    private lateinit var mockedLog: MockedStatic<android.util.Log>

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockedLog = mockStatic(android.util.Log::class.java)
        mockedLog.`when`<Int> { android.util.Log.i(anyString(), anyString()) }.thenReturn(0)
        mockedLog.`when`<Int> { android.util.Log.d(anyString(), anyString()) }.thenReturn(0)
        mockedLog.`when`<Int> { android.util.Log.e(anyString(), anyString()) }.thenReturn(0)
        mockedLog.`when`<Int> { android.util.Log.w(anyString(), anyString()) }.thenReturn(0)

        MockitoAnnotations.openMocks(this)
        `when`(mockContext.applicationContext).thenReturn(mockContext)

        // Mock Handler to execute immediately for post()
        whenever(mockHandler.post(any())).thenAnswer {
            (it.arguments[0] as Runnable).run()
            true
        }

        // Real Logic Components
        streamingContext = StreamingContext()
        streamingContext.isInForeground = true

        interruptionManager = InterruptionManager(streamingContext, mockDartMessenger, mockHandler)
        
        rtmpConnectionHandler = RtmpConnectionHandler(interruptionManager, mockDartMessenger, streamingContext)
        rtmpConnectionHandler.setClient(mockClient)
        
        flutterEventMapper = FlutterEventMapper(mockDartMessenger, streamingContext, interruptionManager)
        
        stateMachine = StreamStateMachine { oldState, newState, event ->
            flutterEventMapper.handleStateTransition(oldState, newState, event)
        }

        reconnectionService = ReconnectionService(
            streamingContext,
            mockClient,
            interruptionManager,
            mockAudioFocus,
            mockHandler,
            mockDartMessenger
        )

        audioStreaming = AudioStreaming(
            mockContext,
            streamingContext,
            mockDartMessenger,
            interruptionManager,
            mockSystemLifecycleObserver,
            rtmpConnectionHandler,
            mockClient,
            mockAudioFocus,
            mockPhoneMonitor,
            mockNetworkMonitor,
            flutterEventMapper,
            stateMachine,
            reconnectionService,
            mockHandler
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        mockedLog.close()
    }

    @Test
    fun `test permanent audio focus loss triggers interruption`() = runBlocking {
        // 1. Start Streaming
        whenever(mockClient.prepareAudio(any(), any(), any(), any(), any())).thenReturn(true)
        whenever(mockClient.isStreaming).thenReturn(false)
        whenever(mockAudioFocus.requestFocus()).thenReturn(true)

        audioStreaming.startStreaming("rtmp://test", null)
        rtmpConnectionHandler.notifyConnected()
        
        assert(audioStreaming.getStreamState() == StreamState.STREAMING)
        verify(mockDartMessenger).send(eq(DartMessenger.EventType.RTMP_STARTED), anyString())

        // 2. Music App Takes Audio Focus (Permanent Loss)
        // This simulates onAudioFocusChange(AUDIOFOCUS_LOSS)
        audioStreaming.onAudioFocusLostPermanently()
        
        // Should be INTERRUPTED, not FAILED
        assert(audioStreaming.getStreamState() == StreamState.INTERRUPTED)
        verify(mockDartMessenger).send(eq(DartMessenger.EventType.AUDIO_INTERRUPTED), anyString(), any())
    }

    @Test
    fun `test focus regained after permanent loss resumes stream`() = runBlocking {
        // 1. Start Streaming
        whenever(mockClient.prepareAudio(any(), any(), any(), any(), any())).thenReturn(true)
        whenever(mockClient.isStreaming).thenReturn(false)
        whenever(mockAudioFocus.requestFocus()).thenReturn(true)

        audioStreaming.startStreaming("rtmp://test", null)
        rtmpConnectionHandler.notifyConnected()
        
        assert(audioStreaming.getStreamState() == StreamState.STREAMING)

        // 2. Music Takes Focus (Permanent)
        audioStreaming.onAudioFocusLostPermanently()
        assert(audioStreaming.getStreamState() == StreamState.INTERRUPTED)
        verify(mockDartMessenger).send(eq(DartMessenger.EventType.AUDIO_INTERRUPTED), anyString(), any())

        // 3. Music Stops -> Focus Regained
        val runnableCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        audioStreaming.onPhoneInterruptionEnded()
        
        // Capture reconnection delay
        verify(mockHandler, atLeastOnce()).postDelayed(runnableCaptor.capture(), eq(1000L))
        runnableCaptor.value.run()
        
        // 4. RTMP Reconnects
        rtmpConnectionHandler.notifyConnected()
        
        // 5. Verify Resumed
        assert(audioStreaming.getStreamState() == StreamState.STREAMING)
        verify(mockDartMessenger).send(eq(DartMessenger.EventType.AUDIO_RESUMED), anyString())
    }

    @Test
    fun `test no duplicate rtmp_stopped on timeout`() = runBlocking {
        // 1. Start Streaming
        whenever(mockClient.prepareAudio(any(), any(), any(), any(), any())).thenReturn(true)
        whenever(mockClient.isStreaming).thenReturn(false)
        whenever(mockAudioFocus.requestFocus()).thenReturn(true)

        audioStreaming.startStreaming("rtmp://test", null)
        rtmpConnectionHandler.notifyConnected()
        
        clearInvocations(mockDartMessenger)

        // 2. Permanent Focus Loss (Music)
        audioStreaming.onAudioFocusLostPermanently()
        
        assert(audioStreaming.getStreamState() == StreamState.INTERRUPTED)
        verify(mockDartMessenger).send(eq(DartMessenger.EventType.AUDIO_INTERRUPTED), anyString(), any())

        // 3. Capture Timeout Runnable (30s)
        val timeoutCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        verify(mockHandler).postDelayed(timeoutCaptor.capture(), eq(30000L))

        // 4. Trigger Timeout
        timeoutCaptor.value.run()

        // 5. Verify ONLY ONE RTMP_STOPPED sent (not two!)
        assert(audioStreaming.getStreamState() == StreamState.FAILED)
        verify(mockDartMessenger, times(1)).send(eq(DartMessenger.EventType.RTMP_STOPPED), anyString())
    }

    @Test
    fun `test disconnection during interruption is ignored`() = runBlocking {
        // 1. Start Streaming
        whenever(mockClient.prepareAudio(any(), any(), any(), any(), any())).thenReturn(true)
        whenever(mockClient.isStreaming).thenReturn(false)
        whenever(mockAudioFocus.requestFocus()).thenReturn(true)

        audioStreaming.startStreaming("rtmp://test", null)
        rtmpConnectionHandler.notifyConnected()
        
        clearInvocations(mockDartMessenger)

        // 2. Music Takes Focus
        audioStreaming.onAudioFocusLostPermanently()
        
        assert(audioStreaming.getStreamState() == StreamState.INTERRUPTED)

        // 3. Client disconnects (because we stopped the RTMP stream for interruption)
        // This should NOT trigger RTMP_STOPPED
        rtmpConnectionHandler.notifyDisconnected()
        
        // 4. State should STILL be INTERRUPTED
        assert(audioStreaming.getStreamState() == StreamState.INTERRUPTED)
        
        // 5. RTMP_STOPPED should NOT have been sent
        verify(mockDartMessenger, never()).send(eq(DartMessenger.EventType.RTMP_STOPPED), anyString())
    }

    @Test
    fun `test music player scenario - quick focus loss and regain with client disconnect`() = runBlocking {
        // This is the exact bug scenario from the user's report
        
        // 1. Start Streaming
        whenever(mockClient.prepareAudio(any(), any(), any(), any(), any())).thenReturn(true)
        whenever(mockClient.isStreaming).thenReturn(false)
        whenever(mockAudioFocus.requestFocus()).thenReturn(true)

        audioStreaming.startStreaming("rtmp://test", null)
        rtmpConnectionHandler.notifyConnected()
        
        assert(audioStreaming.getStreamState() == StreamState.STREAMING)
        verify(mockDartMessenger).send(eq(DartMessenger.EventType.RTMP_STARTED), anyString())
        
        clearInvocations(mockDartMessenger)

        // 2. User minimizes app, plays music -> Focus lost permanently
        audioStreaming.onAudioFocusLostPermanently()
        
        assert(audioStreaming.getStreamState() == StreamState.INTERRUPTED)
        verify(mockDartMessenger).send(eq(DartMessenger.EventType.AUDIO_INTERRUPTED), anyString(), any())
        
        // 3. Client disconnects (RTMP connection closed for interruption)
        // This must NOT trigger RTMP_STOPPED because we're INTERRUPTED, not stopping
        rtmpConnectionHandler.notifyDisconnected()
        
        // State is still INTERRUPTED
        assert(audioStreaming.getStreamState() == StreamState.INTERRUPTED)
        verify(mockDartMessenger, never()).send(eq(DartMessenger.EventType.RTMP_STOPPED), anyString())

        // 4. User stops music -> Focus regained (within a few seconds)
        val runnableCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        audioStreaming.onPhoneInterruptionEnded()
        
        // Capture and execute reconnection delay
        verify(mockHandler, atLeastOnce()).postDelayed(runnableCaptor.capture(), eq(1000L))
        runnableCaptor.allValues.last().run()
        
        // 5. RTMP Reconnects Successfully
        rtmpConnectionHandler.notifyConnected()
        
        // 6. Verify the correct event sequence
        assert(audioStreaming.getStreamState() == StreamState.STREAMING)
        verify(mockDartMessenger).send(eq(DartMessenger.EventType.AUDIO_RESUMED), anyString())
        
        // 7. CRITICAL: Verify no RTMP_STOPPED events were ever sent
        verify(mockDartMessenger, never()).send(eq(DartMessenger.EventType.RTMP_STOPPED), anyString())
    }

    @Test
    fun `test proactive resume on activity resumed when AUDIOFOCUS_GAIN never received`() = runBlocking {
        // This test simulates the REAL bug scenario:
        // 1. User starts streaming
        // 2. User minimizes app, music plays (AUDIOFOCUS_LOSS)
        // 3. User pauses music and returns to app
        // 4. AUDIOFOCUS_GAIN is NEVER received
        // 5. onActivityResumed should proactively resume

        // 1. Start Streaming
        whenever(mockClient.prepareAudio(any(), any(), any(), any(), any())).thenReturn(true)
        whenever(mockClient.isStreaming).thenReturn(false)
        whenever(mockAudioFocus.requestFocus()).thenReturn(true)

        audioStreaming.startStreaming("rtmp://test", null)
        rtmpConnectionHandler.notifyConnected()
        
        assert(audioStreaming.getStreamState() == StreamState.STREAMING)

        // 2. Music takes focus (permanent loss)
        audioStreaming.onAudioFocusLostPermanently()
        assert(audioStreaming.getStreamState() == StreamState.INTERRUPTED)
        verify(mockDartMessenger).send(eq(DartMessenger.EventType.AUDIO_INTERRUPTED), anyString(), any())

        // 3. Simulate RTMP disconnect callback (happens in reality)
        rtmpConnectionHandler.notifyDisconnected("NetConnection.Connect.Closed", null)
        
        // State should STILL be INTERRUPTED (not FAILED)
        assert(audioStreaming.getStreamState() == StreamState.INTERRUPTED)

        // 4. User returns to app - AUDIOFOCUS_GAIN IS NEVER RECEIVED
        // But onActivityResumed is called
        
        // Simulate onActivityResumed triggering proactive resume
        // mockActivity is already set in setup() via audioStreaming constructor helper, but let's be explicit
        val mockActivityInstance = mock(android.app.Activity::class.java)
        audioStreaming.setActivity(mockActivityInstance)
        
        val runnableCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        audioStreaming.onActivityResumed(mockActivityInstance)
        
        // Capture the 500ms proactive resume delay
        verify(mockHandler, atLeastOnce()).postDelayed(runnableCaptor.capture(), eq(500L))
        runnableCaptor.allValues.last().run()
        
        // Should trigger reconnection
        // Capture the 1000ms reconnection delay (from InterruptionManager -> handlePhoneInterruptionEnded -> ReconnectionService)
        // Note: The loop might be handled by InterruptionManager, which calls reconnectStream() directly if no delay.
        // InterruptionManager logic:
        // handlePhoneInterruptionEnded() -> delegate.reconnectStream() -> ReconnectionService.reconnectStream() -> 1000ms delay
        verify(mockHandler, atLeastOnce()).postDelayed(runnableCaptor.capture(), eq(1000L))
        runnableCaptor.allValues.last().run()

        // 5. RTMP Reconnects
        rtmpConnectionHandler.notifyConnected()
        
        // 6. Verify Resumed
        assert(audioStreaming.getStreamState() == StreamState.STREAMING)
        verify(mockDartMessenger).send(eq(DartMessenger.EventType.AUDIO_RESUMED), anyString())
    }

    @Test
    fun `test FlutterEventMapper prevents duplicate stop events`() {
        // Test the deduplication logic directly
        
        // Simulate streaming state with TimeoutExpired (only event that sends RTMP_STOPPED to FAILED)
        streamingContext.lastError = "First error"
        flutterEventMapper.handleStateTransition(StreamState.INTERRUPTED, StreamState.FAILED, StreamEvent.TimeoutExpired)
        
        verify(mockDartMessenger, times(1)).send(eq(DartMessenger.EventType.RTMP_STOPPED), anyString())
        
        // Try to send another FAILED transition (should be suppressed)
        streamingContext.lastError = "Second error"
        flutterEventMapper.handleStateTransition(StreamState.FAILED, StreamState.IDLE, StreamEvent.ExplicitStop)
        
        // Still only 1 call total
        verify(mockDartMessenger, times(1)).send(eq(DartMessenger.EventType.RTMP_STOPPED), anyString())
    }

    @Test
    fun `test FlutterEventMapper reset allows new stop event after streaming resumes`() {
        // First stop event (only TimeoutExpired sends RTMP_STOPPED to FAILED)
        streamingContext.lastError = "Error 1"
        flutterEventMapper.handleStateTransition(StreamState.INTERRUPTED, StreamState.FAILED, StreamEvent.TimeoutExpired)
        verify(mockDartMessenger, times(1)).send(eq(DartMessenger.EventType.RTMP_STOPPED), anyString())
        
        // Reset (simulating a new stream start)
        flutterEventMapper.reset()
        
        // Now streaming started again
        flutterEventMapper.handleStateTransition(StreamState.IDLE, StreamState.STREAMING, StreamEvent.StartSuccess)
        
        // Second stop event should work (using TimeoutExpired)
        streamingContext.lastError = "Error 2"
        flutterEventMapper.handleStateTransition(StreamState.INTERRUPTED, StreamState.FAILED, StreamEvent.TimeoutExpired)
        
        // Now we should have 2 total
        verify(mockDartMessenger, times(2)).send(eq(DartMessenger.EventType.RTMP_STOPPED), anyString())
    }
}
