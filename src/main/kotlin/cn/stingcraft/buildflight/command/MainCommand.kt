package cn.stingcraft.buildflight.command

import cn.stingcraft.buildflight.BuildFlightPlugin
import cn.stingcraft.buildflight.config.PluginMessages
import cn.stingcraft.buildflight.ui.ExchangeMenu
import cn.stingcraft.buildflight.util.Scheduler
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import taboolib.common.platform.command.CommandBody
import taboolib.common.platform.command.CommandHeader
import taboolib.common.platform.command.PermissionDefault
import taboolib.common.platform.command.mainCommand
import taboolib.common.platform.function.warning

/**
 * /bflight 与 /bf。
 *
 * 根命令只负责打开 GUI；飞行开关在 GUI 中完成，避免误触。
 */
@CommandHeader(
    "bflight",
    aliases = ["bf"],
    permission = "buildflight.use",
    permissionDefault = PermissionDefault.TRUE
)
object MainCommand {

    fun ensureLoaded() {
        // 触发对象初始化，完成 TabooLib 命令注册
    }

    @CommandBody
    val main = mainCommand {
        execute<CommandSender> { sender, _, _ ->
            if (sender !is Player) {
                PluginMessages.send(sender, "player-only")
                return@execute
            }
            // Folia 上命令线程不保证就是玩家区域线程，统一投递后再打开 GUI
            Scheduler.runOnEntity(sender) {
                ExchangeMenu.open(sender)
            }
        }

        literal("reload", permission = "buildflight.admin") {
            execute<CommandSender> { sender, _, _ ->
                runCatching { BuildFlightPlugin.reload() }
                    .onSuccess {
                        PluginMessages.send(sender, "reload-success")
                    }
                    .onFailure { error ->
                        warning("[BuildFlight] 重载失败: $error")
                        PluginMessages.send(
                            sender,
                            "reload-failed",
                            "error" to (error.message ?: error.javaClass.simpleName)
                        )
                    }
            }
        }

        literal("help") {
            execute<CommandSender> { sender, _, _ ->
                PluginMessages.sendList(sender, "help")
            }
        }
    }
}
