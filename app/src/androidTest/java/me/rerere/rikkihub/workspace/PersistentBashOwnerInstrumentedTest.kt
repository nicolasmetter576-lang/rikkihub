package me.rerere.rikkihub.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.rikkihub.data.ai.tools.PersistentBashOwner
import me.rerere.rikkihub.data.ai.tools.WorkspacePersistentBashSessionManager
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.data.workspace.defaultRootfsUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * 持久 bash 按所属者分会话：并行的子代理各用自己的 shell，cwd 互不影响；
 * 普通对话（无所属者）仍是每个工作区一个会话；closeOwner 只关自己那一个。
 * 需要真实 rootfs（`-e rootfs false` 时跳过）。
 */
@RunWith(AndroidJUnit4::class)
class PersistentBashOwnerInstrumentedTest {
    private val args get() = InstrumentationRegistry.getArguments()
    private val repo get() = GlobalContext.get().get<WorkspaceRepository>()
    private val bash get() = GlobalContext.get().get<WorkspacePersistentBashSessionManager>()

    @Test(timeout = 15 * 60_000)
    fun ownersHaveSeparateShells() = runBlocking {
        assumeTrue(args.getString("rootfs") != "false")
        val workspace = repo.create("itest-bash-owner-${System.currentTimeMillis()}")
        try {
            assertTrue(repo.installRootfs(workspace.id, defaultRootfsUrl()))
            fun String.cwd() = trim().lines().last().trim()

            assertEquals("/tmp", bash.execute(workspace.id, "cd /tmp && pwd", owner = "a").cwd())
            val other = bash.execute(workspace.id, "pwd", owner = "b").cwd()
            assertNotEquals("/tmp", other)
            val shared = bash.execute(workspace.id, "pwd").cwd()
            assertNotEquals("/tmp", shared)
            // 所属者经协程上下文传入，与显式参数等价
            val viaContext = withContext(PersistentBashOwner("a")) { bash.execute(workspace.id, "pwd") }
            assertEquals("/tmp", viaContext.cwd())

            bash.closeOwner(workspace.id, "a")
            // a 的 shell 已关闭：再次使用是新 shell；b 和共用会话不受影响
            assertNotEquals("/tmp", bash.execute(workspace.id, "pwd", owner = "a").cwd())
            assertEquals("/", bash.execute(workspace.id, "cd / && pwd").cwd())
            assertEquals("/", bash.execute(workspace.id, "pwd").cwd())
            assertEquals(other, bash.execute(workspace.id, "pwd", owner = "b").cwd())
        } finally {
            bash.closeWorkspace(workspace.id)
            repo.delete(workspace.id)
        }
    }
}
