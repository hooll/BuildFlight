package cn.stingcraft.buildflight.config

import org.bukkit.Material
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import taboolib.common.platform.function.warning
import taboolib.module.configuration.Config
import taboolib.module.configuration.Configuration
import java.util.Locale

/**
 * 一个可兑换材料配置项。
 *
 * @param material 实际材料枚举
 * @param points   每件材料兑换的能量点数
 */
data class ExchangeEntry(
    val material: Material,
    val points: Double
)

/**
 * config.yml 的读取入口。
 *
 * 所有 getter 都带默认值，配置文件缺项时插件仍可启动。
 * autoReload = true 时 TabooLib 会自动重载文件，这里额外注册 onReload
 * 以便刷新兑换表缓存。
 */
object PluginConfig {

    @Config("config.yml", autoReload = true)
    lateinit var config: Configuration
        private set

    @Volatile
    private var exchangeEntries: List<ExchangeEntry> = emptyList()

    @Volatile
    private var exchangePoints: Map<Material, Double> = emptyMap()

    @Volatile
    private var reloadHookInstalled = false

    @Volatile
    private var warnedBossBarColor = false

    @Volatile
    private var warnedBossBarStyle = false

    @Volatile
    private var disabledWorlds: Set<String> = emptySet()

    /** 插件启用时调用一次；不要覆盖用户已有的 config.yml。 */
    fun load() {
        if (!reloadHookInstalled) {
            reloadHookInstalled = true
            config.onReload { reloadExchangeTable() }
        }
        reloadExchangeTable()
    }

    /** /bflight reload 调用。 */
    fun reload() {
        config.reload()
        warnedBossBarColor = false
        warnedBossBarStyle = false
        reloadExchangeTable()
    }

    private fun reloadExchangeTable() {
        val entries = mutableListOf<ExchangeEntry>()
        val points = linkedMapOf<Material, Double>()
        val section = config.getConfigurationSection("exchange-materials")
        if (section != null) {
            for (key in section.getKeys(false)) {
                val materialName: String
                val value: Double
                if (section.isConfigurationSection(key)) {
                    val sub = section.getConfigurationSection(key) ?: continue
                    materialName = sub.getString("material", key) ?: key
                    value = if (sub.contains("points")) {
                        sub.getDouble("points", 0.0)
                    } else {
                        sub.getDouble("energy", 0.0)
                    }
                } else {
                    materialName = key
                    value = section.getDouble(key, 0.0)
                }

                val material = parseMaterial(materialName)
                if (material == null || !material.isItem || material.isAir) {
                    warning("[BuildFlight] exchange-materials 中存在无效材料: $materialName")
                    continue
                }
                if (!value.isFinite() || value <= 0.0) {
                    warning("[BuildFlight] exchange-materials.$materialName 的能量值必须大于 0")
                    continue
                }
                if (points.containsKey(material)) {
                    warning("[BuildFlight] exchange-materials 中重复配置了 $material，已忽略后一项")
                    continue
                }
                points[material] = value
                entries += ExchangeEntry(material, value)
            }
        } else {
            warning("[BuildFlight] config.yml 缺少 exchange-materials 节点，兑换功能将不可用")
        }
        exchangeEntries = entries.toList()
        exchangePoints = points.toMap()
        disabledWorlds = config.getStringList("disabled-worlds")
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    fun entries(): List<ExchangeEntry> = exchangeEntries

    fun materialPoints(): Map<Material, Double> = exchangePoints

    fun pointsOf(material: Material): Double? = exchangePoints[material]

    val bossBarColor: BarColor
        get() {
            val raw = config.getString("bossbar.color", "YELLOW") ?: "YELLOW"
            return runCatching { BarColor.valueOf(raw.trim().uppercase(Locale.ROOT)) }
                .getOrElse {
                    if (!warnedBossBarColor) {
                        warnedBossBarColor = true
                        warning("[BuildFlight] 无效的 bossbar.color: $raw，回退到 YELLOW")
                    }
                    BarColor.YELLOW
                }
        }

    val bossBarStyle: BarStyle
        get() {
            val raw = config.getString("bossbar.style", "SOLID") ?: "SOLID"
            return runCatching { BarStyle.valueOf(raw.trim().uppercase(Locale.ROOT)) }
                .getOrElse {
                    if (!warnedBossBarStyle) {
                        warnedBossBarStyle = true
                        warning("[BuildFlight] 无效的 bossbar.style: $raw，回退到 SOLID")
                    }
                    BarStyle.SOLID
                }
        }

    /** 血条 100% 对应的能量点数，只影响显示比例。 */
    val maxEnergyReference: Double
        get() = config.getDouble("bossbar.max-energy-reference", 3600.0).coerceAtLeast(1.0)

    val combatCancelOnDamage: Boolean
        get() = config.getBoolean("combat.cancel-on-damage", true)

    val combatCooldownSeconds: Int
        get() = config.getInt("combat.cooldown-seconds", 10).coerceAtLeast(0)

    val fallProtectionSeconds: Int
        get() = config.getInt("fall-protection-seconds", 10).coerceAtLeast(0)

    val consumePerSecond: Double
        get() = config.getDouble("energy.consume-per-second", 1.0).coerceAtLeast(0.0)

    val autoSaveSeconds: Int
        get() = config.getInt("storage.auto-save-seconds", 30).coerceAtLeast(5)

    val databaseFile: String
        get() = config.getString("storage.database-file", "data.db")?.ifBlank { "data.db" } ?: "data.db"

    fun isWorldAllowed(worldName: String): Boolean =
        worldName.lowercase(Locale.ROOT) !in disabledWorlds

    /**
     * 兼容原版枚举名、带命名空间的写法（minecraft:diamond）以及连字符。
     */
    fun parseMaterial(raw: String): Material? {
        val cleaned = raw.trim()
            .substringAfter(':', raw.trim())
            .uppercase(Locale.ROOT)
            .replace(' ', '_')
            .replace('-', '_')
        if (cleaned.isEmpty()) return null
        return runCatching { Material.matchMaterial(cleaned) }.getOrNull()
    }
}
