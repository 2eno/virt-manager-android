package dev.virtmanager.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.virtmanager.android.ssh.KnownHosts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "virt_manager")

class ConnectionRepository(private val context: Context) : KnownHosts {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val profilesKey = stringPreferencesKey("connections")
    private val knownHostsKey = stringPreferencesKey("known_hosts")
    private val themeKey = stringPreferencesKey("theme")

    private val listSerializer = ListSerializer(ConnectionProfile.serializer())
    private val hostsSerializer = MapSerializer(String.serializer(), String.serializer())

    val connections: Flow<List<ConnectionProfile>> = context.dataStore.data.map { prefs ->
        prefs[profilesKey]?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() } ?: emptyList()
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        prefs[themeKey]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[themeKey] = mode.name }
    }

    suspend fun get(id: String): ConnectionProfile? = connections.first().firstOrNull { it.id == id }

    suspend fun upsert(profile: ConnectionProfile) {
        context.dataStore.edit { prefs ->
            val current = prefs[profilesKey]?.let { json.decodeFromString(listSerializer, it) } ?: emptyList()
            val updated = if (current.any { it.id == profile.id }) current.map { if (it.id == profile.id) profile else it }
            else current + profile
            prefs[profilesKey] = json.encodeToString(listSerializer, updated)
        }
    }

    suspend fun delete(id: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[profilesKey]?.let { json.decodeFromString(listSerializer, it) } ?: emptyList()
            prefs[profilesKey] = json.encodeToString(listSerializer, current.filterNot { it.id == id })
        }
    }

    // --- Known hosts (called from JSch on an IO thread, so blocking is fine) ---------------

    private fun hosts(): Map<String, String> = runBlocking {
        context.dataStore.data.first()[knownHostsKey]?.let {
            runCatching { json.decodeFromString(hostsSerializer, it) }.getOrNull()
        } ?: emptyMap()
    }

    override fun lookup(hostId: String): Pair<String, String>? =
        hosts()[hostId]?.let { it.substringBefore(' ') to it.substringAfter(' ') }

    override fun save(hostId: String, keyType: String, key: String) {
        runBlocking {
            context.dataStore.edit { prefs ->
                val map = prefs[knownHostsKey]?.let { json.decodeFromString(hostsSerializer, it) } ?: emptyMap()
                prefs[knownHostsKey] = json.encodeToString(hostsSerializer, map + (hostId to "$keyType $key"))
            }
        }
    }

    suspend fun forgetHostKeys(host: String, port: Int) {
        context.dataStore.edit { prefs ->
            val map = prefs[knownHostsKey]?.let { json.decodeFromString(hostsSerializer, it) } ?: emptyMap()
            prefs[knownHostsKey] = json.encodeToString(hostsSerializer, map - "[$host]:$port")
        }
    }
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }
