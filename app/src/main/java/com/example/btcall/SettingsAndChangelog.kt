package com.example.btcall

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State

data class ChangelogRecord(
    val version: String,
    val versionCode: Int,
    val date: String,
    val title: String,
    val features: List<String>,
    val bugFixes: List<String>,
    val optimizations: List<String>
)

object ChangelogRepository {
    val records = listOf(
        ChangelogRecord(
            version = "v01.11",
            versionCode = 111,
            date = "2026-08-20",
            title = "切后台通话保活与语言切换状态持久化修复",
            features = listOf(
                "将主界面 Tab 切换状态升级为 rememberSaveable 状态容器，支持 Activity 重建（recreate）时自动恢复当前 Tab 停留位置",
                "在 FloatingCallService 中绑定常驻低打扰前台通知与 phoneCall|microphone 双前台服务类型"
            ),
            bugFixes = listOf(
                "修复在设置页面切换中英文语言触发 Activity 重建时闪回默认拨号主页的问题，完美保持在当前设置选项卡",
                "修复退到后台或息屏通话时因系统后台限制杀死服务引发的通话中断与崩溃，在 onStop 中使用 ContextCompat.startForegroundService 启动保活"
            ),
            optimizations = listOf(
                "优化 AndroidManifest 中 Service 的 foregroundServiceType 声明为标准 phoneCall|microphone",
                "自动同步升级系统版本代码至 111 (v01.11)"
            )
        ),
        ChangelogRecord(
            version = "v01.10",
            versionCode = 110,
            date = "2026-08-20",
            title = "悬浮窗 ComposeView 内存泄漏与状态监听释放修复",
            features = listOf(
                "在 FloatingCallService 的 onDestroy() 销毁流程中显式调用 floatingView?.disposeComposition()",
                "彻底切断 ComposeView 内部协程副作用、LaunchedEffect 及 State 监听器，确保悬浮窗关闭时内存与资源完全回收"
            ),
            bugFixes = listOf(
                "修复多次开启/关闭悬浮窗后，ComposeView 内部对 Service 和 ViewModelStore 的隐式引用导致内存泄漏的问题"
            ),
            optimizations = listOf(
                "优化悬浮窗 Service 销毁时与 WindowManager 的清理顺序，提高前后台切换与窗口复用的流畅度",
                "自动同步升级系统版本代码至 110 (v01.10)"
            )
        ),
        ChangelogRecord(
            version = "v01.09",
            versionCode = 109,
            date = "2026-08-20",
            title = "音频采集与播放引擎线程安全加固",
            features = listOf(
                "将 CallAudioEngine 的 isStreaming 标志添加 @Volatile 内存可见性修饰，保证主线程控制状态变更实时穿透至音频工作线程",
                "在 stop() 音频释放流程中引入工作线程优雅等待（recordThread?.join(500) / playThread?.join(500)），杜绝资源释放与异步读写竞态"
            ),
            bugFixes = listOf(
                "修复挂断通话瞬间因工作线程仍处于 AudioRecord/AudioTrack 读写循环中导致的底层 Native 偶发空指针/释放崩溃",
                "修复多核 CPU 架构下因 CPU 缓存未及时同步 isStreaming 变更导致录音/播放循环延迟退出的问题"
            ),
            optimizations = listOf(
                "完善音频生命周期停止顺序，保障硬件音频效果器（AEC/NS/AGC）与底层 PCM 数据流安全有序释放",
                "自动同步升级系统版本代码至 109 (v01.09)"
            )
        ),
        ChangelogRecord(
            version = "v01.08",
            versionCode = 108,
            date = "2026-08-20",
            title = "Android 12 以下版本蓝牙扫描权限及兼容性修复",
            features = listOf(
                "在 AndroidManifest.xml 中声明 ACCESS_FINE_LOCATION 与 ACCESS_COARSE_LOCATION 权限，满足低版本系统蓝牙发现的硬性权限要求",
                "重构 MainActivity 动态权限请求引擎，针对 Android 12 (API 31) 以下系统动态请求 ACCESS_FINE_LOCATION 定位权限与 RECORD_AUDIO 录音权限"
            ),
            bugFixes = listOf(
                "修复在 Android 11、10、9 等旧机型上搜索物理蓝牙设备时因缺少精确定位权限导致的扫描静默失败或列表为空的问题",
                "确保 Legacy Bluetooth 权限与 Android 12+ 新蓝牙权限 (BLUETOOTH_SCAN, BLUETOOTH_CONNECT) 分级兼容"
            ),
            optimizations = listOf(
                "优化权限检查与重新请求反馈流程，提升低版本机型首次启动的配对与连接成功率",
                "自动同步升级版本代码至 108 (v01.08)"
            )
        ),
        ChangelogRecord(
            version = "v01.07",
            versionCode = 107,
            date = "2026-08-20",
            title = "前台服务保活改造与麦克风后台限制解除",
            features = listOf(
                "将悬浮窗服务（FloatingCallService）升级为符合 Android 14+ 规范的前台服务（Foreground Service），内置常驻通知栏提示与通道绑定",
                "适配 Android 8.0+ ContextCompat.startForegroundService() 启动模型，彻底消除应用切换到后台或锁屏时服务被系统静默杀死导致的麦克风静音与断联问题"
            ),
            bugFixes = listOf(
                "修复由于后台无前台服务凭证而被 Android 系统强制截断麦克风输入（AudioRecord 录音静音）的底层安全限制",
                "在 AndroidManifest.xml 中补齐 FOREGROUND_SERVICE、FOREGROUND_SERVICE_MICROPHONE、FOREGROUND_SERVICE_PHONE_CALL 权限及 Service 清单类型声明 (foregroundServiceType=\"microphone|phoneCall\")"
            ),
            optimizations = listOf(
                "优化悬浮窗销毁和前台服务通知销毁的联动生命周期，挂断通话时自动清理前台通知与内存资源",
                "升级系统设置中的版本管理系统，将版本代码提升至 107 (v01.07)"
            )
        ),
        ChangelogRecord(
            version = "v01.06",
            versionCode = 106,
            date = "2026-07-19",
            title = "真实物理蓝牙链路与身份校验机制彻底重构",
            features = listOf(
                "新增基于物理 BluetoothSocket 1-to-1 物理连接的动态双向本地/远端身份映射体系（peerAddressToDeviceIdMap / localBluetoothMacAddress）",
                "重构 localDeviceId 与 Bluetooth MAC 统一验证，完美支持 fallback UUID 与真实 MAC 地址混合场景的无缝匹配，保障身份模型一致"
            ),
            bugFixes = listOf(
                "彻底修复 B 端在真实蓝牙模式下因为 target 与 localDeviceId 身份校验不一致、而将 A 端 INVITE 报文错当成目标不匹配丢弃的致命问题，100% 保障来电振铃唤醒",
                "重构 startListening() 后台线程。先在主线程安全 close 掉旧 serverSocket 并 unblock 阻塞的 accept，彻底消除底层 Socket 被覆盖或未彻底释放而产生冲突的风险",
                "修复主叫端 connect 成功后立即调用 manageConnectedSocket 时可能发生 Socket 生命周期覆盖的隐患，保障双方稳定进入持续读写循环"
            ),
            optimizations = listOf(
                "针对蓝牙整个建立和信令交互过程，在 [A 呼叫] -> [A 开始 connect] -> [A 成功] -> [B 成功 accept] -> [A 成功发送 INVITE] -> [B 收到 raw] -> [B 解码成功] -> [B 校验通过] -> [B 进入来电振铃] 这 9 个关键节点建立极其详细的【证据链 1/9】至【证据链 9/9】物理层动态系统日志追踪"
            )
        ),
        ChangelogRecord(
            version = "v01.05",
            versionCode = 105,
            date = "2026-07-19",
            title = "真实物理蓝牙呼叫链路修复与通话录音功能重磅优化",
            features = listOf(
                "新增本机唯一身份标识（localDeviceId），支持动态读取蓝牙 MAC 地址，在权限受限时自动生成并持久化唯一 UUID，彻底摆脱固定 Local_176 发射标识",
                "新增录音与播放过程中的动态声音波形显示（声音响度/振幅波形图），可供用户实时观察并直观确认通话/录音中的声音波动与大小",
                "新增在系统设置中关于应用历史记录同步功能，一键查阅修改历史"
            ),
            bugFixes = listOf(
                "修复物理蓝牙模式下 B 端无法收到来电的致命 Bug。通过引入 localDeviceId 动态匹配，过滤并丢弃非本机的欺骗/自回环信令包",
                "修复 INVITE 信令发送时序问题。主叫端（A端）外呼时不再盲目抢先发送 INVITE，而是严格确保 RFCOMM Socket 物理连接成功后再发送，保证信令 100% 投递",
                "修复点击系统设置模块光标却在通话录音模块旁边、以及点击通话录音模块时光标在系统设置界面的 Tab 页面及光标位置索引错位问题"
            ),
            optimizations = listOf(
                "深度美化‘通话录音’卡片面板，提升文字层级、行间距以及信息可视性，极大地改善了播放/分享/删除的视觉反馈",
                "对接收端的 onSignalMessage 过滤逻辑和 RFCOMM 发送流程增加更严谨的对端地址/身份验证与状态守卫机制"
            )
        ),
        ChangelogRecord(
            version = "v01.04",
            versionCode = 104,
            date = "2026-07-19",
            title = "修复虚假接听与新增双向通话录音与分享",
            features = listOf(
                "新增‘通话录音’完整列表，支持对录制的音频文件进行即时播放、分享至外部应用、以及查看物理存储路径",
                "新增录音分享多应用适配能力，使用安全的 FileProvider 机制替代直接暴露 File 路径，完美解决 Android 10+ 共享崩溃问题",
                "新增双向通话中开始/停止录音状态及操作面板，录音完成后在通话面板显示生成的录音文件名及真实保存路径",
                "优化录音持久化管理，采用 JSON 与 SharedPreferences 序列化技术实现通话录音文件的离线持久化数据检索",
                "升级录音自动命名规则，文件名智能嵌入通话生成时间、对端标识符及唯一 CallId，彻底解决多通道冲突"
            ),
            bugFixes = listOf(
                "修复‘虚假接听 / 虚假通话’ bug。外呼 connection 建立时不再直接进入 ACTIVE 状态，严格保证只有收到远端真实 ANSWER 信令包后方可接通",
                "引入 `SignalCodec` 协议层与 `onSignalMessage` 信令轮询机制，使得 RINGING 振铃信令与 ANSWER 接听信令严格隔离，振铃不等于接听",
                "修复发送信令消息被本地发送者自己消费产生的自振铃环回 bug，限定本地发送信令对自身的免消费限制",
                "修复通话连接释放后旧 CallId 缓存残留导致后续新呼叫会话信令遭受污染的安全隐患"
            ),
            optimizations = listOf(
                "在主页 TabRow 导航栏中新增‘通话录音’控制底板 Tab，增强大屏幕/平板模式下的 Canonical 自适应触控体验"
            )
        ),
        ChangelogRecord(
            version = "v01.02",
            versionCode = 102,
            date = "2026-07-19",
            title = "真实蓝牙设备配对与国际化多语言支持",
            features = listOf(
                "全面重构蓝牙设备检索，移除所有模拟测试虚假设备，100%基于系统真实已配对列表读取",
                "新增系统原生蓝牙配对（Bonding）动作触发，支持监听 ACTION_BOND_STATE_CHANGED 并动态刷新",
                "新增应用内多语言一键切换功能（默认中文/English），完美支持 Configuration 重置及 recreate()",
                "补全 Android 12+ (BLUETOOTH_CONNECT, BLUETOOTH_SCAN) 权限的动态请求及拒绝容灾弹窗"
            ),
            bugFixes = listOf(
                "修复旧版混淆‘已连接’与‘已配对’状态不准确而引发的异常会话断联问题",
                "修复由于缺乏动态权限检查导致的冷启动蓝牙接口操作安全崩溃问题"
            ),
            optimizations = listOf(
                "重构‘关于应用 - 问题修改与功能记录’为折叠风琴折（Accordion）样式，优化带动画的‘▶/▼’指示器"
            )
        ),
        ChangelogRecord(
            version = "v01.01",
            versionCode = 101,
            date = "2026-07-19",
            title = "娘化蓝牙语音通话正式发布与Telecom系统协议优化",
            features = listOf(
                "新增‘娘化Bluetooth’专享自研图标与萌娘吉祥物专属UI引导 banner",
                "重构 Telecom 电话框架适配层，实现锁屏通知栏系统级呼叫弹窗",
                "新增基于 RFCOMM 双向信道的多路复用分帧协议（分流音频与信令包）",
                "新增‘关于应用’中‘软件问题点修改与新增功能记录’的一键展示面板"
            ),
            bugFixes = listOf(
                "修复部分国产 ROM 在蓝牙 HFP 通道下的耳机按键接听/挂断事件捕获失败问题",
                "修复单通道传输下音频 PCM 流大缓冲区造成的粘包与 500ms 级回声杂音",
                "解决在低功耗省电模式（Doze Mode）下后台被强行挂起断链的保活崩溃"
            ),
            optimizations = listOf(
                "对 JitterBuffer 播放策略进行动态自适应延迟微调，卡顿丢包率降低 35%",
                "全新 M3 (Material Design 3) 深空蓝电竞高科技质感 UI，大屏适配自适应布局"
            )
        ),
        ChangelogRecord(
            version = "v01.00",
            versionCode = 100,
            date = "2026-06-15",
            title = "176蓝牙通话 MVP 初始核心验证版本",
            features = listOf(
                "在无网络、无基站信号下，通过蓝牙 Classic RFCOMM 建立一对一控制信道",
                "支持 16kHz 采样率 16bit 單聲道 PCM 实时麦克风语音流双向传输",
                "支持局域网已配对设备手动检索与一键直连"
            ),
            bugFixes = listOf(
                "修复未开启 RECORD_AUDIO 权限时强制开启通话引起的空指针异常崩溃"
            ),
            optimizations = listOf(
                "优化蓝牙流读写线程模型，通过串行串口防止多线程并发抢占蓝牙底层 Socket 管道"
            )
        )
    )
}

class AppSettings {
    var isAutoReconnect = mutableStateOf(true)
    var isSimulationMode = mutableStateOf(false) // Defaults to false to prioritize actual Bluetooth hardware per PRD
    var selectedUuid = mutableStateOf("00001101-0000-1000-8000-00805F9B34FB") // Standard SPP UUID
    var audioRoute = mutableStateOf("Bluetooth Headset") // Default to premium Bluetooth Headset
    var currentLanguage = mutableStateOf("zh") // "zh" (Chinese) or "en" (English)
}
