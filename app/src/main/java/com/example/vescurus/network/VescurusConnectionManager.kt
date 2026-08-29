package com.example.vescurus.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vescurus.model.Role
import com.example.vescurus.model.DetectionResponse
import com.example.vescurus.model.DetectionResult
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

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

class VescurusConnectionManager : ViewModel() {
    private val TAG = "VescurusNet"
    
    /**
     * EXPO NETWORKING STRATEGY:
     * To bypass congested Expo Wi-Fi, the Guide should ideally enable its Mobile Hotspot.
     * The Cook phone connects to this Hotspot. 
     * P2P Telemetry (WebSockets) then runs entirely over the local Hotspot LAN.
     * Only the Guide's Gemini API requests touch the public WAN.
     */
    private val SERVICE_TYPE = "_vescurus._tcp."
    private val SERVICE_NAME = "VESCURUS-GUIDE"
    private val HANDSHAKE_INIT = "VESCURUS_HANDSHAKE_INIT"
    private val HANDSHAKE_OK = "VESCURUS_HANDSHAKE_OK|GUIDE-01"
    private val HANDSHAKE_FIN = "VESCURUS_HANDSHAKE_FIN|COOK-01"
    private val PREFIX_DETECTION = "DETECTION|"
    private val PREFIX_FRAME = "FRAME|"
    private val CMD_TAKE_SNAPSHOT = "CMD_TAKE_SNAPSHOT"

    // Ktor constants
    private val WS_PING_INTERVAL_MS = 15000L
    private val WS_TIMEOUT_MS = 30000L

    private val json = Json { ignoreUnknownKeys = true }

    private val _status = MutableStateFlow(ConnectionStatus.IDLE)
    val status: StateFlow<ConnectionStatus> = _status

    private val _diagnostics = MutableStateFlow("")
    val diagnostics: StateFlow<String> = _diagnostics

    private val _latestDetections = MutableStateFlow<List<DetectionResult>>(emptyList())
    val latestDetections: StateFlow<List<DetectionResult>> = _latestDetections

    private val _latestFrame = MutableStateFlow<ByteArray?>(null)
    val latestFrame: StateFlow<ByteArray?> = _latestFrame

    private var server: NettyApplicationEngine? = null
    private var client: HttpClient? = null
    private var activeServerSession: DefaultWebSocketServerSession? = null
    private var activeClientSession: DefaultClientWebSocketSession? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private var reconnectionJob: Job? = null
    private var reconnectAttempt = 0
    private val maxReconnectDelay = 15000L

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
        server?.stop(1000, 2000)
        client?.close()
        unregisterService()
        stopDiscovery()
        _status.value = ConnectionStatus.IDLE
        _diagnostics.value = ""
        _latestFrame.value = null
        _latestDetections.value = emptyList()
        currentRole = Role.NONE
        reconnectAttempt = 0
    }

    // --- SERVER (GUIDE) ---
    private fun startServer() {
        val port = findFreePort()
        Log.d(TAG, "[WS] Starting Server on port $port")
        server = embeddedServer(Netty, port = port) {
            install(io.ktor.server.websocket.WebSockets) {
                pingPeriod = java.time.Duration.ofMillis(WS_PING_INTERVAL_MS)
                timeout = java.time.Duration.ofMillis(WS_TIMEOUT_MS)
                maxFrameSize = Long.MAX_VALUE
                masking = false
            }
            routing {
                webSocket("/ws") {
                    Log.d(TAG, "[WS] New connection received")
                    activeServerSession = this
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                when {
                                    text == HANDSHAKE_INIT -> {
                                        Log.d(TAG, "[WS] Handshake received, sending OK")
                                        send(HANDSHAKE_OK)
                                    }
                                    text.startsWith("VESCURUS_HANDSHAKE_FIN") -> {
                                        _status.value = ConnectionStatus.CONNECTED
                                        Log.d(TAG, "[WS] Handshake complete. Cook connected.")
                                        _diagnostics.value = "Cook connected"
                                    }
                                    text == CMD_TAKE_SNAPSHOT -> {
                                        Log.d(TAG, "[WS] Take Snapshot command received")
                                        _diagnostics.value = "Snapshot captured!"
                                    }
                                }
                            }
                        }
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
        _diagnostics.value = "Ktor server: RUNNING\nListening on port: $port"
    }

    private fun registerService(port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                Log.d(TAG, "[NSD] Service registered: ${nsdServiceInfo.serviceName}")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "[NSD] Registration failed: $errorCode")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "[NSD] Service unregistered")
            }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "[NSD] Unregistration failed: $errorCode")
            }
        }
        nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    private fun unregisterService() {
        registrationListener?.let { 
            try {
                nsdManager?.unregisterService(it) 
            } catch (e: Exception) {
                Log.e(TAG, "[NSD] Error unregistering: ${e.message}")
            }
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
                _diagnostics.value = "Discovery Failed: $errorCode"
                nsdManager?.stopServiceDiscovery(this)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager?.stopServiceDiscovery(this)
            }
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "[NSD] Discovery started")
            }
            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "[NSD] Discovery stopped")
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "[NSD] Service found: ${serviceInfo.serviceName}")
                if (serviceInfo.serviceType == SERVICE_TYPE && serviceInfo.serviceName == SERVICE_NAME) {
                    _status.value = ConnectionStatus.FOUND
                    nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.e(TAG, "[NSD] Resolve failed: $errorCode")
                            _diagnostics.value = "Resolve Failed: $errorCode"
                        }
                        override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                            val hostAddress = resolvedServiceInfo.host?.hostAddress
                            val port = resolvedServiceInfo.port
                            if (hostAddress != null) {
                                Log.d(TAG, "[NSD] Service resolved: $hostAddress:$port")
                                _diagnostics.value = "Found Guide at $hostAddress:$port"
                                connectToServer(hostAddress, port)
                            }
                        }
                    })
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "[NSD] Service lost: ${serviceInfo.serviceName}")
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
            try {
                Log.d(TAG, "[WS] Connecting to $host:$port/ws")
                client?.webSocket(method = HttpMethod.Get, host = host, port = port, path = "/ws") {
                    Log.d(TAG, "[WS] Connected. Starting handshake.")
                    activeClientSession = this
                    _diagnostics.value = "Handshaking..."
                    send(HANDSHAKE_INIT)
                    
                    reconnectAttempt = 0

                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            when {
                                text.startsWith("VESCURUS_HANDSHAKE_OK") -> {
                                    Log.d(TAG, "[WS] Handshake OK. Connected to Guide.")
                                    send(HANDSHAKE_FIN)
                                    _status.value = ConnectionStatus.CONNECTED
                                    _diagnostics.value = "Connected to Guide"
                                }
                                text.startsWith(PREFIX_DETECTION) -> {
                                    try {
                                        val data = text.removePrefix(PREFIX_DETECTION)
                                        val response = json.decodeFromString<DetectionResponse>(data)
                                        _latestDetections.value = response.detections
                                        Log.d(TAG, "[WS] Cook received ${response.detections.size} detections")
                                    } catch (e: Exception) {
                                        Log.e(TAG, "[WS] Failed to parse detection: ${e.message}")
                                    }
                                }
                                text.startsWith(PREFIX_FRAME) -> {
                                    try {
                                        val base64 = text.removePrefix(PREFIX_FRAME)
                                        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                                        _latestFrame.value = bytes
                                    } catch (e: Exception) {
                                        Log.e(TAG, "[WS] Failed to parse frame: ${e.message}")
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[WS] Connection exception: ${e.message}")
                _diagnostics.value = "Connection failed: ${e.message}"
                handleDisconnection()
            } finally {
                Log.d(TAG, "[WS] Client connection closed")
                activeClientSession = null
                if (isRunning.get() && _status.value != ConnectionStatus.RECONNECTING) {
                    handleDisconnection()
                }
            }
        }
    }

    private fun handleDisconnection() {
        if (!isRunning.get() || currentRole != Role.COOK) return
        
        _status.value = ConnectionStatus.RECONNECTING
        val delayTime = calculateBackoff()
        Log.d(TAG, "[WS] Reconnecting in ${delayTime}ms (Attempt ${reconnectAttempt})")
        _diagnostics.value = "Reconnecting in ${delayTime/1000}s..."
        
        reconnectionJob?.cancel()
        reconnectionJob = viewModelScope.launch(Dispatchers.IO) {
            delay(delayTime)
            if (isRunning.get()) {
                startClient()
            }
        }
    }

    private fun calculateBackoff(): Long {
        reconnectAttempt++
        val exponential = (Math.pow(2.0, reconnectAttempt.toDouble()).toLong() * 1000L)
        return Math.min(exponential, maxReconnectDelay)
    }

    private fun stopDiscovery() {
        discoveryListener?.let { 
            try {
                nsdManager?.stopServiceDiscovery(it) 
                Log.d(TAG, "[NSD] Service discovery stopped")
            } catch (e: Exception) {
                Log.e(TAG, "[NSD] Error stopping discovery: ${e.message}")
            }
        }
        discoveryListener = null
    }

    // --- COMMON ---
    fun broadcastDetection(detections: List<DetectionResult>) {
        if (currentRole != Role.GUIDE) return
        
        // Update local Guide overlay
        _latestDetections.value = detections
        
        val session = activeServerSession
        if (session != null && _status.value == ConnectionStatus.CONNECTED) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val data = json.encodeToString(DetectionResponse(detections))
                    Log.d(TAG, "[WS] Sending Detection: $data")
                    session.send(PREFIX_DETECTION + data)
                } catch (e: Exception) {
                    Log.e(TAG, "[WS] Broadcast failed: ${e.message}")
                }
            }
        }
    }

    fun broadcastFrame(bytes: ByteArray) {
        if (currentRole != Role.GUIDE) return
        val session = activeServerSession
        if (session != null && _status.value == ConnectionStatus.CONNECTED) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
                    session.send(PREFIX_FRAME + base64)
                } catch (e: Exception) {
                    Log.e(TAG, "[WS] Failed to broadcast frame: ${e.message}")
                }
            }
        }
    }

    fun sendSnapshotCommand() {
        if (currentRole != Role.COOK) return
        val session = activeClientSession
        if (session != null && _status.value == ConnectionStatus.CONNECTED) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    session.send(CMD_TAKE_SNAPSHOT)
                    Log.d(TAG, "[WS] Sent snapshot command to Guide")
                } catch (e: Exception) {
                    Log.e(TAG, "[WS] Failed to send snapshot command: ${e.message}")
                }
            }
        }
    }

    private fun findFreePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }
}
