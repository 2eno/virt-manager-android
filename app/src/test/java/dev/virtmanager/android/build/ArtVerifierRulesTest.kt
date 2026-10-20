package dev.virtmanager.android.build

import dev.virtmanager.android.vnc.RfbClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import java.io.File
import java.util.zip.ZipFile

/**
 * Bytecode the JVM accepts but Android's ART verifier rejects, which unit tests (on the JVM)
 * can't catch otherwise. A method that holds a monitor must have every instruction until the
 * monitorexit inside a catch-all handler; the stand-alone copy Kotlin keeps of an inline
 * function with `synchronized` calls its InlineMarker outside of it, and ART then rejects the
 * whole class ("expected to be within a catch-all for an instruction where a monitor is held").
 */
class ArtVerifierRulesTest {
    @Test fun noInlineMarkersWhileHoldingAMonitor() {
        val classes = appClasses()
        assertTrue("app classes not found", classes.keys.any { it.endsWith("vnc/RfbClient.class") })
        val offenders = classes.values.flatMap { bytes ->
            val node = ClassNode().also { ClassReader(bytes).accept(it, 0) }
            node.methods.filter { m ->
                val insns = m.instructions.toArray()
                insns.any { it.opcode == Opcodes.MONITORENTER } &&
                    insns.any { it is MethodInsnNode && it.owner == "kotlin/jvm/internal/InlineMarker" }
            }.map { "${node.name}.${it.name}${it.desc}" }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    /** The app's compiled classes, which the test classpath has as a directory or a jar. */
    private fun appClasses(): Map<String, ByteArray> {
        val root = File(RfbClient::class.java.protectionDomain.codeSource.location.toURI())
        if (root.isDirectory) {
            return root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }
                .associate { it.relativeTo(root).path to it.readBytes() }
        }
        return ZipFile(root).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".class") && it.name.startsWith("dev/virtmanager/") }
                .associate { e -> e.name to zip.getInputStream(e).use { it.readBytes() } }
        }
    }
}
