package cn.stingcraft.buildflight.database

import cn.stingcraft.buildflight.config.PluginConfig
import taboolib.common.io.newFile
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.warning
import taboolib.expansion.Database
import taboolib.expansion.buildPlayerDatabase
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** 数据库读取结果，区分“没有记录”和“读取失败”，避免失败时误发初始能量。 */
sealed class LoadResult {
    data class Loaded(val energy: Double, val combatUntil: Long) : LoadResult()
    data object Missing : LoadResult()
    data object Failed : LoadResult()
}

/**
 * 基于 TabooLib DatabasePlayer 的 SQLite 持久化。
 *
 * 这里使用独立的 Database 实例（而不是全局 setupPlayerDatabase），
 * 避免与其他同样使用 DatabasePlayer 的插件互相覆盖数据库引用。
 *
 * 所有数据库访问都经由单线程 IO 执行器串行化，保证同一玩家的写入顺序；
 * 绝不在该线程上调用任何 Bukkit 玩家/背包/GUI/BossBar API。
 */
object EnergyStorage {

    private const val KEY = "buildflight_energy"
    private const val TABLE = "buildflight_energy"

    @Volatile
    private var database: Database? = null

    @Volatile
    private var executor: ScheduledExecutorService? = null

    @Volatile
    private var ready = false

    fun init(): Boolean {
        return runCatching {
            database = buildPlayerDatabase(
                newFile(getDataFolder(), PluginConfig.databaseFile),
                TABLE
            )
            executor = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "BuildFlight-IO").apply { isDaemon = true }
            }
            ready = true
            true
        }.getOrElse {
            warning("[BuildFlight] 初始化 SQLite 失败，飞行与兑换将不可用: $it")
            database = null
            ready = false
            false
        }
    }

    fun isReady(): Boolean = ready

    /**
     * 载荷格式：`能量|战斗冷却结束时间戳`。
     * 只有能量是旧格式时也能兼容（没有 `|` 视为无冷却）。
     */
    fun pack(energy: Double, combatUntil: Long = 0L): String = "$energy|$combatUntil"

    fun load(uuid: UUID): LoadResult {
        val db = database
        if (!ready || db == null) return LoadResult.Failed
        return runCatching {
            val raw = db.get(uuid.toString(), KEY)
            when {
                // 真正没有记录：可以按新档案创建
                raw == null -> LoadResult.Missing
                // 有记录但是空串：属于损坏，不能当作“无记录”覆盖
                raw.isBlank() -> {
                    warning("[BuildFlight] 玩家 $uuid 的能量记录为空，按损坏处理，本次不覆盖原数据")
                    LoadResult.Failed
                }
                else -> parsePayload(uuid, raw)
            }
        }.getOrElse {
            warning("[BuildFlight] 读取玩家 $uuid 的能量失败: $it")
            LoadResult.Failed
        }
    }

    /**
     * 解析 `能量|战斗冷却` 载荷。
     *
     * 旧版本只存能量（没有 `|`），仍然按“冷却 0”兼容；
     * 但只要字段存在却不合法，就返回 [LoadResult.Failed] 并保留原数据——
     * 绝不能当成 `Missing`，否则离线发放会用 0 覆盖掉玩家的真实余额。
     */
    private fun parsePayload(uuid: UUID, raw: String): LoadResult {
        val energy = raw.substringBefore('|').trim().toDoubleOrNull()
        if (energy == null || !energy.isFinite() || energy < 0.0) {
            warning("[BuildFlight] 玩家 $uuid 的能量记录损坏（$raw），本次不覆盖原数据")
            return LoadResult.Failed
        }
        val combatUntil = if (raw.contains('|')) {
            raw.substringAfter('|').trim().toLongOrNull()
        } else {
            0L
        }
        if (combatUntil == null || combatUntil < 0L) {
            warning("[BuildFlight] 玩家 $uuid 的战斗冷却记录损坏（$raw），本次不覆盖原数据")
            return LoadResult.Failed
        }
        return LoadResult.Loaded(energy, combatUntil)
    }

    fun save(uuid: UUID, payload: String): Boolean {
        val db = database
        if (!ready || db == null) return false
        return runCatching {
            db.set(uuid.toString(), KEY, payload)
            true
        }.getOrElse {
            warning("[BuildFlight] 保存玩家 $uuid 的能量失败: $it")
            false
        }
    }

    /** 提交 IO 任务；返回 false 表示执行器不可用（未初始化或已关闭），调用方必须给出失败回执。 */
    fun submit(task: () -> Unit): Boolean {
        val ex = executor ?: return false
        return runCatching {
            ex.execute {
                runCatching(task).onFailure {
                    warning("[BuildFlight] IO 任务执行失败: $it")
                }
            }
            true
        }.getOrElse {
            warning("[BuildFlight] 提交 IO 任务失败: $it")
            false
        }
    }

    fun scheduleAtFixedRate(
        initialDelaySeconds: Long,
        periodSeconds: Long,
        task: () -> Unit
    ): ScheduledFuture<*>? {
        val ex = executor ?: return null
        return ex.scheduleAtFixedRate({
            runCatching(task).onFailure {
                warning("[BuildFlight] 自动保存任务执行失败: $it")
            }
        }, initialDelaySeconds, periodSeconds, TimeUnit.SECONDS)
    }

    /**
     * 等待此前提交的所有 IO 任务完成（单线程执行器 + 哨兵任务）。
     * 用于插件卸载前把缓存中的能量全部落库。
     */
    fun flush(timeoutSeconds: Long) {
        val ex = executor ?: return
        val latch = CountDownLatch(1)
        runCatching { ex.execute { latch.countDown() } }
        runCatching { latch.await(timeoutSeconds.coerceAtLeast(1L), TimeUnit.SECONDS) }
    }

    fun shutdown(timeoutSeconds: Long) {
        val ex = executor ?: return
        executor = null
        ready = false
        ex.shutdown()
        if (!ex.awaitTermination(timeoutSeconds.coerceAtLeast(1L), TimeUnit.SECONDS)) {
            warning("[BuildFlight] 仍有 IO 任务未在超时内结束，已强制关闭执行器")
            ex.shutdownNow()
        }
        val db = database
        database = null
        // 只关闭本插件自己创建的 DataSource，避免影响其他插件
        runCatching { (db?.dataSource as? AutoCloseable)?.close() }
    }
}
