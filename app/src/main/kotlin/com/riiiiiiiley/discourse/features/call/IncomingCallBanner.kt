package com.riiiiiiiley.discourse.features.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riiiiiiiley.discourse.app.AppState
import com.riiiiiiiley.discourse.core.RingtonePlayer
import com.riiiiiiiley.discourse.features.roomlist.RoomAvatarView
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import kotlinx.coroutines.delay

/** iOS system green (`.green`), the accept tint IncomingCallView uses. */
private val CallGreen = Color(0xFF34C759)

private val buttonSize = 44.dp // iOS phone metrics
private val buttonIconSize = 18.dp

/**
 * Ringing banner for an incoming call; floats over the main content while the
 * ringtone loops (port of iOS IncomingCallView). The host animates it in/out
 * (spring, move-from-top + fade — see the MainShell overlay).
 */
@Composable
fun IncomingCallBanner(
    call: AppState.RingingCall,
    accept: () -> Unit,
    decline: () -> Unit,
) {
    val colors = LocalDiscourseColors.current

    DisposableEffect(Unit) {
        RingtonePlayer.start()
        onDispose { RingtonePlayer.stop() }
    }
    // Give up ringing if nobody picks up.
    LaunchedEffect(Unit) {
        delay(45_000)
        decline()
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            // Spans the phone screen (12dp inset), capped like iPad.
            .padding(horizontal = 12.dp)
            .padding(top = 14.dp)
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .shadow(14.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            // Glass effect → elevated surface on Android.
            .background(colors.bgElevated2)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        RoomAvatarView(
            name = call.roomName,
            isDirect = call.isDirect,
            size = 44.dp,
            avatarUrl = call.avatarUrl,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                call.roomName,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Incoming call…",
                fontSize = 15.sp,
                color = colors.textSecondary,
            )
        }
        RoundCallButton(
            icon = Icons.Filled.CallEnd,
            background = CallRed,
            label = "Decline",
            action = decline,
        )
        RoundCallButton(
            icon = Icons.Filled.Call,
            background = CallGreen,
            label = "Accept",
            action = accept,
        )
    }
}

@Composable
private fun RoundCallButton(
    icon: ImageVector,
    background: Color,
    label: String,
    action: () -> Unit,
) {
    IconButton(
        onClick = action,
        modifier = Modifier
            .size(buttonSize)
            .clip(CircleShape)
            .background(background),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(buttonIconSize),
        )
    }
}
