package dev.virtmanager.android.libvirt

import dev.virtmanager.android.data.ConnectionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException

class PassthroughTest {
    @Test fun pciHostdevsAreParsed() {
        val def = Parsers.parseDomainXml(
            "<domain type='kvm'><name>AI-Chat</name><devices>" +
                "<hostdev mode='subsystem' type='pci' managed='yes'><source><address domain='0x0000' bus='0x03' slot='0x00' function='0x0'/></source></hostdev>" +
                "<hostdev mode='subsystem' type='pci' managed='yes'><source><address domain='0x0000' bus='0x03' slot='0x00' function='0x1'/></source></hostdev>" +
                "<hostdev mode='subsystem' type='usb' managed='yes'><source><vendor id='0x046d'/><product id='0xc52b'/></source></hostdev>" +
                "</devices></domain>",
        )
        assertEquals(listOf("0000:03:00.0", "0000:03:00.1"), def.pciHostdevs)
    }

    @Test fun unreachableHostIsExplained() {
        val t = ConnectException("failed to connect to /10.0.0.204 (port 22) from /10.0.0.146 (port 45208) after 20000ms: isConnected failed: EHOSTUNREACH (No route to host)")
        assertTrue(ConnectionManager.friendly(t).startsWith("The host can't be reached"))
    }
}
