package dev.virtmanager.android.libvirt

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The reported failure: an install ISO in the user's home folder, which QEMU (running as its
 * own user) can't enter, so starting fails with "Could not open ...: Permission denied".
 * Runs everything as an ordinary user, like an SSH login: VMA_IT_REAL_URI plus
 * VMA_IT_HOME_USER, a user in the libvirt group whose home folder is mode 0700.
 */
class RealQemuAccessTest {
    private val uri = System.getenv("VMA_IT_REAL_URI")
    private val user = System.getenv("VMA_IT_HOME_USER")
    private val vm = "access-test"
    private lateinit var virsh: VirshClient
    private lateinit var home: File
    private val iso get() = File(home, "install media/debian-netinst.iso")

    /**
     * Runs commands as [user], like the app's SSH session does. Stdin is piped once more inside
     * the user's shell: `virsh define /dev/stdin` reopens it, which root's pipe wouldn't allow.
     */
    private val asUser = CommandRunner { command, stdin ->
        val script = if (stdin != null) "cat | { $command; }" else command
        val p = ProcessBuilder("su", user!!, "-s", "/bin/sh", "-c", script).start()
        if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()) } else p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        ExecResult(p.exitValue(), out, err)
    }

    private fun sh(cmd: String) = ProcessBuilder("sh", "-c", cmd).inheritIO().start().waitFor()

    @Before fun setUp() = runBlocking<Unit> {
        assumeTrue(uri != null && user != null)
        virsh = VirshClient(asUser, uri!!)
        home = File(asUser.run("echo \$HOME", null).stdout.trim())
        cleanup()
        assertEquals(0, sh("chmod 700 '$home' && setfacl -b '$home' && mkdir -p '${iso.parent}' && " +
            "truncate -s 4M '$iso' && chown -R $user: '${iso.parentFile}'"))
        virsh.defineXml(
            """
            <domain type='qemu'><name>$vm</name><memory unit='MiB'>128</memory><vcpu>1</vcpu>
              <os><type arch='x86_64' machine='q35'>hvm</type></os>
              <devices>
                <disk type='file' device='cdrom'><driver name='qemu' type='raw'/><source file='$iso'/>
                  <target dev='sda' bus='sata'/><readonly/></disk>
              </devices>
            </domain>
            """.trimIndent(),
        )
    }

    @After fun cleanup() = runBlocking<Unit> {
        if (!::virsh.isInitialized) return@runBlocking
        runCatching { virsh.destroy(vm) }
        runCatching { virsh.undefine(vm) }
        sh("rm -rf '${iso.parent}'; setfacl -b '$home'")
    }

    @Test fun permissionDeniedIsDiagnosedAndFixed() = runBlocking {
        val failure = runCatching { virsh.startWithNetworks(vm) }.exceptionOrNull()
        assertTrue("$failure", QemuAccess.isPermissionError(failure?.message))

        val problem = virsh.qemuAccessProblem(vm)!!
        assertEquals("libvirt-qemu", problem.user)
        // Only the home folder blocks; "install media" itself is world-searchable.
        assertEquals(mapOf(iso.path to listOf(home.path)), problem.blocked)

        // As the folder's owner, no root needed.
        assertEquals(emptyList<Pair<String, String>>(), virsh.grantQemuSearch(problem))
        assertNull(virsh.qemuAccessProblem(vm))
        virsh.startWithNetworks(vm)
        assertTrue(virsh.domInfo(vm).state.isActive)
        // Nothing but search permission was given.
        val acl = asUser.run("getfacl -n -p --omit-header '$home'", null).stdout
        assertTrue(acl, acl.contains("user:${problem.uid}:--x"))
    }
}
