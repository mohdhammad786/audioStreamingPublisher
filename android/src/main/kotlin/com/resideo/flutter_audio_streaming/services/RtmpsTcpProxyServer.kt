package com.resideo.flutter_audio_streaming.services

import android.util.Log
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A local SSL-to-TCP proxy that bridges an SSLSocket connection to a plain TCP localhost port.
 *
 * Problem: komuxer (the RTMP library inside StreamPack 3.1.2) connects with a plain TCP socket
 * even for rtmps:// URLs — its connect() method only TLS-wraps rtmpt/rtmpte/rtmpts (HTTP-tunnelled)
 * variants. isSecureRtmp() exists in the library but is never called.
 *
 * Solution: spin up a local plain-TCP server here, open a real SSLSocket to the actual RTMPS
 * endpoint, and pipe bytes bidirectionally. The caller rewrites the rtmps:// URL to
 * rtmp://127.0.0.1:<localPort>/... and hands that plain URL to StreamPack.
 */
class RtmpsTcpProxyServer {

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    /**
     * Starts the proxy.
     * @param remoteHost   Real RTMPS server hostname, e.g. "stream.tawheedoihsaan.org"
     * @param remotePort   Real RTMPS port, e.g. 443
     * @return Local TCP port that StreamPack should connect to (on 127.0.0.1)
     */
    fun start(remoteHost: String, remotePort: Int): Int {
        // Port 0 → OS picks a free port
        val server = ServerSocket(0, 1).also { serverSocket = it }
        val localPort = server.localPort
        running = true

        Log.i(TAG, "🔐 RTMPS proxy started on 127.0.0.1:$localPort → $remoteHost:$remotePort")

        Thread({
            try {
                // Accept exactly one connection (from StreamPack)
                val clientSocket: Socket = server.accept()
                Log.i(TAG, "🔗 StreamPack connected to proxy")

                // Establish the real TLS connection to the RTMPS server.
                // Enable HTTPS-style hostname verification so the cert CN/SAN is checked
                // against remoteHost — raw SSLSockets don't do this automatically.
                val sslSocket = SSLSocketFactory.getDefault()
                    .createSocket(remoteHost, remotePort) as SSLSocket
                sslSocket.sslParameters = sslSocket.sslParameters.also { params ->
                    params.endpointIdentificationAlgorithm = "HTTPS"
                }
                sslSocket.startHandshake()
                Log.i(TAG, "🔐 TLS handshake with $remoteHost:$remotePort OK (cert verified)")

                // Pipe client → remote (StreamPack → RTMPS server over TLS)
                val toRemote = Thread({
                    try {
                        val buf = ByteArray(8192)
                        val input = clientSocket.getInputStream()
                        val output = sslSocket.outputStream
                        var n = -1
                        while (running && input.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n)
                            output.flush()
                        }
                    } catch (e: Exception) {
                        if (running) Log.d(TAG, "client→remote pipe ended: ${e.message}")
                    }
                }, "rtmps-proxy-to-remote")

                // Pipe remote → client (RTMPS server over TLS → StreamPack)
                val toClient = Thread({
                    try {
                        val buf = ByteArray(8192)
                        val input = sslSocket.inputStream
                        val output = clientSocket.getOutputStream()
                        var n = -1
                        while (running && input.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n)
                            output.flush()
                        }
                    } catch (e: Exception) {
                        if (running) Log.d(TAG, "remote→client pipe ended: ${e.message}")
                    }
                }, "rtmps-proxy-to-client")

                toRemote.isDaemon = true
                toClient.isDaemon = true
                toRemote.start()
                toClient.start()

                // Wait for both pipes to finish
                toRemote.join()
                toClient.join()

                closeSilently(clientSocket)
                closeSilently(sslSocket)
                Log.i(TAG, "🔴 RTMPS proxy connection closed")
            } catch (e: Exception) {
                if (running) Log.e(TAG, "RTMPS proxy error: ${e.message}", e)
            }
        }, "rtmps-proxy-acceptor").apply { isDaemon = true }.start()

        return localPort
    }

    /** Stops the proxy and closes all sockets. */
    fun stop() {
        running = false
        closeSilently(serverSocket)
        serverSocket = null
        Log.i(TAG, "🛑 RTMPS proxy stopped")
    }

    private fun closeSilently(c: AutoCloseable?) {
        try { c?.close() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "RtmpsTcpProxy"
    }
}
