package cn.stingcraft.buildflight.manager

import cn.stingcraft.buildflight.config.PluginConfig
import cn.stingcraft.buildflight.config.PluginMessages
import cn.stingcraft.buildflight.data.PlayerProfile
import cn.stingcraft.buildflight.util.Scheduler
import cn.stingcraft.buildflight.util.formatEnergy
import cn.stingcraft.buildflight.util.remainingSeconds
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import taboolib.common.platform.function.warning

/**
 * 飞行状态机。
 *
 * 规则：
 *  - 开启后每秒检查一次；仅当 isFlying 且双脚离地时扣除能量。
 *  - 能量归零强制关闭飞行，并给予缓降 + 落地前摔落免伤。
 *  - 受到实体攻击或主动攻击实体时立即中断，并进入战斗冷却。
 *  - 所有方法都必须在玩家区域线程执行（通过 [Scheduler] 投递）。
 */
object FlightManager {

    /** GUI / 指令开启或关闭飞行；callback 在同一实体任务末尾执行。 */
    fun toggle(player: Player, callback: (() -> Unit)? = null) {
        Scheduler.runOnEntity(player) {
            toggleNow(player)
            callback?.invoke()
        }
    }

    private fun toggleNow(player: Player) {
        if (!player.isOnline) return
        val profile = EnergyManager.profile(player.uniqueId)
        if (profile == null || !profile.loaded) {
            PluginMessages.send(player, "data-loading")
            return
        }
        if (profile.loadFailed) {
            PluginMessages.send(player, "data-failed")
            return
        }
        if (!PluginConfig.isWorldAllowed(player.world.name)) {
            PluginMessages.send(player, "world-disabled", "world" to player.world.name)
            return
        }
        if (profile.flying) {
            stop(player, profile, safeLanding = true, notify = true, resetAllowFlight = true)
            EnergyManager.saveAsync(profile)
            return
        }

        if (player.gameMode == GameMode.CREATIVE || player.gameMode == GameMode.SPECTATOR) {
            PluginMessages.send(player, "creative-no-need")
            return
        }

        val cooldown = remainingSeconds(profile.combatUntil)
        if (cooldown > 0L) {
            PluginMessages.send(player, "combat-cooldown", "seconds" to cooldown)
            return
        }

        if (profile.energy <= 0.0) {
            PluginMessages.send(player, "no-energy")
            return
        }

        grant(player, profile)
    }

    private fun grant(player: Player, profile: PlayerProfile) {
        profile.flying = true
        player.allowFlight = true
        player.isFlying = true

        if (!startFlightTask(player, profile)) {
            profile.flying = false
            player.isFlying = false
            player.allowFlight = false
            PluginMessages.send(player, "internal-error")
            return
        }

        BossBarManager.show(player, profile)
        PluginMessages.send(
            player,
            "flight-on",
            "energy" to formatEnergy(profile.energy)
        )
        EnergyManager.saveAsync(profile)
    }

    private fun startFlightTask(player: Player, profile: PlayerProfile): Boolean {
        profile.task?.cancel()
        profile.task = Scheduler.runAtFixedRate(
            player,
            1L,
            20L
        ) { tick(player, profile) } ?: return false
        return true
    }

    @Suppress("DEPRECATION")
    private fun tick(player: Player, profile: PlayerProfile) {
        if (!player.isOnline) {
            // 玩家已离线时不再触碰实体状态，只做本地清理
            profile.task?.cancel()
            profile.task = null
            profile.flying = false
            BossBarManager.hide(player, profile)
            return
        }
        if (!profile.flying) {
            profile.task?.cancel()
            profile.task = null
            BossBarManager.hide(player, profile)
            return
        }
        if (!profile.loaded || profile.loadFailed) {
            stop(player, profile, safeLanding = false, notify = false, resetAllowFlight = true)
            return
        }
        if (!PluginConfig.isWorldAllowed(player.world.name)) {
            stop(player, profile, safeLanding = false, notify = false, resetAllowFlight = true)
            PluginMessages.send(player, "world-disabled", "world" to player.world.name)
            EnergyManager.saveAsync(profile)
            return
        }
        if (player.gameMode == GameMode.CREATIVE || player.gameMode == GameMode.SPECTATOR) {
            stop(player, profile, safeLanding = false, notify = false, resetAllowFlight = false)
            EnergyManager.saveAsync(profile)
            return
        }
        // 飞行权限被其他插件/服务端收回时，停止扣费并清理状态，避免幽灵 BossBar。
        if (!player.allowFlight) {
            profile.flying = false
            profile.task?.cancel()
            profile.task = null
            BossBarManager.hide(player, profile)
            return
        }

        if (profile.energy <= 0.0) {
            exhaust(player, profile)
            return
        }

        // 仅悬浮/飞行且双脚离地时扣费；落在方块上暂停。
        if (!player.isOnGround && player.isFlying) {
            val cost = PluginConfig.consumePerSecond
            if (cost > 0.0) {
                val next = profile.energy - cost
                profile.energy = if (next > 0.0) next else 0.0
                if (profile.energy <= 0.0) {
                    exhaust(player, profile)
                    return
                }
            }
        }

        BossBarManager.update(player, profile)
    }

    private fun exhaust(player: Player, profile: PlayerProfile) {
        profile.energy = 0.0
        stop(player, profile, safeLanding = false, notify = false, resetAllowFlight = true)
        PluginMessages.send(player, "flight-exhausted")
        // 能量耗尽必须无条件给缓降保护，即使玩家当前恰好贴着地面。
        applyFallProtection(player, profile)
        EnergyManager.saveAsync(profile)
    }

    /**
     * 关闭飞行。
     *
     * @param safeLanding 是否在离地时给予缓降/摔落免伤（GUI 主动关闭、插件卸载时为 true；
     *                    战斗打断、跨世界、退出服务器时为 false）
     * @param resetAllowFlight 创造/旁观模式切换时不能强行收回 allowFlight
     */
    private fun stop(
        player: Player,
        profile: PlayerProfile,
        safeLanding: Boolean,
        notify: Boolean,
        resetAllowFlight: Boolean
    ) {
        // 只有“我们确实开启过飞行”时才允许收回 allowFlight，
        // 避免在玩家未使用本插件飞行时误伤其他插件授予的飞行权限。
        val wasFlying = profile.flying
        profile.task?.cancel()
        profile.task = null
        profile.flying = false
        BossBarManager.hide(player, profile)

        if (resetAllowFlight &&
            wasFlying &&
            player.gameMode != GameMode.CREATIVE &&
            player.gameMode != GameMode.SPECTATOR
        ) {
            player.isFlying = false
            player.allowFlight = false
        }

        if (notify) {
            PluginMessages.send(player, "flight-off")
        }
        if (safeLanding && wasFlying && isAirborne(player)) {
            applyFallProtection(player, profile)
        }
    }

    /**
     * 给玩家缓降与落地前摔落免伤；重复调用会刷新时长与硬性超时。
     *
     * 除了周期落地检测外，还会启动一个独立的延迟任务作为最大超时兜底：
     * 即使玩家挂在梯子、蜘蛛网、矿车等特殊状态导致 isLanded 永远为 false，
     * 到点也会强制清除保护标记与两个任务句柄。
     */
    private fun applyFallProtection(player: Player, profile: PlayerProfile) {
        val seconds = PluginConfig.fallProtectionSeconds
        if (seconds > 0) {
            val duration = seconds * 20
            val existing = player.getPotionEffect(PotionEffectType.SLOW_FALLING)
            if (existing == null || existing.duration < duration) {
                player.addPotionEffect(PotionEffect(PotionEffectType.SLOW_FALLING, duration, 0))
            }
        }

        val timeoutMillis = (seconds + 5).toLong() * 1000L
        profile.fallGuard = true
        profile.fallGuardExpireAt = System.currentTimeMillis() + timeoutMillis
        profile.groundChecks = 0
        startGuardTask(player, profile)
        startGuardTimeout(player, profile, timeoutMillis)

        PluginMessages.send(player, "fall-protection", "seconds" to seconds)
    }

    private fun startGuardTask(player: Player, profile: PlayerProfile) {
        profile.guardTask?.cancel()
        profile.guardTask = Scheduler.runAtFixedRate(
            player,
            10L,
            10L
        ) { tickGuard(player, profile) }
        if (profile.guardTask == null) {
            warning("[BuildFlight] 无法启动落地检测任务 (${player.name})，将由硬性超时与摔落事件兜底")
        }
    }

    private fun startGuardTimeout(player: Player, profile: PlayerProfile, timeoutMillis: Long) {
        profile.guardTimeoutTask?.cancel()
        val timeoutTicks = (timeoutMillis / 50L).coerceAtLeast(1L)
        // 记录本次保护的截止时间：即使旧超时任务因调度时序多跑一次，
        // 也不能清掉之后刷新出来的新保护。
        val expectedExpireAt = profile.fallGuardExpireAt
        profile.guardTimeoutTask = Scheduler.runDelayedOnEntity(player, timeoutTicks) {
            if (profile.fallGuard && profile.fallGuardExpireAt == expectedExpireAt) {
                clearFallProtection(player, profile)
            }
        }
        if (profile.guardTimeoutTask == null) {
            warning("[BuildFlight] 无法启动摔落保护超时任务 (${player.name})，将由落地检测与摔落事件兜底")
        }
    }

    private fun tickGuard(player: Player, profile: PlayerProfile) {
        if (!player.isOnline || !profile.fallGuard) {
            // 保护已被其他路径清除，或玩家已离线：只做本地句柄清理
            profile.guardTask?.cancel()
            profile.guardTask = null
            profile.guardTimeoutTask?.cancel()
            profile.guardTimeoutTask = null
            profile.fallGuardExpireAt = 0L
            profile.groundChecks = 0
            return
        }

        // 硬性超时优先：无论是否检测到落地，到点必须解除免伤。
        val now = System.currentTimeMillis()
        if (profile.fallGuardExpireAt > 0L && now >= profile.fallGuardExpireAt) {
            clearFallProtection(player, profile)
            return
        }

        if (isLanded(player)) {
            profile.groundChecks++
            // 延迟约 1 秒清除，保证落地当 tick 的摔落伤害事件仍能看到保护标记。
            if (profile.groundChecks >= 2) {
                clearFallProtection(player, profile)
            }
        } else {
            profile.groundChecks = 0
        }
    }

    /**
     * 玩家用双击空格收起原版飞行时调用（PlayerToggleFlightEvent）。
     *
     * 这里刻意不关闭建筑飞行模式：profile.flying 保持 true，BossBar 保留，
     * 玩家可以平稳落地行走（不着地不扣费），再次双击空格即可继续飞行。
     * 彻底退出模式仍需在 GUI 中点击“关闭飞行”。
     */
    @Suppress("DEPRECATION")
    fun handleVanillaFlightDisabled(player: Player) {
        val profile = EnergyManager.profile(player.uniqueId) ?: return
        if (!profile.flying) return
        if (player.gameMode == GameMode.CREATIVE || player.gameMode == GameMode.SPECTATOR) return
        if (!player.isOnGround) {
            // 事件本身就在玩家区域线程上触发，可以直接补保护
            applyFallProtection(player, profile)
        }
    }

    /** 清除缓降与摔落免伤标记；落地、超时、退出、死亡、跨世界时调用。 */
    fun clearFallProtection(player: Player, profile: PlayerProfile) {
        profile.guardTask?.cancel()
        profile.guardTask = null
        profile.guardTimeoutTask?.cancel()
        profile.guardTimeoutTask = null
        val hadGuard = profile.fallGuard
        profile.fallGuard = false
        profile.fallGuardExpireAt = 0L
        profile.groundChecks = 0
        if (hadGuard) {
            runCatching { player.removePotionEffect(PotionEffectType.SLOW_FALLING) }
        }
    }

    /** 战斗打断：立即关闭飞行并进入冷却。 */
    fun interrupt(player: Player) {
        if (!PluginConfig.combatCancelOnDamage) return

        // 先同步标记冷却：只写 @Volatile 时间戳，不触碰任何 Bukkit 状态。
        // 这样即使玩家在同一 tick 退出，退出保存也能带上冷却，杜绝重连绕过。
        markCombat(player)

        Scheduler.runOnEntity(player) {
            if (!player.isOnline) return@runOnEntity
            val profile = EnergyManager.profile(player.uniqueId) ?: return@runOnEntity
            if (!profile.flying) return@runOnEntity

            val remaining = remainingSeconds(profile.combatUntil)
            stop(player, profile, safeLanding = false, notify = false, resetAllowFlight = true)
            clearFallProtection(player, profile)
            PluginMessages.send(player, "combat-interrupted", "seconds" to remaining)
            EnergyManager.saveAsync(profile)
        }
    }

    private fun markCombat(player: Player) {
        val profile = EnergyManager.profile(player.uniqueId) ?: return
        val cooldown = PluginConfig.combatCooldownSeconds
        val until = System.currentTimeMillis() + cooldown * 1000L
        if (until > profile.combatUntil) {
            profile.combatUntil = until
        }
    }

    fun handleQuit(player: Player) {
        val profile = EnergyManager.profile(player.uniqueId) ?: return
        stop(player, profile, safeLanding = false, notify = false, resetAllowFlight = true)
        clearFallProtection(player, profile)
        EnergyManager.saveAsync(profile)
    }

    fun handleWorldChange(player: Player) {
        val profile = EnergyManager.profile(player.uniqueId) ?: return
        stop(player, profile, safeLanding = false, notify = false, resetAllowFlight = true)
        clearFallProtection(player, profile)
        EnergyManager.saveAsync(profile)
    }

    fun handleDeath(player: Player) {
        val profile = EnergyManager.profile(player.uniqueId) ?: return
        stop(player, profile, safeLanding = false, notify = false, resetAllowFlight = true)
        clearFallProtection(player, profile)
        EnergyManager.saveAsync(profile)
    }

    fun handleGameMode(player: Player, newMode: GameMode) {
        val profile = EnergyManager.profile(player.uniqueId) ?: return
        if (!profile.flying) return
        if (newMode == GameMode.CREATIVE || newMode == GameMode.SPECTATOR) {
            stop(player, profile, safeLanding = false, notify = false, resetAllowFlight = false)
            EnergyManager.saveAsync(profile)
        }
    }

    /**
     * 插件卸载：关闭所有在线玩家的飞行并尽量给予缓降保护。
     * Folia 在 disable 阶段无法保证新任务被调度，此时至少保存能量并隐藏 BossBar。
     */
    fun shutdownAll() {
        for (profile in EnergyManager.profilesSnapshot()) {
            val player = Bukkit.getPlayer(profile.uuid) ?: continue
            if (!player.isOnline) continue
            if (Scheduler.isFolia) {
                Scheduler.runOnEntity(player) {
                    stop(player, profile, safeLanding = true, notify = false, resetAllowFlight = true)
                }
            } else {
                stop(player, profile, safeLanding = true, notify = false, resetAllowFlight = true)
            }
            EnergyManager.saveAsync(profile)
        }
    }

    @Suppress("DEPRECATION")
    private fun isLanded(player: Player): Boolean =
        player.isOnGround ||
            player.isInWater ||
            player.isClimbing ||
            player.isInsideVehicle ||
            player.isSleeping

    private fun isAirborne(player: Player): Boolean = !isLanded(player)
}
