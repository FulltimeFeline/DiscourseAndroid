package com.riiiiiiiley.discourse.features.timeline

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SportsSoccer
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.WavingHand
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.riiiiiiiley.discourse.core.CustomEmojiStore
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import kotlinx.coroutines.launch

// Custom emote data shapes live on core CustomEmojiStore (Emote / Pack), the
// same types StickerPickerView and the composer autocomplete consume.

/**
 * Resolves an `mxc://` URL to a decoded bitmap (cached by the implementation).
 * Implemented by MediaLoader when its port lands; until then pickers show a
 * neutral placeholder tile.
 */
fun interface EmoteImageLoader {
    suspend fun load(mxcUrl: String): Bitmap?
}

/** Async emote/pack-avatar image with a placeholder tile (iOS EmoteImageView). */
@Composable
fun EmoteImage(
    url: String,
    size: Dp,
    loader: EmoteImageLoader?,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url, loader) {
        if (bitmap == null) bitmap = loader?.load(url)
    }
    val loaded = bitmap
    if (loaded != null) {
        Image(
            bitmap = loaded.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(size),
        )
    } else {
        Box(
            modifier
                .size(size)
                .background(
                    LocalDiscourseColors.current.bgElevated2,
                    RoundedCornerShape(4.dp),
                ),
        )
    }
}

/** Touch-down feedback; plain Compose clickables give none (iOS PressFeedbackStyle).
 *  Mirrors ComposerView's helper so the picker's cells press-scale identically. */
@Composable
private fun Modifier.pressFeedback(interactionSource: InteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, tween(100), label = "pressScale")
    val alpha by animateFloatAsState(if (pressed) 0.5f else 1f, tween(100), label = "pressAlpha")
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
        this.alpha = alpha
    }
}

// MARK: Catalog

data class EmojiCategory(val icon: ImageVector, val title: String, val emoji: List<String>)

/**
 * The unicode palette, category-for-category the same lists as the iOS picker
 * (EmojiPickerView.categories) so the two clients offer identical emoji.
 */
object EmojiCatalog {
    val categories: List<EmojiCategory> = listOf(
        EmojiCategory(
            icon = Icons.Outlined.EmojiEmotions, title = "Smileys",
            emoji = "😀 😃 😄 😁 😆 😅 😂 🤣 🥲 🥹 ☺️ 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😗 😙 😚 😋 😛 😝 😜 🤪 🤨 🧐 🤓 😎 🥸 🤩 🥳 😏 😒 😞 😔 😟 😕 🙁 ☹️ 😣 😖 😫 😩 🥺 😢 😭 😮‍💨 😤 😠 😡 🤬 🤯 😳 🥵 🥶 😱 😨 😰 😥 😓 🤗 🤔 🫣 🤭 🫢 🫡 🤫 🤥 😶 😶‍🌫️ 🫥 😐 🫤 😑 😬 🙄 😯 😦 😧 😮 😲 🥱 😴 🤤 😪 😵 😵‍💫 🫨 🤐 🥴 🤢 🤮 🤧 😷 🤒 🤕 🤑 🤠 😈 👿 👹 👺 🤡 💩 👻 💀 ☠️ 👽 👾 🤖 🎃 😺 😸 😹 😻 😼 😽 🙀 😿 😾 🙈 🙉 🙊".split(" "),
        ),
        EmojiCategory(
            icon = Icons.Outlined.WavingHand, title = "People & Body",
            emoji = "👋 🤚 🖐️ ✋ 🖖 🫱 🫲 🫳 🫴 🫷 🫸 👌 🤌 🤏 ✌️ 🤞 🫰 🤟 🤘 🤙 👈 👉 👆 🖕 👇 ☝️ 🫵 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 🫶 👐 🤲 🤝 🙏 ✍️ 💅 🤳 💪 🦾 🦿 🦵 🦶 👂 🦻 👃 🧠 🫀 🫁 🦷 🦴 👀 👁️ 👅 👄 🫦 💋".split(" "),
        ),
        EmojiCategory(
            icon = Icons.Outlined.Person, title = "People & Clothing",
            emoji = "👶 🧒 👦 👧 🧑 👱 👨 🧔 👩 🧓 👴 👵 🙍 🙎 🙅 🙆 💁 🙋 🧏 🙇 🤦 🤷 👮 🕵️ 💂 🥷 👷 🫅 🤴 👸 👳 👲 🧕 🤵 👰 🤰 🫃 🫄 🤱 👼 🎅 🤶 🦸 🦹 🧙 🧚 🧛 🧜 🧝 🧞 🧟 🧌 💆 💇 🚶 🧍 🧎 🏃 💃 🕺 🕴️ 👯 🧖 🧗 👭 👫 👬 💏 💑 👪 🗣️ 👤 👥 🫂 👣 🧳 🌂 ☂️ 🧵 🪡 🪢 🧶 👓 🕶️ 🥽 🥼 🦺 👔 👕 👖 🧣 🧤 🧥 🧦 👗 👘 🥻 🩱 🩲 🩳 👙 👚 👛 👜 👝 🎒 🩴 👞 👟 🥾 🥿 👠 👡 🩰 👢 👑 👒 🎩 🎓 🧢 🪖 ⛑️ 📿 💄 💍 💼".split(" "),
        ),
        EmojiCategory(
            icon = Icons.Outlined.Pets, title = "Animals & Nature",
            emoji = "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐻‍❄️ 🐨 🐯 🦁 🐮 🐷 🐽 🐸 🐵 🐔 🐧 🐦 🐦‍⬛ 🐤 🐣 🐥 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🪱 🐛 🦋 🐌 🐞 🐜 🪰 🪲 🪳 🦟 🦗 🕷️ 🕸️ 🦂 🐢 🐍 🦎 🦖 🦕 🐙 🦑 🪼 🦐 🦞 🦀 🐡 🐠 🐟 🐬 🐳 🐋 🦈 🦭 🐊 🐅 🐆 🦓 🦍 🦧 🦣 🐘 🦛 🦏 🐪 🐫 🦒 🦘 🦬 🐃 🐂 🐄 🐎 🐖 🐏 🐑 🦙 🐐 🦌 🫎 🐕 🐩 🦮 🐕‍🦺 🐈 🐈‍⬛ 🪶 🪽 🐓 🦃 🦤 🦚 🦜 🦢 🪿 🦩 🕊️ 🐇 🦝 🦨 🦡 🦫 🦦 🦥 🐁 🐀 🐿️ 🦔 🐾 🐉 🐲 🌵 🎄 🌲 🌳 🌴 🪵 🌱 🌿 ☘️ 🍀 🎍 🪴 🎋 🍃 🍂 🍁 🪹 🪺 🍄 🐚 🪸 🪨 🌾 💐 🌷 🪷 🌹 🥀 🌺 🌸 🪻 🌼 🌻 🌞 🌝 🌛 🌜 🌚 🌕 🌖 🌗 🌘 🌑 🌒 🌓 🌔 🌙 🌎 🌍 🌏 🪐 💫 ⭐ 🌟 ✨ ⚡ ☄️ 💥 🔥 🌪️ 🌈 ☀️ 🌤️ ⛅ 🌥️ ☁️ 🌦️ 🌧️ ⛈️ 🌩️ 🌨️ ❄️ ☃️ ⛄ 🌬️ 💨 💧 💦 🫧 ☔ 🌊 🌫️".split(" "),
        ),
        EmojiCategory(
            icon = Icons.Outlined.Restaurant, title = "Food & Drink",
            emoji = "🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶️ 🫑 🌽 🥕 🫒 🧄 🧅 🥔 🍠 🥐 🥯 🍞 🥖 🥨 🧀 🥚 🍳 🧈 🥞 🧇 🥓 🥩 🍗 🍖 🌭 🍔 🍟 🍕 🫓 🥪 🥙 🧆 🌮 🌯 🫔 🥗 🥘 🫕 🥫 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🦪 🍤 🍙 🍚 🍘 🍥 🥠 🥮 🍢 🍡 🍧 🍨 🍦 🥧 🧁 🍰 🎂 🍮 🍭 🍬 🍫 🍿 🍩 🍪 🥜 🌰 🫘 🍯 🥛 🍼 ☕ 🍵 🫖 🧃 🥤 🧋 🍶 🍺 🍻 🥂 🍷 🥃 🍸 🍹 🧉 🍾 🧊 🧂 🥣 🥡 🥢 🍽️ 🍴 🥄".split(" "),
        ),
        EmojiCategory(
            icon = Icons.Outlined.SportsSoccer, title = "Activity",
            emoji = "⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🪀 🏓 🏸 🏒 🏑 🥍 🏏 🪃 🥅 ⛳ 🪁 🏹 🎣 🤿 🥊 🥋 🎽 🛹 🛼 🛷 ⛸️ 🥌 🎿 ⛷️ 🏂 🪂 🏋️ 🤼 🤸 ⛹️ 🤺 🤾 🏌️ 🏇 🧘 🏄 🏊 🤽 🚣 🧗 🚵 🚴 🏆 🥇 🥈 🥉 🏅 🎖️ 🏵️ 🎗️ 🎫 🎟️ 🎪 🤹 🎭 🩰 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🪘 🎷 🎺 🪗 🎸 🪕 🎻 🎲 ♟️ 🎯 🎳 🎮 🎰 🧩".split(" "),
        ),
        EmojiCategory(
            icon = Icons.Outlined.DirectionsCar, title = "Travel & Places",
            emoji = "🚗 🚕 🚙 🚌 🚎 🏎️ 🚓 🚑 🚒 🚐 🛻 🚚 🚛 🚜 🦯 🦽 🦼 🛴 🚲 🛵 🏍️ 🛺 🛞 🚨 🚔 🚍 🚘 🚖 🚡 🚠 🚟 🚃 🚋 🚞 🚝 🚄 🚅 🚈 🚂 🚆 🚇 🚊 🚉 ✈️ 🛫 🛬 🛩️ 💺 🛰️ 🚀 🛸 🚁 🛶 ⛵ 🚤 🛥️ 🛳️ ⛴️ 🚢 🛟 ⚓ 🪝 ⛽ 🚧 🚦 🚥 🗺️ 🗿 🗽 🗼 🏰 🏯 🏟️ 🎡 🎢 🎠 ⛲ ⛱️ 🏖️ 🏝️ 🏜️ 🌋 ⛰️ 🏔️ 🗻 🏕️ ⛺ 🛖 🏠 🏡 🏘️ 🏚️ 🏗️ 🏭 🏢 🏬 🏣 🏤 🏥 🏦 🏨 🏪 🏫 🏩 💒 🏛️ ⛪ 🕌 🕍 🛕 🕋 ⛩️ 🏞️ 🌁 🌃 🏙️ 🌄 🌅 🌆 🌇 🌉 🎆 🎇 🌠 🗾".split(" "),
        ),
        EmojiCategory(
            icon = Icons.Outlined.Lightbulb, title = "Objects",
            emoji = "⌚ 📱 📲 💻 ⌨️ 🖥️ 🖨️ 🖱️ 🖲️ 🕹️ 🗜️ 💽 💾 💿 📀 📼 📷 📸 📹 🎥 📽️ 🎞️ 📞 ☎️ 📟 📠 📺 📻 🎙️ 🎚️ 🎛️ 🧭 ⏱️ ⏲️ ⏰ 🕰️ ⌛ ⏳ 📡 🔋 🪫 🔌 💡 🔦 🕯️ 🪔 🧯 🛢️ 💸 💵 💴 💶 💷 🪙 💰 💳 💎 ⚖️ 🪜 🧰 🪛 🔧 🔨 ⚒️ 🛠️ ⛏️ 🪚 🔩 ⚙️ 🪤 🧱 ⛓️ 🧲 🔫 💣 🧨 🪓 🔪 🗡️ ⚔️ 🛡️ 🚬 ⚰️ 🪦 ⚱️ 🏺 🔮 📿 🧿 🪬 💈 ⚗️ 🔭 🔬 🕳️ 🩹 🩺 💊 💉 🩸 🧬 🦠 🧫 🧪 🌡️ 🧹 🪠 🧺 🧻 🚽 🚰 🚿 🛁 🛀 🧼 🪥 🪒 🧽 🪣 🧴 🛎️ 🔑 🗝️ 🚪 🪑 🛋️ 🛏️ 🛌 🧸 🪆 🖼️ 🪞 🪟 🛍️ 🛒 🎁 🎈 🎏 🎀 🪄 🪅 🎊 🎉 🪩 🎎 🏮 🎐 🧧 ✉️ 📩 📨 📧 💌 📥 📤 📦 🏷️ 🪧 📪 📫 📬 📭 📮 📯 📜 📃 📄 📑 🧾 📊 📈 📉 🗒️ 🗓️ 📆 📅 🗑️ 📇 🗃️ 🗳️ 🗄️ 📋 📁 📂 🗂️ 🗞️ 📰 📓 📔 📒 📕 📗 📘 📙 📚 📖 🔖 🧷 🔗 📎 🖇️ 📐 📏 🧮 📌 📍 ✂️ 🖊️ 🖋️ ✒️ 🖌️ 🖍️ 📝 ✏️ 🔍 🔎 🔏 🔐 🔒 🔓".split(" "),
        ),
        EmojiCategory(
            icon = Icons.Outlined.Tag, title = "Symbols",
            emoji = "❤️ 🩷 🧡 💛 💚 💙 🩵 💜 🖤 🩶 🤍 🤎 💔 ❤️‍🔥 ❤️‍🩹 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 💯 💢 💥 💫 💦 💨 🕳️ 💬 🗨️ 🗯️ 💭 💤 ♠️ ♥️ ♦️ ♣️ 🃏 🀄 🎴 🔇 🔈 🔉 🔊 📢 📣 📯 🔔 🔕 🎵 🎶 💹 ☮️ ✝️ ☪️ 🕉️ ☸️ ✡️ 🔯 🕎 ☯️ ☦️ 🛐 ⛎ ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ ❌ ⭕ ❗ ❓ ❕ ❔ ‼️ ⁉️ 💱 💲 ⚕️ ♻️ ⚜️ 🔱 📛 🔰 ✅ ☑️ ✔️ ✖️ ➕ ➖ ➗ ➰ ➿ 〽️ ✳️ ✴️ ❇️ ©️ ®️ ™️ 🔟 🔢 🔣 🔤 🅰️ 🆎 🅱️ 🆑 🆒 🆓 ℹ️ 🆔 Ⓜ️ 🆕 🆖 🅾️ 🆗 🅿️ 🆘 🆙 🆚 ⚠️ 🚸 ⛔ 🚫 🚳 🚭 🚯 🚱 🚷 📵 🔞 ☢️ ☣️ ⬆️ ↗️ ➡️ ↘️ ⬇️ ↙️ ⬅️ ↖️ ↕️ ↔️ ↩️ ↪️ ⤴️ ⤵️ 🔃 🔄 🔙 🔚 🔛 🔜 🔝 🔀 🔁 🔂 ▶️ ⏩ ◀️ ⏪ 🔼 ⏫ 🔽 ⏬ ⏸️ ⏹️ ⏺️ ⏏️ 🎦 🔅 🔆 📶 📳 📴".split(" "),
        ),
        EmojiCategory(
            icon = Icons.Outlined.Flag, title = "Flags",
            emoji = "🏁 🚩 🎌 🏴 🏳️ 🏳️‍🌈 🏳️‍⚧️ 🏴‍☠️ 🇦🇷 🇦🇺 🇦🇹 🇧🇪 🇧🇷 🇨🇦 🇨🇱 🇨🇳 🇨🇴 🇨🇺 🇨🇿 🇩🇰 🇩🇴 🇪🇨 🇪🇬 🇫🇮 🇫🇷 🇩🇪 🇬🇷 🇬🇹 🇭🇳 🇭🇰 🇭🇺 🇮🇸 🇮🇳 🇮🇩 🇮🇪 🇮🇱 🇮🇹 🇯🇵 🇰🇷 🇲🇽 🇳🇱 🇳🇿 🇳🇴 🇵🇦 🇵🇪 🇵🇭 🇵🇱 🇵🇹 🇵🇷 🇷🇴 🇷🇺 🇸🇦 🇸🇬 🇿🇦 🇪🇸 🇸🇪 🇨🇭 🇹🇼 🇹🇭 🇹🇷 🇺🇦 🇦🇪 🇬🇧 🇺🇸 🇺🇾 🇻🇪 🇻🇳".split(" "),
        ),
    )

    /**
     * Unicode names ("grinning face") for search, built once. ZWJ sequences
     * flatten to component names, fine for contains-matching.
     */
    val searchIndex: List<Pair<String, String>> by lazy {
        val seen = mutableSetOf<String>()
        categories.flatMap { it.emoji }.mapNotNull { emoji ->
            if (!seen.add(emoji)) return@mapNotNull null
            emoji to (unicodeName(emoji)?.lowercase() ?: "")
        }
    }

    /**
     * Component code-point names joined with spaces (the Character.getName
     * analogue of iOS's `.toUnicodeName` transform). Null when every scalar is
     * unassigned on this device's ICU.
     */
    internal fun unicodeName(emoji: String): String? {
        // (map + filterNotNull: stdlib has no mapNotNull for primitive arrays.)
        val parts = emoji.codePoints().toArray().map { Character.getName(it) }.filterNotNull()
        return if (parts.isEmpty()) null else parts.joinToString(" ")
    }
}

/**
 * Unicode emoji addressable by `:shortcode:` tokens derived from their
 * Unicode names ("PLEADING FACE" → `pleading_face`). Backs the composer's
 * `:token:` autocomplete and closing-colon auto-replace.
 */
object EmojiShortcodes {
    val entries: List<Pair<String, String>> by lazy {
        val seen = mutableSetOf<String>()
        EmojiCatalog.categories.flatMap { it.emoji }.mapNotNull { emoji ->
            if (!seen.add(emoji)) return@mapNotNull null
            val name = EmojiCatalog.unicodeName(emoji) ?: return@mapNotNull null
            val cleaned = name
                // ZWJ/variation plumbing is noise in a shortcode.
                .replace("VARIATION SELECTOR-16", " ")
                .replace("ZERO WIDTH JOINER", " ")
                .lowercase()
            val shortcode = cleaned
                .split(' ', '-')
                .filter { it.isNotEmpty() }
                .joinToString("_")
            if (shortcode.isEmpty()) return@mapNotNull null
            emoji to shortcode
        }
    }

    /** shortcode → emoji, first-wins. */
    val byShortcode: Map<String, String> by lazy {
        val map = mutableMapOf<String, String>()
        for ((emoji, shortcode) in entries) map.getOrPut(shortcode) { emoji }
        map
    }

    /** Prefix matches first, then contains — the composer's suggestion feed. */
    fun matches(needle: String, limit: Int): List<Pair<String, String>> {
        if (needle.isEmpty()) return emptyList()
        val prefix = mutableListOf<Pair<String, String>>()
        val contains = mutableListOf<Pair<String, String>>()
        for (entry in entries) {
            if (entry.second.startsWith(needle)) {
                prefix.add(entry)
                if (prefix.size == limit) break
            } else if (contains.size < limit && entry.second.contains(needle)) {
                contains.add(entry)
            }
        }
        return (prefix + contains).take(limit)
    }
}

// MARK: Picker

/** One cell of the flattened section list backing the picker grid. */
private sealed class PickerCell {
    data class Header(val sectionId: Int, val title: String) : PickerCell()
    data class Unicode(val emoji: String) : PickerCell()
    data class Emote(val emote: CustomEmojiStore.Emote) : PickerCell()
}

private val gridCellSize = 40.dp
private val emojiFontSize = 29.sp
private val barItemHeight = 32.dp

/**
 * Compact emoji picker; custom emoji packs (MSC2545) render above the unicode
 * categories, and the bottom category bar follows the scroll position.
 */
@Composable
fun EmojiPickerView(
    /** Custom emoji packs (MSC2545), shown above the unicode categories. */
    customPacks: List<CustomEmojiStore.Pack> = emptyList(),
    loader: EmoteImageLoader? = null,
    /** Present when the surface supports custom emoji (composer, reactions). */
    insertCustom: ((CustomEmojiStore.Emote) -> Unit)? = null,
    /**
     * Reports search-field focus so the expression panel can coexist with the
     * keyboard instead of being dismissed by it.
     */
    onSearchFocusChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
    insert: (String) -> Unit,
) {
    val colors = LocalDiscourseColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Same store + key as iOS @AppStorage("recentEmoji"): space-separated,
    // most recent first, capped at 24.
    val recentStore = remember(context) {
        context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    }
    var recentStorage by remember { mutableStateOf(recentStore.getString("recentEmoji", "") ?: "") }
    val recents = remember(recentStorage) { recentStorage.split(" ").filter { it.isNotEmpty() } }
    fun rememberRecent(emoji: String) {
        val list = listOf(emoji) + recents.filter { it != emoji }
        recentStorage = list.take(24).joinToString(" ")
        recentStore.edit { putString("recentEmoji", recentStorage) }
    }

    var query by remember { mutableStateOf("") }
    val trimmedQuery = query.trim().lowercase()

    // Packs shown: only when the surface can insert them, and only their
    // emoticon images. Sticker-only tokens don't convert on send, so offering
    // them here would produce literal `:text:`.
    val shownPacks = remember(customPacks, insertCustom == null) {
        if (insertCustom == null) emptyList() else customPacks.filter { it.emoticons.isNotEmpty() }
    }

    val searchResults = remember(trimmedQuery) {
        if (trimmedQuery.isEmpty()) emptyList()
        else EmojiCatalog.searchIndex.filter { it.second.contains(trimmedQuery) }.map { it.first }
    }
    val customSearchResults = remember(trimmedQuery, shownPacks) {
        // Colons are how users type shortcodes; strip them for matching.
        val needle = trimmedQuery.trim(':')
        if (needle.isEmpty()) emptyList()
        else {
            val seen = mutableSetOf<String>()
            shownPacks.flatMap { it.emoticons }.filter { emote ->
                (emote.shortcode.lowercase().contains(needle)
                    || emote.body.lowercase().contains(needle))
                    && seen.add(emote.shortcode)
            }
        }
    }

    // Every category as a titled section in one flat cell list; the bottom
    // bar jumps between the header indices.
    val cells = remember(trimmedQuery, recents, shownPacks, searchResults, customSearchResults) {
        buildList {
            if (trimmedQuery.isEmpty()) {
                if (recents.isNotEmpty()) {
                    add(PickerCell.Header(-1, "Frequently Used"))
                    recents.forEach { add(PickerCell.Unicode(it)) }
                }
                shownPacks.forEachIndexed { index, pack ->
                    add(PickerCell.Header(100 + index, pack.displayName))
                    pack.emoticons.forEach { add(PickerCell.Emote(it)) }
                }
                EmojiCatalog.categories.forEachIndexed { index, category ->
                    add(PickerCell.Header(index, category.title))
                    category.emoji.forEach { add(PickerCell.Unicode(it)) }
                }
            } else {
                customSearchResults.forEach { add(PickerCell.Emote(it)) }
                searchResults.forEach { add(PickerCell.Unicode(it)) }
            }
        }
    }
    val headerIndexBySection = remember(cells) {
        buildMap {
            cells.forEachIndexed { index, cell ->
                if (cell is PickerCell.Header) put(cell.sectionId, index)
            }
        }
    }
    // Section of each cell, so the bar highlight can follow the scroll without
    // per-frame layout queries (the iOS HeaderPositionBox analogue).
    val sectionOfCell = remember(cells) {
        var current = -1
        IntArray(cells.size) { index ->
            (cells[index] as? PickerCell.Header)?.let { current = it.sectionId }
            current
        }
    }

    val gridState = rememberLazyGridState()
    // A new query rebuilds `cells`, but the grid restores position by key: the
    // previously-visible key is gone, so the stored index survives and measure
    // clamps it to the tail of the match list. Guarded on the first
    // composition so a rotation's restored position isn't wiped.
    var lastQuery by remember { mutableStateOf(trimmedQuery) }
    LaunchedEffect(trimmedQuery) {
        if (trimmedQuery != lastQuery) {
            lastQuery = trimmedQuery
            gridState.scrollToItem(0)
        }
    }
    val scrolledCategory by remember(cells) {
        derivedStateOf {
            val first = gridState.firstVisibleItemIndex
            if (sectionOfCell.isEmpty()) 0 else sectionOfCell[first.coerceIn(0, sectionOfCell.size - 1)]
        }
    }

    Column(modifier.fillMaxSize()) {
        // Search field, carved out above the grid.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)
                .background(colors.bgInput, RoundedCornerShape(16.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Icon(
                Icons.Outlined.Search, contentDescription = null,
                tint = colors.textSecondary, modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("Search", color = colors.textTertiary, fontSize = 15.sp)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = colors.textPrimary, fontSize = 15.sp),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { onSearchFocusChange?.invoke(it.isFocused) },
                )
            }
            if (query.isNotEmpty()) {
                // A bare glyph missed easily; keep a 28dp target.
                Box(
                    Modifier
                        .size(28.dp)
                        .clickable { query = "" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Cancel, contentDescription = "Clear search",
                        tint = colors.textSecondary, modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = gridCellSize),
                state = gridState,
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    count = cells.size,
                    // Stable identity so query/recent changes and scrolling diff
                    // existing cells instead of rebuilding every row. Keys are
                    // namespaced by cell type, and unicode/emote cells also carry
                    // their section: the same glyph can appear in both "Frequently
                    // Used" and its home category, so the section keeps the two
                    // occurrences distinct (a duplicate key would crash the grid).
                    key = { index ->
                        val section = sectionOfCell[index]
                        when (val cell = cells[index]) {
                            is PickerCell.Header -> "h${cell.sectionId}"
                            is PickerCell.Unicode -> "u$section/${cell.emoji}"
                            is PickerCell.Emote -> "e$section/${cell.emote.id}"
                        }
                    },
                    span = { index ->
                        if (cells[index] is PickerCell.Header) GridItemSpan(maxLineSpan)
                        else GridItemSpan(1)
                    },
                    contentType = { index ->
                        when (cells[index]) {
                            is PickerCell.Header -> 0
                            is PickerCell.Unicode -> 1
                            is PickerCell.Emote -> 2
                        }
                    },
                ) { index ->
                    when (val cell = cells[index]) {
                        is PickerCell.Header -> Text(
                            cell.title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
                        )
                        is PickerCell.Unicode -> {
                            val interaction = remember { MutableInteractionSource() }
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(gridCellSize)
                                    .pressFeedback(interaction)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(
                                        interactionSource = interaction,
                                        indication = null,
                                    ) {
                                        insert(cell.emoji)
                                        rememberRecent(cell.emoji)
                                    },
                            ) {
                                Text(cell.emoji, fontSize = emojiFontSize)
                            }
                        }
                        is PickerCell.Emote -> {
                            val interaction = remember { MutableInteractionSource() }
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(gridCellSize)
                                    .pressFeedback(interaction)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(
                                        interactionSource = interaction,
                                        indication = null,
                                    ) { insertCustom?.invoke(cell.emote) },
                            ) {
                                EmoteImage(
                                    url = cell.emote.url,
                                    size = gridCellSize - 8.dp,
                                    loader = loader,
                                    contentDescription = cell.emote.token,
                                )
                            }
                        }
                    }
                }
            }
            if (trimmedQuery.isNotEmpty() && searchResults.isEmpty() && customSearchResults.isEmpty()) {
                Text(
                    "No results for “$query”",
                    color = colors.textSecondary,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
            }
        }

        // Category bar; scrolls when packs overflow. Opaque backdrop: scrolled
        // emoji pass beneath this bar.
        var barWidthPx by remember { mutableIntStateOf(0) }
        Column(Modifier.fillMaxWidth().background(colors.bgElevated)) {
            HorizontalDivider(color = colors.separator)
            Spacer(Modifier.height(6.dp))
            val count = (if (recents.isEmpty()) 0 else 1) + shownPacks.size +
                EmojiCatalog.categories.size
            val barWidth = with(density) { barWidthPx.toDp() }
            // Low enough that the packless bar (11 slots) fits a phone width.
            val slotWidth = maxOf(34.dp, barWidth / maxOf(1, count))
            Row(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { barWidthPx = it.width }
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                fun jump(sectionId: Int) {
                    query = ""
                    headerIndexBySection[sectionId]?.let {
                        scope.launch { gridState.scrollToItem(it) }
                    }
                }
                if (recents.isNotEmpty()) {
                    CategoryBarSlot(
                        selected = scrolledCategory == -1 && query.isEmpty(),
                        width = slotWidth,
                        contentDescription = "Frequently Used",
                        onClick = { jump(-1) },
                    ) { tint ->
                        Icon(
                            Icons.Outlined.Schedule, contentDescription = null,
                            tint = tint, modifier = Modifier.size(16.dp),
                        )
                    }
                }
                shownPacks.forEachIndexed { index, pack ->
                    val sectionId = 100 + index
                    CategoryBarSlot(
                        selected = scrolledCategory == sectionId && query.isEmpty(),
                        width = slotWidth,
                        contentDescription = pack.displayName,
                        onClick = { jump(sectionId) },
                    ) { tint ->
                        val avatarUrl = pack.avatarUrl
                        if (avatarUrl != null) {
                            EmoteImage(url = avatarUrl, size = barItemHeight - 10.dp, loader = loader)
                        } else {
                            Icon(
                                Icons.Outlined.Star, contentDescription = null,
                                tint = tint, modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
                EmojiCatalog.categories.forEachIndexed { index, category ->
                    CategoryBarSlot(
                        selected = scrolledCategory == index && query.isEmpty(),
                        width = slotWidth,
                        contentDescription = category.title,
                        onClick = { jump(index) },
                    ) { tint ->
                        Icon(
                            category.icon, contentDescription = null,
                            tint = tint, modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** One slot in the bottom category bar: equal division of the measured width. */
@Composable
private fun CategoryBarSlot(
    selected: Boolean,
    width: Dp,
    contentDescription: String,
    onClick: () -> Unit,
    content: @Composable (tint: Color) -> Unit,
) {
    val colors = LocalDiscourseColors.current
    val tint = if (selected) colors.accent else colors.textSecondary
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(width)
            .height(barItemHeight)
            .pressFeedback(interaction)
            .clip(CircleShape)
            .background(if (selected) colors.bgHover else Color.Transparent, CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = contentDescription,
                onClick = onClick,
            ),
    ) {
        content(tint)
    }
}

/** Emoji and stickers behind one button, switched with a capsule tab bar. */
@Composable
fun EmojiStickerPicker(
    customPacks: List<CustomEmojiStore.Pack> = emptyList(),
    loader: EmoteImageLoader? = null,
    insertEmoji: (String) -> Unit,
    /** Inserts a custom emote token into the composer. */
    insertCustomEmoji: ((CustomEmojiStore.Emote) -> Unit)? = null,
    /** Bubbles the active tab's search-field focus to the composer. */
    onSearchFocusChange: ((Boolean) -> Unit)? = null,
    /**
     * Packs go stale as the user joins things; opening the picker is the
     * natural refresh point (CustomEmojiStore.refreshIfStale when it lands).
     */
    refreshCustomEmoji: (suspend () -> Unit)? = null,
    /** StickerPickerView slot; the placeholder shows until that port lands. */
    stickerPicker: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDiscourseColors.current
    var tab by remember { mutableStateOf(0) }

    Column(modifier.fillMaxSize()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            PickerTabButton("Emoji", selected = tab == 0, modifier = Modifier.weight(1f)) { tab = 0 }
            PickerTabButton("Stickers", selected = tab == 1, modifier = Modifier.weight(1f)) { tab = 1 }
        }

        when (tab) {
            0 -> {
                LaunchedEffect(Unit) { refreshCustomEmoji?.invoke() }
                EmojiPickerView(
                    customPacks = customPacks,
                    loader = loader,
                    insertCustom = insertCustomEmoji,
                    onSearchFocusChange = onSearchFocusChange,
                    insert = insertEmoji,
                )
            }
            1 -> {
                if (stickerPicker != null) {
                    stickerPicker()
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No stickers yet", color = colors.textSecondary, fontSize = 15.sp)
                    }
                }
            }
        }
    }

    // Switching tabs destroys the active picker mid-focus; its focus reporter
    // never fires false, leaving the composer's panel-search latch stuck.
    LaunchedEffect(tab) { onSearchFocusChange?.invoke(false) }
}

@Composable
private fun PickerTabButton(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .pressFeedback(interaction)
            .clip(CircleShape)
            .background(if (selected) colors.bgElevated2 else Color.Transparent, CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 10.dp),
    ) {
        Text(
            title,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) colors.textPrimary else colors.textSecondary,
        )
    }
}
