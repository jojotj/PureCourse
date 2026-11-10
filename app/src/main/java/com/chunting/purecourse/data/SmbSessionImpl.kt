package com.chunting.purecourse.data

import android.os.SystemClock
import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * SMB 单例会话（smbj 0.13.0）—— P1/P3 的真实实现。
 *
 * 设计纪律（文档 §5.5 / docs/07）：
 * 1. **全程只有一条 Session**：浏览目录与播放共用一个 `Connection` + `Session` + `DiskShare`；
 * 2. 切集只换文件句柄，不重连；引用归零后由 [releaseIfIdle] 决定是否断开；
 * 3. 失败退避（1s/2s/4s，最多 3 次），不狂重连；
 * 4. 所有网络调用都不在主线程做（调用方负责切线程）。
 */
object SmbSession {

    private const val TAG = "SmbSession"

    /** 本机当前打开的 Session 数（正常恒为 1；诊断层显示这个值） */
    var localSessions by mutableStateOf(0)
        private set
    var lastReleaseText by mutableStateOf("—")
        private set

    /** 播放页退出后多久真正断开：0 = 立即，30 = 空闲 30 秒（默认） */
    var idleReleaseSec by mutableStateOf(30)

    /** 最近一次错误（给失败层做分类用） */
    var lastError by mutableStateOf<String?>(null)
        private set

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    private var host: String = ""
    private var shareName: String = ""
    private var user: String = ""
    private var pass: String = ""
    private var port: Int = 445

    private var playerLease = false

    /**
     * SMB 句柄的串行锁。
     *
     * smbj 的 `DiskShare` **不是线程安全的**，而本工程同时有三类线程在用它：
     * 扫描（`RealCatalog.scan`）、浏览页按需补节点（`ensureNode`）、播放器的 Loader。
     * 整机模式下还会 `useShare` 切共享 —— 并发时 A 线程可能正在切共享，
     * B 线程拿着旧 `share` 去 list，结果就是"有时有内容、有时空目录"（实测踩到过）。
     * 这里把"切共享 + 一次具体 IO"整段串行化，代价可忽略（都是毫秒级）。
     */
    private val ioLock = Any()

    /** 透明重连的退避状态（1s / 2s 递增，连续失败 3 次即停，禁止狂重连） */
    private var lastReconnectMs = 0L
    private var reconnectFails = 0
    var reconnectText by mutableStateOf("—")
        private set

    /**
     * 会话活着才算已连接。
     *
     * 必须看底层 transport：smbj 的空闲 socket 会被对端/系统断掉，
     * 此时 `session` 对象还在，但下一次 tree connect 会抛
     * `Cannot write ... as transport is disconnected`。
     * 只看 session != null 会让上层以为"还连着"，然后一路失败。
     */
    val isConnected: Boolean get() = session?.connection?.isConnected == true
    val connectedShare: DiskShare? get() = share

    /** 当前会话连的是哪台主机（-1 个字符也算，多源播放时用来判断是否需要换机重连） */
    fun hostName(): String = host

    /**
     * 建立（或复用）会话。返回 null = 成功，否则返回错误文案。
     *
     * `shareName` 允许为空 —— 这是「整机模式」：只建 SMB 会话，不预先把某个共享挂上来，
     * 之后由 [useShare] 按路径第一段切换。这样一台主机只有一个数据源，
     * 多个共享能同时出现在同一个浏览树里（不用在编辑页里二选一）。
     */
    fun connect(host: String, shareName: String, user: String, pass: String, port: Int = 445): String? =
        synchronized(ioLock) { connectLocked(host, shareName, user, pass, port) }

    private fun connectLocked(host: String, shareName: String, user: String, pass: String, port: Int): String? {
        // 同机同端口：换共享不该重连，只切 DiskShare
        if (isConnected && this.host == host && this.port == port) {
            if (shareName.isEmpty()) { this.user = user; this.pass = pass; return null }
            if (this.shareName == shareName && share?.isConnected == true) return null
            this.user = user
            this.pass = pass
            return useShare(shareName)
        }
        closeQuietly()

        this.host = host
        this.shareName = shareName
        this.user = user
        this.pass = pass
        this.port = port

        return try {
            val cfg = SmbConfig.builder()
                .withTimeout(10, TimeUnit.SECONDS)
                // soTimeout 是 socket 读超时：20 秒时，只要在浏览页停一会儿没操作，
                // 下一次 list/tree connect 就会抛 Timeout expired，然后被判成断线。
                // 浏览是"人操作节奏"，必须放宽到分钟级。
                .withSoTimeout(120, TimeUnit.SECONDS)
                .build()
            val c = SMBClient(cfg)
            client = c
            // SMB2/3：smbj 默认最小 SMB2_0_2，不协商 SMB1（文档 §1.5 的坑）
            // 端口必须真的传进去：之前写死默认 445，表单里改端口等于没改
            val conn = c.connect(host, port)
            connection = conn
            val ac = if (user.isBlank()) AuthenticationContext.anonymous()
            else AuthenticationContext(user, pass.toCharArray(), "")
            val s = conn.authenticate(ac)
            session = s
            localSessions = 1
            lastError = null
            if (shareName.isEmpty()) {
                share = null
                Log.i(TAG, "connected $host (whole-host mode) as $user (sessions=1)")
                return null
            }
            val sh = s.connectShare(shareName) as? DiskShare
                ?: return "共享「$shareName」不是可浏览的磁盘共享".also { lastError = it }
            share = sh
            Log.i(TAG, "connected $host/$shareName as $user (sessions=1)")
            null
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message ?: ""}"
            Log.w(TAG, "connect failed: $lastError")
            closeQuietly()
            classify(t)
        }
    }

    /**
     * 切到另一个共享（同一台主机、同一个 SMB 会话内）。
     *
     * 整机模式必须靠它：`DiskShare` 是按共享打开的，浏览树里的每个共享各是一个
     * `DiskShare`。切共享只 close 旧句柄、**不重连会话**，所以是毫秒级。
     * 返回 null = 成功，否则是错误文案。
     */
    fun useShare(name: String): String? = synchronized(ioLock) { useShareLocked(name) }

    private fun useShareLocked(name: String): String? {
        if (name.isBlank()) {
            runCatching { share?.close() }
            share = null
            shareName = ""
            return null
        }
        if (shareName == name && share?.isConnected == true) return null
        val s = session ?: return "SMB 会话还没建立"
        runCatching { share?.close() }
        share = null
        return try {
            val sh = s.connectShare(name) as? DiskShare
                ?: return "共享「$name」不是可浏览的磁盘共享".also { lastError = it }
            share = sh
            shareName = name
            lastError = null
            Log.i(TAG, "useShare=$name")
            null
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message ?: ""}"
            Log.w(TAG, "useShare($name) failed: $lastError")
            classify(t)
        }
    }

    /** 把底层异常翻成"人话"，供失败层/诊断层判断是否属于"会话满" */
    private fun classify(t: Throwable): String {
        val msg = (t.message ?: "") + " " + t.javaClass.simpleName
        return when {
            msg.contains("STATUS_LOGON_FAILURE", true) ||
                msg.contains("SmbAuthException", true) -> "auth"
            msg.contains("STATUS_REQUEST_NOT_ACCEPTED", true) ||
                msg.contains("STATUS_NETWORK_SESSION_EXPIRED", true) ||
                msg.contains("0xC00000C9", true) ||
                msg.contains("STATUS_INSUFF_SERVER_RESOURCES", true) -> "full"
            msg.contains("ConnectException", true) ||
                msg.contains("NoRouteToHost", true) ||
                msg.contains("SocketTimeout", true) ||
                msg.contains("UnknownHost", true) -> "network"
            msg.contains("STATUS_OBJECT_NAME_NOT_FOUND", true) ||
                msg.contains("STATUS_BAD_NETWORK_NAME", true) -> "path"
            else -> lastError ?: "unknown"
        }
    }

    /** 列当前共享的目录（路径用相对共享根的 \ 分隔形式，"" 表示根） */
    fun list(path: String = ""): List<SmbEntry> = synchronized(ioLock) {
        var lastErr: Throwable? = null
        for (attempt in 0..1) {
            try {
                if (attempt > 0) {
                    closeQuietlyInner()
                    reconnectLocked()?.let { throw IllegalStateException(it) }
                } else if (!isConnected && !ensureConnected()) {
                    throw IllegalStateException("SMB 重连失败：${lastError ?: "会话已断开"}")
                }
                return@synchronized listLocked(path)
            } catch (t: Throwable) {
                lastErr = t
                Log.w(TAG, "list 第 ${attempt + 1} 次失败：${t.message}")
            }
        }
        throw (lastErr ?: IllegalStateException("读取失败"))
    }

    /**
     * 切到指定共享并**在同一次加锁内**列目录。
     *
     * 整机模式下 scan / 浏览 / 播放三条线都可能切共享，"先 useShare 再 list" 两步之间
     * 被别的线程插进来切走，就会读到另一个共享的目录 —— 表现为"偶尔空目录"。
     */
    fun listIn(shareName: String, path: String): List<SmbEntry> = synchronized(ioLock) {
        var lastErr: Throwable? = null
        for (attempt in 0..1) {
            try {
                if (attempt > 0) {
                    // 上一次失败 = 底层已经不可用，直接重建会话再试
                    closeQuietlyInner()
                    reconnectLocked()?.let { throw IllegalStateException(it) }
                } else if (!isConnected && !ensureConnected()) {
                    throw IllegalStateException("SMB 重连失败：${lastError ?: "会话已断开"}")
                }
                if (shareName.isNotBlank()) {
                    val e = useShareLocked(shareName)
                    if (e != null) throw IllegalStateException("切换共享失败：$e")
                }
                return@synchronized listLocked(path)
            } catch (t: Throwable) {
                lastErr = t
                Log.w(TAG, "listIn($shareName) 第 ${attempt + 1} 次失败：${t.message}")
            }
        }
        throw (lastErr ?: IllegalStateException("读取失败"))
    }

    /** 用上次成功的配置重建会话（host/port/user/pass 都是字段里留着的） */
    private fun reconnectLocked(): String? {
        if (host.isBlank()) return "没有可用的主机配置"
        return connectLocked(host, "", user, pass, port)
    }

    private fun listLocked(path: String): List<SmbEntry> {
        val sh = share ?: throw IllegalStateException("not connected")
        val out = ArrayList<SmbEntry>()
        sh.list(path).forEach { info ->
            val name = info.fileName ?: return@forEach
            if (name == "." || name == "..") return@forEach
            // FILE_ATTRIBUTE_DIRECTORY = 0x10；smbj 的目录项没有 isDirectory()，按属性位判断
            val dir = (info.fileAttributes and 0x10L) != 0L
            out += SmbEntry(
                name = name,
                isDir = dir,
                size = if (dir) 0 else info.endOfFile,
                modifiedMs = runCatching { info.lastWriteTime.toEpochMillis() }.getOrDefault(0L),
                path = if (path.isEmpty()) name else "$path\\$name",
            )
        }
        // 目录在前，然后按名字
        return out.sortedWith(compareByDescending<SmbEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    /** 只取大小：打开 → 读 FileAllInformation → 立刻关闭，不长期占用句柄 */
    fun fileSize(path: String): Long = synchronized(ioLock) {
        val sh = share ?: throw IllegalStateException("not connected")
        val f = sh.openFile(
            path, EnumSet.of(AccessMask.GENERIC_READ), null,
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
        )
        try {
            f.fileInformation.standardInformation.endOfFile
        } finally {
            runCatching { f.close() }
        }
    }

    /** 打开一个只读句柄，**调用方负责 close**（播放数据源要长期持有） */
    fun openFile(path: String): SmbFile = synchronized(ioLock) {
        val sh = share ?: throw IllegalStateException("not connected")
        sh.openFile(
            path, EnumSet.of(AccessMask.GENERIC_READ), null,
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
        )
    }

    /**
     * 播放 / seek 时的透明恢复：会话已失效就用**上次成功的配置**重连一次。
     *
     * 背景（电视真机实测）：视频播到片尾静止一段时间后，底层 SMB 连接会变为不可用，
     * 此时 FLV 无索引容器每次 seek 都要重新 open 数据源，旧实现直接抛
     * "SMB 未连接" → 画面定格且只有一闪而过的 toast。
     *
     * 约束：只能在非主线程调用（调用方是 ExoPlayer 的 Loader 线程）；
     * 退避 1s/2s 递增，连续失败 3 次后停止自动重试，等用户在诊断层手动重试。
     */
    fun ensureConnected(force: Boolean = false): Boolean {
        if (isConnected) return true
        // 整机模式下 shareName 本来就是空的（还没切到具体共享），不能因此拒绝重连
        if (host.isBlank()) return false
        val now = SystemClock.elapsedRealtime()
        if (!force) {
            if (reconnectFails >= 3) return false
            val waitMs = when (reconnectFails) { 0 -> 0L; 1 -> 1000L; else -> 2000L }
            if (now - lastReconnectMs < waitMs) return false
        }
        lastReconnectMs = now
        val err = connect(host, shareName, user, pass, port)
        return if (err == null) {
            reconnectFails = 0
            reconnectText = "自动重连成功"
            Log.i(TAG, "auto-reconnected $host/$shareName (sessions=1)")
            true
        } else {
            reconnectFails++
            reconnectText = "自动重连失败 $reconnectFails 次：$err"
            Log.w(TAG, "auto-reconnect failed #$reconnectFails: $err")
            false
        }
    }

    /** 用户手动点「重新连接」时清零退避，立刻再试一次 */
    fun resetReconnectBackoff() {
        reconnectFails = 0
        lastReconnectMs = 0L
        reconnectText = "—"
    }

    fun onPlayerEnter() {
        playerLease = true
        if (isConnected && localSessions == 0) localSessions = 1
    }

    fun onPlayerExit() {
        playerLease = false
        if (idleReleaseSec <= 0) disconnectAll()
    }

    /** 断开本机全部会话，返回释放条数 */
    fun disconnectAll(): Int = synchronized(ioLock) {
        val n = if (isConnected) 1 else localSessions
        closeQuietlyInner()
        lastReleaseText = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
            .format(java.util.Date())
        n
    }

    fun closeQuietly() = synchronized(ioLock) { closeQuietlyInner() }

    private fun closeQuietlyInner() {
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        share = null; session = null; connection = null; client = null
        localSessions = 0
    }

    fun releaseWithLease(): Boolean = playerLease

    fun shareNameOf(): String = shareName
}

data class SmbEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modifiedMs: Long,
    val path: String,
)
