package dev.virtmanager.android.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.virtmanager.android.VirtManagerApp
import dev.virtmanager.android.data.ConnectionManager
import dev.virtmanager.android.data.ConnectionProfile
import dev.virtmanager.android.data.ThemeMode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ConnectionsViewModel(private val app: VirtManagerApp) : ViewModel() {
    val profiles: StateFlow<List<ConnectionProfile>?> =
        app.repository.connections.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val status = app.connections.status
    val themeMode = app.repository.themeMode.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    /** A connection waiting for the user to type the password of [hop] (it or one of its jump hosts). */
    data class PasswordRequest(val profile: ConnectionProfile, val hop: ConnectionProfile)

    val passwordRequest = MutableStateFlow<PasswordRequest?>(null)
    val error = MutableStateFlow<String?>(null)

    private val _opened = Channel<String>(Channel.BUFFERED)
    val opened = _opened.receiveAsFlow()

    private var autoConnected = false

    fun autoConnect(list: List<ConnectionProfile>) {
        if (autoConnected) return
        autoConnected = true
        list.filter { it.autoConnect && app.connections.passwordNeededFor(it, list) == null }.forEach { p ->
            viewModelScope.launch { runCatching { app.connections.connect(p.id) } }
        }
    }

    fun open(profile: ConnectionProfile) {
        if (app.connections.get(profile.id) != null) {
            if (!profile.sshOnly) _opened.trySend(profile.id)
            return
        }
        app.connections.passwordNeededFor(profile, profiles.value.orEmpty())?.let { hop ->
            passwordRequest.value = PasswordRequest(profile, hop)
            return
        }
        connect(profile)
    }

    /** The user typed the password asked for by [request]; asks for the next hop's or connects. */
    fun passwordEntered(request: PasswordRequest, password: String) {
        passwordRequest.value = null
        app.connections.rememberPassword(request.hop.id, password)
        open(request.profile)
    }

    private fun connect(profile: ConnectionProfile) {
        viewModelScope.launch {
            try {
                app.connections.connect(profile.id)
                // A plain SSH host has no hypervisor to show; its card just turns "Connected".
                if (!profile.sshOnly) _opened.send(profile.id)
            } catch (t: Throwable) {
                error.value = "${profile.name}: ${ConnectionManager.friendly(t)}"
            }
        }
    }

    fun disconnect(id: String) = app.connections.disconnect(id)

    fun delete(profile: ConnectionProfile) {
        app.connections.disconnect(profile.id)
        viewModelScope.launch {
            app.repository.delete(profile.id)
            app.repository.forgetHostKeys(profile.host, profile.port)
        }
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { app.repository.setThemeMode(mode) }
    }
}
