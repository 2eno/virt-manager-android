package dev.virtmanager.android.screenshots

import android.os.Looper
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
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
import org.junit.Assert.assertFalse
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
        listOf("AI-Chat", "AI-Chat2", "AI-Chat3", "custom-vm", "renamed", "hw-vm", "setup-vm").forEach(::cleanup)
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

    /** Clicks the first node whose text starts with [prefix], e.g. "NIC " for "NIC :12:34:56". */
    private fun clickPrefix(prefix: String) {
        waitForText(prefix)
        val node = compose.onAllNodes(hasText(prefix, substring = true), useUnmergedTree = true).onFirst()
        runCatching { node.performScrollTo() }
        node.performClick()
        idle()
    }

    /** Clicks the last matching node: the button, not a sentence that mentions it. */
    private fun clickButton(text: String) {
        waitForText(text)
        val node = compose.onAllNodesWithText(text, useUnmergedTree = true).let { it[it.fetchSemanticsNodes().size - 1] }
        runCatching { node.performScrollTo() }
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
        shot("8-start-failed-on-vm-page")
        assertEquals("shut off", virsh("domstate", "AI-Chat3").trim())
        click("Dismiss")
        click("Hardware")
        clickPrefix("NIC ")
        waitForText("Network source")
        click("default")
        click("Apply")
        waitForText("Boot options") // back on the VM page's hardware list
        assertTrue(virsh("dumpxml", "--inactive", "AI-Chat3").contains("network='default'"))
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
        waitForText("Boot options")
        shot("5-hardware-list")
        clickPrefix("NIC ")
        waitForText("Device model")
        click("e1000e")
        shot("6-nic-page")
        click("Apply")
        waitForText("Boot options")
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
        shot("7-xml-rename-refused")
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

    @Test fun hardwarePages() {
        connect()
        wizardTo4("hw-vm")
        click("Customize configuration before install")
        click("Customize")
        waitForText("Begin installation")

        // Overview: title + firmware (allowed while shut off)
        click("Hardware")
        waitForText("Boot options")
        click("Overview")
        waitForText("Hypervisor details")
        compose.onAllNodes(hasText("Title")).onFirst().performTextInput("Edited on a phone")
        click("UEFI")
        shot("9-overview-page")
        click("Apply")
        waitForText("Boot options")
        var xml = virsh("dumpxml", "--inactive", "hw-vm")
        assertTrue(xml, xml.contains("<title>Edited on a phone</title>") && xml.contains("firmware='efi'"))

        // CPUs: 1 of 4 vCPUs with a manual topology
        click("CPUs")
        waitForText("vCPU allocation")
        compose.onAllNodes(hasText("Maximum allocation")).onFirst().performTextReplacement("4")
        compose.onAllNodes(hasText("Current allocation")).onFirst().performTextReplacement("1")
        click("Manually set CPU topology")
        compose.onAllNodes(hasText("Cores")).onFirst().performTextReplacement("2")
        compose.onAllNodes(hasText("Threads")).onFirst().performTextReplacement("2")
        click("Apply")
        waitForText("Boot options")
        xml = virsh("dumpxml", "--inactive", "hw-vm")
        assertTrue(xml, xml.contains("current='1'>4</vcpu>") && xml.contains("cores='2' threads='2'"))

        // Boot options: CD-ROM first, boot menu on
        click("Boot options")
        waitForText("Boot device order")
        click("Enable boot menu")
        click("Apply")
        waitForText("Boot options")
        assertTrue(virsh("dumpxml", "--inactive", "hw-vm").contains("<bootmenu enable='yes'/>"))

        // Add Hardware: a sound card and a new 1 GiB disk
        clickButton("Add Hardware")
        waitForText("VirtIO VSOCK")
        shot("10-add-hardware")
        click("Sound")
        click("ac97")
        click("Finish")
        waitForText("Sound ac97")
        clickButton("Add Hardware")
        click("Storage")
        compose.onAllNodes(hasText("Size (GiB)")).onFirst().performTextReplacement("1")
        shot("11-add-storage")
        click("Finish")
        waitForText("VirtIO Disk 2")
        xml = virsh("dumpxml", "--inactive", "hw-vm")
        assertTrue(xml, xml.contains("model='ac97'") && xml.contains("hw-vm-1.qcow2"))

        // Remove the sound card again, via its page
        click("Sound ac97")
        waitForText("Model")
        compose.onNodeWithContentDescription("Remove").performClick()
        click("Remove")
        waitForText("Boot options")
        assertFalse(virsh("dumpxml", "--inactive", "hw-vm").contains("model='ac97'"))

        // Per-device XML tab
        click("RNG /dev/urandom")
        click("XML")
        val rngXml = compose.onAllNodes(hasText("<rng", substring = true)).onFirst()
        rngXml.performTextReplacement("<rng model='virtio'><backend model='random'>/dev/random</backend></rng>")
        click("Apply")
        waitForText("RNG /dev/random")

        // Cancel installation deletes the VM and its disks, but not the ISO.
        click("Cancel installation")
        waitForText("Install media (ISO files) are kept")
        clickButton("Cancel installation") // the dialog's confirm button
        compose.waitUntil(60_000) { idle(); virsh("list", "--all", "--name").lines().none { it == "hw-vm" } }
        val vols = virsh("vol-list", "default")
        assertFalse(vols, vols.contains("hw-vm.qcow2") || vols.contains("hw-vm-1.qcow2"))
        assertTrue(vols.contains("debian-13-netinst.iso"))
    }

    private fun back() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        idle()
    }

    @Test fun unfinishedSetupAndPciDevices() {
        connect()
        wizardTo4("setup-vm")
        click("Customize configuration before install")
        click("Customize")
        waitForText("Begin installation")

        // Every PCI device of the host is offered, described even without the PCI ID database.
        click("Hardware")
        waitForText("Boot options")
        clickButton("Add Hardware")
        click("PCI Host Device")
        val count = virsh("nodedev-list", "--cap", "pci").lines().count { it.isNotBlank() }
        waitForText("$count of $count PCI devices")
        waitForText("Intel Corporation Host bridge")
        shot("12-pci-devices")
        compose.onAllNodes(hasText("Search")).onFirst().performTextInput("Ethernet")
        waitForText("1 of $count PCI devices")
        click("Red Hat, Inc. Ethernet controller")
        click("Finish")
        waitForText("PCI 0000:00:08.0")
        assertTrue(virsh("dumpxml", "--inactive", "setup-vm").contains("slot='0x08'"))
        click("PCI 0000:00:08.0")
        waitForText("Red Hat, Inc. Ethernet controller")
        assertTrue(compose.onAllNodesWithText("already attached", substring = true).fetchSemanticsNodes().isEmpty())
        shot("13-pci-device-page")
        back()
        waitForText("Boot options")

        // Leaving asks first; "Finish later" keeps the VM, marked as not set up.
        back()
        waitForText("Leave setup of")
        shot("14-leave-setup")
        click("Finish later")
        waitForText("Setup not finished")
        shot("15-setup-not-finished")

        // The setup can be resumed from the list: the page knows it from the VM's metadata.
        click("setup-vm")
        waitForText("Begin installation")
        back()
        click("Cancel installation") // in the leave dialog
        waitForText("Install media (ISO files) are kept")
        clickButton("Cancel installation")
        compose.waitUntil(60_000) { idle(); virsh("list", "--all", "--name").lines().none { it == "setup-vm" } }
        waitForText("New VM")
        assertFalse(virsh("vol-list", "default").contains("setup-vm.qcow2"))
        assertTrue(virsh("vol-list", "default").contains("debian-13-netinst.iso"))
    }
}
