package com.example.btcall

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.media.audiofx.AutomaticGainControl
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.util.PriorityQueue

object CallAudioEngine {
    private const val SAMPLE_RATE = 16000
    private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private const val FRAME_SIZE_SAMPLES = 320 // 20ms frame at 16kHz (instead of 40ms)
    private const val BUFFER_SIZE_BYTES = FRAME_SIZE_SAMPLES * 2 // 640 bytes

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var audioManager: AudioManager? = null
    private var originalAudioMode: Int = AudioManager.MODE_NORMAL
    private var originalSpeakerphone: Boolean = false

    // Hardware Audio Effects
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    @Volatile private var isStreaming = false
    private var recordThread: Thread? = null
    private var playThread: Thread? = null

    // Audio Frame data structure for Jitter Buffer
    class AudioFrame(
        val seq: Int,
        val timestamp: Long,
        val payload: ByteArray
    ) : Comparable<AudioFrame> {
        override fun compareTo(other: AudioFrame): Int {
            return this.seq.compareTo(other.seq)
        }
    }

    // Jitter Buffer members
    private val jitterBuffer = PriorityQueue<AudioFrame>()
    private var nextExpectedSeq = -1
    private var isBuffering = true
    private const val BUFFER_TARGET_SIZE = 5 // Buffer 5 frames (~100ms) for smooth playout
    private const val MAX_JITTER_SIZE = 15    // Max depth (~300ms) before dropping or catching up

    private val _micLevel = MutableStateFlow(0f)
    val micLevel = _micLevel.asStateFlow()

    private var recordingFile: File? = null
    private var recordingOutputStream: FileOutputStream? = null
    private var isRecordingLocal = false
    private var totalBytesRecorded = 0

    private var callEngineRef: BluetoothCallEngine? = null

    // Send Seq count
    private var sendSeq = 0

    // Metric counters (reported every second)
    private var framesSentSec = 0
    private var framesReceivedSec = 0
    private var framesDroppedSec = 0
    private var framesLateSec = 0
    private var silenceFramesPaddedSec = 0
    private var logTimer: java.util.Timer? = null

    fun initialize(context: Context, engine: BluetoothCallEngine) {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        callEngineRef = engine
    }

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (isStreaming) return
        isStreaming = true
        sendSeq = 0
        nextExpectedSeq = -1
        isBuffering = true

        framesSentSec = 0
        framesReceivedSec = 0
        framesDroppedSec = 0
        framesLateSec = 0
        silenceFramesPaddedSec = 0

        synchronized(jitterBuffer) {
            jitterBuffer.clear()
        }

        audioManager?.let { am ->
            originalAudioMode = am.mode
            originalSpeakerphone = am.isSpeakerphoneOn
            try {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                
                // Select route according to priorities: 1. Bluetooth -> 2. Earpiece -> 3. Speakerphone (avoid default speaker howling)
                val availableRoutes = getAvailableAudioRoutes()
                val targetRoute = when {
                    availableRoutes.contains("Bluetooth") -> "Bluetooth"
                    availableRoutes.contains("Earpiece") -> "Earpiece"
                    else -> "Speaker"
                }
                
                callEngineRef?.addSystemLogDirect(
                    "AUDIO",
                    "ROUTE_PRIORITY",
                    "通话建立！系统检测到可用音频路由: $availableRoutes，按高优先级自动切换至: $targetRoute"
                )
                setAudioRoute(targetRoute)
            } catch (e: Exception) {
                callEngineRef?.addSystemLogDirect("AUDIO", "ROUTE_ERR", "初始默认音频路由配置异常: ${e.localizedMessage}")
            }
        }

        try {
            val minPlayBufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
            val trackBufSize = Math.max(minPlayBufSize, BUFFER_SIZE_BYTES * 4)
            audioTrack = AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                SAMPLE_RATE,
                CHANNEL_OUT,
                ENCODING,
                trackBufSize,
                AudioTrack.MODE_STREAM
            )
            audioTrack?.play()
            callEngineRef?.addSystemLogDirect("AUDIO", "TRACK_INIT", "AudioTrack 播放流成功启动 [20ms 规格]")
        } catch (e: Exception) {
            callEngineRef?.addSystemLogDirect("AUDIO", "TRACK_ERR", "AudioTrack 初始化失败: ${e.localizedMessage}")
        }

        try {
            val minRecBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
            val recBufSize = Math.max(minRecBufSize, BUFFER_SIZE_BYTES * 4)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_IN,
                ENCODING,
                recBufSize
            )
            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord?.startRecording()
                callEngineRef?.addSystemLogDirect("AUDIO", "RECORD_INIT", "AudioRecord 录制成功启动 [20ms 规格]")

                // Initialize Hardware Audio Effects (AEC, NS, AGC) to resolve short-range howling & noise
                val audioSessionId = audioRecord?.audioSessionId ?: 0
                if (audioSessionId != 0) {
                    // Acoustic Echo Canceler (AEC)
                    if (AcousticEchoCanceler.isAvailable()) {
                        try {
                            aec = AcousticEchoCanceler.create(audioSessionId)
                            aec?.enabled = true
                            callEngineRef?.addSystemLogDirect("AUDIO_FX", "AEC_SUCCESS", "声学回声消除器 (AEC) 成功创建并启用")
                        } catch (e: Exception) {
                            callEngineRef?.addSystemLogDirect("AUDIO_FX", "AEC_ERR", "启用 AEC 异常: ${e.localizedMessage}")
                        }
                    } else {
                        callEngineRef?.addSystemLogDirect("AUDIO_FX", "AEC_UNAVAILABLE", "提示：该物理设备不支持硬件声学回声消除 (AEC)")
                    }

                    // Noise Suppressor (NS)
                    if (NoiseSuppressor.isAvailable()) {
                        try {
                            ns = NoiseSuppressor.create(audioSessionId)
                            ns?.enabled = true
                            callEngineRef?.addSystemLogDirect("AUDIO_FX", "NS_SUCCESS", "噪音抑制器 (NS) 成功创建并启用")
                        } catch (e: Exception) {
                            callEngineRef?.addSystemLogDirect("AUDIO_FX", "NS_ERR", "启用 NS 异常: ${e.localizedMessage}")
                        }
                    } else {
                        callEngineRef?.addSystemLogDirect("AUDIO_FX", "NS_UNAVAILABLE", "提示：该物理设备不支持硬件自动降噪 (NS)")
                    }

                    // Automatic Gain Control (AGC)
                    if (AutomaticGainControl.isAvailable()) {
                        try {
                            agc = AutomaticGainControl.create(audioSessionId)
                            agc?.enabled = true
                            callEngineRef?.addSystemLogDirect("AUDIO_FX", "AGC_SUCCESS", "自动增益控制 (AGC) 成功创建并启用")
                        } catch (e: Exception) {
                            callEngineRef?.addSystemLogDirect("AUDIO_FX", "AGC_ERR", "启用 AGC 异常: ${e.localizedMessage}")
                        }
                    } else {
                        callEngineRef?.addSystemLogDirect("AUDIO_FX", "AGC_UNAVAILABLE", "提示：该物理设备不支持硬件自动增益控制 (AGC)")
                    }
                }
            } else {
                callEngineRef?.addSystemLogDirect("AUDIO", "RECORD_ERR", "AudioRecord 录音源初始化状态失败，请检查系统录音权限！")
            }
        } catch (e: Exception) {
            callEngineRef?.addSystemLogDirect("AUDIO", "RECORD_ERR", "AudioRecord 启动发生异常: ${e.localizedMessage}")
        }

        startThreads()
        startMetricsTimer()
    }

    private fun startThreads() {
        recordThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = ByteArray(BUFFER_SIZE_BYTES)
            while (isStreaming) {
                val record = audioRecord
                if (record != null && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val read = record.read(buffer, 0, BUFFER_SIZE_BYTES)
                    if (read > 0) {
                        val isMicMuted = callEngineRef?.isMicMuted?.value ?: false
                        val rms = if (isMicMuted) 0f else calculateRMS(buffer, read)
                        _micLevel.value = rms

                        // If mic is muted, completely zero out buffer for waveform / local record / streaming
                        val tempBuffer = if (isMicMuted) {
                            ByteArray(read) // automatic all zeros
                        } else {
                            buffer
                        }

                        val pcmFrame = if (read == BUFFER_SIZE_BYTES) tempBuffer else tempBuffer.copyOf(read)
                        writeLocalRecordBytes(pcmFrame)

                        // Packaging into standard header format
                        // Header size: 16 bytes
                        // Payload size: pcmFrame.size
                        val packedBytes = ByteArray(16 + pcmFrame.size)
                        
                        // 1. Magic bytes: "AUDO" (4 bytes)
                        packedBytes[0] = 0x41
                        packedBytes[1] = 0x55
                        packedBytes[2] = 0x44
                        packedBytes[3] = 0x4F
                        
                        // 2. Seq: (Int, 4 bytes)
                        val currentSeq = sendSeq++
                        packedBytes[4] = ((currentSeq shr 24) and 0xFF).toByte()
                        packedBytes[5] = ((currentSeq shr 16) and 0xFF).toByte()
                        packedBytes[6] = ((currentSeq shr 8) and 0xFF).toByte()
                        packedBytes[7] = (currentSeq and 0xFF).toByte()
                        
                        // 3. Timestamp: (Long, 8 bytes)
                        val currentTs = System.currentTimeMillis()
                        packedBytes[8] = ((currentTs shr 56) and 0xFF).toByte()
                        packedBytes[9] = ((currentTs shr 48) and 0xFF).toByte()
                        packedBytes[10] = ((currentTs shr 40) and 0xFF).toByte()
                        packedBytes[11] = ((currentTs shr 32) and 0xFF).toByte()
                        packedBytes[12] = ((currentTs shr 24) and 0xFF).toByte()
                        packedBytes[13] = ((currentTs shr 16) and 0xFF).toByte()
                        packedBytes[14] = ((currentTs shr 8) and 0xFF).toByte()
                        packedBytes[15] = (currentTs and 0xFF).toByte()
                        
                        System.arraycopy(pcmFrame, 0, packedBytes, 16, pcmFrame.size)

                        callEngineRef?.sendAudioFrame(packedBytes)
                        framesSentSec++
                    }
                } else {
                    try { Thread.sleep(10) } catch (e: Exception) {}
                }
            }
        }, "CallAudioRecordThread")

        playThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val silenceBuffer = ByteArray(BUFFER_SIZE_BYTES) // Silent padding block

            while (isStreaming) {
                var frameToPlay: ByteArray? = null

                synchronized(jitterBuffer) {
                    if (isBuffering) {
                        // Pre-buffering phase: wait until threshold reached to absorb network jitter
                        if (jitterBuffer.size >= BUFFER_TARGET_SIZE) {
                            isBuffering = false
                            callEngineRef?.addSystemLogDirect("AUDIO_JITTER", "BUFFERED_READY", "JitterBuffer 首次缓冲就绪，开始播放音频数据帧...")
                        }
                    }

                    if (!isBuffering) {
                        val head = jitterBuffer.peek()
                        if (head == null) {
                            // Queue starving: play comfort silence and re-enter buffering phase
                            isBuffering = true
                            frameToPlay = silenceBuffer
                            silenceFramesPaddedSec++
                        } else {
                            if (nextExpectedSeq == -1) {
                                nextExpectedSeq = head.seq
                            }

                            if (head.seq < nextExpectedSeq) {
                                // Arrived too late: discard out-of-order packet to preserve real-time sync
                                jitterBuffer.poll()
                                framesLateSec++
                                framesDroppedSec++
                                // Force restart the evaluation in the next tick
                                return@synchronized
                            } else if (head.seq == nextExpectedSeq) {
                                // Exactly what we expect next
                                val frame = jitterBuffer.poll()
                                frameToPlay = frame?.payload
                                nextExpectedSeq++
                            } else {
                                // Frame Gap (packet loss / latency delay): head.seq > nextExpectedSeq
                                if (jitterBuffer.size >= 8) {
                                    // If queue is getting deeper, skip the gap to catch up (maintain low latency)
                                    val frame = jitterBuffer.poll()
                                    frameToPlay = frame?.payload
                                    nextExpectedSeq = head.seq + 1
                                } else {
                                    // Otherwise, generate comfort silence to wait for missing frames (PLC)
                                    frameToPlay = silenceBuffer
                                    nextExpectedSeq++ // Skip missing sequence
                                    silenceFramesPaddedSec++
                                }
                            }
                        }
                    } else {
                        // In buffering state: output silence
                        frameToPlay = silenceBuffer
                    }
                }

                if (frameToPlay != null) {
                    val isSpeakerMuted = callEngineRef?.isSpeakerMuted?.value ?: false
                    if (!isSpeakerMuted) {
                        val track = audioTrack
                        if (track != null && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            track.write(frameToPlay!!, 0, frameToPlay!!.size)
                        }
                    }
                }

                // Sleep precisely 20ms to match the sampling time base clock
                try {
                    Thread.sleep(20)
                } catch (e: Exception) {
                    break
                }
            }
        }, "CallAudioPlayThread")

        recordThread?.start()
        playThread?.start()
    }

    fun onReceivedAudio(audioData: ByteArray) {
        if (!isStreaming) return
        
        // Exclude invalid short packets
        if (audioData.size < 16) {
            framesDroppedSec++
            return
        }

        // Header Check: Magic "AUDO"
        val m0 = audioData[0]
        val m1 = audioData[1]
        val m2 = audioData[2]
        val m3 = audioData[3]
        if (m0 != 0x41.toByte() || m1 != 0x55.toByte() || m2 != 0x44.toByte() || m3 != 0x4F.toByte()) {
            framesDroppedSec++
            return
        }

        // Parse Sequence (Int, 4 bytes)
        val seq = ((audioData[4].toInt() and 0xFF) shl 24) or
                  ((audioData[5].toInt() and 0xFF) shl 16) or
                  ((audioData[6].toInt() and 0xFF) shl 8) or
                  (audioData[7].toInt() and 0xFF)

        // Parse Timestamp (Long, 8 bytes)
        val ts = ((audioData[8].toLong() and 0xFF) shl 56) or
                 ((audioData[9].toLong() and 0xFF) shl 48) or
                 ((audioData[10].toLong() and 0xFF) shl 40) or
                 ((audioData[11].toLong() and 0xFF) shl 32) or
                 ((audioData[12].toLong() and 0xFF) shl 24) or
                 ((audioData[13].toLong() and 0xFF) shl 16) or
                 ((audioData[14].toLong() and 0xFF) shl 8) or
                 (audioData[15].toLong() and 0xFF)

        // Copy raw PCM Payload
        val payloadSize = audioData.size - 16
        val payload = ByteArray(payloadSize)
        System.arraycopy(audioData, 16, payload, 0, payloadSize)

        synchronized(jitterBuffer) {
            // Discard late arriving packets directly
            if (nextExpectedSeq != -1 && seq < nextExpectedSeq) {
                framesLateSec++
                framesDroppedSec++
                return
            }

            // Bound the buffer depth to prevent latency build up or memory explosion
            if (jitterBuffer.size >= MAX_JITTER_SIZE) {
                val oldest = jitterBuffer.poll()
                if (oldest != null && nextExpectedSeq != -1 && oldest.seq >= nextExpectedSeq) {
                    nextExpectedSeq = oldest.seq + 1
                }
                framesDroppedSec++
            }

            jitterBuffer.offer(AudioFrame(seq, ts, payload))
            framesReceivedSec++
        }
    }

    private fun calculateRMS(buffer: ByteArray, length: Int): Float {
        var sum = 0.0
        val sampleCount = length / 2
        if (sampleCount == 0) return 0f
        for (i in 0 until sampleCount) {
            val low = buffer[i * 2].toInt() and 0xFF
            val high = buffer[i * 2 + 1].toInt()
            val sample = ((high shl 8) or low).toShort()
            sum += sample * sample
        }
        val rms = Math.sqrt(sum / sampleCount)
        val norm = (rms / 4000.0).toFloat() // conversional scaling
        return norm.coerceIn(0f, 1f)
    }

    fun startLocalRecording(file: File) {
        try {
            recordingFile = file
            recordingOutputStream = FileOutputStream(file)
            val dummyHeader = ByteArray(44)
            recordingOutputStream?.write(dummyHeader)
            totalBytesRecorded = 0
            isRecordingLocal = true
            callEngineRef?.addSystemLogDirect("RECORD", "START_IO", "本地录音流成功打开: ${file.name}")
        } catch (e: Exception) {
            callEngineRef?.addSystemLogDirect("RECORD", "IO_ERR", "创建录音流文件异常: ${e.localizedMessage}")
        }
    }

    private fun writeLocalRecordBytes(bytes: ByteArray) {
        if (!isRecordingLocal) return
        try {
            recordingOutputStream?.write(bytes)
            totalBytesRecorded += bytes.size
        } catch (e: Exception) {
            // ignore
        }
    }

    fun stopLocalRecording() {
        if (!isRecordingLocal) return
        isRecordingLocal = false
        try {
            recordingOutputStream?.flush()
            recordingOutputStream?.close()
            recordingOutputStream = null
            
            val file = recordingFile
            if (file != null && file.exists()) {
                writeWavHeader(file, totalBytesRecorded)
                callEngineRef?.addSystemLogDirect("RECORD", "STOP_IO", "录音写入完成，成功写入标准 WAV 帧，有效数据: $totalBytesRecorded 字节")
            }
        } catch (e: Exception) {
            callEngineRef?.addSystemLogDirect("RECORD", "IO_CLOSE_ERR", "更新本地 WAV 头异常: ${e.localizedMessage}")
        } finally {
            recordingFile = null
            totalBytesRecorded = 0
        }
    }

    private fun writeWavHeader(file: File, pcmSize: Int) {
        val randomAccessFile = java.io.RandomAccessFile(file, "rw")
        val channels = 1
        val sampleRate = SAMPLE_RATE
        val byteRate = sampleRate * 2
        val totalAudioLen = pcmSize
        val totalDataLen = totalAudioLen + 36

        val header = ByteArray(44)
        header[0] = 'R'.toByte()
        header[1] = 'I'.toByte()
        header[2] = 'F'.toByte()
        header[3] = 'F'.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.toByte()
        header[9] = 'A'.toByte()
        header[10] = 'V'.toByte()
        header[11] = 'E'.toByte()
        header[12] = 'f'.toByte()
        header[13] = 'm'.toByte()
        header[14] = 't'.toByte()
        header[15] = ' '.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = 2
        header[33] = 0
        header[34] = 16
        header[35] = 0
        header[36] = 'd'.toByte()
        header[37] = 'a'.toByte()
        header[38] = 't'.toByte()
        header[39] = 'a'.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        randomAccessFile.seek(0)
        randomAccessFile.write(header)
        randomAccessFile.close()
    }

    private fun startMetricsTimer() {
        logTimer?.cancel()
        logTimer = java.util.Timer()
        logTimer?.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                if (isStreaming) {
                    val depth = synchronized(jitterBuffer) { jitterBuffer.size }
                    callEngineRef?.addSystemLogDirect(
                        "AUDIO_PERF",
                        "STATS",
                        "【音频核心秒级统计】发送: ${framesSentSec} 帧/秒 | 接收: ${framesReceivedSec} 帧/秒 | 丢帧: ${framesDroppedSec} 帧/秒 (迟到: ${framesLateSec} 帧/秒) | 缓冲深度: $depth 帧 | 丢失补静音: ${silenceFramesPaddedSec} 帧/秒"
                    )
                    framesSentSec = 0
                    framesReceivedSec = 0
                    framesDroppedSec = 0
                    framesLateSec = 0
                    silenceFramesPaddedSec = 0
                }
            }
        }, 1000, 1000) // Precise 1 second cycle
    }

    fun stop() {
        if (!isStreaming) return
        isStreaming = false
        _micLevel.value = 0f

        logTimer?.cancel()
        logTimer = null

        stopLocalRecording()

        recordThread?.interrupt()
        try {
            recordThread?.join(500)
        } catch (e: Exception) {}
        recordThread = null

        playThread?.interrupt()
        try {
            playThread?.join(500)
        } catch (e: Exception) {}
        playThread = null

        try {
            aec?.release()
            ns?.release()
            agc?.release()
        } catch (e: Exception) {}
        aec = null
        ns = null
        agc = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {}
        audioRecord = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {}
        audioTrack = null

        audioManager?.let { am ->
            try {
                am.mode = originalAudioMode
                am.isSpeakerphoneOn = originalSpeakerphone
                callEngineRef?.addSystemLogDirect("AUDIO", "ROUTE_RESET", "挂断通话：重置音频物理状态，释放音频路由")
            } catch (e: Exception) {}
        }
    }

    fun setAudioRoute(route: String) {
        val am = audioManager ?: return
        callEngineRef?.addSystemLogDirect("AUDIO", "ROUTE_CHANGE", "用户请求切换音频路由为: $route")
        
        try {
            if (route == "Speaker") {
                // Safeguard against howling feedback loop on speakerphone
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                val safeVol = (maxVol * 0.5f).toInt() // Safe 50% limit to protect ears and reduce echo power
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, safeVol, AudioManager.FLAG_SHOW_UI)
                callEngineRef?.addSystemLogDirect(
                    "AUDIO",
                    "SPEAKER_VOLUME_LIMIT",
                    "【安全提示】切换至扬声器外放！已自动限流限制音量至 $safeVol (最大 $maxVol 的 50%)。扬声器模式下近距离通话容易引起回声啸叫，建议使用耳机/听筒或保持距离。"
                )
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                // Android 12+ API 31+ routing
                val devices = am.availableCommunicationDevices
                var targetDevice: AudioDeviceInfo? = null
                when (route) {
                    "Speaker" -> {
                        targetDevice = devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    }
                    "Earpiece" -> {
                        targetDevice = devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                    }
                    "Bluetooth" -> {
                        targetDevice = devices.find { 
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || 
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || 
                            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET 
                        }
                    }
                }
                if (targetDevice != null) {
                    val success = am.setCommunicationDevice(targetDevice)
                    callEngineRef?.addSystemLogDirect("AUDIO", "ROUTE_API_31", "API 31+ 切换设备 ${targetDevice.productName} (${targetDevice.type}) 结果: $success")
                } else {
                    // Fallback to legacy
                    fallbackAudioRoute(route)
                }
            } else {
                // Legacy routing for API < 31
                fallbackAudioRoute(route)
            }
            
            // Update routing state
            val routeLabel = when (route) {
                "Speaker" -> "Speakerphone"
                "Earpiece" -> "Earpiece"
                "Bluetooth" -> "Bluetooth Headset"
                else -> route
            }
            callEngineRef?.updateAudioRoute(routeLabel)
        } catch (e: Exception) {
            callEngineRef?.addSystemLogDirect("AUDIO", "ROUTE_ERR", "切换音频路由发生异常: ${e.localizedMessage}")
        }
    }

    @Suppress("DEPRECATION")
    private fun fallbackAudioRoute(route: String) {
        val am = audioManager ?: return
        when (route) {
            "Speaker" -> {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
                am.isSpeakerphoneOn = true
                callEngineRef?.addSystemLogDirect("AUDIO", "ROUTE_LEGACY", "Legacy: 开启扬声器, 关闭蓝牙 SCO")
            }
            "Earpiece" -> {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
                am.isSpeakerphoneOn = false
                callEngineRef?.addSystemLogDirect("AUDIO", "ROUTE_LEGACY", "Legacy: 使用听筒, 关闭扬声器, 关闭蓝牙 SCO")
            }
            "Bluetooth" -> {
                am.isSpeakerphoneOn = false
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
                callEngineRef?.addSystemLogDirect("AUDIO", "ROUTE_LEGACY", "Legacy: 启动蓝牙 SCO, 开启蓝牙音频")
            }
        }
    }

    fun getAvailableAudioRoutes(): List<String> {
        val routes = mutableListOf("Speaker", "Earpiece")
        val am = audioManager ?: return routes
        
        // Check if bluetooth is connected/available
        var isBluetoothConnected = false
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val devices = am.availableCommunicationDevices
            isBluetoothConnected = devices.any { 
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || 
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || 
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET 
            }
        } else {
            isBluetoothConnected = am.isBluetoothScoAvailableOffCall || am.isBluetoothA2dpOn
        }
        
        if (isBluetoothConnected) {
            routes.add("Bluetooth")
        }
        return routes
    }
}

