package com.resideo.flutter_audio_streaming

import android.app.Activity
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.runBlocking

class InterruptionReconnectionTest {

    @Mock lateinit var mockContext: Context
    @Mock lateinit var mockActivity: Activity
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
        streamingContext.isInForeground = true // Default to foreground

        interruptionManager = InterruptionManager(streamingContext, mockDartMessenger, mockHandler)
        
        rtmpConnectionHandler = RtmpConnectionHandler(interruptionManager, mockDartMessenger, streamingContext)
        rtmpConnectionHandler.setClient(mockClient) // Hook mock client
        
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
        audioStreaming.setActivity(mockActivity)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        mockedLog.close()
    }

    @Test
    fun `test music interruption (focus loss transient) resume sends AUDIO_RESUMED`() = runBlocking {
        // 1. Start Streaming
        whenever(mockClient.prepareAudio(any(), any(), any(), any(), any())).thenReturn(true)
        whenever(mockClient.isStreaming).thenReturn(false)
        whenever(mockAudioFocus.requestFocus()).thenReturn(true)

        audioStreaming.startStreaming("rtmp://test", null)
        rtmpConnectionHandler.notifyConnected()
        
        assert(audioStreaming.getStreamState() == StreamState.STREAMING)

        // 2. Music Starts -> Audio Focus Lost Transiently
        // AudioStreaming.onAudioFocusLostTransient() calls interruptionManager.handlePhoneInterruptionBegan()
        audioStreaming.onAudioFocusLostTransient()
        
        assert(audioStreaming.getStreamState() == StreamState.INTERRUPTED)
        verify(mockDartMessenger).send(eq(DartMessenger.EventType.AUDIO_INTERRUPTED), anyString(), any())

        // SIMULATE REAL WORLD BEHAVIOR:
        // When stream stops for interruption, the client disconnects and fires callback.
        // We must ensure this disconnection doesn't trigger FAILED state or RTMP_STOPPED.
        rtmpConnectionHandler.notifyDisconnected()
        
        // Assert state is STILL INTERRUPTED (not FAILED)
        assert(audioStreaming.getStreamState() == StreamState.INTERRUPTED)
        
        // CRITICAL CHECK: Verify RTMP_STOPPED was NOT sent
        verify(mockDartMessenger, never()).send(eq(DartMessenger.EventType.RTMP_STOPPED), anyString())

        // 3. Music Stops -> Audio Focus Gained
        // AudioFocusManager calls mediator.onPhoneInterruptionEnded()
        val cleanupCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        audioStreaming.onPhoneInterruptionEnded()
        
        // 1. Verify and run hardware cleanup delay (500ms)
        verify(mockHandler, atLeastOnce()).postDelayed(cleanupCaptor.capture(), eq(1000L))
        cleanupCaptor.value.run()
        
        // 2. Verify reconnection delay (1000ms)
        val runnableCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        verify(mockHandler, atLeastOnce()).postDelayed(runnableCaptor.capture(), eq(1000L))
        runnableCaptor.value.run()
        
        // 4. Simulate RTMP Reconnection Success
        rtmpConnectionHandler.notifyConnected()
        
        // 5. Verify Resumed
        assert(audioStreaming.getStreamState() == StreamState.STREAMING)
        verify(mockDartMessenger).send(eq(DartMessenger.EventType.AUDIO_RESUMED), anyString())
    }

    @Test
    fun `test phone interruption resume sends AUDIO_RESUMED`() = runBlocking {
        // 1. Start Streaming
        whenever(mockClient.prepareAudio(any(), any(), any(), any(), any())).thenReturn(true)
        whenever(mockClient.isStreaming).thenReturn(false)
        whenever(mockAudioFocus.requestFocus()).thenReturn(true)
        
        audioStreaming.startStreaming("rtmp://test", null)
        
        // Simulate successful start
        rtmpConnectionHandler.notifyConnected()
        
        assert(audioStreaming.getStreamState() == StreamState.STREAMING)
        verify(mockDartMessenger).send(eq(DartMessenger.EventType.RTMP_STARTED), anyString())

        // 2. Phone Interruption Begins
        audioStreaming.onPhoneInterruptionBegan()
        
        assert(audioStreaming.getStreamState() == StreamState.INTERRUPTED)
        verify(mockDartMessenger).send(eq(DartMessenger.EventType.AUDIO_INTERRUPTED), anyString(), any())

        // 3. Phone Interruption Ends
        // Capture the reconnection delay runnable
        val cleanupCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        
        audioStreaming.onPhoneInterruptionEnded()
        
        // 1. Verify and run hardware cleanup delay (500ms)
        verify(mockHandler, atLeastOnce()).postDelayed(cleanupCaptor.capture(), eq(1000L))
        cleanupCaptor.value.run()
        
        // 2. Verify reconnection delay (1000ms)
        // ReconnectionService uses 1000ms delay.
        val runnableCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        verify(mockHandler, atLeastOnce()).postDelayed(runnableCaptor.capture(), eq(1000L))
        
        // Execute the reconnection runnable
        // This will call: mediator.transitionTo(ReconnectionStarted) -> client.stopStream() -> client.startStream()
        
        // SIMULATE REAL WORLD BEHAVIOR:
        // ReconnectionService calls client.stopStream(). This triggers notifyDisconnected() on the real client.
        // We must ensure this disconnection is IGNORED because state is RECONNECTING.
        whenever(mockClient.stopStream()).thenAnswer {
            rtmpConnectionHandler.notifyDisconnected()
        }
        
        runnableCaptor.value.run()
        
        // Now state should be RECONNECTING (and not FAILED)
        assert(audioStreaming.getStreamState() == StreamState.RECONNECTING)
        
        // Verify no STOPPED/FAILED events were sent during this process
        verify(mockDartMessenger, never()).send(eq(DartMessenger.EventType.RTMP_STOPPED), anyString())
        
        // 4. Simulate RTMP Reconnection Success
        // Client.startStream() is called inside runnable.
        // We simulate callback from client
        rtmpConnectionHandler.notifyConnected()
        
        // 5. Verify Resumed
        assert(audioStreaming.getStreamState() == StreamState.STREAMING)
        verify(mockDartMessenger).send(eq(DartMessenger.EventType.AUDIO_RESUMED), anyString())
    }

    @Test
    fun `test direct reconnection from INTERRUPTED state sends AUDIO_RESUMED`() = runBlocking {
        // 1. Start Streaming
        whenever(mockClient.prepareAudio(any(), any(), any(), any(), any())).thenReturn(true)
        whenever(mockClient.isStreaming).thenReturn(false)
        whenever(mockAudioFocus.requestFocus()).thenReturn(true)

        audioStreaming.startStreaming("rtmp://test", null)
        rtmpConnectionHandler.notifyConnected()

        // 2. Phone Interruption
        audioStreaming.onPhoneInterruptionBegan()
        assert(audioStreaming.getStreamState() == StreamState.INTERRUPTED)

        // 3. End Interruption (triggers ReconnectionService delay)
        val cleanupCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        audioStreaming.onPhoneInterruptionEnded()
        
        // 1. Verify and run hardware cleanup delay (500ms)
        verify(mockHandler, atLeastOnce()).postDelayed(cleanupCaptor.capture(), eq(1000L))
        cleanupCaptor.value.run()

        val runnableCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        verify(mockHandler, atLeastOnce()).postDelayed(runnableCaptor.capture(), eq(1000L))

        // DO NOT run the runnable. State remains INTERRUPTED.
        // This simulates a scenario where connection recovers before ReconnectionService runs,
        // or ReconnectionService fails to transition but client connects anyway.

        // 4. Simulate Connection Success
        rtmpConnectionHandler.notifyConnected()

        // 5. Verify Resumed
        assert(audioStreaming.getStreamState() == StreamState.STREAMING)
        verify(mockDartMessenger).send(eq(DartMessenger.EventType.AUDIO_RESUMED), anyString())
    }

    @Test
    fun `test network interruption resume sends NETWORK_RESUMED`() = runBlocking {
        // 1. Start Streaming
        whenever(mockClient.prepareAudio(any(), any(), any(), any(), any())).thenReturn(true)
        whenever(mockClient.isStreaming).thenReturn(false)
        whenever(mockAudioFocus.requestFocus()).thenReturn(true)

        audioStreaming.startStreaming("rtmp://test", null)
        rtmpConnectionHandler.notifyConnected()
        
        // Clear previous interactions to focus on network events
        clearInvocations(mockDartMessenger)

        // 2. Network Lost
        audioStreaming.onNetworkLost()
        
        assert(audioStreaming.getStreamState() == StreamState.INTERRUPTED)
        // Verify NETWORK_INTERRUPTED event
        verify(mockDartMessenger).send(eq(DartMessenger.EventType.NETWORK_INTERRUPTED), anyString(), any())
        
        // Verify timeout timer started (30s)
        verify(mockHandler).postDelayed(any(), eq(30000L))

        // 3. Network Available (triggers stabilization delay)
        val stabilizationCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        
        audioStreaming.onNetworkAvailable()
        
        // InterruptionManager has 1000ms stabilization delay
        verify(mockHandler, atLeastOnce()).postDelayed(stabilizationCaptor.capture(), eq(1000L))
        
        // Run stabilization delay -> calls handleInterruptionEndedInternal -> triggers ReconnectionService
        stabilizationCaptor.allValues.last().run()

        // 1. Verify and run hardware cleanup delay (500ms)
        val cleanupCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        verify(mockHandler, atLeastOnce()).postDelayed(cleanupCaptor.capture(), eq(1000L))
        cleanupCaptor.value.run()

        // Now ReconnectionService schedules its own delay (another 1000ms)
        // We need to capture that one too.
        // Since we are using the same mockHandler, we can capture the *latest* postDelayed call.
        
        // ReconnectionService runs: mainHandler.postDelayed({ ... }, 1000)
        val reconnectionCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        verify(mockHandler, atLeastOnce()).postDelayed(reconnectionCaptor.capture(), eq(1000L))
        
        // Run reconnection logic
        reconnectionCaptor.allValues.last().run()
        
        assert(audioStreaming.getStreamState() == StreamState.RECONNECTING)

        // 4. Simulate RTMP Reconnection Success
        rtmpConnectionHandler.notifyConnected()

        // 5. Verify Resumed with correct event
        assert(audioStreaming.getStreamState() == StreamState.STREAMING)
        verify(mockDartMessenger).send(eq(DartMessenger.EventType.NETWORK_RESUMED), anyString())
    }

    @Test
    fun `test network interruption timeout sends RTMP_STOPPED`() = runBlocking {
        // 1. Start Streaming
        whenever(mockClient.prepareAudio(any(), any(), any(), any(), any())).thenReturn(true)
        whenever(mockClient.isStreaming).thenReturn(false)
        whenever(mockAudioFocus.requestFocus()).thenReturn(true)

        audioStreaming.startStreaming("rtmp://test", null)
        rtmpConnectionHandler.notifyConnected()
        clearInvocations(mockDartMessenger)

        // 2. Network Lost
        audioStreaming.onNetworkLost()
        
        assert(audioStreaming.getStreamState() == StreamState.INTERRUPTED)
        verify(mockDartMessenger).send(eq(DartMessenger.EventType.NETWORK_INTERRUPTED), anyString(), any())

        // 3. Capture Timeout Runnable (30s)
        val timeoutCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        verify(mockHandler).postDelayed(timeoutCaptor.capture(), eq(30000L))

        // 4. Trigger Timeout
        timeoutCaptor.value.run()

        // 5. Verify Stream Failed and Event Sent
        assert(audioStreaming.getStreamState() == StreamState.FAILED)
        verify(mockDartMessenger).send(eq(DartMessenger.EventType.RTMP_STOPPED), contains("Stream stopped due to prolonged interruption"))
    }

    @Test
    fun `test phone interruption resume sends AUDIO_RESUMED without activity`() = runBlocking {
        // Simulate Background Service scenario where Activity is null
        audioStreaming.setActivity(null)

        // 1. Start Streaming
        whenever(mockClient.prepareAudio(any(), any(), any(), any(), any())).thenReturn(true)
        whenever(mockClient.isStreaming).thenReturn(false)
        whenever(mockAudioFocus.requestFocus()).thenReturn(true)
        
        audioStreaming.startStreaming("rtmp://test", null)
        rtmpConnectionHandler.notifyConnected()
        
        assert(audioStreaming.getStreamState() == StreamState.STREAMING)

        // 2. Phone Interruption Begins
        audioStreaming.onPhoneInterruptionBegan()
        
        assert(audioStreaming.getStreamState() == StreamState.INTERRUPTED)

        // 3. Phone Interruption Ends
        val cleanupCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        audioStreaming.onPhoneInterruptionEnded()
        
        // 1. Verify and run hardware cleanup delay (500ms)
        verify(mockHandler, atLeastOnce()).postDelayed(cleanupCaptor.capture(), eq(1000L))
        cleanupCaptor.value.run()
        
        val runnableCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        verify(mockHandler, atLeastOnce()).postDelayed(runnableCaptor.capture(), eq(1000L))
        runnableCaptor.value.run()
        
        // 4. Simulate RTMP Reconnection Success
        rtmpConnectionHandler.notifyConnected()
        
        // 5. Verify Resumed
        assert(audioStreaming.getStreamState() == StreamState.STREAMING)
        verify(mockDartMessenger).send(eq(DartMessenger.EventType.AUDIO_RESUMED), anyString())
    }

    @Test
    fun `test network interruption resume sends NETWORK_RESUMED without activity`() = runBlocking {
        // Simulate Background Service scenario where Activity is null
        audioStreaming.setActivity(null)

        // 1. Start Streaming
        whenever(mockClient.prepareAudio(any(), any(), any(), any(), any())).thenReturn(true)
        whenever(mockClient.isStreaming).thenReturn(false)
        whenever(mockAudioFocus.requestFocus()).thenReturn(true)

        audioStreaming.startStreaming("rtmp://test", null)
        rtmpConnectionHandler.notifyConnected()
        clearInvocations(mockDartMessenger)

        // 2. Network Lost
        audioStreaming.onNetworkLost()
        
        assert(audioStreaming.getStreamState() == StreamState.INTERRUPTED)

        // 3. Network Available
        val stabilizationCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        audioStreaming.onNetworkAvailable()
        
        verify(mockHandler, atLeastOnce()).postDelayed(stabilizationCaptor.capture(), eq(1000L))
        stabilizationCaptor.allValues.last().run() // Stabilization delay

        // 1. Verify and run hardware cleanup delay (500ms)
        val cleanupCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        verify(mockHandler, atLeastOnce()).postDelayed(cleanupCaptor.capture(), eq(1000L))
        cleanupCaptor.value.run()

        val reconnectionCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        verify(mockHandler, atLeastOnce()).postDelayed(reconnectionCaptor.capture(), eq(1000L))
        reconnectionCaptor.allValues.last().run() // Reconnection delay

        // 4. Simulate RTMP Reconnection Success
        rtmpConnectionHandler.notifyConnected()

        // 5. Verify Resumed
        assert(audioStreaming.getStreamState() == StreamState.STREAMING)
        verify(mockDartMessenger).send(eq(DartMessenger.EventType.NETWORK_RESUMED), anyString())
    }
}
