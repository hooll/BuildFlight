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
import java.util.UUID

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

    /** 目标玩家解析结果：在线玩家直接投递到其区域线程，离线玩家走数据库写库。 */
    private sealed interface GiveTarget {
        class Online(val player: Player) : GiveTarget
        class Offline(val uuid: UUID, val name: String) : GiveTarget
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
        val target = resolveTarget(name)
        if (target == null) {
            PluginMessages.send(sender, "give-player-unknown", "player" to name)
            return
        }

        when (target) {
            is GiveTarget.Online -> EnergyManager.giveOnline(target.player, amount, sender)
            is GiveTarget.Offline -> EnergyManager.giveOffline(target.uuid, target.name, amount, sender)
        }
    }

    /**
     * 先精确匹配在线玩家，再忽略大小写匹配在线玩家；
     * 都不在则只在“曾进服过”的离线玩家缓存里找，找不到返回 null。
     */
    private fun resolveTarget(name: String): GiveTarget? {
        if (name.isEmpty()) return null
        Bukkit.getPlayerExact(name)?.let { return GiveTarget.Online(it) }
        Bukkit.getOnlinePlayers()
            .firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?.let { return GiveTarget.Online(it) }

        val offline = Bukkit.getOfflinePlayers().firstOrNull { candidate ->
            candidate.hasPlayedBefore() &&
                candidate.name?.equals(name, ignoreCase = true) == true
        } ?: return null
        return GiveTarget.Offline(offline.uniqueId, offline.name ?: name)
    }
}
