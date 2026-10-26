package cn.stingcraft.buildflight.manager

import cn.stingcraft.buildflight.config.PluginConfig
import cn.stingcraft.buildflight.config.PluginMessages
import cn.stingcraft.buildflight.data.PendingGive
import cn.stingcraft.buildflight.data.PlayerProfile
import cn.stingcraft.buildflight.database.EnergyStorage
import cn.stingcraft.buildflight.database.LoadResult
import cn.stingcraft.buildflight.util.Scheduler
import cn.stingcraft.buildflight.util.formatEnergy
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import taboolib.common.platform.function.info
import taboolib.common.platform.function.warning
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ScheduledFuture

/**
 * 玩家能量缓存与持久化协调。
 *
 * 读库在单线程 IO 上执行；所有 Bukkit 相关动作再投递回玩家区域线程。
 *
 * 发放（/bf give）与加载/卸载之间的不变量：
 *  1. 内存里的 [PlayerProfile.energy] 只在玩家区域线程被修改；
 *  2. 档案尚未加载完成时，发放请求进入 [PlayerProfile.pendingGives]，由加载完成回调 drain；
 *  3. 档案在加载完成前被卸载时，pending 交给排在加载任务之后的 IO 任务写库，
 *     绝不在实体线程改一个随后会被加载结果覆盖的内存值；
 *  4. 从 [profiles] 移除后到达的发放走离线写库，不会挂到已死档案上。
 */
object EnergyManager {

    /** 在线发放与离线写库互相转交的最大次数，防止极端竞态下无限来回投递。 */
    private const val MAX_HANDOFF = 3

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
        profile.player = player

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

            // 加载完成回调必须投递回实体线程再动内存；档案已被卸载（或已被新档案顶替）时不再碰它。
            Scheduler.runOnEntity(player) {
                if (profiles[uuid] !== profile) return@runOnEntity
                if (profile.loadFailed) {
                    if (player.isOnline) PluginMessages.send(player, "data-failed")
                    failAllPending(profile, player.name)
                } else {
                    drainOnline(player, profile)
                }
            }
        }
    }

    fun unload(player: Player) {
        // 先摘除索引：摘除之后到达的发放会走离线写库，不会挂到已死档案上。
        // 按实例移除，避免误删同一 UUID 的新档案。
        val profile = profiles[player.uniqueId] ?: return
        val removed = profiles.remove(player.uniqueId, profile)
        profile.task?.cancel()
        profile.task = null
        profile.guardTask?.cancel()
        profile.guardTask = null
        profile.guardTimeoutTask?.cancel()
        profile.guardTimeoutTask = null
        profile.fallGuardExpireAt = 0L
        profile.bossBar = null
        profile.player = null
        // 该实例已不是当前档案（被新会话顶替）：不再动它的能量，事务交给新档案自己加载。
        if (!removed) return

        if (!profile.loaded) {
            // 加载任务尚未完成：内存稍后会被加载结果覆盖，绝不能在这里改能量。
            // 把 pending 交给排在加载任务之后的 IO 任务写库。
            val pending = takePending(profile)
            if (pending.isNotEmpty()) {
                submitPendingWrite(player.uniqueId, player.name, pending)
            }
            return
        }

        if (profile.loadFailed) {
            failAllPending(profile, player.name)
            return
        }

        // 已加载：在实体线程把 pending 应用到内存，随卸载保存一起落库。
        while (true) {
            val give = profile.pendingGives.poll() ?: break
            val next = saturatedAdd(profile.energy, give.amount)
            profile.energy = next
            replyGive(give.sender, "give-success",
                "player" to player.name,
                "points" to formatEnergy(give.amount),
                "energy" to formatEnergy(next)
            )
            logGive(give.senderName, player.name, give.amount, next, online = true)
        }
        submitFinalSave(profile)
    }

    /**
     * 卸载/关闭时的最后一次落库。
     *
     * 这里写“增量”而不是绝对值：档案摘除之后到达的离线发放会直接写库，
     * 而本次写入与它是两个线程提交的任务，先后顺序不确定。
     * 增量写法（数据库当前值 + 本会话相对上次落库的增量）对顺序不敏感，
     * 无论谁先执行都不会把对方的发放覆盖掉。
     */
    private fun submitFinalSave(profile: PlayerProfile) {
        EnergyStorage.submit {
            val memory = profile.energy
            val combat = profile.combatUntil
            val last = profile.lastSavedPayload
            if (last == EnergyStorage.pack(memory, combat)) return@submit
            val base = payloadEnergy(last)
            when (val result = EnergyStorage.load(profile.uuid)) {
                LoadResult.Failed -> warning(
                    "[BuildFlight] 玩家 ${profile.uuid} 退出时读取能量失败，本次未落库"
                )

                LoadResult.Missing -> {
                    val payload = EnergyStorage.pack(memory, combat)
                    if (EnergyStorage.save(profile.uuid, payload)) {
                        profile.lastSavedPayload = payload
                    }
                }

                is LoadResult.Loaded -> {
                    val energy = mergeEnergy(result.energy, memory - base)
                    val payload = EnergyStorage.pack(energy, maxOf(combat, result.combatUntil))
                    if (EnergyStorage.save(profile.uuid, payload)) {
                        profile.lastSavedPayload = payload
                    }
                }
            }
        }
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

    /**
     * 命令线程入口：先按在线玩家精确匹配；不在线时把**可能访问磁盘**的离线解析
     * 丢到 IO 线程（`Bukkit.getOfflinePlayers()` 会遍历 playerdata 目录），
     * 命令线程与玩家实体线程都不做文件 IO。
     */
    fun giveByTargetName(name: String, amount: Double, sender: CommandSender) {
        Bukkit.getPlayerExact(name)?.let {
            giveOnline(it, amount, sender)
            return
        }
        Bukkit.getOnlinePlayers()
            .firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?.let {
                giveOnline(it, amount, sender)
                return
            }

        if (!EnergyStorage.isReady()) {
            replyGive(sender, "give-data-failed", "player" to name)
            return
        }
        val queued = EnergyStorage.submit {
            val target = runCatching { findPlayedBefore(name) }.getOrElse {
                warning("[BuildFlight] 解析离线玩家 $name 失败: $it")
                replyGive(sender, "give-lookup-failed", "player" to name)
                return@submit
            }
            if (target == null) {
                replyGive(sender, "give-player-unknown", "player" to name)
                return@submit
            }
            submitOfflineWrite(target.uniqueId, target.name ?: name, amount, sender, 0)
        }
        if (!queued) {
            replyGive(sender, "give-data-failed", "player" to name)
        }
    }

    /** 只在 IO 线程调用：遍历服务端缓存的离线玩家，只认曾经进服过的名字。 */
    private fun findPlayedBefore(name: String): OfflinePlayer? =
        Bukkit.getOfflinePlayers().firstOrNull { candidate ->
            candidate.hasPlayedBefore() && candidate.name?.equals(name, ignoreCase = true) == true
        }

    /**
     * 给在线玩家发放能量。
     *
     * 真正的档案操作投递到目标所属实体线程；档案尚未创建时改走离线写库，
     * 由 IO 任务重新检查档案是否已经出现（见 [submitOfflineWrite]）。
     */
    fun giveOnline(player: Player, amount: Double, sender: CommandSender) {
        dispatchOnline(player, amount, sender, 0)
    }

    /** 给离线（或档案尚未就绪）的玩家发放能量，必须等数据库写入成功才回复成功。 */
    fun giveOffline(uuid: UUID, name: String, amount: Double, sender: CommandSender) {
        submitOfflineWrite(uuid, name, amount, sender, 0)
    }

    /**
     * 把发放投递到目标实体线程。
     *
     * Folia 的实体任务在玩家退休后不会再执行，因此必须用带终态的调度：
     * `action` 与 `retired` 只能有一个生效，一次性开关保证同一请求不会被
     * 回复或落库两次；退休时转离线流程，绝不静默丢弃。
     */
    private fun dispatchOnline(player: Player, amount: Double, sender: CommandSender, depth: Int) {
        if (depth > MAX_HANDOFF) {
            replyGive(sender, "give-data-failed", "player" to player.name)
            warning("[BuildFlight] 向 ${player.name} 的发放交接次数超限，已放弃")
            return
        }
        val completed = AtomicBoolean(false)
        fun finishOnce(block: () -> Unit) {
            if (completed.compareAndSet(false, true)) block()
        }
        Scheduler.runOnEntityOrElse(
            player,
            action = {
                finishOnce {
                    if (!player.isOnline) {
                        // 投递途中退出：改走离线写库（IO 线程会重新判断档案是否还在）
                        submitOfflineWrite(player.uniqueId, player.name, amount, sender, depth + 1)
                    } else {
                        applyOnline(player, amount, sender, depth)
                    }
                }
            },
            retired = {
                finishOnce {
                    submitOfflineWrite(player.uniqueId, player.name, amount, sender, depth + 1)
                }
            }
        )
    }

    /** 在玩家实体线程上把发放交给档案；档案实例与玩家会话必须仍然匹配。 */
    private fun applyOnline(player: Player, amount: Double, sender: CommandSender, depth: Int) {
        val profile = profiles[player.uniqueId]
        if (profile == null || profile.player !== player) {
            // 进服事件还没创建档案，或档案已被新会话顶替：交给离线写库重新判断
            submitOfflineWrite(player.uniqueId, player.name, amount, sender, depth + 1)
            return
        }
        profile.pendingGives.add(PendingGive(amount, sender, senderName(sender)))
        // 未加载完成时留给加载回调 drain；已加载则立刻在实体线程上应用。
        if (!profile.loaded) return
        if (profile.loadFailed) {
            failAllPending(profile, player.name)
        } else {
            drainOnline(player, profile)
        }
    }

    private fun submitOfflineWrite(
        uuid: UUID,
        name: String,
        amount: Double,
        sender: CommandSender,
        depth: Int
    ) {
        if (!EnergyStorage.isReady()) {
            replyGive(sender, "give-data-failed", "player" to name)
            warning("[BuildFlight] 能量数据库未就绪，取消向 $name 发放 ${formatEnergy(amount)} 点能量")
            return
        }
        val queued = EnergyStorage.submit {
            // 档案在 IO 排队期间被创建：转回实体线程按在线规则发放。
            val profile = profiles[uuid]
            val online = profile?.player
            if (profile != null && online != null && online.isOnline) {
                if (depth > MAX_HANDOFF) {
                    // 绝不能在这里直接写库：随后在线档案的保存会用内存值覆盖它。
                    replyGive(sender, "give-data-failed", "player" to name)
                    warning("[BuildFlight] 向 $name 的发放在线/离线之间交接次数超限，已放弃（不直接写库）")
                    return@submit
                }
                dispatchOnline(online, amount, sender, depth + 1)
                return@submit
            }
            applyOfflineWrite(uuid, name, amount, sender)
        }
        if (!queued) {
            replyGive(sender, "give-data-failed", "player" to name)
        }
    }

    /** 离线写库：读—改—写，全部在 IO 线程上完成，绝不碰在线档案的内存。 */
    private fun applyOfflineWrite(uuid: UUID, name: String, amount: Double, sender: CommandSender) {
        when (val result = EnergyStorage.load(uuid)) {
            LoadResult.Failed -> {
                replyGive(sender, "give-data-failed", "player" to name)
                warning("[BuildFlight] 读取 $name ($uuid) 的能量失败，取消发放")
            }

            is LoadResult.Loaded -> writeOffline(
                uuid, name, result.energy, result.combatUntil, amount, sender
            )

            LoadResult.Missing -> writeOffline(uuid, name, 0.0, 0L, amount, sender)
        }
    }

    private fun writeOffline(
        uuid: UUID,
        name: String,
        base: Double,
        combatUntil: Long,
        amount: Double,
        sender: CommandSender
    ) {
        val energy = saturatedAdd(base, amount)
        if (!EnergyStorage.save(uuid, EnergyStorage.pack(energy, combatUntil))) {
            replyGive(sender, "give-save-failed",
                "player" to name,
                "points" to formatEnergy(amount)
            )
            warning("[BuildFlight] 向 $name ($uuid) 写入能量失败，未发放")
            return
        }
        replyGive(sender, "give-success",
            "player" to name,
            "points" to formatEnergy(amount),
            "energy" to formatEnergy(energy)
        )
        logGive(senderName(sender), name, amount, energy, online = false)
    }

    /** 把一份加载完成前的 pending 批次写进数据库（档案已被卸载时使用）。 */
    private fun submitPendingWrite(uuid: UUID, name: String, pending: List<PendingGive>) {
        if (!EnergyStorage.isReady()) {
            pending.forEach { replyGive(it.sender, "give-data-failed", "player" to name) }
            warning("[BuildFlight] 能量数据库未就绪，取消向 $name 发放 ${pending.size} 笔能量")
            return
        }
        EnergyStorage.submit {
            val total = pending.fold(0.0) { acc, give -> saturatedAdd(acc, give.amount) }
            val base: Double
            val combatUntil: Long
            when (val result = EnergyStorage.load(uuid)) {
                LoadResult.Failed -> {
                    pending.forEach { replyGive(it.sender, "give-data-failed", "player" to name) }
                    warning("[BuildFlight] 读取 $name ($uuid) 的能量失败，取消 ${pending.size} 笔发放")
                    return@submit
                }

                is LoadResult.Loaded -> {
                    base = result.energy
                    combatUntil = result.combatUntil
                }

                LoadResult.Missing -> {
                    base = 0.0
                    combatUntil = 0L
                }
            }

            val energy = saturatedAdd(base, total)
            if (!EnergyStorage.save(uuid, EnergyStorage.pack(energy, combatUntil))) {
                pending.forEach {
                    replyGive(it.sender, "give-save-failed",
                        "player" to name,
                        "points" to formatEnergy(it.amount)
                    )
                }
                warning("[BuildFlight] 向 $name ($uuid) 写入能量失败，${pending.size} 笔发放未生效")
                return@submit
            }
            pending.forEach {
                replyGive(it.sender, "give-success",
                    "player" to name,
                    "points" to formatEnergy(it.amount),
                    "energy" to formatEnergy(energy)
                )
                logGive(it.senderName, name, it.amount, energy, online = false)
            }
        }
    }

    /**
     * 在玩家实体线程上把 pending 队列叠加到内存能量。
     *
     * 循环到队列为空；每次 drain 结束时若期间又有新入队，下一次入队方会再次触发 drain，
     * 因此不会出现永久滞留的发放。
     */
    private fun drainOnline(player: Player, profile: PlayerProfile) {
        if (profile.loadFailed) {
            failAllPending(profile, player.name)
            return
        }
        var changed = false
        while (true) {
            val give = profile.pendingGives.poll() ?: break
            val next = saturatedAdd(profile.energy, give.amount)
            profile.energy = next
            changed = true
            replyGive(give.sender, "give-success",
                "player" to player.name,
                "points" to formatEnergy(give.amount),
                "energy" to formatEnergy(next)
            )
            if (give.sender !== player && player.isOnline) {
                PluginMessages.send(player, "give-received",
                    "points" to formatEnergy(give.amount),
                    "energy" to formatEnergy(next)
                )
            }
            logGive(give.senderName, player.name, give.amount, next, online = true)
        }
        if (!changed) return
        saveAsync(profile)
        if (profile.flying && player.isOnline) {
            if (profile.bossBar == null) {
                BossBarManager.show(player, profile)
            } else {
                BossBarManager.update(player, profile)
            }
        }
    }

    private fun failAllPending(profile: PlayerProfile, name: String) {
        val pending = takePending(profile)
        pending.forEach {
            replyGive(it.sender, "give-data-failed", "player" to name)
            warning("[BuildFlight] ${it.senderName} 向 $name 发放 ${formatEnergy(it.amount)} 点能量失败：能量数据不可用")
        }
    }

    private fun takePending(profile: PlayerProfile): List<PendingGive> {
        val drained = mutableListOf<PendingGive>()
        while (true) {
            val give = profile.pendingGives.poll() ?: break
            drained += give
        }
        return drained
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
            if (!profile.loaded || profile.loadFailed) {
                takePending(profile).forEach {
                    warning("[BuildFlight] 关闭时丢弃 ${it.senderName} 向 ${profile.uuid} 的 ${formatEnergy(it.amount)} 点发放：能量数据不可用")
                }
                continue
            }
            // 关闭时不再发消息，但已入队的发放必须算进去，否则会静默丢失。
            val name = profile.player?.name ?: profile.uuid.toString()
            val online = profile.player?.isOnline == true
            takePending(profile).forEach {
                profile.energy = saturatedAdd(profile.energy, it.amount)
                logGive(it.senderName, name, it.amount, profile.energy, online)
            }
            submitFinalSave(profile)
        }
        EnergyStorage.flush(5)
        profiles.clear()
    }

    /** 饱和相加：结果超出可表示范围时钳到 [Double.MAX_VALUE]，绝不产生 Infinity。 */
    private fun saturatedAdd(base: Double, amount: Double): Double {
        val sum = base + amount
        return if (sum.isFinite()) sum else Double.MAX_VALUE
    }

    /** 增量合并：数据库当前值加上本会话的增量，结果钳在 [0, Double.MAX_VALUE]。 */
    private fun mergeEnergy(dbValue: Double, delta: Double): Double {
        val sum = dbValue + delta
        return when {
            sum.isNaN() -> dbValue
            sum <= 0.0 -> 0.0
            !sum.isFinite() -> Double.MAX_VALUE
            else -> sum
        }
    }

    /** 从 `能量|冷却` 载荷里取出能量；空载荷（从未落库）视为 0。 */
    private fun payloadEnergy(payload: String): Double =
        payload.substringBefore('|').toDoubleOrNull() ?: 0.0

    private fun senderName(sender: CommandSender): String =
        if (sender is ConsoleCommandSender) "Console" else sender.name

    private fun logGive(senderName: String, target: String, amount: Double, energy: Double, online: Boolean) {
        info(
            "[BuildFlight] $senderName 给予 $target ${formatEnergy(amount)} 点能量" +
                "（${if (online) "在线" else "离线"}，当前 ${formatEnergy(energy)}）"
        )
    }

    /** 回执给命令发送者：玩家发送者投递回其区域线程，控制台直接发送。 */
    private fun replyGive(sender: CommandSender, key: String, vararg placeholders: Pair<String, Any?>) {
        val player = sender as? Player
        if (player == null) {
            PluginMessages.send(sender, key, *placeholders)
        } else {
            Scheduler.runOnEntity(player) { PluginMessages.send(player, key, *placeholders) }
        }
    }
}
