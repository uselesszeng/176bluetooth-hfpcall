package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import com.example.btcall.*
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.collectAsState
import kotlin.math.sin

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable

object LocaleHelper {
    fun setLocale(context: android.content.Context, lang: String) {
        val locale = java.util.Locale(lang)
        java.util.Locale.setDefault(locale)
        val resources = context.resources
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
        
        // Also update application context resources
        val appContext = context.applicationContext
        val appConfig = appContext.resources.configuration
        appConfig.setLocale(locale)
        appContext.resources.updateConfiguration(appConfig, appContext.resources.displayMetrics)
    }

    fun getSavedLanguage(context: android.content.Context): String {
        val prefs = context.getSharedPreferences("176_prefs", android.content.Context.MODE_PRIVATE)
        return prefs.getString("selected_lang", "zh") ?: "zh"
    }

    fun saveLanguage(context: android.content.Context, lang: String) {
        val prefs = context.getSharedPreferences("176_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("selected_lang", lang).apply()
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var callingEngine: BluetoothCallEngine
    val hasPermissions = mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.all { it.value }
        hasPermissions.value = granted
        if (granted) {
            callingEngine.loadDevices()
        }
    }

    private val bondStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            val action = intent?.action
            if (android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED == action) {
                val device = intent.getParcelableExtra<android.bluetooth.BluetoothDevice>(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                val bondState = intent.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE, android.bluetooth.BluetoothDevice.ERROR)
                
                if (device != null && bondState == android.bluetooth.BluetoothDevice.BOND_BONDED) {
                    callingEngine.loadDevices()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Force saved locale before onCreate/inflation
        LocaleHelper.setLocale(this, LocaleHelper.getSavedLanguage(this))
        
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        callingEngine = BluetoothCallEngine(this)

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    BluetoothAppMainScreen(
                        engine = callingEngine,
                        hasPermissions = hasPermissions,
                        onRequestPermissions = { checkAndRequestPermissions() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }

        checkAndRequestPermissions()
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(bondStateReceiver, android.content.IntentFilter(android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        stopFloatingCallOverlay()
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(bondStateReceiver)
        } catch (e: Exception) {}
        startFloatingCallOverlay()
    }

    private fun startFloatingCallOverlay() {
        val engine = callingEngine
        if (engine.callStateValue != CallState.CONNECTED && engine.callStateValue != CallState.IDLE && engine.callStateValue != CallState.FAILED) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && android.provider.Settings.canDrawOverlays(this)) {
                val intent = android.content.Intent(this, FloatingCallService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    androidx.core.content.ContextCompat.startForegroundService(this, intent)
                } else {
                    startService(intent)
                }
            } else {
                callingEngine.addSystemLogDirect("FLOATING", "WARN", "未获得悬浮窗权限，无法拉起后台悬浮窗")
            }
        }
    }

    private fun stopFloatingCallOverlay() {
        val intent = android.content.Intent(this, FloatingCallService::class.java)
        stopService(intent)
    }

    fun checkAndRequestPermissions() {
        val permissions = getRequiredPermissions()
        val allGranted = permissions.all {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        hasPermissions.value = allGranted
        if (!allGranted) {
            permissionLauncher.launch(permissions)
        } else {
            callingEngine.loadDevices()
        }
    }

    private fun getRequiredPermissions(): Array<String> {
        val list = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            list.add(android.Manifest.permission.BLUETOOTH_CONNECT)
            list.add(android.Manifest.permission.BLUETOOTH_SCAN)
        } else {
            list.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            list.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.toTypedArray()
    }
}

@Composable
fun BluetoothAppMainScreen(
    engine: BluetoothCallEngine,
    hasPermissions: State<Boolean>,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(0) }
    val currentCallState by engine.callState

    // Show permission warning alert dialog if permissions are not granted
    if (!hasPermissions.value) {
        AlertDialog(
            onDismissRequest = { /* Force permission choice */ },
            containerColor = Color(0xFF1E293B),
            title = { Text(stringResource(id = R.string.need_perm_alert_title), color = Color.White) },
            text = { Text(stringResource(id = R.string.need_perm_alert_desc), color = Color(0xFF94A3B8)) },
            confirmButton = {
                Button(
                    onClick = onRequestPermissions,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8))
                ) {
                    Text(stringResource(id = R.string.grant_now), color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF070B19), // Deep dark space blue
                        Color(0xFF0F172A)  // Dark slate
                    )
                )
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // App Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Bluetooth,
                        contentDescription = "Bluetooth Logo",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(id = R.string.app_name),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontFamily = FontFamily.SansSerif
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = Color(0xFF0EA5E9).copy(alpha = 0.2f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "v01.04",
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                // Interactive Simulator Status badge
                Surface(
                    color = Color(0xFF10B981).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(Color(0xFF10B981), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(id = R.string.demo_ready),
                            color = Color(0xFF34D399),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Tabs Header
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = Color(0xFF38BDF8),
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = Color(0xFF38BDF8)
                    )
                },
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(id = R.string.tab_dialer), fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                    icon = { Icon(Icons.Default.Phone, contentDescription = "Call", modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(id = R.string.tab_logs), fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                    icon = { Icon(Icons.Default.History, contentDescription = "Logs", modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text(stringResource(id = R.string.tab_recordings), fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                    icon = { Icon(Icons.Default.Mic, contentDescription = "Recordings", modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text(stringResource(id = R.string.tab_settings), fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings", modifier = Modifier.size(18.dp)) }
                )
            }

            // Tab Content
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                when (selectedTab) {
                    0 -> CallingTab(engine, hasPermissions)
                    1 -> LogsTab(engine)
                    2 -> RecordingsTab(engine)
                    3 -> SettingsTab(engine)
                }
            }
        }

        // Full-screen Overlays for Call States (Ringing, Active, etc.)
        if (currentCallState != CallState.CONNECTED && currentCallState != CallState.IDLE) {
            CallScreenOverlay(engine = engine)
        }
    }
}

@Composable
fun CallingTab(engine: BluetoothCallEngine, hasPermissions: State<Boolean>) {
    val devices = engine.peerDevices
    val bookmarkedAddresses by engine.bookmarkedAddresses
    
    val sortedDevices = remember(devices, bookmarkedAddresses) {
        devices.toList().sortedWith(
            compareByDescending<PeerDevice> { bookmarkedAddresses.contains(it.address) }
                .thenBy { it.name.ifBlank { "Unknown" } }
        )
    }
    
    var showIncomingSimulatorDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            // Header card introducing Moe Mascot / Banner
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFF334155)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Try to load generated mascot image. fallback to Bluetooth icon if needed
                    Box(
                        modifier = Modifier
                            .size(74.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0F172A)),
                        contentAlignment = Alignment.Center
                    ) {
                        val painter = painterResource(id = R.drawable.img_bluetooth_anime_icon_1784451811330)
                        Image(
                            painter = painter,
                            contentDescription = "Bluetooth Mascot Anime Logo",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(id = R.string.banner_title),
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(id = R.string.banner_body),
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(id = R.string.paired_devices),
                    color = Color(0xFF94A3B8),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                
                Button(
                    onClick = { showIncomingSimulatorDialog = true },
                    enabled = hasPermissions.value,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Default.PhoneCallback, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(id = R.string.simulate_incoming), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // List paired devices (sorted)
        items(sortedDevices) { device ->
            DeviceItemCard(
                device = device,
                engine = engine,
                hasPermissions = hasPermissions,
                onCallClick = { engine.startOutgoingCall(device) }
            )
        }
    }

    if (showIncomingSimulatorDialog) {
        AlertDialog(
            onDismissRequest = { showIncomingSimulatorDialog = false },
            containerColor = Color(0xFF1E293B),
            title = { Text(stringResource(id = R.string.dialog_title), color = Color.White) },
            text = {
                Column {
                    Text(stringResource(id = R.string.dialog_text), color = Color(0xFF94A3B8), fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    sortedDevices.forEach { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    engine.receiveIncomingCall(device)
                                    showIncomingSimulatorDialog = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Bluetooth, contentDescription = null, tint = Color(0xFF38BDF8))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = device.name.ifBlank { stringResource(id = R.string.unknown_device) },
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(device.address, color = Color(0xFF64748B), fontSize = 12.sp)
                            }
                        }
                        HorizontalDivider(color = Color(0xFF334155))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showIncomingSimulatorDialog = false }) {
                    Text(stringResource(id = R.string.cancel), color = Color(0xFF38BDF8))
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeviceItemCard(
    device: PeerDevice,
    engine: BluetoothCallEngine,
    hasPermissions: State<Boolean>,
    onCallClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val bookmarkedAddresses by engine.bookmarkedAddresses
    val isBookmarked = bookmarkedAddresses.contains(device.address)

    Box {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A).copy(alpha = 0.5f)),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, if (isBookmarked) Color(0xFFF59E0B) else Color(0xFF1E293B)),
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { /* Do nothing */ },
                    onLongClick = { showMenu = true }
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        color = if (isBookmarked) Color(0xFFF59E0B).copy(alpha = 0.1f) else Color(0xFF0EA5E9).copy(alpha = 0.1f),
                        shape = CircleShape,
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isBookmarked) Icons.Default.Star else Icons.Default.Bluetooth,
                                contentDescription = null,
                                tint = if (isBookmarked) Color(0xFFF59E0B) else Color(0xFF38BDF8),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = device.name.ifBlank { stringResource(id = R.string.unknown_device) },
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (isBookmarked) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = "Starred",
                                    tint = Color(0xFFF59E0B),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = device.address,
                            color = Color(0xFF64748B),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onCallClick,
                    enabled = hasPermissions.value,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0284C7),
                        disabledContainerColor = Color(0xFF1E293B).copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    modifier = Modifier
                        .width(90.dp)
                        .height(38.dp)
                        .testTag("call_button_${device.address.replace(":", "")}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(id = R.string.call_btn), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            modifier = Modifier.background(Color(0xFF1E293B))
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(id = R.string.menu_copy_name), color = Color.White) },
                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color(0xFF38BDF8)) },
                onClick = {
                    showMenu = false
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("Device Name", device.name.ifBlank { "Unknown Device" })
                    clipboard.setPrimaryClip(clip)
                    android.widget.Toast.makeText(context, context.getString(R.string.copied_toast), android.widget.Toast.LENGTH_SHORT).show()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(id = R.string.menu_copy_mac), color = Color.White) },
                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color(0xFF38BDF8)) },
                onClick = {
                    showMenu = false
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("MAC Address", device.address)
                    clipboard.setPrimaryClip(clip)
                    android.widget.Toast.makeText(context, context.getString(R.string.copied_toast), android.widget.Toast.LENGTH_SHORT).show()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(id = R.string.menu_share), color = Color.White) },
                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = Color(0xFF38BDF8)) },
                onClick = {
                    showMenu = false
                    val shareIntent = android.content.Intent().apply {
                        action = android.content.Intent.ACTION_SEND
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, "${device.name} (${device.address})")
                    }
                    context.startActivity(android.content.Intent.createChooser(shareIntent, context.getString(R.string.menu_share)))
                }
            )
            DropdownMenuItem(
                text = { Text(if (isBookmarked) stringResource(id = R.string.menu_unfavorite) else stringResource(id = R.string.menu_favorite), color = Color.White) },
                leadingIcon = { Icon(if (isBookmarked) Icons.Default.StarBorder else Icons.Default.Star, contentDescription = null, tint = Color(0xFFF59E0B)) },
                onClick = {
                    showMenu = false
                    engine.toggleBookmark(device.address)
                }
            )
        }
    }
}

@Composable
fun LogsTab(engine: BluetoothCallEngine) {
    val logs = engine.signalLogs

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(id = R.string.logs_header),
                color = Color(0xFF94A3B8),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val context = LocalContext.current
                TextButton(
                    onClick = { LogCsvExporter.exportAndShareLogs(context, logs) },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF38BDF8))
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("导出 CSV", fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = { logs.clear() },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF4444))
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(id = R.string.clear_terminal), fontSize = 12.sp)
                }
            }
        }

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                    .background(Color(0xFF0B0F19)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = Color(0xFF475569),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(id = R.string.no_logs), color = Color(0xFF64748B), fontSize = 13.sp)
                    Text(stringResource(id = R.string.no_logs_sub), color = Color(0xFF475569), fontSize = 11.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                    .background(Color(0xFF030712))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(logs) { packet ->
                    LogPacketRow(packet)
                }
            }
        }
    }
}

@Composable
fun LogPacketRow(packet: SignalPacket) {
    val isSystem = packet.direction == "SYSTEM"
    val isSent = packet.direction == "SENT"
    val color = when {
        isSystem -> Color(0xFFEAB308)
        isSent -> Color(0xFF22C55E)
        else -> Color(0xFF3B82F6)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "[${packet.timestamp}]",
                    color = Color(0xFF64748B),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    color = color.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = packet.type,
                        color = color,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                text = packet.direction,
                color = when (packet.direction) {
                    "SENT" -> Color(0xFF4ADE80)
                    "RECEIVED" -> Color(0xFF60A5FA)
                    else -> Color(0xFFFBBF24)
                },
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = packet.content,
            color = if (isSystem) Color(0xFFD1D5DB) else Color(0xFF9CA3AF),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

@Composable
fun SettingsTab(engine: BluetoothCallEngine) {
    val changelogs = ChangelogRepository.records
    var isRecordsExpanded by remember { mutableStateOf(false) } // Default collapsible fold is collapsed (visibility = gone)!
    var expandedChangelogIndex by remember { mutableStateOf(0) } // Inside the accordion, index 0 default open

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App About Section with Version Info
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFF334155)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_bluetooth_anime_icon_1784451811330),
                        contentDescription = "Moe App Icon",
                        modifier = Modifier
                            .size(100.dp)
                            .clip(CircleShape)
                            .border(2.dp, Color(0xFF38BDF8), CircleShape),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(id = R.string.app_name),
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(id = R.string.version_label),
                        color = Color(0xFF38BDF8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    Text(
                        text = stringResource(id = R.string.app_subtitle),
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Language Switcher Item
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF334155)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = stringResource(id = R.string.language_label),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (LocaleHelper.getSavedLanguage(LocalContext.current) == "zh") stringResource(id = R.string.current_lang_zh) else stringResource(id = R.string.current_lang_en),
                            color = Color(0xFF38BDF8),
                            fontSize = 12.sp
                        )
                    }
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val context = LocalContext.current
                        val isZh = LocaleHelper.getSavedLanguage(context) == "zh"
                        
                        Text(
                            text = "EN",
                            color = if (!isZh) Color.White else Color(0xFF64748B),
                            fontSize = 12.sp,
                            fontWeight = if (!isZh) FontWeight.Bold else FontWeight.Normal
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Switch(
                            checked = isZh,
                            onCheckedChange = { checked ->
                                val targetLang = if (checked) "zh" else "en"
                                LocaleHelper.saveLanguage(context, targetLang)
                                LocaleHelper.setLocale(context, targetLang)
                                (context as? android.app.Activity)?.recreate()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF38BDF8),
                                checkedTrackColor = Color(0xFF0EA5E9).copy(alpha = 0.5f),
                                uncheckedThumbColor = Color(0xFF94A3B8),
                                uncheckedTrackColor = Color(0xFF334155)
                            )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "中文",
                            color = if (isZh) Color.White else Color(0xFF64748B),
                            fontSize = 12.sp,
                            fontWeight = if (isZh) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        // Simulation Mode Switcher Item
        item {
            val isSimulation = engine.simulationMode.value
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF334155)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = stringResource(id = R.string.simulation_mode_label),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(id = R.string.simulation_mode_desc),
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                    
                    Switch(
                        checked = isSimulation,
                        onCheckedChange = { checked ->
                            engine.setSimulationMode(checked)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF10B981),
                            checkedTrackColor = Color(0xFF10B981).copy(alpha = 0.4f),
                            uncheckedThumbColor = Color(0xFF94A3B8),
                            uncheckedTrackColor = Color(0xFF334155)
                        )
                    )
                }
            }
        }

        // System Overlay Permission Card for Floating Window
        item {
            val context = LocalContext.current
            var hasOverlayPermission by remember {
                mutableStateOf(
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        android.provider.Settings.canDrawOverlays(context)
                    } else {
                        true
                    }
                )
            }

            // Re-check permission whenever the lifecycle resumes (e.g. returning from settings page)
            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                        hasOverlayPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            android.provider.Settings.canDrawOverlays(context)
                        } else {
                            true
                        }
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, if (hasOverlayPermission) Color(0xFF10B981).copy(alpha = 0.5f) else Color(0xFF334155)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "后台悬浮通话窗",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (hasOverlayPermission) "悬浮窗权限已开通，后台通话将自动浮现悬浮球" else "需要系统悬浮窗权限才能在后台时自动开启小悬浮窗，点击去申请",
                            color = if (hasOverlayPermission) Color(0xFF10B981) else Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                    
                    Button(
                        onClick = {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && !hasOverlayPermission) {
                                try {
                                    val intent = android.content.Intent(
                                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                    context.startActivity(intent)
                                }
                            }
                        },
                        enabled = !hasOverlayPermission,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF38BDF8),
                            disabledContainerColor = Color(0xFF10B981)
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (hasOverlayPermission) "已开通" else "去申请",
                            color = Color.Black,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Clickable Collapsible Header
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isRecordsExpanded = !isRecordsExpanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(id = R.string.about_title),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isRecordsExpanded) "▼" else "▶",
                    color = Color(0xFF38BDF8),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Accordion content wrapped in animated visibility to achieve sliding transition!
        item {
            AnimatedVisibility(
                visible = isRecordsExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    changelogs.forEachIndexed { index, log ->
                        val isExpanded = expandedChangelogIndex == index

                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isExpanded) Color(0xFF0EA5E9) else Color(0xFF1E293B)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expandedChangelogIndex = if (isExpanded) -1 else index
                                }
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = log.version,
                                                color = if (isExpanded) Color(0xFF38BDF8) else Color.White,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 16.sp
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "(${log.date})",
                                                color = Color(0xFF64748B),
                                                fontSize = 12.sp
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = log.title,
                                            color = Color(0xFF94A3B8),
                                            fontSize = 12.sp
                                        )
                                    }
                                    Icon(
                                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = Color(0xFF64748B)
                                    )
                                }

                                if (isExpanded) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    HorizontalDivider(color = Color(0xFF1E293B))
                                    Spacer(modifier = Modifier.height(12.dp))

                                    // New Features
                                    ChangelogBulletSection(
                                        title = if (LocaleHelper.getSavedLanguage(LocalContext.current) == "zh") "新增功能 (New Features)" else "New Features",
                                        items = log.features,
                                        color = Color(0xFF10B981)
                                    )

                                    // Bug Fixes
                                    ChangelogBulletSection(
                                        title = if (LocaleHelper.getSavedLanguage(LocalContext.current) == "zh") "问题修改 (Bug Fixes)" else "Bug Fixes",
                                        items = log.bugFixes,
                                        color = Color(0xFFEF4444)
                                    )

                                    // Optimizations
                                    ChangelogBulletSection(
                                        title = if (LocaleHelper.getSavedLanguage(LocalContext.current) == "zh") "性能优化 (Optimizations)" else "Optimizations",
                                        items = log.optimizations,
                                        color = Color(0xFF3B82F6)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChangelogBulletSection(
    title: String,
    items: List<String>,
    color: Color
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(color, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                color = color,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        items.forEach { bullet ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = "• ",
                    color = Color(0xFF64748B),
                    fontSize = 12.sp
                )
                Text(
                    text = bullet,
                    color = Color(0xFFD1D5DB),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
fun CallScreenOverlay(engine: BluetoothCallEngine) {
    val callState by engine.callState
    val peer by engine.activePeer
    val duration by engine.callDuration
    val currentRoute by engine.audioRoute
    val isMicMuted by engine.isMicMuted
    val isSpeakerMuted by engine.isSpeakerMuted
    val isRecording by engine.isRecording
    val context = LocalContext.current

    val isIncoming = callState == CallState.INCOMING_RINGING
    // WeChat-style deep dark background with subtle green/black glow gradient
    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(Color(0xFF142416), Color(0xFF0E1310), Color(0xFF0A0C0B))
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
            .clickable(enabled = false) {} // Prevent click-throughs
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Top Bar Section (Minimize + Title)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        (context as? android.app.Activity)?.moveTaskToBack(true)
                        engine.addSystemLogDirect("CALL_UI", "MINIMIZE", "用户点击最小化，程序进入后台，激活悬浮通话窗")
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Minimize to Floating Window",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(28.dp)
                    )
                }

                Text(
                    text = if (isIncoming) "语音通话来电..." else "语音通话",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )

                // Spacer or dummy box to balance the row
                Box(modifier = Modifier.size(36.dp))
            }

            // 2. Peer Profile Avatar and Info Section (WeChat style big avatar)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f).padding(top = 24.dp)
            ) {
                Spacer(modifier = Modifier.height(32.dp))
                
                // Pulsating Profile Avatar Container
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(120.dp)
                ) {
                    val scale by rememberInfiniteTransition(label = "").animateFloat(
                        initialValue = 1.0f,
                        targetValue = 1.15f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1500, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = ""
                    )
                    
                    if (isIncoming || callState == CallState.OUTGOING_RINGING) {
                        // Pulsating circle glow
                        Box(
                            modifier = Modifier
                                .size(110.dp)
                                .graphicsLayer(scaleX = scale, scaleY = scale)
                                .clip(CircleShape)
                                .background(Color(0xFF07C160).copy(alpha = 0.2f))
                        )
                    }

                    // Main Avatar Circle with peer initials
                    Box(
                        modifier = Modifier
                            .size(90.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                            .border(2.dp, Color(0xFF07C160), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        val initial = (peer?.name ?: "Unknown").take(1).uppercase()
                        Text(
                            text = initial,
                            color = Color(0xFF07C160),
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = peer?.name?.ifBlank { "未知蓝牙设备" } ?: "未知蓝牙设备",
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = peer?.address ?: "00:00:00:00:00:00",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Status Indicator
                val stateText = when (callState) {
                    CallState.OUTGOING_INIT -> "正在连接呼叫系统..."
                    CallState.OUTGOING_RINGING -> "等待对方接受邀请..."
                    CallState.INCOMING_RINGING -> "邀请你进行语音通话"
                    CallState.ANSWERING -> "正在接通通话..."
                    CallState.ACTIVE -> if (isRecording) "正在录音中" else "通话连接成功"
                    CallState.RECONNECTING -> "连接中继信号弱，正在重连..."
                    CallState.DISCONNECTING -> "正在挂断..."
                    CallState.FAILED -> "连接超时，无法接通"
                    else -> ""
                }
                
                Text(
                    text = stateText,
                    color = if (callState == CallState.ACTIVE) Color(0xFF07C160) else Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center
                )

                if (callState == CallState.ACTIVE) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = formatDuration(duration),
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    val micRms by CallAudioEngine.micLevel.collectAsState()
                    RealTimeAudioWaveform(
                        rms = micRms,
                        modifier = Modifier.width(180.dp).height(48.dp),
                        color = if (isRecording) Color(0xFFEF4444) else Color(0xFF07C160)
                    )
                }
            }

            // 3. Last Recording Info Saved Card
            val rec = engine.lastRecordingInfo.value
            if (rec != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161F1A).copy(alpha = 0.95f)),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF07C160).copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Mic, contentDescription = null, tint = Color(0xFF07C160), modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "双向录音已成功写入",
                                    color = Color(0xFF07C160),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            IconButton(
                                onClick = { engine.clearLastRecording() },
                                modifier = Modifier.size(18.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(12.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "文件: " + rec.filename,
                            color = Color.White,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "路径: " + rec.filePath,
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 10.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // 4. WeChat-style Grid Call Controls Menu (Active Call only)
            if (callState == CallState.ACTIVE) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Control 1: Mic Mute
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = { engine.toggleMicMute() },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (isMicMuted) Color(0xFFEF4444) else Color(0xFF222B25)
                            ),
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(
                                imageVector = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = "Mic Mute",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "静音自己",
                            color = if (isMicMuted) Color(0xFFEF4444) else Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp
                        )
                    }

                    // Control 2: Speaker Mute
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = { engine.toggleSpeakerMute() },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (isSpeakerMuted) Color(0xFFEF4444) else Color(0xFF222B25)
                            ),
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(
                                imageVector = if (isSpeakerMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                contentDescription = "Speaker Mute",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "静音对方",
                            color = if (isSpeakerMuted) Color(0xFFEF4444) else Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp
                        )
                    }

                    // Control 3: Interactive Audio Device Routing Selector Dropdown!
                    var showRouteMenu by remember { mutableStateOf(false) }
                    val routes = engine.getAvailableAudioRoutes()
                    
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box {
                            IconButton(
                                onClick = { showRouteMenu = true },
                                colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = Color(0xFF222B25)
                                ),
                                modifier = Modifier.size(56.dp)
                            ) {
                                val routeIcon = when {
                                    currentRoute.contains("Bluetooth") -> Icons.Default.Bluetooth
                                    currentRoute.contains("Speaker") -> Icons.Default.VolumeUp
                                    else -> Icons.Default.Hearing
                                }
                                Icon(
                                    imageVector = routeIcon,
                                    contentDescription = "Toggle Audio Route",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            
                            DropdownMenu(
                                expanded = showRouteMenu,
                                onDismissRequest = { showRouteMenu = false },
                                modifier = Modifier.background(Color(0xFF1E293B))
                            ) {
                                routes.forEach { route ->
                                    DropdownMenuItem(
                                        text = { 
                                            Text(
                                                text = when(route) {
                                                    "Speaker" -> "手机扬声器"
                                                    "Earpiece" -> "手机听筒"
                                                    "Bluetooth" -> "蓝牙耳机"
                                                    else -> route
                                                },
                                                color = Color.White,
                                                fontSize = 14.sp
                                            ) 
                                        },
                                        onClick = {
                                            engine.setAudioRoute(route)
                                            showRouteMenu = false
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = when(route) {
                                                    "Speaker" -> Icons.Default.VolumeUp
                                                    "Earpiece" -> Icons.Default.Hearing
                                                    "Bluetooth" -> Icons.Default.Bluetooth
                                                    else -> Icons.Default.Phone
                                                },
                                                contentDescription = null,
                                                tint = Color(0xFF07C160)
                                            )
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = when {
                                currentRoute.contains("Bluetooth") -> "蓝牙耳机"
                                currentRoute.contains("Speaker") -> "手机扬声器"
                                else -> "手机听筒"
                            },
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp
                        )
                    }

                    // Control 4: Multi-direction Call Recording
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = {
                                engine.toggleRecording()
                                val toastMsg = if (engine.isRecording.value) "通话录音已开始" else "录音结束并已保存"
                                android.widget.Toast.makeText(context, toastMsg, android.widget.Toast.LENGTH_SHORT).show()
                            },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (isRecording) Color(0xFFEF4444) else Color(0xFF222B25)
                            ),
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FiberManualRecord,
                                contentDescription = "Record Call",
                                tint = if (isRecording) Color.White else Color(0xFFEF4444),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (isRecording) "停止录制" else "开始双录",
                            color = if (isRecording) Color(0xFFEF4444) else Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // 5. Simulate Peer Answer Button during Outgoing Ringing/Init
            if (callState == CallState.OUTGOING_RINGING || callState == CallState.OUTGOING_INIT) {
                Button(
                    onClick = { engine.simulateHeadsetButtonPress("SIMULATE_ANSWER") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF07C160)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .padding(bottom = 16.dp)
                        .testTag("simulate_peer_answer_button")
                ) {
                    Icon(Icons.Default.PhoneCallback, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("模拟对端接听", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            // 6. Bottom Accept / Decline Actions Bar (WeChat signature layout)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 48.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (callState == CallState.FAILED) {
                    // Failed Retry Option Buttons
                    IconButton(
                        onClick = { engine.reject() },
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF334155)),
                        modifier = Modifier.size(68.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                    Spacer(modifier = Modifier.width(64.dp))
                    IconButton(
                        onClick = { peer?.let { engine.startOutgoingCall(it) } },
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF07C160)),
                        modifier = Modifier.size(68.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retry", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                } else if (callState == CallState.INCOMING_RINGING) {
                    // Incoming Invitation: WeChat Left Red Decline, Right Green Answer
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(
                            onClick = { engine.reject() },
                            colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFFFA5151)), // WeChat red decline
                            modifier = Modifier
                                .size(72.dp)
                                .testTag("decline_call_button")
                        ) {
                            Icon(Icons.Default.CallEnd, contentDescription = "Decline Call", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("拒绝", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(
                            onClick = { engine.answer() },
                            colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF07C160)), // WeChat green accept
                            modifier = Modifier
                                .size(72.dp)
                                .testTag("accept_call_button")
                        ) {
                            Icon(Icons.Default.Phone, contentDescription = "Accept Call", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("接听", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                } else {
                    // Single Hanging Key for Outgoing, Connecting, or Active Talk
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = { engine.hangup() },
                            colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFFFA5151)), // WeChat signature red hangup
                            modifier = Modifier
                                .size(72.dp)
                                .testTag("hangup_call_button")
                        ) {
                            Icon(Icons.Default.CallEnd, contentDescription = "Hangup Call", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("挂断", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun PulsatingRingingAvatar() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        // Pulsing circles
        Box(
            modifier = Modifier
                .size(160.dp * scale)
                .background(Color(0xFF38BDF8).copy(alpha = 0.15f), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(120.dp * scale)
                .background(Color(0xFF0EA5E9).copy(alpha = 0.25f), CircleShape)
        )
        // Core Mascot Image
        Image(
            painter = painterResource(id = R.drawable.img_bluetooth_anime_icon_1784451811330),
            contentDescription = "Ringing avatar",
            modifier = Modifier
                .size(90.dp)
                .clip(CircleShape)
                .border(3.dp, Color(0xFF38BDF8), CircleShape),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
fun WaveformVisualizer(isRecording: Boolean = false) {
    val transition = rememberInfiniteTransition(label = "waves")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * java.lang.Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val midY = height / 2f
        val points = 100

        // Draw multiple overlapping sine waves to create high tech look
        for (waveIndex in 0..2) {
            val path = androidx.compose.ui.graphics.Path()
            val ampMultiplier = if (isRecording) 1.5f else 1.0f
            val amplitude = (25.dp.toPx() - (waveIndex * 5.dp.toPx())) * ampMultiplier
            val frequency = (2f + (waveIndex * 0.5f)) * (if (isRecording) 1.3f else 1.0f)
            
            val strokeColor = if (isRecording) {
                when (waveIndex) {
                    0 -> Color(0xFFEF4444)
                    1 -> Color(0xFFF87171).copy(alpha = 0.6f)
                    else -> Color(0xFFFCA5A5).copy(alpha = 0.3f)
                }
            } else {
                when (waveIndex) {
                    0 -> Color(0xFF38BDF8)
                    1 -> Color(0xFF0EA5E9).copy(alpha = 0.6f)
                    else -> Color(0xFF22D3EE).copy(alpha = 0.3f)
                }
            }

            for (i in 0..points) {
                val x = (i / points.toFloat()) * width
                val angle = (i / points.toFloat()) * frequency * Math.PI.toFloat() + phase + waveIndex.toFloat()
                val y = midY + amplitude * sin(angle)
                if (i == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }

            drawPath(
                path = path,
                color = strokeColor,
                style = Stroke(width = 3.dp.toPx())
            )
        }
    }
}

@Composable
fun SoundWaveform(
    modifier: Modifier = Modifier,
    barCount: Int = 15,
    color: Color = Color(0xFF38BDF8),
    isAnimating: Boolean = true
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    
    // Create an animatable height factor for each bar
    val heights = (0 until barCount).map { index ->
        val duration = remember(index) { (300..900).random() }
        infiniteTransition.animateFloat(
            initialValue = 0.15f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(duration, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar_$index"
        )
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        heights.forEach { heightFactor ->
            val barHeight = if (isAnimating) {
                // Read measured height or default to standard size
                32.dp * heightFactor.value
            } else {
                4.dp
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(barHeight)
                    .background(color, RoundedCornerShape(2.dp))
            )
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val m = (seconds / 60).toString().padStart(2, '0')
    val s = (seconds % 60).toString().padStart(2, '0')
    return "$m:$s"
}

private fun formatDurationMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
}

@Composable
fun RealTimeAudioWaveform(
    rms: Float,
    modifier: Modifier = Modifier,
    barCount: Int = 20,
    color: Color = Color(0xFF38BDF8)
) {
    val levels = remember { mutableStateListOf<Float>().apply { addAll(List(barCount) { 0.1f }) } }
    val animatedRms by animateFloatAsState(targetValue = rms.coerceIn(0.08f, 1.0f), label = "rms")

    LaunchedEffect(animatedRms) {
        if (levels.size >= barCount) {
            levels.removeAt(0)
        }
        levels.add(animatedRms)
    }

    Row(
        modifier = modifier.height(48.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        levels.forEach { level ->
            val h = 48.dp * level
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(h)
                    .background(color, RoundedCornerShape(3.dp))
            )
        }
    }
}

@Composable
fun StaticRecordingWaveform(
    peaks: List<Float>,
    progress: Float,
    onProgressChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = Color(0xFF38BDF8),
    inactiveColor: Color = Color(0xFF475569)
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(peaks) {
                detectTapGestures { offset ->
                    val ratio = (offset.x / size.width).coerceIn(0f, 1f)
                    onProgressChanged(ratio)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val barCount = peaks.size
            if (barCount > 0) {
                val gap = 4f
                val totalGapWidth = gap * (barCount - 1)
                val barWidth = (size.width - totalGapWidth) / barCount
                
                for (i in 0 until barCount) {
                    val peakValue = peaks[i]
                    val barHeight = size.height * peakValue.coerceIn(0.08f, 1.0f)
                    val x = i * (barWidth + gap)
                    val y = (size.height - barHeight) / 2
                    
                    val isPast = (i.toFloat() / barCount) <= progress
                    val drawColor = if (isPast) activeColor else inactiveColor
                    
                    drawRoundRect(
                        color = drawColor,
                        topLeft = androidx.compose.ui.geometry.Offset(x, y),
                        size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                    )
                }
            }
        }
    }
}

@Composable
fun RecordingsTab(engine: BluetoothCallEngine) {
    val recordings = engine.recordingsList
    val playingId by engine.playingRecordingId
    val context = LocalContext.current
    
    if (recordings.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.LibraryMusic,
                contentDescription = null,
                tint = Color(0xFF475569),
                modifier = Modifier.size(80.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(id = R.string.no_recordings),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(id = R.string.no_recordings_sub),
                color = Color(0xFF94A3B8),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            Text(
                text = stringResource(id = R.string.tab_recordings) + " (${recordings.size})",
                color = Color(0xFF94A3B8),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(recordings, key = { it.id }) { rec ->
                    val isPlaying = playingId == rec.id
                    
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, if (isPlaying) Color(0xFF38BDF8) else Color(0xFF334155)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("recording_item_${rec.id}")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = rec.peerName,
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = rec.peerAddress,
                                        color = Color(0xFF64748B),
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                
                                val formattedTime = remember(rec.callTime) {
                                    val sdf = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault())
                                    sdf.format(java.util.Date(rec.callTime))
                                }
                                Text(
                                    text = formattedTime,
                                    color = Color(0xFF94A3B8),
                                    fontSize = 12.sp
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = Color(0xFF334155))
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Text(
                                text = stringResource(id = R.string.rec_file_prefix) + rec.filename,
                                color = Color(0xFFCBD5E1),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(id = R.string.rec_path_prefix) + rec.filePath,
                                color = Color(0xFF64748B),
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    val clip = android.content.ClipData.newPlainText("recording_path", rec.filePath)
                                    clipboard.setPrimaryClip(clip)
                                    android.widget.Toast.makeText(context, context.getString(R.string.path_copied), android.widget.Toast.LENGTH_SHORT).show()
                                }
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(id = R.string.duration_prefix) + "${rec.durationSec}s",
                                color = Color(0xFF38BDF8),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            
                            val peaks = remember(rec.filePath) {
                                RecordingWaveformAnalyzer.analyzeWaveform(rec.filePath, pointsCount = 36)
                            }
                            
                            if (isPlaying) {
                                Spacer(modifier = Modifier.height(12.dp))
                                val playbackProgress by engine.playbackProgressFlow
                                val playbackPos by engine.playbackPositionMs
                                val playbackDur by engine.playbackDurationMs
                                
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    StaticRecordingWaveform(
                                        peaks = peaks,
                                        progress = playbackProgress,
                                        onProgressChanged = { ratio ->
                                            engine.seekRecordingTo(ratio)
                                        },
                                        modifier = Modifier.fillMaxWidth().height(48.dp)
                                    )
                                    
                                    Spacer(modifier = Modifier.height(6.dp))
                                    
                                    Slider(
                                        value = playbackProgress,
                                        onValueChange = { ratio ->
                                            engine.seekRecordingTo(ratio)
                                        },
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color(0xFF38BDF8),
                                            activeTrackColor = Color(0xFF0EA5E9),
                                            inactiveTrackColor = Color(0xFF334155)
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = formatDurationMs(playbackPos),
                                            color = Color(0xFF38BDF8),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = formatDurationMs(playbackDur),
                                            color = Color(0xFF94A3B8),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF0EA5E9).copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                        .border(BorderStroke(1.dp, Color(0xFF0EA5E9).copy(alpha = 0.2f)), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VolumeUp,
                                        contentDescription = "Playing",
                                        tint = Color(0xFF38BDF8),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "PLAYING AUDIO",
                                        color = Color(0xFF38BDF8),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    SoundWaveform(
                                        modifier = Modifier.width(64.dp).height(20.dp),
                                        barCount = 8,
                                        color = Color(0xFF38BDF8),
                                        isAnimating = true
                                    )
                                }
                            } else {
                                Spacer(modifier = Modifier.height(12.dp))
                                StaticRecordingWaveform(
                                    peaks = peaks,
                                    progress = 0f,
                                    onProgressChanged = { ratio ->
                                        engine.playRecording(rec)
                                        engine.seekRecordingTo(ratio)
                                    },
                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = { engine.playRecording(rec) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isPlaying) Color(0xFFEF4444) else Color(0xFF0EA5E9)
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                        contentDescription = if (isPlaying) "Stop" else "Play",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isPlaying) "停止" else "播放",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    IconButton(
                                        onClick = { engine.shareRecording(rec) },
                                        modifier = Modifier
                                            .size(32.dp)
                                            .background(Color(0xFF334155), RoundedCornerShape(8.dp))
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Share,
                                            contentDescription = "Share",
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    
                                    var showDeleteConfirm by remember { mutableStateOf(false) }
                                    
                                    IconButton(
                                        onClick = { showDeleteConfirm = true },
                                        modifier = Modifier
                                            .size(32.dp)
                                            .background(Color(0xFFEF4444).copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            tint = Color(0xFFF87171),
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    
                                    if (showDeleteConfirm) {
                                        AlertDialog(
                                            onDismissRequest = { showDeleteConfirm = false },
                                            containerColor = Color(0xFF1E293B),
                                            title = { Text(stringResource(id = R.string.delete_confirm), color = Color.White) },
                                            text = { Text(stringResource(id = R.string.delete_confirm_desc), color = Color(0xFF94A3B8)) },
                                            confirmButton = {
                                                TextButton(
                                                    onClick = {
                                                        engine.deleteRecording(rec)
                                                        showDeleteConfirm = false
                                                    }
                                                ) {
                                                    Text("确认删除", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                                                }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = { showDeleteConfirm = false }) {
                                                    Text("取消", color = Color(0xFF38BDF8))
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

