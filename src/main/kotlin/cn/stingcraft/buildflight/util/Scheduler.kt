package cn.stingcraft.buildflight.util

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import taboolib.common.platform.function.warning
import taboolib.platform.Folia
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/**
 * 统一的调度入口。
 *
 * 非 Folia 服务器使用 Bukkit 主线程调度；Folia 服务器使用实体区域调度器。
 * 所有涉及玩家实体状态（allowFlight / isFlying / BossBar / 背包 / GUI）的代码
 * 必须通过本工具投递，禁止在纯异步线程直接调用。
 */
fun interface TaskHandle {
    fun cancel()
}

object Scheduler {

    /** 插件在 plugin.yml 中的名字（由 TabooLib Gradle 插件生成）。 */
    private const val PLUGIN_NAME = "BuildFlight"

    val isFolia: Boolean
        get() = Folia.isFolia

    private val bukkitPlugin: org.bukkit.plugin.Plugin?
        get() = runCatching { Bukkit.getPluginManager().getPlugin(PLUGIN_NAME) }.getOrNull()

    private val entityClass: Class<*>? = runCatching {
        Class.forName("org.bukkit.entity.Entity")
    }.getOrNull()

    /**
     * 反射引用 Paper 的区域调度器接口，避免在非 Folia 服务端触发类加载失败，
     * 也避免把 paper-api 的类型写进本插件的直接依赖。
     */
    private val entitySchedulerClass: Class<*>? = runCatching {
        Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler")
    }.getOrNull()

    private val scheduledTaskClass: Class<*>? = runCatching {
        Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask")
    }.getOrNull()

    private val getSchedulerMethod: Method? = runCatching {
        entityClass?.getMethod("getScheduler")
    }.getOrNull()

    @Volatile
    private var warned = false

    private fun entityScheduler(player: Player): Any? {
        val method = getSchedulerMethod
        if (method == null) {
            warnOnce("无法获取 Folia EntityScheduler，玩家状态任务不会执行")
            return null
        }
        return runCatching { method.invoke(player) }.getOrElse {
            warnOnce("获取 Folia EntityScheduler 失败: ${it.message}")
            null
        }
    }

    private fun warnOnce(message: String) {
        if (!warned) {
            warned = true
            warning("[BuildFlight] $message")
        }
    }

    private fun cancelHandle(task: Any?): TaskHandle? {
        if (task == null) return null
        val cancel = runCatching { scheduledTaskClass?.getMethod("cancel") }.getOrNull() ?: return null
        return TaskHandle { runCatching { cancel.invoke(task) } }
    }

    /**
     * 在玩家所属的区域线程执行一次任务。
     * Folia 上若拿不到实体调度器，则直接放弃，绝不回退到全局线程修改玩家状态。
     */
    fun runOnEntity(player: Player, action: () -> Unit) {
        val plugin = bukkitPlugin ?: return
        if (isFolia) {
            val scheduler = entityScheduler(player) ?: return
            runCatching {
                val method = entitySchedulerClass?.getMethod(
                    "run",
                    org.bukkit.plugin.Plugin::class.java,
                    Consumer::class.java,
                    Runnable::class.java
                ) ?: return@runCatching
                method.invoke(scheduler, plugin, Consumer<Any?> { action() }, Runnable { })
            }.onFailure { warnOnce("提交 Folia 实体任务失败: ${it.message}") }
        } else {
            // 插件 disable 阶段调度可能被拒绝；这里静默失败，不把异常抛回业务层
            runCatching { Bukkit.getScheduler().runTask(plugin, Runnable { action() }) }
        }
    }

    /**
     * 一次性实体任务，带明确终态。
     *
     * 契约：`action` 与 `retired` **恰好有一个**会被调用，且只调用一次。
     * 四种结果全部收敛到同一个原子门：
     *  1. 调度接受 → 由调度器触发 `action`（或实体随后退休时触发 `retired`）；
     *  2. 调度器返回 null → 本方法立即补一次 `retired`；
     *  3. 调度器不可用 / 反射失败 / 提交异常 → 立即补一次 `retired`；
     *  4. 插件已卸载 → 立即补一次 `retired`。
     *
     * 返回 true 表示调度已接受（后续由调度器回调），false 表示已在本次调用内
     * 完成 `retired`，调用方无需（也不应）再补一次失败处理。
     *
     * 关于 null：对本机 26.3 服务端字节码核对确认，
     * `FoliaEntityScheduler.run → runDelayed → scheduleInternal` 在实体已退休
     * （`tickCount == -1`）时**直接返回 null 且不执行任何回调**——
     * 既不会调用 run，也不会调用 retired。因此 null 绝不能被当成
     * “调度器已经处理过 retired”，必须由这里补上终态，否则请求会静默消失。
     *
     * 用于发放这类“不能静默丢失”的操作：Folia 的实体任务在玩家退出后不会再执行，
     * 原来 `runOnEntity` 的 retired 是空 Runnable，请求会凭空消失。
     */
    fun runOnEntityOrElse(
        player: Player,
        action: () -> Unit,
        retired: () -> Unit
    ): Boolean {
        val finished = AtomicBoolean(false)
        val safeAction = { if (finished.compareAndSet(false, true)) action() }
        val safeRetired = { if (finished.compareAndSet(false, true)) retired() }

        val plugin = bukkitPlugin
        if (plugin == null) {
            runCatching { safeRetired() }
            return false
        }
        if (isFolia) {
            val scheduler = entityScheduler(player)
            if (scheduler == null) {
                runCatching { safeRetired() }
                return false
            }
            val method = runCatching {
                entitySchedulerClass?.getMethod(
                    "run",
                    org.bukkit.plugin.Plugin::class.java,
                    Consumer::class.java,
                    Runnable::class.java
                )
            }.getOrNull()
            if (method == null) {
                runCatching { safeRetired() }
                return false
            }
            val task = runCatching {
                method.invoke(
                    scheduler,
                    plugin,
                    Consumer<Any?> { runCatching(safeAction).onFailure { warnOnce("实体任务执行失败: ${it.message}") } },
                    Runnable { runCatching(safeRetired) }
                )
            }.getOrElse {
                warnOnce("提交 Folia 实体任务失败: ${it.message}")
                runCatching { safeRetired() }
                return false
            }
            if (task == null) {
                // 实体已退休：调度器不执行任何回调，由这里补终态（原子门保证只补一次）
                runCatching { safeRetired() }
                return false
            }
            return true
        }
        return runCatching {
            Bukkit.getScheduler().runTask(
                plugin,
                Runnable { runCatching(safeAction).onFailure { warnOnce("主线程任务执行失败: ${it.message}") } }
            )
            true
        }.getOrElse {
            runCatching { safeRetired() }
            false
        }
    }

    /**
     * 在玩家所属的区域线程上按固定周期执行任务。
     * 返回的句柄必须在玩家退出、飞行关闭或插件卸载时取消。
     */
    fun runAtFixedRate(
        player: Player,
        initialDelayTicks: Long,
        periodTicks: Long,
        action: () -> Unit
    ): TaskHandle? {
        if (periodTicks <= 0L) return null
        val plugin = bukkitPlugin ?: return null
        if (isFolia) {
            val scheduler = entityScheduler(player) ?: return null
            val method = runCatching {
                entitySchedulerClass?.getMethod(
                    "runAtFixedRate",
                    org.bukkit.plugin.Plugin::class.java,
                    Consumer::class.java,
                    Runnable::class.java,
                    java.lang.Long.TYPE,
                    java.lang.Long.TYPE
                )
            }.getOrNull() ?: return null
            val task = runCatching {
                method.invoke(
                    scheduler,
                    plugin,
                    Consumer<Any?> { action() },
                    Runnable { },
                    initialDelayTicks,
                    periodTicks
                )
            }.getOrElse {
                warnOnce("提交 Folia 周期任务失败: ${it.message}")
                null
            }
            return cancelHandle(task)
        }
        val task = runCatching {
            Bukkit.getScheduler().runTaskTimer(
                plugin,
                Runnable { action() },
                initialDelayTicks.coerceAtLeast(0L),
                periodTicks
            )
        }.getOrNull() ?: return null
        return TaskHandle { task.cancel() }
    }

    /**
     * 在玩家所属的区域线程上延迟执行一次任务。
     * 用于摔落保护的硬性超时兜底；返回的句柄在保护提前结束时必须取消。
     */
    fun runDelayedOnEntity(player: Player, delayTicks: Long, action: () -> Unit): TaskHandle? {
        if (delayTicks <= 0L) return null
        val plugin = bukkitPlugin ?: return null
        if (isFolia) {
            val scheduler = entityScheduler(player) ?: return null
            val method = runCatching {
                entitySchedulerClass?.getMethod(
                    "runDelayed",
                    org.bukkit.plugin.Plugin::class.java,
                    Consumer::class.java,
                    Runnable::class.java,
                    java.lang.Long.TYPE
                )
            }.getOrNull() ?: return null
            val task = runCatching {
                method.invoke(
                    scheduler,
                    plugin,
                    Consumer<Any?> { action() },
                    Runnable { },
                    delayTicks
                )
            }.getOrElse {
                warnOnce("提交 Folia 延迟任务失败: ${it.message}")
                null
            }
            return cancelHandle(task)
        }
        val task = runCatching {
            Bukkit.getScheduler().runTaskLater(plugin, Runnable { action() }, delayTicks)
        }.getOrNull() ?: return null
        return TaskHandle { task.cancel() }
    }
}
