package com.myp.sleepplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

/** Small static placeholders; never allocate a bitmap or enter the cover cache. */
internal class ThumbnailStatusDrawable(context: Context, failed: Boolean) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val label = context.getString(
        if (failed) R.string.thumbnail_failed else R.string.thumbnail_loading)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(if (failed) R.color.player_danger else R.color.player_text_muted)
        textSize = 11f * context.resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val icon = requireNotNull(context.getDrawable(
        if (failed) R.drawable.ic_thumbnail_failed else android.R.drawable.ic_media_play)).mutate().apply {
        setTint(paint.color)
    }
    private val iconSize = (24f * density).roundToInt()
    private val gap = 4f * density
    private val fontMetrics = paint.fontMetrics
    private val labelHeight = fontMetrics.descent - fontMetrics.ascent
    private val contentHeight = iconSize + gap + labelHeight

    override fun getIntrinsicWidth(): Int =
        maxOf(80f * density, paint.measureText(label) + 8f * density).roundToInt()

    override fun getIntrinsicHeight(): Int =
        maxOf(56f * density, contentHeight + 8f * density).roundToInt()

    override fun draw(canvas: Canvas) {
        val centerX = bounds.exactCenterX()
        val top = bounds.exactCenterY() - contentHeight / 2f
        val iconLeft = (centerX - iconSize / 2f).roundToInt()
        val iconTop = top.roundToInt()
        icon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
        icon.draw(canvas)
        canvas.drawText(label, centerX, top + iconSize + gap - fontMetrics.ascent, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        icon.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        icon.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
