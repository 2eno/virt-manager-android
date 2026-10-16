package dev.virtmanager.android.libvirt

/** POSIX shell quoting for arguments we send over SSH. */
object Shell {
    private val safe = Regex("^[A-Za-z0-9_@%+=:,./-]+$")

    fun quote(arg: String): String =
        if (arg.isNotEmpty() && safe.matches(arg)) arg
        else "'" + arg.replace("'", "'\"'\"'") + "'"

    fun join(args: List<String>): String = args.joinToString(" ") { quote(it) }
}

data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
}

/** Abstraction over "run a shell command on the hypervisor host"; the SSH session implements it. */
fun interface CommandRunner {
    suspend fun run(command: String, stdin: String?): ExecResult
}
