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
 */
object HookPrefReader {

    private const val TAG = "MediaPill"
    private const val AUTHORITY = "com.clydeenke.mediapill.prefs"
    private val URI = Uri.parse("content://$AUTHORITY")

    private const val K_KEY = "key"
    private const val K_DEFAULT = "default"
    private const val K_VALUE = "value"

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

    /** 便捷方法：检查模块总开关是否开启。 */
    fun isMasterEnabled(context: Context): Boolean {
        return getBoolean(context, Config.MASTER_SWITCH, Config.MASTER_SWITCH_DEFAULT)
    }
}
