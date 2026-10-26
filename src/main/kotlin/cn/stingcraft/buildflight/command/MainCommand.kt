package cn.stingcraft.buildflight.command

import cn.stingcraft.buildflight.BuildFlightPlugin
import cn.stingcraft.buildflight.config.PluginMessages
import cn.stingcraft.buildflight.manager.EnergyManager
import cn.stingcraft.buildflight.ui.ExchangeMenu
import cn.stingcraft.buildflight.util.Scheduler
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import taboolib.common.platform.command.CommandBody
import taboolib.common.platform.command.CommandHeader
import taboolib.common.platform.command.PermissionDefault
import taboolib.common.platform.command.mainCommand
import taboolib.common.platform.command.subCommand
import taboolib.common.platform.function.warning

/**
 * /bflight 与 /bf。
 *
 * 根命令只负责打开 GUI；飞行开关在 GUI 中完成，避免误触。
 * `give` 供管理员与其他插件通过控制台 dispatch 发放能量。
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

    /**
     * /bf give <玩家> <点数>（别名 /bflight give）。
     *
     * 复用 buildflight.admin；控制台默认放行，方便其他插件用
     * `Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "bf give ...")` 接入。
     * 离线目标只认服务端缓存里曾进服过的玩家，不发网络查询、不阻塞区域线程。
     */
    @CommandBody(permission = "buildflight.admin")
    val give = subCommand {
        execute<CommandSender> { sender, _, _ ->
            PluginMessages.sendList(sender, "help")
        }
        dynamic("player") {
            // uncheck：补全只提示在线玩家，但必须允许任意名字（离线玩家也要能发放）
            suggestion<CommandSender>(uncheck = true) { _, _ ->
                Bukkit.getOnlinePlayers().map { it.name }
            }
            dynamic("points") {
                execute<CommandSender> { sender, context, _ ->
                    givePoints(sender, context["player"], context["points"])
                }
            }
        }
    }

    private fun givePoints(sender: CommandSender, rawName: String, rawPoints: String) {
        val amount = rawPoints.trim().toDoubleOrNull()
        if (amount == null || !amount.isFinite() || amount <= 0.0) {
            PluginMessages.send(sender, "give-invalid-amount", "input" to rawPoints)
            return
        }

        val name = rawName.trim()
        if (name.isEmpty()) {
            PluginMessages.send(sender, "give-player-unknown", "player" to name)
            return
        }
        // 在线匹配在命令线程完成；离线名字解析会读 playerdata 目录，
        // 由 EnergyManager 投递到具名 IO 线程，命令线程不做文件 IO。
        EnergyManager.giveByTargetName(name, amount, sender)
    }
}
