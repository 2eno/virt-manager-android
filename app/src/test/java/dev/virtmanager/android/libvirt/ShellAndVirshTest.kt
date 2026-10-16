package dev.virtmanager.android.libvirt

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ShellAndVirshTest {

    @Test fun quoting() {
        assertEquals("qemu:///system", Shell.quote("qemu:///system"))
        assertEquals("'my vm'", Shell.quote("my vm"))
        assertEquals("''", Shell.quote(""))
        assertEquals("'it'\"'\"'s'", Shell.quote("it's"))
        assertEquals("'\$(rm -rf /)'", Shell.quote("\$(rm -rf /)"))
    }

    private class FakeRunner(val reply: (String, String?) -> ExecResult) : CommandRunner {
        val commands = mutableListOf<String>()
        override suspend fun run(command: String, stdin: String?): ExecResult {
            commands += command
            return reply(command, stdin)
        }
    }

    @Test fun commandsAreQuotedAndLocalised() = runBlocking {
        val runner = FakeRunner { _, _ -> ExecResult(0, "", "") }
        val v = VirshClient(runner, "qemu:///system", useSudo = true)
        v.start("my vm; reboot")
        assertEquals("LC_ALL=C sudo -n virsh -q -c qemu:///system start 'my vm; reboot'", runner.commands.single())
    }

    @Test fun errorsBecomeExceptions() = runBlocking {
        val runner = FakeRunner { _, _ -> ExecResult(1, "", "error: failed to get domain 'x'\nerror: Domain not found\n") }
        try {
            VirshClient(runner, "qemu:///system").start("x")
            fail("expected exception")
        } catch (e: VirshException) {
            assertEquals("failed to get domain 'x'\nDomain not found", e.message)
        }
    }

    @Test fun batchSplitsSections() = runBlocking {
        val runner = FakeRunner { _, _ ->
            ExecResult(0, "host-a\n__VMA_SECTION__ 0\n__VMA_SECTION__ 1\nlibvirt\n__VMA_SECTION__ 0\n", "")
        }
        val out = VirshClient(runner, "qemu:///system").batch(listOf("hostname"), listOf("nodeinfo"), listOf("version"))
        assertEquals("host-a\n", out[0])
        assertNull(out[1])
        assertEquals("libvirt\n", out[2])
        val script = runner.commands.single()
        assertTrue(script.contains("virsh -c qemu:///system hostname 2>/dev/null"))
    }

    @Test fun defineSendsXmlOnStdin() = runBlocking {
        var received: String? = null
        val runner = FakeRunner { _, stdin -> received = stdin; ExecResult(0, "Domain 'x' defined", "") }
        VirshClient(runner, "qemu:///session").defineXml("<domain/>")
        assertEquals("<domain/>", received)
        assertTrue(runner.commands.single().endsWith("define /dev/stdin"))
    }
}
