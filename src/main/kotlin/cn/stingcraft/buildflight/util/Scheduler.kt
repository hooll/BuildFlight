package cn.stingcraft.buildflight.util

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import taboolib.common.platform.function.warning
import taboolib.platform.Folia
import java.lang.reflect.Method
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
