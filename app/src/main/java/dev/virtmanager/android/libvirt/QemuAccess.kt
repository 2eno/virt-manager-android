package dev.virtmanager.android.libvirt

/**
 * Why QEMU can't open a disk or ISO image: libvirt runs QEMU as its own user (e.g.
 * "libvirt-qemu") and gives it the image file itself, but not the folders above it. An ISO in
 * a home folder (mode 0700/0750) then fails with "Could not open ...: Permission denied".
 * virt-manager offers to fix this by granting that user search permission (x) on the folders;
 * this does the same.
 */
object QemuAccess {
    /** Folders QEMU's user can't pass through, per image file. */
    data class Problem(val user: String, val uid: Int, val blocked: Map<String, List<String>>) {
        val dirs: List<String> get() = blocked.values.flatten().distinct()

        /** The command that fixes it, for the user to run themselves if the app can't. */
        val command: String get() = "setfacl -m u:$user:x " + dirs.joinToString(" ") { Shell.quote(it) }
    }

    /** One folder above an image: owner, group, permission bits and its ACL lines (getfacl). */
    data class Dir(val path: String, val uid: Int, val gid: Int, val mode: Int, val acl: List<String> = emptyList())

    /** The uid and gid QEMU runs as, from the DAC `<baselabel>` in `virsh capabilities`. */
    fun qemuIdentity(capabilitiesXml: String, domainType: String?): Pair<Int, Int>? {
        val dac = Regex("<secmodel>\\s*<model>dac</model>(.*?)</secmodel>", RegexOption.DOT_MATCHES_ALL)
            .find(capabilitiesXml)?.groupValues?.get(1) ?: return null
        val labels = Regex("<baselabel type='([^']*)'>\\+?(\\d+):\\+?(\\d+)</baselabel>").findAll(dac)
            .associate { it.groupValues[1] to (it.groupValues[2].toInt() to it.groupValues[3].toInt()) }
        return labels[domainType] ?: labels.values.firstOrNull()
    }

    /**
     * Prints QEMU's user name and groups, then for each path every folder above it (nearest
     * first) with owner, group, mode and ACL. Needs no root; see [parse].
     */
    fun script(uid: Int, paths: List<String>): String = buildString {
        appendLine("u=\$(getent passwd $uid | cut -d: -f1)")
        appendLine("echo \"U \$u\"")
        appendLine("echo \"G \$([ -n \"\$u\" ] && id -G \"\$u\" 2>/dev/null)\"")
        for (p in paths) {
            appendLine("echo ${Shell.quote("P $p")}")
            appendLine("d=\$(dirname -- ${Shell.quote(p)})")
            appendLine(
                "while :; do echo \"D \$(stat -c '%u %g %a' -- \"\$d\" 2>/dev/null) \$d\"; " +
                    "getfacl -n -p --omit-header -- \"\$d\" 2>/dev/null | sed 's/^/A /'; " +
                    "[ \"\$d\" = / ] && break; d=\$(dirname -- \"\$d\"); done",
            )
        }
        append("true")
    }

    /** Parses [script]'s output into the problem, or null if QEMU can reach every path. */
    fun parse(out: String, uid: Int, gid: Int): Problem? {
        var user = uid.toString()
        val gids = mutableSetOf(gid)
        val dirsByPath = linkedMapOf<String, MutableList<Dir>>()
        var current: MutableList<Dir>? = null
        var aclTarget: MutableList<Dir>? = null // the list whose last folder the ACL lines belong to
        val dirLine = Regex("^D (\\d+) (\\d+) ([0-7]+) (.+)$")
        for (line in out.lines()) {
            when {
                line.startsWith("U ") -> line.removePrefix("U ").trim().takeIf { it.isNotEmpty() }?.let { user = it }
                line.startsWith("G ") -> line.removePrefix("G ").trim().split(Regex("\\s+")).mapNotNullTo(gids) { it.toIntOrNull() }
                line.startsWith("P ") -> current = dirsByPath.getOrPut(line.removePrefix("P ")) { mutableListOf() }
                line.startsWith("D ") -> {
                    // A folder we couldn't stat ourselves can't be judged; leave it out.
                    val m = dirLine.find(line)
                    aclTarget = if (m == null) null else current?.also { dirs ->
                        val (u, g, mode, path) = m.destructured
                        dirs += Dir(path, u.toInt(), g.toInt(), mode.toInt(8))
                    }
                }
                line.startsWith("A ") -> aclTarget?.let { dirs ->
                    dirs[dirs.lastIndex] = dirs.last().let { it.copy(acl = it.acl + line.removePrefix("A ").trim()) }
                }
            }
        }
        val blocked = dirsByPath.mapValues { (_, dirs) -> dirs.filterNot { canSearch(it, uid, gids) }.map { it.path } }
            .filterValues { it.isNotEmpty() }
        return if (blocked.isEmpty()) null else Problem(user, uid, blocked)
    }

    /** Whether [uid] (in groups [gids]) may pass through [dir], following POSIX ACL rules. */
    fun canSearch(dir: Dir, uid: Int, gids: Set<Int>): Boolean {
        if (uid == 0) return true
        val entries = dir.acl.mapNotNull { line -> line.substringBefore('#').trim().split(':').takeIf { it.size == 3 } }
        if (entries.none { it[0] == "user" && it[1].isEmpty() }) {
            // No ACL listing (getfacl missing): the classic owner/group/other bits.
            return when {
                uid == dir.uid -> dir.mode and 0b001_000_000 != 0
                dir.gid in gids -> dir.mode and 0b000_001_000 != 0
                else -> dir.mode and 0b000_000_001 != 0
            }
        }
        val mask = entries.firstOrNull { it[0] == "mask" }?.get(2)
        fun masked(perms: String) = 'x' in perms && (mask == null || 'x' in mask)
        if (uid == dir.uid) return 'x' in entries.first { it[0] == "user" && it[1].isEmpty() }[2]
        entries.firstOrNull { it[0] == "user" && it[1] == uid.toString() }?.let { return masked(it[2]) }
        val groups = entries.filter { it[0] == "group" && ((it[1].isEmpty() && dir.gid in gids) || it[1].toIntOrNull() in gids) }
        if (groups.isNotEmpty()) return groups.any { masked(it[2]) }
        return entries.firstOrNull { it[0] == "other" }?.let { 'x' in it[2] } ?: false
    }

    /** "Could not open '...': Permission denied" and libvirt's own access checks. */
    fun isPermissionError(message: String?): Boolean = message?.contains("Permission denied", ignoreCase = true) == true
}
