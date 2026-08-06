package com.clydeenke.mediapill.xposed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * 锁屏媒体药丸 — 真正的药丸形状 + 高级毛玻璃模糊背景。
 *
 * 设计规范：
 * - 真药丸：椭圆胶囊形（高度 48dp，固定宽度 260dp）
 * - 毛玻璃：多层渐变 + 噪点纹理模拟高级玻璃质感
 * - 布局：[封面 40dp] [歌名 滚动] [播放按钮 36dp]
 * - 无水波纹，精致按压效果
 */
class MediaPillView(context: Context) : FrameLayout(context) {

    companion object {
        private const val TAG = "MediaPill"
    }

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density).toInt()
    private fun dpf(v: Float): Float = v * density

    // 尺寸 — 固定宽度，不再自适应
    private val pillHeight = dp(48f)
    private val pillWidth = dp(260f)      // 固定宽度
    private val artworkSize = dp(40f)     // 增大封面
    private val playBtnSize = dp(36f)     // 增大按钮
    private val cornerRadius = dpf(24f)   // 半高 = 全圆角

    // 高级玻璃质感绘制
    private val glassBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 进度条
    private val progressBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 255, 255, 255)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
    }

    // 子 View
    private val artworkView: ImageView
    private val titleView: TextView
    private val playPauseBtn: PillButton
    private val contentLayout: LinearLayout

    // 状态
    private var isPlaying = false
    private var isShown = false
    private var progressFraction = 0f

    // 图标
    private var playIcon: Drawable? = null
    private var pauseIcon: Drawable? = null

    init {
        // 禁用默认水波纹
        isClickable = false
        isFocusable = false

        // 药丸形状轮廓 — 注意：clipToOutline 会裁剪边框，所以边框要画在内部
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }

        // 初始化玻璃质感画笔
        initGlassPaints()

        // 加载图标
        playIcon = ModuleResources.getDrawable("ic_play_24")
        pauseIcon = ModuleResources.getDrawable("ic_pause_24")

        // 内容布局（水平）
        contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(4f), dp(12f), dp(4f))
        }

        // 封面 — 修复裁剪
        artworkView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(artworkSize, artworkSize).apply {
                marginEnd = dp(12f)
            }
            // 使用 clipToOutline 实现圆角
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dpf(10f))
                }
            }
            setBackgroundColor(Color.argb(30, 255, 255, 255))
        }
        contentLayout.addView(artworkView)

        // 歌名 — 固定宽度 + 跑马灯滚动
        titleView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true  // 必须设为 true 才能滚动
            setSingleLine(true)
            setHorizontalFadingEdgeEnabled(true)
            setFadingEdgeLength(dp(20f))
            // 固定宽度，不再自适应
            layoutParams = LinearLayout.LayoutParams(dp(140f), LayoutParams.WRAP_CONTENT)
        }
        contentLayout.addView(titleView)

        // 播放按钮 — 增大尺寸
        playPauseBtn = PillButton(context, dpf(16f)).apply {
            layoutParams = LinearLayout.LayoutParams(playBtnSize, playBtnSize).apply {
                marginStart = dp(10f)
            }
            setIcon(playIcon ?: FallbackPlayIcon())
            setOnClickListener { onPlayPauseToggle?.invoke() }
        }
        contentLayout.addView(playPauseBtn)

        addView(contentLayout, FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        ))

        // 初始状态
        visibility = View.GONE
        alpha = 0f
        scaleX = 0.85f
        scaleY = 0.85f
    }

    /**
     * 初始化高级玻璃质感画笔。
     */
    private fun initGlassPaints() {
        // 主背景：深色半透明 + 轻微蓝色调（类似 iOS 控制中心）
        glassBgPaint.apply {
            color = Color.argb(180, 25, 25, 30)
            style = Paint.Style.FILL
        }

        // 顶部高光（模拟玻璃反射）
        glassHighlightPaint.apply {
            style = Paint.Style.FILL
        }

        // 底部阴影（增加立体感）
        glassShadowPaint.apply {
            color = Color.argb(60, 0, 0, 0)
            style = Paint.Style.FILL
        }

        // 边框：半透明白色细线
        borderPaint.apply {
            color = Color.argb(40, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = dpf(1f)
            isAntiAlias = true
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 更新渐变
        if (w > 0 && h > 0) {
            glassHighlightPaint.shader = LinearGradient(
                0f, 0f, 0f, h * 0.6f,
                Color.argb(30, 255, 255, 255),
                Color.argb(0, 255, 255, 255),
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val innerRect = RectF(dpf(1f), dpf(1f), width - dpf(1f), height - dpf(1f))

        // 1. 主背景
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, glassBgPaint)

        // 2. 顶部高光（玻璃反射效果）
        val highlightRect = RectF(0f, 0f, width.toFloat(), height * 0.5f)
        canvas.drawRoundRect(highlightRect, cornerRadius, cornerRadius, glassHighlightPaint)

        // 3. 底部阴影
        val shadowRect = RectF(0f, height * 0.7f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(shadowRect, cornerRadius, cornerRadius, glassShadowPaint)

        // 4. 边框
        canvas.drawRoundRect(innerRect, cornerRadius - dpf(0.5f), cornerRadius - dpf(0.5f), borderPaint)

        // 5. 底部进度条
        if (progressFraction > 0f) {
            drawProgressBar(canvas)
        }
    }

    private fun drawProgressBar(canvas: Canvas) {
        val progressH = dpf(3f)
        val progressY = height - progressH - dpf(4f)
        val margin = dpf(12f)
        val maxWidth = width - margin * 2

        canvas.save()
        // 使用 clipPath 确保进度条不超出圆角
        val clipPath = Path().apply {
            addRoundRect(
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                cornerRadius, cornerRadius, Path.Direction.CW
            )
        }
        canvas.clipPath(clipPath)

        // 背景条
        canvas.drawRoundRect(
            margin, progressY,
            margin + maxWidth, progressY + progressH,
            progressH / 2, progressH / 2, progressBgPaint
        )

        // 进度条
        val progressWidth = maxWidth * progressFraction
        if (progressWidth > 0) {
            canvas.drawRoundRect(
                margin, progressY,
                margin + progressWidth, progressY + progressH,
                progressH / 2, progressH / 2, progressPaint
            )
        }
        canvas.restore()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 固定尺寸
        setMeasuredDimension(pillWidth, pillHeight)

        // 让子布局填满
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(pillWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(pillHeight, MeasureSpec.EXACTLY)
        )
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
    //  显隐动画（弹簧效果）
    // ═══════════════════════════════════════════════════════

    private var showAnimY: SpringAnimation? = null
    private var showAnimAlpha: SpringAnimation? = null
    private var showAnimScaleX: SpringAnimation? = null
    private var showAnimScaleY: SpringAnimation? = null
    private var hideAnimSet: List<SpringAnimation>? = null

    /**
     * 显示药丸 — 从下方弹簧弹出。
     */
    fun showPill(fromY: Float = translationY + dp(60f)) {
        if (isShown) return
        isShown = true

        cancelAllAnimations()

        visibility = View.VISIBLE
        alpha = 0f
        scaleX = 0.85f
        scaleY = 0.85f
        translationY = fromY

        // Alpha 淡入
        showAnimAlpha = SpringAnimation(this, DynamicAnimation.ALPHA, 1f).apply {
            spring = SpringForce(1f).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                stiffness = SpringForce.STIFFNESS_MEDIUM
            }
        }

        // Y 位置弹簧动画
        val targetY = translationY - (fromY - (translationY - dp(60f)))
        showAnimY = SpringAnimation(this, DynamicAnimation.TRANSLATION_Y, targetY).apply {
            spring = SpringForce(targetY).apply {
                dampingRatio = 0.55f  // 明显弹簧感
                stiffness = 350f
            }
        }

        // Scale 弹性放大
        showAnimScaleX = SpringAnimation(this, DynamicAnimation.SCALE_X, 1f).apply {
            spring = SpringForce(1f).apply {
                dampingRatio = 0.6f
                stiffness = 400f
            }
        }
        showAnimScaleY = SpringAnimation(this, DynamicAnimation.SCALE_Y, 1f).apply {
            spring = SpringForce(1f).apply {
                dampingRatio = 0.6f
                stiffness = 400f
            }
        }

        showAnimAlpha?.start()
        showAnimY?.start()
        showAnimScaleX?.start()
        showAnimScaleY?.start()
    }

    /**
     * 隐藏药丸 — 往下弹簧缩回。
     */
    fun hidePill(toY: Float = translationY + dp(60f)) {
        if (!isShown) return
        isShown = false

        cancelAllAnimations()

        val currentY = translationY

        // 往下缩回动画
        val hideY = SpringAnimation(this, DynamicAnimation.TRANSLATION_Y, toY).apply {
            spring = SpringForce(toY).apply {
                dampingRatio = 0.7f
                stiffness = 400f
            }
        }

        val hideScaleX = SpringAnimation(this, DynamicAnimation.SCALE_X, 0.85f).apply {
            spring = SpringForce(0.85f).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                stiffness = SpringForce.STIFFNESS_HIGH
            }
        }

        val hideScaleY = SpringAnimation(this, DynamicAnimation.SCALE_Y, 0.85f).apply {
            spring = SpringForce(0.85f).apply {
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
                if (!isShown) {
                    visibility = View.GONE
                }
            }
        }

        hideY.start()
        hideScaleX.start()
        hideScaleY.start()
        hideAlpha.start()

        hideAnimSet = listOf(hideY, hideScaleX, hideScaleY, hideAlpha)
    }

    /**
     * 立即隐藏（无动画）。
     */
    fun hidePillImmediate() {
        isShown = false
        cancelAllAnimations()
        visibility = View.GONE
        alpha = 0f
        scaleX = 0.85f
        scaleY = 0.85f
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

    var onPlayPauseToggle: (() -> Unit)? = null
    var onNextClicked: (() -> Unit)? = null

    fun updateMedia(title: CharSequence, artist: CharSequence, artwork: Bitmap?, playing: Boolean) {
        isPlaying = playing
        val displayText = if (title.isNotEmpty()) title else artist
        titleView.text = displayText

        if (artwork != null) {
            // 修复：确保封面填满，无白边
            artworkView.setImageBitmap(artwork)
            artworkView.setBackgroundColor(Color.TRANSPARENT)
            // 强制重新裁剪
            artworkView.invalidate()
        } else {
            artworkView.setImageDrawable(null)
            artworkView.setBackgroundColor(Color.argb(30, 255, 255, 255))
        }

        val icon = if (playing) pauseIcon else playIcon
        playPauseBtn.setIcon(icon ?: FallbackPlayIcon(if (playing) 1 else 0))

        invalidate()
    }

    fun updateProgress(fraction: Float) {
        progressFraction = fraction.coerceIn(0f, 1f)
        invalidate()
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

    /**
     * 设置药丸宽度（用户可调）。
     */
    fun setWidthLimits(minDp: Int, maxDp: Int) {
        // 固定宽度，此方法现在只用于兼容性
    }
}

// ═══════════════════════════════════════════════════════
//  精致圆形按钮
// ═══════════════════════════════════════════════════════

class PillButton(context: Context, private val iconRadius: Float = 0f) : View(context) {

    private var isPressedState = false
    private var currentIcon: Drawable? = null

    // 精致渐变背景
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 255, 255, 255)
    }
    private val bgPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 255, 255, 255)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        isFocusable = true
    }

    fun setIcon(drawable: Drawable) {
        currentIcon = drawable
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isPressedState = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                isPressedState = false
                invalidate()
                if (event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()) {
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressedState = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - dpf(2f)

        // 圆形背景（玻璃质感）
        canvas.drawCircle(cx, cy, r, if (isPressedState) bgPressedPaint else bgPaint)

        // 图标 — 居中绘制
        currentIcon?.let { drawable ->
            val size = (r * 1.2f).toInt()  // 图标占按钮的 60%
            val left = (width - size) / 2
            val top = (height - size) / 2
            drawable.setBounds(left, top, left + size, top + size)
            drawable.draw(canvas)
        }
    }

    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(size, size)
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
            1 -> { // Pause
                val barW = w * 0.22f
                val barH = h * 0.55f
                val gap = w * 0.12f
                val left = (w - barW * 2 - gap) / 2
                val top = (h - barH) / 2
                val r = barW / 2.5f
                canvas.drawRoundRect(RectF(left, top, left + barW, top + barH), r, r, paint)
                canvas.drawRoundRect(RectF(left + barW + gap, top, left + barW * 2 + gap, top + barH), r, r, paint)
            }
            else -> { // Play
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
