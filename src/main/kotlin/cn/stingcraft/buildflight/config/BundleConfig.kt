package cn.stingcraft.buildflight.config

import org.bukkit.Material
import taboolib.common.platform.function.warning
import taboolib.library.configuration.ConfigurationSection
import taboolib.module.chat.colored
import taboolib.module.configuration.Config
import taboolib.module.configuration.Configuration

/**
 * 套餐中的一项需求材料。
 *
 * @param material 需求材料
 * @param amount   每份套餐需要的数量
 */
data class BundleItem(
    val material: Material,
    val amount: Int
)

/**
 * 一个套餐：固定的一组材料 + 固定能量奖励。
 *
 * @param id     套餐唯一标识（bundles.yml 下的键）
 * @param name   显示名（原始配置文本，允许为空）
 * @param icon   GUI 图标；为空或非法时回退到第一个需求材料
 * @param points 每份套餐奖励的能量点数
 * @param items  需求材料（保持配置顺序）
 */
data class BundleEntry(
    val id: String,
    val name: String?,
    val icon: Material?,
    val points: Double,
    val items: List<BundleItem>
) {

    /** GUI 实际使用的图标。 */
    val displayIcon: Material
        get() = icon ?: items.first().material

    /** 每份套餐需要的某材料数量，不需要则为 0。 */
    fun amountOf(material: Material): Int =
        items.firstOrNull { it.material == material }?.amount ?: 0

    /**
     * 按单件兑换表计算的需求材料总价。
     *
     * 只要有一项材料不在单件表里就返回 null —— 此时 GUI 不显示折扣对比，
     * 避免拿不完整的合计去误导玩家。结果必须是有限数。
     */
    fun basePoints(table: Map<Material, Double>): Double? {
        var sum = 0.0
        for (item in items) {
            val per = table[item.material] ?: return null
            sum += per * item.amount
            if (!sum.isFinite()) return null
        }
        return sum
    }
}

/** 套餐显示名：配置了 name 就用它（支持 `&` 颜色），否则用 `gui-bundle-name`。 */
fun BundleEntry.displayName(): String =
    name?.colored() ?: PluginMessages.text("gui-bundle-name", "bundle" to id)

/**
 * bundles.yml 的读取入口。
 *
 * 校验策略：任何一个字段非法都只记一条 warning 并整条跳过，
 * 绝不让半套套餐进入内存；文件缺失/损坏等同于“没有套餐”，插件照常启动。
 */
object BundleConfig {

    @Config("bundles.yml", autoReload = true)
    lateinit var config: Configuration
        private set

    @Volatile
    private var bundles: List<BundleEntry> = emptyList()

    @Volatile
    private var indexed: Map<String, BundleEntry> = emptyMap()

    @Volatile
    private var reloadHookInstalled = false

    /** 插件启用时调用一次；@Config 会在文件缺失时按资源生成默认文件。 */
    fun load() {
        if (!reloadHookInstalled) {
            reloadHookInstalled = true
            config.onReload { reloadBundles() }
        }
        reloadBundles()
    }

    /** /bflight reload 调用：文件损坏也绝不把异常抛给命令层。 */
    fun reload() {
        val reloaded = runCatching { config.reload() }
            .onFailure { warning("[BuildFlight] bundles.yml 重载失败，本轮的套餐列表已清空: $it") }
            .isSuccess
        if (!reloaded) {
            bundles = emptyList()
            indexed = emptyMap()
            return
        }
        reloadBundles()
    }

    fun entries(): List<BundleEntry> = bundles

    fun byId(id: String): BundleEntry? = indexed[id]

    private fun reloadBundles() {
        val built = runCatching { buildBundles() }
            .getOrElse {
                warning("[BuildFlight] bundles.yml 读取失败，本轮不加载任何套餐: $it")
                emptyList()
            }
        bundles = built
        indexed = built.associateBy { it.id }
    }

    private fun buildBundles(): List<BundleEntry> {
        val section = config.getConfigurationSection("bundles") ?: return emptyList()
        val loaded = mutableListOf<BundleEntry>()
        for (id in section.getKeys(false)) {
            val entry = parseBundle(id, section) ?: continue
            loaded += entry
        }
        return loaded.toList()
    }

    private fun parseBundle(id: String, parent: ConfigurationSection): BundleEntry? {
        val section = parent.getConfigurationSection(id)
        if (section == null) {
            skip(id, "必须是配置节")
            return null
        }

        val points = section.getDouble("points", Double.NaN)
        if (!points.isFinite() || points <= 0.0) {
            skip(id, "points 必须是大于 0 的数字")
            return null
        }

        val itemSection = section.getConfigurationSection("items")
        if (itemSection == null) {
            skip(id, "缺少 items 配置节")
            return null
        }

        val items = mutableListOf<BundleItem>()
        for (key in itemSection.getKeys(false)) {
            val material = PluginConfig.parseMaterial(key)
            if (material == null || !material.isItem || material.isAir) {
                skip(id, "items 中存在无效材料: $key")
                return null
            }
            if (items.any { it.material == material }) {
                skip(id, "items 中重复配置了材料 $material")
                return null
            }
            val amount = itemSection.getInt(key, 0)
            if (amount <= 0) {
                skip(id, "items.$key 的数量必须是正整数")
                return null
            }
            items += BundleItem(material, amount)
        }
        if (items.isEmpty()) {
            skip(id, "items 至少需要一项材料")
            return null
        }

        val name = section.getString("name")?.takeIf { it.isNotBlank() }

        var icon: Material? = null
        val rawIcon = section.getString("icon")
        if (!rawIcon.isNullOrBlank()) {
            val parsed = PluginConfig.parseMaterial(rawIcon)
            if (parsed == null || !parsed.isItem || parsed.isAir) {
                warning("[BuildFlight] bundles.$id.icon 无效: $rawIcon，已回退到第一个需求材料")
            } else {
                icon = parsed
            }
        }

        return BundleEntry(id, name, icon, points, items.toList())
    }

    private fun skip(id: String, reason: String) {
        warning("[BuildFlight] bundles.$id 配置无效（$reason），已跳过该套餐")
    }
}
