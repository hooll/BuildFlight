package cn.stingcraft.buildflight.ui

import cn.stingcraft.buildflight.config.BundleConfig
import cn.stingcraft.buildflight.config.BundleEntry
import cn.stingcraft.buildflight.config.ExchangeEntry
import cn.stingcraft.buildflight.config.PluginConfig
import cn.stingcraft.buildflight.config.PluginMessages
import cn.stingcraft.buildflight.config.displayName
import cn.stingcraft.buildflight.data.PlayerProfile
import cn.stingcraft.buildflight.manager.EnergyManager
import cn.stingcraft.buildflight.manager.ExchangeManager
import cn.stingcraft.buildflight.manager.FlightManager
import cn.stingcraft.buildflight.util.Scheduler
import cn.stingcraft.buildflight.util.formatEnergy
import cn.stingcraft.buildflight.util.localizedMaterialName
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

/** 面板里的一项可兑换内容：套餐排在材料之前。 */
sealed interface Offer {
    data class Bundle(val entry: BundleEntry) : Offer
    data class Material(val entry: ExchangeEntry) : Offer
}

/**
 * 建筑飞行主面板。
 *
 * 槽位布局：
 *  4            当前能量状态
 *  11           一键存入全部可兑换材料
 *  15           飞行开关
 *  19..25 / 28..34 / 37..43  当前页套餐与材料按钮（每页 21 项）
 *  45 / 49 / 53 上一页 / 页码 / 下一页
 *
 * 列表顺序固定为“套餐（配置顺序）→ 材料（配置顺序）”，共用同一套分页。
 *
 * 构建顺序是显式的：
 *  1. onInventoryCreate 先铺玻璃，再画固定按钮和翻页按钮；
 *  2. PageableChest 的 finalBuild 只把当前页元素写进列表槽位。
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
    private val sessions = ConcurrentHashMap<UUID, PageableChest<Offer>>()

    fun open(player: Player, page: Int = 0) {
        if (!player.isOnline) return
        val profile = EnergyManager.profile(player.uniqueId)
        val activeProfile = readyProfile(profile)
        val offers = if (activeProfile != null) collectOffers() else emptyList()
        val currentPage = clampPage(page, offers.size)
        val counts = if (activeProfile != null) countMaterials(player, offers) else emptyMap()
        val offerItems: Map<Offer, ItemStack> = if (activeProfile != null) {
            offers.associateWith { offer -> offerItem(player, offer, counts, activeProfile) }
        } else {
            emptyMap()
        }
        val status = statusText(player, profile)

        lateinit var menu: PageableChest<Offer>
        player.openMenu<PageableChest<Offer>>(PluginMessages.text("gui-title")) {
            menu = this
            rows(MENU_ROWS)
            menuLocked(true)
            handLocked(true)
            // 整页取消只负责拦截拿取/拖拽，元素与固定按钮回调仍会继续执行
            onClick(true) { }
            slots(MATERIAL_SLOTS)
            page(currentPage)
            elements { if (activeProfile != null) offers else emptyList() }

            // 同步生成；物品模板已在实体线程预生成，构建回调只克隆，不读配置或背包
            onGenerate(async = false) { _, offer, _, _ ->
                offerItems[offer]?.clone() ?: ItemStack(Material.AIR)
            }

            onInventoryCreate { inventory ->
                paintLayout(inventory, profile, activeProfile, status, offers.size, currentPage)
            }

            onClose {
                sessions.remove(player.uniqueId, menu)
            }

            onClick { event, offer ->
                when (offer) {
                    is Offer.Material ->
                        handleMaterialClick(player, event, offer.entry, menu, activeProfile)
                    is Offer.Bundle ->
                        handleBundleClick(player, event, offer.entry, menu, activeProfile)
                }
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

            if (offers.size > PAGE_SIZE) {
                onClick(PREVIOUS_PAGE_SLOT) { event ->
                    if (!isLeftClick(event)) return@onClick
                    val total = if (activeProfile != null) offerCount() else 0
                    if (total <= 0) {
                        if (offers.isNotEmpty()) refresh(player, menu, 0)
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
                    val total = if (activeProfile != null) offerCount() else 0
                    if (total <= 0) {
                        if (offers.isNotEmpty()) refresh(player, menu, 0)
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

    /** 套餐在前、材料在后，各自保持配置文件里的顺序。 */
    private fun collectOffers(): List<Offer> =
        BundleConfig.entries().map { Offer.Bundle(it) } +
            PluginConfig.entries().map { Offer.Material(it) }

    private fun offerCount(): Int = BundleConfig.entries().size + PluginConfig.entries().size

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
        session: PageableChest<Offer>,
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

    /**
     * 套餐按钮点击。
     *
     * 点击时重新按 id 取当前配置：条目被删除或内容（价格/需求）变化时只刷新，
     * 绝不按旧按钮上的价格结算。
     */
    private fun handleBundleClick(
        player: Player,
        event: ClickEvent,
        bundle: BundleEntry,
        session: PageableChest<Offer>,
        activeProfile: PlayerProfile?
    ) {
        val click = event.clickEventOrNull() ?: return
        if (activeProfile == null) return
        val current = BundleConfig.byId(bundle.id)
        if (current != bundle) {
            refresh(player, session, session.page)
            return
        }
        val requested = when (click.click) {
            ClickType.LEFT, ClickType.RIGHT -> 1
            ClickType.SHIFT_LEFT -> Int.MAX_VALUE
            else -> return
        }
        ExchangeManager.exchangeBundle(player, current, requested)
        refresh(player, session, session.page)
    }

    private fun isLeftClick(event: ClickEvent): Boolean =
        event.clickEventOrNull()?.click == ClickType.LEFT

    /** 一次扫描主背包，统计所有套餐与材料需求涉及的物品数量。 */
    private fun countMaterials(player: Player, offers: List<Offer>): Map<Material, Int> {
        if (offers.isEmpty()) return emptyMap()
        val wanted = HashSet<Material>()
        for (offer in offers) {
            when (offer) {
                is Offer.Material -> wanted += offer.entry.material
                is Offer.Bundle -> offer.entry.items.forEach { wanted += it.material }
            }
        }
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

    private fun offerItem(
        player: Player,
        offer: Offer,
        counts: Map<Material, Int>,
        profile: PlayerProfile
    ): ItemStack = when (offer) {
        is Offer.Material ->
            materialItem(player, offer.entry, counts[offer.entry.material] ?: 0, profile)
        is Offer.Bundle ->
            bundleItem(player, offer.entry, counts)
    }

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
        player: Player,
        entry: ExchangeEntry,
        have: Int,
        profile: PlayerProfile
    ): ItemStack = buildItem(entry.material) {
        name = PluginMessages.text(
            "gui-material-name",
            "material" to localizedMaterialName(player, entry.material),
            "points" to formatEnergy(entry.points)
        )
        lore += PluginMessages.textList(
            "gui-material-lore",
            "have" to have,
            "points" to formatEnergy(entry.points),
            "energy" to formatEnergy(profile.energy)
        )
    }

    /**
     * 套餐按钮：奖励 → 逐项需求（拥有/需要）→ 单件合计与折扣对比 → 点击说明。
     *
     * 只有全部需求材料都在单件兑换表里时才显示对比，避免拿不完整的合计误导玩家；
     * 套餐比单件贵时如实提示“比单件少”。
     */
    private fun bundleItem(
        player: Player,
        bundle: BundleEntry,
        counts: Map<Material, Int>
    ): ItemStack {
        val lines = mutableListOf<String>()
        lines += PluginMessages.textList("gui-bundle-lore", "points" to formatEnergy(bundle.points))
        for (item in bundle.items) {
            val have = counts[item.material] ?: 0
            val key = if (have >= item.amount) {
                "gui-bundle-requirement-ok"
            } else {
                "gui-bundle-requirement-lack"
            }
            lines += PluginMessages.text(
                key,
                "material" to localizedMaterialName(player, item.material),
                "need" to item.amount,
                "have" to have
            )
        }
        val base = bundle.basePoints(PluginConfig.materialPoints())
        if (base != null) {
            lines += PluginMessages.text("gui-bundle-compare", "base" to formatEnergy(base))
            when {
                bundle.points > base -> {
                    val bonus = bundle.points - base
                    val percent = if (base > 0.0) bonus / base * 100.0 else 0.0
                    lines += PluginMessages.text(
                        "gui-bundle-bonus",
                        "bonus" to formatEnergy(bonus),
                        "percent" to formatEnergy(percent)
                    )
                }

                bundle.points < base -> {
                    lines += PluginMessages.text(
                        "gui-bundle-penalty",
                        "diff" to formatEnergy(base - bundle.points)
                    )
                }
            }
        }
        lines += PluginMessages.textList("gui-bundle-lore-footer")

        return buildItem(bundle.displayIcon) {
            name = bundle.displayName()
            lore += lines
        }
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
                "gui-offer-indicator-lore",
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
    private fun refresh(player: Player, session: PageableChest<Offer>, page: Int) {
        Scheduler.runOnEntity(player) {
            if (!isCurrentSession(player, session)) return@runOnEntity
            open(player, page)
        }
    }

    private fun isCurrentSession(player: Player, session: PageableChest<Offer>): Boolean {
        if (!player.isOnline) return false
        val holder = player.openInventory.topInventory.holder as? MenuHolder ?: return false
        return holder.menu === session
    }
}
