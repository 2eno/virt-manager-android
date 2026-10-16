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

    /** Profile waiting for the user to type a password. */
    val passwordRequest = MutableStateFlow<ConnectionProfile?>(null)
    val error = MutableStateFlow<String?>(null)

    private val _opened = Channel<String>(Channel.BUFFERED)
    val opened = _opened.receiveAsFlow()

    private var autoConnected = false

    fun autoConnect(list: List<ConnectionProfile>) {
        if (autoConnected) return
        autoConnected = true
        list.filter { it.autoConnect && !app.connections.needsPassword(it) }.forEach { p ->
            viewModelScope.launch { runCatching { app.connections.connect(p.id) } }
        }
    }

    fun open(profile: ConnectionProfile) {
        if (app.connections.get(profile.id) != null) {
            _opened.trySend(profile.id)
            return
        }
        if (app.connections.needsPassword(profile)) {
            passwordRequest.value = profile
            return
        }
        connect(profile, null)
    }

    fun connect(profile: ConnectionProfile, password: String?) {
        passwordRequest.value = null
        viewModelScope.launch {
            try {
                app.connections.connect(profile.id, password)
                _opened.send(profile.id)
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
