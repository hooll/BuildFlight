package cn.stingcraft.buildflight.hook

import cn.stingcraft.buildflight.config.PluginConfig
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import taboolib.common.platform.function.info
import taboolib.common.platform.function.warning

/**
 * Dominion 领地判定结果。
 *
 * [ALLOWED] 之外的取值都会阻止起飞；[UNAVAILABLE] 表示 Dominion 缺失或调用失败，
 * 是否禁止飞行由 `dominion.deny-when-missing` 决定。
 */
enum class DominionResult {
    ALLOWED,
    NOT_IN_CLAIM,
    NOT_MEMBER,
    NO_PRIVILEGE,
    FLAG_DISABLED,
    UNAVAILABLE
}

/**
 * 领地判定后端。
 *
 * 实现类 [DominionBackendImpl] 是唯一直接引用 Dominion 类型（lunadeer 的 dominion 包）的文件，
 * 由 [DominionHook] 在 Dominion 存在时才通过反射实例化，避免未安装时类加载失败。
 */
internal interface DominionBackend {
    fun check(player: Player, location: Location): DominionResult
}

/**
 * Dominion 安全门面。
 *
 * 本对象不 import 任何 Dominion 类型：Dominion 未安装、未启用或 API 初始化失败时，
 * 插件仍能正常加载，并按 `dominion.deny-when-missing` 决定放行还是拒绝。
 *
 * 线程约定：所有 [check] 调用点都在玩家实体/区域线程；Dominion 的 `getDominion`
 * 是内存缓存查询，不触发数据库 IO。
 */
object DominionHook {

    private const val BACKEND_CLASS = "cn.stingcraft.buildflight.hook.DominionBackendImpl"
    private const val BYPASS_PERMISSION = "buildflight.dominion.bypass"

    @Volatile
    private var backend: DominionBackend? = null

    @Volatile
    private var warnedMissing = false

    @Volatile
    private var warnedFailure = false

    /** 当前是否有可用的 Dominion 后端。 */
    val available: Boolean
        get() = backend != null

    /**
     * 初始化/重新初始化 hook。
     *
     * 每次调用都会重置一次性警告标记，保证 `/bflight reload` 后能重新记录问题。
     */
    fun init() {
        backend = null
        warnedMissing = false
        warnedFailure = false

        if (!PluginConfig.dominionEnabled) {
            info("[BuildFlight] Dominion 集成已关闭 (dominion.enabled=false)")
            return
        }

        val plugin = runCatching { Bukkit.getPluginManager().getPlugin("Dominion") }.getOrNull()
        if (plugin == null || !plugin.isEnabled) {
            warnMissingOnce()
            return
        }

        backend = runCatching {
            val type = Class.forName(BACKEND_CLASS)
            type.getDeclaredConstructor().newInstance() as DominionBackend
        }.getOrElse {
            warnFailureOnce("实例化 Dominion 后端失败: ${it.javaClass.simpleName}: ${it.message}")
            null
        }

        if (backend != null) {
            info("[BuildFlight] Dominion hook 已启用，mode=${PluginConfig.dominionMode}")
        }
    }

    /** 只清空引用，不触碰 Dominion 自身的资源。 */
    fun shutdown() {
        backend = null
    }

    /**
     * 判定玩家在指定位置是否允许使用建筑飞行。
     *
     * OP 与 `buildflight.dominion.bypass` 始终放行，且绕过 Dominion 缺失检查。
     */
    fun check(player: Player, location: Location = player.location): DominionResult {
        if (!PluginConfig.dominionEnabled) return DominionResult.ALLOWED
        if (player.isOp || player.hasPermission(BYPASS_PERMISSION)) return DominionResult.ALLOWED

        val current = backend
        if (current == null) {
            return if (PluginConfig.dominionDenyWhenMissing) DominionResult.UNAVAILABLE else DominionResult.ALLOWED
        }

        return runCatching { current.check(player, location) }.getOrElse {
            warnFailureOnce("Dominion 判定失败: ${it.javaClass.simpleName}: ${it.message}")
            if (PluginConfig.dominionDenyWhenMissing) DominionResult.UNAVAILABLE else DominionResult.ALLOWED
        }
    }

    private fun warnMissingOnce() {
        if (warnedMissing) return
        warnedMissing = true
        warning(
            "[BuildFlight] 未检测到可用的 Dominion，建筑飞行将按 " +
                "deny-when-missing=${PluginConfig.dominionDenyWhenMissing} 处理"
        )
    }

    private fun warnFailureOnce(message: String) {
        if (warnedFailure) return
        warnedFailure = true
        warning("[BuildFlight] $message")
    }
}
