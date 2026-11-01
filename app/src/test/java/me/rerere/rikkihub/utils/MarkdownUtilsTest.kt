package me.rerere.rikkihub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MarkdownUtilsTest {

    @Test
    fun `strip html tags but keep text`() {
        val result = "<details>\n<summary>标题</summary>\n\n内容\n\n</details>".stripMarkdown()
        assertFalse("朗读文本里不应留下 HTML 标签: $result", result.contains("<") || result.contains(">"))
        assertEquals("标题\n内容", result.replace(Regex("\\s*\n\\s*"), "\n"))
    }

    @Test
    fun `strip inline html tags with attributes`() {
        assertEquals(
            "这是重点文字",
            "这是<b class=\"x\">重点</b><span style='color:red'>文字</span>".stripMarkdown()
        )
    }

    @Test
    fun `line break tags do not join sentences`() {
        assertEquals("第一行\n第二行", "第一行<br/>第二行".stripMarkdown())
        assertEquals("第一行\n第二行", "第一行<br>第二行".stripMarkdown())
    }

    @Test
    fun `strip html comments and style blocks`() {
        assertEquals("正文", "<!-- 注释 --><style>.a{color:red}</style>正文".stripMarkdown())
    }

    @Test
    fun `decode common html entities once`() {
        assertEquals("a < b & c", "a &lt; b &amp; c".stripMarkdown())
    }

    @Test
    fun `keep comparison signs that are not tags`() {
        assertEquals("1 < 2 且 3 > 2", "1 < 2 且 3 > 2".stripMarkdown())
        assertEquals("条件 x<y && y>0", "条件 x<y && y>0".stripMarkdown())
    }

    @Test
    fun `keep bracket style tts markers`() {
        assertEquals("(开心) 你好 [笑]", "(开心) 你好 [笑]".stripMarkdown())
    }

    @Test
    fun `existing markdown stripping is unchanged`() {
        assertEquals("这是重点和链接", "这是**重点**和[链接](https://example.com)".stripMarkdown())
        assertEquals("运行  即可\n\n结束", "运行 `ls` 即可\n\n```kotlin\nval a = 1\n```\n\n结束".stripMarkdown())
        assertEquals("标题\n第一项\n第二项\n\n引用", "# 标题\n\n- 第一项\n- 第二项\n\n> 引用".stripMarkdown())
    }
}
