package cn.stingcraft.buildflight.data

import cn.stingcraft.buildflight.util.TaskHandle
import org.bukkit.boss.BossBar
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

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

    /**
     * 是否因离开 Dominion 领地而暂停飞行。
     *
     * 暂停不等于关闭模式：`flying` 保持 true、扣费任务与 BossBar 保留，
     * 只把 `allowFlight` 收回；回到领地后清除本标记并恢复 `allowFlight`。
     * 仅运行期状态，不持久化。
     */
    @Volatile
    var dominionSuspended: Boolean = false

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

    /**
     * 档案对应的在线玩家；仅在线期间非空。
     *
     * IO 线程需要判断“这条离线写库是否应该改成在线发放”时读取它，
     * 避免在 IO 线程上遍历在线玩家表。
     */
    @Volatile
    var player: Player? = null

    /**
     * 档案已加载/加载中，但发放请求需要暂存时使用的队列。
     *
     * 只在玩家区域线程入队与 drain；加载完成回调也投递回该线程后再 drain。
     * 使用并发队列是为了让“入队在实体线程、排空在实体线程”的交接不需要额外加锁。
     */
    val pendingGives: ConcurrentLinkedQueue<PendingGive> = ConcurrentLinkedQueue()
}

/**
 * 一条待发放的能量请求。
 *
 * 档案尚未加载完成时先入队，加载完成后再应用到内存；
 * 若档案在加载完成前被卸载，则由 IO 任务写入数据库。
 *
 * @param amount     发放点数（已校验为有限正数）
 * @param sender     发起发放的命令发送者，用于回执
 * @param senderName 发送者名字，用于控制台日志
 */
class PendingGive(
    val amount: Double,
    val sender: CommandSender,
    val senderName: String
)
