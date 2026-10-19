package dev.virtmanager.android.libvirt

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Targeted edits of a domain's (inactive) XML that are then saved with `virsh define`. */
object DomainXmlEditor {

    private fun parse(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }

    private fun serialize(doc: Document): String {
        val t = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        }
        return StringWriter().also { t.transform(DOMSource(doc), StreamResult(it)) }.toString()
    }

    private fun Element.childElements(tag: String): List<Element> {
        val out = mutableListOf<Element>()
        for (i in 0 until childNodes.length) {
            val n = childNodes.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && n.nodeName == tag) out += n as Element
        }
        return out
    }

    /**
     * Points the [index]-th network interface at [source] and optionally changes its [model].
     * The MAC address is kept; host-side runtime details (`<target>`, `<alias>`) are dropped.
     */
    fun setInterface(xml: String, index: Int, source: NetworkSource, model: String? = null): String {
        require(source !is NetworkSource.None) { "Use removeInterface to drop a NIC" }
        val doc = parse(xml)
        val devices = doc.documentElement.childElements("devices").firstOrNull()
            ?: throw IllegalArgumentException("Domain has no <devices>")
        val nic = devices.childElements("interface").getOrNull(index)
            ?: throw IllegalArgumentException("No network interface #$index")

        nic.childElements("source").forEach { nic.removeChild(it) }
        nic.childElements("target").forEach { nic.removeChild(it) }
        nic.childElements("alias").forEach { nic.removeChild(it) }

        val src = doc.createElement("source")
        when (source) {
            is NetworkSource.Virtual -> {
                nic.setAttribute("type", "network")
                src.setAttribute("network", source.network)
            }
            is NetworkSource.Bridge -> {
                nic.setAttribute("type", "bridge")
                src.setAttribute("bridge", source.bridge)
            }
            NetworkSource.None -> Unit
        }
        // Keep <mac> first, as libvirt writes it, so diffs of the XML stay readable.
        val mac = nic.childElements("mac").firstOrNull()
        nic.insertBefore(src, mac?.nextSibling ?: nic.firstChild)

        if (model != null) {
            val m = nic.childElements("model").firstOrNull() ?: doc.createElement("model").also { nic.appendChild(it) }
            m.setAttribute("type", model)
        }
        return serialize(doc)
    }

    /** Returns the `<name>` and `<uuid>` of a domain XML document, or throws if it isn't one. */
    fun identity(xml: String): Pair<String, String?> {
        val root = parse(xml).documentElement
        require(root.nodeName == "domain") { "The root element must be <domain>" }
        val name = root.childElements("name").firstOrNull()?.textContent?.trim()
        require(!name.isNullOrEmpty()) { "The domain has no <name>" }
        return name to root.childElements("uuid").firstOrNull()?.textContent?.trim()
    }
}
