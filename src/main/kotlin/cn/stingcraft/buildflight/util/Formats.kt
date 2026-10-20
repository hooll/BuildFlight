package cn.stingcraft.buildflight.util

import org.bukkit.Material
import org.bukkit.entity.Player
import taboolib.module.nms.getI18nName
import java.util.Locale

private const val NO_LOCALE = "NO_LOCALE"

/** 能量显示：整数不带小数，小数保留一位。 */
fun formatEnergy(value: Double): String {
    if (!value.isFinite()) return "0"
    return if (value % 1.0 == 0.0) {
        value.toLong().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
}

/** 剩余秒数（向上取整），用于战斗冷却提示。 */
fun remainingSeconds(untilMillis: Long, now: Long = System.currentTimeMillis()): Long {
    val diff = untilMillis - now
    return if (diff <= 0L) 0L else (diff + 999L) / 1000L
}

/** 将 Material 名字转成易读形式，仅用于 GUI 展示。 */
fun prettyMaterialName(name: String): String =
    name.lowercase(Locale.ROOT)
        .split('_')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase(Locale.ROOT) } }

/**
 * 材料显示名：优先取玩家客户端语言的官方译名，取不到时回退 [prettyMaterialName]。
 *
 * 只在玩家实体线程调用；纯查询，不修改任何 Bukkit 状态。
 */
fun localizedMaterialName(player: Player, material: Material): String {
    val fallback = prettyMaterialName(material.name)
    val localized = runCatching { material.getI18nName(player) }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: return fallback
    if (localized == NO_LOCALE) return fallback
    val isRawKey = runCatching {
        localized == material.translationKey || localized == material.itemTranslationKey
    }.getOrDefault(false)
    return if (isRawKey) fallback else localized
}
