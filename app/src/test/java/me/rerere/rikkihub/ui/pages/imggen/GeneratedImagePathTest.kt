package me.rerere.rikkihub.ui.pages.imggen

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GeneratedImagePathTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `model names with path separators become a single file name part`() {
        assertEquals("bytedance-seed_seedream-4.5", "bytedance-seed/seedream-4.5".toImageFileNamePart())
        assertEquals("a_b_c_d_e_f_g_h_i", """a\b:c*d?e"f<g>h|i""".toImageFileNamePart())
        assertEquals("gpt-image-2", "gpt-image-2".toImageFileNamePart())
    }

    @Test
    fun `records keep resolving to the flat file`() {
        val imagesDir = tempFolder.newFolder("images")
        val flat = File(imagesDir, "1700_vendor_model_0.png").apply { writeBytes(byteArrayOf(1)) }

        assertEquals(flat, resolveGeneratedImageFile(imagesDir, "images/1700_vendor_model_0.png", "vendor/model", 1700))
        assertEquals(
            File(imagesDir, "1700_gpt-image-2_0.png"),
            resolveGeneratedImageFile(imagesDir, "images/1700_gpt-image-2_0.png", "gpt-image-2", 1700),
        )
    }

    @Test
    fun `images saved by older versions under a model name sub directory are found again`() {
        val imagesDir = tempFolder.newFolder("images")
        // Old file name "1700_vendor/model_0.png": the "/" made "1700_vendor" a directory, the record kept "model_0.png".
        val legacy = File(imagesDir, "1700_vendor/model_0.png").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1))
        }

        assertEquals(legacy, resolveGeneratedImageFile(imagesDir, "images/model_0.png", "vendor/model", 1700))
    }
}
