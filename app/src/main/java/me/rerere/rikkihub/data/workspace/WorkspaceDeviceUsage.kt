package me.rerere.rikkihub.data.workspace

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * 设备目录 busy 探针桥（/tmp/workspace-direct/busy-api.txt 约定的跨模块契约）。
 *
 * 动机：`WorkspaceRuntimeGate.snapshot.activeOperationsByKind` 只能看到「已准入
 * 的在册操作」。两类真实忙碌落在这个口径之外：
 *  1. 生成已派发但 job 尚未走进 `withOperation` 的 admission 临界区；
 *  2. PENDING_APPROVAL 等待用户决策、**没有任何 job 在跑**——审批一旦落地
 *     就继续生成，此刻放设备结构变更会让恢复的工具拿新配置执行旧签发。
 *
 * 本类是「生产侧（ChatService）注入 → 领域侧（WorkspaceDeviceDirectoryService
 * 的 rejectIfBusy）消费」的纯内存回调注册表，避免 ChatService ↔ 领域服务的
 * 构造期 DI 环。probe 约定：**非 suspend、非阻塞、无 I/O、无 gate 重入**
 * （调用点在 publishPrepared 的 admissionMutex 临界区内）。probe 抛异常按
 * busy 处理（fail-closed，判不出就当忙，绝不放行）。
 *
 * 契约的另一端由领域 agent 在 `rejectIfBusy` 里 `usage?.isBusy(ws)` 消费；
 * 本文件不感知领域服务类型。
 */
class WorkspaceDeviceUsage {
    private val probes = ConcurrentHashMap<String, (workspaceId: String) -> Boolean>()

    /** 注册一个 busy 探针；返回注销函数（重复 owner 拒绝注册，防双写）。 */
    fun registerProbe(
        owner: String,
        probe: (workspaceId: String) -> Boolean,
    ): () -> Unit {
        require(owner.isNotBlank()) { "probe owner must not be blank" }
        check(probes.putIfAbsent(owner, probe) == null) {
            "Duplicate device-usage probe: $owner"
        }
        return { probes.remove(owner, probe) }
    }

    /**
     * 任一注册探针报告 busy 即为 busy。无探针 → false（未接线场景等价于
     * 「只按 gate 册表判定」，不凭空 busy）。探针异常 fail-closed 记为 busy。
     */
    fun isBusy(workspaceId: String): Boolean {
        for ((owner, probe) in probes) {
            val busy = try {
                probe(workspaceId)
            } catch (failure: Throwable) {
                Log.w(TAG, "device-usage probe '$owner' failed; treating as busy", failure)
                true
            }
            if (busy) return true
        }
        return false
    }

    private companion object {
        const val TAG = "WorkspaceDeviceUsage"
    }
}
