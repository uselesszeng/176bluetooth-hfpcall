package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.btcall.BluetoothCallEngine
import com.example.btcall.CallState
import java.util.Locale

class FloatingCallService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    
    companion object {
        private const val NOTIFICATION_ID = 1076
        private const val CHANNEL_ID = "floating_call_channel"
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var floatingView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "176蓝牙通话悬浮窗服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持通话后台连接、悬浮窗及麦克风录制常驻运行"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        createNotificationChannel()
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("176蓝牙通话进行中")
            .setContentText("悬浮窗及后台麦克风语音流运行中...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        // Start Foreground Service with Microphone and ConnectedDevice types on supported versions
        try {
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                startForeground(NOTIFICATION_ID, notification, serviceType)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            BluetoothCallEngine.instance?.addSystemLogDirect("FLOATING", "FOREGROUND_START", "前台服务通知及麦克风保活通道启动成功")
        } catch (e: Exception) {
            BluetoothCallEngine.instance?.addSystemLogDirect("FLOATING", "FOREGROUND_ERR", "前台服务启动异常: ${e.localizedMessage}")
        }

        val engine = BluetoothCallEngine.instance
        if (engine == null) {
            stopSelf()
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        floatingView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingCallService)
            setViewTreeViewModelStoreOwner(this@FloatingCallService)
            setViewTreeSavedStateRegistryOwner(this@FloatingCallService)
            setContent {
                MaterialTheme {
                    FloatingWindowContent(engine)
                }
            }
        }

        val typeParam = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            typeParam,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 150
        }

        try {
            windowManager?.addView(floatingView, layoutParams)
            engine.addSystemLogDirect("FLOATING", "SHOW", "悬浮窗显示成功")
        } catch (e: Exception) {
            engine.addSystemLogDirect("FLOATING", "ERR", "添加悬浮窗失败: ${e.localizedMessage}")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        floatingView?.let { view ->
            try {
                view.disposeComposition()
                windowManager?.removeView(view)
                BluetoothCallEngine.instance?.addSystemLogDirect("FLOATING", "HIDE", "悬浮窗释放并隐藏成功")
            } catch (e: Exception) {}
        }
        floatingView = null
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    @Composable
    fun FloatingWindowContent(engine: BluetoothCallEngine) {
        val callState by engine.callState
        val peer by engine.activePeer
        val duration by engine.callDuration
        val isMicMuted by engine.isMicMuted

        // Automatically close the floating window if the call disconnects
        LaunchedEffect(callState) {
            if (callState == CallState.CONNECTED || callState == CallState.FAILED || callState == CallState.IDLE) {
                stopSelf()
            }
        }

        var offsetX by remember { mutableStateOf(100f) }
        var offsetY by remember { mutableStateOf(150f) }

        // Format duration helper
        val durationText = remember(duration) {
            val mins = duration / 60
            val secs = duration % 60
            String.format(Locale.getDefault(), "%02d:%02d", mins, secs)
        }

        val stateText = when (callState) {
            CallState.OUTGOING_INIT -> "呼叫中"
            CallState.OUTGOING_RINGING -> "回铃中"
            CallState.INCOMING_RINGING -> "来电中"
            CallState.ANSWERING -> "接听中"
            CallState.ACTIVE -> "通话中 $durationText"
            else -> "已挂断"
        }

        Card(
            modifier = Modifier
                .width(180.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        layoutParams?.let { lp ->
                            lp.x += dragAmount.x.toInt()
                            lp.y += dragAmount.y.toInt()
                            windowManager?.updateViewLayout(floatingView, lp)
                        }
                    }
                },
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xEE1E293B)), // semi-transparent slate
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Return button and Peer name
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            val intent = Intent(this@FloatingCallService, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            }
                            startActivity(intent)
                            engine.addSystemLogDirect("FLOATING", "CLICK_RETURN", "用户通过悬浮窗返回通话主界面")
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = "返回通话",
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Text(
                        text = peer?.name ?: "未知设备",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // State Text / Duration
                Text(
                    text = stateText,
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Control Actions: Mute & Hangup
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Mute button
                    FilledIconButton(
                        onClick = { engine.toggleMicMute() },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (isMicMuted) Color(0xFFEF4444) else Color(0xFF334155)
                        ),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = "静音",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Hangup button
                    FilledIconButton(
                        onClick = {
                            engine.hangup()
                            engine.addSystemLogDirect("FLOATING", "CLICK_HANGUP", "用户通过悬浮窗挂断电话")
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xFFEF4444)
                        ),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CallEnd,
                            contentDescription = "挂断",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
