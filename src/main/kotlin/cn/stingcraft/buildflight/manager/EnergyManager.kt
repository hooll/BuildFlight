package cn.stingcraft.buildflight.manager

import cn.stingcraft.buildflight.config.PluginConfig
import cn.stingcraft.buildflight.config.PluginMessages
import cn.stingcraft.buildflight.data.PlayerProfile
import cn.stingcraft.buildflight.database.EnergyStorage
import cn.stingcraft.buildflight.database.LoadResult
import cn.stingcraft.buildflight.util.Scheduler
import org.bukkit.entity.Player
import taboolib.common.platform.function.warning
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture

/**
 * 玩家能量缓存与持久化协调。
 *
 * 读库在单线程 IO 上执行；所有 Bukkit 相关动作再投递回玩家区域线程。
 */
object EnergyManager {

    private val profiles = ConcurrentHashMap<UUID, PlayerProfile>()

    @Volatile
    private var autosave: ScheduledFuture<*>? = null

    fun start() {
        val interval = PluginConfig.autoSaveSeconds.toLong()
        autosave?.cancel(false)
        autosave = EnergyStorage.scheduleAtFixedRate(interval, interval) { saveDirty() }
    }

    fun profile(uuid: UUID): PlayerProfile? = profiles[uuid]

    fun profilesSnapshot(): List<PlayerProfile> = profiles.values.toList()

    fun loadPlayer(player: Player) {
        val uuid = player.uniqueId
        val profile = profiles.computeIfAbsent(uuid) { PlayerProfile(it) }
        // 极端情况下旧的退出流程尚未清理完，先取消旧任务
        profile.task?.cancel()
        profile.task = null
        profile.guardTask?.cancel()
        profile.guardTask = null
        profile.guardTimeoutTask?.cancel()
        profile.guardTimeoutTask = null
        profile.loaded = false
        profile.loadFailed = false
        profile.flying = false
        profile.combatUntil = 0L
        profile.fallGuard = false
        profile.fallGuardExpireAt = 0L
        profile.groundChecks = 0
        profile.exchanging = false

        if (!EnergyStorage.isReady()) {
            profile.loaded = true
            profile.loadFailed = true
            Scheduler.runOnEntity(player) {
                if (player.isOnline) PluginMessages.send(player, "data-failed")
            }
            return
        }

        EnergyStorage.submit {
            val result = EnergyStorage.load(uuid)
            when (result) {
                is LoadResult.Loaded -> {
                    profile.energy = result.energy
                    // 冷却时间戳跨重连保留，防止打架后掉线重进绕过冷却。
                    // 读库期间玩家可能已经参战，这里取较晚的时间，避免被旧数据覆盖。
                    profile.combatUntil = maxOf(profile.combatUntil, result.combatUntil)
                    // lastSavedPayload 只代表数据库里真实存在的状态；
                    // 若读库期间又打了一架，下一次保存会把更晚的冷却写进去。
                    profile.lastSavedPayload = EnergyStorage.pack(result.energy, result.combatUntil)
                    profile.loaded = true
                }

                LoadResult.Missing -> {
                    profile.energy = 0.0
                    // 保留读库期间可能写入的冷却时间
                    profile.lastSavedPayload = ""
                    profile.loaded = true
                }

                LoadResult.Failed -> {
                    profile.energy = 0.0
                    profile.loadFailed = true
                    profile.loaded = true
                    warning("[BuildFlight] 玩家 $uuid 的能量读取失败，已禁止本次会话的飞行与兑换")
                }
            }

            if (profile.loadFailed) {
                Scheduler.runOnEntity(player) {
                    if (player.isOnline) PluginMessages.send(player, "data-failed")
                }
            }
        }
    }

    fun unload(player: Player) {
        val profile = profiles.remove(player.uniqueId) ?: return
        profile.task?.cancel()
        profile.task = null
        profile.guardTask?.cancel()
        profile.guardTask = null
        profile.guardTimeoutTask?.cancel()
        profile.guardTimeoutTask = null
        profile.fallGuardExpireAt = 0L
        profile.bossBar = null
    }

    /** 立即排队保存当前能量；调用方保证运行在玩家区域线程。 */
    fun saveAsync(profile: PlayerProfile) {
        if (!profile.loaded || profile.loadFailed) return
        val payload = EnergyStorage.pack(profile.energy, profile.combatUntil)
        if (payload == profile.lastSavedPayload) return
        EnergyStorage.submit {
            if (EnergyStorage.save(profile.uuid, payload)) {
                profile.lastSavedPayload = payload
            }
        }
    }

    private fun saveDirty() {
        for (profile in profiles.values) {
            if (!profile.loaded || profile.loadFailed) continue
            val payload = EnergyStorage.pack(profile.energy, profile.combatUntil)
            if (payload == profile.lastSavedPayload) continue
            EnergyStorage.submit {
                if (EnergyStorage.save(profile.uuid, payload)) {
                    profile.lastSavedPayload = payload
                }
            }
        }
    }

    /**
     * 插件卸载：把缓存里的能量全部落库后再关闭 IO 执行器。
     * 必须先于 EnergyStorage.shutdown 调用。
     */
    fun shutdown() {
        autosave?.cancel(false)
        autosave = null
        for (profile in profiles.values) {
            if (!profile.loaded || profile.loadFailed) continue
            val payload = EnergyStorage.pack(profile.energy, profile.combatUntil)
            EnergyStorage.submit {
                EnergyStorage.save(profile.uuid, payload)
            }
        }
        EnergyStorage.flush(5)
        profiles.clear()
    }
}
