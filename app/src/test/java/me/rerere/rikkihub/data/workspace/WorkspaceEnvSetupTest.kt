package me.rerere.rikkihub.data.workspace

import me.rerere.rikkihub.data.workspace.WorkspaceEnvItem.AGY
import me.rerere.rikkihub.data.workspace.WorkspaceEnvItem.BASE
import me.rerere.rikkihub.data.workspace.WorkspaceEnvItem.DOCS
import me.rerere.rikkihub.data.workspace.WorkspaceEnvItem.FONTS
import me.rerere.rikkihub.data.workspace.WorkspaceEnvItem.NODE
import me.rerere.rikkihub.data.workspace.WorkspaceEnvItem.PYTHON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class WorkspaceEnvSetupTest {
    @Test
    fun selectingAnItemSelectsWhatItNeedsAndDeselectingDropsItsDependents() {
        assertEquals(setOf(DOCS, PYTHON), WorkspaceEnvSetup.toggle(emptySet(), DOCS, true))
        assertEquals(setOf(AGY, BASE), WorkspaceEnvSetup.toggle(emptySet(), AGY, true))
        // BASE off takes NODE and AGY with it, PYTHON stays
        assertEquals(setOf(PYTHON), WorkspaceEnvSetup.toggle(setOf(BASE, NODE, AGY, PYTHON), BASE, false))
        assertEquals(setOf(BASE), WorkspaceEnvSetup.toggle(setOf(BASE, NODE), NODE, false))
    }

    @Test
    fun scriptInstallsRequirementsEvenWhenOnlyTheDependentWasPassed() {
        val script = WorkspaceEnvSetup.setupScript(setOf(DOCS), WorkspaceEnvMirror.OFFICIAL, null)
        val install = script.lines().single { it.startsWith("apt-get install") }
        assertTrue(install, " python3-pip " in "$install ")
        assertTrue(install, " pandoc " in "$install ")
        assertTrue(script, "pip install --no-cache-dir --root-user-action=ignore python-docx" in script)
        assertFalse(script, "step node" in script)
    }

    @Test
    fun officialSourceRestoresUbuntuHostsAndDropsMirrorConfig() {
        val script = WorkspaceEnvSetup.setupScript(setOf(BASE), WorkspaceEnvMirror.OFFICIAL, null)
        assertTrue(script, "http://ports.ubuntu.com/ubuntu-ports/" in script)
        assertTrue(script, "http://archive.ubuntu.com/ubuntu/" in script)
        assertTrue(script, "rm -f /usr/local/etc/npmrc" in script)
        assertFalse(script, "index-url" in script)
        assertTrue(script, "break-system-packages = true" in script)
    }

    @Test
    fun chinaMirrorPointsAptPipNpmAndNodeAtTheMirror() {
        val script = WorkspaceEnvSetup.setupScript(setOf(NODE), WorkspaceEnvMirror.CHINA, null)
        assertTrue(script, "http://mirrors.aliyun.com/ubuntu-ports/" in script)
        assertTrue(script, "index-url = https://mirrors.aliyun.com/pypi/simple/" in script)
        assertTrue(script, "registry=https://registry.npmmirror.com/" in script)
        assertTrue(script, "base=https://npmmirror.com/mirrors/node/latest-v24.x" in script)
    }

    @Test
    fun onlyAPlainTimeZoneIdReachesTheScript() {
        val good = WorkspaceEnvSetup.setupScript(setOf(BASE), WorkspaceEnvMirror.OFFICIAL, "Asia/Shanghai")
        assertTrue(good, "ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime" in good)
        listOf("Asia/Shanghai; rm -rf /", "../etc/passwd", "Etc/\$(id)", "").forEach { zone ->
            val script = WorkspaceEnvSetup.setupScript(setOf(BASE), WorkspaceEnvMirror.OFFICIAL, zone)
            assertFalse(zone, "/etc/localtime" in script)
        }
    }

    @Test
    fun agentStepsUseTheCliPanelInstallers() {
        val script = WorkspaceEnvSetup.setupScript(setOf(AGY), WorkspaceEnvMirror.OFFICIAL, null)
        assertTrue(script, "step agy" in script)
        assertTrue(script, "antigravity.google/cli/install.sh" in script)
        assertFalse(script, "step droid" in script)
    }

    @Test
    fun checkOutputParsesInstalledItemsMirrorAndMissingApt() {
        val status = WorkspaceEnvSetup.parseCheck("::have base\n::have node\n  ::have fonts \n::have nope\n::mirror CHINA\n")
        assertEquals(setOf(BASE, NODE, FONTS), status.installed)
        assertEquals(WorkspaceEnvMirror.CHINA, status.mirror)
        assertTrue(status.aptAvailable)
        val alpine = WorkspaceEnvSetup.parseCheck("::no-apt\n")
        assertFalse(alpine.aptAvailable)
        assertEquals(WorkspaceEnvMirror.OFFICIAL, alpine.mirror)
        assertEquals(emptySet<WorkspaceEnvItem>(), alpine.installed)
    }

    @Test
    fun stepMarkersAreRecognised() {
        assertEquals("node", WorkspaceEnvSetup.stepOf("::step node"))
        assertEquals(null, WorkspaceEnvSetup.stepOf("Get:1 http://archive.ubuntu.com noble InRelease"))
    }

    /** Every generated script must at least parse; skipped where the host has no bash. */
    @Test
    fun generatedScriptsAreValidBash() {
        val bash = File("/bin/bash").takeIf { it.canExecute() } ?: return
        val scripts = listOf(
            WorkspaceEnvSetup.setupScript(WorkspaceEnvItem.entries.toSet(), WorkspaceEnvMirror.CHINA, "Europe/Berlin"),
            WorkspaceEnvSetup.setupScript(setOf(BASE), WorkspaceEnvMirror.OFFICIAL, null),
            WorkspaceEnvSetup.checkScript(),
        )
        scripts.forEach { script ->
            val process = ProcessBuilder(bash.path, "-n").redirectErrorStream(true).start()
            process.outputStream.use { it.write(script.toByteArray()) }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(process.inputStream.bufferedReader().readText(), 0, process.exitValue())
        }
    }
}
