package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.model.Key
import com.example.ui.theme.DarkBorder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

import com.example.ui.theme.LightButtonBase
import com.example.ui.theme.LightButtonIcon
import com.example.ui.theme.LocalThemeIsDark

@Composable
fun UpperBlock(
    onSendKey: (Key) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back Key on the Left (Giant key with 40dp radius)
        BackKey(
            onSendKey = onSendKey,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Volume Key on the Right: tall pill split into + (top 40%), mute (middle 20%), - (bottom 40%)
        VolumePill(
            onSendKey = onSendKey,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
    }
}

@Composable
private fun BackKey(
    onSendKey: (Key) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = LocalThemeIsDark.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }

    val shape = RoundedCornerShape(40.dp)
    val baseColor = if (!isDark) LightButtonBase else MaterialTheme.colorScheme.surface
    val iconColor = if (!isDark) LightButtonIcon else MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .shadow(
                elevation = if (!isDark) 6.dp else 0.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.2f),
                spotColor = Color.Black.copy(alpha = 0.25f)
            )
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(shape)
            .background(baseColor)
            .then(
                if (isDark) Modifier.border(1.dp, DarkBorder, shape)
                else Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            )
            .testTag("key_back")
            .semantics {
                role = Role.Button
                contentDescription = "Back button"
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()

                    // Send immediately on ACTION_DOWN
                    onSendKey(Key.BACK)
                    TactileHaptics.backDoubleTick(view, scope)

                    // Stiff spring to 0.96
                    scope.launch {
                        scale.animateTo(
                            targetValue = 0.96f,
                            animationSpec = spring(dampingRatio = 0.6f, stiffness = 800f)
                        )
                    }

                    // Wait until released
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })

                    // Soft overshoot release
                    scope.launch {
                        scale.animateTo(
                            targetValue = 1f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium
                            )
                        )
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.graphicsLayer {
                scaleX = 1.3f
                scaleY = 1.3f
            }
        )
    }
}

@Composable
private fun VolumePill(
    onSendKey: (Key) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = LocalThemeIsDark.current
    val shape = RoundedCornerShape(40.dp)
    val baseColor = if (!isDark) LightButtonBase else MaterialTheme.colorScheme.surface
    val iconColor = if (!isDark) LightButtonIcon else MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .shadow(
                elevation = if (!isDark) 6.dp else 0.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.2f),
                spotColor = Color.Black.copy(alpha = 0.25f)
            )
            .clip(shape)
            .background(baseColor)
            .then(
                if (isDark) Modifier.border(1.dp, DarkBorder, shape)
                else Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Volume Up (Top 40%)
            VolumeSection(
                key = Key.VOLUME_UP,
                onSendKey = onSendKey,
                isRepeatable = true,
                description = "Volume Up",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.40f)
                    .testTag("key_vol_up")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = iconColor
                )
            }

            // Mute (Middle 20%)
            VolumeSection(
                key = Key.MUTE,
                onSendKey = onSendKey,
                isRepeatable = false,
                isMute = true,
                description = "Mute",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.20f)
                    .testTag("key_mute")
            ) {
                Icon(
                    imageVector = Icons.Rounded.VolumeOff,
                    contentDescription = null,
                    tint = if (!isDark) iconColor.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            // Volume Down (Bottom 40%)
            VolumeSection(
                key = Key.VOLUME_DOWN,
                onSendKey = onSendKey,
                isRepeatable = true,
                description = "Volume Down",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.40f)
                    .testTag("key_vol_down")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Remove,
                    contentDescription = null,
                    tint = iconColor
                )
            }
        }
    }
}

@Composable
private fun VolumeSection(
    key: Key,
    onSendKey: (Key) -> Unit,
    isRepeatable: Boolean,
    description: String,
    modifier: Modifier = Modifier,
    isMute: Boolean = false,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .semantics {
                role = Role.Button
                contentDescription = description
            }
            .pointerInput(key) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()

                    // Send immediately on ACTION_DOWN
                    onSendKey(key)
                    if (isMute) {
                        TactileHaptics.muteHeavyClick(view)
                    } else {
                        TactileHaptics.volumeTick(view)
                    }

                    scope.launch {
                        scale.animateTo(
                            0.94f,
                            spring(dampingRatio = 0.6f, stiffness = 800f)
                        )
                    }

                    // Independent repeat job for this pointer
                    var repeatJob: Job? = null
                    if (isRepeatable) {
                        repeatJob = scope.launch {
                            delay(350) // Initial hold delay
                            while (isActive) {
                                onSendKey(key)
                                TactileHaptics.volumeTick(view)
                                delay(90) // Repeat interval
                            }
                        }
                    }

                    // Wait until released
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })

                    repeatJob?.cancel()

                    scope.launch {
                        scale.animateTo(
                            1f,
                            spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium
                            )
                        )
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
