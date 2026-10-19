package dev.virtmanager.android.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.virtmanager.android.ui.components.HostKeyDialog
import dev.virtmanager.android.ui.connections.ConnectionEditScreen
import dev.virtmanager.android.ui.connections.ConnectionsScreen
import dev.virtmanager.android.ui.console.SerialConsoleScreen
import dev.virtmanager.android.ui.console.VncScreen
import dev.virtmanager.android.ui.create.NewVmScreen
import dev.virtmanager.android.ui.host.HostScreen
import dev.virtmanager.android.ui.vm.VmDetailScreen
import dev.virtmanager.android.ui.vm.XmlEditorScreen
import kotlinx.serialization.Serializable

@Serializable object ConnectionsRoute
@Serializable data class EditConnectionRoute(val id: String? = null)
@Serializable data class HostRoute(val connectionId: String)
@Serializable data class VmRoute(
    val connectionId: String,
    val name: String,
    /** Shown as an error banner on arrival, e.g. why a freshly created VM didn't start. */
    val notice: String? = null,
    /** Opened from the New VM wizard's "Customize configuration before install". */
    val customize: Boolean = false,
)
@Serializable data class XmlEditRoute(val connectionId: String, val name: String)
@Serializable data class NewVmRoute(val connectionId: String)
@Serializable data class VncRoute(val connectionId: String, val name: String)
@Serializable data class SerialRoute(val connectionId: String, val name: String)

@Composable
fun AppNavigation() {
    val nav = rememberNavController()
    val app = app()
    val hostKeyPrompt by app.connections.hostKeyPrompt.collectAsState()

    NavHost(
        navController = nav,
        startDestination = ConnectionsRoute,
        enterTransition = { slideInHorizontally(tween(280)) { it / 4 } + fadeIn(tween(280)) },
        exitTransition = { fadeOut(tween(200)) },
        popEnterTransition = { fadeIn(tween(280)) },
        popExitTransition = { slideOutHorizontally(tween(240)) { it / 4 } + fadeOut(tween(240)) },
    ) {
        composable<ConnectionsRoute> {
            ConnectionsScreen(
                onAdd = { nav.navigate(EditConnectionRoute()) },
                onEdit = { nav.navigate(EditConnectionRoute(it)) },
                onOpen = { nav.navigate(HostRoute(it)) { launchSingleTop = true } },
            )
        }
        composable<EditConnectionRoute> { entry ->
            ConnectionEditScreen(entry.toRoute<EditConnectionRoute>().id, onDone = { nav.popBackStack() })
        }
        composable<HostRoute> { entry ->
            val r = entry.toRoute<HostRoute>()
            HostScreen(
                connectionId = r.connectionId,
                onBack = { nav.popBackStack() },
                onOpenVm = { nav.navigate(VmRoute(r.connectionId, it)) },
                onNewVm = { nav.navigate(NewVmRoute(r.connectionId)) },
                onConsole = { nav.navigate(VncRoute(r.connectionId, it)) },
            )
        }
        composable<VmRoute> { entry ->
            val r = entry.toRoute<VmRoute>()
            VmDetailScreen(
                connectionId = r.connectionId,
                vmName = r.name,
                notice = r.notice,
                customize = r.customize,
                onBack = { nav.popBackStack() },
                onConsole = { nav.navigate(VncRoute(r.connectionId, it)) },
                onSerial = { nav.navigate(SerialRoute(r.connectionId, it)) },
                onEditXml = { nav.navigate(XmlEditRoute(r.connectionId, it)) },
            )
        }
        composable<XmlEditRoute> { entry ->
            val r = entry.toRoute<XmlEditRoute>()
            XmlEditorScreen(r.connectionId, r.name, onDone = { nav.popBackStack() })
        }
        composable<NewVmRoute> { entry ->
            val r = entry.toRoute<NewVmRoute>()
            NewVmScreen(r.connectionId) { created ->
                nav.popBackStack()
                if (created != null) {
                    nav.navigate(VmRoute(r.connectionId, created.name, created.startError, created.customize))
                }
            }
        }
        composable<VncRoute> { entry ->
            val r = entry.toRoute<VncRoute>()
            VncScreen(r.connectionId, r.name, onBack = { nav.popBackStack() })
        }
        composable<SerialRoute> { entry ->
            val r = entry.toRoute<SerialRoute>()
            SerialConsoleScreen(r.connectionId, r.name, onBack = { nav.popBackStack() })
        }
    }

    hostKeyPrompt?.let { HostKeyDialog(it) }
}
