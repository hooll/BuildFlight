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
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import taboolib.module.ui.ClickEvent
import taboolib.module.ui.MenuHolder
import taboolib.module.ui.openMenu
import taboolib.module.ui.type.PageableChest
import taboolib.platform.util.buildItem
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 建筑飞行主面板。
 *
 * 槽位布局：
 *  4            当前能量状态
 *  11           一键存入全部可兑换材料
 *  15           飞行开关
 *  19..25 / 28..34 / 37..43  当前页材料按钮（每页 21 项）
 *  45 / 49 / 53 上一页 / 页码 / 下一页
 *
 * 构建顺序是显式的：
 *  1. onInventoryCreate 先铺玻璃，再画固定按钮和翻页按钮；
 *  2. PageableChest 的 finalBuild 只把当前页元素写进材料槽位。
 *
 * 不使用 set(Int, ItemStack) 铺玻璃：TabooLib 6.3.0 的 ChestImpl.onBuild
 * 会把新回调包在旧回调前面执行，先注册的玻璃最后执行，从而覆盖后注册的按钮。
 */
object ExchangeMenu {

    private const val MENU_ROWS = 6
    private const val MENU_SIZE = MENU_ROWS * 9

    private const val ENERGY_SLOT = 4
    private const val DEPOSIT_SLOT = 11
    private const val TOGGLE_SLOT = 15
    private const val EMPTY_SLOT = 22

    private const val PREVIOUS_PAGE_SLOT = 45
    private const val PAGE_SLOT = 49
    private const val NEXT_PAGE_SLOT = 53

    private val MATERIAL_SLOTS: List<Int> = listOf(
        19..25,
        28..34,
        37..43
    ).flatten()

    private val PAGE_SIZE: Int = MATERIAL_SLOTS.size

    /** 当前打开的 BuildFlight 菜单，用于配置重载后只刷新自己的界面。 */
    private val sessions = ConcurrentHashMap<UUID, PageableChest<ExchangeEntry>>()

    fun open(player: Player, page: Int = 0) {
        if (!player.isOnline) return
        val profile = EnergyManager.profile(player.uniqueId)
        val activeProfile = readyProfile(profile)
        val entries = if (activeProfile != null) PluginConfig.entries() else emptyList()
        val currentPage = clampPage(page, entries.size)
        val counts = if (activeProfile != null) countMaterials(player, entries) else emptyMap()
        val materialItems: Map<ExchangeEntry, ItemStack> = if (activeProfile != null) {
            entries.associateWith { entry ->
                materialItem(entry, counts[entry.material] ?: 0, activeProfile)
            }
        } else {
            emptyMap()
        }
        val status = statusText(player, profile)

        lateinit var menu: PageableChest<ExchangeEntry>
        player.openMenu<PageableChest<ExchangeEntry>>(PluginMessages.text("gui-title")) {
            menu = this
            rows(MENU_ROWS)
            menuLocked(true)
            handLocked(true)
            // 整页取消只负责拦截拿取/拖拽，元素与固定按钮回调仍会继续执行
            onClick(true) { }
            slots(MATERIAL_SLOTS)
            page(currentPage)
            elements { if (activeProfile != null) entries else emptyList() }

            // 同步生成；物品模板已在实体线程预生成，构建回调只克隆，不读配置或背包
            onGenerate(async = false) { _, entry, _, _ ->
                materialItems[entry]?.clone() ?: ItemStack(Material.AIR)
            }

            onInventoryCreate { inventory ->
                paintLayout(inventory, profile, activeProfile, status, entries.size, currentPage)
            }

            onClose {
                sessions.remove(player.uniqueId, menu)
            }

            onClick { event, entry ->
                handleMaterialClick(player, event, entry, menu, activeProfile)
            }

            onClick(DEPOSIT_SLOT) { event ->
                if (!isLeftClick(event) || activeProfile == null) return@onClick
                ExchangeManager.depositAll(player)
                refresh(player, menu, menu.page)
            }

            onClick(TOGGLE_SLOT) { event ->
                if (!isLeftClick(event) || activeProfile == null) return@onClick
                // toggle 内部会投递到玩家区域线程，回调完成后再刷新当前页
                FlightManager.toggle(player) { refresh(player, menu, menu.page) }
            }

            if (entries.size > PAGE_SIZE) {
                onClick(PREVIOUS_PAGE_SLOT) { event ->
                    if (!isLeftClick(event)) return@onClick
                    val total = if (activeProfile != null) PluginConfig.entries().size else 0
                    if (total <= 0) {
                        if (entries.isNotEmpty()) refresh(player, menu, 0)
                        return@onClick
                    }
                    val current = clampPage(menu.page, total)
                    if (current != menu.page) {
                        refresh(player, menu, current)
                        return@onClick
                    }
                    if (current <= 0) return@onClick
                    refresh(player, menu, current - 1)
                }

                onClick(NEXT_PAGE_SLOT) { event ->
                    if (!isLeftClick(event)) return@onClick
                    val total = if (activeProfile != null) PluginConfig.entries().size else 0
                    if (total <= 0) {
                        if (entries.isNotEmpty()) refresh(player, menu, 0)
                        return@onClick
                    }
                    val current = clampPage(menu.page, total)
                    if (current != menu.page) {
                        refresh(player, menu, current)
                        return@onClick
                    }
                    if (current >= pageCount(total) - 1) return@onClick
                    refresh(player, menu, current + 1)
                }
            }
        }
        if (isCurrentSession(player, menu)) {
            sessions[player.uniqueId] = menu
        }
    }

    /** 配置重载后刷新所有仍停留在本菜单的玩家；页码由 open 按新列表收敛。 */
    fun refreshOpen() {
        for ((uuid, session) in sessions) {
            val player = Bukkit.getPlayer(uuid)
            if (player == null || !player.isOnline) {
                sessions.remove(uuid, session)
                continue
            }
            Scheduler.runOnEntity(player) {
                if (!isCurrentSession(player, session)) {
                    sessions.remove(uuid, session)
                    return@runOnEntity
                }
                open(player, session.page)
            }
        }
    }

    fun clearSession(player: Player) {
        sessions.remove(player.uniqueId)
    }

    private fun paintLayout(
        inventory: Inventory,
        profile: PlayerProfile?,
        activeProfile: PlayerProfile?,
        status: String,
        total: Int,
        requestedPage: Int
    ) {
        val filler = fillerItem()
        for (slot in 0 until MENU_SIZE) {
            inventory.setItem(slot, filler.clone())
        }
        inventory.setItem(ENERGY_SLOT, energyItem(profile, status))

        if (activeProfile == null) {
            val state = stateItem(profile)
            inventory.setItem(DEPOSIT_SLOT, state.clone())
            inventory.setItem(TOGGLE_SLOT, state.clone())
            inventory.setItem(EMPTY_SLOT, state)
            return
        }

        inventory.setItem(DEPOSIT_SLOT, depositAllItem())
        inventory.setItem(TOGGLE_SLOT, toggleItem(activeProfile))

        if (total <= 0) {
            inventory.setItem(EMPTY_SLOT, noMaterialsItem())
            return
        }

        val pages = pageCount(total)
        val current = clampPage(requestedPage, total)
        val shown = minOf(PAGE_SIZE, total - current * PAGE_SIZE)
        inventory.setItem(PAGE_SLOT, pageIndicatorItem(current + 1, pages, total, shown))
        if (pages > 1) {
            inventory.setItem(
                PREVIOUS_PAGE_SLOT,
                if (current > 0) previousPageItem() else disabledPageItem()
            )
            inventory.setItem(
                NEXT_PAGE_SLOT,
                if (current < pages - 1) nextPageItem() else disabledPageItem()
            )
        }
    }

    private fun handleMaterialClick(
        player: Player,
        event: ClickEvent,
        entry: ExchangeEntry,
        session: PageableChest<ExchangeEntry>,
        activeProfile: PlayerProfile?
    ) {
        val click = event.clickEventOrNull() ?: return
        if (activeProfile == null) return
        // 配置在菜单打开后被修改时，旧按钮不按旧价格结算，先刷新到当前配置
        if (PluginConfig.pointsOf(entry.material) != entry.points) {
            refresh(player, session, session.page)
            return
        }
        val amount = when (click.click) {
            ClickType.LEFT -> Int.MAX_VALUE
            ClickType.RIGHT -> 1
            ClickType.SHIFT_LEFT -> 64
            else -> return
        }
        ExchangeManager.depositMaterial(player, entry, amount)
        refresh(player, session, session.page)
    }

    private fun isLeftClick(event: ClickEvent): Boolean =
        event.clickEventOrNull()?.click == ClickType.LEFT

    private fun countMaterials(player: Player, entries: List<ExchangeEntry>): Map<Material, Int> {
        if (entries.isEmpty()) return emptyMap()
        val wanted = entries.mapTo(HashSet(entries.size)) { it.material }
        val counts = HashMap<Material, Int>()
        for (stack in player.inventory.storageContents) {
            if (stack != null && stack.type in wanted) {
                counts[stack.type] = (counts[stack.type] ?: 0) + stack.amount
            }
        }
        return counts
    }

    private fun pageCount(total: Int): Int =
        if (total <= 0) 0 else (total - 1) / PAGE_SIZE + 1

    private fun clampPage(page: Int, total: Int): Int =
        if (total <= 0) 0 else page.coerceIn(0, pageCount(total) - 1)

    private fun readyProfile(profile: PlayerProfile?): PlayerProfile? =
        profile?.takeIf { it.loaded && !it.loadFailed }

    private fun energyItem(profile: PlayerProfile?, status: String): ItemStack =
        buildItem(Material.EXPERIENCE_BOTTLE) {
            name = PluginMessages.text("gui-energy-name")
            lore += PluginMessages.textList(
                "gui-energy-lore",
                "energy" to formatEnergy(profile?.energy ?: 0.0),
                "consume" to formatEnergy(PluginConfig.consumePerSecond),
                "max" to formatEnergy(PluginConfig.maxEnergyReference),
                "status" to status
            )
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
        entry: ExchangeEntry,
        have: Int,
        profile: PlayerProfile
    ): ItemStack = buildItem(entry.material) {
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

    private fun fillerItem(): ItemStack = buildItem(Material.GRAY_STAINED_GLASS_PANE) {
        name = " "
    }

    private fun loadingItem(): ItemStack = buildItem(Material.CLOCK) {
        name = PluginMessages.text("gui-loading-name")
        lore += PluginMessages.textList("gui-loading-lore")
    }

    private fun failedItem(): ItemStack = buildItem(Material.BARRIER) {
        name = PluginMessages.text("status-failed")
        lore += PluginMessages.textList("gui-loading-lore")
    }

    private fun stateItem(profile: PlayerProfile?): ItemStack =
        if (profile?.loadFailed == true) failedItem() else loadingItem()

    private fun noMaterialsItem(): ItemStack = buildItem(Material.BARRIER) {
        name = PluginMessages.text("gui-no-materials-name")
        lore += PluginMessages.textList("gui-no-materials-lore")
    }

    private fun previousPageItem(): ItemStack = buildItem(Material.ARROW) {
        name = PluginMessages.text("gui-page-prev-name")
        lore += PluginMessages.textList("gui-page-prev-lore")
    }

    private fun nextPageItem(): ItemStack = buildItem(Material.ARROW) {
        name = PluginMessages.text("gui-page-next-name")
        lore += PluginMessages.textList("gui-page-next-lore")
    }

    private fun disabledPageItem(): ItemStack = buildItem(Material.GRAY_STAINED_GLASS_PANE) {
        name = PluginMessages.text("gui-page-disabled-name")
        lore += PluginMessages.textList("gui-page-disabled-lore")
    }

    private fun pageIndicatorItem(page: Int, pages: Int, total: Int, shown: Int): ItemStack =
        buildItem(Material.PAPER) {
            name = PluginMessages.text(
                "gui-page-indicator-name",
                "page" to page,
                "pages" to pages
            )
            lore += PluginMessages.textList(
                "gui-page-indicator-lore",
                "page" to page,
                "pages" to pages,
                "total" to total,
                "shown" to shown
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
     * 下一次实体 tick 重开菜单，并确认玩家仍停留在同一个菜单实例上。
     * 新会话会创建新的菜单实例，旧回调因此不会覆盖新界面。
     */
    private fun refresh(player: Player, session: PageableChest<ExchangeEntry>, page: Int) {
        Scheduler.runOnEntity(player) {
            if (!isCurrentSession(player, session)) return@runOnEntity
            open(player, page)
        }
    }

    private fun isCurrentSession(player: Player, session: PageableChest<ExchangeEntry>): Boolean {
        if (!player.isOnline) return false
        val holder = player.openInventory.topInventory.holder as? MenuHolder ?: return false
        return holder.menu === session
    }
}
