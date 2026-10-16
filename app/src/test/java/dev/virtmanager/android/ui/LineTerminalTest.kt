package dev.virtmanager.android.ui

import dev.virtmanager.android.ui.console.LineTerminal
import org.junit.Assert.assertEquals
import org.junit.Test

class LineTerminalTest {
    @Test fun handlesControlCharactersAndEscapes() {
        val t = LineTerminal()
        t.feed("Booting\r\n\u001b[1;32mOK\u001b[0m done\r\nprogress 10%\rprogress 99%\r\nab\bc\u001b]0;title\u0007\n")
        assertEquals(listOf("Booting", "OK done", "progress 99%", "ac", ""), t.snapshot())
    }

    @Test fun eraseToEndOfLine() {
        val t = LineTerminal()
        t.feed("login: longname\rlogin: \u001b[K")
        assertEquals(listOf("login: "), t.snapshot())
    }

    @Test fun escapeSplitAcrossChunks() {
        val t = LineTerminal()
        t.feed("a\u001b[")
        t.feed("31mb")
        assertEquals(listOf("ab"), t.snapshot())
    }

    @Test fun boundedScrollback() {
        val t = LineTerminal(maxLines = 3)
        t.feed("1\n2\n3\n4\n5")
        assertEquals(listOf("3", "4", "5"), t.snapshot())
    }
}
