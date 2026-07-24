package com.riiiiiiiley.discourse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * Room/user avatar: the mxc image when available, otherwise deterministic
 * initials on a name-hashed gradient circle (port of iOS `RoomAvatarView`).
 *
 * Image loading attaches when MediaLoader lands (media phase) — the composable
 * already takes [avatarUrl] so call sites won't change. iOS behaves the same
 * way with a nil mediaLoader: initials only.
 */
@Composable
fun RoomAvatarView(
    name: String,
    isDirect: Boolean,
    size: Dp = 28.dp,
    avatarUrl: String? = null,
) {
    val background = avatarPaletteColor(name)
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    listOf(background, background.darkened(0.15f)),
                ),
            )
            // Decorative: the adjacent name carries the info. Otherwise TalkBack
            // reads the initials as a stray fragment.
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials(name),
            color = Color.White,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun initials(name: String): String {
    val cleaned = name.trim { it in "#@!+ " }
    val letters = cleaned.split(" ")
        .filter { it.isNotEmpty() }
        .take(2)
        .map { it.first() }
    if (letters.isEmpty()) return "?"
    return letters.joinToString("").uppercase()
}

/**
 * iOS system palette (blue, indigo, purple, pink, red, orange, teal, green),
 * indexed by the same 64-bit wrapping `hash * 31 + scalar` iOS uses so the
 * same name gets the same color on both platforms.
 */
private fun avatarPaletteColor(name: String): Color {
    val palette = listOf(
        Color(0xFF007AFF), Color(0xFF5856D6), Color(0xFFAF52DE), Color(0xFFFF2D55),
        Color(0xFFFF3B30), Color(0xFFFF9500), Color(0xFF30B0C7), Color(0xFF34C759),
    )
    var hash = 0L
    var i = 0
    while (i < name.length) {
        val codePoint = name.codePointAt(i)
        hash = hash * 31 + codePoint
        i += Character.charCount(codePoint)
    }
    return palette[(abs(hash) % palette.size).toInt()]
}

private fun Color.darkened(amount: Float): Color = Color(
    red = red * (1 - amount),
    green = green * (1 - amount),
    blue = blue * (1 - amount),
    alpha = alpha,
)
