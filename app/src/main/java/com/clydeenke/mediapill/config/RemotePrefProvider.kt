package com.clydeenke.mediapill.config

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * 跨进程 SharedPreferences 桥。
 *
 * App 端写入 → ContentProvider 持有 SharedPreferences 实例。
 * Hook 端（SystemUI 进程）通过 ContentResolver.call() 读取。
 *
 * 不依赖 libxposed-service，兼容所有 Xposed 框架（LSPosed / ReVanced Xposed 等）。
 */
class RemotePrefProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.clydeenke.mediapill.prefs"
        private const val TAG = "RemotePrefProvider"

        // call() 方法名
        const val M_GET_BOOLEAN = "getBoolean"
        const val M_GET_INT = "getInt"
        const val M_GET_STRING = "getString"
        const val M_GET_ALL = "getAll"
        const val M_PUT_BOOLEAN = "putBoolean"
        const val M_PUT_INT = "putInt"
        const val M_PUT_STRING = "putString"

        // Bundle key
        const val K_KEY = "key"
        const val K_DEFAULT = "default"
        const val K_VALUE = "value"
    }

    private lateinit var prefs: SharedPreferences

    override fun onCreate(): Boolean {
        prefs = context!!.getSharedPreferences(Config.GROUP, Context.MODE_PRIVATE)
        Log.i(TAG, "onCreate: prefs=${Config.GROUP}")
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        try {
            when (method) {
                M_GET_BOOLEAN -> {
                    val key = extras?.getString(K_KEY) ?: return null
                    val def = extras.getBoolean(K_DEFAULT, false)
                    return Bundle().apply { putBoolean(K_VALUE, prefs.getBoolean(key, def)) }
                }

                M_GET_INT -> {
                    val key = extras?.getString(K_KEY) ?: return null
                    val def = extras.getInt(K_DEFAULT, 0)
                    return Bundle().apply { putInt(K_VALUE, prefs.getInt(key, def)) }
                }

                M_GET_STRING -> {
                    val key = extras?.getString(K_KEY) ?: return null
                    val def = extras.getString(K_DEFAULT, null)
                    return Bundle().apply { putString(K_VALUE, prefs.getString(key, def)) }
                }

                M_GET_ALL -> {
                    val bundle = Bundle()
                    prefs.all.forEach { (k, v) ->
                        when (v) {
                            is Boolean -> bundle.putBoolean(k, v)
                            is Int -> bundle.putInt(k, v)
                            is String -> bundle.putString(k, v)
                            is Long -> bundle.putLong(k, v)
                            is Float -> bundle.putFloat(k, v)
                        }
                    }
                    return bundle
                }

                M_PUT_BOOLEAN -> {
                    val key = extras?.getString(K_KEY) ?: return null
                    val value = extras.getBoolean(K_VALUE, false)
                    prefs.edit().putBoolean(key, value).apply()
                    return Bundle.EMPTY
                }

                M_PUT_INT -> {
                    val key = extras?.getString(K_KEY) ?: return null
                    val value = extras.getInt(K_VALUE, 0)
                    prefs.edit().putInt(key, value).apply()
                    return Bundle.EMPTY
                }

                M_PUT_STRING -> {
                    val key = extras?.getString(K_KEY) ?: return null
                    val value = extras.getString(K_VALUE, null)
                    prefs.edit().putString(key, value).apply()
                    return Bundle.EMPTY
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "call($method) failed", t)
        }
        return null
    }

    // ---- 以下方法不使用，但 ContentProvider 要求实现 ----

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0
}
