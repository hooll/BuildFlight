package cn.stingcraft.buildflight.manager

import cn.stingcraft.buildflight.config.BundleEntry
import cn.stingcraft.buildflight.config.ExchangeEntry
import cn.stingcraft.buildflight.config.PluginConfig
import cn.stingcraft.buildflight.config.PluginMessages
import cn.stingcraft.buildflight.config.displayName
import cn.stingcraft.buildflight.data.PlayerProfile
import cn.stingcraft.buildflight.util.formatEnergy
import cn.stingcraft.buildflight.util.localizedMaterialName
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.function.warning

/**
 * 背包材料 -> 能量的结算。
 *
 * 原子性约定：
 *  1. 先在玩家区域线程上确认能量档案已加载；
 *  2. 再从背包精确扣除物品（扣多少算多少，绝不按物品总数估算）；
 *  3. 扣除完成后才写入内存能量，最后异步落库。
 *
 * 内存加能量不会失败，所以“先扣物、后加能量”的顺序只会朝少给玩家能量的方向偏差，
 * 不会产生凭空刷能量的窗口。若扣物过程抛异常，会把已移除的物品原样放回。
 */
object ExchangeManager {

    fun countMaterial(player: Player, material: Material): Int =
        player.inventory.storageContents
            .filterNotNull()
            .filter { it.type == material }
            .sumOf { it.amount }

    /** 一键存入背包内所有配置允许的材料。 */
    fun depositAll(player: Player): Boolean {
        val profile = readyProfile(player) ?: return false
        val table = PluginConfig.materialPoints()
        if (table.isEmpty()) {
            PluginMessages.send(player, "no-materials")
            return false
        }
        if (!begin(profile)) return false

        try {
            val inventory = player.inventory
            val contents = inventory.storageContents
            val removed = mutableListOf<Pair<Int, ItemStack>>()
            var items = 0
            var gained = 0.0

            try {
                for (slot in contents.indices) {
                    val stack = contents[slot] ?: continue
                    val perItem = table[stack.type] ?: continue
                    items += stack.amount
                    gained += perItem * stack.amount
                    removed += slot to stack.clone()
                    inventory.setItem(slot, null)
                }
            } catch (error: Throwable) {
                rollback(inventory, removed)
                warning("[BuildFlight] 一键存入扣除物品失败 (${player.name}): $error")
                PluginMessages.send(player, "deposit-failed")
                return false
            }

            if (items <= 0) {
                PluginMessages.send(player, "deposit-none")
                return false
            }

            credit(profile, gained)
            PluginMessages.send(
                player,
                "deposit-all-success",
                "items" to items,
                "points" to formatEnergy(gained),
                "energy" to formatEnergy(profile.energy)
            )
            afterChange(player, profile)
            return true
        } finally {
            profile.exchanging = false
        }
    }

    /**
     * 存入指定材料。
     *
     * @param requested 期望数量；Int.MAX_VALUE 表示全部
     */
    fun depositMaterial(player: Player, entry: ExchangeEntry, requested: Int): Boolean {
        val profile = readyProfile(player) ?: return false
        if (requested <= 0) return false
        if (!begin(profile)) return false

        try {
            val inventory = player.inventory
            val contents = inventory.storageContents
            val available = contents
                .filterNotNull()
                .filter { it.type == entry.material }
                .sumOf { it.amount }
            if (available <= 0) {
                PluginMessages.send(
                    player,
                    "deposit-none-material",
                    "material" to localizedMaterialName(player, entry.material)
                )
                return false
            }

            val take = if (requested == Int.MAX_VALUE) available else minOf(requested, available)
            if (take <= 0) return false

            val snapshot = contents.mapIndexedNotNull { index, stack ->
                if (stack != null && stack.type == entry.material) index to stack.clone() else null
            }

            var removed = 0
            try {
                var remaining = take
                for (slot in contents.indices) {
                    if (remaining <= 0) break
                    val stack = contents[slot] ?: continue
                    if (stack.type != entry.material) continue

                    val amount = minOf(stack.amount, remaining)
                    stack.amount -= amount
                    remaining -= amount
                    removed += amount
                    inventory.setItem(slot, if (stack.amount <= 0) null else stack)
                }
            } catch (error: Throwable) {
                snapshot.forEach { (slot, stack) ->
                    runCatching { inventory.setItem(slot, stack) }
                }
                warning("[BuildFlight] 存入材料扣除失败 (${player.name}): $error")
                PluginMessages.send(player, "deposit-failed")
                return false
            }

            if (removed <= 0) {
                PluginMessages.send(
                    player,
                    "deposit-none-material",
                    "material" to localizedMaterialName(player, entry.material)
                )
                return false
            }

            val gained = entry.points * removed
            credit(profile, gained)
            PluginMessages.send(
                player,
                "deposit-success",
                "material" to localizedMaterialName(player, entry.material),
                "items" to removed,
                "points" to formatEnergy(gained),
                "energy" to formatEnergy(profile.energy)
            )
            afterChange(player, profile)
            return true
        } finally {
            profile.exchanging = false
        }
    }

    /**
     * 兑换指定套餐。
     *
     * 原子性：先在快照里逐槽精确扣除，扣完校验每种材料的实扣量等于“需求 × 份数”，
     * 任一步异常或数量对不上就按快照原槽位放回，绝不出现“扣了物没加能量”或“加能量没扣物”。
     *
     * @param requested 期望份数；[Int.MAX_VALUE] 表示在材料允许范围内尽可能多
     */
    fun exchangeBundle(player: Player, bundle: BundleEntry, requested: Int): Boolean {
        val profile = readyProfile(player) ?: return false
        if (requested <= 0 || bundle.items.isEmpty()) return false
        if (!begin(profile)) return false

        try {
            val inventory = player.inventory
            val contents = inventory.storageContents

            val wanted = bundle.items.mapTo(HashSet()) { it.material }
            val have = HashMap<Material, Int>()
            for (stack in contents) {
                if (stack != null && stack.type in wanted) {
                    have[stack.type] = (have[stack.type] ?: 0) + stack.amount
                }
            }

            val maxPortions = bundle.items.minOf { (have[it.material] ?: 0) / it.amount }
            val portions = minOf(requested, maxPortions)
            if (portions <= 0) {
                PluginMessages.send(
                    player,
                    "bundle-not-enough",
                    "bundle" to bundle.displayName(),
                    "missing" to missingText(player, bundle, have)
                )
                return false
            }

            // 扣物前先确认奖励可表示：避免扣了物却只能加 Infinity 或加不上能量。
            val gained = bundle.points * portions
            if (!gained.isFinite() || gained <= 0.0) {
                warning("[BuildFlight] 套餐 ${bundle.id} 的奖励无法表示: ${bundle.points} × $portions")
                PluginMessages.send(player, "bundle-failed")
                return false
            }

            val required = bundle.items.associate { it.material to it.amount * portions }
            val snapshot = contents.mapIndexedNotNull { index, stack ->
                if (stack != null && stack.type in required) index to stack.clone() else null
            }

            val remaining = required.toMutableMap()
            try {
                for (slot in contents.indices) {
                    val stack = contents[slot] ?: continue
                    // 先把材料名取出来：setAmount(0) 之后 CraftItemStack 的 type 会变成 AIR，
                    // 再拿 stack.type 当键就会写到一个不存在的键上，导致扣除校验永远失败。
                    val material = stack.type
                    val need = remaining[material] ?: continue
                    if (need <= 0) continue
                    val take = minOf(need, stack.amount)
                    stack.amount -= take
                    remaining[material] = need - take
                    inventory.setItem(slot, if (stack.amount <= 0) null else stack)
                }
            } catch (error: Throwable) {
                rollback(inventory, snapshot)
                warning("[BuildFlight] 套餐扣除物品失败 (${player.name}): $error")
                PluginMessages.send(player, "bundle-failed")
                return false
            }

            val unfinished = remaining.filterValues { it > 0 }
            if (unfinished.isNotEmpty()) {
                rollback(inventory, snapshot)
                warning("[BuildFlight] 套餐 ${bundle.id} 扣除数量校验失败 (${player.name}): $unfinished")
                PluginMessages.send(player, "bundle-failed")
                return false
            }

            credit(profile, gained)
            PluginMessages.send(
                player,
                "bundle-success",
                "portions" to portions,
                "bundle" to bundle.displayName(),
                "points" to formatEnergy(gained),
                "energy" to formatEnergy(profile.energy)
            )
            afterChange(player, profile)
            return true
        } finally {
            profile.exchanging = false
        }
    }

    /** 材料不足时列出缺少项，格式“材料 拥有/需要”。 */
    private fun missingText(
        player: Player,
        bundle: BundleEntry,
        have: Map<Material, Int>
    ): String = bundle.items
        .filter { (have[it.material] ?: 0) < it.amount }
        .joinToString(", ") { item ->
            val owned = have[item.material] ?: 0
            "${localizedMaterialName(player, item.material)} $owned/${item.amount}"
        }

    private fun readyProfile(player: Player): PlayerProfile? {
        val profile = EnergyManager.profile(player.uniqueId)
        if (profile == null || !profile.loaded) {
            PluginMessages.send(player, "data-loading")
            return null
        }
        if (profile.loadFailed) {
            PluginMessages.send(player, "data-failed")
            return null
        }
        return profile
    }

    private fun begin(profile: PlayerProfile): Boolean {
        if (profile.exchanging) return false
        profile.exchanging = true
        return true
    }

    private fun credit(profile: PlayerProfile, amount: Double) {
        if (!amount.isFinite() || amount <= 0.0) return
        val next = profile.energy + amount
        profile.energy = if (next.isFinite()) next else Double.MAX_VALUE
    }

    private fun afterChange(player: Player, profile: PlayerProfile) {
        EnergyManager.saveAsync(profile)
        if (profile.flying) {
            if (profile.bossBar == null) {
                BossBarManager.show(player, profile)
            } else {
                BossBarManager.update(player, profile)
            }
        }
    }

    private fun rollback(
        inventory: org.bukkit.inventory.PlayerInventory,
        removed: List<Pair<Int, ItemStack>>
    ) {
        for ((slot, stack) in removed) {
            runCatching { inventory.setItem(slot, stack) }
        }
    }
}
