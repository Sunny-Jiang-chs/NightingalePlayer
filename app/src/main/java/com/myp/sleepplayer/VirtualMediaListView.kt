package com.myp.sleepplayer

import android.content.Context
import android.graphics.Rect
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ScrollView

/**
 * Fixed-height directory rows inside the page's existing ScrollView.
 *
 * The group occupies the complete list height, but only the viewport and a bounded
 * buffer own Views. Removing an old row detaches its thumbnail binding and lets
 * Android discard the row's display list instead of retaining every visited row.
 */
internal class VirtualMediaListView(
    context: Context,
    private val itemCount: Int,
    private val rowHeightPx: Int,
    rowSpacingPx: Int,
    private val createRow: (Int) -> View
) : ViewGroup(context) {
    private val rowStridePx: Int
    private val contentHeightPx: Int
    private val mountedRows = SparseArray<View>()
    private val visibleRect = Rect()
    private val previewRect = Rect()
    private var observedTree: ViewTreeObserver? = null
    private var refreshPosted = false
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { scheduleRefresh() }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { scheduleRefresh() }
    private val refreshViewport = Runnable {
        refreshPosted = false
        if (isAttachedToWindow && updateMountedRows(inLayout = false)) requestLayout()
    }

    init {
        require(itemCount >= 0 && rowHeightPx > 0 && rowSpacingPx >= 0)
        val stride = rowHeightPx.toLong() + rowSpacingPx
        val contentHeight = stride * itemCount
        require(stride <= MEASURED_SIZE_MASK && contentHeight <= MEASURED_SIZE_MASK) {
            "Directory rows exceed Android's measured view height"
        }
        rowStridePx = stride.toInt()
        contentHeightPx = contentHeight.toInt()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            suggestedMinimumWidth
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }
        val desiredHeight = (contentHeightPx.toLong() + paddingTop + paddingBottom)
            .coerceAtMost(MEASURED_SIZE_MASK.toLong()).toInt()
        setMeasuredDimension(
            resolveSizeAndState(availableWidth, widthMeasureSpec, 0),
            resolveSizeAndState(maxOf(suggestedMinimumHeight, desiredHeight), heightMeasureSpec, 0)
        )
        measureRows()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // The first attached layout can mount rows immediately. Scroll changes
        // later use one animation callback for the whole group, not one per item.
        updateMountedRows(inLayout = true)
        measureRows()
        for (position in 0 until mountedRows.size()) {
            val index = mountedRows.keyAt(position)
            val row = mountedRows.valueAt(position)
            val rowTop = paddingTop + index * rowStridePx
            row.layout(paddingLeft, rowTop, width - paddingRight, rowTop + rowHeightPx)
        }
    }

    private fun measureRows() {
        val rowWidth = (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0)
        for (position in 0 until mountedRows.size()) {
            mountedRows.valueAt(position).measure(
                MeasureSpec.makeMeasureSpec(rowWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(rowHeightPx, MeasureSpec.EXACTLY)
            )
        }
    }

    private fun updateMountedRows(inLayout: Boolean): Boolean {
        val rows = previewWindow()?.rows(itemCount, rowStridePx, paddingTop) ?: IntRange.EMPTY
        // Mount the viewport plus one screen on each side, independent of item count.
        var changed = false
        for (position in mountedRows.size() - 1 downTo 0) {
            if (mountedRows.keyAt(position) !in rows) {
                val row = mountedRows.valueAt(position)
                mountedRows.removeAt(position)
                if (inLayout) removeViewInLayout(row) else removeView(row)
                changed = true
            }
        }
        for (index in rows) {
            if (mountedRows[index] != null) continue
            val row = createRow(index)
            mountedRows.put(index, row)
            val position = mountedRows.indexOfKey(index)
            val params = LayoutParams(LayoutParams.MATCH_PARENT, rowHeightPx)
            if (inLayout) {
                addViewInLayout(row, position, params, true)
            } else {
                addView(row, position, params)
            }
            changed = true
        }
        if (changed) invalidate()
        return changed
    }

    /** Use the actual scroll viewport, even when only a sliver of this group is visible. */
    private fun previewWindow(): ThumbnailPrefetchWindow? {
        if (itemCount == 0 || width <= 0 || height <= 0 || !isAttachedToWindow ||
            windowVisibility != VISIBLE || !isShown) return null
        var ancestor = parent
        while (ancestor != null && ancestor !is ScrollView) ancestor = ancestor.parent
        val scroll = ancestor as? ScrollView ?: return null
        if (!scroll.getLocalVisibleRect(visibleRect)) return null
        scroll.offsetRectIntoDescendantCoords(this, visibleRect)
        if (visibleRect.right <= 0 || visibleRect.left >= width) return null
        return ThumbnailPrefetchWindow(visibleRect.top, visibleRect.bottom)
    }

    fun previewDistance(view: View): Int? {
        val window = previewWindow() ?: return null
        previewRect.set(0, 0, view.width, view.height)
        offsetDescendantRectToMyCoords(view, previewRect)
        return window.distance(previewRect.top, previewRect.bottom)
    }

    private fun scheduleRefresh() {
        if (!isAttachedToWindow || refreshPosted) return
        refreshPosted = true
        postOnAnimation(refreshViewport)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observedTree = viewTreeObserver.also {
            it.addOnScrollChangedListener(scrollListener)
            it.addOnGlobalLayoutListener(layoutListener)
        }
        scheduleRefresh()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refreshViewport)
        refreshPosted = false
        observedTree?.takeIf { it.isAlive }?.let {
            it.removeOnScrollChangedListener(scrollListener)
            it.removeOnGlobalLayoutListener(layoutListener)
        }
        observedTree = null
        // ViewGroup detaches the bounded set of children itself. Keeping them
        // here also permits an ordinary window reattachment to reuse its rows.
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        scheduleRefresh()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // This callback also covers an ancestor directory panel being folded.
        scheduleRefresh()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        scheduleRefresh()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.collectionInfo = AccessibilityNodeInfo.CollectionInfo.obtain(itemCount, 1, false)
    }

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.MATCH_PARENT, rowHeightPx)
}
