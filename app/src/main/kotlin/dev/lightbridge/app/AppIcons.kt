// SPDX-License-Identifier: MIT
package dev.lightbridge.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The demo's icon set.
 *
 * Hand-authored vectors on a 24dp grid rather than `material-icons-extended`: the demo needs nine
 * icons, and drawing them keeps the app's dependency list down to Compose plus Youniversal while
 * matching the system's rounded, two-weight line style.
 */
public object AppIcons {

    private const val Viewport = 24f
    private val Ink = SolidColor(Color.Black)
    private const val Stroke = 2f

    private fun vector(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = Viewport.dp,
            defaultHeight = Viewport.dp,
            viewportWidth = Viewport,
            viewportHeight = Viewport,
        ).apply(block).build()

    private fun PathBuilder.circle(centerX: Float, centerY: Float, radius: Float) {
        moveTo(centerX - radius, centerY)
        arcTo(radius, radius, 0f, false, true, centerX + radius, centerY)
        arcTo(radius, radius, 0f, false, true, centerX - radius, centerY)
        close()
    }

    /** Stacked layers: surfaces and containers. */
    public val Layers: ImageVector by lazy {
        vector("Layers") {
            path(fill = Ink) {
                moveTo(12f, 2.2f)
                lineTo(21f, 6.6f)
                lineTo(12f, 11f)
                lineTo(3f, 6.6f)
                close()
            }
            path(fill = Ink) {
                moveTo(12f, 13f)
                lineTo(21f, 17.4f)
                lineTo(12f, 21.8f)
                lineTo(3f, 17.4f)
                close()
            }
        }
    }

    /** Four-point sparkle. */
    public val Overview: ImageVector by lazy {
        vector("Overview") {
            path(fill = Ink) {
                moveTo(12f, 2f)
                quadTo(13.2f, 10.8f, 22f, 12f)
                quadTo(13.2f, 13.2f, 12f, 22f)
                quadTo(10.8f, 13.2f, 2f, 12f)
                quadTo(10.8f, 10.8f, 12f, 2f)
                close()
            }
        }
    }

    /** A 2x2 grid of dots. */
    public val Components: ImageVector by lazy {
        vector("Components") {
            path(fill = Ink) {
                circle(7.4f, 7.4f, 3.1f)
                circle(16.6f, 7.4f, 3.1f)
                circle(7.4f, 16.6f, 3.1f)
                circle(16.6f, 16.6f, 3.1f)
            }
        }
    }

    /** A circle that is half filled: color and contrast. */
    public val Palette: ImageVector by lazy {
        vector("Palette") {
            path(fill = Ink, pathFillType = PathFillType.EvenOdd) {
                circle(12f, 12f, 9f)
                circle(12f, 12f, 6.8f)
            }
            path(fill = Ink) {
                moveTo(12f, 5.2f)
                arcTo(6.8f, 6.8f, 0f, false, true, 12f, 18.8f)
                close()
            }
        }
    }

    /** A serif-less T: the type scale. */
    public val Type: ImageVector by lazy {
        vector("Type") {
            path(fill = Ink) {
                moveTo(4f, 4.5f)
                lineTo(20f, 4.5f)
                lineTo(20f, 8.2f)
                lineTo(13.9f, 8.2f)
                lineTo(13.9f, 20f)
                lineTo(10.1f, 20f)
                lineTo(10.1f, 8.2f)
                lineTo(4f, 8.2f)
                close()
            }
        }
    }

    /** An i in a circle: about and license. */
    public val About: ImageVector by lazy {
        vector("About") {
            path(fill = Ink, pathFillType = PathFillType.EvenOdd) {
                circle(12f, 12f, 9.2f)
                circle(12f, 12f, 7.4f)
            }
            path(fill = Ink) {
                circle(12f, 7.6f, 1.25f)
            }
            path(fill = Ink) {
                moveTo(10.9f, 10.6f)
                lineTo(13.1f, 10.6f)
                lineTo(13.1f, 17.2f)
                lineTo(10.9f, 17.2f)
                close()
            }
        }
    }

    /** Tick. */
    public val Check: ImageVector by lazy {
        vector("Check") {
            path(
                stroke = Ink,
                strokeLineWidth = 2.4f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(5f, 12.6f)
                lineTo(9.8f, 17.4f)
                lineTo(19f, 7.4f)
            }
        }
    }

    /** Cross. */
    public val Close: ImageVector by lazy {
        vector("Close") {
            path(
                stroke = Ink,
                strokeLineWidth = Stroke,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(6.5f, 6.5f)
                lineTo(17.5f, 17.5f)
                moveTo(17.5f, 6.5f)
                lineTo(6.5f, 17.5f)
            }
        }
    }

    /** Three sliders: settings and preferences. */
    public val Sliders: ImageVector by lazy {
        vector("Sliders") {
            path(
                stroke = Ink,
                strokeLineWidth = Stroke,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(3.5f, 7f)
                lineTo(20.5f, 7f)
                moveTo(3.5f, 12f)
                lineTo(20.5f, 12f)
                moveTo(3.5f, 17f)
                lineTo(20.5f, 17f)
            }
            path(fill = Ink) {
                circle(9f, 7f, 2.6f)
                circle(15.5f, 12f, 2.6f)
                circle(7f, 17f, 2.6f)
            }
        }
    }

    /** Droplet: pick an accent seed. */
    public val Droplet: ImageVector by lazy {
        vector("Droplet") {
            path(fill = Ink) {
                moveTo(12f, 2.6f)
                curveTo(12f, 2.6f, 18.6f, 10.4f, 18.6f, 14.2f)
                arcTo(6.6f, 6.6f, 0f, false, false, 5.4f, 14.2f)
                curveTo(5.4f, 10.4f, 12f, 2.6f, 12f, 2.6f)
                close()
            }
        }
    }

    /** The Youniversal mark, matching the launcher icon. */
    public val Mark: ImageVector by lazy {
        vector("Mark") {
            path(
                stroke = Ink,
                strokeLineWidth = 2.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(6f, 4f)
                lineTo(12f, 12.2f)
                lineTo(18f, 4f)
                moveTo(12f, 12.2f)
                lineTo(12f, 20f)
            }
        }
    }
}
