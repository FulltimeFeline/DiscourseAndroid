package com.riiiiiiiley.discourse.features.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riiiiiiiley.discourse.app.AppState
import com.riiiiiiiley.discourse.core.LoginResult
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import kotlinx.coroutines.launch

/**
 * Sign-in form, matching the iOS LoginView's phone layout: hero (app glyph +
 * title + subtitle), then a two-stage grouped form — homeserver entry, then
 * the auth methods that server supports (browser OAuth/SSO and/or password).
 */
@Composable
fun LoginScreen(
    appState: AppState,
    /** Add-account sheet; the full-window logged-out login shows no Cancel. */
    isSheet: Boolean = false,
    onDismiss: () -> Unit = {},
) {
    val colors = LocalDiscourseColors.current
    val context = LocalContext.current
    val viewModel = remember { LoginViewModel(context.applicationContext) }
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    val homeserverFocus = remember { FocusRequester() }
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }

    fun complete(result: LoginResult) {
        appState.completeLogin(service = result.service, token = result.token)
    }

    fun submitPassword() {
        coroutineScope.launch {
            viewModel.passwordLogin()?.let { complete(it) }
        }
    }

    // Drive focus from the stage, mirroring iOS's onChange(of: stage).
    LaunchedEffect(ui.stage, ui.supportsPassword) {
        when (ui.stage) {
            LoginViewModel.Stage.SERVER -> homeserverFocus.requestFocus()
            LoginViewModel.Stage.METHODS ->
                if (ui.supportsPassword) usernameFocus.requestFocus()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bgApp)
            .systemBarsPadding()
            .imePadding(),
    ) {
        if (isSheet) {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
            ) {
                Text("Cancel", color = colors.accent)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(if (isSheet) 56.dp else 40.dp))

            Column(
                modifier = Modifier.widthIn(max = 440.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Hero
                Icon(
                    imageVector = Icons.Filled.Forum,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(88.dp).padding(12.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Discourse",
                    color = colors.textPrimary,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = when (ui.stage) {
                        LoginViewModel.Stage.SERVER -> "Sign in to Matrix"
                        LoginViewModel.Stage.METHODS -> ui.homeserverDisplayName
                    },
                    color = colors.textSecondary,
                    fontSize = 17.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(28.dp))

                when (ui.stage) {
                    LoginViewModel.Stage.SERVER -> ServerStage(
                        viewModel = viewModel,
                        ui = ui,
                        homeserverFocus = homeserverFocus,
                    )
                    LoginViewModel.Stage.METHODS -> MethodsStage(
                        viewModel = viewModel,
                        ui = ui,
                        usernameFocus = usernameFocus,
                        passwordFocus = passwordFocus,
                        onSubmitPassword = ::submitPassword,
                        onComplete = ::complete,
                    )
                }

                ui.errorMessage?.let { error ->
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = error,
                        color = Color(0xFFFF453A),
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

// MARK: Stage 1 — homeserver

@Composable
private fun ServerStage(
    viewModel: LoginViewModel,
    ui: LoginViewModel.UiState,
    homeserverFocus: FocusRequester,
) {
    val coroutineScope = rememberCoroutineScope()

    SectionHeader("Homeserver")
    FormTextField(
        value = ui.homeserver,
        onValueChange = viewModel::setHomeserver,
        placeholder = "matrix.org",
        enabled = !ui.isBusy,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = {
            coroutineScope.launch { viewModel.discoverMethods() }
        }),
        modifier = Modifier.focusRequester(homeserverFocus),
    )
    SectionFooter("The Matrix server your account lives on.")

    Spacer(Modifier.height(20.dp))
    ProminentButton(
        title = if (ui.isBusy) null else "Continue",
        enabled = !ui.isBusy,
        onClick = { coroutineScope.launch { viewModel.discoverMethods() } },
    )
}

// MARK: Stage 2 — auth methods

@Composable
private fun MethodsStage(
    viewModel: LoginViewModel,
    ui: LoginViewModel.UiState,
    usernameFocus: FocusRequester,
    passwordFocus: FocusRequester,
    onSubmitPassword: () -> Unit,
    onComplete: (LoginResult) -> Unit,
) {
    val colors = LocalDiscourseColors.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    fun browserLogin(kind: LoginViewModel.BrowserLoginKind) {
        coroutineScope.launch {
            viewModel.browserLogin(context, kind)?.let { onComplete(it) }
        }
    }

    if (ui.supportsOAuth) {
        BrowserButton(
            title = "Sign In with Browser",
            prominent = true,
            enabled = !ui.isBusy,
            onClick = { browserLogin(LoginViewModel.BrowserLoginKind.OAUTH) },
        )
    } else if (ui.supportsSso) {
        BrowserButton(
            title = "Sign In with SSO",
            prominent = !ui.supportsPassword,
            enabled = !ui.isBusy,
            onClick = { browserLogin(LoginViewModel.BrowserLoginKind.SSO) },
        )
    }

    if (ui.supportsPassword) {
        if (ui.supportsOAuth || ui.supportsSso) {
            Spacer(Modifier.height(24.dp))
            SectionHeader("Or sign in with a password")
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.bgElevated),
        ) {
            FormTextField(
                value = ui.username,
                onValueChange = viewModel::setUsername,
                placeholder = "@user:server",
                enabled = !ui.isBusy,
                grouped = true,
                keyboardOptions = KeyboardOptions(
                    // Autocapitalize/autocorrect would corrupt the user ID.
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                modifier = Modifier.focusRequester(usernameFocus),
            )
            HorizontalDivider(color = colors.separator, thickness = 1.dp)
            FormTextField(
                value = ui.password,
                onValueChange = viewModel::setPassword,
                placeholder = "Password",
                enabled = !ui.isBusy,
                grouped = true,
                isPassword = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { onSubmitPassword() }),
                modifier = Modifier.focusRequester(passwordFocus),
            )
        }

        Spacer(Modifier.height(20.dp))
        ProminentButton(
            title = if (ui.isBusy) null else "Sign In",
            enabled = ui.canSubmitPassword && !ui.isBusy,
            onClick = onSubmitPassword,
        )
    }

    if (!ui.supportsPassword && !ui.supportsOAuth && !ui.supportsSso) {
        Text(
            text = "This homeserver offers no supported sign-in method.",
            color = colors.textSecondary,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
    }

    Spacer(Modifier.height(16.dp))
    TextButton(onClick = viewModel::backToServerEntry) {
        Text(
            text = "Use a different homeserver",
            color = colors.textSecondary,
            fontSize = 15.sp,
        )
    }
}

// MARK: Form building blocks

@Composable
private fun SectionHeader(text: String) {
    val colors = LocalDiscourseColors.current
    Text(
        text = text.uppercase(),
        color = colors.textSecondary,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun SectionFooter(text: String) {
    val colors = LocalDiscourseColors.current
    Text(
        text = text,
        color = colors.textTertiary,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 6.dp),
    )
}

@Composable
private fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    modifier: Modifier = Modifier,
    grouped: Boolean = false,
    isPassword: Boolean = false,
) {
    val colors = LocalDiscourseColors.current
    val shape = if (grouped) RoundedCornerShape(0.dp) else RoundedCornerShape(12.dp)
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = colors.textTertiary) },
        enabled = enabled,
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        shape = shape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.bgElevated,
            unfocusedContainerColor = colors.bgElevated,
            disabledContainerColor = colors.bgElevated,
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
            disabledTextColor = colors.textSecondary,
            cursorColor = colors.accent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        modifier = modifier
            .fillMaxWidth()
            .then(if (grouped) Modifier else Modifier.clip(shape)),
    )
}

/** Full-width prominent (accent-filled) button; null title shows a spinner. */
@Composable
private fun ProminentButton(
    title: String?,
    enabled: Boolean,
    onClick: () -> Unit,
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
        if (title != null) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        } else {
            CircularProgressIndicator(
                color = colors.textOnAccent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Browser sign-in row: accent-filled when prominent, outlined otherwise. */
@Composable
private fun BrowserButton(
    title: String,
    prominent: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    val label: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Language,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.padding(start = 8.dp))
            Text(
                title,
                fontSize = 17.sp,
                fontWeight = if (prominent) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
    if (prominent) {
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
        ) { label() }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.accent),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { label() }
    }
}
