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

    fun load() {
        // 仅触发 @Config 注入，无需额外注册。
    }

    fun reload() {
        config.reload()
    }

    fun text(key: String, vararg placeholders: Pair<String, Any?>): String {
        val raw = config.getString(key) ?: return ""
        return applyPlaceholders(raw, placeholders).colored()
    }

    fun textList(key: String, vararg placeholders: Pair<String, Any?>): List<String> =
        config.getStringList(key).map { applyPlaceholders(it, placeholders).colored() }

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
