package com.clydeenke.mediapill.xposed

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * 锁屏媒体药丸 — 液态玻璃效果
 *
 * 使用 SystemUI 原生模糊 API (createBackgroundBlurDrawable) 实现真正的实时模糊
 * 参考 Iconify 的 HeadsUpBlur 实现
 */
class MediaPillView(context: Context) : FrameLayout(context) {

    companion object {
        private const val TAG = "MediaPill"
        private const val BLUR_RADIUS_DP = 40  // 模糊半径（dp）
        private const val OVERLAY_ALPHA = 25   // 叠加层透明度
    }

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density).toInt()
    private fun dpf(v: Float): Float = v * density

    // 尺寸
    private val pillHeight = dp(52f)
    private val pillMaxWidth = dp(280f)
    private val artworkSize = dp(40f)
    private val playBtnSize = dp(44f)
    private val cornerRadius = dpf(26f)

    // 内容层
    private val artworkView: ImageView
    private val titleView: TextView
    private val artistView: TextView
    private val playPauseBtn: ImageView
    private val prevBtn: ImageView
    private val nextBtn: ImageView
    private val contentLayout: LinearLayout

    // 状态
    private var isPlaying = false
    private var isShown = false
    private var progressFraction = 0f
    private var blurApplied = false

    // 图标
    private var playIcon: Drawable? = null
    private var pauseIcon: Drawable? = null
    private var prevIcon: Drawable? = null
    private var nextIcon: Drawable? = null

    // 回调
    var onPlayPauseToggle: (() -> Unit)? = null
    var onPreviousClicked: (() -> Unit)? = null
    var onNextClicked: (() -> Unit)? = null
    var onArtworkClick: (() -> Unit)? = null

    init {
        isClickable = false
        isFocusable = false

        // 初始背景为透明
        setBackgroundColor(Color.TRANSPARENT)

        // 1. 玻璃质感叠加层（底层）
        val glassOverlay = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
            background = createGlassOverlay()
        }
        addView(glassOverlay)

        // 3. 内容层（最上层）
        contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
        }

        // 封面（可点击展开详情）
        artworkView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(artworkSize, artworkSize).apply {
                marginEnd = dp(10f)
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dpf(8f))
                }
            }
            setBackgroundColor(Color.argb(30, 255, 255, 255))
            isClickable = true
            isFocusable = true
            setOnClickListener { 
                animateClickFeedback(this)
                onArtworkClick?.invoke() 
            }
        }
        contentLayout.addView(artworkView)

        // 文字区域（歌名 + 歌手）
        val textLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(130f), LayoutParams.WRAP_CONTENT)
        }

        // 歌名
        titleView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
            setSingleLine(true)
            setHorizontalFadingEdgeEnabled(true)
            setFadingEdgeLength(dp(16f))
        }
        textLayout.addView(titleView)

        // 歌手名（新增）
        artistView = TextView(context).apply {
            setTextColor(Color.argb(180, 255, 255, 255))
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE // 默认隐藏，需要时显示
        }
        textLayout.addView(artistView)
        contentLayout.addView(textLayout)

        // 控制按钮组
        val controlsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }

        // 上一首按钮
        prevBtn = createControlButton("ic_skip_prev_24") {
            animateClickFeedback(it)
            onPreviousClicked?.invoke()
        }
        controlsLayout.addView(prevBtn)

        // 播放/暂停按钮
        playPauseBtn = createPlayButton()
        controlsLayout.addView(playPauseBtn)

        // 下一首按钮
        nextBtn = createControlButton("ic_skip_next_24") {
            animateClickFeedback(it)
            onNextClicked?.invoke()
        }
        controlsLayout.addView(nextBtn)

        contentLayout.addView(controlsLayout)

        addView(contentLayout, FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        ))

        // 药丸形状
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
        clipToOutline = true

        // 加载图标
        playIcon = ModuleResources.getDrawable("ic_play_24")
        pauseIcon = ModuleResources.getDrawable("ic_pause_24")
        prevIcon = ModuleResources.getDrawable("ic_skip_prev_24")
        nextIcon = ModuleResources.getDrawable("ic_skip_next_24")

        // 初始状态
        visibility = View.GONE
        alpha = 0f
        scaleX = 0.9f
        scaleY = 0.9f
    }

    /**
     * 创建玻璃质感叠加层
     */
    private fun createGlassOverlay(): Drawable {
        return android.graphics.drawable.LayerDrawable(arrayOf(
            // 白色磨砂层（很淡）
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = cornerRadius
                setColor(Color.argb(25, 255, 255, 255))
            },
            // 顶部高光
            android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.argb(40, 255, 255, 255),
                    Color.argb(10, 255, 255, 255),
                    Color.argb(0, 255, 255, 255)
                )
            ).apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = cornerRadius
            },
            // 边框
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = cornerRadius
                setColor(Color.TRANSPARENT)
                setStroke(dp(1f), Color.argb(100, 255, 255, 255))
            }
        ))
    }

    /**
     * 应用 SystemUI 原生模糊效果
     * 参考 Iconify 的 HeadsUpBlur 实现
     */
    fun applySystemBlur() {
        if (blurApplied) return

        try {
            // 使用反射调用 SystemUI 的 createBackgroundBlurDrawable
            val viewRootImpl = this::class.java.getMethod("getViewRootImpl").invoke(this)
                ?: return

            val createBlurMethod = viewRootImpl::class.java.getMethod("createBackgroundBlurDrawable")
            val blurDrawable = createBlurMethod.invoke(viewRootImpl) as? Drawable
                ?: return

            // 设置模糊参数
            val blurRadiusPx = dp(BLUR_RADIUS_DP.toFloat())

            // 设置圆角
            blurDrawable::class.java.getMethod("setCornerRadius", Float::class.java)
                .invoke(blurDrawable, cornerRadius)

            // 设置模糊半径
            blurDrawable::class.java.getMethod("setBlurRadius", Int::class.java)
                .invoke(blurDrawable, blurRadiusPx)

            // 设置颜色（半透明背景色）
            val backgroundColor = ColorUtils.setAlphaComponent(
                Color.WHITE,
                OVERLAY_ALPHA
            )
            blurDrawable::class.java.getMethod("setColor", Int::class.java)
                .invoke(blurDrawable, backgroundColor)

            // 创建 LayerDrawable：模糊层 + 玻璃质感层
            val layers = arrayOf(blurDrawable, createGlassOverlay())
            val layerDrawable = LayerDrawable(layers)

            // 设置为背景
            background = layerDrawable
            blurApplied = true

            Log.i(TAG, "System blur applied successfully (radius=${BLUR_RADIUS_DP}dp)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply system blur", e)
            // 降级到纯色背景
            applyFallbackBackground()
        }
    }

    /**
     * 降级方案：纯色磨砂背景（当 SystemUI 模糊不可用时）
     */
    private fun applyFallbackBackground() {
        background = createGlassOverlay()
        Log.w(TAG, "Using fallback background (no blur)")
    }

    /**
     * 刷新模糊效果（壁纸变化时调用）
     */
    fun refreshBlur() {
        if (!blurApplied) {
            applySystemBlur()
        }
    }

    private fun createPlayButton(): ImageView {
        return createControlButton("ic_play_24") { view ->
            onPlayPauseToggle?.invoke()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(playBtnSize, playBtnSize)
        }
    }

    /**
     * 创建控制按钮（上一首/下一首）
     */
    private fun createControlButton(iconName: String, onClick: (View) -> Unit): ImageView {
        val btnSize = dp(36f)
        return ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                marginStart = dp(4f)
                marginEnd = dp(4f)
            }

            val rippleColor = Color.argb(40, 255, 255, 255)
            val mask = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            background = RippleDrawable(
                android.content.res.ColorStateList.valueOf(rippleColor),
                null,
                mask
            )

            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            scaleType = ImageView.ScaleType.CENTER
            setImageDrawable(ModuleResources.getDrawable(iconName) ?: createFallbackIcon(false))
            isClickable = true
            isFocusable = true
            setOnClickListener { 
                animateClickFeedback(this)
                onClick(this)
            }
        }
    }

    /**
     * 点击反馈动画（缩放效果）
     */
    private fun animateClickFeedback(view: View) {
        view.animate()
            .scaleX(0.85f)
            .scaleY(0.85f)
            .setDuration(100)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(150)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.5f))
                    .start()
            }
            .start()
    }

    // ═══════════════════════════════════════════════════════
    //  位置控制
    // ═══════════════════════════════════════════════════════

    private var positionSpring: SpringAnimation? = null

    fun setPositionTranslationY(y: Float, animate: Boolean) {
        if (animate && isShown) {
            positionSpring?.cancel()
            positionSpring = SpringAnimation(this, DynamicAnimation.TRANSLATION_Y, y).apply {
                spring = SpringForce(y).apply {
                    dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
                    stiffness = SpringForce.STIFFNESS_LOW
                }
                start()
            }
        } else {
            translationY = y
        }
    }

    // ═══════════════════════════════════════════════════════
    //  显隐动画
    // ═══════════════════════════════════════════════════════

    private var showAnimY: SpringAnimation? = null
    private var showAnimAlpha: SpringAnimation? = null
    private var showAnimScaleX: SpringAnimation? = null
    private var showAnimScaleY: SpringAnimation? = null
    private var hideAnimSet: List<SpringAnimation>? = null

    fun showPill(fromY: Float = translationY + dp(50f)) {
        if (isShown) return
        isShown = true

        cancelAllAnimations()

        visibility = View.VISIBLE
        alpha = 0f
        scaleX = 0.9f
        scaleY = 0.9f
        translationY = fromY

        val targetY = translationY - dp(50f)

        showAnimAlpha = SpringAnimation(this, DynamicAnimation.ALPHA, 1f).apply {
            spring = SpringForce(1f).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                stiffness = SpringForce.STIFFNESS_MEDIUM
            }
        }

        showAnimY = SpringAnimation(this, DynamicAnimation.TRANSLATION_Y, targetY).apply {
            spring = SpringForce(targetY).apply {
                dampingRatio = 0.5f
                stiffness = 380f
            }
        }

        showAnimScaleX = SpringAnimation(this, DynamicAnimation.SCALE_X, 1f).apply {
            spring = SpringForce(1f).apply {
                dampingRatio = 0.55f
                stiffness = 420f
            }
        }

        showAnimScaleY = SpringAnimation(this, DynamicAnimation.SCALE_Y, 1f).apply {
            spring = SpringForce(1f).apply {
                dampingRatio = 0.55f
                stiffness = 420f
            }
        }

        showAnimAlpha?.start()
        showAnimY?.start()
        showAnimScaleX?.start()
        showAnimScaleY?.start()
    }

    fun hidePill(toY: Float = translationY + dp(50f)) {
        if (!isShown) return
        isShown = false

        cancelAllAnimations()

        val hideY = SpringAnimation(this, DynamicAnimation.TRANSLATION_Y, toY).apply {
            spring = SpringForce(toY).apply {
                dampingRatio = 0.65f
                stiffness = 450f
            }
        }

        val hideScaleX = SpringAnimation(this, DynamicAnimation.SCALE_X, 0.9f).apply {
            spring = SpringForce(0.9f).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                stiffness = SpringForce.STIFFNESS_HIGH
            }
        }

        val hideScaleY = SpringAnimation(this, DynamicAnimation.SCALE_Y, 0.9f).apply {
            spring = SpringForce(0.9f).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                stiffness = SpringForce.STIFFNESS_HIGH
            }
        }

        val hideAlpha = SpringAnimation(this, DynamicAnimation.ALPHA, 0f).apply {
            spring = SpringForce(0f).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                stiffness = SpringForce.STIFFNESS_HIGH
            }
            addEndListener { _, _, _, _ ->
                if (!isShown) visibility = View.GONE
            }
        }

        hideY.start()
        hideScaleX.start()
        hideScaleY.start()
        hideAlpha.start()

        hideAnimSet = listOf(hideY, hideScaleX, hideScaleY, hideAlpha)
    }

    fun hidePillImmediate() {
        isShown = false
        cancelAllAnimations()
        visibility = View.GONE
        alpha = 0f
        scaleX = 0.9f
        scaleY = 0.9f
    }

    private fun cancelAllAnimations() {
        listOfNotNull(
            positionSpring, showAnimY, showAnimAlpha,
            showAnimScaleX, showAnimScaleY
        ).forEach { it.cancel() }
        hideAnimSet?.forEach { it.cancel() }
    }

    fun isPillShown(): Boolean = isShown

    // ═══════════════════════════════════════════════════════
    //  数据更新
    // ═══════════════════════════════════════════════════════

    fun updateMedia(title: CharSequence, artist: CharSequence, artwork: Bitmap?, playing: Boolean) {
        isPlaying = playing
        
        // 更新歌名
        titleView.text = title
        
        // 更新歌手名（如果有）
        if (artist.isNotEmpty()) {
            artistView.text = artist
            artistView.visibility = View.VISIBLE
        } else {
            artistView.visibility = View.GONE
        }

        // 更新封面
        if (artwork != null) {
            artworkView.setImageBitmap(artwork)
            artworkView.setBackgroundColor(Color.TRANSPARENT)
            artworkView.invalidate()
        } else {
            artworkView.setImageDrawable(null)
            artworkView.setBackgroundColor(Color.argb(30, 255, 255, 255))
        }

        // 更新播放按钮图标
        val icon = if (playing) pauseIcon else playIcon
        playPauseBtn.setImageDrawable(icon ?: createFallbackIcon(playing))

        invalidate()
    }

    fun updateProgress(fraction: Float) {
        progressFraction = fraction.coerceIn(0f, 1f)
        invalidate()
    }

    private fun createFallbackIcon(isPause: Boolean): Drawable {
        return FallbackPlayIcon(if (isPause) 1 else 0)
    }

    fun iconToBitmap(icon: Icon?, size: Int): Bitmap? {
        if (icon == null) return null
        return try {
            val drawable = icon.loadDrawable(context) ?: return null
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "iconToBitmap failed", e)
            null
        }
    }

    fun setWidthLimits(minDp: Int, maxDp: Int) {}

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)

        val targetWidth = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> minOf(pillMaxWidth, widthSize)
            else -> pillMaxWidth
        }

        setMeasuredDimension(targetWidth.coerceAtLeast(dp(200f)), pillHeight)

        super.onMeasure(
            MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(pillHeight, MeasureSpec.EXACTLY)
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 清理资源
        blurApplied = false
    }
}

// ═══════════════════════════════════════════════════════
//  Fallback 图标
// ═══════════════════════════════════════════════════════

class FallbackPlayIcon(private val type: Int = 0) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0 || h <= 0) return

        when (type) {
            1 -> {
                val barW = w * 0.22f
                val barH = h * 0.55f
                val gap = w * 0.12f
                val left = (w - barW * 2 - gap) / 2
                val top = (h - barH) / 2
                val r = barW / 2.5f
                canvas.drawRoundRect(RectF(left, top, left + barW, top + barH), r, r, paint)
                canvas.drawRoundRect(RectF(left + barW + gap, top, left + barW * 2 + gap, top + barH), r, r, paint)
            }
            else -> {
                val path = Path().apply {
                    val cx = w * 0.55f
                    val cy = h * 0.5f
                    val halfH = h * 0.35f
                    val halfW = w * 0.3f
                    moveTo(cx - halfW * 0.3f, cy - halfH)
                    lineTo(cx + halfW, cy)
                    lineTo(cx - halfW * 0.3f, cy + halfH)
                    close()
                }
                canvas.drawPath(path, paint)
            }
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Suppress("DEPRECATION") override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = 48
    override fun getIntrinsicHeight(): Int = 48
}
