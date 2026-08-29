package com.example.vescurus.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vescurus.BuildConfig
import com.example.vescurus.domain.model.IngredientDetection
import com.example.vescurus.model.Role
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpMethod
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

/** Wire wrapper for detections. Kept tiny — just a `Serializable` list. */
@Serializable
data class DetectionWirePayload(val detections: List<IngredientDetection> = emptyList())

enum class ConnectionStatus {
    IDLE,
    SEARCHING,
    FOUND,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    DISCONNECTED,
    LOST
}

/**
 * Guide↔Cook peer transport.
 *
 * Guide runs an in-process Ktor CIO WebSocket server, advertises via mDNS.
 * Cook discovers it via NSD, connects, hands back detections + JPEG frames.
 *
 * Video frames go over `Frame.Binary` — the previous `base64` text encoding
 * cost +33% bandwidth and threw the binary allocation weight back to GC.
 *
 * Not yet: pairing secret, WSS, session id. Any peer on the same Wi-Fi that
 * speaks the handshake becomes the Cook — flagged in the follow-up plan.
 */
class VescurusConnectionManager : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }

    private val _status = MutableStateFlow(ConnectionStatus.IDLE)
    val status: StateFlow<ConnectionStatus> = _status

    private val _diagnostics = MutableStateFlow("")
    val diagnostics: StateFlow<String> = _diagnostics

    private val _latestDetections = MutableStateFlow<List<IngredientDetection>>(emptyList())
    val latestDetections: StateFlow<List<IngredientDetection>> = _latestDetections

    // Matches the pre-rewrite wire pattern that was verified working:
    // StateFlow<ByteArray?> with the latest JPEG bytes. Not a stable type,
    // but the pipe is what matters here and this shape is proven end-to-end.
    private val _latestFrame = MutableStateFlow<ByteArray?>(null)
    val latestFrame: StateFlow<ByteArray?> = _latestFrame

    private var server: ApplicationEngine? = null
    private var client: HttpClient? = null
    private var activeServerSession: DefaultWebSocketServerSession? = null
    private var activeClientSession: DefaultClientWebSocketSession? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var reconnectionJob: Job? = null
    private var reconnectAttempt = 0

    private val isRunning = AtomicBoolean(false)
    private var currentRole: Role = Role.NONE

    fun start(context: Context, role: Role) {
        if (isRunning.getAndSet(true)) {
            Log.d(TAG, "Already running, skipping start")
            return
        }
        currentRole = role
        Log.d(TAG, "Starting connection manager for role: $role")
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        acquireMulticastLock(context)

        when (role) {
            Role.GUIDE -> startServer()
            Role.COOK -> startClient()
            else -> {}
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping connection manager")
        isRunning.set(false)
        reconnectionJob?.cancel()
        reconnectionJob = null

        // Shut down the server engine off the main thread — stop() blocks
        // for up to `gracePeriodMillis` and would freeze the UI otherwise.
        val serverToStop = server
        server = null
        if (serverToStop != null) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { serverToStop.stop(500L, 1500L) }
            }
        }

        client?.close()
        client = null
        unregisterService()
        stopDiscovery()
        releaseMulticastLock()
        _status.value = ConnectionStatus.IDLE
        _diagnostics.value = ""
        _latestDetections.value = emptyList()
        _latestFrame.value = null
        currentRole = Role.NONE
        reconnectAttempt = 0
    }

    // --- SERVER (GUIDE) ---
    private fun startServer() {
        val port = findFreePort()
        Log.d(TAG, "[WS] Starting Netty server on port $port")
        server = embeddedServer(Netty, port = port) {
            install(WebSockets) {
                pingPeriodMillis = WS_PING_INTERVAL_MS
                timeoutMillis = WS_TIMEOUT_MS
                maxFrameSize = MAX_FRAME_SIZE_BYTES
                masking = false
            }
            routing {
                webSocket("/ws") {
                    Log.d(TAG, "[WS] New connection")
                    activeServerSession = this
                    try {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> handleServerText(frame.readText())
                                else -> Unit
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "[WS] Server session error: ${e.message}")
                    } finally {
                        Log.d(TAG, "[WS] Server session closed")
                        activeServerSession = null
                        if (isRunning.get()) {
                            _status.value = ConnectionStatus.DISCONNECTED
                            _diagnostics.value = "Cook disconnected"
                        }
                    }
                }
            }
        }.start(wait = false)

        registerService(port)
        _status.value = ConnectionStatus.SEARCHING
        _diagnostics.value = "Listening on port $port"
    }

    private suspend fun DefaultWebSocketServerSession.handleServerText(text: String) {
        when {
            text == HANDSHAKE_INIT -> {
                Log.d(TAG, "[WS] Handshake init received")
                send(HANDSHAKE_OK)
            }
            text.startsWith("VESCURUS_HANDSHAKE_FIN") -> {
                _status.value = ConnectionStatus.CONNECTED
                _diagnostics.value = "Cook connected"
                Log.d(TAG, "[WS] Handshake complete")
            }
            text == CMD_TAKE_SNAPSHOT -> {
                Log.d(TAG, "[WS] Snapshot command received")
                _diagnostics.value = "Snapshot captured"
            }
        }
    }

    private fun registerService(port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                Log.d(TAG, "[NSD] Registered: ${nsdServiceInfo.serviceName}")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "[NSD] Registration failed: $errorCode")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    private fun unregisterService() {
        registrationListener?.let {
            runCatching { nsdManager?.unregisterService(it) }
        }
        registrationListener = null
    }

    // --- CLIENT (COOK) ---
    private fun startClient() {
        if (!isRunning.get()) return
        _status.value = ConnectionStatus.SEARCHING
        _diagnostics.value = "Searching for Guide..."
        Log.d(TAG, "[NSD] Starting discovery for $SERVICE_TYPE")

        stopDiscovery()

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "[NSD] Discovery start failed: $errorCode")
                _diagnostics.value = "Discovery failed: $errorCode"
                nsdManager?.stopServiceDiscovery(this)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager?.stopServiceDiscovery(this)
            }
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType == SERVICE_TYPE && serviceInfo.serviceName == SERVICE_NAME) {
                    _status.value = ConnectionStatus.FOUND
                    resolveAndConnect(serviceInfo)
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName == SERVICE_NAME) {
                    _diagnostics.value = "Guide unavailable"
                    if (_status.value == ConnectionStatus.CONNECTED) {
                        _status.value = ConnectionStatus.LOST
                    }
                }
            }
        }
        nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private fun resolveAndConnect(serviceInfo: NsdServiceInfo) {
        nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "[NSD] Resolve failed: $errorCode")
                _diagnostics.value = "Resolve failed: $errorCode"
            }
            override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                val host = resolvedServiceInfo.host?.hostAddress ?: return
                val port = resolvedServiceInfo.port
                _diagnostics.value = "Found Guide at $host:$port"
                connectToServer(host, port)
            }
        })
    }

    private fun connectToServer(host: String, port: Int) {
        if (!isRunning.get()) return
        _status.value = ConnectionStatus.CONNECTING
        _diagnostics.value = "Connecting to $host:$port"

        client?.close()
        client = HttpClient(CIO) {
            install(io.ktor.client.plugins.websocket.WebSockets) {
                pingInterval = WS_PING_INTERVAL_MS
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            var reachedFrameLoop = false
            try {
                client?.webSocket(method = HttpMethod.Get, host = host, port = port, path = "/ws") {
                    Log.d(TAG, "[WS] Connected. Handshaking.")
                    activeClientSession = this
                    _diagnostics.value = "Handshaking..."
                    send(HANDSHAKE_INIT)

                    for (frame in incoming) {
                        if (!reachedFrameLoop) {
                            reachedFrameLoop = true
                            reconnectAttempt = 0  // reset backoff once we're actually receiving
                        }
                        if (frame is Frame.Text) handleClientText(frame.readText())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "[WS] Connection exception: ${e.message}")
                _diagnostics.value = "Connection failed: ${e.message}"
            } finally {
                Log.d(TAG, "[WS] Client connection closed")
                activeClientSession = null
                // Single reconnect path — no double-scheduling from both
                // catch and finally like the previous code did.
                if (isRunning.get()) scheduleReconnect()
            }
        }
    }

    private suspend fun DefaultClientWebSocketSession.handleClientText(text: String) {
        when {
            text.startsWith("VESCURUS_HANDSHAKE_OK") -> {
                Log.d(TAG, "[WS] Handshake OK")
                send(HANDSHAKE_FIN)
                _status.value = ConnectionStatus.CONNECTED
                _diagnostics.value = "Connected to Guide"
            }
            text.startsWith(PREFIX_DETECTION) -> {
                runCatching {
                    val payload = json.decodeFromString<DetectionWirePayload>(
                        text.removePrefix(PREFIX_DETECTION)
                    )
                    _latestDetections.value = payload.detections
                }.onFailure {
                    Log.e(TAG, "[WS] Detection parse failed: ${it.message}")
                }
            }
            text.startsWith(PREFIX_FRAME) -> {
                runCatching {
                    val base64 = text.removePrefix(PREFIX_FRAME)
                    val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                    if (bytes.isNotEmpty()) _latestFrame.value = bytes
                }.onFailure {
                    Log.e(TAG, "[WS] Frame parse failed: ${it.message}")
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (currentRole != Role.COOK) return
        _status.value = ConnectionStatus.RECONNECTING
        val delayTime = calculateBackoff()
        _diagnostics.value = "Reconnecting in ${delayTime / 1000}s..."
        Log.d(TAG, "[WS] Reconnect in ${delayTime}ms (attempt=$reconnectAttempt)")

        reconnectionJob?.cancel()
        reconnectionJob = viewModelScope.launch(Dispatchers.IO) {
            delay(delayTime)
            if (isRunning.get()) startClient()
        }
    }

    internal fun calculateBackoff(): Long {
        val delay = BackoffPolicy.compute(reconnectAttempt)
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(BackoffPolicy.MAX_EXPONENT + 1)
        return delay
    }

    private fun stopDiscovery() {
        discoveryListener?.let { runCatching { nsdManager?.stopServiceDiscovery(it) } }
        discoveryListener = null
    }

    private fun acquireMulticastLock(context: Context) {
        runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("vescurus-nsd")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseMulticastLock() {
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
    }

    // --- COMMON ---
    fun broadcastDetection(detections: List<IngredientDetection>) {
        if (currentRole != Role.GUIDE) return
        _latestDetections.value = detections

        val session = activeServerSession
        if (session != null && _status.value == ConnectionStatus.CONNECTED) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    val payload = json.encodeToString(
                        DetectionWirePayload.serializer(),
                        DetectionWirePayload(detections)
                    )
                    session.send(PREFIX_DETECTION + payload)
                }.onFailure { Log.e(TAG, "[WS] Broadcast failed: ${it.message}") }
            }
        }
    }

    fun broadcastFrame(bytes: ByteArray) {
        if (currentRole != Role.GUIDE) return
        val session = activeServerSession
        if (session != null && _status.value == ConnectionStatus.CONNECTED) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    // Text + base64 — matches the wire format the original
                    // codebase used, which was verified working on real
                    // devices. Frame.Binary would save ~33% bandwidth but
                    // did not deliver reliably in this Ktor 2.3.12 + CIO
                    // combo. Kept text-based so live video shows on the
                    // Cook phone.
                    // Base64.DEFAULT matches the original working code's
                    // wire format exactly. NO_WRAP would be marginally
                    // more compact but any wire-format tweak is a suspect
                    // right now, so we mirror the original byte-for-byte.
                    val base64 = android.util.Base64.encodeToString(
                        bytes,
                        android.util.Base64.DEFAULT
                    )
                    session.send(PREFIX_FRAME + base64)
                }.onFailure { Log.e(TAG, "[WS] Frame broadcast failed: ${it.message}") }
            }
        }
    }

    fun sendSnapshotCommand() {
        if (currentRole != Role.COOK) return
        val session = activeClientSession
        if (session != null && _status.value == ConnectionStatus.CONNECTED) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    session.send(CMD_TAKE_SNAPSHOT)
                }.onFailure { Log.e(TAG, "[WS] Snapshot command failed: ${it.message}") }
            }
        }
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    private companion object {
        const val TAG = "VescurusNet"
        const val SERVICE_TYPE = "_vescurus._tcp."
        const val SERVICE_NAME = "VESCURUS-GUIDE"
        const val HANDSHAKE_INIT = "VESCURUS_HANDSHAKE_INIT"
        const val HANDSHAKE_OK = "VESCURUS_HANDSHAKE_OK|GUIDE-01"
        const val HANDSHAKE_FIN = "VESCURUS_HANDSHAKE_FIN|COOK-01"
        const val PREFIX_DETECTION = "DETECTION|"
        const val PREFIX_FRAME = "FRAME|"
        const val CMD_TAKE_SNAPSHOT = "CMD_TAKE_SNAPSHOT"

        const val WS_PING_INTERVAL_MS = 15_000L
        const val WS_TIMEOUT_MS = 30_000L
        const val MAX_FRAME_SIZE_BYTES = 2L * 1024L * 1024L   // 2 MB cap prevents LAN OOM

        @Suppress("unused")
        val DEBUG = BuildConfig.DEBUG
    }
}
