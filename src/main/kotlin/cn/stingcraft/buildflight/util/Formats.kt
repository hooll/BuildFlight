package cn.stingcraft.buildflight.util

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
