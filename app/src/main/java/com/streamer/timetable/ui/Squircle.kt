package com.streamer.timetable.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.withSign

/**
 * A squircle, as a superellipse rather than a rounded rectangle.
 *
 * `RoundedCornerShape` joins straight edges to circular arcs, so curvature jumps at
 * the join -- the corners read as slightly pinched next to a true squircle. A
 * superellipse (|x|^n + |y|^n = 1) curves continuously, which is what Android's own
 * adaptive-icon mask and iOS icons use.
 *
 * [exponent] 4 is the usual squircle; higher tends toward a square, 2 gives an ellipse.
 */
class SquircleShape(private val exponent: Double = 4.0) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val halfWidth = size.width / 2.0
        val halfHeight = size.height / 2.0
        val power = 2.0 / exponent

        val path = Path()
        // Sampled rather than approximated with beziers: at icon sizes this is well
        // below one pixel of error and avoids fiddly control-point maths.
        for (step in 0..SEGMENTS) {
            val angle = 2.0 * Math.PI * step / SEGMENTS
            val x = halfWidth + halfWidth * abs(cos(angle)).pow(power).withSign(cos(angle))
            val y = halfHeight + halfHeight * abs(sin(angle)).pow(power).withSign(sin(angle))

            if (step == 0) path.moveTo(x.toFloat(), y.toFloat())
            else path.lineTo(x.toFloat(), y.toFloat())
        }
        path.close()

        return Outline.Generic(path)
    }

    private companion object {
        const val SEGMENTS = 120
    }
}
