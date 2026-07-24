package com.riiiiiiiley.discourse.features.auth

import android.content.Context
import com.riiiiiiiley.discourse.core.LoginResult
import com.riiiiiiiley.discourse.core.MatrixService
import com.riiiiiiiley.discourse.core.MatrixServiceException
import com.riiiiiiiley.discourse.core.PendingLogin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class LoginViewModel(private val appContext: Context) {

    enum class Stage { SERVER, METHODS }

    enum class BrowserLoginKind { OAUTH, SSO }

    /**
     * The whole login form's observable state in one value; StateFlow's
     * equality conflation is the equality guard on every write.
     */
    data class UiState(
        val stage: Stage = Stage.SERVER,
        val homeserver: String = "matrix.org",
        val username: String = "",
        val password: String = "",
        val isBusy: Boolean = false,
        val errorMessage: String? = null,
        val supportsPassword: Boolean = false,
        val supportsOAuth: Boolean = false,
        val supportsSso: Boolean = false,
    ) {
        val homeserverDisplayName: String
            get() = homeserver.trim().ifEmpty { "matrix.org" }

        val canSubmitPassword: Boolean
            get() = username.trim().isNotEmpty() && password.isNotEmpty()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var pending: PendingLogin? = null

    fun setHomeserver(value: String) = update { it.copy(homeserver = value) }
    fun setUsername(value: String) = update { it.copy(username = value) }
    fun setPassword(value: String) = update { it.copy(password = value) }
    fun setError(message: String?) = update { it.copy(errorMessage = message) }

    private inline fun update(transform: (UiState) -> UiState) {
        _state.value = transform(_state.value)
    }

    // MARK: Stage transitions

    suspend fun discoverMethods() {
        if (_state.value.isBusy) return
        update { it.copy(isBusy = true, errorMessage = null) }
        try {
            val prepared = MatrixService.prepare(_state.value.homeserverDisplayName, appContext)
            pending = prepared
            update {
                it.copy(
                    stage = Stage.METHODS,
                    supportsPassword = prepared.supportsPassword,
                    supportsOAuth = prepared.supportsOAuth,
                    supportsSso = prepared.supportsSso,
                )
            }
        } catch (error: Exception) {
            update {
                it.copy(errorMessage =
                    "Couldn't reach ${it.homeserverDisplayName}: ${error.message ?: error}")
            }
        } finally {
            update { it.copy(isBusy = false) }
        }
    }

    fun backToServerEntry() {
        pending = null
        update {
            it.copy(
                stage = Stage.SERVER,
                errorMessage = null,
                password = "",
                supportsPassword = false,
                supportsOAuth = false,
                supportsSso = false,
            )
        }
    }

    // MARK: Auth methods

    suspend fun passwordLogin(): LoginResult? {
        val pending = pending ?: return null
        if (!_state.value.canSubmitPassword || _state.value.isBusy) return null
        update { it.copy(isBusy = true, errorMessage = null) }
        return try {
            pending.finishWithPassword(
                username = _state.value.username.trim(),
                password = _state.value.password,
            )
        } catch (error: Exception) {
            update { it.copy(errorMessage = friendlyMessage(error)) }
            null
        } finally {
            update { it.copy(isBusy = false) }
        }
    }

    suspend fun browserLogin(context: Context, kind: BrowserLoginKind): LoginResult? {
        val pending = pending ?: return null
        if (_state.value.isBusy) return null
        update { it.copy(isBusy = true, errorMessage = null) }
        return try {
            val url = when (kind) {
                BrowserLoginKind.OAUTH -> pending.startOAuth()
                BrowserLoginKind.SSO -> pending.startSso()
            }
            val callback = WebAuthSession.authenticate(
                context = context, url = url, callbackScheme = PendingLogin.callbackScheme)
            when (kind) {
                BrowserLoginKind.OAUTH -> pending.finishOAuth(callbackUrl = callback.toString())
                BrowserLoginKind.SSO -> pending.finishSso(callbackUrl = callback.toString())
            }
        } catch (error: Exception) {
            if (kind == BrowserLoginKind.OAUTH) {
                runCatching { pending.abortOAuth() }
            }
            if (!isUserCancellation(error)) {
                update { it.copy(errorMessage = "Sign-in failed: ${error.message ?: error}") }
            }
            null
        } finally {
            update { it.copy(isBusy = false) }
        }
    }

    // MARK: Helpers

    private fun isUserCancellation(error: Exception): Boolean =
        error is WebAuthSession.UserCancelledException

    private fun friendlyMessage(error: Exception): String {
        if (error is MatrixServiceException) return error.message ?: "Sign-in failed."
        val text = error.toString()
        if (text.contains("forbidden", ignoreCase = true) || text.contains("M_FORBIDDEN")) {
            return "Incorrect username or password."
        }
        return "Sign-in failed: ${error.message ?: error}"
    }
}
