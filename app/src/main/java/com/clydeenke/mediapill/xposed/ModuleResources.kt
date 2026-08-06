package com.clydeenke.mediapill.xposed

import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.Log

/**
 * 在 SystemUI 进程中加载图标资源。
 *
 * 优先从 SystemUI 自身资源加载（SystemUI 有媒体播放图标），
 * 找不到则返回 null，由 MediaPillView 使用代码绘制的 Fallback 图标。
 */
object ModuleResources {

    private const val TAG = "MediaPill"
    private const val SYSTEMUI_PKG = "com.android.systemui"

    private var systemUiResources: Resources? = null

    fun init(context: Context) {
        systemUiResources = context.resources
        Log.i(TAG, "ModuleResources initialized (using SystemUI resources)")
    }

    /**
     * 按资源名加载 drawable。
     * 先查 SystemUI 资源，再查 Android framework 资源。
     * 都找不到返回 null（触发 Fallback 图标）。
     */
    fun getDrawable(name: String): Drawable? {
        val res = systemUiResources ?: return null

        // 1. 尝试 SystemUI 自身资源
        try {
            val id = res.getIdentifier(name, "drawable", SYSTEMUI_PKG)
            if (id != 0) {
                return res.getDrawable(id, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "getIdentifier($name) from SystemUI failed", e)
        }

        // 2. 尝试 Android framework 资源（ic_media_play 等）
        try {
            val id = res.getIdentifier(name, "drawable", "android")
            if (id != 0) {
                return res.getDrawable(id, null)
            }
        } catch (_: Exception) { }

        return null
    }
}
