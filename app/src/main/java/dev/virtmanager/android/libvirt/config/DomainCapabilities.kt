package dev.virtmanager.android.libvirt.config

/**
 * What the host's hypervisor supports for a given VM, from `virsh domcapabilities` (the same
 * source virt-manager uses to fill its option lists).
 */
class DomainCapabilities(private val enums: Map<String, List<String>>, val firmwareEfi: Boolean?) {

    /** Values of enum [name] for [device] (e.g. "graphics", "type"), or null if not reported. */
    fun values(device: String, name: String): List<String>? = enums["$device.$name"]

    /**
     * [known] narrowed to what the host supports. Unknown capability (older libvirt, or the
     * query failed) keeps the full list rather than hiding everything.
     */
    fun filter(known: List<String>, device: String, name: String, keep: String? = null): List<String> {
        val supported = values(device, name) ?: return known
        return known.filter { it in supported || it == keep }
    }

    companion object {
        val UNKNOWN = DomainCapabilities(emptyMap(), null)

        fun parse(xml: String): DomainCapabilities = runCatching {
            val root = Dom.parse(xml).documentElement
            val enums = mutableMapOf<String, List<String>>()
            root.child("devices")?.elements()?.forEach { dev ->
                if (dev.attr("supported") == "no") {
                    enums["${dev.tagName}.supported"] = emptyList()
                    return@forEach
                }
                dev.children("enum").forEach { e ->
                    enums["${dev.tagName}.${e.attr("name")}"] = e.children("value").mapNotNull { it.text() }
                }
            }
            val os = root.child("os")
            val fw = os?.children("enum")?.firstOrNull { it.attr("name") == "firmware" }
                ?.children("value")?.mapNotNull { it.text() }
            DomainCapabilities(enums, fw?.contains("efi"))
        }.getOrDefault(UNKNOWN)
    }
}
