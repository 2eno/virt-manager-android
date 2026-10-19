package dev.virtmanager.android.libvirt.config

import dev.virtmanager.android.libvirt.CommandRunner
import dev.virtmanager.android.libvirt.DomainXmlBuilder
import dev.virtmanager.android.libvirt.ExecResult
import dev.virtmanager.android.libvirt.Firmware
import dev.virtmanager.android.libvirt.NetworkSource
import dev.virtmanager.android.libvirt.NewVmSpec
import dev.virtmanager.android.libvirt.VirshClient
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Every customization the app offers, saved through a real libvirtd: libvirt must accept the
 * XML and read the values back. Opt-in: VMA_IT_REAL_URI (e.g. qemu:///system) with local virsh.
 */
class RealLibvirtConfigTest {
    private val uri = System.getenv("VMA_IT_REAL_URI")
    private val vm = "cfg-test"
    private lateinit var virsh: VirshClient

    private val local = CommandRunner { command, stdin ->
        val p = ProcessBuilder("sh", "-c", command).start()
        if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()) } else p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        ExecResult(p.exitValue(), out, err)
    }

    @Before fun define() = runBlocking<Unit> {
        assumeTrue(uri != null)
        virsh = VirshClient(local, uri!!)
        cleanup()
        val spec = NewVmSpec(
            vm, 1024, 2, firmware = Firmware.BIOS, installMediaPath = "/var/lib/libvirt/images/debian-13-netinst.iso",
            diskPath = "/var/lib/libvirt/images/$vm.qcow2", network = NetworkSource.Virtual("default"), virtType = "qemu",
        )
        local.run("qemu-img create -f qcow2 /var/lib/libvirt/images/$vm.qcow2 64M >/dev/null", null)
        virsh.defineXml(DomainXmlBuilder.build(spec))
    }

    @After fun cleanup() = runBlocking<Unit> {
        if (!::virsh.isInitialized) return@runBlocking
        runCatching { virsh.destroy(vm) }
        runCatching { virsh.undefine(vm) }
        local.run("rm -f /var/lib/libvirt/images/$vm.qcow2 /var/lib/libvirt/images/$vm-extra.qcow2", null)
    }

    /** Edit, save through libvirt, and return what libvirt stored. */
    private fun edit(block: DomainConfig.() -> Unit): DomainConfig = runBlocking {
        val cfg = virsh.inactiveConfig(vm)
        cfg.block()
        virsh.saveConfig(vm, cfg)
        virsh.inactiveConfig(vm)
    }

    private fun device(cfg: DomainConfig, tag: String, pred: (DeviceSettings?) -> Boolean = { true }) =
        cfg.devices().first { it.tag == tag && pred(DeviceSettings.read(cfg.deviceElement(it.index)!!)) }

    @Test fun overviewOsCpuMemoryBoot() {
        var cfg = edit {
            title = "Chat server"
            description = "Line one\nLine two"
            osInfoId = "http://almalinux.org/almalinux/10"
        }
        assertEquals("Chat server", cfg.title)
        assertEquals("Line one\nLine two", cfg.description)
        assertEquals("http://almalinux.org/almalinux/10", cfg.osInfoId)

        cfg = edit { firmware = FirmwareKind.UEFI }
        assertEquals(FirmwareKind.UEFI, cfg.firmware)
        cfg = edit { firmware = FirmwareKind.UEFI_SECURE }
        assertEquals(FirmwareKind.UEFI_SECURE, cfg.firmware)
        cfg = edit { firmware = FirmwareKind.BIOS }
        assertEquals(FirmwareKind.BIOS, cfg.firmware)

        cfg = edit { setChipset(Chipset.I440FX) }
        assertEquals(Chipset.I440FX, cfg.chipset)
        assertTrue(cfg.devices().any { it.label.startsWith("IDE CDROM") })
        cfg = edit { setChipset(Chipset.Q35) }
        assertEquals(Chipset.Q35, cfg.chipset)
        assertTrue(cfg.devices().any { it.label.startsWith("SATA CDROM") })

        cfg = edit { setVcpus(current = 2, max = 4); topology = CpuTopology(sockets = 1, cores = 2, threads = 2) }
        assertEquals(2, cfg.currentVcpus); assertEquals(4, cfg.maxVcpus)
        assertEquals(CpuTopology(1, 1, 2, 2), cfg.topology)
        cfg = edit { cpuMode = "custom"; cpuModel = "qemu64" }
        assertEquals("custom", cfg.cpuMode); assertEquals("qemu64", cfg.cpuModel)
        cfg = edit { cpuMode = "host-model" }
        assertEquals("host-model", cfg.cpuMode)
        cfg = edit { cpuMode = null; topology = null }
        // libvirt spells "hypervisor default" out as custom + the default model on its own.
        assertTrue(cfg.cpuMode == null || cfg.cpuMode == "custom")
        assertEquals(null, cfg.topology)

        cfg = edit { setMemory(currentMiB = 768, maxMiB = 1536); sharedMemory = true }
        assertEquals(1536, cfg.maxMemoryMiB); assertEquals(768, cfg.currentMemoryMiB)
        assertTrue(cfg.sharedMemory)
        cfg = edit { sharedMemory = false }
        assertFalse(cfg.sharedMemory)

        // Boot: put the disk first, disable the CD-ROM, enable the boot menu.
        val disk = cfg.bootEntries().first { it.label.startsWith("VirtIO Disk") }
        val nic = cfg.bootEntries().first { it.label.startsWith("NIC") }
        cfg = edit { setBootOrder(listOf(disk.index, nic.index)); bootMenu = true }
        val entries = cfg.bootEntries()
        assertEquals(listOf(disk.label, nic.label), entries.filter { it.enabled }.map { it.label })
        assertTrue(entries.any { it.label.startsWith("SATA CDROM") && !it.enabled })
        assertTrue(cfg.bootMenu)
    }

    @Test fun editExistingDevices() {
        var cfg = virsh.let { runBlocking { it.inactiveConfig(vm) } }
        val disk = device(cfg, "disk") { (it as DiskSettings).device == "disk" }
        cfg = edit {
            val s = DeviceSettings.read(deviceElement(disk.index)!!) as DiskSettings
            s.copy(bus = "scsi", cache = "none", discard = "unmap", serial = "DISK01").apply(deviceElement(disk.index)!!, this)
            // SCSI disks need a SCSI controller.
            addDevice(ControllerSettings("scsi", "virtio-scsi").toXml(this))
        }
        val d = DeviceSettings.read(cfg.deviceElement(device(cfg, "disk") { (it as DiskSettings).device == "disk" }.index)!!) as DiskSettings
        assertEquals("scsi", d.bus); assertEquals("none", d.cache); assertEquals("unmap", d.discard); assertEquals("DISK01", d.serial)
        assertTrue(cfg.devices().any { it.label.startsWith("SCSI Disk") })

        val nic = device(cfg, "interface")
        cfg = edit {
            InterfaceSettings("network", "default", "e1000e", "52:54:00:12:34:56", linkUp = false).apply(deviceElement(nic.index)!!, this)
        }
        val n = DeviceSettings.read(cfg.deviceElement(device(cfg, "interface").index)!!) as InterfaceSettings
        assertEquals(InterfaceSettings("network", "default", "e1000e", "52:54:00:12:34:56", false), n)

        val gfx = device(cfg, "graphics")
        cfg = edit { GraphicsSettings("vnc", "0.0.0.0", autoport = false, port = 5944, password = "s3cret").apply(deviceElement(gfx.index)!!, this) }
        val g = DeviceSettings.read(cfg.deviceElement(device(cfg, "graphics").index)!!) as GraphicsSettings
        assertEquals("0.0.0.0", g.listen); assertEquals(5944, g.port); assertFalse(g.autoport); assertEquals("s3cret", g.password)

        val video = device(cfg, "video")
        cfg = edit { VideoSettings("vga", 1).apply(deviceElement(video.index)!!, this) }
        assertTrue(cfg.devices().any { it.label == "Video VGA" })

        val tablet = device(cfg, "input") { (it as InputSettings).type == "tablet" }
        cfg = edit { InputSettings("tablet", "virtio").apply(deviceElement(tablet.index)!!, this) }
        assertTrue(cfg.devices().any { it.tag == "input" && (DeviceSettings.read(cfg.deviceElement(it.index)!!) as InputSettings).bus == "virtio" })

        val rng = device(cfg, "rng")
        cfg = edit { RngSettings("/dev/random").apply(deviceElement(rng.index)!!, this) }
        assertTrue(cfg.devices().any { it.label == "RNG /dev/random" })

        // Per-device XML tab: replace the channel's XML wholesale.
        val ch = device(cfg, "channel")
        cfg = edit { replaceDevice(ch.index, "<channel type='unix'><target type='virtio' name='org.qemu.guest_agent.0'/></channel>") }
        assertTrue(cfg.devices().any { it.label == "Channel (qemu-ga)" })

        // Remove
        val usb = device(cfg, "controller") { (it as ControllerSettings).type == "usb" }
        cfg = edit { removeDevice(device(this, "rng").index) }
        assertTrue(cfg.devices().none { it.tag == "rng" })
        assertTrue(usb.removable)
    }

    @Test fun addEveryHardwareType() {
        runBlocking { local.run("qemu-img create -f qcow2 /var/lib/libvirt/images/$vm-extra.qcow2 16M >/dev/null", null) }
        val additions: List<Pair<String, DomainConfig.() -> DeviceSettings>> = listOf(
            "Storage (new disk)" to { DiskSettings("disk", "file", "/var/lib/libvirt/images/$vm-extra.qcow2", "virtio", "qcow2") },
            "Storage (CD-ROM)" to { DiskSettings("cdrom", "file", null, "sata", "raw") },
            "Controller" to { ControllerSettings("scsi", "virtio-scsi") },
            "Network" to { InterfaceSettings("network", "default", "virtio") },
            "Input" to { InputSettings("keyboard", "virtio") },
            "Graphics" to { GraphicsSettings("vnc") },
            "Sound" to { SoundSettings("ich9") },
            "Serial" to { CharSettings("serial") },
            "Parallel" to { CharSettings("parallel") },
            "Console" to { CharSettings("console", targetType = "virtio") },
            "Channel" to { CharSettings("channel", "unix", targetName = "org.qemu.guest_agent.1") },
            "Video" to { VideoSettings("virtio") },
            "Watchdog" to { WatchdogSettings("i6300esb", "poweroff") },
            "Filesystem" to { FilesystemSettings("virtiofs", "/srv", "srv-share") },
            "Smartcard" to { SmartcardSettings("host") },
            "USB Redirection" to { RedirdevSettings("tcp", "localhost", 4000) }, // no SPICE in this QEMU
            "TPM" to { TpmSettings("tpm-crb", "emulator") },
            "RNG" to { RngSettings("/dev/urandom") },
            "Panic Notifier" to { PanicSettings("isa") },
            "VirtIO VSOCK" to { VsockSettings() },
            "USB Host Device" to { HostdevSettings("usb", vendorId = "0x046d", productId = "0xc52b") },
            "PCI Host Device" to { HostdevSettings("pci", pciAddress = "0000:00:1f.0") },
        )
        for ((label, settings) in additions) {
            val before = runBlocking { virsh.inactiveConfig(vm) }.devices().size
            val cfg = try {
                edit { addDevice(settings().toXml(this)) }
            } catch (t: Throwable) {
                throw AssertionError("libvirt rejected '$label': ${t.message}", t)
            }
            assertTrue("$label was not added", cfg.devices().size > before)
        }
        val cfg = runBlocking { virsh.inactiveConfig(vm) }
        assertTrue(cfg.sharedMemory) // enabled automatically for virtiofs
        val labels = cfg.devices().map { it.label }
        listOf("VirtIO Disk 2", "SATA CDROM 2", "Controller SCSI", "Sound ich9", "Serial 1", "Parallel 1", "Video Virtio",
            "Watchdog", "Filesystem srv-share", "Smartcard", "USB Redirector 1", "TPM v2.0", "Panic Notifier",
            "VirtIO VSOCK", "USB 046d:c52b", "PCI 0000:00:1f.0").forEach { assertTrue("missing $it in $labels", it in labels) }
    }

    @Test fun editedVmStillBoots() = runBlocking<Unit> {
        // A realistic customization, then start it on QEMU to prove the result really runs.
        edit {
            title = "boots"
            setVcpus(1, 2)
            setMemory(512, 1024)
            addDevice(SoundSettings("ich9").toXml(this))
            addDevice(WatchdogSettings("i6300esb", "reset").toXml(this))
            addDevice(CharSettings("serial").toXml(this))
            val cd = devices().first { it.label.startsWith("SATA CDROM") }
            val disk = devices().first { it.label.startsWith("VirtIO Disk") }
            setBootOrder(listOf(disk.index, cd.index))
        }
        virsh.startWithNetworks(vm)
        assertEquals("running", local.run("virsh -c $uri domstate $vm", null).stdout.trim())
    }
}
