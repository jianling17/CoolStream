package com.example.mychat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.0 模块二：web_fetch 的纯函数单测。
 *
 * 这一层是「字符串进、字符串出」，不需要网络、不需要 Context，
 * 所以剥 HTML 的每一条规则都能在这里钉死 ——
 * 真机上抓到什么页面是不可控的，唯一能保证回归的就是这些断言。
 */
class WebFetchTest {

    // ---------- isHttpUrl ----------

    @Test
    fun isHttpUrlAcceptsHttps() {
        assertTrue(isHttpUrl("https://example.com/a?b=1"))
    }

    @Test
    fun isHttpUrlAcceptsPlainHttp() {
        assertTrue(isHttpUrl("http://example.com"))
    }

    @Test
    fun isHttpUrlIsCaseInsensitive() {
        assertTrue(isHttpUrl("HTTPS://EXAMPLE.COM"))
    }

    @Test
    fun isHttpUrlTrimsSurroundingSpace() {
        assertTrue(isHttpUrl("  https://example.com  "))
    }

    @Test
    fun isHttpUrlRejectsOtherSchemes() {
        assertFalse(isHttpUrl("file:///sdcard/a.txt"))
        assertFalse(isHttpUrl("javascript:alert(1)"))
        assertFalse(isHttpUrl("ftp://example.com"))
        assertFalse(isHttpUrl("data:text/html,<b>x</b>"))
    }

    @Test
    fun isHttpUrlRejectsBareHost() {
        assertFalse(isHttpUrl("example.com"))
        assertFalse(isHttpUrl(""))
    }

    // ---------- htmlTitle ----------

    @Test
    fun htmlTitleReadsSimpleTitle() {
        assertEquals("Hello", htmlTitle("<html><head><title>Hello</title></head></html>"))
    }

    @Test
    fun htmlTitleIgnoresAttributesOnTitleTag() {
        assertEquals("Doc", htmlTitle("<title lang=\"zh\">Doc</title>"))
    }

    @Test
    fun htmlTitleDecodesEntities() {
        assertEquals("A & B", htmlTitle("<title>A &amp; B</title>"))
    }

    @Test
    fun htmlTitleCollapsesInnerWhitespace() {
        assertEquals("A B", htmlTitle("<title>  A   B  </title>"))
    }

    @Test
    fun htmlTitleMissingReturnsEmpty() {
        assertEquals("", htmlTitle("<html><body>no title</body></html>"))
    }

    // ---------- htmlToPlainText：删块 ----------

    @Test
    fun removesScriptContent() {
        assertEquals("a" + "\n" + "b", htmlToPlainText("<div>a</div><script>var x = 1;</script><div>b</div>"))
    }

    @Test
    fun removesStyleContent() {
        assertEquals("hi", htmlToPlainText("<style>.a{color:red}</style><p>hi</p>"))
    }

    @Test
    fun removesComments() {
        assertEquals("a b", htmlToPlainText("<p>a<!-- hidden -->b</p>"))
    }

    @Test
    fun removesNoscriptAndIframe() {
        assertEquals("x", htmlToPlainText("<noscript>off</noscript><iframe src=\"y\"></iframe><p>x</p>"))
    }

    @Test
    fun cutsRestWhenScriptIsUnclosed() {
        assertEquals("a", htmlToPlainText("<p>a</p><script>var x = 1; if (a < b) {</script>"))
    }

    @Test
    fun keepsTextAfterClosedScriptWhenAnotherOpensUnclosed() {
        assertEquals("a", htmlToPlainText("<script>1</script><p>a</p><script>2"))
    }

    // ---------- htmlToPlainText：换行与空白 ----------

    @Test
    fun blockTagsBecomeNewlines() {
        assertEquals("one" + "\n" + "two", htmlToPlainText("<p>one</p><p>two</p>"))
    }

    @Test
    fun brBecomesNewline() {
        assertEquals("a" + "\n" + "b", htmlToPlainText("<p>a<br>b</p>"))
    }

    @Test
    fun listItemsBecomeNewlines() {
        assertEquals("one" + "\n" + "two", htmlToPlainText("<ul><li>one</li><li>two</li></ul>"))
    }

    @Test
    fun inlineTagsBecomeSpaces() {
        assertEquals("a b", htmlToPlainText("<div><span>a</span><span>b</span></div>"))
    }

    @Test
    fun collapsesRunsOfSpaces() {
        assertEquals("a b", htmlToPlainText("<p>a     b</p>"))
    }

    @Test
    fun dropsEmptyLines() {
        assertEquals("a" + "\n" + "b", htmlToPlainText("<div>a</div><div>   </div><div>b</div>"))
    }

    @Test
    fun uppercaseTagsAreHandled() {
        assertEquals("a" + "\n" + "b", htmlToPlainText("<P>a</P><P>b</P>"))
    }

    @Test
    fun emptyInputReturnsEmpty() {
        assertEquals("", htmlToPlainText(""))
        assertEquals("", htmlToPlainText("   "))
    }

    @Test
    fun keepsChineseText() {
        assertEquals("你好，世界", htmlToPlainText("<div>你好，世界</div>"))
    }

    // ---------- decodeHtmlEntities ----------

    @Test
    fun decodesNamedEntities() {
        assertEquals("< > &", decodeHtmlEntities("&lt; &gt; &amp;"))
    }

    @Test
    fun decodesNbspToSpace() {
        assertEquals("a  b", decodeHtmlEntities("a&nbsp;&nbsp;b"))
    }

    @Test
    fun decodesDecimalEntities() {
        assertEquals("你好", decodeHtmlEntities("&#20320;&#22909;"))
    }

    @Test
    fun decodesHexEntities() {
        assertEquals("你好", decodeHtmlEntities("&#x4F60;&#x597D;"))
    }

    @Test
    fun leavesUnknownEntityAlone() {
        assertEquals("&zzz;", decodeHtmlEntities("&zzz;"))
    }

    @Test
    fun doesNotDoubleDecodeAmp() {
        assertEquals("&lt;", decodeHtmlEntities("&amp;lt;"))
    }

    @Test
    fun passesThroughTextWithoutAmpersand() {
        assertEquals("plain text", decodeHtmlEntities("plain text"))
    }

    // ---------- normalizeTextLines ----------

    @Test
    fun normalizeTrimsAndDropsBlankLines() {
        assertEquals("a" + "\n" + "b" + "\n" + "c", normalizeTextLines("a" + "\n" + "\n" + "  b  " + "\n" + " c "))
    }

    @Test
    fun normalizeEmptyStaysEmpty() {
        assertEquals("", normalizeTextLines(""))
    }

    // ---------- truncateWebText ----------

    @Test
    fun truncateKeepsShortTextAsIs() {
        val text = "short body"
        assertEquals(text, truncateWebText(text, 100))
    }

    @Test
    fun truncateCutsToLimitAndSaysOriginalLength() {
        val text = "a".repeat(6000)
        val out = truncateWebText(text, 5000)
        assertTrue(out.startsWith("a".repeat(5000)))
        assertTrue(out.contains("正文共 6000 字"))
        assertTrue(out.contains("前 5000 字"))
    }

    @Test
    fun truncateTrimsTrailingWhitespaceAtCutPoint() {
        val text = "a".repeat(98) + "  " + "b".repeat(50)
        val out = truncateWebText(text, 100)
        assertTrue(out.startsWith("a".repeat(98) + "\n"))
    }

    @Test
    fun truncateWithZeroLimitReturnsText() {
        val text = "x".repeat(9000)
        assertEquals(text, truncateWebText(text, 0))
    }

    @Test
    fun defaultLimitIsFiveThousand() {
        assertEquals(5000, WEB_FETCH_MAX_CHARS)
    }
}
