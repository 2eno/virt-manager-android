package dev.virtmanager.android.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.ui.graphics.vector.ImageVector
import dev.virtmanager.android.libvirt.DomainState
import dev.virtmanager.android.libvirt.VirshClient

/** The lifecycle actions virt-manager offers in its toolbar / context menu. */
enum class DomainAction(
    val label: String,
    val icon: ImageVector,
    val confirm: String? = null,
    val run: suspend VirshClient.(String) -> Unit,
) {
    START("Start", Icons.Outlined.PlayArrow, run = { startWithNetworks(it) }),
    RESUME("Resume", Icons.Outlined.PlayArrow, run = { resume(it) }),
    PAUSE("Pause", Icons.Outlined.Pause, run = { suspend(it) }),
    SHUTDOWN("Shut down", Icons.Outlined.PowerSettingsNew, run = { shutdown(it) }),
    REBOOT("Reboot", Icons.Outlined.RestartAlt, run = { reboot(it) }),
    RESET(
        "Reset", Icons.Outlined.Replay,
        confirm = "Resetting is like pressing the reset button: unsaved data in the guest may be lost.",
        run = { reset(it) },
    ),
    FORCE_OFF(
        "Force off", Icons.Outlined.Bolt,
        confirm = "Forcing off is like pulling the power cord: unsaved data in the guest may be lost.",
        run = { destroy(it) },
    ),
    SAVE(
        "Save state", Icons.Outlined.Bedtime,
        confirm = "The VM's memory is saved to disk and it stops. Starting it again restores it.",
        run = { managedSave(it) },
    );

    companion object {
        fun primaryFor(state: DomainState): DomainAction? = when (state) {
            DomainState.SHUTOFF, DomainState.CRASHED, DomainState.NO_STATE -> START
            DomainState.PAUSED, DomainState.PM_SUSPENDED -> RESUME
            DomainState.RUNNING, DomainState.BLOCKED -> SHUTDOWN
            DomainState.SHUTTING_DOWN -> FORCE_OFF
        }

        fun availableFor(state: DomainState): List<DomainAction> = when (state) {
            DomainState.SHUTOFF, DomainState.NO_STATE -> listOf(START)
            DomainState.CRASHED -> listOf(START, FORCE_OFF)
            DomainState.PAUSED, DomainState.PM_SUSPENDED -> listOf(RESUME, FORCE_OFF, SAVE)
            DomainState.RUNNING, DomainState.BLOCKED -> listOf(SHUTDOWN, REBOOT, PAUSE, SAVE, RESET, FORCE_OFF)
            DomainState.SHUTTING_DOWN -> listOf(FORCE_OFF)
        }
    }
}
