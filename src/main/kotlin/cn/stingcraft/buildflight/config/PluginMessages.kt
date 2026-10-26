package cn.stingcraft.buildflight.config

import org.bukkit.command.CommandSender
import taboolib.module.chat.colored
import taboolib.module.configuration.Config
import taboolib.module.configuration.Configuration

/**
 * messages.yml 的读取入口。
 *
 * 支持 `&` 传统颜色码以及 `&{#RRGGBB}` 十六进制颜色（由 TabooLib 的 colored 处理）。
 * 占位符同时支持 `{key}` 与 `%key%` 两种写法。
 */
object PluginMessages {

    @Config("messages.yml", autoReload = true)
    lateinit var config: Configuration
        private set

    /**
     * 新增消息键的代码级默认值。
     *
     * 旧安装的 messages.yml 不会自动补键；缺少这些键时使用这里的默认文案，
     * 不修改服主已有的消息文件。
     */
    private val defaultText: Map<String, String> = mapOf(
        "gui-page-prev-name" to "&e← 上一页",
        "gui-page-next-name" to "&e下一页 →",
        "gui-page-disabled-name" to "&8翻页不可用",
        "gui-page-indicator-name" to "&e第 &f{page}&7/&f{pages} &e页",
        "dominion-required" to "&c只能在 Dominion 领地内开启建筑飞行。",
        "dominion-not-member" to "&c你必须是该领地的拥有者或成员才能在此飞行。",
        "dominion-no-privilege" to "&c你在当前领地没有飞行权限。",
        "dominion-flag-disabled" to "&c服务器未启用 Dominion 的 fly 权限标志，无法在此飞行。",
        "dominion-left" to "&c你已离开领地，飞行已暂停；回到领地后可继续。",
        "dominion-returned" to "&a已回到领地，飞行已恢复；双击空格可继续飞行。",
        "dominion-missing" to "&c服务器未安装或未启用 Dominion，建筑飞行暂不可用。",
        "give-success" to "&a已给予 &f{player} &e{points} &a点能量（当前 &e{energy}&a）。",
        "give-received" to "&a你获得了 &e{points} &a点建筑飞行能量（当前 &e{energy}&a）。",
        "give-player-unknown" to "&c找不到玩家 &f{player} &c（从未在本服出现过）。",
        "give-lookup-failed" to "&c解析玩家 &f{player} &c失败，未发放。",
        "give-invalid-amount" to "&c能量点数必须是大于 0 的数字: &f{input}",
        "give-data-failed" to "&c目标玩家 &f{player} &c的能量数据不可用，未发放。",
        "give-save-failed" to "&c向 &f{player} &c发放 &e{points} &c点能量失败，未写入数据库。",
        "gui-bundle-name" to "&e{bundle} &7套餐",
        "gui-bundle-requirement-ok" to "&7- &f{material} &7×&f{need} &8(拥有 &a{have}&8)",
        "gui-bundle-requirement-lack" to "&7- &f{material} &7×&f{need} &8(拥有 &c{have}&8)",
        "gui-bundle-compare" to "&7单件合计: &f{base} &7点",
        "gui-bundle-bonus" to "&7套餐加成: &a+{bonus} &7点 &8(+{percent}%)",
        "gui-bundle-penalty" to "&7比单件少: &c-{diff} &7点",
        "bundle-success" to "&a已兑换 &f{portions} &a份 &f{bundle}&a，获得 &e{points} &a点能量（当前 &e{energy}&a）。",
        "bundle-not-enough" to "&c材料不足，无法兑换 &f{bundle}&c。&7缺少: &f{missing}",
        "bundle-failed" to "&c兑换失败，物品已退回背包，请重试。"
    )

    private val defaultList: Map<String, List<String>> = mapOf(
        "gui-page-prev-lore" to listOf("&7点击查看上一页材料"),
        "gui-page-next-lore" to listOf("&7点击查看下一页材料"),
        "gui-page-disabled-lore" to listOf("&7已经到达可翻页范围的边界"),
        "gui-page-indicator-lore" to listOf(
            "&7共 &f{total} &7种可兑换材料",
            "&7本页显示 &f{shown} &7种"
        ),
        // 面板同时包含套餐与材料后改用新键，旧安装缺少该键时不会继续显示“共 N 种材料”
        "gui-offer-indicator-lore" to listOf(
            "&7共 &f{total} &7项可兑换内容",
            "&7本页显示 &f{shown} &7项"
        ),
        "gui-bundle-lore" to listOf(
            "&7套餐奖励: &a{points} &7点"
        ),
        "gui-bundle-lore-footer" to listOf(
            "",
            "&f左键 &7兑换 1 份",
            "&fShift+左键 &7兑换全部可凑齐份数",
            "&f右键 &7兑换 1 份"
        )
    )

    fun load() {
        // 仅触发 @Config 注入，无需额外注册。
    }

    fun reload() {
        config.reload()
    }

    fun text(key: String, vararg placeholders: Pair<String, Any?>): String {
        val raw = config.getString(key) ?: defaultText[key] ?: return ""
        return applyPlaceholders(raw, placeholders).colored()
    }

    fun textList(key: String, vararg placeholders: Pair<String, Any?>): List<String> {
        val raw = config.getStringList(key).ifEmpty { defaultList[key] ?: emptyList() }
        return raw.map { applyPlaceholders(it, placeholders).colored() }
    }

    fun send(sender: CommandSender, key: String, vararg placeholders: Pair<String, Any?>) {
        val body = text(key, *placeholders)
        if (body.isEmpty()) return
        sender.sendMessage(text("prefix") + body)
    }

    fun sendNoPrefix(sender: CommandSender, key: String, vararg placeholders: Pair<String, Any?>) {
        val body = text(key, *placeholders)
        if (body.isNotEmpty()) sender.sendMessage(body)
    }

    fun sendList(sender: CommandSender, key: String, vararg placeholders: Pair<String, Any?>) {
        textList(key, *placeholders).forEach { sender.sendMessage(it) }
    }

    private fun applyPlaceholders(raw: String, placeholders: Array<out Pair<String, Any?>>): String {
        var out = raw
        for ((key, value) in placeholders) {
            val replacement = value?.toString() ?: ""
            out = out.replace("{$key}", replacement).replace("%$key%", replacement)
        }
        return out
    }
}
