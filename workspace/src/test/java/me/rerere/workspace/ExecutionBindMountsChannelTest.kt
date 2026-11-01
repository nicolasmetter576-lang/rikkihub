package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 三通道 (持久 bash / App 终端 / Web 终端) 现在都吃同一份「完整执行挂载」列表
 * (WorkspaceRepository.executionBindMounts = legacy optional + 已验证设备挂载)。
 * 本测试锁定向 channel 交付时依赖的纯 JVM 契约:
 * - required 设备挂载 source 失效 → buildCommand 明确报错, 不起裸 rootfs 会话
 * - 可选 legacy 挂载 source 缺失 → 静默跳过 (旧行为)
 * - 空列表回落到 agentBindMounts 由调用方负责, 这里验证回落路径本身
 *
 * App/Web manager 本体与 WorkspacePtySession.awaitExit 依赖 Android/JNI,
 * JVM 侧只注入假挂载列表验证参数拼装, 不宣称真实进程/PTY 行为。
 */
class ExecutionBindMountsChannelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun prootSpec() = Triple(tmp.newFolder("files"), tmp.newFolder("linux"), tmp.newFile("proot"))

    @Test
    fun `full mount list carries device binds as required`() {
        val (files, linux, proot) = prootSpec()
        val deviceSrc = tmp.newFolder("device-src")
        val skills = tmp.newFolder("skills")

        // repo.executionBindMounts 的期望产物形态: optional legacy + required device
        val mounts = listOf(
            WorkspaceBindMount(skills, "/skills"),
            WorkspaceDeviceMount("id1", "docs", deviceSrc).toBindMount(),
        )
        val args = ProotLauncher.buildCommand(
            proot = proot, filesDir = files, linuxDir = linux, bindMounts = mounts,
        ).joinToString(" ")
        assertTrue(args.contains("${skills.absolutePath}:/skills"))
        assertTrue(args.contains("${deviceSrc.absolutePath}:/device/docs"))
    }

    @Test
    fun `stale device source aborts spawn instead of silently dropping the mount`() {
        val (files, linux, proot) = prootSpec()
        val gone = File(tmp.root, "revoked-device")
        val mounts = listOf(WorkspaceBindMount(gone, "/device/docs", required = true))
        assertThrows(IllegalStateException::class.java) {
            ProotLauncher.buildCommand(
                proot = proot, filesDir = files, linuxDir = linux, bindMounts = mounts,
            )
        }
    }

    @Test
    fun `empty repo mounts fall back to exposed legacy mounts only`() {
        // 调用方约定: executionBindMounts 为空时回落 agentBindMounts(exposedTargets);
        // 回落结果只含目标在暴露集合内的 optional 挂载, 不凭空继承全局 bindMounts。
        val skills = tmp.newFolder("skills")
        val upload = tmp.newFolder("upload")
        val mgr = WorkspaceManager(
            baseDir = tmp.newFolder("ws"),
            bindMounts = listOf(
                WorkspaceBindMount(skills, "/skills"),
                WorkspaceBindMount(upload, "/upload"),
                WorkspaceBindMount(tmp.newFolder("other"), "/other"),
            ),
        )
        val fallback = mgr.agentBindMounts(setOf("/skills"))
        assertEquals(listOf("/skills", "/other"), fallback.map { it.target })
        assertTrue("/upload" !in fallback.map { it.target })
    }
}
