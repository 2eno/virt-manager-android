package dev.virtmanager.android.libvirt.config

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

/** Small DOM helpers for editing libvirt XML (namespace-unaware, like libvirt's own output). */
object Dom {
    fun parse(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }

    fun serialize(node: Node): String {
        val t = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        }
        return StringWriter().also { t.transform(DOMSource(node), StreamResult(it)) }.toString()
    }

    /** Parses a snippet such as `<disk .../>` and imports it into [doc]. */
    fun importSnippet(doc: Document, xml: String): Element {
        val el = parse(xml.trim()).documentElement
        return doc.importNode(el, true) as Element
    }
}

fun Element.elements(): List<Element> {
    val out = mutableListOf<Element>()
    for (i in 0 until childNodes.length) {
        val n = childNodes.item(i)
        if (n.nodeType == Node.ELEMENT_NODE) out += n as Element
    }
    return out
}

fun Element.children(tag: String): List<Element> = elements().filter { it.tagName == tag }
fun Element.child(tag: String): Element? = children(tag).firstOrNull()

/** Returns the first child [tag], creating (and appending) it if missing. */
fun Element.ensure(tag: String): Element =
    child(tag) ?: ownerDocument.createElement(tag).also { appendChild(it) }

fun Element.attr(name: String): String? = getAttribute(name).takeIf { it.isNotEmpty() }

/** Sets or removes (when null/blank) an attribute. */
fun Element.setAttr(name: String, value: String?) {
    if (value.isNullOrBlank()) removeAttribute(name) else setAttribute(name, value)
}

fun Element.text(): String? = textContent?.trim()?.takeIf { it.isNotEmpty() }

fun Element.setText(value: String) {
    while (firstChild != null) removeChild(firstChild)
    appendChild(ownerDocument.createTextNode(value))
}

fun Element.removeChildren(tag: String) = children(tag).forEach { removeChild(it) }

/** Sets the text of child [tag], or removes the child when [value] is null/blank. */
fun Element.setChildText(tag: String, value: String?) {
    if (value.isNullOrBlank()) removeChildren(tag) else ensure(tag).setText(value)
}
