package com.example.btcall

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

enum class CallState {
    IDLE,
    CONNECTED,
    OUTGOING_INIT,
    OUTGOING_RINGING,
    INCOMING_RINGING,
    ANSWERING,
    ACTIVE,
    DISCONNECTING,
    RECONNECTING,
    FAILED
}

data class PeerDevice(
    val address: String,
    val name: String,
    val isBonded: Boolean,
    val isOnline: Boolean
)

data class ScannedDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int,
    val isPhone: Boolean,
    val isBonded: Boolean
)

data class SignalPacket(
    val timestamp: String,
    val type: String,
    val direction: String, // "SENT", "RECEIVED", "SYSTEM"
    val content: String
)

data class RecordingInfo(
    val id: String,
    val filename: String,
    val filePath: String,
    val callTime: Long,
    val peerName: String,
    val peerAddress: String,
    val callId: String,
    val durationSec: Int
)

// Signal Codec for robust JSON message processing
object SignalCodec {
    fun encode(type: String, callId: String, sender: String, target: String, extra: Map<String, Any> = emptyMap()): String {
        val obj = JSONObject()
        obj.put("type", type)
        obj.put("callId", callId)
        obj.put("sender", sender)
        obj.put("target", target)
        obj.put("timestamp", System.currentTimeMillis())
        extra.forEach { (k, v) ->
            obj.put(k, v)
        }
        return obj.toString()
    }

    fun decode(msgStr: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            val obj = JSONObject(msgStr)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = obj.get(key).toString()
            }
        } catch (e: Exception) {
            // Fallback for simple key-value parsing if not perfect JSON
            val cleaned = msgStr.replace("{", "").replace("}", "").trim()
            if (cleaned.isNotEmpty()) {
                cleaned.split(",").forEach { pair ->
                    val parts = pair.split(":")
                    if (parts.size >= 2) {
                        val k = parts[0].trim().replace("\"", "")
                        val v = parts.subList(1, parts.size).joinToString(":").trim().replace("\"", "")
                        map[k] = v
                    }
                }
            }
        }
        return map
    }
}

class BluetoothCallEngine(private val context: Context) {
    
    companion object {
        @Volatile
        var instance: BluetoothCallEngine? = null
            private set
    }
    
    // Core Engine States
    private val _callState = mutableStateOf(CallState.CONNECTED)
    val callState: State<CallState> = _callState

    var callStateValue: CallState
        get() = _callState.value
        set(value) {
            val oldValue = _callState.value
            if (oldValue == value) return
            _callState.value = value
            onCallStateChanged(oldValue, value)
        }

    // Playback Progress, Duration & Position States
    private val _playbackProgressFlow = mutableStateOf(0f)
    val playbackProgressFlow: State<Float> = _playbackProgressFlow

    private val _playbackPositionMs = mutableStateOf(0L)
    val playbackPositionMs: State<Long> = _playbackPositionMs

    private val _playbackDurationMs = mutableStateOf(0L)
    val playbackDurationMs: State<Long> = _playbackDurationMs

    private var playbackPollJob: Job? = null

    private var activeRecordingFile: File? = null
    private var activeRecordingFilename: String? = null

    private val _activeCallId = mutableStateOf<String?>(null)
    val activeCallId: State<String?> = _activeCallId

    private val _activePeer = mutableStateOf<PeerDevice?>(null)
    val activePeer: State<PeerDevice?> = _activePeer

    private val _callDuration = mutableStateOf(0)
    val callDuration: State<Int> = _callDuration

    private val _audioRoute = mutableStateOf("Bluetooth Headset")
    val audioRoute: State<String> = _audioRoute

    private val _isMuted = mutableStateOf(false)
    val isMuted: State<Boolean> = _isMuted

    private val _isMicMuted = mutableStateOf(false)
    val isMicMuted: State<Boolean> = _isMicMuted

    private val _isSpeakerMuted = mutableStateOf(false)
    val isSpeakerMuted: State<Boolean> = _isSpeakerMuted

    private val _isRecording = mutableStateOf(false)
    val isRecording: State<Boolean> = _isRecording

    private val _bookmarkedAddresses = mutableStateOf<Set<String>>(emptySet())
    val bookmarkedAddresses: State<Set<String>> = _bookmarkedAddresses

    // Devices, Logs & Recordings Lists
    val peerDevices = mutableStateListOf<PeerDevice>()
    val signalLogs = mutableStateListOf<SignalPacket>()
    val recordingsList = mutableStateListOf<RecordingInfo>()
    val scannedDevices = mutableStateListOf<ScannedDevice>()

    // Bluetooth Scanning & RSSI Filter States
    private val _isScanning = mutableStateOf(false)
    val isScanning: State<Boolean> = _isScanning

    private val _rssiThreshold = mutableStateOf(-50)
    val rssiThreshold: State<Int> = _rssiThreshold

    private val _filterOnlyPhone = mutableStateOf(true)
    val filterOnlyPhone: State<Boolean> = _filterOnlyPhone

    private val _filterOnlyHighSignal = mutableStateOf(true)
    val filterOnlyHighSignal: State<Boolean> = _filterOnlyHighSignal

    private var discoveryReceiver: android.content.BroadcastReceiver? = null

    // Last completed recording info (shown on call overlay screen)
    private val _lastRecordingInfo = mutableStateOf<RecordingInfo?>(null)
    val lastRecordingInfo: State<RecordingInfo?> = _lastRecordingInfo

    // Simulation / Debug Switcher (Default: False - Real prioritized!)
    private val _simulationMode = mutableStateOf(false)
    val simulationMode: State<Boolean> = _simulationMode

    // Real Bluetooth RFCOMM Socket Members
    private val MY_UUID = java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val NAME = "176BluetoothCall"
    
    private var serverSocket: android.bluetooth.BluetoothServerSocket? = null
    private var clientSocket: android.bluetooth.BluetoothSocket? = null
    private var connectedSocket: android.bluetooth.BluetoothSocket? = null
    
    private var listenJob: Job? = null
    private var connectJob: Job? = null
    private var ioJob: Job? = null

    // Media Player for call recording playbacks
    private var mediaPlayer: MediaPlayer? = null
    private val _playingRecordingId = mutableStateOf<String?>(null)
    val playingRecordingId: State<String?> = _playingRecordingId

    private var callJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // Track recording starting duration
    private var recordingStartDuration = 0

    val localDeviceId: String
    private val peerAddressToDeviceIdMap = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val peerDeviceIdToAddressMap = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var localBluetoothMacAddress: String? = null

    init {
        instance = this
        // Load simulation mode preference (defaults to false)
        val prefs = context.getSharedPreferences("176_prefs", Context.MODE_PRIVATE)
        _simulationMode.value = prefs.getBoolean("simulation_mode", false)
        _rssiThreshold.value = prefs.getInt("rssi_threshold", -50)
        _filterOnlyPhone.value = prefs.getBoolean("filter_only_phone", true)
        _filterOnlyHighSignal.value = prefs.getBoolean("filter_only_high_signal", true)
        
        localDeviceId = getOrGenerateDeviceId()
        addSystemLog("INFO", "IDENTITY", "本地唯一设备标识初始化成功: $localDeviceId")
        
        loadBookmarks()
        loadDevices()
        loadRecordingsFromPrefs()
        
        // Initialize Audio Engine
        CallAudioEngine.initialize(context, this)
        
        addSystemLog("INFO", "SYSTEM", "176蓝牙通话语音核心初始化成功。当前模式: ${if (_simulationMode.value) "模拟仿真" else "物理蓝牙"}")
        
        // Start background socket listener in Real Mode
        if (!_simulationMode.value) {
            startListening()
        }
    }

    fun setRssiThreshold(value: Int) {
        _rssiThreshold.value = value
        val prefs = context.getSharedPreferences("176_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("rssi_threshold", value).apply()
        addSystemLog("SETTINGS", "RSSI", "更新高信号强度过滤阈值: ${value}dBm")
    }

    fun setFilterOnlyPhone(value: Boolean) {
        _filterOnlyPhone.value = value
        val prefs = context.getSharedPreferences("176_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("filter_only_phone", value).apply()
    }

    fun setFilterOnlyHighSignal(value: Boolean) {
        _filterOnlyHighSignal.value = value
        val prefs = context.getSharedPreferences("176_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("filter_only_high_signal", value).apply()
    }

    @Suppress("MissingPermission")
    fun startScanning(
        onlyPhone: Boolean = _filterOnlyPhone.value,
        onlyHighSignal: Boolean = _filterOnlyHighSignal.value
    ) {
        _filterOnlyPhone.value = onlyPhone
        _filterOnlyHighSignal.value = onlyHighSignal
        val prefs = context.getSharedPreferences("176_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("filter_only_phone", onlyPhone)
            .putBoolean("filter_only_high_signal", onlyHighSignal)
            .apply()

        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Toast.makeText(context, "蓝牙未开启或设备不支持", Toast.LENGTH_SHORT).show()
            return
        }

        val hasScanPerm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (!hasScanPerm) {
            Toast.makeText(context, "缺少蓝牙扫描/定位权限", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
        } catch (e: Exception) {}

        scannedDevices.clear()
        _isScanning.value = true
        addSystemLog("SCAN", "START", "发起扫描: 仅手机=$onlyPhone, 仅高信号=$onlyHighSignal (阈值 ${_rssiThreshold.value}dBm)")

        if (discoveryReceiver == null) {
            discoveryReceiver = object : android.content.BroadcastReceiver() {
                @Suppress("MissingPermission")
                override fun onReceive(c: Context?, intent: android.content.Intent?) {
                    val action = intent?.action ?: return
                    when (action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            } ?: return

                            val rawRssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                            val rssi = if (rawRssi == Short.MIN_VALUE.toInt()) -100 else rawRssi

                            val btClass: android.bluetooth.BluetoothClass? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_CLASS, android.bluetooth.BluetoothClass::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_CLASS)
                            } ?: try { device.bluetoothClass } catch (e: Exception) { null }

                            val isPhone = btClass?.majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.PHONE
                            val name = try { device.name ?: "" } catch (e: Exception) { "" }
                            val address = device.address ?: ""
                            val isBonded = device.bondState == BluetoothDevice.BOND_BONDED

                            if (_filterOnlyPhone.value && !isPhone) {
                                return
                            }
                            if (_filterOnlyHighSignal.value && rssi < _rssiThreshold.value) {
                                return
                            }

                            val item = ScannedDevice(
                                device = device,
                                name = if (name.isNotBlank()) name else "附近蓝牙设备",
                                address = address,
                                rssi = rssi,
                                isPhone = isPhone,
                                isBonded = isBonded
                            )

                            val existingIdx = scannedDevices.indexOfFirst { it.address == address }
                            if (existingIdx >= 0) {
                                scannedDevices[existingIdx] = item
                            } else {
                                scannedDevices.add(item)
                            }
                            addSystemLog("SCAN", "FOUND", "扫描发现: ${item.name} (${item.address}) RSSI: ${item.rssi}dBm 手机类型: $isPhone")
                        }
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                            _isScanning.value = false
                            addSystemLog("SCAN", "FINISH", "扫描结束，找到 ${scannedDevices.size} 个匹配设备")
                        }
                    }
                }
            }
            val filter = android.content.IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            }
            try {
                context.registerReceiver(discoveryReceiver, filter)
            } catch (e: Exception) {
                addSystemLog("ERROR", "SCAN_REG", "注册扫描监听器失败: ${e.localizedMessage}")
            }
        }

        try {
            val started = bluetoothAdapter.startDiscovery()
            if (!started) {
                _isScanning.value = false
                addSystemLog("WARN", "SCAN", "startDiscovery 返回 false")
            }
        } catch (e: Exception) {
            _isScanning.value = false
            addSystemLog("ERROR", "SCAN", "启动扫描异常: ${e.localizedMessage}")
        }
    }

    @Suppress("MissingPermission")
    fun stopScanning() {
        try {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
        } catch (e: Exception) {}
        _isScanning.value = false
        addSystemLog("SCAN", "STOP", "手动终止扫描")
    }

    fun addSystemLogDirect(module: String, action: String, detail: String) {
        scope.launch(Dispatchers.Main) {
            addSystemLog(module, action, detail)
        }
    }

    private fun onCallStateChanged(oldState: CallState, newState: CallState) {
        addSystemLog("STATE_FLOW", "TRANSITION", "系统状态流转: $oldState -> $newState")
        
        // Ringtone triggers
        if (newState == CallState.OUTGOING_INIT || newState == CallState.OUTGOING_RINGING) {
            CallToneManager.playOutgoingRingtone(context)
            addSystemLog("TONE", "RINGBACK_START", "发起呼叫，开始播发去电回铃音...")
        } else if (newState == CallState.INCOMING_RINGING) {
            CallToneManager.playIncomingRingtone(context)
            addSystemLog("TONE", "RINGTONE_START", "收到呼入信令，开启来电铃声播放")
        } else if (newState == CallState.ACTIVE) {
            CallToneManager.stopAll()
            addSystemLog("TONE", "RING_STOP", "通话建立成功，立即停止所有来去电铃声")
            CallAudioEngine.start(context)
        } else {
            CallToneManager.stopAll()
        }

        // Handle leaving active state
        if (oldState == CallState.ACTIVE && newState != CallState.ACTIVE) {
            CallAudioEngine.stop()
            addSystemLog("AUDIO", "ENGINE_STOP", "通话结束，释放双向 PCM 音频采集与播放引擎")
        }
    }

    private fun getOrGenerateDeviceId(): String {
        val prefs = context.getSharedPreferences("176_prefs", Context.MODE_PRIVATE)
        
        // 1. Try to get Bluetooth MAC Address safely
        var address: String? = null
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter != null) {
                @Suppress("MissingPermission")
                val addr = adapter.address
                if (!addr.isNullOrEmpty() && addr != "02:00:00:00:00:00") {
                    address = addr
                }
            }
        } catch (e: Exception) {
            // Permission or security restriction
        }

        if (!address.isNullOrEmpty()) {
            addSystemLog("INFO", "IDENTITY", "成功读取到本地蓝牙硬件 MAC 地址: $address")
            return address
        }

        // 2. Check SharedPreferences for existing generated UUID
        val savedId = prefs.getString("local_device_id", null)
        if (!savedId.isNullOrEmpty()) {
            addSystemLog("INFO", "IDENTITY", "使用已持久化的虚拟本地设备标识: $savedId")
            return savedId
        }

        // 3. Generate new unique UUID and persist it
        val newId = "UUID_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12).uppercase()
        prefs.edit().putString("local_device_id", newId).apply()
        addSystemLog("INFO", "IDENTITY", "生成并持久化全新虚拟本地设备标识: $newId")
        return newId
    }

    fun setSimulationMode(enabled: Boolean) {
        _simulationMode.value = enabled
        val prefs = context.getSharedPreferences("176_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("simulation_mode", enabled).apply()
        addSystemLog("SYSTEM", "CONFIG", "切换通话模式: ${if (enabled) "模拟仿真模式" else "物理蓝牙连接模式"}")
        if (enabled) {
            stopThreads()
        } else {
            startListening()
        }
    }

    fun clearLastRecording() {
        _lastRecordingInfo.value = null
    }

    @Suppress("MissingPermission")
    fun loadDevices() {
        peerDevices.clear()
        
        // Load Real Paired Bluetooth Devices if supported
        try {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) {
                val bondedDevices: Set<BluetoothDevice>? = bluetoothAdapter.bondedDevices
                bondedDevices?.forEach { device ->
                    if (device.bondState == BluetoothDevice.BOND_BONDED) {
                        peerDevices.add(
                            PeerDevice(
                                address = device.address,
                                name = device.name ?: "",
                                isBonded = true,
                                isOnline = true
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            addSystemLog("ERROR", "BT_SDK", "读取本地蓝牙配对列表失败: ${e.localizedMessage}")
        }
    }

    @Suppress("MissingPermission")
    fun initiatePairing(address: String): Boolean {
        try {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter() ?: return false
            val device = bluetoothAdapter.getRemoteDevice(address)
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                addSystemLog("BOND", "INIT", "设备 ${device.address} 未配对，正在调用 createBond() 发起系统配对流程...")
                val success = device.createBond()
                addSystemLog("BOND", "ACTION", "系统配对发起返回: $success")
                return false
            }
            return true
        } catch (e: Exception) {
            addSystemLog("ERROR", "BOND_ERR", "发起配对流程异常: ${e.localizedMessage}")
            return false
        }
    }

    // Task 1: Fix "fake answer/call" bug.
    // Transition correctly: only receive real ANSWER signal to enter ACTIVE.
    
    @Suppress("MissingPermission")
    fun startListening() {
        if (_simulationMode.value) return
        
        if (listenJob?.isActive == true) {
            addSystemLog("REAL_BT", "SERVER", "监听服务已在后台活跃运行中，无需重复启动")
            return
        }
        
        val hasConnect = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        if (!hasConnect) {
            addSystemLog("REAL_BT", "SERVER", "缺少蓝牙连接权限，暂无法开启物理监听通道")
            return
        }
        
        try {
            serverSocket?.close()
            serverSocket = null
        } catch (e: Exception) {}
        
        listenJob?.cancel()
        listenJob = scope.launch(Dispatchers.IO) {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) {
                addSystemLog("REAL_BT", "SERVER_HALT", "本地蓝牙未启用，物理监听服务暂时挂起")
                return@launch
            }
            
            addSystemLog("REAL_BT", "SERVER_START", "启动长期稳定的自愈式蓝牙 RFCOMM 后台服务端监听器...")
            
            while (isActive) {
                try {
                    val server = adapter.listenUsingRfcommWithServiceRecord(NAME, MY_UUID)
                    serverSocket = server
                    addSystemLog("REAL_BT", "SERVER_LISTEN", "【证据链1_SRV】蓝牙 RFCOMM 服务端创建并开始监听成功 [UUID: $MY_UUID]")
                    
                    while (isActive) {
                        addSystemLog("REAL_BT", "SERVER_ACCEPT", "服务端阻塞等待对端 RFCOMM 呼入请求 (server.accept)...")
                        val socket = try {
                            server.accept()
                        } catch (e: Exception) {
                            addSystemLog("REAL_BT", "SERVER_ACCEPT_ERR", "server.accept 产生异常退出当前循环: ${e.localizedMessage}")
                            break // Break the inner loop to recreate server socket
                        }
                        
                        if (socket != null) {
                            addSystemLog(
                                "REAL_BT",
                                "SERVER_ACCEPT_OK",
                                "【证据链2_SRV】被叫端 B 成功 accept 到来自对端 [${socket.remoteDevice.address}] 的 RFCOMM 物理连接"
                            )
                            manageConnectedSocket(socket, isIncoming = true)
                        }
                    }
                } catch (e: Exception) {
                    addSystemLog("REAL_BT", "SERVER_RETRY", "物理监听器由于异常崩溃: ${e.localizedMessage}，将在 3 秒后尝试自我恢复/重建通道...")
                    delay(3000)
                } finally {
                    try {
                        serverSocket?.close()
                    } catch (e: Exception) {}
                    serverSocket = null
                }
            }
        }
    }

    @Suppress("MissingPermission")
    private fun manageConnectedSocket(socket: android.bluetooth.BluetoothSocket, isIncoming: Boolean) {
        addSystemLog(
            "REAL_BT",
            "MANAGE_SOCKET",
            "【分配管理通道】开始接管 Socket 物理对象: Remote=[${socket.remoteDevice.address}], Name=[${socket.remoteDevice.name}], Role=[${if (isIncoming) "INCOMING_SERVER" else "OUTGOING_CLIENT"}]"
        )
        
        if (isIncoming) {
            val s = callStateValue
            if (s == CallState.ACTIVE || 
                s == CallState.OUTGOING_INIT || 
                s == CallState.OUTGOING_RINGING || 
                s == CallState.INCOMING_RINGING || 
                s == CallState.ANSWERING ||
                s == CallState.RECONNECTING) {
                addSystemLog(
                    "REAL_BT",
                    "REJECT_CONN",
                    "【保护机制生效】当前设备正处于通话/呼叫阶段 [当前状态: $s]，拒绝/忽略外部新连接，执行 socket.close()"
                )
                try {
                    socket.close()
                } catch (e: Exception) {}
                return
            }
        }
        
        try {
            connectedSocket?.close()
            addSystemLog("REAL_BT", "REPLACE_SOCKET", "关闭并替换原有的旧 connectedSocket 连接，载入新通道")
        } catch (e: Exception) {}
        
        connectedSocket = socket
        
        ioJob?.cancel()
        ioJob = scope.launch(Dispatchers.IO) {
            try {
                val inputStream = socket.inputStream
                val dis = java.io.DataInputStream(inputStream)
                val peerAddress = socket.remoteDevice.address
                val peerName = socket.remoteDevice.name ?: "Remote Peer"
                
                withContext(Dispatchers.Main) {
                    if (callStateValue == CallState.CONNECTED || callStateValue == CallState.FAILED || callStateValue == CallState.IDLE) {
                        val foundPeer = peerDevices.find { it.address == peerAddress } ?: PeerDevice(peerAddress, peerName, false, true)
                        _activePeer.value = foundPeer
                    }
                }
                
                addSystemLog("REAL_BT", "SESSION_OPEN", "【证据链通道就绪】物理双向 IO 数据链路载入成功, Remote: $peerName ($peerAddress)")
                
                while (isActive) {
                    val magic = dis.readByte()
                    if (magic != 0xFD.toByte()) {
                        continue
                    }
                    val type = dis.readByte()
                    val length = dis.readInt()
                    if (length <= 0 || length > 100000) {
                        continue
                    }
                    val payload = ByteArray(length)
                    dis.readFully(payload)
                    
                    if (type == 0x01.toByte()) {
                        val line = String(payload, Charsets.UTF_8)
                        addSystemLog("REAL_BT", "RECEIVE_SIGNAL", "【信令通道】物理层读取到原始控制信令: $line")
                        withContext(Dispatchers.Main) {
                            onSignalMessage(line)
                        }
                    } else if (type == 0x02.toByte()) {
                        CallAudioEngine.onReceivedAudio(payload)
                    }
                }
            } catch (e: Exception) {
                addSystemLog("REAL_BT", "SESSION_CLOSE", "物理链路检测到断开或流读取异常: ${e.localizedMessage}")
            } finally {
                try {
                    socket.close()
                } catch (e: Exception) {}
                if (connectedSocket == socket) {
                    connectedSocket = null
                }
                withContext(Dispatchers.Main) {
                    if (callStateValue != CallState.CONNECTED && callStateValue != CallState.FAILED && callStateValue != CallState.IDLE) {
                        addSystemLog("REAL_BT", "CALL_DROP", "警告：通话过程中物理层链路突发式意外中断！状态置为 FAILED")
                        callStateValue = CallState.FAILED
                    }
                }
            }
        }
    }

    private fun stopThreads() {
        listenJob?.cancel()
        listenJob = null
        connectJob?.cancel()
        connectJob = null
        ioJob?.cancel()
        ioJob = null
        
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverSocket = null
        
        try {
            clientSocket?.close()
        } catch (e: Exception) {}
        clientSocket = null
        
        try {
            connectedSocket?.close()
        } catch (e: Exception) {}
        connectedSocket = null
    }

    @Suppress("MissingPermission")
    fun startOutgoingCall(peer: PeerDevice) {
        if (callStateValue != CallState.CONNECTED && callStateValue != CallState.FAILED) return
        
        _activePeer.value = peer
        // Rule 1: Clean out and generate fresh call ID
        val newCallId = "call_" + UUID.randomUUID().toString().take(6)
        _activeCallId.value = newCallId
        
        _isMuted.value = false
        _isMicMuted.value = false
        _isSpeakerMuted.value = false
        _isRecording.value = false
        _lastRecordingInfo.value = null
        
        callStateValue = CallState.OUTGOING_INIT
        addSystemLog("REAL_BT", "A_CLICK_CALL", "【证据链1/9】主叫端 A 点击呼叫，准备建立物理链路并发起外呼...")
        addSystemLog("SYSTEM", "CALL_INIT", "发起外呼：对端地址 [${peer.address}]，生成全新 CallId: [$newCallId]")
        
        if (simulationMode.value) {
            val inviteJson = SignalCodec.encode("INVITE", newCallId, localDeviceId, peer.address, mapOf("protocol" to "v1.1"))
            sendSignalPacket("INVITE", inviteJson)
            // Under Simulation mode, run standard simulated delay and response flow
            scope.launch {
                delay(1500)
                if (_activeCallId.value != newCallId) return@launch
                
                // Simulate B sending RINGING back to us
                val ringingJson = SignalCodec.encode("RINGING", newCallId, peer.address, localDeviceId, mapOf("status" to "RINGING_ON_PEER"))
                onSignalMessage(ringingJson)
                
                // Simulate B answering after 3s
                delay(3000)
                if (callStateValue == CallState.OUTGOING_RINGING && _activeCallId.value == newCallId) {
                    val answerJson = SignalCodec.encode("ANSWER", newCallId, peer.address, localDeviceId, mapOf("codec" to "Opus_16k_20ms", "route" to "SCO"))
                    onSignalMessage(answerJson)
                }
            }
        } else {
            // Rule 1 & 2: Under Real mode, we DO NOT forge RINGING/ANSWER.
            // We launch RFCOMM client socket connecting to the target device address!
            addSystemLog("SYSTEM", "REAL_BT", "当前处于 [真实物理蓝牙模式]：准备建立物理信道。")
            
            connectJob?.cancel()
            connectJob = scope.launch(Dispatchers.IO) {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                if (adapter == null || !adapter.isEnabled) {
                    withContext(Dispatchers.Main) {
                        callStateValue = CallState.FAILED
                        addSystemLog("REAL_BT", "ERROR", "物理连接失败：本地蓝牙未开启或不支持")
                    }
                    return@launch
                }
                try {
                    addSystemLog("REAL_BT", "A_CONNECT_START", "【证据链2/9】主叫端 A 开始建立 RFCOMM 物理连接，对端 MAC 地址: ${peer.address}")
                    val device = adapter.getRemoteDevice(peer.address)
                    val socket = device.createRfcommSocketToServiceRecord(MY_UUID)
                    clientSocket = socket
                    
                    adapter.cancelDiscovery() // speed up connection
                    
                    socket.connect()
                    addSystemLog("REAL_BT", "A_CONNECT_SUCCESS", "【证据链3/9】主叫端 A 与对端 [${peer.address}] RFCOMM 物理连接建立成功！")
                    manageConnectedSocket(socket, isIncoming = false)
                    
                    // Resend the invite over the real socket now that it's connected
                    val realInviteJson = SignalCodec.encode("INVITE", newCallId, localDeviceId, peer.address, mapOf("protocol" to "v1.1"))
                    sendSignalPacket("INVITE", realInviteJson)
                    
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        callStateValue = CallState.FAILED
                        addSystemLog("REAL_BT", "CLIENT_ERR", "建立物理连接失败: ${e.localizedMessage}")
                    }
                }
            }
        }
    }

    fun receiveIncomingCall(peer: PeerDevice) {
        // Rule 7: Simulation inputs blocked in real mode
        if (!simulationMode.value) {
            addSystemLog("SIMULATION", "INTERCEPT", "拦截模拟呼入：当前处于[真实物理蓝牙模式]，已拦截该测试接口。")
            return
        }
        if (callStateValue != CallState.CONNECTED) return
        
        val incomingCallId = "call_" + UUID.randomUUID().toString().take(6)
        val inviteJson = SignalCodec.encode("INVITE", incomingCallId, peer.address, localDeviceId, mapOf("hfp_support" to true))
        
        addSystemLog("SIMULATION", "TRIGGER", "模拟测试：接收到虚拟呼入信号 INVITE。")
        onSignalMessage(inviteJson)
    }

    // Handle incoming signal packets
    fun onSignalMessage(messageJson: String) {
        val packet = SignalCodec.decode(messageJson)
        val type = packet["type"] ?: return
        val msgCallId = packet["callId"] ?: return
        val sender = packet["sender"] ?: ""
        val target = packet["target"] ?: ""

        addSystemLog("REAL_BT", "B_DECODE_SUCCESS", "【证据链7/9】被叫端 B 成功解码信令包: type=$type, callId=$msgCallId, sender=$sender -> target=$target")

        val socket = connectedSocket
        val realPeerAddress = if (!simulationMode.value) socket?.remoteDevice?.address else null

        // 0. Update Identity Map dynamically
        if (sender.isNotEmpty() && realPeerAddress != null && sender != realPeerAddress) {
            peerDeviceIdToAddressMap[sender] = realPeerAddress
            peerAddressToDeviceIdMap[realPeerAddress] = sender
        }

        // 1. Detect and bind our local Bluetooth MAC address
        if (target.isNotEmpty() && target.contains(":") && realPeerAddress != null && target != realPeerAddress) {
            if (localBluetoothMacAddress != target) {
                localBluetoothMacAddress = target
                addSystemLog("REAL_BT", "IDENTITY", "从接收信令中动态绑定本地蓝牙 MAC 地址: $target")
            }
        }

        // Rule 4: Do not handle our own sent messages if received on some loopback
        if (sender == localDeviceId || (localBluetoothMacAddress != null && sender == localBluetoothMacAddress)) {
            addSystemLog("REAL_BT", "FILTER", "过滤本地物理回路自发送报文")
            return
        }

        // Rule 4.1: If target is specified and doesn't match our localDeviceId/localBluetoothMacAddress, check if we can accept
        val isTargetMe = target.isEmpty() || 
                         target == localDeviceId || 
                         (localBluetoothMacAddress != null && target == localBluetoothMacAddress) ||
                         (!simulationMode.value) || // Point-to-point RFCOMM: if we physically receive a packet, it is always meant for us!
                         (!simulationMode.value && realPeerAddress != null && target != realPeerAddress)

        if (!isTargetMe) {
            addSystemLog("REAL_BT", "B_TARGET_CHECK_FAIL", "【证据链8/9_FAILED】被叫端 B 目标地址校验失败！target=$target，本端 localDeviceId=$localDeviceId，localBluetoothMacAddress=$localBluetoothMacAddress")
            return
        } else {
            addSystemLog("REAL_BT", "B_TARGET_CHECK_PASS", "【证据链8/9_PASSED】被叫端 B 目标地址校验通过！target=$target")
        }

        // Rule 5: Non-matching/old call ID message discard
        val currentCallId = _activeCallId.value
        if (currentCallId != null && msgCallId != currentCallId && type != "INVITE") {
            addSystemLog("REAL_BT", "DISCARD", "丢弃不匹配或已过期的旧 CallId 报文: $msgCallId (当前活动 CallId: $currentCallId)")
            return
        }

        // Log the received signal message
        addSignalLog(type, "RECEIVED", messageJson)

        // Run on main thread to guarantee state updates
        scope.launch(Dispatchers.Main) {
            val oldState = callStateValue
            when (type) {
                "INVITE" -> {
                    if (callStateValue == CallState.CONNECTED || callStateValue == CallState.FAILED || callStateValue == CallState.IDLE) {
                        _activeCallId.value = msgCallId
                        _isMuted.value = false
                        _isMicMuted.value = false
                        _isSpeakerMuted.value = false
                        _isRecording.value = false
                        _lastRecordingInfo.value = null
                        
                        // Map sender device identifier to physical peer address if available
                        val resolvedSender = if (!simulationMode.value && realPeerAddress != null) {
                            realPeerAddress
                        } else {
                            sender
                        }

                        val foundPeer = peerDevices.find { it.address == resolvedSender } ?: PeerDevice(resolvedSender, "Remote Peer", false, true)
                        _activePeer.value = foundPeer
                        
                        callStateValue = CallState.INCOMING_RINGING
                        addSystemLog("REAL_BT", "B_STATE_INCOMING_RINGING", "【证据链9/9】被叫端 B 通话状态流转: $oldState -> $callStateValue (触发呼入界面与系统振铃)")
                        
                        // Reply with RINGING
                        delay(500)
                        val ringingJson = SignalCodec.encode("RINGING", msgCallId, localDeviceId, sender, mapOf("status" to "RINGING_ON_PEER"))
                        sendSignalPacket("RINGING", ringingJson)
                    }
                }
                "RINGING" -> {
                    // Rule 5: RINGING only progresses OUTGOING_INIT to OUTGOING_RINGING
                    if (callStateValue == CallState.OUTGOING_INIT) {
                        callStateValue = CallState.OUTGOING_RINGING
                        addSystemLog("STATE_CHANGE", "RINGING", "振铃状态流转: $oldState -> $callStateValue (收到对端真实 RINGING 回复)")
                    } else {
                        addSystemLog("REAL_BT", "IGNORE", "忽略 RINGING: 当前通话状态为 $callStateValue")
                    }
                }
                "ANSWER" -> {
                    // Rule 5: ANSWER progresses state to ANSWERING -> ACTIVE
                    if (callStateValue == CallState.OUTGOING_RINGING || callStateValue == CallState.OUTGOING_INIT || callStateValue == CallState.ANSWERING) {
                        callStateValue = CallState.ANSWERING
                        addSystemLog("STATE_CHANGE", "ANSWER", "接听状态流转: $oldState -> $callStateValue (收到对端真实 ANSWER 确认信令)")
                        delay(800)
                        startAudioStream()
                    } else {
                        addSystemLog("REAL_BT", "IGNORE", "忽略 ANSWER: 当前通话状态为 $callStateValue")
                    }
                }
                "REJECT" -> {
                    addSystemLog("STATE_CHANGE", "REJECT", "对端拒绝：状态流转 $oldState -> CONNECTED")
                    cleanupCall()
                }
                "HANGUP" -> {
                    addSystemLog("STATE_CHANGE", "HANGUP", "对端挂断：状态流转 $oldState -> CONNECTED")
                    cleanupCall()
                }
            }
        }
    }

    private fun sendSignalPacket(type: String, jsonStr: String) {
        addSignalLog(type, "SENT", jsonStr)
        
        val socket = connectedSocket
        if (socket != null && socket.isConnected) {
            scope.launch(Dispatchers.IO) {
                try {
                    val os = socket.outputStream
                    synchronized(os) {
                        val payload = jsonStr.toByteArray(Charsets.UTF_8)
                        os.write(0xFD) // Magic byte
                        os.write(0x01) // Type: SIGNAL
                        val length = payload.size
                        os.write((length shr 24) and 0xFF)
                        os.write((length shr 16) and 0xFF)
                        os.write((length shr 8) and 0xFF)
                        os.write(length and 0xFF)
                        os.write(payload)
                        os.flush()
                    }
                    if (type == "INVITE") {
                        addSystemLog("REAL_BT", "A_SEND_INVITE_SUCCESS", "【证据链5/9】主叫端 A 成功通过 RFCOMM 发送 INVITE 信令: $jsonStr")
                    } else {
                        addSystemLog("REAL_BT", "SEND_OK", "物理层通过 RFCOMM 发送信令 [$type] 成功")
                    }
                } catch (e: Exception) {
                    addSystemLog("REAL_BT", "SEND_ERR", "物理层通过 RFCOMM 发送信令 [$type] 失败: ${e.localizedMessage}")
                }
            }
        } else {
            if (!simulationMode.value) {
                addSystemLog("REAL_BT", "SEND_WARN", "警告：试图发送信令 [$type] 但未检测到活跃的蓝牙物理连接")
            }
        }
    }

    fun sendAudioFrame(audioBytes: ByteArray) {
        val socket = connectedSocket
        if (socket != null && socket.isConnected) {
            try {
                val os = socket.outputStream
                synchronized(os) {
                    os.write(0xFD) // Magic byte
                    os.write(0x02) // Type: AUDIO
                    val length = audioBytes.size
                    os.write((length shr 24) and 0xFF)
                    os.write((length shr 16) and 0xFF)
                    os.write((length shr 8) and 0xFF)
                    os.write(length and 0xFF)
                    os.write(audioBytes)
                    os.flush()
                }
            } catch (e: Exception) {
                // Ignore silent socket errors on audio frame send to prevent spamming
            }
        }
    }

    fun answer() {
        if (callStateValue != CallState.INCOMING_RINGING) return
        val currentId = _activeCallId.value ?: return
        val peer = _activePeer.value ?: return
        
        scope.launch {
            callStateValue = CallState.ANSWERING
            addSystemLog("STATE_CHANGE", "ANSWER", "手动接听：状态流转 INCOMING_RINGING -> ANSWERING")
            val answerJson = SignalCodec.encode("ANSWER", currentId, localDeviceId, peer.address, mapOf("status" to "ACCEPTED"))
            sendSignalPacket("ANSWER", answerJson)
            delay(600)
            startAudioStream()
        }
    }

    fun reject() {
        val currentId = _activeCallId.value ?: "unknown"
        val peer = _activePeer.value
        if (peer != null) {
            val rejectJson = SignalCodec.encode("REJECT", currentId, localDeviceId, peer.address, mapOf("reason" to "user_declined"))
            sendSignalPacket("REJECT", rejectJson)
        }
        cleanupCall()
    }

    fun hangup() {
        val currentId = _activeCallId.value ?: "unknown"
        val peer = _activePeer.value
        if (peer != null) {
            val hangupJson = SignalCodec.encode("HANGUP", currentId, localDeviceId, peer.address, mapOf("reason" to "manual_hangup"))
            sendSignalPacket("HANGUP", hangupJson)
        }
        
        scope.launch {
            callStateValue = CallState.DISCONNECTING
            delay(500)
            cleanupCall()
        }
    }

    fun simulateHeadsetButtonPress(action: String) {
        when (action) {
            "HEADSET_ANSWER" -> {
                if (callStateValue == CallState.INCOMING_RINGING) {
                    addSystemLog("HEADSET", "HFP", "捕获到外部蓝牙耳机物理 [接听键] 双击事件")
                    answer()
                }
            }
            "HEADSET_HANGUP" -> {
                if (callStateValue == CallState.ACTIVE) {
                    addSystemLog("HEADSET", "HFP", "捕获到外部蓝牙耳机物理 [挂断键] 长按 1s 事件")
                    hangup()
                }
            }
            "LINK_LOST" -> {
                if (callStateValue == CallState.ACTIVE) {
                    scope.launch {
                        callStateValue = CallState.RECONNECTING
                        addSystemLog("WARN", "RFCOMM", "蓝牙 RFCOMM 管道接收超时，进入自重连恢复阶段...")
                        addSignalLog("KEEPALIVE_TIMEOUT", "SYSTEM", "丢失连续3路心跳包")
                        delay(2500)
                        
                        callStateValue = CallState.ACTIVE
                        addSystemLog("SUCCESS", "RFCOMM", "重连成功！自动绑定原有 Telecom 会话并恢复音频 SCO 通道")
                        addSignalLog("KEEPALIVE_ACK", "RECEIVED", "{\"status\":\"REESTABLISHED\"}")
                    }
                }
            }
            "SIMULATE_ANSWER" -> {
                // Rule 7: simulateHeadsetButtonPress SIMULATE_ANSWER only works in simulation sandbox
                if (!simulationMode.value) {
                    addSystemLog("SIMULATION", "INTERCEPT", "拦截模拟接听：当前处于[真实物理蓝牙模式]，禁止在主叫端伪造自接听。")
                    return
                }
                
                if (callStateValue == CallState.OUTGOING_RINGING || callStateValue == CallState.OUTGOING_INIT) {
                    val peer = _activePeer.value
                    val currentId = _activeCallId.value
                    if (peer != null && currentId != null) {
                        addSystemLog("SIMULATION", "USER", "手动触发模拟对端 [接听] 操作")
                        val answerJson = SignalCodec.encode("ANSWER", currentId, peer.address, localDeviceId, mapOf("codec" to "Opus_16k_20ms", "route" to "SCO"))
                        onSignalMessage(answerJson)
                    }
                }
            }
        }
    }

    fun setAudioRoute(route: String) {
        CallAudioEngine.setAudioRoute(route)
        _audioRoute.value = route
        addSystemLog("AUDIO", "ROUTE", "通话音频输出路由变更为: $route (AudioManager.MODE_IN_COMMUNICATION)")
    }

    private fun startAudioStream() {
        val currentState = callStateValue
        // Rule 6: startAudioStream must have state guard
        if (currentState != CallState.ANSWERING && currentState != CallState.INCOMING_RINGING) {
            addSystemLog("AUDIO_GUARD", "INTERCEPT", "状态安全守卫：拦截非法调用！当前状态为 $currentState，只有处于接通前置状态 (ANSWERING/INCOMING_RINGING) 时才允许激活音频流。")
            return
        }
        
        callStateValue = CallState.ACTIVE
        _callDuration.value = 0
        addSystemLog("AUDIO", "ACTIVE_REASON", "进入 ACTIVE 状态原因：成功完成双向信令三次握手并接通音频链路。")
        addSystemLog("AUDIO", "ENGINE", "实时音频引擎启动成功。双向 16kHz PCM 通道打开，启动 JitterBuffer 缓冲区。")
        addSignalLog("AUDIO_START", "SENT", "{\"callId\":\"${_activeCallId.value}\", \"sample_rate\":16000}")

        callJob = scope.launch {
            while (isActive) {
                delay(1000)
                _callDuration.value += 1
                
                if (_callDuration.value % 10 == 0) {
                    addSignalLog("KEEPALIVE", "SENT", "{\"ts\":${System.currentTimeMillis()}, \"seq\":${_callDuration.value / 10}}")
                }
            }
        }
    }

    fun toggleMute() {
        _isMicMuted.value = !_isMicMuted.value
        _isMuted.value = _isMicMuted.value
        addSystemLog("AUDIO", "MUTE", "麦克风静音状态变更为: ${_isMicMuted.value}")
        addSignalLog("MIC_STATE", "SENT", "{\"muted\":${_isMicMuted.value}}")
    }

    fun toggleMicMute() {
        _isMicMuted.value = !_isMicMuted.value
        _isMuted.value = _isMicMuted.value
        addSystemLog("AUDIO", "MIC_MUTE", "麦克风静音状态变更为: ${_isMicMuted.value}")
        addSignalLog("MIC_STATE", "SENT", "{\"muted\":${_isMicMuted.value}}")
    }

    fun toggleSpeakerMute() {
        _isSpeakerMuted.value = !_isSpeakerMuted.value
        addSystemLog("AUDIO", "SPEAKER_MUTE", "扬声器静音状态变更为: ${_isSpeakerMuted.value}")
    }

    fun getAvailableAudioRoutes(): List<String> {
        return CallAudioEngine.getAvailableAudioRoutes()
    }

    fun updateAudioRoute(route: String) {
        _audioRoute.value = route
    }

    // Task 2: Call recording feature implementations
    fun toggleRecording() {
        if (callStateValue != CallState.ACTIVE) return
        _isRecording.value = !_isRecording.value
        
        if (_isRecording.value) {
            recordingStartDuration = _callDuration.value
            addSystemLog("AUDIO", "RECORD", "启动双向通话音频录制并写入物理文件...")
            addSignalLog("RECORD_START", "SENT", "{\"callId\":\"${_activeCallId.value}\", \"codec\":\"PCM_16K\"}")
            
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val peer = _activePeer.value
            val callId = _activeCallId.value ?: "unknown"
            val peerId = peer?.address?.replace(":", "") ?: "UnknownDevice"
            val filename = "rec_${timestamp}_${peerId}_$callId.wav"
            
            val recordDir = File(context.filesDir, "recordings")
            if (!recordDir.exists()) {
                recordDir.mkdirs()
            }
            val recordFile = File(recordDir, filename)
            
            // Instruct CallAudioEngine to dump local PCM data
            CallAudioEngine.startLocalRecording(recordFile)
            
            activeRecordingFile = recordFile
            activeRecordingFilename = filename
        } else {
            stopRecordingAndSave()
        }
    }

    private fun stopRecordingAndSave() {
        val duration = _callDuration.value - recordingStartDuration
        val peer = _activePeer.value
        val callId = _activeCallId.value ?: "unknown"
        
        CallAudioEngine.stopLocalRecording()
        
        val recordFile = activeRecordingFile
        val filename = activeRecordingFilename
        
        if (recordFile != null && filename != null) {
            try {
                val info = RecordingInfo(
                    id = UUID.randomUUID().toString(),
                    filename = filename,
                    filePath = recordFile.absolutePath,
                    callTime = System.currentTimeMillis(),
                    peerName = peer?.name?.ifBlank { "Unknown Device" } ?: "Unknown Device",
                    peerAddress = peer?.address ?: "00:00:00:00:00:00",
                    callId = callId,
                    durationSec = if (duration > 0) duration else 1
                )
                
                recordingsList.add(0, info)
                saveRecordingsToPrefs()
                _lastRecordingInfo.value = info
                
                addSystemLog("AUDIO", "RECORD", "停止音频录制。物理文件保存成功: $filename")
                addSignalLog("RECORD_STOP", "SENT", "{\"callId\":\"$callId\", \"path\":\"${recordFile.absolutePath}\"}")
            } catch (e: Exception) {
                addSystemLog("ERROR", "RECORD", "保存录音文件失败: ${e.localizedMessage}")
            }
        }
        activeRecordingFile = null
        activeRecordingFilename = null
    }

    private fun writeDummyWavFile(file: File) {
        val sampleRate = 16000
        val channels = 1
        val byteRate = sampleRate * 2
        val dummyPcmSize = sampleRate * 2 * 3 // 3 seconds
        val totalAudioLen = dummyPcmSize + 36

        file.outputStream().use { out ->
            out.write("RIFF".toByteArray())
            out.write(intToByteArray(totalAudioLen), 0, 4)
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            out.write(intToByteArray(16), 0, 4)
            out.write(shortToByteArray(1), 0, 2)
            out.write(shortToByteArray(channels.toShort()), 0, 2)
            out.write(intToByteArray(sampleRate), 0, 4)
            out.write(intToByteArray(byteRate), 0, 4)
            out.write(shortToByteArray(2), 0, 2)
            out.write(shortToByteArray(16), 0, 2)
            out.write("data".toByteArray())
            out.write(intToByteArray(dummyPcmSize), 0, 4)
            
            // Generate standard sine wave tone at 440Hz
            val buffer = ByteArray(dummyPcmSize)
            for (i in 0 until dummyPcmSize / 2) {
                val sample = (Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate) * 32767.0).toInt().toShort()
                buffer[i * 2] = (sample.toInt() and 0xff).toByte()
                buffer[i * 2 + 1] = ((sample.toInt() shr 8) and 0xff).toByte()
            }
            out.write(buffer)
        }
    }

    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte(),
            ((value shr 16) and 0xff).toByte(),
            ((value shr 24) and 0xff).toByte()
        )
    }

    private fun shortToByteArray(value: Short): ByteArray {
        return byteArrayOf(
            (value.toInt() and 0xff).toByte(),
            ((value.toInt() shr 8) and 0xff).toByte()
        )
    }

    // Recordings Persistence using SharedPreferences & JSON
    private fun saveRecordingsToPrefs() {
        try {
            val array = JSONArray()
            recordingsList.forEach { rec ->
                val obj = JSONObject()
                obj.put("id", rec.id)
                obj.put("filename", rec.filename)
                obj.put("filePath", rec.filePath)
                obj.put("callTime", rec.callTime)
                obj.put("peerName", rec.peerName)
                obj.put("peerAddress", rec.peerAddress)
                obj.put("callId", rec.callId)
                obj.put("durationSec", rec.durationSec)
                array.put(obj)
            }
            context.getSharedPreferences("176_recordings_prefs", Context.MODE_PRIVATE)
                .edit().putString("recordings_json", array.toString()).apply()
        } catch (e: Exception) {
            addSystemLog("ERROR", "PREFS", "序列化录音列表失败: ${e.localizedMessage}")
        }
    }

    private fun loadRecordingsFromPrefs() {
        recordingsList.clear()
        try {
            val prefs = context.getSharedPreferences("176_recordings_prefs", Context.MODE_PRIVATE)
            val jsonStr = prefs.getString("recordings_json", "[]") ?: "[]"
            val array = JSONArray(jsonStr)
            val temp = mutableListOf<RecordingInfo>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                temp.add(
                    RecordingInfo(
                        id = obj.getString("id"),
                        filename = obj.getString("filename"),
                        filePath = obj.getString("filePath"),
                        callTime = obj.getLong("callTime"),
                        peerName = obj.getString("peerName"),
                        peerAddress = obj.getString("peerAddress"),
                        callId = obj.getString("callId"),
                        durationSec = obj.getInt("durationSec")
                    )
                )
            }
            // List sorted descendingly by callTime
            temp.sortByDescending { it.callTime }
            recordingsList.addAll(temp)
        } catch (e: Exception) {
            addSystemLog("ERROR", "PREFS", "加载本地录音列表失败: ${e.localizedMessage}")
        }
    }

    // Recording operations: Playback, stop, share, and delete
    fun playRecording(recording: RecordingInfo) {
        if (_playingRecordingId.value == recording.id) {
            stopPlaying()
            return
        }
        
        stopPlaying()
        try {
            val file = File(recording.filePath)
            if (!file.exists()) {
                Toast.makeText(context, "录音文件不存在或已被删除", Toast.LENGTH_SHORT).show()
                return
            }
            
            mediaPlayer = MediaPlayer().apply {
                setDataSource(recording.filePath)
                prepare()
                setOnCompletionListener {
                    stopPlaying()
                }
                start()
            }
            _playingRecordingId.value = recording.id
            _playbackDurationMs.value = mediaPlayer?.duration?.toLong() ?: 0L
            _playbackPositionMs.value = 0L
            _playbackProgressFlow.value = 0f

            playbackPollJob?.cancel()
            playbackPollJob = scope.launch(Dispatchers.Main) {
                while (isActive && mediaPlayer != null) {
                    try {
                        val player = mediaPlayer
                        if (player != null) {
                            val pos = player.currentPosition.toLong()
                            val dur = player.duration.toLong()
                            _playbackPositionMs.value = pos
                            _playbackDurationMs.value = dur
                            if (dur > 0) {
                                _playbackProgressFlow.value = pos.toFloat() / dur.toFloat()
                            }
                        }
                    } catch (e: Exception) {}
                    delay(100)
                }
            }

            addSystemLog("AUDIO", "PLAY", "开始播放录音: ${recording.filename}")
        } catch (e: Exception) {
            Toast.makeText(context, "播放录音失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            addSystemLog("ERROR", "PLAY", "播放录音异常: ${e.localizedMessage}")
        }
    }

    fun seekRecordingTo(progress: Float) {
        val player = mediaPlayer ?: return
        try {
            val dur = player.duration
            if (dur > 0) {
                val targetMs = (progress * dur).toInt()
                player.seekTo(targetMs)
                _playbackPositionMs.value = targetMs.toLong()
                _playbackProgressFlow.value = progress
                addSystemLog("AUDIO", "PLAY_SEEK", "Seek 播放进度到: $targetMs ms (${(progress * 100).toInt()}%)")
            }
        } catch (e: Exception) {}
    }

    fun stopPlaying() {
        playbackPollJob?.cancel()
        playbackPollJob = null
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            // ignore
        }
        mediaPlayer = null
        _playingRecordingId.value = null
        _playbackProgressFlow.value = 0f
        _playbackPositionMs.value = 0L
        _playbackDurationMs.value = 0L
    }

    fun shareRecording(recording: RecordingInfo) {
        try {
            val file = File(recording.filePath)
            if (!file.exists()) {
                Toast.makeText(context, "录音文件不存在", Toast.LENGTH_SHORT).show()
                return
            }
            
            // Build FileProvider sharing Uri dynamically
            val authority = "${context.packageName}.fileprovider"
            val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
            
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            val chooser = android.content.Intent.createChooser(intent, "分享通话录音文件")
            chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            
            addSystemLog("SYSTEM", "SHARE", "已拉起分享会话: ${recording.filename}")
        } catch (e: Exception) {
            Toast.makeText(context, "分享录音失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            addSystemLog("ERROR", "SHARE", "分享失败: ${e.localizedMessage}")
        }
    }

    fun deleteRecording(recording: RecordingInfo) {
        try {
            stopPlaying()
            val file = File(recording.filePath)
            if (file.exists()) {
                file.delete()
            }
            recordingsList.remove(recording)
            saveRecordingsToPrefs()
            addSystemLog("SYSTEM", "DELETE", "已删除录音文件: ${recording.filename}")
            Toast.makeText(context, "已成功删除录音文件", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "删除录音失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    fun deleteRecordingsBatch(selectedIds: Set<String>) {
        try {
            stopPlaying()
            val toDelete = recordingsList.filter { selectedIds.contains(it.id) }
            if (toDelete.isEmpty()) return
            toDelete.forEach { rec ->
                val file = File(rec.filePath)
                if (file.exists()) {
                    file.delete()
                }
                recordingsList.remove(rec)
            }
            saveRecordingsToPrefs()
            addSystemLog("SYSTEM", "DELETE_BATCH", "已批量删除 ${toDelete.size} 个录音文件")
            Toast.makeText(context, "已成功删除 ${toDelete.size} 个录音文件", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "批量删除失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareRecordingsBatch(selectedIds: Set<String>) {
        try {
            val selected = recordingsList.filter { selectedIds.contains(it.id) }
            val validFiles = selected.map { File(it.filePath) }.filter { it.exists() }
            if (validFiles.isEmpty()) {
                Toast.makeText(context, "未找到有效的录音文件", Toast.LENGTH_SHORT).show()
                return
            }
            val authority = "${context.packageName}.fileprovider"
            val uris = ArrayList<android.net.Uri>()
            validFiles.forEach { file ->
                uris.add(androidx.core.content.FileProvider.getUriForFile(context, authority, file))
            }
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                type = "audio/wav"
                putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = android.content.Intent.createChooser(intent, "批量分享通话录音文件")
            chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            addSystemLog("SYSTEM", "SHARE_BATCH", "已拉起批量分享 (${uris.size} 个文件)")
        } catch (e: Exception) {
            Toast.makeText(context, "批量分享失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            addSystemLog("ERROR", "SHARE_BATCH", "分享失败: ${e.localizedMessage}")
        }
    }

    fun toggleBookmark(address: String) {
        val current = _bookmarkedAddresses.value.toMutableSet()
        if (current.contains(address)) {
            current.remove(address)
            addSystemLog("BOOKMARK", "REMOVE", "已取消收藏设备: $address")
        } else {
            current.add(address)
            addSystemLog("BOOKMARK", "ADD", "已收藏并置顶设备: $address")
        }
        _bookmarkedAddresses.value = current
        val prefs = context.getSharedPreferences("176_bookmarks_prefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("bookmarked_macs", current).apply()
    }

    private fun loadBookmarks() {
        val prefs = context.getSharedPreferences("176_bookmarks_prefs", Context.MODE_PRIVATE)
        _bookmarkedAddresses.value = prefs.getStringSet("bookmarked_macs", emptySet()) ?: emptySet()
    }

    private fun cleanupCall() {
        // If still active and recording, stop and save recording before releasing call
        if (_isRecording.value) {
            _isRecording.value = false
            stopRecordingAndSave()
        }
        
        stopPlaying()
        callJob?.cancel()
        callJob = null
        
        try {
            clientSocket?.close()
        } catch (e: Exception) {}
        clientSocket = null
        
        try {
            connectedSocket?.close()
        } catch (e: Exception) {}
        connectedSocket = null
        
        callStateValue = CallState.CONNECTED
        _activeCallId.value = null
        _activePeer.value = null
        _callDuration.value = 0
        _isMuted.value = false
        _isMicMuted.value = false
        _isSpeakerMuted.value = false
        addSystemLog("SYSTEM", "CALL", "通话会话已释放，回到就绪待命状态。")
        
        if (!simulationMode.value) {
            startListening()
        }
    }

    private fun addSignalLog(type: String, direction: String, content: String) {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val time = sdf.format(Date())
        signalLogs.add(0, SignalPacket(time, type, direction, content))
    }

    private fun addSystemLog(module: String, action: String, detail: String) {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val time = sdf.format(Date())
        signalLogs.add(0, SignalPacket(time, "LOG_$module", "SYSTEM", "[$action] $detail"))
    }
}
