package com.clydeenke.mediapill.xposed

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * 药丸背景辅助类。
 *
 * Stage 2 初版：使用半透明深色背景 + 轻微白色玻璃遮罩。
 * 不做真实壁纸模糊（后续可用 RenderEffect 实现）。
 *
 * 接口与 MediaPillView 完全兼容：
 * - createBlurBackground() 返回带 ImageView 子 View 的 FrameLayout
 * - createGlassOverlay() 返回玻璃遮罩 View
 * - updateBlurSnapshot() 为 no-op（静态背景不需要更新）
 */
class BlurBackgroundHelper(private val context: Context) {

    /**
     * 创建模糊背景容器。
     * 返回一个 FrameLayout，内含一个 ImageView（供 updateBlurSnapshot 使用）。
     * 背景为半透明深色，带圆角。
     */
    fun createBlurBackground(cornerRadius: Float): FrameLayout {
        return FrameLayout(context).apply {
            val imageView = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                // 深色半透明背景代替模糊壁纸
                setBackgroundColor(Color.argb(130, 0, 0, 0))
            }
            addView(imageView)

            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                this.cornerRadius = cornerRadius
                setColor(Color.argb(130, 0, 0, 0))
            }
        }
    }

    /**
     * 创建玻璃遮罩层（轻微白色调，增加质感）。
     */
    fun createGlassOverlay(cornerRadius: Float): View {
        return View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                this.cornerRadius = cornerRadius
                setColor(Color.argb(18, 255, 255, 255))
            }
        }
    }

    /**
     * 更新模糊快照（no-op — 静态背景不需要更新）。
     * 保留接口以兼容 MediaPillView 的调用。
     */
    fun updateBlurSnapshot(imageView: ImageView, x: Int, y: Int, w: Int, h: Int) {
        // no-op: 使用静态半透明背景
    }
}
