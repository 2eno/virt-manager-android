package dev.virtmanager.android.libvirt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {

    @Test fun domStats() {
        val out = """
            Domain: 'web-01'
              state.state=1
              state.reason=1
              cpu.time=123456789000
              balloon.current=2097152
              balloon.maximum=4194304
              balloon.rss=1048576
              vcpu.current=2
              vcpu.maximum=4
              net.count=1
              net.0.name=vnet3
              net.0.rx.bytes=1000
              net.0.tx.bytes=2000
              block.count=2
              block.0.name=vda
              block.0.rd.bytes=10
              block.0.wr.bytes=20
              block.0.capacity=21474836480
              block.1.name=sda
              block.1.rd.bytes=5

            Domain: 'db with space'
              state.state=5
              state.reason=2
              balloon.maximum=1048576
              vcpu.maximum=1

        """.trimIndent()
        val stats = Parsers.parseDomStats(out)
        assertEquals(2, stats.size)
        val web = stats[0]
        assertEquals("web-01", web.name)
        assertEquals(DomainState.RUNNING, web.state)
        assertEquals(123456789000L, web.cpuTimeNs)
        assertEquals(2, web.vcpus)
        assertEquals(4, web.maxVcpus)
        assertEquals(2097152L, web.memoryKiB)
        assertEquals(1000L, web.netRxBytes)
        assertEquals(2000L, web.netTxBytes)
        assertEquals(15L, web.diskReadBytes)
        assertEquals(20L, web.diskWriteBytes)
        assertEquals(21474836480L, web.blocks[0].capacity)

        val db = stats[1]
        assertEquals("db with space", db.name)
        assertEquals(DomainState.SHUTOFF, db.state)
        assertFalse(db.state.isActive)
        assertNull(db.cpuTimeNs)
        assertTrue(db.interfaces.isEmpty())
    }

    @Test fun domInfo() {
        val info = Parsers.parseDomInfo(
            """
            Id:             3
            Name:           web-01
            UUID:           4dea22b3-1d52-d8f3-2516-782e98ab3fa0
            OS Type:        hvm
            State:          running
            CPU(s):         2
            CPU time:       12.3s
            Max memory:     4194304 KiB
            Used memory:    2097152 KiB
            Persistent:     yes
            Autostart:      enable
            Managed save:   no
            Security model: apparmor
            """.trimIndent(),
        )
        assertEquals("3", info.id)
        assertEquals("web-01", info.name)
        assertEquals(DomainState.RUNNING, info.state)
        assertEquals(2, info.cpus)
        assertEquals(4194304L, info.maxMemoryKiB)
        assertTrue(info.persistent)
        assertTrue(info.autostart)
    }

    @Test fun shutOffDomInfo() {
        val info = Parsers.parseDomInfo("Id:             -\nName: x\nState:          shut off\nAutostart:      disable\n")
        assertNull(info.id)
        assertEquals(DomainState.SHUTOFF, info.state)
        assertFalse(info.autostart)
    }

    @Test fun nodeInfoAndStats() {
        val node = Parsers.parseNodeInfo(
            """
            CPU model:           x86_64
            CPU(s):              16
            CPU frequency:       3400 MHz
            CPU socket(s):       1
            Core(s) per socket:  8
            Thread(s) per core:  2
            NUMA cell(s):        1
            Memory size:         65761140 KiB
            """.trimIndent(),
        )
        assertEquals("x86_64", node.cpuModel)
        assertEquals(16, node.cpus)
        assertEquals(3400, node.cpuFrequencyMHz)
        assertEquals(65761140L, node.memoryKiB)

        val mem = Parsers.parseNodeMemStats("total  :             65761140 KiB\nfree   :             30000000 KiB\nbuffers:               100000 KiB\ncached :              5000000 KiB\n")
        assertEquals(65761140L, mem.totalKiB)
        assertEquals(65761140L - 30000000 - 100000 - 5000000, mem.usedKiB)

        val cpu = Parsers.parseNodeCpuStats("user:                 1000\nsystem:               500\nidle:                8000\niowait:                 50\n")
        assertEquals(1500L, cpu.busy)
        assertEquals(9550L, cpu.total)
    }

    @Test fun version() {
        val v = Parsers.parseVersion(
            "Compiled against library: libvirt 10.0.0\nUsing library: libvirt 10.0.0\nUsing API: QEMU 10.0.0\nRunning hypervisor: QEMU 8.2.2\n",
        )
        assertEquals("libvirt 10.0.0", v.library)
        assertEquals("QEMU 8.2.2", v.hypervisor)
    }

    @Test fun tables() {
        val nets = Parsers.parseNetList(
            """
             Name      State      Autostart   Persistent
            ----------------------------------------------
             default   active     yes         yes
             isolated  inactive   no          yes
            """.trimIndent(),
        )
        assertEquals(2, nets.size)
        assertTrue(nets[0].active && nets[0].autostart)
        assertFalse(nets[1].active)

        val pools = Parsers.parsePoolList(
            """
             Name      State     Autostart   Persistent   Capacity     Allocation   Available
            -------------------------------------------------------------------------------------
             default   running   yes         yes          97.87 GiB    40.41 GiB    57.46 GiB
             iso       inactive  no          yes          -            -            -
            """.trimIndent(),
        )
        assertEquals("97.87 GiB", pools[0].capacity)
        assertTrue(pools[0].active)
        assertNull(pools[1].capacity)
        assertFalse(pools[1].active)

        val vols = Parsers.parseVolList(
            """
             Name               Path                                        Type   Capacity     Allocation
            ------------------------------------------------------------------------------------------------
             debian-12.iso      /var/lib/libvirt/images/debian-12.iso       file   628.00 MiB   628.00 MiB
             web-01.qcow2       /var/lib/libvirt/images/web-01.qcow2        file   20.00 GiB    3.27 GiB
            """.trimIndent(),
        )
        assertEquals(2, vols.size)
        assertEquals("/var/lib/libvirt/images/web-01.qcow2", vols[1].path)
        assertEquals("3.27 GiB", vols[1].allocation)

        val snaps = Parsers.parseSnapshotList(
            """
             Name           Creation Time               State
            ---------------------------------------------------
             before-update  2024-05-01 10:00:00 +0200   shutoff
             clean          2024-05-02 11:30:00 +0200   running
            """.trimIndent(),
            current = "clean",
        )
        assertEquals("2024-05-01 10:00:00 +0200", snaps[0].creationTime)
        assertTrue(snaps[1].current)
        assertFalse(snaps[0].current)
    }

    @Test fun emptyTable() {
        assertTrue(Parsers.parseNetList(" Name   State   Autostart   Persistent\n-----------------\n\n").isEmpty())
        assertTrue(Parsers.parseNetList("").isEmpty())
    }

    @Test fun domainXml() {
        val xml = """
            <domain type='kvm' id='3'>
              <name>web-01</name>
              <uuid>4dea22b3-1d52-d8f3-2516-782e98ab3fa0</uuid>
              <title>Web server</title>
              <memory unit='GiB'>4</memory>
              <currentMemory unit='KiB'>2097152</currentMemory>
              <vcpu placement='static' current='2'>4</vcpu>
              <os firmware='efi'>
                <type arch='x86_64' machine='pc-q35-8.2'>hvm</type>
                <boot dev='hd'/>
                <boot dev='cdrom'/>
              </os>
              <cpu mode='host-passthrough' check='none'/>
              <devices>
                <emulator>/usr/bin/qemu-system-x86_64</emulator>
                <disk type='file' device='disk'>
                  <driver name='qemu' type='qcow2'/>
                  <source file='/var/lib/libvirt/images/web-01.qcow2'/>
                  <target dev='vda' bus='virtio'/>
                </disk>
                <disk type='volume' device='disk'>
                  <driver name='qemu' type='raw'/>
                  <source pool='fast' volume='data.img'/>
                  <target dev='vdb' bus='virtio'/>
                </disk>
                <disk type='file' device='cdrom'>
                  <target dev='sda' bus='sata'/>
                  <readonly/>
                </disk>
                <interface type='network'>
                  <mac address='52:54:00:12:34:56'/>
                  <source network='default'/>
                  <target dev='vnet3'/>
                  <model type='virtio'/>
                </interface>
                <interface type='bridge'>
                  <source bridge='br0'/>
                </interface>
                <serial type='pty'><target port='0'/></serial>
                <channel type='unix'><target type='virtio' name='org.qemu.guest_agent.0'/></channel>
                <graphics type='vnc' port='5901' autoport='yes' listen='127.0.0.1' passwd='secret'>
                  <listen type='address' address='127.0.0.1'/>
                </graphics>
                <video><model type='virtio' heads='1'/></video>
              </devices>
            </domain>
        """.trimIndent()
        val d = Parsers.parseDomainXml(xml)
        assertEquals("web-01", d.name)
        assertEquals("Web server", d.title)
        assertEquals(4L * 1024 * 1024, d.memoryKiB)
        assertEquals(2097152L, d.currentMemoryKiB)
        assertEquals(2, d.vcpus)
        assertEquals("x86_64", d.arch)
        assertEquals("efi", d.firmware)
        assertEquals(listOf("hd", "cdrom"), d.bootDevices)
        assertEquals(3, d.disks.size)
        assertEquals("/var/lib/libvirt/images/web-01.qcow2", d.disks[0].source)
        assertEquals("fast/data.img", d.disks[1].source)
        assertNull(d.disks[2].source)
        assertTrue(d.disks[2].readOnly)
        assertEquals("default", d.interfaces[0].source)
        assertEquals("vnet3", d.interfaces[0].target)
        assertEquals("br0", d.interfaces[1].source)
        val vnc = d.vnc!!
        assertEquals(5901, vnc.port)
        assertTrue(vnc.hasPassword)
        assertEquals("127.0.0.1", vnc.listen)
        assertTrue(d.hasSerialConsole)
        assertTrue(d.hasGuestAgentChannel)
        assertEquals("virtio", d.videoModel)
    }

    @Test fun inactiveVncHasNoPort() {
        val d = Parsers.parseDomainXml(
            "<domain type='kvm'><name>x</name><memory>1024</memory><os><type>hvm</type></os>" +
                "<devices><graphics type='vnc' port='-1' autoport='yes'/></devices></domain>",
        )
        assertNull(d.vnc!!.port)
        assertTrue(d.vnc!!.autoport)
        assertEquals("bios", d.firmware)
    }

    @Test fun networkXml() {
        val n = Parsers.parseNetworkXml(
            """
            <network>
              <name>default</name>
              <forward mode='nat'/>
              <bridge name='virbr0' stp='on' delay='0'/>
              <ip address='192.168.122.1' netmask='255.255.255.0'>
                <dhcp><range start='192.168.122.2' end='192.168.122.254'/></dhcp>
              </ip>
            </network>
            """.trimIndent(),
        )
        assertEquals("virbr0", n.bridge)
        assertEquals("nat", n.forwardMode)
        assertEquals("192.168.122.2", n.dhcpStart)
        assertEquals("isolated", Parsers.parseNetworkXml("<network><name>iso</name></network>").forwardMode)
    }

    @Test fun units() {
        assertEquals(1024L, Parsers.toKiB(1, "MiB"))
        assertEquals(1L, Parsers.toKiB(1024, "bytes"))
        assertEquals(1024L * 1024, Parsers.toKiB(1, "G"))
    }
}
