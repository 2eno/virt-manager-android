package dev.virtmanager.android.screenshots

import android.os.Looper
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import com.github.takahirom.roborazzi.captureRoboImage
import dev.virtmanager.android.MainActivity
import dev.virtmanager.android.VirtManagerApp
import dev.virtmanager.android.data.AuthMethod
import dev.virtmanager.android.data.ConnectionProfile
import dev.virtmanager.android.data.SecretCipher
import dev.virtmanager.android.data.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * Drives the real app (real SSH, real virsh against libvirt's test driver) under Robolectric and
 * records screenshots into docs/screenshots. Opt-in: needs VMA_IT_SSH_PORT, VMA_IT_SSH_KEY and
 * VMA_IT_TESTNODE (path of a libvirt test-driver node XML on that host).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi", application = VirtManagerApp::class)
class AppScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val port = System.getenv("VMA_IT_SSH_PORT")?.toIntOrNull()
    private val key = System.getenv("VMA_IT_SSH_KEY")
    private val node = System.getenv("VMA_IT_TESTNODE")
    private val out = File("../docs/screenshots").apply { mkdirs() }

    private val app get() = compose.activity.application as VirtManagerApp

    @Before fun setUp() {
        assumeTrue(port != null && key != null && node != null)
        FakeAndroidKeyStore.install()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(out, "$name.png").path)
    }

    /** Lets real time pass (for counters on the host) and runs the app's 2 s poll loop. */
    private fun tick(times: Int) = repeat(times) {
        Thread.sleep(500)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000))
        repeat(8) {
            Thread.sleep(100)
            shadowOf(Looper.getMainLooper()).idle()
        }
        compose.waitForIdle()
    }

    private fun waitForText(text: String, timeoutMs: Long = 20_000) {
        try {
            compose.waitUntil(timeoutMs) {
                shadowOf(Looper.getMainLooper()).idle()
                compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            compose.onRoot().captureRoboImage(File(out, "debug-${text.filter(Char::isLetterOrDigit)}.png").path)
            throw AssertionError("'$text' not found. Tree:\n" + compose.onRoot(useUnmergedTree = true).printToString(), e)
        }
    }

    @Test fun tour() {
        runBlocking {
            app.repository.upsert(
                ConnectionProfile(
                    id = "homelab", name = "homelab", host = "127.0.0.1", port = port!!, username = "root",
                    authMethod = AuthMethod.KEY, libvirtUri = "test://$node",
                    encryptedPrivateKey = SecretCipher.encrypt(File(key!!).readText()),
                ),
            )
            app.repository.upsert(
                ConnectionProfile(id = "office", name = "office-server", host = "10.0.20.15", username = "admin", useSudo = true),
            )
        }
        waitForText("homelab")
        shot("01-connections")

        compose.onNodeWithText("homelab").performClick()
        waitForText("Unknown host")
        shot("02-host-key")
        compose.onNodeWithText("Trust").performClick()

        waitForText("web-frontend")
        tick(12)
        shot("03-machines")

        runBlocking { app.repository.setThemeMode(ThemeMode.DARK) }
        tick(1)
        shot("03-machines-dark")
        runBlocking { app.repository.setThemeMode(ThemeMode.SYSTEM) }
        tick(1)

        compose.onNodeWithText("Host").performClick()
        tick(1)
        shot("04-host-overview")

        compose.onNodeWithText("Networks").performClick()
        waitForText("virbr0")
        shot("05-networks")

        compose.onNodeWithText("Storage").performClick()
        waitForText("Volumes")
        compose.onNodeWithText("Volumes").performClick()
        waitForText("postgres-db.qcow2")
        shot("06-storage")

        compose.onNodeWithText("Machines").performClick()
        waitForText("postgres-db")
        compose.onNodeWithText("postgres-db").performClick()
        waitForText("Basic details")
        tick(6)
        shot("07-vm-overview")

        compose.onNodeWithText("Hardware").performClick()
        waitForText("Processor & memory")
        shot("08-vm-hardware")

        compose.onNodeWithContentDescription("Back").performClick()
        waitForText("New VM")
        compose.onNodeWithText("New VM", useUnmergedTree = true).performClick()
        waitForText("Install media")
        compose.onAllNodes(hasText("Name")).onFirst().performTextInput("fedora-workstation")
        compose.onNodeWithText("debian-12.7.0-amd64-netinst.iso").performClick()
        shot("09-new-vm-media")
        compose.onNodeWithText("Next").performClick()
        waitForText("Memory")
        shot("10-new-vm-resources")
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Next").performClick()
        waitForText("Summary")
        shot("11-new-vm-summary")
    }

    @Test fun editor() {
        waitForText("Add connection")
        compose.onNodeWithText("Add connection", useUnmergedTree = true).performClick()
        waitForText("libvirt URI on the host")
        compose.onNodeWithText("SSH key").performClick()
        shot("12-add-connection")
    }
}
