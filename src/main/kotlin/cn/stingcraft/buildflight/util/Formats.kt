package cn.stingcraft.buildflight.util

import org.bukkit.Material
import org.bukkit.entity.Player
import taboolib.module.nms.MinecraftLanguage
import java.util.Locale

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
 * 材料显示名：按玩家客户端语言查 Minecraft 官方语言文件，取不到时回退 [prettyMaterialName]。
 *
 * 为什么不用 TabooLib 的 `getI18nName`：
 *  1. 它内部经 `CraftItemStack.asCraftCopy` 取翻译键，在 Paper/Lophine 26.3 上该方法签名
 *     已变化，运行期抛 `NoSuchMethodError`，被调用方捕获后只会静默回退成英文；
 *  2. 它内部的缓存只以材料名为键、不区分 locale，先被英文客户端调用一次就会把英文
 *     结果固定给之后所有玩家。
 * 因此这里直接使用 `Material.translationKey` 查 `MinecraftLanguage` 的语言表：不经过
 * NMS、不写全局缓存，并按玩家 locale 取词。
 *
 * 只在玩家实体线程调用；纯查询，不修改任何 Bukkit 状态。
 */
fun localizedMaterialName(player: Player, material: Material): String {
    val fallback = prettyMaterialName(material.name)
    return runCatching {
        val key = material.translationKey
        // en_us 由 MinecraftLanguage 内部映射到 en_gb；未知 locale 回退到默认语言表（zh_cn）。
        val file = MinecraftLanguage.getLanguageFile(player.locale) ?: MinecraftLanguage.getDefaultLanguageFile()
        val name = file?.get(key)
        if (name.isNullOrBlank() || name == key) fallback else name
    }.getOrDefault(fallback)
}
