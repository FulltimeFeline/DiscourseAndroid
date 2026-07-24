package com.riiiiiiiley.discourse.features.roomlist

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.riiiiiiiley.discourse.core.SessionScope
import com.riiiiiiiley.discourse.features.timeline.RenderedBody
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A space's "home page": banner, name/avatar, and the space bio (topic). Opened
 * from the sidebar banner. Space admins can change the banner here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceHomeView(
    space: RoomListViewModel.SpaceItem,
    bannerUrl: String?,
    scope: SessionScope,
    onDismiss: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()

    var localBanner by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    /** message to isError. */
    var editStatus by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    /** False until the permission check lands; controls stay hidden until then. */
    var canEditBanner by remember { mutableStateOf(false) }

    // The banner to display: a just-set one wins over the passed-in value.
    val effectiveBanner = localBanner ?: bannerUrl

    LaunchedEffect(space.id) {
        canEditBanner = scope.canEditSpaceBanner(spaceId = space.id)
    }

    fun setBanner(data: ByteArray, mime: String) {
        isSaving = true
        editStatus = null
        coroutineScope.launch {
            try {
                val mxc = scope.setSpaceBanner(spaceId = space.id, data = data, mimeType = mime)
                if (mxc != null) {
                    localBanner = mxc
                    editStatus = "Banner updated." to false
                } else {
                    editStatus = "You don't have permission to change this banner." to true
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                editStatus = (error.message ?: "Couldn't set the banner.") to true
            } finally {
                isSaving = false
            }
        }
    }

    fun removeBanner() {
        isSaving = true
        editStatus = null
        coroutineScope.launch {
            try {
                val ok = scope.removeSpaceBanner(spaceId = space.id)
                if (ok) {
                    localBanner = null
                    editStatus = "Banner removed." to false
                } else {
                    editStatus = "You don't have permission to change this banner." to true
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                editStatus = (error.message ?: "Couldn't remove the banner.") to true
            } finally {
                isSaving = false
            }
        }
    }

    val bannerPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            val (data, mime) = withContext(Dispatchers.IO) {
                val bytes = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
                bytes to (context.contentResolver.getType(uri) ?: "image/png")
            }
            if (data == null) {
                editStatus = "Couldn't read that image." to true
                return@launch
            }
            setBanner(data, mime)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.bgElevated,
    ) {
        Column(Modifier.fillMaxWidth()) {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        space.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
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
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                effectiveBanner?.let { banner ->
                    BannerImageView(
                        banner,
                        Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoomAvatarView(name = space.name, isDirect = false, size = 56.dp,
                                   avatarUrl = space.avatarUrl)
                    Text(
                        space.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val topic = space.topic
                if (!topic.isNullOrEmpty()) {
                    // Markdown/link-rendered like iOS RenderedBodyCache.rendered:
                    // formatted text with tappable, accent-tinted links.
                    SelectionContainer {
                        Text(
                            RenderedBody.rendered(topic, accent = colors.accent),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textPrimary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    Text(
                        "No description.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textSecondary,
                    )
                }

                if (canEditBanner) {
                    HorizontalDivider(color = colors.separator)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(
                                enabled = !isSaving,
                                onClick = {
                                    bannerPicker.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly),
                                    )
                                },
                            ) {
                                Text(if (effectiveBanner == null) "Add Banner…"
                                     else "Change Banner…")
                            }
                            if (effectiveBanner != null) {
                                TextButton(enabled = !isSaving, onClick = { removeBanner() }) {
                                    Text("Remove")
                                }
                            }
                            if (isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = colors.accent,
                                )
                            }
                        }
                        editStatus?.let { (message, isError) ->
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isError) MaterialTheme.colorScheme.error
                                        else colors.presenceOnline,
                            )
                        }
                    }
                }
            }
        }
    }
}
