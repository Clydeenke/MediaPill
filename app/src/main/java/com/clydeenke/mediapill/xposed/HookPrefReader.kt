package com.clydeenke.mediapill.xposed

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.clydeenke.mediapill.config.Config

/**
 * Hook 端（SystemUI 进程）配置读取器。
 *
 * 通过 ContentResolver.call() 跨进程读取 MediaPill app 的 SharedPreferences。
 * 不依赖 libxposed-service，兼容所有 Xposed 框架。
 *
 * 重要：所有跨进程 IPC 在后台线程执行，主线程只读缓存值（零 IPC → 零 ANR）。
 */
object HookPrefReader {

    private const val TAG = "MediaPill"
    private const val AUTHORITY = "com.clydeenke.mediapill.prefs"
    private val URI = Uri.parse("content://$AUTHORITY")

    private const val K_KEY = "key"
    private const val K_DEFAULT = "default"
    private const val K_VALUE = "value"

    // ── 缓存值（主线程直接读，零 IPC） ──
    @Volatile var masterEnabled: Boolean = Config.MASTER_SWITCH_DEFAULT
        private set

    @Volatile var pillPositionPercent: Int = Config.PILL_POSITION_PERCENT_DEFAULT
        private set

    // 新增用户可调参数
    @Volatile var pillYOffsetDp: Int = 0        // Y 偏移（dp，可正可负）
        private set
    @Volatile var pillMaxWidthDp: Int = 280     // 最大宽度（dp）
        private set
    @Volatile var pillMinWidthDp: Int = 180     // 最小宽度（dp）
        private set
    @Volatile var pillAlphaPercent: Int = 85    // 背景透明度（%）
        private set

    private var applicationContext: Context? = null
    @Volatile private var initialized = false

    /**
     * 初始化缓存。在后台线程执行首次读取 + 定期刷新。
     * 必须在 PillOverlayController.init() 中调用。
     */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        applicationContext = context.applicationContext

        // 首次同步读取（后台线程，不阻塞主线程）
        Thread {
            refresh()
            // 定期刷新（每 15 秒）
            while (true) {
                try {
                    Thread.sleep(15_000)
                } catch (_: InterruptedException) {
                    break
                }
                refresh()
            }
        }.apply {
            isDaemon = true
            name = "MediaPill-ConfigRefresh"
            start()
        }
        Log.i(TAG, "HookPrefReader initialized (async refresh every 15s)")
    }

    /**
     * 后台线程执行：读取全部配置并更新缓存。
     */
    private fun refresh() {
        val ctx = applicationContext ?: return
        try {
            val all = getAll(ctx)
            (all[Config.KEY_MASTER_SWITCH] as? Boolean)?.let { masterEnabled = it }
            (all[Config.KEY_PILL_POSITION_PERCENT] as? Int)?.let { pillPositionPercent = it }
            (all[Config.KEY_PILL_Y_OFFSET_DP] as? Int)?.let { pillYOffsetDp = it }
            (all[Config.KEY_PILL_MAX_WIDTH_DP] as? Int)?.let { pillMaxWidthDp = it }
            (all[Config.KEY_PILL_MIN_WIDTH_DP] as? Int)?.let { pillMinWidthDp = it }
            (all[Config.KEY_PILL_ALPHA_PERCENT] as? Int)?.let { pillAlphaPercent = it }
            Log.d(TAG, "Config refreshed: master=$masterEnabled pos=$pillPositionPercent% " +
                    "yOffset=${pillYOffsetDp}dp width=$pillMinWidthDp-$pillMaxWidthDp alpha=$pillAlphaPercent%")
        } catch (e: Exception) {
            Log.e(TAG, "Config refresh failed", e)
        }
    }

    // ── 同步方法（仅在后台线程使用，或一次性读取） ──

    fun getBoolean(context: Context, key: String, default: Boolean): Boolean {
        return try {
            val extras = Bundle().apply {
                putString(K_KEY, key)
                putBoolean(K_DEFAULT, default)
            }
            val result = context.contentResolver.call(URI, "getBoolean", null, extras)
            result?.getBoolean(K_VALUE, default) ?: default
        } catch (e: Exception) {
            Log.e(TAG, "getBoolean($key) failed", e)
            default
        }
    }

    fun getInt(context: Context, key: String, default: Int): Int {
        return try {
            val extras = Bundle().apply {
                putString(K_KEY, key)
                putInt(K_DEFAULT, default)
            }
            val result = context.contentResolver.call(URI, "getInt", null, extras)
            result?.getInt(K_VALUE, default) ?: default
        } catch (e: Exception) {
            Log.e(TAG, "getInt($key) failed", e)
            default
        }
    }

    /** 一次性读取全部配置，减少跨进程调用次数。 */
    fun getAll(context: Context): Map<String, Any?> {
        return try {
            val result = context.contentResolver.call(URI, "getAll", null, null)
            result?.keySet()?.associateWith { result[it] } ?: emptyMap()
        } catch (e: Exception) {
            Log.e(TAG, "getAll failed", e)
            emptyMap()
        }
    }
}
