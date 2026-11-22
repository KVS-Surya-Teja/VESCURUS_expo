package com.example.vescurus.network

import android.content.Context
import android.net.wifi.WifiManager
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
import com.example.vescurus.model.*
import android.os.SystemClock
import kotlinx.coroutines.channels.Channel
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.ktor.server.response.*
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
    private val TAG="VescurusNet"
    private val SERVICE_TYPE="_vescurus._tcp"
    private val SERVICE_NAME="VESCURUS-GUIDE"
    private val json=Json { ignoreUnknownKeys=true }
    private val isRunning=AtomicBoolean(false)
    private val generation=AtomicLong(0)
    private var currentRole=Role.NONE
    private var prefs: android.content.SharedPreferences?=null
    private val _status=MutableStateFlow(ConnectionStatus.IDLE)
    val status: StateFlow<ConnectionStatus> = _status
    private val _diagnostics=MutableStateFlow("")
    val diagnostics: StateFlow<String> = _diagnostics
    private val _latestDetections=MutableStateFlow<List<DetectionResult>>(emptyList())
    val latestDetections: StateFlow<List<DetectionResult>> = _latestDetections
    private val _latestFrame=MutableStateFlow<ByteArray?>(null)
    val latestFrame: StateFlow<ByteArray?> = _latestFrame
    private val _observation=MutableStateFlow<Observation?>(null)
    val observation: StateFlow<Observation?> = _observation
    private val _sessionContext=MutableStateFlow<SessionContext?>(null)
    val sessionContext: StateFlow<SessionContext?> = _sessionContext
    private val _pairCode=MutableStateFlow("")
    val pairCode: StateFlow<String> = _pairCode
    private val _cameraFresh=MutableStateFlow(false)
    val cameraFresh: StateFlow<Boolean> = _cameraFresh
    private val _visionStatus=MutableStateFlow("Waiting for an analyzed frame")
    val visionStatus: StateFlow<String> = _visionStatus
    private var observationReceived=0L
    private var frameReceived=0L
    private var gate=ObservationGate()
    private var videoGate=ObservationGate(3000)
    private var lastStream: String?=null
    private var server: NettyApplicationEngine?=null
    private var client: HttpClient?=null
    private var activeClientSession: DefaultClientWebSocketSession?=null
    private var nsdManager: NsdManager?=null
    private var registrationListener: NsdManager.RegistrationListener?=null
    private var discoveryListener: NsdManager.DiscoveryListener?=null
    private var multicastLock: WifiManager.MulticastLock?=null
    private var connectionJob: Job?=null
    private var reconnectionJob: Job?=null
    private var activeContextChannel: Channel<SessionContext>?=null
    private val serverOwnership=Mutex()
    private class GuidePeer(val session: DefaultWebSocketServerSession) {
        val observations=Channel<Observation>(Channel.CONFLATED)
        val video=Channel<VideoFrame>(Channel.CONFLATED)
        var paired=false
        fun closeQueues() { observations.close();video.close() }
    }
    @Volatile private var peer: GuidePeer?=null
    init { viewModelScope.launch {
        while(isActive) {
            delay(500);val now=SystemClock.elapsedRealtime()
            _observation.value?.let { if(!gate.fresh(it,observationReceived,now)) {
                _observation.value=null;_latestDetections.value=emptyList();_visionStatus.value="Analysis expired — waiting for a new result"
            } }
            if(now-frameReceived>3000) { _latestFrame.value=null;_cameraFresh.value=false }
        }
    } }
    fun start(context: Context,role: Role) {
        if(isRunning.get() && role==currentRole)return
        stop()
        if(role!=Role.GUIDE && role!=Role.COOK)return
        prefs=context.applicationContext.getSharedPreferences("vescurus",0)
        _pairCode.value=prefs!!.getString("ownPairCode",null) ?: (100000+java.security.SecureRandom().nextInt(900000)).toString().also { prefs!!.edit().putString("ownPairCode",it).apply() }
        isRunning.set(true);currentRole=role
        nsdManager=context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifi=context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        runCatching { multicastLock=wifi?.createMulticastLock("VescurusDiscovery")?.apply { setReferenceCounted(false);acquire() } }
        try { if(role==Role.GUIDE)startServer() else startClient() }
        catch(_: Exception) { _status.value=ConnectionStatus.DISCONNECTED;_diagnostics.value="Local connection could not start. Retry or switch roles." }
    }
    fun updateSession(context: SessionContext) {
        if(currentRole!=Role.COOK)return
        if(_sessionContext.value?.sessionId!=context.sessionId) { clearObservations();gate=ObservationGate();videoGate=ObservationGate(3000) }
        _sessionContext.value=context;activeContextChannel?.trySend(context)
    }
    fun stop() {
        isRunning.set(false);generation.incrementAndGet()
        connectionJob?.cancel();reconnectionJob?.cancel();activeContextChannel?.close();activeContextChannel=null
        activeClientSession=null;peer?.closeQueues();peer=null
        client?.close();client=null;server?.stop(100,300);server=null
        unregisterService();stopDiscovery();runCatching { multicastLock?.let { if(it.isHeld)it.release() } };multicastLock=null
        clearObservations();gate=ObservationGate();videoGate=ObservationGate(3000)
        _sessionContext.value=null;currentRole=Role.NONE;_status.value=ConnectionStatus.IDLE;_diagnostics.value=""
    }
    private fun clearObservations() {
        _latestFrame.value=null;_latestDetections.value=emptyList();_observation.value=null
        _cameraFresh.value=false;lastStream=null;_visionStatus.value="Waiting for an analyzed frame"
    }
    private fun validContext(c: SessionContext)=c.sessionId.length in 1..128 && c.version>=0 && c.step in 0..500 && c.ingredients.size<=100 && c.ingredients.all { it.length<=80 } && (c.recipe?.length ?: 0)<=300
    private fun startServer() {
        val port=8766;val ip=getLocalIpAddress()
        server=embeddedServer(Netty,host="0.0.0.0",port=port) {
            install(io.ktor.server.websocket.WebSockets) { pingPeriod=java.time.Duration.ofSeconds(5);timeout=java.time.Duration.ofSeconds(15);maxFrameSize=3_000_000 }
            routing {
                get("/health") { call.respondText("{\"service\":\"VESCURUS Guide\",\"protocol\":2}",ContentType.Application.Json) }
                webSocket("/ws") {
                    val owned=GuidePeer(this)
                    // New unauthenticated connections cannot replace the current Cook.
                    var sender: Job?=null;var videoSender: Job?=null
                    try {
                        send("READY|2")
                        withTimeout(10000) {
                            val first=incoming.receive() as? Frame.Text ?: error("Pairing required")
                            val text=first.readText()
                            if(!text.startsWith("PAIR|"))error("Pairing required")
                            val request=json.decodeFromString<PairRequest>(text.removePrefix("PAIR|"))
                            if(request.code!=_pairCode.value || !validContext(request.context)) {
                                send("PAIR_REJECTED");close(CloseReason(CloseReason.Codes.VIOLATED_POLICY,"Pairing rejected"));return@withTimeout
                            }
                            serverOwnership.withLock {
                                val previous=peer;peer=owned;owned.paired=true
                                previous?.closeQueues();previous?.session?.close(CloseReason(CloseReason.Codes.NORMAL,"Cook replaced"))
                                if(_sessionContext.value?.sessionId!=request.context.sessionId)clearObservations()
                                _sessionContext.value=request.context
                            }
                            send("SYNC|"+json.encodeToString(request.context));_status.value=ConnectionStatus.CONNECTED
                            _diagnostics.value="Cook synchronized | ${request.context.sessionId.take(8)} v${request.context.version} | $ip:$port"
                        }
                        if(!owned.paired)return@webSocket
                        sender=launch { for(o in owned.observations) {
                            val age=SystemClock.elapsedRealtime()-o.captureUptimeMs
                            if(age in 0 until 15000 && o.sessionId==_sessionContext.value?.sessionId)send("OBS|"+json.encodeToString(o.copy(sourceAgeMs=age)))
                        } }
                        videoSender=launch { for(v in owned.video) {
                            val age=SystemClock.elapsedRealtime()-v.captureUptimeMs
                            if(age in 0 until 3000 && v.sessionId==_sessionContext.value?.sessionId)send("VIDEO|"+json.encodeToString(v.copy(sourceAgeMs=age)))
                        } }
                        for(frame in incoming) if(frame is Frame.Text && peer===owned) {
                            val text=frame.readText()
                            if(text.startsWith("SESSION|")) {
                                val c=json.decodeFromString<SessionContext>(text.removePrefix("SESSION|"))
                                if(!validContext(c))continue
                                val previous=_sessionContext.value
                                if(previous?.sessionId==c.sessionId && previous.version>c.version)continue
                                if(previous?.sessionId!=c.sessionId)clearObservations()
                                _sessionContext.value=c;send("SYNC|"+json.encodeToString(c))
                                _diagnostics.value="Cook synchronized | ${c.sessionId.take(8)} v${c.version} | $ip:$port"
                            } else if(text.startsWith("SNAPSHOT|"))send("SNAPSHOT_UNAVAILABLE|"+text.removePrefix("SNAPSHOT|"))
                        }
                    } catch(e: CancellationException) { throw e
                    } catch(_: Exception) { /* Ownership-scoped cleanup below; never log submitted credentials. */
                    } finally {
                        sender?.cancel();videoSender?.cancel();owned.closeQueues()
                        withContext(NonCancellable) { serverOwnership.withLock {
                            if(peer===owned) { peer=null;_sessionContext.value=null;clearObservations();_status.value=ConnectionStatus.DISCONNECTED;_diagnostics.value="Cook disconnected | $ip:$port" }
                        } }
                    }
                }
            }
        }.start(wait=false)
        registerService(port);_status.value=ConnectionStatus.SEARCHING;_diagnostics.value="Guide address: $ip:$port | waiting for Cook"
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

    private fun startClient() {
        if(!isRunning.get())return
        _status.value=ConnectionStatus.SEARCHING;_diagnostics.value="Searching for Guide. Direct address is also available."
        stopDiscovery();val ownedGeneration=generation.get()
        discoveryListener=object: NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStopDiscoveryFailed(type: String,code: Int) {}
            override fun onStartDiscoveryFailed(type: String,code: Int) { if(ownedGeneration==generation.get())_diagnostics.value="Discovery unavailable. Use the Guide address and pairing code." }
            override fun onServiceLost(info: NsdServiceInfo) { /* mDNS visibility does not establish socket liveness. */ }
            override fun onServiceFound(info: NsdServiceInfo) {
                if(ownedGeneration!=generation.get() || !isRunning.get() || _status.value==ConnectionStatus.CONNECTED || _status.value==ConnectionStatus.CONNECTING)return
                if(!info.serviceName.contains("VESCURUS") || !info.serviceType.contains("_vescurus._tcp"))return
                nsdManager?.resolveService(info,object: NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo,code: Int) {}
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        if(ownedGeneration!=generation.get() || !isRunning.get())return
                        val host=resolved.host?.hostAddress ?: return
                        _status.value=ConnectionStatus.FOUND;_diagnostics.value="Guide found at $host:${resolved.port}. Enter its pairing code to connect."
                        val savedCode=prefs?.getString("guidePairCode","").orEmpty()
                        if(savedCode.length==6)connectToServer(host,resolved.port)
                    }
                })
            }
        }
        nsdManager?.discoverServices(SERVICE_TYPE,NsdManager.PROTOCOL_DNS_SD,discoveryListener)
    }
    fun connectToServer(host: String,port: Int) {
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {
            viewModelScope.launch { connectToServer(host,port) };return
        }
        if(!isRunning.get() || currentRole!=Role.COOK)return
        if(port !in 1..65535 || !isValidPeerHost(host)) { _diagnostics.value="Enter a hostname or IP and a valid port.";return }
        val code=prefs?.getString("guidePairCode","").orEmpty()
        if(code.length!=6) { _status.value=ConnectionStatus.DISCONNECTED;_diagnostics.value="Enter the six-digit pairing code shown on Guide.";return }
        val attempt=generation.incrementAndGet();stopDiscovery();reconnectionJob?.cancel();connectionJob?.cancel()
        client?.close()
        val ownedClient=HttpClient(CIO) { install(io.ktor.client.plugins.websocket.WebSockets) { pingInterval=5000;maxFrameSize=3_000_000 };engine { requestTimeout=15000 } }
        client=ownedClient;_status.value=ConnectionStatus.CONNECTING;_diagnostics.value="Connecting and synchronizing session…";clearObservations()
        connectionJob=viewModelScope.launch(Dispatchers.IO) {
            var pairingRejected=false
            val contextQueue=Channel<SessionContext>(Channel.CONFLATED)
            try {
                ownedClient.webSocket(method=HttpMethod.Get,host=host.trim(),port=port,path="/ws") {
                    if(attempt!=generation.get())return@webSocket
                    activeClientSession=this;activeContextChannel=contextQueue
                    var paired=false
                    val contextSender=launch { for(c in contextQueue) if(paired)send("SESSION|"+json.encodeToString(c)) }
                    val handshakeDeadline=launch { delay(10000);if(!paired)close(CloseReason(CloseReason.Codes.NORMAL,"Synchronization timeout")) }
                    try {
                        for(frame in incoming) if(frame is Frame.Text && attempt==generation.get()) {
                            val text=frame.readText()
                            when {
                                text=="READY|2" -> {
                                    val c=_sessionContext.value ?: continue
                                    send("PAIR|"+json.encodeToString(PairRequest(code,c)))
                                }
                                text=="PAIR_REJECTED" -> {
                                    pairingRejected=true;_status.value=ConnectionStatus.DISCONNECTED;_diagnostics.value="Pairing code rejected. Re-enter the code displayed on Guide.";break
                                }
                                text.startsWith("SYNC|") -> {
                                    val c=json.decodeFromString<SessionContext>(text.removePrefix("SYNC|"))
                                    paired=true
                                    if(c==_sessionContext.value) { _status.value=ConnectionStatus.CONNECTED;_diagnostics.value="Session synchronized | ${c.sessionId.take(8)} v${c.version}" }
                                    else _sessionContext.value?.let { contextQueue.trySend(it) }
                                }
                                text.startsWith("OBS|") && paired -> {
                                    val o=json.decodeFromString<Observation>(text.removePrefix("OBS|"));val id=_sessionContext.value?.sessionId ?: continue
                                    if(_status.value==ConnectionStatus.CONNECTED && (!_cameraFresh.value || lastStream==o.streamId) && gate.accept(o,id))publish(o)
                                }
                                text.startsWith("VIDEO|") && paired -> {
                                    val v=json.decodeFromString<VideoFrame>(text.removePrefix("VIDEO|"));val id=_sessionContext.value?.sessionId ?: continue
                                    if(v.valid() && videoGate.accept(v.asGateObservation(),id)) {
                                        _latestFrame.value=android.util.Base64.decode(v.jpegBase64,android.util.Base64.NO_WRAP)
                                        lastStream=v.streamId;frameReceived=SystemClock.elapsedRealtime();_cameraFresh.value=true
                                    }
                                }
                                text.startsWith("SNAPSHOT_UNAVAILABLE|") -> _diagnostics.value="Snapshot unavailable for request ${text.substringAfter('|').take(12)}"
                            }
                        }
                    } finally { contextSender.cancel();handshakeDeadline.cancel() }
                }
            } catch(e: CancellationException) { throw e
            } catch(_: Exception) { if(attempt==generation.get())_diagnostics.value="Guide unavailable. Retrying; your cooking session is retained." }
            finally {
                ownedClient.close();contextQueue.close()
                withContext(NonCancellable+Dispatchers.Main.immediate) {
                if(attempt==generation.get() && isRunning.get()) {
                    activeClientSession=null;activeContextChannel=null;clearObservations()
                    if(!pairingRejected) {
                        _status.value=ConnectionStatus.RECONNECTING
                        _diagnostics.value="Guide connection lost. Retrying; your cooking session is retained."
                        reconnectionJob=viewModelScope.launch { delay(4000);connectToServer(host,port) }
                    }
                }
                }
            }
        }
    }
    private fun publish(o: Observation) {
        _observation.value=o;observationReceived=SystemClock.elapsedRealtime();_visionStatus.value=o.status
        _latestDetections.value=if(o.status=="SUCCESS")o.detections else emptyList()
        Log.d(TAG,"Observation session=${o.sessionId.take(8)} stream=${o.streamId.take(8)} frame=${o.frameId} status=${o.status} engine=${o.engine} latency=${o.inferenceMs}")
    }
    fun broadcastObservation(o: Observation) {
        if(currentRole!=Role.GUIDE || o.sessionId!=(_sessionContext.value?.sessionId ?: "unpaired") || !gate.accept(o,o.sessionId))return
        publish(o);peer?.takeIf { it.paired }?.observations?.trySend(o)
    }
    fun broadcastVideo(v: VideoFrame) {
        if(currentRole!=Role.GUIDE)return
        frameReceived=SystemClock.elapsedRealtime();_cameraFresh.value=true
        peer?.takeIf { it.paired }?.video?.trySend(v)
    }
    fun broadcastDetection(d: List<DetectionResult>) { /* No unassociated legacy results. */ }
    fun broadcastFrame(bytes: ByteArray) { /* VideoFrame identity is required. */ }
    fun sendSnapshotCommand() { /* Captured analyzed snapshots are shown; remote capture is explicitly unavailable. */ }
    fun connectDirectly(host: String,port: Int)=connectToServer(host,port)
    override fun onCleared() { stop();super.onCleared() }
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

    fun getLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        val hostAddress = address.hostAddress ?: continue
                        if (!hostAddress.startsWith("127.")) return hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    private fun findFreePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }
}
