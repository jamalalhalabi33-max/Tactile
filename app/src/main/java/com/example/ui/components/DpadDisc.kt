package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.model.Key
import com.example.ui.theme.AccentBlue
import com.example.ui.theme.DarkBorder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.sqrt

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import com.example.ui.theme.LightDpadDot
import com.example.ui.theme.LightDpadOkCenter
import com.example.ui.theme.LightDpadOkSurface
import com.example.ui.theme.LightDpadRingBottom
import com.example.ui.theme.LightDpadRingTop
import com.example.ui.theme.LocalThemeIsDark

@Composable
fun DpadDisc(
    diameter: Dp,
    onSendKey: (Key) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = LocalThemeIsDark.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    // OK spring scale
    val okScale = remember { Animatable(1f) }

    // Dpad wedge visual highlight animatables: [UP, RIGHT, DOWN, LEFT]
    val upAlpha = remember { Animatable(0f) }
    val rightAlpha = remember { Animatable(0f) }
    val downAlpha = remember { Animatable(0f) }
    val leftAlpha = remember { Animatable(0f) }

    val upScale = remember { Animatable(1f) }
    val rightScale = remember { Animatable(1f) }
    val downScale = remember { Animatable(1f) }
    val leftScale = remember { Animatable(1f) }

    val okRadiusRatio = 0.38f / 2f // 19% radius = 38% diameter

    val outerRingBrush = if (!isDark) {
        Brush.verticalGradient(listOf(LightDpadRingTop, LightDpadRingBottom))
    } else {
        SolidColor(MaterialTheme.colorScheme.surface)
    }

    Box(
        modifier = modifier
            .size(diameter)
            .shadow(
                elevation = if (!isDark) 10.dp else 0.dp,
                shape = CircleShape,
                ambientColor = Color.Black.copy(alpha = 0.25f),
                spotColor = Color.Black.copy(alpha = 0.35f)
            )
            .clip(CircleShape)
            .background(outerRingBrush)
            .then(
                if (isDark) Modifier.border(1.dp, DarkBorder, CircleShape)
                else Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            )
            .testTag("dpad_disc")
            .semantics {
                contentDescription = "Directional pad disc"
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()

                    val centerX = size.width / 2f
                    val centerY = size.height / 2f
                    val dx = down.position.x - centerX
                    val dy = down.position.y - centerY
                    val dist = sqrt(dx * dx + dy * dy)
                    val radius = size.width / 2f
                    val okRadius = radius * 0.38f

                    val hitKey: Key
                    val isCenterOk = dist <= okRadius

                    if (isCenterOk) {
                        hitKey = Key.OK
                    } else {
                        // Angle in degrees (-180 to +180)
                        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                        hitKey = when {
                            angle in -45f..45f -> Key.RIGHT
                            angle > 45f && angle <= 135f -> Key.DOWN
                            angle < -45f && angle >= -135f -> Key.UP
                            else -> Key.LEFT
                        }
                    }

                    // 1. Immediate tactile and visual feedback on ACTION_DOWN
                    onSendKey(hitKey)

                    if (hitKey == Key.OK) {
                        TactileHaptics.okClick(view)
                        scope.launch {
                            okScale.animateTo(0.94f, spring(dampingRatio = 0.6f, stiffness = 800f))
                        }
                    } else {
                        TactileHaptics.dpadTick(view)
                        val (alphaAnim, scaleAnim) = when (hitKey) {
                            Key.UP -> Pair(upAlpha, upScale)
                            Key.RIGHT -> Pair(rightAlpha, rightScale)
                            Key.DOWN -> Pair(downAlpha, downScale)
                            Key.LEFT -> Pair(leftAlpha, leftScale)
                            else -> Pair(upAlpha, upScale)
                        }

                        scope.launch {
                            launch { alphaAnim.animateTo(0.20f, tween(80)) }
                            launch { scaleAnim.animateTo(0.90f, spring(dampingRatio = 0.6f, stiffness = 800f)) }
                        }
                    }

                    // 2. Repeat for D-pad after 350 ms, every 90 ms (OK does NOT repeat)
                    var repeatJob: Job? = null
                    if (hitKey != Key.OK) {
                        repeatJob = scope.launch {
                            delay(350)
                            while (isActive) {
                                onSendKey(hitKey)
                                TactileHaptics.dpadTick(view)
                                delay(90)
                            }
                        }
                    }

                    // 3. Track release
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })

                    repeatJob?.cancel()

                    // 4. Release animation
                    if (hitKey == Key.OK) {
                        scope.launch {
                            okScale.animateTo(
                                1f,
                                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
                            )
                        }
                    } else {
                        val (alphaAnim, scaleAnim) = when (hitKey) {
                            Key.UP -> Pair(upAlpha, upScale)
                            Key.RIGHT -> Pair(rightAlpha, rightScale)
                            Key.DOWN -> Pair(downAlpha, downScale)
                            Key.LEFT -> Pair(leftAlpha, leftScale)
                            else -> Pair(upAlpha, upScale)
                        }
                        scope.launch {
                            launch { alphaAnim.animateTo(0f, tween(240)) }
                            launch {
                                scaleAnim.animateTo(
                                    1f,
                                    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
                                )
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Draw the 4 wedge accent highlights without drawing wedge dividers
        Canvas(modifier = Modifier.fillMaxSize()) {
            val d = size.width
            val r = d / 2f
            val arcSize = Size(d, d)

            // Right: -45 deg for 90 deg
            if (rightAlpha.value > 0f) {
                drawArc(
                    color = AccentBlue.copy(alpha = rightAlpha.value),
                    startAngle = -45f,
                    sweepAngle = 90f,
                    useCenter = true,
                    size = arcSize,
                    topLeft = Offset.Zero
                )
            }
            // Down: 45 deg for 90 deg
            if (downAlpha.value > 0f) {
                drawArc(
                    color = AccentBlue.copy(alpha = downAlpha.value),
                    startAngle = 45f,
                    sweepAngle = 90f,
                    useCenter = true,
                    size = arcSize,
                    topLeft = Offset.Zero
                )
            }
            // Left: 135 deg for 90 deg
            if (leftAlpha.value > 0f) {
                drawArc(
                    color = AccentBlue.copy(alpha = leftAlpha.value),
                    startAngle = 135f,
                    sweepAngle = 90f,
                    useCenter = true,
                    size = arcSize,
                    topLeft = Offset.Zero
                )
            }
            // Up: 225 deg for 90 deg
            if (upAlpha.value > 0f) {
                drawArc(
                    color = AccentBlue.copy(alpha = upAlpha.value),
                    startAngle = 225f,
                    sweepAngle = 90f,
                    useCenter = true,
                    size = arcSize,
                    topLeft = Offset.Zero
                )
            }
        }

        // Up Directional Indicator (White dot in light mode, Chevron in dark mode)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .graphicsLayer {
                    translationY = if (!isDark) 30.dp.toPx() else 24.dp.toPx()
                    scaleX = upScale.value
                    scaleY = upScale.value
                }
        ) {
            if (!isDark) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(LightDpadDot)
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowUp,
                    contentDescription = "Up",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        // Down Directional Indicator (White dot in light mode, Chevron in dark mode)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .graphicsLayer {
                    translationY = if (!isDark) (-30).dp.toPx() else (-24).dp.toPx()
                    scaleX = downScale.value
                    scaleY = downScale.value
                }
        ) {
            if (!isDark) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(LightDpadDot)
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "Down",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        // Left Directional Indicator (White dot in light mode, Chevron in dark mode)
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .graphicsLayer {
                    translationX = if (!isDark) 30.dp.toPx() else 24.dp.toPx()
                    scaleX = leftScale.value
                    scaleY = leftScale.value
                }
        ) {
            if (!isDark) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(LightDpadDot)
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowLeft,
                    contentDescription = "Left",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        // Right Directional Indicator (White dot in light mode, Chevron in dark mode)
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .graphicsLayer {
                    translationX = if (!isDark) (-30).dp.toPx() else (-24).dp.toPx()
                    scaleX = rightScale.value
                    scaleY = rightScale.value
                }
        ) {
            if (!isDark) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(LightDpadDot)
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowRight,
                    contentDescription = "Right",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        // Center OK Disc (38% diameter)
        val okDiameter = diameter * 0.38f
        val okSurfaceBrush = if (!isDark) {
            Brush.radialGradient(
                listOf(LightDpadOkCenter, LightDpadOkSurface)
            )
        } else {
            SolidColor(AccentBlue)
        }

        Box(
            modifier = Modifier
                .size(okDiameter)
                .graphicsLayer {
                    scaleX = okScale.value
                    scaleY = okScale.value
                }
                .shadow(
                    elevation = if (!isDark) 6.dp else 0.dp,
                    shape = CircleShape,
                    ambientColor = if (!isDark) Color.Black.copy(alpha = 0.25f) else AccentBlue.copy(alpha = 0.2f),
                    spotColor = if (!isDark) Color.Black.copy(alpha = 0.35f) else AccentBlue.copy(alpha = 0.3f)
                )
                .clip(CircleShape)
                .background(okSurfaceBrush)
                .then(
                    if (!isDark) Modifier.border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
                    else Modifier
                )
                .testTag("key_ok")
                .semantics {
                    role = Role.Button
                    contentDescription = "OK center button"
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "OK",
                style = MaterialTheme.typography.headlineLarge,
                color = if (!isDark) Color(0xFFEAEAEA) else Color.White
            )
        }
    }
}
