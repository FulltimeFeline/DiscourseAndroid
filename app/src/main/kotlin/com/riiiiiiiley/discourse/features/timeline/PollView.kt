package com.riiiiiiiley.discourse.features.timeline

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riiiiiiiley.discourse.models.MessageItem
import com.riiiiiiiley.discourse.models.PollItem
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import kotlinx.coroutines.launch

private fun voteCountText(count: Int): String =
    if (count == 1) "1 vote" else "$count votes"

/** Inline poll: question, votable options with result bars, end-poll for the author. */
@Composable
fun PollView(
    poll: PollItem,
    message: MessageItem,
    viewModel: TimelineViewModel,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDiscourseColors.current
    val showsResults = poll.isDisclosed || poll.isEnded || poll.votedByMe
    // SwiftUI `.quaternary` analogue: primary text at ~18% alpha, then scaled
    // by the iOS opacities below.
    val quaternary = colors.textPrimary.copy(alpha = 0.18f)

    Column(
        modifier
            .widthIn(max = 380.dp)
            .clip(RoundedCornerShape(10.dp))
            // 0.35, not 0.5: option chips (0.5) stack on this, and 0.5 here would
            // double-darken every nested row.
            .background(quaternary.copy(alpha = quaternary.alpha * 0.35f))
            .padding(12.dp)
            .animateContentSize(tween(durationMillis = 300, easing = EaseOut)),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.BarChart,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                poll.question,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                modifier = Modifier.semantics { contentDescription = "Poll: ${poll.question}" },
            )
        }

        poll.answers.forEach { answer ->
            PollOptionRow(
                poll = poll,
                answer = answer,
                showsResults = showsResults,
                chipBackground = quaternary.copy(alpha = quaternary.alpha * 0.5f),
                onVote = {
                    if (!poll.isEnded) viewModel.votePoll(message, answer.id)
                },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Combine the two status captions into one spoken element.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.semantics(mergeDescendants = true) {},
            ) {
                Text(
                    if (poll.isEnded) "Final result — ${voteCountText(poll.totalVotes)}"
                    else voteCountText(poll.totalVotes),
                    fontSize = 11.sp,
                    color = colors.textTertiary,
                )
                if (!showsResults) {
                    Text(
                        "Results shown when the poll ends",
                        fontSize = 11.sp,
                        color = colors.textTertiary,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (message.isOwn && !poll.isEnded) {
                Text(
                    "End Poll",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { viewModel.endPoll(message) }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun PollOptionRow(
    poll: PollItem,
    answer: PollItem.Answer,
    showsResults: Boolean,
    chipBackground: Color,
    onVote: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    // Animated like the iOS `.animation(.easeOut(0.3), value: poll)` bar growth.
    val fraction by animateFloatAsState(
        targetValue = if (showsResults && poll.totalVotes > 0) {
            answer.voteCount.toFloat() / poll.totalVotes
        } else 0f,
        animationSpec = tween(durationMillis = 300, easing = EaseOut),
        label = "pollBar",
    )

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(chipBackground)
            .clickable(enabled = !poll.isEnded, onClick = onVote)
            // One spoken element per option: text, then count and voted state.
            .semantics(mergeDescendants = true) {
                if (answer.votedByMe) selected = true
                if (showsResults) stateDescription = voteCountText(answer.voteCount)
            },
    ) {
        if (fraction > 0f) {
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.accent.copy(alpha = if (answer.votedByMe) 0.25f else 0.12f)),
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (answer.votedByMe) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = if (answer.votedByMe) colors.accent else colors.textSecondary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                answer.text,
                fontSize = 15.sp,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            if (showsResults) {
                Text(
                    answer.voteCount.toString(),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textSecondary,
                    // iOS `.monospacedDigit()`: keep counts from jittering as votes land.
                    style = TextStyle(fontFeatureSettings = "tnum"),
                )
            }
        }
    }
}

/** Poll composer sheet. */
@Composable
fun NewPollSheet(
    viewModel: TimelineViewModel,
    onDismiss: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var question by remember { mutableStateOf("") }
    val answers = remember { mutableStateListOf("", "") }
    var disclosed by remember { mutableStateOf(true) }
    var isCreating by remember { mutableStateOf(false) }
    val questionFocus = remember { FocusRequester() }
    val optionFocus = remember { mutableStateListOf(FocusRequester(), FocusRequester()) }

    val canCreate = question.trim().isNotEmpty() &&
        answers.count { it.trim().isNotEmpty() } >= 2

    fun create() {
        isCreating = true
        val finalAnswers = answers.map { it.trim() }.filter { it.isNotEmpty() }
        scope.launch {
            viewModel.createPoll(
                question = question.trim(),
                answers = finalAnswers,
                disclosed = disclosed,
            )
            onDismiss()
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.bgApp)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // Nav-bar Cancel/Create (iOS phone sheet chrome).
        Box(
            Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(
                "Cancel",
                fontSize = 15.sp,
                color = colors.accent,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                "New Poll",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                modifier = Modifier.align(Alignment.Center),
            )
            Text(
                if (isCreating) "Creating…" else "Create",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.accent,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .alpha(if (canCreate && !isCreating) 1f else 0.4f)
                    .clickable(enabled = canCreate && !isCreating) { create() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            FormSection(header = "Question") {
                PollTextField(
                    value = question,
                    onValueChange = { question = it },
                    placeholder = "Ask a question",
                    focusRequester = questionFocus,
                    imeAction = ImeAction.Next,
                    onImeAction = { optionFocus.firstOrNull()?.requestFocus() },
                )
            }

            FormSection(header = "Options", footer = "A poll needs at least two options.") {
                answers.forEachIndexed { index, answer ->
                    if (index > 0) HorizontalDivider(color = colors.separator)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PollTextField(
                            value = answer,
                            onValueChange = { answers[index] = it },
                            placeholder = "Option ${index + 1}",
                            focusRequester = optionFocus[index],
                            imeAction = if (index == answers.size - 1) ImeAction.Done else ImeAction.Next,
                            onImeAction = {
                                if (index < answers.size - 1) {
                                    optionFocus[index + 1].requestFocus()
                                } else {
                                    focusManager.clearFocus()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        // Explicit delete, no swipe needed.
                        Icon(
                            Icons.Filled.RemoveCircle,
                            contentDescription = "Remove Option ${index + 1}",
                            tint = if (answers.size > 2) colors.unreadMention else colors.textTertiary,
                            modifier = Modifier
                                .size(22.dp)
                                .clickable(enabled = answers.size > 2) {
                                    answers.removeAt(index)
                                    optionFocus.removeAt(index)
                                },
                        )
                    }
                }
                if (answers.size < 8) {
                    HorizontalDivider(color = colors.separator)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                answers.add("")
                                optionFocus.add(FocusRequester())
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Outlined.AddCircle,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(20.dp),
                        )
                        Text("Add Option", fontSize = 15.sp, color = colors.accent)
                    }
                }
            }

            FormSection(footer = "When this is off, votes stay hidden until you end the poll.") {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Show results while the poll is open",
                        fontSize = 15.sp,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = disclosed,
                        onCheckedChange = { disclosed = it },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = colors.accent,
                            checkedThumbColor = colors.textOnAccent,
                        ),
                    )
                }
            }
        }
    }
}

/** Inset-grouped form section: uppercase header, rounded card, caption footer. */
@Composable
private fun FormSection(
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalDiscourseColors.current
    Column {
        if (header != null) {
            Text(
                header.uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textSecondary,
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colors.bgElevated)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            content = content,
        )
        if (footer != null) {
            Text(
                footer,
                fontSize = 11.sp,
                color = colors.textTertiary,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp),
            )
        }
    }
}

@Composable
private fun PollTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDiscourseColors.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        textStyle = TextStyle(fontSize = 15.sp, color = colors.textPrimary),
        cursorBrush = SolidColor(colors.accent),
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(
            onNext = { onImeAction() },
            onDone = { onImeAction() },
        ),
        decorationBox = { inner ->
            Box(
                Modifier.defaultMinSize(minHeight = 44.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, fontSize = 15.sp, color = colors.textTertiary)
                }
                inner()
            }
        },
    )
}
