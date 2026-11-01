package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceDeviceDirectoryGuardTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var primary: File
    private lateinit var extRoot: File

    private fun guard(): WorkspaceDeviceDirectoryGuard {
        primary = tmp.newFolder("files")
        extRoot = tmp.newFolder("sdcard")
        return WorkspaceDeviceDirectoryGuard(extRoot)
    }

    @Test
    fun `valid source inside external root resolves`() {
        val g = guard()
        val dir = File(extRoot, "Documents").apply { mkdirs() }
        assertEquals(dir.canonicalFile, g.resolveSource(dir.path))
    }

    @Test
    fun `external storage root itself can be connected`() {
        val g = guard()
        assertEquals(extRoot.canonicalFile, g.resolveSource(extRoot.path))
    }

    @Test
    fun `whole Android subtree is rejected case-insensitively`() {
        val g = guard()
        File(extRoot, "Android").mkdirs()
        File(extRoot, "android").mkdirs()
        File(extRoot, "Android/data").mkdirs()
        listOf("Android", "android", "Android/data").forEach {
            assertThrows("should reject $it", IllegalArgumentException::class.java) {
                g.resolveSource(File(extRoot, it).path)
            }
        }
    }

    @Test
    fun `outside primary shared storage root and its ancestor are rejected`() {
        val g = guard()
        assertThrows(IllegalArgumentException::class.java) { g.resolveSource(primary.path) }
        val child = File(primary, "sub").apply { mkdirs() }
        assertThrows(IllegalArgumentException::class.java) { g.resolveSource(child.path) }
        // 包含 primary 的祖先也不行 (temp 目录本身)
        assertThrows(IllegalArgumentException::class.java) {
            g.resolveSource(requireNotNull(primary.parentFile).path)
        }
    }

    @Test
    fun `missing or non-directory source is rejected and never created`() {
        val g = guard()
        val missing = File(extRoot, "nope")
        assertThrows(IllegalArgumentException::class.java) { g.resolveSource(missing.path) }
        assertTrue(!missing.exists())
        val file = File(extRoot, "f.txt").apply { writeText("x") }
        assertThrows(IllegalArgumentException::class.java) { g.resolveSource(file.path) }
    }

    @Test
    fun `colon and nul and relative paths are rejected`() {
        val g = guard()
        assertThrows(IllegalArgumentException::class.java) { g.resolveSource("a:b") }
        assertThrows(IllegalArgumentException::class.java) { g.resolveSource("relative/dir") }
        assertThrows(IllegalArgumentException::class.java) { g.resolveSource("${'$'}{extRoot.path}\u0000x") }
    }

    @Test
    fun `symlink escaping into Android subtree is rejected`() {
        val g = guard()
        File(extRoot, "Android/data").mkdirs()
        val link = File(extRoot, "link")
        java.nio.file.Files.createSymbolicLink(link.toPath(), File(extRoot, "Android").toPath())
        assertThrows(IllegalArgumentException::class.java) { g.resolveSource(link.path) }
    }

    @Test
    fun `alias rules and uniqueness and limit`() {
        val g = guard()
        val dir = File(extRoot, "d1").apply { mkdirs() }
        val mount = { alias: String, src: File -> WorkspaceDeviceMount("id-$alias", alias, src) }
        // bad aliases
        listOf("A", "1abc", "-x", "", "a".repeat(33), "UPPER").forEach { alias ->
            assertThrows("alias $alias", IllegalArgumentException::class.java) {
                g.validateConnections(listOf(mount(alias, dir)))
            }
        }
        // duplicates
        val d2 = File(extRoot, "d2").apply { mkdirs() }
        assertThrows(IllegalArgumentException::class.java) {
            g.validateConnections(listOf(mount("a", dir), mount("a", d2)))
        }
        // over limit
        assertThrows(IllegalArgumentException::class.java) {
            g.validateConnections((1..9).map { i ->
                val s = File(extRoot, "m$i").apply { mkdirs() }
                mount("m$i", s)
            })
        }
        // ok
        g.validateConnections(listOf(mount("docs", dir), mount("pics-2", d2)))
    }

    @Test
    fun `duplicate and nested sources across mounts are rejected`() {
        val g = guard()
        val parent = File(extRoot, "p").apply { mkdirs() }
        val child = File(parent, "c").apply { mkdirs() }
        assertThrows(IllegalArgumentException::class.java) {
            g.validateConnections(listOf(
                WorkspaceDeviceMount("1", "a", parent),
                WorkspaceDeviceMount("2", "b", File(parent, ".")), // same canonical
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            g.validateConnections(listOf(
                WorkspaceDeviceMount("1", "a", parent),
                WorkspaceDeviceMount("2", "b", child),
            ))
        }
    }

    @Test
    fun `single source outside primary or through symlink is rejected`() {
        val g = guard()
        val outside = tmp.newFolder("other-volume")
        val link = File(extRoot, "outside-link")
        java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { g.resolveSource(link.path) }
        val real = File(extRoot, "local-real").apply { mkdirs() }
        val internalLink = File(extRoot, "internal-link")
        java.nio.file.Files.createSymbolicLink(internalLink.toPath(), real.toPath())
        assertThrows(IllegalArgumentException::class.java) { g.resolveSource(internalLink.path) }
        assertThrows(IllegalArgumentException::class.java) { g.resolveSource("${extRoot.path}/../sdcard/local-real") }
    }

    @Test
    fun `nested source via symlink is rejected`() {
        val g = guard()
        val real = File(extRoot, "real").apply { mkdirs() }
        val link = File(extRoot, "lk")
        java.nio.file.Files.createSymbolicLink(link.toPath(), real.toPath())
        assertThrows(IllegalArgumentException::class.java) {
            g.validateConnections(listOf(
                WorkspaceDeviceMount("1", "a", real),
                WorkspaceDeviceMount("2", "b", link),
            ))
        }
    }
}
