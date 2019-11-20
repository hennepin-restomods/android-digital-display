package com.github.anastr.speedviewlib.components.Indicators

import android.content.Context
import android.graphics.Canvas

/**
 * this Library build By Anas Altair
 * see it on [GitHub](https://github.com/anastr/SpeedView)
 */
class NoIndicator(context: Context) : Indicator<NoIndicator>(context) {

    override val defaultIndicatorWidth: Float
        get() = 0f

    override fun draw(canvas: Canvas, degree: Float) {}

    override fun updateIndicator() {}

    override fun setWithEffects(withEffects: Boolean) {}
}
