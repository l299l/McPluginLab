package dev.mpl.core

import dev.mpl.core.process.LineSplitter.Companion.render
import kotlin.test.Test
import kotlin.test.assertEquals

class LineSplitterTest {
    @Test
    fun stripsColors() = assertEquals("[INFO]: Done", render("\u001B[32m[INFO]: Done\u001B[0m\r"))

    @Test
    fun appliesJLineRedraw() = assertEquals("say hi", render("> s\u001B[1Dsa\u001B[2Dsay hi"))

    @Test
    fun clearsPromptBeforeLogLine() = assertEquals("[INFO]: hello", render("> \r\u001B[K[INFO]: hello"))

    @Test
    fun ignoresOsc() = assertEquals("title", render("\u001B]0;window\u0007title"))
}
