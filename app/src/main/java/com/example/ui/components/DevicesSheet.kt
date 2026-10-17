package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.discovery.TvDiscoveryManager
import com.example.model.ConnectionState
import com.example.model.TvBrand
import com.example.model.TvDevice
import com.example.remote.RemoteManager
import com.example.ui.theme.AccentBlue
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkText
import com.example.ui.theme.LightButtonBase
import com.example.ui.theme.LightButtonIcon
import com.example.ui.theme.LightSurface
import com.example.ui.theme.LocalThemeIsDark
import com.example.ui.theme.StatusConnected
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DevicesSheet(
    visible: Boolean,
    discoveryManager: TvDiscoveryManager,
    remoteManager: RemoteManager,
    onDismiss: () -> Unit
) {
    if (visible) {
        BackHandler {
            onDismiss()
        }
    }

    val isDark = LocalThemeIsDark.current
    val scope = rememberCoroutineScope()

    val savedDevices by remoteManager.storage.savedDevicesFlow.collectAsState(initial = emptyList())
    val lastUsedId by remoteManager.storage.lastUsedDeviceIdFlow.collectAsState(initial = null)
    val currentDevice by remoteManager.currentDevice.collectAsState()
    val connectionState by remoteManager.connectionState.collectAsState()

    val discoveredDevices by discoveryManager.discoveredDevices.collectAsState()
    var isScanning by remember { mutableStateOf(true) }
    var scanTimeExpired by remember { mutableStateOf(false) }
    var showManualIpInput by remember { mutableStateOf(false) }
    var manualIpText by remember { mutableStateOf("") }
    var manualIpProbing by remember { mutableStateOf(false) }
    var manualIpError by remember { mutableStateOf<String?>(null) }

    // Track user-initiated device selection to only auto-dismiss on successful user action
    var userSelectedDeviceId by remember { mutableStateOf<String?>(null) }

    // Start scanning on open
    LaunchedEffect(visible) {
        if (visible) {
            isScanning = true
            scanTimeExpired = false
            discoveryManager.startScanning(scope)

            // 8-second empty state timer
            delay(8000)
            scanTimeExpired = true
            isScanning = false
        }
    }

    // Auto-dismiss ONLY if user initiated a connection and it succeeded
    LaunchedEffect(connectionState, userSelectedDeviceId) {
        if (userSelectedDeviceId != null && connectionState is ConnectionState.Connected) {
            delay(900) // Give user time to see the green connected checkmark confirmation
            onDismiss()
            userSelectedDeviceId = null
        }
    }

    // Merge saved and discovered devices, deduplicating and prioritizing IPv4
    val allDevices = remember(savedDevices, discoveredDevices, lastUsedId) {
        val result = mutableListOf<TvDevice>()
        val cleanSaved = savedDevices.filter { !it.ip.startsWith("fe80", ignoreCase = true) }
        val cleanDiscovered = discoveredDevices.filter { !it.ip.startsWith("fe80", ignoreCase = true) }

        // Last-used device pinned first if present
        val lastDevice = cleanSaved.firstOrNull { it.id == lastUsedId }
            ?: cleanDiscovered.firstOrNull { it.id == lastUsedId }
        if (lastDevice != null) {
            result.add(lastDevice)
        }

        fun norm(name: String) = name.lowercase()
            .replace("_androidtvremote2", "")
            .replace("._tcp", "")
            .replace(".local", "")
            .replace(Regex("[^a-z0-9]"), "")
            .trim()

        for (candidate in cleanSaved + cleanDiscovered) {
            val isIpv4 = !candidate.ip.contains(":")
            val candNorm = norm(candidate.name)

            // Deduplicate by IP
            val existingByIpIndex = result.indexOfFirst { it.ip.equals(candidate.ip, ignoreCase = true) }
            if (existingByIpIndex != -1) {
                val existing = result[existingByIpIndex]
                if ((existing.name == "Android TV" || existing.name == "LG webOS TV") &&
                    candidate.name != "Android TV" && candidate.name != "LG webOS TV"
                ) {
                    result[existingByIpIndex] = existing.copy(name = candidate.name)
                }
                continue
            }

            // Deduplicate by brand + name
            val existingByNameIndex = result.indexOfFirst { existing ->
                if (existing.brand != candidate.brand) return@indexOfFirst false
                val existNorm = norm(existing.name)
                if (existNorm.isEmpty() || candNorm.isEmpty()) return@indexOfFirst false
                existNorm == candNorm ||
                        (existNorm != "androidtv" && existNorm != "lgwebostv" &&
                                (existNorm.contains(candNorm) || candNorm.contains(existNorm)))
            }

            if (existingByNameIndex != -1) {
                val existing = result[existingByNameIndex]
                val existingIsIpv4 = !existing.ip.contains(":")
                // If existing was IPv6 and candidate is IPv4, upgrade to IPv4!
                if (!existingIsIpv4 && isIpv4) {
                    result[existingByNameIndex] = candidate
                }
            } else {
                result.add(candidate)
            }
        }

        result
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(240)),
        exit = fadeOut(tween(200))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.BottomCenter
        ) {
            // Main sheet container firmly anchored to screen bottom
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.80f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {} // Consume clicks inside the sheet
                    )
                    .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                    .background(if (isDark) DarkSurface else LightSurface)
                    .then(
                        if (isDark) Modifier.border(1.dp, DarkBorder, RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                        else Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                    )
                    .animateEnterExit(
                        enter = slideInVertically(
                            initialOffsetY = { it },
                            animationSpec = tween(280, easing = FastOutSlowInEasing)
                        ),
                        exit = slideOutVertically(
                            targetOffsetY = { it },
                            animationSpec = tween(220, easing = FastOutSlowInEasing)
                        )
                    )
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .testTag("devices_sheet")
                ) {
                    // Top Drag Indicator Handle
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = 12.dp)
                            .size(width = 40.dp, height = 4.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.25f))
                    )

                    // Header Row: Title, Scan Status, Rescan, and Close Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Devices",
                            style = MaterialTheme.typography.headlineLarge.copy(fontSize = 24.sp),
                            color = if (isDark) DarkText else LightButtonIcon
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isScanning) {
                                ScanningPulseIndicator()
                                Spacer(modifier = Modifier.width(8.dp))
                            }

                            TextButton(
                                onClick = {
                                    isScanning = true
                                    scanTimeExpired = false
                                    discoveryManager.startScanning(scope, clearPrevious = true)
                                    scope.launch {
                                        delay(8000)
                                        scanTimeExpired = true
                                        isScanning = false
                                    }
                                },
                                modifier = Modifier.testTag("rescan_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = "Scan again",
                                    tint = AccentBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isScanning) "Scanning..." else "Scan",
                                    color = AccentBlue,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.08f))
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Close",
                                    tint = if (isDark) DarkText.copy(alpha = 0.8f) else LightButtonIcon.copy(alpha = 0.8f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if (allDevices.isEmpty() && isScanning) {
                        // Skeleton Rows shaped like final rows
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            repeat(3) {
                                SkeletonRow(isDark = isDark)
                            }
                        }
                    } else if (allDevices.isEmpty() && scanTimeExpired) {
                        // Empty state after 8 seconds
                        EmptyDevicesState(
                            onRescan = {
                                isScanning = true
                                scanTimeExpired = false
                                discoveryManager.startScanning(scope, clearPrevious = true)
                                scope.launch {
                                    delay(8000)
                                    scanTimeExpired = true
                                    isScanning = false
                                }
                            },
                            onEnterIp = { showManualIpInput = true }
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(allDevices, key = { it.id }) { device ->
                                val isSelected = currentDevice?.id == device.id
                                val isSaved = savedDevices.any { it.id == device.id }
                                DeviceRow(
                                    device = device,
                                    isPinned = device.id == lastUsedId,
                                    isSaved = isSaved,
                                    isSelected = isSelected,
                                    connectionState = if (isSelected) connectionState else ConnectionState.Disconnected,
                                    onSelect = {
                                        userSelectedDeviceId = device.id
                                        remoteManager.selectDevice(device)
                                    },
                                    onForget = {
                                        scope.launch {
                                            remoteManager.storage.removeDevice(device.id)
                                            if (currentDevice?.id == device.id) {
                                                remoteManager.disconnect()
                                            }
                                        }
                                    },
                                    onSubmitPairing = { code -> remoteManager.submitPairingCode(code) }
                                )
                            }

                            item {
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(
                                    onClick = { showManualIpInput = !showManualIpInput },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("toggle_manual_ip")
                                ) {
                                    Text(
                                        text = if (showManualIpInput) "Hide manual IP" else "Enter IP manually",
                                        color = AccentBlue,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }

                    // Manual IP Entry Card
                    AnimatedVisibility(visible = showManualIpInput) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp, bottom = 12.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isDark) DarkBackground else LightButtonBase)
                                .then(
                                    if (isDark) Modifier.border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
                                    else Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                                )
                                .padding(16.dp)
                        ) {
                            Text(
                                text = "Connect by IP Address",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isDark) DarkText else LightButtonIcon
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Enter TV IP (e.g., 192.168.1.50). Tactile auto-detects LG or Android TV.",
                                style = MaterialTheme.typography.bodySmall,
                                color = (if (isDark) DarkText else LightButtonIcon).copy(alpha = 0.65f)
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = manualIpText,
                                onValueChange = { manualIpText = it; manualIpError = null },
                                placeholder = {
                                    Text(
                                        "192.168.1.xxx",
                                        color = (if (isDark) DarkText else LightButtonIcon).copy(alpha = 0.4f)
                                    )
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentBlue,
                                    unfocusedBorderColor = if (isDark) DarkBorder else Color.White.copy(alpha = 0.15f),
                                    focusedTextColor = if (isDark) DarkText else LightButtonIcon,
                                    unfocusedTextColor = if (isDark) DarkText else LightButtonIcon
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("manual_ip_input")
                            )

                            if (manualIpError != null) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = manualIpError ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Button(
                                onClick = {
                                    if (manualIpText.isNotBlank()) {
                                        manualIpProbing = true
                                        manualIpError = null
                                        scope.launch {
                                            val probed = discoveryManager.probeManualIp(manualIpText)
                                            manualIpProbing = false
                                            if (probed != null) {
                                                userSelectedDeviceId = probed.id
                                                remoteManager.selectDevice(probed)
                                            } else {
                                                manualIpError = "No supported TV responded at $manualIpText on ports 6466 or 3001/3000."
                                            }
                                        }
                                    }
                                },
                                enabled = !manualIpProbing && manualIpText.isNotBlank(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("manual_ip_connect_button")
                            ) {
                                Text(
                                    text = if (manualIpProbing) "Probing ports..." else "Connect",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceRow(
    device: TvDevice,
    isPinned: Boolean,
    isSaved: Boolean,
    isSelected: Boolean,
    connectionState: ConnectionState,
    onSelect: () -> Unit,
    onForget: () -> Unit,
    onSubmitPairing: (String) -> Unit
) {
    val isDark = LocalThemeIsDark.current
    val isSuccess = isSelected && connectionState is ConnectionState.Connected
    val isPairing = isSelected && connectionState is ConnectionState.PairingRequired
    val isError = isSelected && connectionState is ConnectionState.Error

    val shakeOffset = remember { Animatable(0f) }
    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Error) {
            shakeOffset.animateTo(10f, tween(50))
            shakeOffset.animateTo(-10f, tween(50))
            shakeOffset.animateTo(10f, tween(50))
            shakeOffset.animateTo(0f, tween(50))
        }
    }

    val rowBg = if (isSelected) AccentBlue.copy(alpha = 0.12f)
    else if (isDark) DarkSurface.copy(alpha = 0.85f)
    else LightButtonBase

    val textColor = if (isDark) DarkText else LightButtonIcon

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = shakeOffset.value }
            .clip(RoundedCornerShape(18.dp))
            .background(rowBg)
            .then(
                if (isDark) Modifier.border(1.dp, DarkBorder, RoundedCornerShape(18.dp))
                else Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
            )
            .combinedClickable(
                onClick = onSelect,
                onLongClick = onForget
            )
            .padding(14.dp)
            .testTag("device_row_${device.ip}")
            .semantics {
                role = Role.Button
                contentDescription = "${device.name}, ${device.ip}, ${if (isSelected) "Selected" else ""}. Long press to forget."
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Brand Tile
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (device.brand == TvBrand.LG_WEBOS) Color(0xFFA50034)
                        else AccentBlue
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (device.brand == TvBrand.LG_WEBOS) "LG" else "TV",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isPinned) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Last used",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                            color = AccentBlue
                        )
                    } else if (isSaved) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Saved",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                            color = textColor.copy(alpha = 0.55f)
                        )
                    } else {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Discovered",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                            color = Color(0xFF34C759)
                        )
                    }
                }

                Text(
                    text = "${device.ip} • ${if (device.brand == TvBrand.LG_WEBOS) "webOS" else "Google / Android TV"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor.copy(alpha = 0.65f)
                )
            }

            // Status morph / Checkmark
            if (isSuccess) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(StatusConnected),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Connected",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else if (isSelected && connectionState is ConnectionState.Connecting) {
                ScanningPulseIndicator()
            }
        }

        // Inline Pairing Area - STAYS VISIBLE!
        if (isPairing) {
            val pairingState = connectionState as ConnectionState.PairingRequired
            Spacer(modifier = Modifier.height(14.dp))

            if (pairingState.brand == TvBrand.LG_WEBOS) {
                // LG WebOS prompt: "Accept on your TV"
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AccentBlue.copy(alpha = 0.15f))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Accept prompt on your TV screen",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = AccentBlue
                    )
                }
            } else {
                // Android TV: Six large code boxes with auto-advance and auto-submit
                AndroidTvCodeBoxes(onSubmit = onSubmitPairing)
            }
        }

        // Inline error message in plain language
        if (isError) {
            val errorMsg = (connectionState as ConnectionState.Error).message
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMsg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun AndroidTvCodeBoxes(onSubmit: (String) -> Unit) {
    var codeText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    var hasSubmitted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val isDark = LocalThemeIsDark.current
    val boxBg = if (isDark) DarkBackground else Color(0xFF1E1E22)
    val charColor = if (isDark) DarkText else LightButtonIcon

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Enter 6-character code on your TV screen",
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = charColor.copy(alpha = 0.8f)
        )
        Spacer(modifier = Modifier.height(10.dp))

        // 6 Large Code Boxes
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    focusRequester.requestFocus()
                },
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            for (i in 0 until 6) {
                val char = codeText.getOrNull(i)?.toString() ?: ""
                val isFocused = codeText.length == i || (codeText.length == 6 && i == 5)

                Box(
                    modifier = Modifier
                        .size(width = 46.dp, height = 54.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(boxBg)
                        .border(
                            width = if (isFocused) 2.dp else 1.dp,
                            color = if (isFocused) AccentBlue else Color.White.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = char,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold),
                        color = charColor
                    )
                }
            }
        }

        // Hidden input to capture keyboard typing
        BasicTextField(
            value = codeText,
            onValueChange = { newText ->
                val clean = newText.filter { it.isLetterOrDigit() }.take(6).uppercase()
                codeText = clean
                if (clean.length == 6 && !hasSubmitted) {
                    hasSubmitted = true
                    onSubmit(clean)
                }
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = {
                if (codeText.length == 6 && !hasSubmitted) {
                    hasSubmitted = true
                    onSubmit(codeText)
                }
            }),
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focusRequester)
                .testTag("atv_pin_hidden_input")
        )
    }
}

@Composable
private fun EmptyDevicesState(
    onRescan: () -> Unit,
    onEnterIp: () -> Unit
) {
    val isDark = LocalThemeIsDark.current
    val textColor = if (isDark) DarkText else LightButtonIcon

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.Tv,
            contentDescription = null,
            tint = textColor.copy(alpha = 0.4f),
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "No TVs found",
            style = MaterialTheme.typography.titleMedium,
            color = textColor
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier.fillMaxWidth(0.9f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            CheckItem(text = "Both devices are on the same Wi-Fi", textColor = textColor)
            CheckItem(text = "TV is powered on", textColor = textColor)
            CheckItem(text = "LG TV: Enable 'LG Connect Apps' in settings", textColor = textColor)
        }

        Spacer(modifier = Modifier.height(18.dp))

        Button(
            onClick = onRescan,
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("Scan again", color = Color.White)
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = onEnterIp) {
            Text("Enter IP", color = AccentBlue)
        }
    }
}

@Composable
private fun CheckItem(text: String, textColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(AccentBlue)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = textColor.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun SkeletonRow(isDark: Boolean) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeletonAlpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (isDark) DarkSurface else LightButtonBase
            )
            .then(
                if (isDark) Modifier.border(1.dp, DarkBorder, RoundedCornerShape(18.dp))
                else Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Gray.copy(alpha = alpha))
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .size(width = 140.dp, height = 14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Gray.copy(alpha = alpha))
            )
            Box(
                modifier = Modifier
                    .size(width = 80.dp, height = 10.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Gray.copy(alpha = alpha))
            )
        }
    }
}

@Composable
private fun ScanningPulseIndicator() {
    val transition = rememberInfiniteTransition(label = "pulseRing")
    val scale by transition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringScale"
    )
    val alpha by transition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringAlpha"
    )

    Box(
        modifier = Modifier
            .size(16.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .border(2.dp, AccentBlue.copy(alpha = alpha), CircleShape)
    )
}
