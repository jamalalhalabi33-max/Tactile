package com.example.ui

import android.app.Activity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.discovery.TvDiscoveryManager
import com.example.model.ConnectionState
import com.example.model.Key
import com.example.remote.RemoteManager
import com.example.ui.components.DevicesSheet
import com.example.ui.components.DpadDisc
import com.example.ui.components.StatusPill
import com.example.ui.components.TvKeyboardInput
import com.example.ui.components.UpperBlock
import com.example.ui.theme.AccentBlue
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.LightBorder
import com.example.ui.theme.LightButtonBase
import com.example.ui.theme.LightButtonIcon
import com.example.ui.theme.LightGradientBottom
import com.example.ui.theme.LightGradientMiddle
import com.example.ui.theme.LightGradientTop
import com.example.ui.theme.LocalThemeIsDark
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.ui.graphics.Brush

@Composable
fun TactileScreen(
    remoteManager: RemoteManager,
    discoveryManager: TvDiscoveryManager,
    isDarkTheme: Boolean = true,
    onToggleTheme: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isDark = LocalThemeIsDark.current
    val context = LocalContext.current
    val currentDevice by remoteManager.currentDevice.collectAsState()
    val connectionState by remoteManager.connectionState.collectAsState()
    val savedDevices by remoteManager.storage.savedDevicesFlow.collectAsState(initial = emptyList())

    var showDevicesSheet by remember { mutableStateOf(false) }

    fun keepFullScreen() {
        (context as? Activity)?.window?.let { window ->
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Auto-open devices sheet if no TV saved after initial check
    LaunchedEffect(savedDevices) {
        delay(400)
        if (savedDevices.isEmpty() && currentDevice == null) {
            showDevicesSheet = true
            keepFullScreen()
        }
    }

    // Launch cosmetic intro (<300 ms lift 12dp + fade with 30 ms stagger, never delaying input)
    val pillAlpha = remember { Animatable(0f) }
    val pillY = remember { Animatable(12f) }
    val dpadAlpha = remember { Animatable(0f) }
    val dpadY = remember { Animatable(12f) }
    val lowerAlpha = remember { Animatable(0f) }
    val lowerY = remember { Animatable(12f) }

    LaunchedEffect(Unit) {
        launch {
            pillAlpha.animateTo(1f, tween(200))
        }
        launch {
            pillY.animateTo(0f, tween(200))
        }

        delay(30)
        launch {
            dpadAlpha.animateTo(1f, tween(200))
        }
        launch {
            dpadY.animateTo(0f, tween(200))
        }

        delay(30)
        launch {
            lowerAlpha.animateTo(1f, tween(200))
        }
        launch {
            lowerY.animateTo(0f, tween(200))
        }
    }

    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    val backgroundBrush = if (!isDark) {
        Brush.verticalGradient(
            listOf(LightGradientTop, LightGradientMiddle, LightGradientBottom)
        )
    } else {
        Brush.linearGradient(
            listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background)
        )
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundBrush)
            .testTag("tactile_root_screen")
    ) {
        val screenHeight = maxHeight
        val screenWidth = maxWidth

        // Calculate dynamic dimensions
        val availableHeight = screenHeight - statusBarPadding - 24.dp
        val lowerBlockHeight = (availableHeight * 0.28f).coerceIn(150.dp, 230.dp)
        val dpadDiameter = (screenWidth - 32.dp).coerceAtMost(availableHeight * 0.54f).coerceAtLeast(240.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = statusBarPadding + 28.dp,
                    bottom = 20.dp,
                    start = 20.dp,
                    end = 20.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Header: Minimal Theme Switch (top-left), Status Pill (center), Keyboard Button (top-right)
            val keyboardSession by remoteManager.keyboardSession.collectAsState()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = pillAlpha.value
                        translationY = pillY.value.dp.toPx()
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Top-left minimal theme switch button (not following system theme)
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .shadow(
                            elevation = if (!isDark) 4.dp else 0.dp,
                            shape = CircleShape,
                            ambientColor = Color.Black.copy(alpha = 0.05f),
                            spotColor = Color.Black.copy(alpha = 0.08f)
                        )
                        .clip(CircleShape)
                        .background(if (!isDark) LightButtonBase else MaterialTheme.colorScheme.surface)
                        .then(
                            if (isDark) Modifier.border(1.dp, DarkBorder, CircleShape)
                            else Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true),
                            onClick = onToggleTheme
                        )
                        .testTag("theme_toggle_button")
                        .semantics {
                            role = Role.Button
                            contentDescription = if (isDark) "Switch to light theme" else "Switch to dark theme"
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isDark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                        contentDescription = null,
                        tint = if (!isDark) LightButtonIcon else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Center: Status Pill
                StatusPill(
                    device = currentDevice,
                    connectionState = connectionState,
                    onClick = {
                        keepFullScreen()
                        showDevicesSheet = true
                    }
                )

                // Top-right: Manual Keyboard Fallback Button (or balanced spacer)
                if (connectionState is ConnectionState.Connected) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .shadow(
                                elevation = if (!isDark) 4.dp else 0.dp,
                                shape = CircleShape,
                                ambientColor = Color.Black.copy(alpha = 0.05f),
                                spotColor = Color.Black.copy(alpha = 0.08f)
                            )
                            .clip(CircleShape)
                            .background(if (!isDark) LightButtonBase else MaterialTheme.colorScheme.surface)
                            .then(
                                if (isDark) Modifier.border(1.dp, DarkBorder, CircleShape)
                                else Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(bounded = true),
                                onClick = {
                                    remoteManager.setKeyboardActive(!keyboardSession.isActive)
                                }
                            )
                            .testTag("manual_keyboard_fallback_button")
                            .semantics {
                                role = Role.Button
                                contentDescription = if (keyboardSession.isActive) "Hide keyboard" else "Show keyboard"
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Keyboard,
                            contentDescription = null,
                            tint = if (keyboardSession.isActive) AccentBlue else (if (!isDark) LightButtonIcon else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.size(42.dp))
                }
            }

            Spacer(modifier = Modifier.weight(0.10f))

            // D-pad Disc in TOP position (natural thumb placement)
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        alpha = dpadAlpha.value
                        translationY = dpadY.value.dp.toPx()
                    },
                contentAlignment = Alignment.Center
            ) {
                DpadDisc(
                    diameter = dpadDiameter,
                    onSendKey = { key -> remoteManager.sendKey(key) }
                )
            }

            Spacer(modifier = Modifier.weight(0.14f))

            // Back Key & Volume Pill in DOWN position
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(lowerBlockHeight)
                    .graphicsLayer {
                        alpha = lowerAlpha.value
                        translationY = lowerY.value.dp.toPx()
                    }
            ) {
                UpperBlock(
                    onSendKey = { key -> remoteManager.sendKey(key) },
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.weight(0.08f))
        }

        // Devices Sheet
        if (showDevicesSheet) {
            DevicesSheet(
                discoveryManager = discoveryManager,
                remoteManager = remoteManager,
                onDismiss = {
                    showDevicesSheet = false
                    keepFullScreen()
                }
            )
        }

        // Automatic & manual TV Keyboard Input Bar
        TvKeyboardInput(
            remoteManager = remoteManager,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
