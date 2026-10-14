package com.example.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.discovery.TvDiscoveryManager
import com.example.model.Key
import com.example.remote.RemoteManager
import com.example.ui.components.DevicesSheet
import com.example.ui.components.DpadDisc
import com.example.ui.components.StatusPill
import com.example.ui.components.UpperBlock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TactileScreen(
    remoteManager: RemoteManager,
    discoveryManager: TvDiscoveryManager,
    modifier: Modifier = Modifier
) {
    val currentDevice by remoteManager.currentDevice.collectAsState()
    val connectionState by remoteManager.connectionState.collectAsState()
    val savedDevices by remoteManager.storage.savedDevicesFlow.collectAsState(initial = emptyList())

    var showDevicesSheet by remember { mutableStateOf(false) }

    // Auto-open devices sheet if no TV saved after initial check
    LaunchedEffect(savedDevices) {
        delay(400)
        if (savedDevices.isEmpty() && currentDevice == null) {
            showDevicesSheet = true
        }
    }

    // Launch cosmetic intro (<300 ms lift 12dp + fade with 30 ms stagger, never delaying input)
    val pillAlpha = remember { Animatable(0f) }
    val pillY = remember { Animatable(12f) }
    val upperAlpha = remember { Animatable(0f) }
    val upperY = remember { Animatable(12f) }
    val dpadAlpha = remember { Animatable(0f) }
    val dpadY = remember { Animatable(12f) }

    LaunchedEffect(Unit) {
        launch {
            pillAlpha.animateTo(1f, tween(200))
        }
        launch {
            pillY.animateTo(0f, tween(200))
        }

        delay(30)
        launch {
            upperAlpha.animateTo(1f, tween(200))
        }
        launch {
            upperY.animateTo(0f, tween(200))
        }

        delay(30)
        launch {
            dpadAlpha.animateTo(1f, tween(200))
        }
        launch {
            dpadY.animateTo(0f, tween(200))
        }
    }

    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("tactile_root_screen")
    ) {
        val screenHeight = maxHeight
        val screenWidth = maxWidth

        // Calculate dynamic dimensions respecting small and large screens
        val availableHeight = screenHeight - statusBarPadding - 24.dp
        val upperBlockHeight = (availableHeight * 0.28f).coerceIn(160.dp, 240.dp)
        val dpadDiameter = (screenWidth - 32.dp).coerceAtMost(availableHeight * 0.54f).coerceAtLeast(240.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = statusBarPadding + 16.dp,
                    bottom = 20.dp,
                    start = 20.dp,
                    end = 20.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top: Status Pill (44dp, centered)
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        alpha = pillAlpha.value
                        translationY = pillY.value.dp.toPx()
                    }
            ) {
                StatusPill(
                    device = currentDevice,
                    connectionState = connectionState,
                    onClick = { showDevicesSheet = true }
                )
            }

            Spacer(modifier = Modifier.weight(0.12f))

            // Upper block (~28% of height): Back on left, Volume on right
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(upperBlockHeight)
                    .graphicsLayer {
                        alpha = upperAlpha.value
                        translationY = upperY.value.dp.toPx()
                    }
            ) {
                UpperBlock(
                    onSendKey = { key -> remoteManager.sendKey(key) },
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.weight(0.16f))

            // Lower block (~55%): D-pad disc in thumb zone
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

            Spacer(modifier = Modifier.weight(0.08f))
        }

        // Devices Sheet
        if (showDevicesSheet) {
            DevicesSheet(
                discoveryManager = discoveryManager,
                remoteManager = remoteManager,
                onDismiss = { showDevicesSheet = false }
            )
        }
    }
}
