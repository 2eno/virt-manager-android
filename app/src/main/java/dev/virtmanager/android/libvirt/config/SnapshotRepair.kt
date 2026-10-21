package dev.virtmanager.android.libvirt.config

import org.w3c.dom.Element

/**
 * Undoes what reverting to an external snapshot does to CD-ROMs in libvirt 10/11: it puts an
 * overlay (`<iso name>.<timestamp>`) on the install ISO although the snapshot skipped it,
 * records that overlay in the snapshot as `snapshot='external'`, and the VM then uses it with
 * format qcow2. Deleting the snapshot afterwards tries to merge the overlay into the ISO, which
 * fails where QEMU may not write the ISO (and would change it where it may).
 */
object SnapshotRepair {
    private val mediaDevices = setOf("cdrom", "floppy")

    /** The media disks of the domain recorded in a snapshot: target → disk element. */
    private fun snapshotMedia(snapshot: Element): Map<String, Element> =
        snapshot.child("domain")?.child("devices")?.children("disk").orEmpty()
            .filter { it.attr("device") in mediaDevices }
            .mapNotNull { d -> d.child("target")?.attr("dev")?.let { it to d } }
            .toMap()

    /** The snapshot XML with its media disks back to `snapshot='no'`, or null if already so. */
    fun fixSnapshot(snapshotXml: String): String? {
        val root = Dom.parse(snapshotXml).documentElement
        val media = snapshotMedia(root).keys
        val disks = root.child("disks") ?: return null
        var changed = false
        for (d in disks.children("disk")) {
            if (d.attr("name") in media && d.attr("snapshot") == "external") {
                while (d.firstChild != null) d.removeChild(d.firstChild)
                listOf("type").forEach { d.removeAttribute(it) }
                d.setAttribute("snapshot", "no")
                changed = true
            }
        }
        return if (changed) Dom.serialize(root) else null
    }

    /**
     * The VM's (saved) XML with each CD-ROM that now uses such an overlay given the snapshot's
     * media back, with its original format; null if nothing needs fixing.
     */
    fun fixDomain(domainXml: String, snapshotXml: String): String? {
        val original = snapshotMedia(Dom.parse(snapshotXml).documentElement)
        val root = Dom.parse(domainXml).documentElement
        var changed = false
        for (d in root.child("devices")?.children("disk").orEmpty().filter { it.attr("device") in mediaDevices }) {
            val target = d.child("target")?.attr("dev") ?: continue
            val was = original[target] ?: continue
            val wasFile = was.child("source")?.attr("file") ?: continue
            val source = d.child("source") ?: continue
            val now = source.attr("file") ?: continue
            if (now == wasFile || !isOverlayOf(now, wasFile)) continue
            source.setAttribute("file", wasFile)
            d.ensure("driver").setAttribute("type", was.child("driver")?.attr("type") ?: "raw")
            d.removeChildren("backingStore")
            changed = true
        }
        return if (changed) Dom.serialize(root) else null
    }

    /** libvirt names these overlays "<image without extension>.<timestamp>" next to the image. */
    fun isOverlayOf(path: String, original: String): Boolean {
        val dir = original.substringBeforeLast('/')
        val stem = original.substringAfterLast('/').substringBeforeLast('.')
        val name = path.substringAfterLast('/')
        return path.substringBeforeLast('/') == dir && name.startsWith("$stem.") &&
            name.removePrefix("$stem.").let { it.isNotEmpty() && it.all(Char::isDigit) }
    }
}
