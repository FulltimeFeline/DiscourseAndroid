package com.riiiiiiiley.discourse.features.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riiiiiiiley.discourse.app.AppState
import com.riiiiiiiley.discourse.core.MatrixService
import com.riiiiiiiley.discourse.core.SessionScope
import com.riiiiiiiley.discourse.core.media.MediaLoader
import com.riiiiiiiley.discourse.features.profile.BannerImageView
import com.riiiiiiiley.discourse.features.roomlist.RoomAvatarView
import com.riiiiiiiley.discourse.features.timeline.EmojiPickerView
import com.riiiiiiiley.discourse.features.timeline.EmoteImageLoader
import com.riiiiiiiley.discourse.ui.media.LocalMediaLoader
import com.riiiiiiiley.discourse.ui.presence.PresenceDot
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.TimeZone
import java.util.UUID

/**
 * The phone Settings tab (iOS ProfileTabView): identity card, customization,
 * privacy/notifications, accounts, and the sign-out control, with its own
 * internal navigation stack for the sub-screens.
 */
private enum class SettingsDestination {
    ROOT, EDIT_PROFILE, APPEARANCE, CHAT, ACCESSIBILITY, STORAGE, STICKERS,
    PRIVACY, NOTIFICATIONS, ADVANCED, ABOUT,
}

@Composable
fun SettingsTabScreen(
    appState: AppState,
    scope: SessionScope,
    modifier: Modifier = Modifier,
    /** Session media loader for the Storage screen's cache controls (scope.mediaLoader). */
    mediaLoader: MediaLoader? = null,
) {
    val prefs = appState.preferences
    val p by prefs.state.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(SettingsDestination.ROOT) }

    BackHandler(enabled = destination != SettingsDestination.ROOT) {
        destination = SettingsDestination.ROOT
    }

    val back: () -> Unit = { destination = SettingsDestination.ROOT }
    val reduceMotion = p.reduceTimelineMotion

    AnimatedContent(
        targetState = destination,
        transitionSpec = {
            if (reduceMotion) {
                fadeIn() togetherWith fadeOut()
            } else if (targetState == SettingsDestination.ROOT) {
                // Popping back: root slides in from the left, detail exits right.
                (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith
                    slideOutHorizontally { it }
            } else {
                slideInHorizontally { it } togetherWith
                    (slideOutHorizontally { -it / 4 } + fadeOut())
            }
        },
        modifier = modifier,
        label = "settings-nav",
    ) { dest ->
        when (dest) {
            SettingsDestination.ROOT -> SettingsRootScreen(
                appState = appState,
                scope = scope,
                onNavigate = { destination = it },
            )
            SettingsDestination.EDIT_PROFILE -> ProfileEditScreen(scope = scope, onBack = back)
            SettingsDestination.APPEARANCE -> AppearanceSettingsScreen(prefs = prefs, onBack = back)
            SettingsDestination.CHAT -> ChatSettingsScreen(prefs = prefs, onBack = back)
            SettingsDestination.ACCESSIBILITY -> AccessibilitySettingsScreen(prefs = prefs, onBack = back)
            SettingsDestination.STORAGE -> StorageSettingsScreen(
                prefs = prefs,
                onBack = back,
                loader = mediaLoader,
            )
            SettingsDestination.STICKERS -> StickerMakerScreen(
                store = scope.stickers,
                loader = scope.mediaLoader,
                onBack = back,
            )
            SettingsDestination.PRIVACY -> PrivacySettingsScreen(prefs = prefs, onBack = back)
            SettingsDestination.NOTIFICATIONS -> NotificationSettingsScreen(appState = appState, onBack = back)
            SettingsDestination.ADVANCED -> AdvancedSettingsScreen(scope = scope, prefs = prefs, onBack = back)
            SettingsDestination.ABOUT -> AboutSettingsScreen(onBack = back)
        }
    }
}

@Composable
private fun SettingsRootScreen(
    appState: AppState,
    scope: SessionScope,
    onNavigate: (SettingsDestination) -> Unit,
) {
    val colors = LocalDiscourseColors.current
    val runScope = rememberCoroutineScope()
    val ownDisplayName by scope.ownDisplayName.collectAsStateWithLifecycle()
    val ownAvatarUrl by scope.ownAvatarUrl.collectAsStateWithLifecycle()
    val accountTokens by appState.accountTokens.collectAsStateWithLifecycle()
    // Recompose hint for the per-account badges: unreadCount is a plain read
    // into the warm scopes (like the iOS computed property), so key it on the
    // cross-account unread flow.
    val otherAccountsHaveUnread by appState.otherAccountsHaveUnread.collectAsStateWithLifecycle()
    var showsSignOutConfirm by remember { mutableStateOf(false) }

    SettingsScaffold(title = "Settings", onBack = null) {
        // Apple-ID-style identity card: taps through to a dedicated Edit
        // Profile screen rather than inlining the whole editor here.
        SettingsSection {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigate(SettingsDestination.EDIT_PROFILE) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Box {
                    RoomAvatarView(
                        name = ownDisplayName ?: scope.userId,
                        isDirect = true,
                        size = 60.dp,
                        avatarUrl = ownAvatarUrl,
                    )
                    PresenceDot(
                        userId = scope.userId,
                        size = 15.dp,
                        modifier = Modifier.align(Alignment.BottomEnd),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = ownDisplayName ?: scope.userId,
                        color = colors.textPrimary,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(text = "Edit Profile", color = colors.textSecondary, fontSize = 14.sp)
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.textTertiary,
                )
            }
        }

        SettingsSection(header = "Customization") {
            NavRow(title = "Appearance", icon = Icons.Filled.Brush) { onNavigate(SettingsDestination.APPEARANCE) }
            FormDivider()
            NavRow(title = "Chat", icon = Icons.Filled.Forum) { onNavigate(SettingsDestination.CHAT) }
            FormDivider()
            NavRow(title = "Accessibility", icon = Icons.Filled.Accessibility) { onNavigate(SettingsDestination.ACCESSIBILITY) }
            FormDivider()
            NavRow(title = "Storage", icon = Icons.Filled.Storage) { onNavigate(SettingsDestination.STORAGE) }
            FormDivider()
            NavRow(title = "Stickers", icon = Icons.Filled.Mood) { onNavigate(SettingsDestination.STICKERS) }
        }

        SettingsSection {
            NavRow(title = "Privacy & Security", icon = Icons.Filled.PanTool) { onNavigate(SettingsDestination.PRIVACY) }
            FormDivider()
            NavRow(title = "Notifications", icon = Icons.Filled.Notifications) { onNavigate(SettingsDestination.NOTIFICATIONS) }
        }

        SettingsSection(header = "Accounts") {
            accountTokens.forEachIndexed { index, token ->
                val userId = token.session.userId
                if (index > 0) FormDivider()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { runScope.launch { appState.switchAccount(to = userId) } }
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = userId,
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier.weight(1f),
                    )
                    val unread = remember(userId, otherAccountsHaveUnread) {
                        appState.unreadCount(forUserId = userId)
                    }
                    if (userId != appState.activeUserId && unread > 0) {
                        UnreadBadge(count = unread)
                        Spacer(Modifier.width(8.dp))
                    }
                    if (userId == appState.activeUserId) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Active account",
                            tint = colors.accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            FormDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { appState.isAddAccountPresented.value = true }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.PersonAdd,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(14.dp))
                Text(text = "Add Account…", color = colors.accent, fontSize = 16.sp)
            }
        }

        SettingsSection(header = "Account") {
            LabeledValueRow(label = "Homeserver", value = scope.token.session.homeserverUrl)
            FormDivider()
            LabeledValueRow(label = "Device ID", value = scope.token.session.deviceId)
        }

        SettingsSection {
            NavRow(title = "Advanced", icon = Icons.Filled.Build) { onNavigate(SettingsDestination.ADVANCED) }
            FormDivider()
            NavRow(title = "About", icon = Icons.Filled.Info) { onNavigate(SettingsDestination.ABOUT) }
        }

        SettingsSection {
            ButtonRow(title = "Sign Out…", destructive = true) { showsSignOutConfirm = true }
        }
    }

    if (showsSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showsSignOutConfirm = false },
            containerColor = colors.bgElevated2,
            titleContentColor = colors.textPrimary,
            textContentColor = colors.textSecondary,
            title = { Text("Sign out of ${scope.userId}?") },
            text = { Text("Local session data is removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    showsSignOutConfirm = false
                    runScope.launch { appState.logOut() }
                }) {
                    Text("Sign Out", color = colors.unreadMention)
                }
            },
            dismissButton = {
                TextButton(onClick = { showsSignOutConfirm = false }) {
                    Text("Cancel", color = colors.accent)
                }
            },
        )
    }
}

// MARK: Edit Profile (iOS ProfileEditSection)

/** A mutable social-link row (stable id for list keys/focus). */
private data class EditableLink(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val link: String = "",
    val img: String = "",
)

private enum class ImageTarget { AVATAR, BANNER }

/** Edit avatar, banner, identity fields, and social links, published server-side. */
@Composable
fun ProfileEditScreen(scope: SessionScope, onBack: () -> Unit) {
    val colors = LocalDiscourseColors.current
    val context = LocalContext.current
    val runScope = rememberCoroutineScope()

    val ownDisplayName by scope.ownDisplayName.collectAsStateWithLifecycle()
    val ownAvatarUrl by scope.ownAvatarUrl.collectAsStateWithLifecycle()
    val ownPronouns by scope.ownPronouns.collectAsStateWithLifecycle()
    val ownBio by scope.ownBio.collectAsStateWithLifecycle()
    val ownStatus by scope.ownStatus.collectAsStateWithLifecycle()
    val ownTimezone by scope.ownTimezone.collectAsStateWithLifecycle()
    val ownBannerUrl by scope.ownBannerUrl.collectAsStateWithLifecycle()
    val ownSocialLinks by scope.ownSocialLinks.collectAsStateWithLifecycle()

    var displayName by remember { mutableStateOf("") }
    var pronouns by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var statusMsg by remember { mutableStateOf("") }
    var timezone by remember { mutableStateOf("") }
    var links by remember { mutableStateOf(listOf<EditableLink>()) }
    var loaded by remember { mutableStateOf(false) }
    /** Identifies which link row's icon the picker sheet is choosing for. */
    var iconTargetId by remember { mutableStateOf<String?>(null) }

    // A single image picker, routed by target — same routing the iOS view uses
    // for its one photosPicker.
    var imageTarget by remember { mutableStateOf(ImageTarget.AVATAR) }
    var isSaving by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    fun runOp(operation: suspend () -> Unit) {
        isSaving = true
        status = null
        runScope.launch {
            try {
                operation()
                status = "Profile updated." to false
            } catch (error: Exception) {
                status = (error.message ?: "Something went wrong.") to true
            } finally {
                isSaving = false
            }
        }
    }

    fun applyPickedImage(data: ByteArray, mime: String, target: ImageTarget) {
        when (target) {
            ImageTarget.AVATAR -> runOp { scope.setAvatar(data = data, mimeType = mime) }
            ImageTarget.BANNER -> runOp { scope.setBanner(data = data, mimeType = mime) }
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val target = imageTarget
        runScope.launch {
            val picked = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    bytes?.let { it to (context.contentResolver.getType(uri) ?: "image/png") }
                }.getOrNull()
            }
            if (picked == null) {
                status = "Couldn't read that image." to true
                return@launch
            }
            applyPickedImage(data = picked.first, mime = picked.second, target = target)
        }
    }

    fun launchPicker(target: ImageTarget) {
        imageTarget = target
        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    LaunchedEffect(Unit) {
        scope.loadOwnProfile()
        // Seed the editable fields once, so typing isn't clobbered by a later
        // profile refresh.
        if (!loaded) {
            loaded = true
            displayName = scope.ownDisplayName.value ?: ""
            pronouns = scope.ownPronouns.value ?: ""
            bio = scope.ownBio.value ?: ""
            statusMsg = scope.ownStatus.value ?: ""
            timezone = scope.ownTimezone.value ?: ""
            links = scope.ownSocialLinks.value.map {
                EditableLink(title = it.title, link = it.link, img = it.img ?: "")
            }
        }
    }

    /** The edited links as SocialLinks, dropping rows with no usable link. */
    val currentLinks = links.mapNotNull { row ->
        val link = row.link.trim()
        if (link.isEmpty()) return@mapNotNull null
        val title = row.title.trim()
        val img = row.img.trim()
        MatrixService.SocialLink(
            img = img.ifEmpty { null },
            title = title.ifEmpty { link },
            link = link,
        )
    }

    val hasChanges = run {
        fun norm(s: String) = s.trim()
        val nameChanged = norm(displayName).isNotEmpty() && norm(displayName) != (ownDisplayName ?: "")
        nameChanged ||
            norm(pronouns) != (ownPronouns ?: "") ||
            norm(statusMsg) != (ownStatus ?: "") ||
            norm(bio) != (ownBio ?: "") ||
            norm(timezone) != (ownTimezone ?: "") ||
            currentLinks != ownSocialLinks
    }

    /** Saves every field that changed, in one pass. */
    fun saveAll() {
        isSaving = true
        status = null
        runScope.launch {
            try {
                fun norm(s: String) = s.trim()
                val name = norm(displayName)
                if (name.isNotEmpty() && name != scope.ownDisplayName.value) scope.setDisplayName(name)
                if (norm(pronouns) != (scope.ownPronouns.value ?: "")) scope.setPronouns(pronouns)
                if (norm(statusMsg) != (scope.ownStatus.value ?: "")) scope.setStatus(statusMsg)
                if (norm(bio) != (scope.ownBio.value ?: "")) scope.setBio(bio)
                if (norm(timezone) != (scope.ownTimezone.value ?: "")) scope.setTimezone(timezone)
                if (currentLinks != scope.ownSocialLinks.value) scope.setSocialLinks(currentLinks)
                status = "Profile updated." to false
            } catch (error: Exception) {
                status = (error.message ?: "Something went wrong.") to true
            } finally {
                isSaving = false
            }
        }
    }

    fun updateLink(id: String, transform: (EditableLink) -> EditableLink) {
        links = links.map { if (it.id == id) transform(it) else it }
    }

    SettingsScaffold(title = "Edit Profile", onBack = onBack) {
        // Centered avatar header, sitting on the form background rather than in
        // a boxed row — the standard "profile top" look.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            RoomAvatarView(
                name = displayName.ifEmpty { scope.userId },
                isDirect = true,
                size = 88.dp,
                avatarUrl = ownAvatarUrl,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = displayName.ifEmpty { scope.userId },
                color = colors.textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallBorderedButton(title = "Change Photo") { launchPicker(ImageTarget.AVATAR) }
                if (ownAvatarUrl != null) {
                    SmallBorderedButton(title = "Remove", destructive = true) {
                        runOp { scope.removeAvatar() }
                    }
                }
            }
        }

        SettingsSection(header = "Banner", footer = "Shows at the top of your profile card.") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                ownBannerUrl?.let { banner ->
                    BannerImageView(
                        mxcUrl = banner,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmallBorderedButton(
                        title = if (ownBannerUrl == null) "Add Banner…" else "Change Banner…",
                    ) { launchPicker(ImageTarget.BANNER) }
                    if (ownBannerUrl != null) {
                        SmallBorderedButton(title = "Remove", destructive = true) {
                            runOp { scope.removeBanner() }
                        }
                    }
                }
            }
        }

        SettingsSection(
            header = "Identity",
            footer = "Your name, pronouns, and status are visible to everyone you share a room with.",
        ) {
            LabeledFieldRow(label = "Name", placeholder = "Display name", value = displayName) { displayName = it }
            FormDivider()
            LabeledFieldRow(label = "Pronouns", placeholder = "they/them", value = pronouns) { pronouns = it }
            FormDivider()
            LabeledFieldRow(label = "Status", placeholder = "What you're up to", value = statusMsg) { statusMsg = it }
            FormDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(text = "Bio", color = colors.textSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                BasicTextField(
                    value = bio,
                    onValueChange = { bio = it },
                    textStyle = TextStyle(color = colors.textPrimary, fontSize = 16.sp),
                    cursorBrush = SolidColor(colors.accent),
                    minLines = 3,
                    maxLines = 6,
                    decorationBox = { inner ->
                        Box {
                            if (bio.isEmpty()) {
                                Text(text = "Add a bio", color = colors.textTertiary, fontSize = 16.sp)
                            }
                            inner()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            FormDivider()
            TimezoneRow(value = timezone, onValueChange = { timezone = it })
        }

        SettingsSection(header = "Social Links") {
            links.forEachIndexed { index, link ->
                if (index > 0) FormDivider()
                SocialLinkRow(
                    link = link,
                    onTitleChange = { updateLink(link.id) { row -> row.copy(title = it) } },
                    onLinkChange = { updateLink(link.id) { row -> row.copy(link = it) } },
                    onChooseIcon = { iconTargetId = link.id },
                    onClearIcon = { updateLink(link.id) { row -> row.copy(img = "") } },
                    onRemove = { links = links.filter { it.id != link.id } },
                )
            }
            if (links.isNotEmpty()) FormDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { links = links + EditableLink() }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.AddCircle,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(text = "Add Link", color = colors.accent, fontSize = 16.sp)
            }
        }

        Column(Modifier.fillMaxWidth()) {
            Button(
                onClick = { saveAll() },
                enabled = !isSaving && hasChanges,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent,
                    contentColor = colors.textOnAccent,
                    disabledContainerColor = colors.accent.copy(alpha = 0.4f),
                    disabledContentColor = colors.textOnAccent.copy(alpha = 0.6f),
                ),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        color = colors.textOnAccent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(text = "Save Profile", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            status?.let { (message, isError) ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    color = if (isError) colors.unreadMention else colors.presenceOnline,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
        }
    }

    iconTargetId?.let { targetId ->
        LinkIconPickerSheet(
            scope = scope,
            onPick = { img ->
                updateLink(targetId) { it.copy(img = img) }
                iconTargetId = null
            },
            onDismiss = { iconTargetId = null },
        )
    }
}

// MARK: Edit-profile rows

/** A labeled text row: label left, right-aligned field (Settings-style). */
@Composable
private fun LabeledFieldRow(
    label: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    val colors = LocalDiscourseColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(text = label, color = colors.textPrimary, fontSize = 16.sp)
        Spacer(Modifier.width(12.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = colors.textSecondary, fontSize = 16.sp, textAlign = TextAlign.End),
            cursorBrush = SolidColor(colors.accent),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterEnd) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = colors.textTertiary,
                            fontSize = 16.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TimezoneRow(value: String, onValueChange: (String) -> Unit) {
    val colors = LocalDiscourseColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Text(text = "Timezone", color = colors.textPrimary, fontSize = 16.sp)
        Spacer(Modifier.width(12.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = colors.textSecondary, fontSize = 16.sp, textAlign = TextAlign.End),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterEnd) {
                    if (value.isEmpty()) {
                        Text(
                            text = "Europe/Berlin",
                            color = colors.textTertiary,
                            fontSize = 16.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onValueChange(TimeZone.getDefault().id) }) {
            Icon(
                imageVector = Icons.Filled.MyLocation,
                contentDescription = "Use current timezone",
                tint = colors.accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SocialLinkRow(
    link: EditableLink,
    onTitleChange: (String) -> Unit,
    onLinkChange: (String) -> Unit,
    onChooseIcon: () -> Unit,
    onClearIcon: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = link.title,
                onValueChange = onTitleChange,
                singleLine = true,
                textStyle = TextStyle(color = colors.textPrimary, fontSize = 16.sp),
                cursorBrush = SolidColor(colors.accent),
                decorationBox = { inner ->
                    Box {
                        if (link.title.isEmpty()) {
                            Text(text = "Title", color = colors.textTertiary, fontSize = 16.sp)
                        }
                        inner()
                    }
                },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Filled.RemoveCircle,
                    contentDescription = "Remove link",
                    tint = colors.unreadMention,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        BasicTextField(
            value = link.link,
            onValueChange = onLinkChange,
            singleLine = true,
            textStyle = TextStyle(color = colors.textSecondary, fontSize = 15.sp),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(
                // Autocapitalize/autocorrect would corrupt the URL.
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
            ),
            decorationBox = { inner ->
                Box {
                    if (link.link.isEmpty()) {
                        Text(text = "Link (https://…)", color = colors.textTertiary, fontSize = 15.sp)
                    }
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onChooseIcon)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                LinkIconPreview(img = link.img)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (link.img.isEmpty()) "Choose Icon…" else "Change Icon…",
                    color = colors.accent,
                    fontSize = 14.sp,
                )
            }
            if (link.img.isNotEmpty()) {
                IconButton(onClick = onClearIcon, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Cancel,
                        contentDescription = "Remove icon",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * Small preview of a social-link icon: an mxc emote, a unicode emoji, or a
 * placeholder glyph.
 */
@Composable
private fun LinkIconPreview(img: String) {
    val colors = LocalDiscourseColors.current
    val loader = LocalMediaLoader.current
    var image by remember(img) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(img, loader) {
        image = null
        if (!img.startsWith("mxc://")) return@LaunchedEffect
        image = loader?.avatar(img, 40)
    }

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(20.dp)) {
        val bitmap = image
        when {
            bitmap != null -> Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize(),
            )
            img.isNotEmpty() && !img.startsWith("mxc://") -> Text(text = img, fontSize = 14.sp) // unicode emoji
            else -> Icon(
                imageVector = Icons.Filled.Mood,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/**
 * Presents the emoji/emote picker to choose a social-link icon: a custom emote
 * yields its mxc URL, a unicode emoji yields the character.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkIconPickerSheet(
    scope: SessionScope,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    val customPacks by scope.customEmoji.packs.collectAsStateWithLifecycle()
    val emoteLoader = remember(scope) {
        EmoteImageLoader { url -> scope.mediaLoader.avatar(url, 64f) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.bgElevated,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        ) {
            Text(
                text = "Choose Icon",
                color = colors.textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.accent)
            }
        }
        EmojiPickerView(
            customPacks = customPacks,
            loader = emoteLoader,
            insertCustom = { onPick(it.url) },
            modifier = Modifier.fillMaxWidth().height(420.dp),
            insert = { onPick(it) },
        )
    }
}

// MARK: Shared bits

/**
 * Small bordered action button (iOS `.bordered` + `.controlSize(.small)`).
 */
@Composable
private fun SmallBorderedButton(title: String, destructive: Boolean = false, onClick: () -> Unit) {
    val colors = LocalDiscourseColors.current
    val tint = if (destructive) colors.unreadMention else colors.accent
    Text(
        text = title,
        color = tint,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

