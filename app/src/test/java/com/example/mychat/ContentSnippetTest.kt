package com.example.mychat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.5.0 模块四：contentSnippet 命中片段提取 */
class ContentSnippetTest {
    @Test fun hitAtStart() {
        val s = contentSnippet("hello world this is a long text about kotlin lang", "hello")
        assertTrue(s.startsWith("hello"))
        assertTrue(s.length <= 62)
    }

    @Test fun hitInMiddleAddsEllipsis() {
        val s = contentSnippet("aaaa bbbb cccc dddd eeee ffff gggg KEYWORD hhhh iiii jjjj kkkk llll mmmm nnnn oooo", "KEYWORD")
        assertTrue(s.startsWith("…"))
        assertTrue(s.contains("KEYWORD"))
    }

    @Test fun caseInsensitive() {
        val s = contentSnippet("find The Needle here", "the needle")
        assertTrue(s.contains("The Needle"))
    }

    @Test fun notFoundReturnsHead() {
        val s = contentSnippet("plain text only", "zzz")
        assertEquals("plain text only", s)
    }

    @Test fun longTextNoHitTruncates() {
        val long = "x".repeat(200)
        val s = contentSnippet(long, "zzz")
        assertEquals(61, s.length)
    }

    @Test fun newlinesFlattened() {
        val s = contentSnippet("line1" + 10.toChar() + "line2 keyword here", "keyword")
        assertTrue(!s.contains(10.toChar()))
        assertTrue(s.contains("line2 keyword here"))
    }

    @Test fun emptyContent() {
        assertEquals("", contentSnippet("", "q"))
    }

    @Test fun emptyQuery() {
        val s = contentSnippet("some content", "")
        assertEquals("some content", s)
    }

    @Test fun shortContentHit() {
        val s = contentSnippet("kotlin", "kotlin")
        assertEquals("kotlin", s)
    }
}
