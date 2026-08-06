package com.clydeenke.mediapill.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import com.clydeenke.mediapill.config.Config

/**
 * Stage 2 控制器：药丸注入 + 显隐逻辑 + 智能位置 + 状态监听。
 *
 * 新增功能：
 * - 监听状态栏展开/收起（QS/Shade）
 * - 控制中心打开时隐藏
 * - 解锁时渐变消失
 * - 用户可调位置、宽度、透明度
 */
class PillOverlayController(private val context: Context) {

    companion object {
        private const val TAG = "MediaPill"
        // 位置百分比基准（从底部往上，数值越大位置越高）
        // 百分比越大 = 离底部越远 = y越负 = 在屏幕上越靠上（位置越高）
        // 不充电要低（小白条上方）→ 用较小百分比 → y较不负 → 位置低
        // 充电时要高（往上移）→ 用较大百分比 → y更负 → 位置高
        // 整体往下移：增大百分比（离底部更远 = y更不负 = 位置更低）
        private const val POSITION_NORMAL = 90      // 不充电：90%（小白条上方，更低位置）
        private const val POSITION_CHARGING = 95    // 充电时：95%（往上移到现在不充电的位置）
    }

    private var rootView: ViewGroup? = null
    private var pillView: MediaPillView? = null
    private var injected = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // 显隐状态
    private var isOnKeyguard = false
    private var isDozing = false
    private var hasMedia = false
    private var isShadeExpanded = false      // 状态栏展开
    private var isQsExpanded = false         // QS 展开
    private var isControlCenterOpen = false  // 控制中心打开
    private var isBouncerShowing = false     // 密码输入界面显示
    private var isLauncherVisible = false    // 桌面是否可见

    // 显示模式配置
    private var showOnKeyguard = true        // 在锁屏显示
    private var showOnLauncher = false       // 在桌面显示（默认关闭）

    // 位置状态
    private var isCharging = false
    private var navBarHeight = 0
    private var screenHeight = 0
    private var screenWidth = 0

    // 用户可调参数（从配置读取）
    private var userYOffsetDp = 0      // 用户 Y 偏移（dp，可正可负）
    private var pillMaxWidthDp = 280   // 药丸最大宽度（dp）
    private var pillMinWidthDp = 180   // 药丸最小宽度（dp）

    // MediaDataManager
    private var mediaDataManager: Any? = null
    private var mediaDataListener: Any? = null

    // MediaData 反射字段
    private var mediaDataClass: Class<*>? = null
    private var fldSong: java.lang.reflect.Field? = null
    private var fldArtist: java.lang.reflect.Field? = null
    private var fldArtwork: java.lang.reflect.Field? = null
    private var fldIsPlaying: java.lang.reflect.Field? = null
    private var fldPackageName: java.lang.reflect.Field? = null

    private var currentMediaKey: String? = null
    private var currentPackageName: String? = null
    private var progressRunnable: Runnable? = null

    fun init(classLoader: ClassLoader) {
        ModuleResources.init(context)
        HookPrefReader.init(context)

        // 缓存反射字段
        try {
            mediaDataClass = Class.forName(
                "com.android.systemui.media.controls.shared.model.MediaData",
                false, classLoader
            )
            fldSong = mediaDataClass!!.getDeclaredField("song").apply { isAccessible = true }
            fldArtist = mediaDataClass!!.getDeclaredField("artist").apply { isAccessible = true }
            fldArtwork = mediaDataClass!!.getDeclaredField("artwork").apply { isAccessible = true }
            fldIsPlaying = mediaDataClass!!.getDeclaredField("isPlaying").apply { isAccessible = true }
            fldPackageName = mediaDataClass!!.getDeclaredField("packageName").apply { isAccessible = true }
            Log.i(TAG, "MediaData reflection cached ✓")
        } catch (e: Exception) {
            Log.e(TAG, "MediaData reflection failed", e)
        }

        // 屏幕尺寸
        val dm = context.resources.displayMetrics
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels

        // 读取用户配置
        refreshUserConfig()
    }

    /**
     * 刷新用户配置。
     */
    private fun refreshUserConfig() {
        userYOffsetDp = HookPrefReader.pillYOffsetDp
        pillMaxWidthDp = HookPrefReader.pillMaxWidthDp.coerceIn(200, 360)
        pillMinWidthDp = HookPrefReader.pillMinWidthDp.coerceIn(150, 250)
    }

    fun inject(rootView: ViewGroup) {
        if (injected) return
        this.rootView = rootView
        Log.i(TAG, "Injecting into ${rootView.javaClass.simpleName} (${screenWidth}x${screenHeight})")

        pillView = MediaPillView(context).apply {
            setWidthLimits(pillMinWidthDp, pillMaxWidthDp)
            onPlayPauseToggle = { togglePlayPause() }
            onPreviousClicked = { skipToPrevious() }
            onNextClicked = { skipToNext() }
            onArtworkClick = { openMediaApp() }
        }

        // 布局参数：居中底部，自适应宽度
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,  // 宽度自适应
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = dp(16)  // 距离底部基础边距
        }

        rootView.addView(pillView, lp)
        injected = true

        // 等待视图附加到窗口后应用系统模糊
        pillView?.post {
            pillView?.applySystemBlur()
        }

        // 初始位置
        updatePillPosition(animate = false)

        // WindowInsets 监听
        pillView?.setOnApplyWindowInsetsListener { _, insets ->
            navBarHeight = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            updatePillPosition(animate = true)
            insets
        }

        // 充电监听
        registerBatteryReceiver()

        // 定期刷新配置（用户可能在设置 App 中调整）
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                refreshUserConfig()
                pillView?.setWidthLimits(pillMinWidthDp, pillMaxWidthDp)
                mainHandler.postDelayed(this, 5000)
            }
        }, 5000)

        Log.i(TAG, "MediaPillView injected ✓")
    }

    // ═══════════════════════════════════════════════════════
    //  位置计算（核心）
    // ═══════════════════════════════════════════════════════

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private fun updatePillPosition(animate: Boolean) {
        Log.d(TAG, "updatePillPosition called, pillView=${pillView != null}, isCharging=$isCharging")
        val view = pillView ?: run {
            Log.w(TAG, "updatePillPosition: pillView is null")
            return
        }

        // 重新获取屏幕尺寸（可能旋转）
        val dm = context.resources.displayMetrics
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels

        // 基础位置百分比
        // 不充电 → 75%（位置更低，小白条上方）
        // 充电 → 82%（位置更高，往上移避开充电信息）
        // 直接交换：充电时用 NORMAL(82)，不充电时用 CHARGING(88)
        val basePercent = if (isCharging) POSITION_NORMAL else POSITION_CHARGING

        // 计算 Y 偏移（从底部往上）
        val safeBottom = navBarHeight + dp(16)
        val targetBottomY = screenHeight * (100 - basePercent) / 100

        // 用户偏移（先重置为0测试）
        val userOffsetPx = 0 // dp(userYOffsetDp)
        val yOffset = (targetBottomY - safeBottom + userOffsetPx).coerceAtLeast(dp(40))

        val targetY = -yOffset.toFloat()

        Log.d(TAG, "Position: charging=$isCharging screen=${screenWidth}x${screenHeight} base=$basePercent% y=$targetY")

        view.setPositionTranslationY(targetY, animate)
    }

    private fun registerBatteryReceiver() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        try {
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                    val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
                    val wasCharging = isCharging
                    isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                 status == BatteryManager.BATTERY_STATUS_FULL
                    Log.d(TAG, "Battery broadcast: status=$status plugged=$plugged isCharging=$isCharging")
                    if (wasCharging != isCharging) {
                        Log.i(TAG, "Charging changed: $wasCharging -> $isCharging")
                        updatePillPosition(animate = true)
                    }
                }
            }, filter)
            Log.i(TAG, "Battery receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register battery receiver", e)
        }

        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryIntent?.let {
            val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val plugged = it.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == BatteryManager.BATTERY_STATUS_FULL
            Log.i(TAG, "Initial battery state: status=$status plugged=$plugged isCharging=$isCharging")
        }
    }

    // ═══════════════════════════════════════════════════════
    //  状态监听（状态栏、QS、控制中心）
    // ═══════════════════════════════════════════════════════

    /**
     * 状态栏展开状态变化。
     */
    fun onShadeExpandedChanged(expanded: Boolean) {
        if (isShadeExpanded == expanded) return
        isShadeExpanded = expanded
        Log.d(TAG, "Shade expanded: $expanded")
        updateVisibility()
    }

    /**
     * QS 展开状态变化。
     */
    fun onQsExpandedChanged(expanded: Boolean) {
        if (isQsExpanded == expanded) return
        isQsExpanded = expanded
        Log.d(TAG, "QS expanded: $expanded")
        updateVisibility()
    }

    /**
     * 控制中心打开状态（某些 ROM 有独立控制中心）。
     */
    fun onControlCenterChanged(open: Boolean) {
        if (isControlCenterOpen == open) return
        isControlCenterOpen = open
        Log.d(TAG, "Control center: $open")
        updateVisibility()
    }

    /**
     * Bouncer（密码输入界面）显示状态。
     */
    fun onBouncerStateChanged(showing: Boolean) {
        if (isBouncerShowing == showing) return
        isBouncerShowing = showing
        Log.d(TAG, "Bouncer: $showing")
        updateVisibility()
    }

    // ═══════════════════════════════════════════════════════
    //  显隐控制
    // ═══════════════════════════════════════════════════════

    fun onKeyguardStateChanged(onKeyguard: Boolean) {
        if (isOnKeyguard == onKeyguard) return
        isOnKeyguard = onKeyguard
        Log.i(TAG, "Keyguard: $onKeyguard")

        if (!onKeyguard) {
            // 解锁：渐变消失
            pillView?.hidePill()
        } else {
            updateVisibility()
        }
    }

    fun onDozeStateChanged(dozing: Boolean) {
        if (isDozing == dozing) return
        isDozing = dozing
        Log.i(TAG, "Doze: $dozing")
        updateVisibility()
    }

    /**
     * 开关屏幕动画。
     */
    fun onScreenStateChanged(screenOn: Boolean) {
        Log.i(TAG, "Screen: $screenOn")
        if (screenOn && isOnKeyguard && !isDozing && hasMedia) {
            // 屏幕打开：从小白条位置（下方）弹簧弹出
            pillView?.showPill(fromY = getSpringStartYDown())
        } else if (!screenOn) {
            // 屏幕关闭：往下弹簧缩回
            pillView?.hidePill(toY = getSpringStartYDown())
        }
    }

    private fun getSpringStartYDown(): Float {
        // 从小白条位置开始（屏幕底部往上约 20-30dp，即药丸当前位置的下方）
        val pillCurrentY = pillView?.translationY ?: 0f
        return pillCurrentY + dp(50)  // 往下 50dp
    }

    /**
     * 桌面可见性变化（从 Launcher 状态监听）
     */
    fun onLauncherVisibilityChanged(visible: Boolean) {
        if (isLauncherVisible == visible) return
        isLauncherVisible = visible
        Log.d(TAG, "Launcher visible: $visible")
        updateVisibility()
    }

    /**
     * 设置显示模式
     */
    fun setDisplayMode(showOnKeyguard: Boolean, showOnLauncher: Boolean) {
        this.showOnKeyguard = showOnKeyguard
        this.showOnLauncher = showOnLauncher
        Log.i(TAG, "Display mode: keyguard=$showOnKeyguard launcher=$showOnLauncher")
        updateVisibility()
    }

    private fun updateVisibility() {
        // 检查当前位置是否应该显示
        val shouldShowOnKeyguard = showOnKeyguard && isOnKeyguard && !isDozing
        val shouldShowOnLauncher = showOnLauncher && isLauncherVisible && !isOnKeyguard
        val locationValid = shouldShowOnKeyguard || shouldShowOnLauncher

        // 检查其他条件
        val shouldShow = locationValid &&
                         hasMedia &&
                         HookPrefReader.masterEnabled &&
                         !isShadeExpanded &&
                         !isQsExpanded &&
                         !isControlCenterOpen &&
                         !isBouncerShowing

        Log.d(TAG, "Visibility: shouldShow=$shouldShow (kg=$isOnKeyguard launcher=$isLauncherVisible " +
                "doze=$isDozing media=$hasMedia shade=$isShadeExpanded qs=$isQsExpanded " +
                "cc=$isControlCenterOpen bouncer=$isBouncerShowing)")

        if (shouldShow) {
            pillView?.showPill()
        } else {
            pillView?.hidePill()
        }
    }

    fun isInjected(): Boolean = injected
    fun hasMediaDataManager(): Boolean = mediaDataManager != null
    fun isOnKeyguard(): Boolean = isOnKeyguard

    /**
     * 壁纸变化时刷新模糊效果
     */
    fun refreshBlur() {
        pillView?.refreshBlur()
    }

    // ═══════════════════════════════════════════════════════
    //  MediaDataManager
    // ═══════════════════════════════════════════════════════

    fun setMediaDataManager(manager: Any, classLoader: ClassLoader) {
        if (mediaDataManager != null) return
        mediaDataManager = manager

        try {
            val mgrClass = manager.javaClass
            val listenerCls = Class.forName(
                "com.android.systemui.media.controls.domain.pipeline.MediaDataManager\$Listener",
                false, classLoader
            )

            if (!listenerCls.isInterface) {
                Log.w(TAG, "Listener not interface, fallback")
                startMediaSessionFallback()
                return
            }

            val addListenerMethod = mgrClass.getDeclaredMethod("addListener", listenerCls).apply { isAccessible = true }

            mediaDataListener = java.lang.reflect.Proxy.newProxyInstance(
                classLoader, arrayOf(listenerCls)
            ) { _, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(this)
                    "equals" -> (this === args?.get(0))
                    "toString" -> "MediaPillListener"
                    "onMediaDataLoaded" -> {
                        val key = args?.get(0) as? String
                        val data = args?.getOrNull(2)
                        if (data != null) handleMediaData(key, data)
                        null
                    }
                    "onMediaDataRemoved" -> {
                        val key = args?.get(0) as? String
                        if (key == currentMediaKey) {
                            currentMediaKey = null
                            hasMedia = false
                            stopProgressUpdate()
                            mainHandler.post { updateVisibility() }
                        }
                        null
                    }
                    "onMediaDataUpdated" -> {
                        val key = args?.get(0) as? String
                        val data = args?.getOrNull(1) ?: args?.getOrNull(2)
                        if (data != null) handleMediaData(key, data)
                        null
                    }
                    else -> null
                }
            }

            addListenerMethod.invoke(manager, mediaDataListener)
            Log.i(TAG, "MediaDataManager.Listener registered ✓")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register listener", e)
            startMediaSessionFallback()
        }
    }

    private fun handleMediaData(key: String?, data: Any) {
        try {
            currentMediaKey = key
            val song = fldSong?.get(data) as? CharSequence ?: ""
            val artist = fldArtist?.get(data) as? CharSequence ?: ""
            val isPlaying = fldIsPlaying?.get(data) as? Boolean ?: false
            val pkg = fldPackageName?.get(data) as? String ?: ""
            currentPackageName = pkg

            val artworkIcon = fldArtwork?.get(data) as? android.graphics.drawable.Icon
            val bitmap = pillView?.iconToBitmap(artworkIcon, 120)

            hasMedia = true
            mainHandler.post {
                pillView?.updateMedia(song, artist, bitmap, isPlaying)
                updateVisibility()
            }

            if (isPlaying) startProgressUpdate()
        } catch (e: Exception) {
            Log.e(TAG, "handleMediaData failed", e)
        }
    }

    private fun startProgressUpdate() {
        stopProgressUpdate()
        progressRunnable = object : Runnable {
            override fun run() {
                try {
                    val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                    val pkg = currentPackageName ?: return
                    val controller = msm.getActiveSessions(null).firstOrNull { it.packageName == pkg }
                    val state = controller?.playbackState
                    val pos = state?.position ?: 0L
                    val dur = controller?.metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                    if (dur > 0) {
                        val fraction = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                        pillView?.updateProgress(fraction)
                    }
                } catch (_: Exception) { }
                mainHandler.postDelayed(this, 1000)
            }
        }
        mainHandler.post(progressRunnable!!)
    }

    private fun stopProgressUpdate() {
        progressRunnable?.let { mainHandler.removeCallbacks(it) }
        progressRunnable = null
    }

    private fun startMediaSessionFallback() {
        try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm.addOnActiveSessionsChangedListener({ _ ->
                refreshFromMediaSessions(msm)
            }, null)
            refreshFromMediaSessions(msm)
            Log.i(TAG, "MediaSession fallback started ✓")
        } catch (e: Exception) {
            Log.e(TAG, "MediaSession fallback failed", e)
        }
    }

    private fun refreshFromMediaSessions(msm: MediaSessionManager) {
        try {
            val controllers = msm.getActiveSessions(null)
            val active = controllers.firstOrNull {
                it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
            } ?: controllers.firstOrNull()

            if (active != null) {
                currentPackageName = active.packageName
                hasMedia = true
                val meta = active.metadata
                val title = meta?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: ""
                val artist = meta?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                val art = meta?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART)
                val playing = active.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING

                mainHandler.post {
                    pillView?.updateMedia(title, artist, art, playing)
                    updateVisibility()
                }
            } else {
                hasMedia = false
                mainHandler.post { updateVisibility() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "refreshFromMediaSessions failed", e)
        }
    }

    private fun togglePlayPause() {
        try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val pkg = currentPackageName ?: return
            val controller = msm.getActiveSessions(null).firstOrNull { it.packageName == pkg }
            controller?.let {
                val playing = it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
                if (playing) it.transportControls.pause()
                else it.transportControls.play()
            }
        } catch (e: Exception) {
            Log.e(TAG, "togglePlayPause failed", e)
        }
    }

    private fun skipToPrevious() {
        try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val pkg = currentPackageName ?: return
            val controller = msm.getActiveSessions(null).firstOrNull { it.packageName == pkg }
            controller?.transportControls?.skipToPrevious()
            Log.d(TAG, "Skip to previous")
        } catch (e: Exception) {
            Log.e(TAG, "skipToPrevious failed", e)
        }
    }

    private fun skipToNext() {
        try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val pkg = currentPackageName ?: return
            val controller = msm.getActiveSessions(null).firstOrNull { it.packageName == pkg }
            controller?.transportControls?.skipToNext()
            Log.d(TAG, "Skip to next")
        } catch (e: Exception) {
            Log.e(TAG, "skipToNext failed", e)
        }
    }

    private fun openMediaApp() {
        try {
            val pkg = currentPackageName ?: return
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            intent?.let {
                it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
                Log.d(TAG, "Open media app: $pkg")
            }
        } catch (e: Exception) {
            Log.e(TAG, "openMediaApp failed", e)
        }
    }
}
