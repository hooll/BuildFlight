package cn.stingcraft.buildflight.manager

import cn.stingcraft.buildflight.config.ExchangeEntry
import cn.stingcraft.buildflight.config.PluginConfig
import cn.stingcraft.buildflight.config.PluginMessages
import cn.stingcraft.buildflight.data.PlayerProfile
import cn.stingcraft.buildflight.util.formatEnergy
import cn.stingcraft.buildflight.util.prettyMaterialName
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
                    "material" to prettyMaterialName(entry.material.name)
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
                    "material" to prettyMaterialName(entry.material.name)
                )
                return false
            }

            val gained = entry.points * removed
            credit(profile, gained)
            PluginMessages.send(
                player,
                "deposit-success",
                "material" to prettyMaterialName(entry.material.name),
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
