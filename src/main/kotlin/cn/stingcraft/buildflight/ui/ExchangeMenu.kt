package cn.stingcraft.buildflight.ui

import cn.stingcraft.buildflight.config.ExchangeEntry
import cn.stingcraft.buildflight.config.PluginConfig
import cn.stingcraft.buildflight.config.PluginMessages
import cn.stingcraft.buildflight.data.PlayerProfile
import cn.stingcraft.buildflight.manager.EnergyManager
import cn.stingcraft.buildflight.manager.ExchangeManager
import cn.stingcraft.buildflight.manager.FlightManager
import cn.stingcraft.buildflight.util.Scheduler
import cn.stingcraft.buildflight.util.formatEnergy
import cn.stingcraft.buildflight.util.prettyMaterialName
import cn.stingcraft.buildflight.util.remainingSeconds
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import taboolib.module.ui.MenuHolder
import taboolib.module.ui.openMenu
import taboolib.module.ui.type.Chest
import taboolib.platform.util.buildItem

/**
 * 建筑飞行主面板。
 *
 * 槽位布局：
 *  4            当前能量状态
 *  11           一键存入全部可兑换材料
 *  15           飞行开关
 *  19..25 / 28..34 / 37..43  单类材料存入按钮
 */
object ExchangeMenu {

    private val materialSlots: List<Int> = listOf(
        19..25,
        28..34,
        37..43
    ).flatten()

    fun open(player: Player) {
        if (!player.isOnline) return
        val profile = EnergyManager.profile(player.uniqueId)

        player.openMenu<Chest>(PluginMessages.text("gui-title")) {
            rows(6)
            // 锁定整页点击/拖拽，防止玩家把菜单物品拿走或把背包物品塞进菜单
            onClick(true) { }

            val filler = buildItem(Material.GRAY_STAINED_GLASS_PANE) {
                name = " "
            }
            for (slot in 0 until 54) {
                set(slot, filler.clone())
            }

            set(4, energyItem(player, profile))

            val readyProfile = profile?.takeIf { it.loaded && !it.loadFailed }
            if (readyProfile == null) {
                set(11, loadingItem())
                set(15, loadingItem())
                return@openMenu
            }

            set(11, depositAllItem()) {
                if (clickEventOrNull() != null) {
                    ExchangeManager.depositAll(player)
                    refresh(player)
                }
            }

            set(15, toggleItem(readyProfile)) {
                if (clickEventOrNull() != null) {
                    // toggle 内部会投递到玩家区域线程，回调里重开面板刷新状态
                    FlightManager.toggle(player) { reopenIfMenuOpen(player) }
                }
            }

            val entries = PluginConfig.entries()
            if (entries.isEmpty()) {
                set(22, noMaterialsItem())
            } else {
                entries.take(materialSlots.size).forEachIndexed { index, entry ->
                    val slot = materialSlots[index]
                    set(slot, materialItem(player, readyProfile, entry)) {
                        val click = clickEventOrNull()
                        if (click != null) {
                            val amount = when {
                                click.isShiftClick && click.isLeftClick -> 64
                                click.isRightClick -> 1
                                else -> Int.MAX_VALUE
                            }
                            ExchangeManager.depositMaterial(player, entry, amount)
                            refresh(player)
                        }
                    }
                }
                if (entries.size > materialSlots.size) {
                    set(49, moreMaterialsItem(entries.size))
                }
            }
        }
    }

    private fun energyItem(player: Player, profile: PlayerProfile?): ItemStack {
        val status = statusText(player, profile)
        return buildItem(Material.EXPERIENCE_BOTTLE) {
            name = PluginMessages.text("gui-energy-name")
            lore += PluginMessages.textList(
                "gui-energy-lore",
                "energy" to formatEnergy(profile?.energy ?: 0.0),
                "consume" to formatEnergy(PluginConfig.consumePerSecond),
                "max" to formatEnergy(PluginConfig.maxEnergyReference),
                "status" to status
            )
        }
    }

    private fun toggleItem(profile: PlayerProfile): ItemStack {
        val flying = profile.flying
        val nameKey = if (flying) "gui-toggle-on-name" else "gui-toggle-off-name"
        val loreKey = if (flying) "gui-toggle-on-lore" else "gui-toggle-off-lore"
        return buildItem(if (flying) Material.FEATHER else Material.ELYTRA) {
            name = PluginMessages.text(nameKey)
            lore += PluginMessages.textList(
                loreKey,
                "energy" to formatEnergy(profile.energy),
                "seconds" to remainingSeconds(profile.combatUntil)
            )
        }
    }

    private fun depositAllItem(): ItemStack = buildItem(Material.HOPPER) {
        name = PluginMessages.text("gui-deposit-all-name")
        lore += PluginMessages.textList("gui-deposit-all-lore")
    }

    private fun materialItem(
        player: Player,
        profile: PlayerProfile,
        entry: ExchangeEntry
    ): ItemStack {
        val have = ExchangeManager.countMaterial(player, entry.material)
        return buildItem(entry.material) {
            name = PluginMessages.text(
                "gui-material-name",
                "material" to prettyMaterialName(entry.material.name),
                "points" to formatEnergy(entry.points)
            )
            lore += PluginMessages.textList(
                "gui-material-lore",
                "have" to have,
                "points" to formatEnergy(entry.points),
                "energy" to formatEnergy(profile.energy)
            )
        }
    }

    private fun loadingItem(): ItemStack = buildItem(Material.CLOCK) {
        name = PluginMessages.text("gui-loading-name")
        lore += PluginMessages.textList("gui-loading-lore")
    }

    private fun noMaterialsItem(): ItemStack = buildItem(Material.BARRIER) {
        name = PluginMessages.text("gui-no-materials-name")
        lore += PluginMessages.textList("gui-no-materials-lore")
    }

    private fun moreMaterialsItem(total: Int): ItemStack = buildItem(Material.BOOK) {
        name = PluginMessages.text("gui-more-name")
        lore += PluginMessages.textList(
            "gui-more-lore",
            "total" to total,
            "shown" to materialSlots.size
        )
    }

    private fun statusText(player: Player, profile: PlayerProfile?): String {
        if (profile == null || !profile.loaded) return PluginMessages.text("status-loading")
        if (profile.loadFailed) return PluginMessages.text("status-failed")
        if (profile.flying) {
            // 双击空格收起原版飞行后，建筑飞行模式仍在，只是进入地面/下落待机
            return if (player.isFlying) {
                PluginMessages.text("status-flying")
            } else {
                PluginMessages.text("status-standby")
            }
        }

        val cooldown = remainingSeconds(profile.combatUntil)
        if (cooldown > 0L) {
            return PluginMessages.text("status-combat", "seconds" to cooldown)
        }
        return PluginMessages.text("status-idle")
    }

    /**
     * 面板刷新：下一次实体 tick 重开，避免在 InventoryClickEvent 内直接操作 InventoryView。
     */
    private fun refresh(player: Player) {
        Scheduler.runOnEntity(player) {
            reopenIfMenuOpen(player)
        }
    }

    /** 只有玩家当前仍停留在本菜单时才重开，避免把已经关闭面板的玩家强行拉回界面。 */
    private fun reopenIfMenuOpen(player: Player) {
        if (!player.isOnline) return
        if (player.openInventory.topInventory.holder is MenuHolder) {
            open(player)
        }
    }
}
