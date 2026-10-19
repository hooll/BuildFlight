package cn.stingcraft.buildflight.data

import cn.stingcraft.buildflight.util.TaskHandle
import org.bukkit.boss.BossBar
import java.util.UUID

/**
 * 单个玩家的运行期档案。
 *
 * 能量与飞行标记使用 @Volatile：写入发生在玩家所属区域线程，
 * 自动保存线程只读取快照，不会直接修改这些字段。
 * 其余运行期状态（任务句柄、BossBar）都只在玩家线程上访问。
 */
class PlayerProfile(val uuid: UUID) {

    @Volatile
    var energy: Double = 0.0

    @Volatile
    var loaded: Boolean = false

    @Volatile
    var loadFailed: Boolean = false

    @Volatile
    var flying: Boolean = false

    /** 战斗冷却结束时间（epoch millis） */
    @Volatile
    var combatUntil: Long = 0L

    /** 缓降/摔落免伤保护是否生效 */
    @Volatile
    var fallGuard: Boolean = false

    /** 摔落保护硬性截止时间（epoch millis），0 表示未启用 */
    @Volatile
    var fallGuardExpireAt: Long = 0L

    /** 连续检测到落地的次数，用于延迟清除保护 */
    @Volatile
    var groundChecks: Int = 0

    @Volatile
    var bossBar: BossBar? = null

    /** 每秒扣费任务 */
    @Volatile
    var task: TaskHandle? = null

    /** 落地检测任务 */
    @Volatile
    var guardTask: TaskHandle? = null

    /** 硬性超时任务：无论是否落地，到点必须解除免伤 */
    @Volatile
    var guardTimeoutTask: TaskHandle? = null

    /** 兑换操作的串行锁，防止 GUI 连点造成重复结算 */
    @Volatile
    var exchanging: Boolean = false

    /** 最近一次成功写入数据库的载荷，用于跳过无变化的自动保存 */
    @Volatile
    var lastSavedPayload: String = ""
}
