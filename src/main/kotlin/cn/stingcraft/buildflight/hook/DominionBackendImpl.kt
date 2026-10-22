package cn.stingcraft.buildflight.hook

import cn.lunadeer.dominion.api.DominionAPI
import cn.lunadeer.dominion.api.dtos.flag.Flags
import cn.stingcraft.buildflight.config.DominionMode
import cn.stingcraft.buildflight.config.PluginConfig
import org.bukkit.Location
import org.bukkit.entity.Player
import taboolib.common.platform.function.warning

/**
 * Dominion 判定后端的真实实现。
 *
 * 这是全项目唯一直接引用 `cn.lunadeer.dominion.*` 的文件，只能由 [DominionHook]
 * 在确认 Dominion 已加载后通过 `Class.forName` 实例化；不要在其他类中 import 本类。
 *
 * 线程约定：`getDominion` 走 Dominion 的内存缓存，不查数据库；只能在玩家实体/区域线程调用。
 */
internal class DominionBackendImpl : DominionBackend {

    private val api: DominionAPI = DominionAPI.getInstance() ?: error("DominionAPI 尚未初始化")
    private val flyFlag = Flags.FLY

    init {
        // FLY 标志未启用时 checkPrivilegeFlagSilence 会无条件返回 true，
        // 必须在构造阶段提示服主，否则 FLAG 模式会把“标志未启用”误判为“人人有权限”。
        if (PluginConfig.dominionMode == DominionMode.FLAG && flyFlag.enable != true) {
            warning(
                "[BuildFlight] dominion.mode=flag 但 Dominion 未启用 fly 标志，" +
                    "请在 Dominion 的 flags.yml 中设置 privilege.fly.enable: true"
            )
        }
    }

    override fun check(player: Player, location: Location): DominionResult {
        val dominion = api.getDominion(location) ?: return DominionResult.NOT_IN_CLAIM
        return when (PluginConfig.dominionMode) {
            // 拥有者不在成员表里，必须单独比较 owner。
            DominionMode.MEMBER ->
                if (dominion.owner == player.uniqueId || api.getMember(dominion, player) != null) {
                    DominionResult.ALLOWED
                } else {
                    DominionResult.NOT_MEMBER
                }

            DominionMode.ANY -> DominionResult.ALLOWED

            DominionMode.FLAG -> {
                if (flyFlag.enable != true) return DominionResult.FLAG_DISABLED
                if (api.checkPrivilegeFlagSilence(dominion, flyFlag, player)) {
                    DominionResult.ALLOWED
                } else {
                    DominionResult.NO_PRIVILEGE
                }
            }
        }
    }
}
