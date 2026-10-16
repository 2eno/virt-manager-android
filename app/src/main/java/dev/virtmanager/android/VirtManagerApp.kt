package dev.virtmanager.android

import android.app.Application
import dev.virtmanager.android.data.ConnectionManager
import dev.virtmanager.android.data.ConnectionRepository

class VirtManagerApp : Application() {
    lateinit var repository: ConnectionRepository
        private set
    lateinit var connections: ConnectionManager
        private set

    override fun onCreate() {
        super.onCreate()
        repository = ConnectionRepository(this)
        connections = ConnectionManager(repository)
    }
}
