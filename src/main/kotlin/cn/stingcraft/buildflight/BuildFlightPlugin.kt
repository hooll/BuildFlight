package cn.stingcraft.buildflight

import cn.stingcraft.buildflight.command.MainCommand
import cn.stingcraft.buildflight.config.PluginConfig
import cn.stingcraft.buildflight.config.PluginMessages
import cn.stingcraft.buildflight.database.EnergyStorage
import cn.stingcraft.buildflight.listener.CombatListener
import cn.stingcraft.buildflight.listener.PlayerListener
import cn.stingcraft.buildflight.manager.BossBarManager
import cn.stingcraft.buildflight.manager.EnergyManager
import cn.stingcraft.buildflight.manager.FlightManager
import org.bukkit.Bukkit
import taboolib.common.platform.Plugin
import taboolib.common.platform.function.info
import taboolib.common.platform.function.warning

/**
 * 插件入口。
 *
 * 只负责启动/停止各模块，业务逻辑分别在 manager / listener / ui / database 包中。
 */
object BuildFlightPlugin : Plugin() {

    override fun onEnable() {
        PluginConfig.load()
        PluginMessages.load()

        if (!EnergyStorage.init()) {
            warning("[BuildFlight] SQLite 初始化失败：本次启动所有飞行与兑换功能将保持禁用")
        }
        EnergyManager.start()

        // 兼容插件热重载：已经在线但不会触发 PlayerJoinEvent 的玩家也要加载能量
        Bukkit.getOnlinePlayers().forEach { EnergyManager.loadPlayer(it) }

        // 强制加载带 @SubscribeEvent 的对象，确保事件注册完成
        PlayerListener.ensureLoaded()
        CombatListener.ensureLoaded()

        // 预热命令类，避免首次执行时才注册
        MainCommand.ensureLoaded()

        info("BuildFlight enabled")
    }

    override fun onDisable() {
        runCatching { FlightManager.shutdownAll() }
            .onFailure { warning("[BuildFlight] 关闭在线玩家飞行失败: $it") }
        runCatching { BossBarManager.hideAllNow() }
            .onFailure { warning("[BuildFlight] 清理 BossBar 失败: $it") }
        runCatching { EnergyManager.shutdown() }
            .onFailure { warning("[BuildFlight] 保存能量缓存失败: $it") }
        runCatching { EnergyStorage.shutdown(5) }
            .onFailure { warning("[BuildFlight] 关闭数据库失败: $it") }
        info("BuildFlight disabled")
    }

    /** /bflight reload：配置、消息与 BossBar 外观一起重载。 */
    fun reload() {
        try {
            PluginConfig.reload()
            PluginMessages.reload()
            // 自动保存间隔可能变化，重建任务
            EnergyManager.start()
        } finally {
            // 即使配置/消息加载抛异常，也先把旧的 BossBar 注销再按当前配置重建
            BossBarManager.reload()
        }
    }
}
