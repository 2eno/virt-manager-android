package dev.virtmanager.android.libvirt

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DomainEditingTest {
    private val xml = """
        <domain type='kvm'>
          <name>AI-Chat</name>
          <uuid>11111111-2222-3333-4444-555555555555</uuid>
          <memory unit='KiB'>2097152</memory>
          <os><type arch='x86_64'>hvm</type></os>
          <devices>
            <interface type='bridge'>
              <mac address='52:54:00:aa:bb:cc'/>
              <source bridge='br0'/>
              <target dev='vnet7'/>
              <model type='e1000e'/>
            </interface>
            <interface type='network'>
              <mac address='52:54:00:11:22:33'/>
              <source network='default'/>
            </interface>
          </devices>
        </domain>
    """.trimIndent()

    @Test fun bridgeToNetworkKeepsMacAndDropsRuntimeTarget() {
        val out = DomainXmlEditor.setInterface(xml, 0, NetworkSource.Virtual("default"), model = "virtio")
        val nic = Parsers.parseDomainXml(out).interfaces[0]
        assertEquals("network", nic.type)
        assertEquals("default", nic.source)
        assertEquals("52:54:00:aa:bb:cc", nic.mac)
        assertEquals("virtio", nic.model)
        assertNull(nic.target)
        assertFalse(out.contains("bridge='br0'") || out.contains("bridge=\"br0\""))
        // The other NIC is untouched.
        assertEquals("default", Parsers.parseDomainXml(out).interfaces[1].source)
    }

    @Test fun networkToBridgeAddsModelWhenMissing() {
        val out = DomainXmlEditor.setInterface(xml, 1, NetworkSource.Bridge("br-home"), model = "virtio")
        val nic = Parsers.parseDomainXml(out).interfaces[1]
        assertEquals("bridge", nic.type)
        assertEquals("br-home", nic.source)
        assertEquals("virtio", nic.model)
        assertEquals("52:54:00:11:22:33", nic.mac)
    }

    @Test fun keepsModelWhenNotGiven() {
        val out = DomainXmlEditor.setInterface(xml, 0, NetworkSource.Bridge("br-home"))
        assertEquals("e1000e", Parsers.parseDomainXml(out).interfaces[0].model)
    }

    @Test fun identity() {
        assertEquals("AI-Chat" to "11111111-2222-3333-4444-555555555555", DomainXmlEditor.identity(xml))
        assertTrue(runCatching { DomainXmlEditor.identity("<network><name>x</name></network>") }.isFailure)
        assertTrue(runCatching { DomainXmlEditor.identity("<domain><name>broken") }.isFailure)
    }

    private class Fake(val reply: (String, String?) -> ExecResult) : CommandRunner {
        val commands = mutableListOf<String>()
        override suspend fun run(command: String, stdin: String?) = reply(command, stdin).also { commands += command }
    }

    private val volList = """
         Name               Path                                   Type   Capacity    Allocation
        ---------------------------------------------------------------------------------------
         AI-Chat.qcow2      /var/lib/libvirt/images/AI-Chat.qcow2      file   20.00 GiB   1.00 GiB
         AI-Chat-1.qcow2    /var/lib/libvirt/images/AI-Chat-1.qcow2    file   20.00 GiB   1.00 GiB
    """.trimIndent()

    @Test fun uniqueVolumeNameSkipsLeftovers() = runBlocking {
        val v = VirshClient(Fake { _, _ -> ExecResult(0, volList, "") }, "qemu:///system")
        assertEquals("AI-Chat-2.qcow2", v.uniqueVolumeName("default", "AI-Chat.qcow2"))
        assertEquals("other.qcow2", v.uniqueVolumeName("default", "other.qcow2"))
    }

    @Test fun hostBridgesExcludesLibvirtOwn() = runBlocking {
        val v = VirshClient(Fake { _, _ -> ExecResult(0, "virbr0\nbr-home\n\nbr-lab\n", "") }, "qemu:///system")
        assertEquals(listOf("br-home", "br-lab"), v.hostBridges(setOf("virbr0")))
    }

    @Test fun startWithNetworksStartsInactiveNetworkFirst() = runBlocking {
        val netList = " Name      State      Autostart   Persistent\n----------------------\n default   inactive   no          yes\n"
        val fake = Fake { cmd, _ ->
            when {
                cmd.contains("dumpxml") -> ExecResult(0, xml, "")
                cmd.contains("net-list") -> ExecResult(0, netList, "")
                else -> ExecResult(0, "", "")
            }
        }
        val started = VirshClient(fake, "qemu:///system").startWithNetworks("AI-Chat")
        assertEquals(listOf("default"), started)
        val netStart = fake.commands.indexOfFirst { it.endsWith("net-start default") }
        val start = fake.commands.indexOfFirst { it.endsWith(" start AI-Chat") }
        assertTrue(fake.commands.toString(), netStart in 0 until start)
        assertTrue(fake.commands.first { it.contains("dumpxml") }.contains("--inactive"))
    }

    @Test fun redefineRefusesRenameAndUuidChange() = runBlocking {
        val fake = Fake { _, _ -> ExecResult(0, "", "") }
        val v = VirshClient(fake, "qemu:///system")
        for (bad in listOf(xml.replace("<name>AI-Chat</name>", "<name>Other</name>"), xml.replace("11111111", "99999999"))) {
            try {
                v.redefine("AI-Chat", "11111111-2222-3333-4444-555555555555", bad)
                fail("expected refusal")
            } catch (_: VirshException) {
            }
        }
        assertTrue(fake.commands.isEmpty())
        v.redefine("AI-Chat", "11111111-2222-3333-4444-555555555555", xml.replace("2097152", "4194304"))
        assertTrue(fake.commands.single().endsWith("define /dev/stdin"))
    }
}
