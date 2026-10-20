package cn.stingcraft.buildflight.listener

import cn.stingcraft.buildflight.manager.EnergyManager
import cn.stingcraft.buildflight.manager.FlightManager
import cn.stingcraft.buildflight.ui.ExchangeMenu
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerToggleFlightEvent
import taboolib.common.platform.event.SubscribeEvent

/** 玩家生命周期：加载能量、退出保存、跨世界/死亡/模式切换时关闭飞行。 */
object PlayerListener {

    fun ensureLoaded() {
        // 触发对象初始化，完成 @SubscribeEvent 注册
    }

    @SubscribeEvent
    fun onJoin(event: PlayerJoinEvent) {
        EnergyManager.loadPlayer(event.player)
    }

    @SubscribeEvent
    fun onQuit(event: PlayerQuitEvent) {
        ExchangeMenu.clearSession(event.player)
        FlightManager.handleQuit(event.player)
        EnergyManager.unload(event.player)
    }

    @SubscribeEvent
    fun onChangedWorld(event: PlayerChangedWorldEvent) {
        FlightManager.handleWorldChange(event.player)
    }

    @SubscribeEvent
    fun onDeath(event: PlayerDeathEvent) {
        FlightManager.handleDeath(event.entity)
    }

    @SubscribeEvent
    fun onGameModeChange(event: PlayerGameModeChangeEvent) {
        FlightManager.handleGameMode(event.player, event.newGameMode)
    }

    /**
     * 允许玩家用双击空格收起原版飞行，恢复原版生存建筑的操作直觉。
     *
     * 收起后建筑飞行模式仍然保留（profile.flying = true，BossBar 不消失），
     * 但只有空中收起飞行时才补一次缓降/摔落保护；落地行走不扣费，
     * 再次双击空格即可继续飞行。彻底退出模式请走 GUI 的飞行开关。
     */
    @SubscribeEvent
    fun onToggleFlight(event: PlayerToggleFlightEvent) {
        if (event.isFlying) return
        FlightManager.handleVanillaFlightDisabled(event.player)
    }
}
