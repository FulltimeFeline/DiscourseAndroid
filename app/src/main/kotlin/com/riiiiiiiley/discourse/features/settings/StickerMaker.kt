package com.riiiiiiiley.discourse.features.settings

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riiiiiiiley.discourse.core.StickerStore
import com.riiiiiiiley.discourse.core.media.MediaLoader
import com.riiiiiiiley.discourse.features.stickers.StickerThumb
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pick and name an image; it's cropped, scaled to 512px, uploaded, and saved
 * to the account-wide sticker pack (MSC2545). Port of the iOS StickerMakerView
 * (grouped-Form variant with per-row deletion).
 */
@Composable
fun StickerMakerScreen(
    store: StickerStore,
    loader: MediaLoader,
    onBack: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    val context = LocalContext.current
    val runScope = rememberCoroutineScope()

    val stickers by store.stickers.collectAsStateWithLifecycle()
    val errorMessage by store.errorMessage.collectAsStateWithLifecycle()
    // Plain getter, recomputed as [stickers] changes (like the iOS computed property).
    val packs = remember(stickers) { store.packs }

    var pickedData by remember { mutableStateOf<ByteArray?>(null) }
    var pickedPreview by remember { mutableStateOf<ImageBitmap?>(null) }
    var name by remember { mutableStateOf("") }
    var pack by remember { mutableStateOf(StickerStore.Sticker.defaultPack) }
    var isAdding by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runScope.launch {
            val picked = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
            } ?: return@launch
            pickedData = picked
            pickedPreview = withContext(Dispatchers.Default) {
                runCatching { BitmapFactory.decodeByteArray(picked, 0, picked.size)?.asImageBitmap() }
                    .getOrNull()
            }
        }
    }

    fun addSticker() {
        val data = pickedData ?: return
        isAdding = true
        val stickerName = name
        val packName = pack
        runScope.launch {
            // store.add reports failure via store.errorMessage, not throwing;
            // keep the picked image and name so a retry needn't re-pick.
            store.add(name = stickerName, imageData = data, pack = packName)
            isAdding = false
            if (store.errorMessage.value == null) {
                pickedData = null
                pickedPreview = null
                name = ""
            }
        }
    }

    val canAdd = pickedData != null && name.trim().isNotEmpty() && !isAdding

    LaunchedEffect(Unit) { store.load() }

    SettingsScaffold(title = "Stickers", onBack = onBack) {
        SettingsSection(
            header = "New Sticker",
            footer = "Stickers are cropped, scaled, and saved to your account-wide pack. " +
                "They sync to other Matrix clients.",
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(
                    text = if (pickedPreview == null) "Choose Image" else "Change Image",
                    color = colors.accent,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                val preview = pickedPreview
                if (preview != null) {
                    Image(
                        bitmap = preview,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.AddPhotoAlternate,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            FormDivider()
            PlainFieldRow(placeholder = "Name", value = name, onValueChange = { name = it })
            FormDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            ) {
                BasicTextField(
                    value = pack,
                    onValueChange = { pack = it },
                    singleLine = true,
                    textStyle = TextStyle(color = colors.textPrimary, fontSize = 16.sp),
                    cursorBrush = SolidColor(colors.accent),
                    decorationBox = { inner ->
                        Box {
                            if (pack.isEmpty()) {
                                Text(text = "Pack", color = colors.textTertiary, fontSize = 16.sp)
                            }
                            inner()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                if (packs.isNotEmpty()) {
                    var showsPackMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showsPackMenu = true }) {
                            Icon(
                                imageVector = Icons.Filled.UnfoldMore,
                                contentDescription = "Choose Existing Pack",
                                tint = colors.textSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = showsPackMenu,
                            onDismissRequest = { showsPackMenu = false },
                            containerColor = colors.bgElevated2,
                        ) {
                            for (existing in packs) {
                                DropdownMenuItem(
                                    text = { Text(existing, color = colors.textPrimary) },
                                    onClick = {
                                        pack = existing
                                        showsPackMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
            FormDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = canAdd, onClick = ::addSticker)
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                if (isAdding) {
                    CircularProgressIndicator(
                        color = colors.textSecondary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(text = "Adding…", color = colors.textSecondary, fontSize = 16.sp)
                } else {
                    Text(
                        text = "Add Sticker",
                        color = if (canAdd) colors.accent else colors.accent.copy(alpha = 0.4f),
                        fontSize = 16.sp,
                    )
                }
            }
        }

        errorMessage?.let { error ->
            SettingsSection {
                Text(
                    text = error,
                    color = colors.unreadMention,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }

        if (stickers.isEmpty()) {
            SettingsSection {
                Text(
                    text = "Your stickers appear here and sync to other Matrix clients.",
                    color = colors.textSecondary,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        } else {
            for (packName in packs) {
                SettingsSection(header = packName) {
                    val inPack = stickers.filter { it.pack == packName }
                    inPack.forEachIndexed { index, sticker ->
                        if (index > 0) FormDivider()
                        StickerRow(
                            sticker = sticker,
                            loader = loader,
                            onDelete = { runScope.launch { store.remove(sticker.shortcode) } },
                        )
                    }
                }
            }
        }
    }
}

/** One saved sticker: thumb, name, and a visible per-row delete (discoverable
 *  without a long-press) — plus the long-press affordance too. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StickerRow(
    sticker: StickerStore.Sticker,
    loader: MediaLoader,
    onDelete: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    var showsContextMenu by remember { mutableStateOf(false) }

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = {}, onLongClick = { showsContextMenu = true })
                .heightIn(min = 56.dp)
                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        ) {
            StickerThumb(sticker = sticker, loader = loader, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                text = sticker.body,
                color = colors.textPrimary,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.RemoveCircle,
                    contentDescription = "Delete ${sticker.body}",
                    tint = colors.unreadMention,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        DropdownMenu(
            expanded = showsContextMenu,
            onDismissRequest = { showsContextMenu = false },
            containerColor = colors.bgElevated2,
        ) {
            DropdownMenuItem(
                text = { Text("Delete Sticker", color = colors.unreadMention) },
                onClick = {
                    showsContextMenu = false
                    onDelete()
                },
            )
        }
    }
}

/** Full-width text field row (iOS Form `TextField`). */
@Composable
private fun PlainFieldRow(placeholder: String, value: String, onValueChange: (String) -> Unit) {
    val colors = LocalDiscourseColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = colors.textPrimary, fontSize = 16.sp),
            cursorBrush = SolidColor(colors.accent),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(text = placeholder, color = colors.textTertiary, fontSize = 16.sp)
                    }
                    inner()
                }
            },
            modifier = Modifier.weight(1f),
        )
    }
}
