package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.ConnectionState
import com.example.model.TvDevice
import com.example.ui.theme.AccentBlue
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.StatusConnecting
import com.example.ui.theme.StatusDisconnected

@Composable
fun StatusPill(
    device: TvDevice?,
    connectionState: ConnectionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )

    val (dotColor, dotAlpha) = when (connectionState) {
        is ConnectionState.Connected -> Pair(AccentBlue, 1f)
        is ConnectionState.Connecting, is ConnectionState.PairingRequired -> Pair(StatusConnecting, pulseAlpha)
        is ConnectionState.Disconnected, is ConnectionState.Error -> Pair(StatusDisconnected, 1f)
    }

    val displayName = when {
        device != null -> device.name
        else -> "Choose TV"
    }

    val statusDesc = when (connectionState) {
        is ConnectionState.Connected -> "Connected to $displayName"
        is ConnectionState.Connecting -> "Connecting to $displayName"
        is ConnectionState.PairingRequired -> "Pairing with $displayName"
        is ConnectionState.Disconnected -> "Disconnected"
        is ConnectionState.Error -> "Connection error"
    }

    Box(
        modifier = modifier
            .height(44.dp)
            .shadow(
                elevation = if (!isDark) 4.dp else 0.dp,
                shape = CircleShape,
                ambientColor = Color.Black.copy(alpha = 0.05f),
                spotColor = Color.Black.copy(alpha = 0.08f)
            )
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .then(
                if (isDark) Modifier.border(1.dp, DarkBorder, CircleShape)
                else Modifier
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true),
                onClick = onClick
            )
            .padding(horizontal = 16.dp)
            .testTag("status_pill")
            .semantics {
                role = Role.Button
                contentDescription = "$statusDesc. Tap to open devices list."
            },
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor.copy(alpha = dotAlpha))
            )

            Spacer(modifier = Modifier.width(10.dp))

            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
