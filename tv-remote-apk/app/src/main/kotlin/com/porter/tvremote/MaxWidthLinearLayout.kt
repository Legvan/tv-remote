package com.porter.tvremote

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/**
 * LinearLayout that fills the width its parent offers, up to `android:maxWidth`.
 *
 * The status card and buttons are 560dp wide on a TV. A phone in landscape or split screen can
 * have less room than that, and a fixed width would push them off the edges (#8).
 */
class MaxWidthLinearLayout(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {

    /** In pixels; 0 when the layout sets no android:maxWidth. */
    private val maxWidth: Int

    init {
        val attributes = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.maxWidth))
        try {
            maxWidth = attributes.getDimensionPixelSize(0, 0)
        } finally {
            attributes.recycle()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val unbounded = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
        val capped = if (maxWidth > 0 && (unbounded || MeasureSpec.getSize(widthMeasureSpec) > maxWidth)) {
            MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.EXACTLY)
        } else {
            widthMeasureSpec
        }
        super.onMeasure(capped, heightMeasureSpec)
    }
}
