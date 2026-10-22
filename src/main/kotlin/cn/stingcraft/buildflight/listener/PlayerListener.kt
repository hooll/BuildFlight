package cn.stingcraft.buildflight.listener

import cn.stingcraft.buildflight.hook.DominionHook
import cn.stingcraft.buildflight.hook.DominionResult
import cn.stingcraft.buildflight.manager.EnergyManager
import cn.stingcraft.buildflight.manager.FlightManager
import cn.stingcraft.buildflight.ui.ExchangeMenu
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerToggleFlightEvent
import taboolib.common.platform.event.EventPriority
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
     * 只按方块坐标变化触发领地检查，避免每一帧移动都查 Dominion。
     *
     * 传送到新位置由 [onTeleport] 处理（传送事件同时是移动事件，这里直接跳过）。
     * 不取消移动事件，避免玩家被橡皮筋拉回。
     */
    @SubscribeEvent(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (event is PlayerTeleportEvent) return
        val player = event.player
        val profile = EnergyManager.profile(player.uniqueId) ?: return
        if (!profile.flying) return
        val from = event.from
        val to = event.to ?: return
        if (from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ) return

        val result = DominionHook.check(player, to)
        if (profile.dominionSuspended) {
            if (result == DominionResult.ALLOWED) FlightManager.resumeDominion(player, profile)
            return
        }
        if (result != DominionResult.ALLOWED) FlightManager.handleDominionOutside(player, result)
    }

    /** 传送后立即按目标位置重新判定，防止用传送绕过领地限制。 */
    @SubscribeEvent(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        val player = event.player
        val profile = EnergyManager.profile(player.uniqueId) ?: return
        if (!profile.flying) return
        val to = event.to ?: return
        val result = DominionHook.check(player, to)
        if (profile.dominionSuspended) {
            if (result == DominionResult.ALLOWED) FlightManager.resumeDominion(player, profile)
            return
        }
        if (result != DominionResult.ALLOWED) FlightManager.handleDominionOutside(player, result)
    }

    /**
     * 允许玩家用双击空格收起原版飞行，恢复原版生存建筑的操作直觉。
     *
     * 收起后建筑飞行模式仍然保留（profile.flying = true，BossBar 不消失），
     * 但只有空中收起飞行时才补一次缓降/摔落保护；落地行走不扣费，
     * 再次双击空格即可继续飞行。彻底退出模式请走 GUI 的飞行开关。
     *
     * 恢复飞行（isFlying=true）时也要过领地判定：暂停期间或领地外不能靠双击空格起飞。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onToggleFlight(event: PlayerToggleFlightEvent) {
        val player = event.player
        if (!event.isFlying) {
            FlightManager.handleVanillaFlightDisabled(player)
            return
        }
        val profile = EnergyManager.profile(player.uniqueId) ?: return
        if (!profile.flying) return
        val result = DominionHook.check(player)
        if (result != DominionResult.ALLOWED) {
            event.isCancelled = true
            player.isFlying = false
            FlightManager.notifyDominionDenied(player, result)
        }
    }
}
