package dev.virtmanager.android.libvirt

import dev.virtmanager.android.libvirt.config.Dom
import dev.virtmanager.android.libvirt.config.child
import dev.virtmanager.android.libvirt.config.text

/** Checks on user-supplied domain XML before it is defined. */
object DomainXmlEditor {
    /** Returns the `<name>` and `<uuid>` of a domain XML document, or throws if it isn't one. */
    fun identity(xml: String): Pair<String, String?> {
        val root = Dom.parse(xml).documentElement
        require(root.nodeName == "domain") { "The root element must be <domain>" }
        val name = root.child("name")?.text()
        require(!name.isNullOrEmpty()) { "The domain has no <name>" }
        return name to root.child("uuid")?.text()
    }
}
