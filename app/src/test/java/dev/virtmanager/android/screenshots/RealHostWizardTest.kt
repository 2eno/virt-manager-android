package dev.virtmanager.android.screenshots

import android.os.Looper
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import com.github.takahirom.roborazzi.captureRoboImage
import dev.virtmanager.android.MainActivity
import dev.virtmanager.android.VirtManagerApp
import dev.virtmanager.android.data.AuthMethod
import dev.virtmanager.android.data.ConnectionProfile
import dev.virtmanager.android.data.SecretCipher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
import java.util.concurrent.TimeUnit

/**
 * Runs the New VM wizard end to end against a real libvirtd (QEMU), reproducing the failures
 * reported from a real host: a bridge that doesn't exist, an inactive "default" network, a
 * leftover disk image, and "Customize configuration before install".
 *
 * Opt-in and destructive for its own VMs only: needs VMA_IT_SSH_PORT / VMA_IT_SSH_KEY (root on
 * 127.0.0.1) and VMA_IT_REAL_URI (e.g. qemu:///system) with a "default" pool containing an ISO.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi", application = VirtManagerApp::class)
class RealHostWizardTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val port = System.getenv("VMA_IT_SSH_PORT")?.toIntOrNull()
    private val key = System.getenv("VMA_IT_SSH_KEY")
    private val uri = System.getenv("VMA_IT_REAL_URI")
    private val out = File("build/real-host-screens").apply { mkdirs() }
    private val app get() = compose.activity.application as VirtManagerApp

    @Before fun setUp() {
        assumeTrue(port != null && key != null && uri != null)
        FakeAndroidKeyStore.install()
        listOf("AI-Chat", "AI-Chat2", "AI-Chat3", "custom-vm", "renamed").forEach(::cleanup)
        // A libvirt network that starts fine but points at a bridge the host doesn't have: guests
        // on it fail with the reported "Cannot get interface MTU on 'br0': No such device".
        virsh("net-destroy", "broken"); virsh("net-undefine", "broken")
        val net = File.createTempFile("broken", ".xml").apply {
            writeText("<network><name>broken</name><forward mode='bridge'/><bridge name='br0'/></network>")
        }
        virsh("net-define", net.path)
        virsh("net-destroy", "default") // start each run like the reported host: network down
    }

    /** Runs virsh on the host directly, independent of the app, to check what really happened. */
    private fun virsh(vararg args: String): String {
        val p = ProcessBuilder(listOf("virsh", "-q", "-c", uri!!) + args).redirectErrorStream(true).start()
        p.waitFor(60, TimeUnit.SECONDS)
        return p.inputStream.bufferedReader().readText()
    }

    private fun cleanup(name: String) {
        virsh("destroy", name)
        // Not --remove-all-storage: that would also delete the shared installer ISO.
        virsh("undefine", name, "--nvram")
        virsh("vol-delete", "--pool", "default", "$name.qcow2")
        virsh("vol-delete", "--pool", "default", "$name-1.qcow2")
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(out, "$name.png").path)
    }

    private fun idle() = repeat(5) { Thread.sleep(100); shadowOf(Looper.getMainLooper()).idle() }

    private fun waitForText(text: String, timeoutMs: Long = 60_000) {
        try {
            compose.waitUntil(timeoutMs) {
                shadowOf(Looper.getMainLooper()).idle()
                compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            throw AssertionError("'$text' not found. Tree:\n" + runCatching { compose.onRoot(useUnmergedTree = true).printToString() }.getOrDefault("?"), e)
        }
    }

    private fun click(text: String) {
        waitForText(text)
        val node = compose.onAllNodesWithText(text, useUnmergedTree = true).onFirst()
        runCatching { node.performScrollTo() } // only possible inside scrolling containers
        node.performClick()
        idle()
    }

    private fun connect() {
        runBlocking {
            app.repository.upsert(
                ConnectionProfile(
                    id = "real", name = "real-host", host = "127.0.0.1", port = port!!, username = "root",
                    authMethod = AuthMethod.KEY, libvirtUri = uri!!,
                    encryptedPrivateKey = SecretCipher.encrypt(File(key!!).readText()),
                ),
            )
        }
        click("real-host")
        // The host key prompt only appears the first time (trust persists across tests).
        compose.waitUntil(60_000) {
            shadowOf(Looper.getMainLooper()).idle()
            listOf("Unknown host", "New VM").any {
                compose.onAllNodesWithText(it, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        }
        if (compose.onAllNodesWithText("Unknown host").fetchSemanticsNodes().isNotEmpty()) click("Trust")
        waitForText("New VM")
    }

    /** Fills steps 1-3 of the wizard and stops on step 4. */
    private fun wizardTo4(name: String) {
        click("New VM")
        waitForText("Operating system") // step 1 content, i.e. the host has been queried
        compose.onAllNodes(hasText("Name")).onFirst().performTextInput(name)
        click("debian-13-netinst.iso")
        click("Next"); click("Next"); click("Next")
        waitForText("Network & finish")
    }

    @Test fun reportedFailuresAreHandled() {
        connect()

        // 1. A bridge that doesn't exist is caught before anything is created.
        wizardTo4("AI-Chat")
        click("Bridge device")
        compose.onNodeWithText("Bridge name", useUnmergedTree = true).performClick()
        compose.onAllNodes(hasText("Bridge name")).onFirst().performTextClearance()
        compose.onAllNodes(hasText("Bridge name")).onFirst().performTextInput("br0")
        idle()
        waitForText("No bridges found on the host")
        compose.onNodeWithText("Create").assertIsNotEnabled()
        shot("1-bridge-validation")
        assertEquals("", virsh("list", "--all", "--name").lines().filter { it == "AI-Chat" }.joinToString())

        // 2. The inactive "default" network is started with the VM, and we land on its page.
        click("Virtual network \"default\"")
        waitForText("started together with the VM")
        click("Create")
        waitForText("Basic details")
        shot("2-created-and-running")
        assertTrue(virsh("domstate", "AI-Chat").trim(), virsh("domstate", "AI-Chat").trim() == "running")
        assertTrue(virsh("net-list", "--name").lines().contains("default"))
        assertTrue(virsh("vol-list", "default").contains("AI-Chat.qcow2"))

        // 3. A leftover disk image from an earlier attempt gets a new, unique name.
        virsh("vol-create-as", "default", "AI-Chat2.qcow2", "1M")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitForText("New VM")
        wizardTo4("AI-Chat2")
        click("Start after creation") // just define it
        click("Create")
        waitForText("Basic details")
        val xml = virsh("dumpxml", "AI-Chat2")
        assertTrue(xml, xml.contains("AI-Chat2-1.qcow2"))

        // 4. Defined but failing to start: we land on the VM's page with the reason, and can fix
        //    the network card there instead of retrying the wizard.
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitForText("New VM")
        wizardTo4("AI-Chat3")
        click("Virtual network \"broken\"")
        click("Create")
        waitForText("Created, but it failed to start")
        waitForText("Cannot get interface MTU on 'br0'")
        shot("7-start-failed-on-vm-page")
        assertEquals("shut off", virsh("domstate", "AI-Chat3").trim())
        click("Dismiss")
        click("Hardware")
        click("Change…")
        click("Virtual network \"default\"")
        click("Apply")
        waitForText("Network card updated")
        click("Start")
        compose.waitUntil(60_000) { idle(); virsh("domstate", "AI-Chat3").trim() == "running" }
    }

    @Test fun customizeBeforeInstall() {
        connect()
        wizardTo4("custom-vm")
        click("Customize configuration before install")
        click("Customize")
        waitForText("Begin installation")
        assertEquals("shut off", virsh("domstate", "custom-vm").trim())
        shot("3-customize-banner")

        // Network card: switch the model (the only network here is "default").
        click("Hardware")
        waitForText("Processor & memory")
        click("Change…")
        waitForText("Device model")
        click("e1000e")
        shot("5-network-dialog")
        click("Apply")
        waitForText("Network card updated")
        assertTrue(virsh("dumpxml", "--inactive", "custom-vm").contains("<model type='e1000e'/>"))

        // XML editor: a rename is refused and the draft kept; a valid change is saved.
        click("Settings")
        click("Edit XML…")
        waitForText("<domain")
        val xml = virsh("dumpxml", "--inactive", "custom-vm")
        val editor = compose.onAllNodes(hasText("<domain", substring = true)).onFirst()
        editor.performTextReplacement(xml.replace("<name>custom-vm</name>", "<name>renamed</name>"))
        click("Save")
        waitForText("Use Rename instead")
        shot("6-xml-rename-refused")
        editor.performTextReplacement(xml.replace("<description>", "<description>").let {
            if (it.contains("<description>")) it else it.replace("</uuid>", "</uuid>\n  <description>edited on Android</description>")
        })
        click("Save")
        waitForText("Begin installation")
        assertTrue(virsh("dumpxml", "--inactive", "custom-vm").contains("edited on Android"))
        assertTrue(virsh("list", "--all", "--name").lines().none { it == "renamed" })

        click("Begin installation")
        // Like virt-manager, beginning the installation opens the VM's console.
        waitForText("Hide controls", 90_000)
        waitForText("720×400", 60_000) // QEMU's display, through the SSH tunnel
        repeat(10) { Thread.sleep(300); idle() }
        shot("4-console-after-begin")
        assertEquals("running", virsh("domstate", "custom-vm").trim())
        assertTrue(virsh("net-list", "--name").lines().contains("default"))
    }
}
