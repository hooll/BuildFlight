package cn.stingcraft.buildflight.listener

import cn.stingcraft.buildflight.config.PluginConfig
import cn.stingcraft.buildflight.manager.EnergyManager
import cn.stingcraft.buildflight.manager.FlightManager
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.TNTPrimed
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import taboolib.common.platform.event.EventPriority
import taboolib.common.platform.event.SubscribeEvent

/**
 * 战斗打断 + 摔落免伤。
 */
object CombatListener {

    fun ensureLoaded() {
        // 触发对象初始化，完成 @SubscribeEvent 注册
    }

    // MONITOR + ignoreCancelled：只有真正生效的伤害才触发冷却，避免安全区里的取消攻击误判
    @SubscribeEvent(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDamage(event: EntityDamageByEntityEvent) {
        if (!PluginConfig.combatCancelOnDamage) return

        val victim = event.entity as? Player
        val damager = resolvePlayer(event.damager)

        if (victim != null) {
            FlightManager.interrupt(victim)
        }
        if (damager != null && damager != victim) {
            FlightManager.interrupt(damager)
        }
    }

    /** 缓降/落地保护的兜底：保护期内取消所有摔落伤害。 */
    @SubscribeEvent
    fun onFallDamage(event: EntityDamageEvent) {
        if (event.cause != EntityDamageEvent.DamageCause.FALL) return
        val player = event.entity as? Player ?: return
        val profile = EnergyManager.profile(player.uniqueId) ?: return
        if (!profile.fallGuard) return

        // 硬性超时的最后一道保险：即使落地检测/超时任务都异常，
        // 超过保护时限后也绝不再取消摔落伤害。
        val now = System.currentTimeMillis()
        if (profile.fallGuardExpireAt > 0L && now >= profile.fallGuardExpireAt) {
            FlightManager.clearFallProtection(player, profile)
            return
        }
        event.isCancelled = true
    }

    private fun resolvePlayer(entity: Entity): Player? = when (entity) {
        is Player -> entity
        is Projectile -> entity.shooter as? Player
        is TNTPrimed -> entity.source as? Player
        else -> null
    }
}
