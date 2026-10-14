package com.biyahe.app

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView that never grows taller than [maxHeightPx]; beyond that it scrolls.
 * (android:maxHeight is ignored by RecyclerView, which is why the old suggestion
 * list could grow to fill the whole screen.)
 */
class MaxHeightRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    var maxHeightPx: Int = run {
        val metrics = resources.displayMetrics
        val byScreen = (metrics.heightPixels * 0.34f).toInt()
        val cap = (300 * metrics.density).toInt()
        minOf(byScreen, cap)
    }
        set(value) {
            field = value
            requestLayout()
        }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val mode = MeasureSpec.getMode(heightSpec)
        val size = MeasureSpec.getSize(heightSpec)
        val spec = when (mode) {
            MeasureSpec.EXACTLY -> heightSpec
            MeasureSpec.AT_MOST -> MeasureSpec.makeMeasureSpec(minOf(size, maxHeightPx), MeasureSpec.AT_MOST)
            else -> MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthSpec, spec)
    }
}