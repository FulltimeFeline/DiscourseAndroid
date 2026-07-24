package com.riiiiiiiley.discourse.features.verification

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.PhonelinkRing
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riiiiiiiley.discourse.core.SessionScope
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Verify-session sheet: SAS emoji flow against another device, or recovery-key
 * entry. Presented as a modal bottom sheet (the iOS `.medium`/`.large` detent
 * sheet) either from the sidebar's "Verify this session" banner or for an
 * incoming request from another device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerificationSheet(
    scope: SessionScope,
    incoming: SessionScope.IncomingVerification? = null,
    onDismiss: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    val viewModel = remember { VerificationViewModel(scope.service) }
    val step by viewModel.step.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (incoming != null) {
            viewModel.beginIncomingVerification(senderId = incoming.senderId, flowId = incoming.flowId)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.dispose()
            // Hand the delegate back to the incoming-request watcher (a
            // fire-and-forget task, like the iOS onDisappear Task).
            CoroutineScope(Dispatchers.Main).launch { scope.watchForIncomingVerification() }
        }
    }

    /** Cancels an in-flight verification before dismissing. */
    fun cancelIfInFlight() {
        when (viewModel.step.value) {
            VerificationViewModel.Step.WaitingForOtherDevice,
            is VerificationViewModel.Step.ComparingEmojis,
            VerificationViewModel.Step.Confirming,
            -> viewModel.cancel()
            else -> Unit
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            cancelIfInFlight()
            onDismiss()
        },
        // Half-height first like the iOS .medium detent; drag up for .large.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = colors.bgElevated,
    ) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "Verify Session",
                        color = colors.textPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            cancelIfInFlight()
                            onDismiss()
                        },
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.bgApp,
                    titleContentColor = colors.textPrimary,
                    navigationIconContentColor = colors.textPrimary,
                    actionIconContentColor = colors.textPrimary,
                ),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 28.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                StepContent(viewModel = viewModel, step = step, onDismiss = onDismiss)
            }
        }
    }
}

@Composable
private fun StepContent(
    viewModel: VerificationViewModel,
    step: VerificationViewModel.Step,
    onDismiss: () -> Unit,
) {
    val colors = LocalDiscourseColors.current

    when (step) {
        VerificationViewModel.Step.Intro -> {
            StepHeader(
                title = "Verify This Session",
                icon = Icons.Filled.Security,
                subtitle = "Until this device is verified, your encrypted messages stay locked.",
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ProminentButton(onClick = viewModel::beginDeviceVerification) {
                    Icon(Icons.Filled.Smartphone, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Verify with Another Device", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                BorderedButton(onClick = viewModel::showRecoveryKeyEntry) {
                    Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Use Recovery Key", fontSize = 17.sp)
                }
            }
        }

        VerificationViewModel.Step.WaitingForOtherDevice -> {
            StepHeader(
                title = "Check Your Other Device",
                icon = Icons.Filled.PhonelinkRing,
                subtitle = "Accept the verification request on a device where you're already signed in.",
            )
            CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(28.dp))
        }

        is VerificationViewModel.Step.ComparingEmojis -> {
            StepHeader(
                title = "Compare Emojis",
                icon = Icons.Filled.Mood,
                subtitle = "Confirm the same emojis appear in the same order on your other device.",
            )
            EmojiGrid(step.emojis)
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ProminentButton(onClick = viewModel::emojisMatch) {
                    Text("They Match", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                BorderedButton(onClick = viewModel::emojisDontMatch, destructive = true) {
                    Text("They Don't Match", fontSize = 17.sp)
                }
            }
        }

        VerificationViewModel.Step.Confirming -> {
            StepHeader(
                title = "Confirming…",
                icon = Icons.Filled.HourglassEmpty,
                subtitle = "Waiting for your other device to confirm.",
            )
            CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(28.dp))
        }

        VerificationViewModel.Step.RecoveryKeyEntry -> {
            StepHeader(
                title = "Enter Recovery Key",
                icon = Icons.Filled.Key,
                subtitle = "The recovery key you saved when setting up encrypted backup (looks like EsT… groups of four).",
            )
            RecoveryEntry(viewModel)
        }

        VerificationViewModel.Step.Recovering -> {
            StepHeader(title = "Restoring Keys…", icon = Icons.Filled.Key)
            CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(28.dp))
        }

        VerificationViewModel.Step.Done -> {
            StepHeader(
                title = "Session Verified",
                icon = Icons.Filled.Verified,
                subtitle = "Encrypted messages will now decrypt on this device.",
            )
        }

        is VerificationViewModel.Step.Failed -> {
            StepHeader(
                title = "Verification Failed",
                icon = Icons.Filled.GppBad,
                subtitle = step.message,
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ProminentButton(onClick = viewModel::reset) {
                    Text("Try Again", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                BorderedButton(onClick = onDismiss) {
                    Text("Close", fontSize = 17.sp)
                }
            }
        }
    }
}

/** The 4-column SAS emoji grid: 32sp symbol over its caption. */
@Composable
private fun EmojiGrid(emojis: List<VerificationEmoji>) {
    val colors = LocalDiscourseColors.current
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        emojis.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { emoji ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(emoji.symbol, fontSize = 32.sp)
                        Text(
                            text = emoji.description,
                            fontSize = 12.sp,
                            color = colors.textSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun RecoveryEntry(viewModel: VerificationViewModel) {
    val colors = LocalDiscourseColors.current
    val recoveryKey by viewModel.recoveryKey.collectAsStateWithLifecycle()

    TextField(
        value = recoveryKey,
        onValueChange = viewModel::setRecoveryKey,
        placeholder = { Text("Recovery key", color = colors.textTertiary) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { viewModel.submitRecoveryKey() }),
        shape = RoundedCornerShape(10.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.bgInput,
            unfocusedContainerColor = colors.bgInput,
            disabledContainerColor = colors.bgInput,
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
            cursorColor = colors.accent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)),
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ProminentButton(
            onClick = viewModel::submitRecoveryKey,
            enabled = recoveryKey.trim().isNotEmpty(),
        ) {
            Text("Restore", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        TextButton(onClick = viewModel::reset) {
            Text("Back", fontSize = 15.sp, color = colors.accent)
        }
    }
}

/**
 * Step header: tinted glyph (40dp Material icon ≈ the iOS 36pt SF symbol,
 * which draws with less internal padding), title2-weight title, optional
 * centered callout subtitle.
 */
@Composable
private fun StepHeader(title: String, icon: ImageVector, subtitle: String? = null) {
    val colors = LocalDiscourseColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = title,
            color = colors.textPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                color = colors.textSecondary,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Full-width accent-filled button (iOS `.borderedProminent`, `.large`). */
@Composable
private fun ProminentButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.accent,
            contentColor = colors.textOnAccent,
            disabledContainerColor = colors.accent.copy(alpha = 0.4f),
            disabledContentColor = colors.textOnAccent.copy(alpha = 0.6f),
        ),
        modifier = Modifier.fillMaxWidth().height(50.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

/**
 * Full-width tinted-fill button (iOS `.bordered`, `.large`); red tint when
 * destructive.
 */
@Composable
private fun BorderedButton(
    onClick: () -> Unit,
    destructive: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    val tint = if (destructive) colors.unreadMention else colors.accent
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = tint.copy(alpha = 0.16f),
            contentColor = tint,
        ),
        modifier = Modifier.fillMaxWidth().height(50.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) { content() }
    }
}
