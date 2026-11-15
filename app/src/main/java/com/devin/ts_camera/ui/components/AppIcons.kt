package com.devin.ts_camera.ui.components

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The few icons this app needs, defined locally.
 *
 * `material-icons-extended` pulls in ~40 MB of generated classes for the sake
 * of a handful of glyphs; these path definitions keep the APK small and the
 * build free of that dependency.
 */
object AppIcons {

    val FlashOn: ImageVector = materialIcon(name = "Filled.FlashOn") {
        materialPath {
            moveTo(7.0f, 2.0f)
            verticalLineToRelative(11.0f)
            horizontalLineToRelative(3.0f)
            verticalLineToRelative(9.0f)
            lineToRelative(7.0f, -12.0f)
            horizontalLineToRelative(-4.0f)
            lineToRelative(4.0f, -8.0f)
            close()
        }
    }

    val FlashOff: ImageVector = materialIcon(name = "Filled.FlashOff") {
        materialPath {
            moveTo(3.27f, 3.0f)
            lineTo(2.0f, 4.27f)
            lineToRelative(5.0f, 5.0f)
            verticalLineTo(13.0f)
            horizontalLineToRelative(3.0f)
            verticalLineToRelative(9.0f)
            lineToRelative(3.58f, -6.14f)
            lineTo(17.73f, 20.0f)
            lineTo(19.0f, 18.73f)
            close()
            moveTo(17.0f, 10.0f)
            horizontalLineToRelative(-4.0f)
            lineToRelative(4.0f, -8.0f)
            horizontalLineTo(7.0f)
            verticalLineToRelative(2.18f)
            lineToRelative(8.46f, 8.46f)
            close()
        }
    }

    val FlashAuto: ImageVector = materialIcon(name = "Filled.FlashAuto") {
        materialPath {
            moveTo(3.0f, 2.0f)
            verticalLineToRelative(12.0f)
            horizontalLineToRelative(3.0f)
            verticalLineToRelative(9.0f)
            lineToRelative(7.0f, -12.0f)
            horizontalLineTo(9.0f)
            lineToRelative(4.0f, -9.0f)
            close()
            moveTo(19.0f, 2.0f)
            horizontalLineToRelative(-2.0f)
            lineToRelative(-3.2f, 9.0f)
            horizontalLineToRelative(1.9f)
            lineToRelative(0.7f, -2.0f)
            horizontalLineToRelative(3.2f)
            lineToRelative(0.7f, 2.0f)
            horizontalLineToRelative(1.9f)
            close()
            moveTo(16.85f, 7.65f)
            lineTo(18.0f, 4.0f)
            lineToRelative(1.15f, 3.65f)
            close()
        }
    }

    val Cameraswitch: ImageVector = materialIcon(name = "Filled.Cameraswitch") {
        materialPath {
            moveTo(16.0f, 7.0f)
            horizontalLineToRelative(-1.0f)
            lineToRelative(-1.0f, -1.0f)
            horizontalLineToRelative(-4.0f)
            lineTo(9.0f, 7.0f)
            horizontalLineTo(8.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            verticalLineToRelative(6.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(8.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            verticalLineTo(9.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(12.0f, 15.0f)
            curveToRelative(-1.66f, 0.0f, -3.0f, -1.34f, -3.0f, -3.0f)
            reflectiveCurveToRelative(1.34f, -3.0f, 3.0f, -3.0f)
            reflectiveCurveToRelative(3.0f, 1.34f, 3.0f, 3.0f)
            reflectiveCurveToRelative(-1.34f, 3.0f, -3.0f, 3.0f)
            close()
        }
    }
}
