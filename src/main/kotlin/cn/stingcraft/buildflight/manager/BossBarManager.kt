package cn.stingcraft.buildflight.manager

import cn.stingcraft.buildflight.config.PluginConfig
import cn.stingcraft.buildflight.config.PluginMessages
import cn.stingcraft.buildflight.data.PlayerProfile
import cn.stingcraft.buildflight.util.Scheduler
import cn.stingcraft.buildflight.util.formatEnergy
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import taboolib.common.platform.function.warning

/**
 * BossBar 生命周期管理。
 *
 * 每个玩家拥有独立 BossBar；所有 show/update/hide 调用都必须发生在玩家区域线程。
 * 插件重载或卸载时绝不残留幽灵血条。
 */
object BossBarManager {

    fun show(player: Player, profile: PlayerProfile) {
        if (!player.isOnline) return
        hide(player, profile)
        runCatching {
            val bar = Bukkit.createBossBar(
                PluginMessages.text("bossbar-title", "energy" to formatEnergy(profile.energy)),
                PluginConfig.bossBarColor,
                PluginConfig.bossBarStyle
            )
            bar.setProgress(progress(profile.energy))
            bar.addPlayer(player)
            profile.bossBar = bar
        }.onFailure {
            profile.bossBar = null
            warning("[BuildFlight] 创建 BossBar 失败 (${player.name}): ${it.message}")
        }
    }

    fun update(player: Player, profile: PlayerProfile) {
        val bar = profile.bossBar ?: return
        if (!player.isOnline) {
            hide(player, profile)
            return
        }
        runCatching {
            bar.setTitle(PluginMessages.text("bossbar-title", "energy" to formatEnergy(profile.energy)))
            bar.setProgress(progress(profile.energy))
            // 配置热重载后同步外观；仅在变化时发送，避免每秒重复刷新
            val color = PluginConfig.bossBarColor
            if (bar.color != color) bar.setColor(color)
            val style = PluginConfig.bossBarStyle
            if (bar.style != style) bar.setStyle(style)
        }.onFailure {
            hide(player, profile)
        }
    }

    fun hide(player: Player, profile: PlayerProfile) {
        val bar = profile.bossBar ?: return
        profile.bossBar = null
        runCatching { bar.removePlayer(player) }
        runCatching { bar.removeAll() }
    }

    /**
     * 插件重载时重建 BossBar：先注销旧的，再按新配置显示。
     * 通过实体调度器执行，Folia 上不会跨区域操作。
     */
    fun reload() {
        Bukkit.getOnlinePlayers().toList().forEach { player ->
            if (!player.isOnline) return@forEach
            Scheduler.runOnEntity(player) {
                val profile = EnergyManager.profile(player.uniqueId) ?: return@runOnEntity
                if (!profile.flying) {
                    hide(player, profile)
                    return@runOnEntity
                }
                hide(player, profile)
                show(player, profile)
            }
        }
    }

    /**
     * 插件卸载时的兜底清理。
     *
     * 非 Folia 可安全直接调用；Folia 在 disable 阶段已无法向各区域线程投递新任务，
     * 这里直接 removeAll 是最后一道保障，服务端关闭本身也会清理玩家视口。
     */
    fun hideAllNow() {
        for (profile in EnergyManager.profilesSnapshot()) {
            val bar = profile.bossBar ?: continue
            profile.bossBar = null
            runCatching { bar.removeAll() }
        }
    }

    private fun progress(energy: Double): Double =
        (energy / PluginConfig.maxEnergyReference).coerceIn(0.0, 1.0)
}
