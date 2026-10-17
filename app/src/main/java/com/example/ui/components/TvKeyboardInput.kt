package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.remote.RemoteManager
import com.example.ui.theme.AccentBlue
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.LightBorder

@Composable
fun TvKeyboardInput(
    remoteManager: RemoteManager,
    modifier: Modifier = Modifier
) {
    val isDark = com.example.ui.theme.LocalThemeIsDark.current
    val session by remoteManager.keyboardSession.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var textState by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }

    LaunchedEffect(session.isActive, session.initialText) {
        if (session.isActive) {
            textState = session.initialText
            isSubmitting = false
            focusRequester.requestFocus()
            keyboardController?.show()
        } else {
            textState = ""
            isSubmitting = false
            keyboardController?.hide()
        }
    }

    fun submitAndClose() {
        if (isSubmitting) return
        isSubmitting = true
        remoteManager.sendEnter()
        remoteManager.setKeyboardActive(false)
        keyboardController?.hide()
    }

    AnimatedVisibility(
        visible = session.isActive,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = if (!isDark) 12.dp else 0.dp,
                        shape = RoundedCornerShape(24.dp),
                        ambientColor = Color.Black.copy(alpha = 0.1f),
                        spotColor = Color.Black.copy(alpha = 0.16f)
                    )
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .then(
                        if (isDark) Modifier.border(1.dp, DarkBorder, RoundedCornerShape(24.dp))
                        else Modifier.border(1.dp, LightBorder, RoundedCornerShape(24.dp))
                    )
                    .padding(16.dp)
                    .testTag("tv_keyboard_bar")
            ) {
                // Header with label and close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(AccentBlue.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Keyboard,
                                contentDescription = null,
                                tint = AccentBlue,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Text(
                            text = session.label,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    IconButton(
                        onClick = {
                            remoteManager.setKeyboardActive(false)
                            keyboardController?.hide()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close keyboard",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Text field receiving native keyboard input (Gboard, autocorrect, multiple languages)
                OutlinedTextField(
                    value = textState,
                    onValueChange = { newText ->
                        val oldText = textState
                        // Calculate diff to transmit accurately to TV
                        if (newText.startsWith(oldText)) {
                            // User appended characters (including spacebar)
                            val added = newText.substring(oldText.length)
                            remoteManager.sendText(added)
                        } else if (oldText.startsWith(newText)) {
                            // User backspaced characters
                            val deleteCount = oldText.length - newText.length
                            if (deleteCount > 0) {
                                remoteManager.sendBackspace(deleteCount)
                            }
                        } else {
                            // Replacement (autocorrect, paste, or mid-string edit)
                            val commonPrefix = commonPrefixLength(oldText, newText)
                            val deleteCount = oldText.length - commonPrefix
                            if (deleteCount > 0) {
                                remoteManager.sendBackspace(deleteCount)
                            }
                            val added = newText.substring(commonPrefix)
                            if (added.isNotEmpty()) {
                                remoteManager.sendText(added)
                            }
                        }
                        textState = newText
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown &&
                                (keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter)
                            ) {
                                submitAndClose()
                                true
                            } else {
                                false
                            }
                        }
                        .testTag("tv_native_text_field"),
                    placeholder = {
                        Text(
                            text = "Type on TV...",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = if (isDark) DarkBorder else LightBorder,
                        focusedContainerColor = MaterialTheme.colorScheme.background,
                        unfocusedContainerColor = MaterialTheme.colorScheme.background,
                        focusedTextColor = MaterialTheme.colorScheme.onBackground,
                        unfocusedTextColor = MaterialTheme.colorScheme.onBackground
                    ),
                    trailingIcon = {
                        IconButton(
                            onClick = { submitAndClose() }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Send,
                                contentDescription = "Submit to TV",
                                tint = AccentBlue,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                        autoCorrect = true
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { submitAndClose() },
                        onSearch = { submitAndClose() },
                        onGo = { submitAndClose() },
                        onSend = { submitAndClose() }
                    )
                )
            }
        }
    }
}

private fun commonPrefixLength(a: String, b: String): Int {
    val minLen = minOf(a.length, b.length)
    var i = 0
    while (i < minLen && a[i] == b[i]) {
        i++
    }
    return i
}
