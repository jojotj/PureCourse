package com.chunting.purecourse.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import com.chunting.purecourse.data.SmbSession

/**
 * Media3 的 SMB 数据源：`smb://host/share/path/to/file.mp4`
 *
 * 关键点：SMB 是**随机访问**，不是顺序流。smbj 的 `File.read(buffer, fileOffset)`
 * 天然支持按偏移读，所以 seek 不需要"重新打开 + 丢弃前面的字节"，
 * 这也是选择"自写 DataSource"而不是"先把文件下到本地"的原因。
 */
class SmbDataSource : BaseDataSource(/* isNetwork = */ true) {

    private var file: com.hierynomus.smbj.share.File? = null
    private var uri: Uri? = null
    private var position = 0L
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    /**
     * 读放大缓冲（起播加速的核心）。
     *
     * ExoPlayer 起播时按小块要数据（解析 moov、填 500ms 起播缓冲，单次通常 16~64KB），
     * 若每次 read 都直接发一条 SMB READ，一个片头就要 20~50 次局域网往返，
     * 单次往返 10~30ms → 用户感知即是"进片要等几秒"。
     * 播放开始后 LoadControl 已经预缓冲 12s，读请求被摊薄，所以只有"刚进片"慢。
     *
     * 这里一次 READ 就取 [PREFETCH_BYTES]（1MB，smbj 会自动拆成协商好的多个 SMB2 READ），
     * 后续小块读全部从内存切片，起播往返次数从几十次压到 1~2 次。
     */
    private var cache = ByteArray(PREFETCH_BYTES)
    /** 缓存覆盖的起始文件偏移；[-cacheValid] 为 false 时无意义 */
    private var cacheStart = -1L
    /** 缓存内有效字节数 */
    private var cacheLen = 0
    private var cacheValid = false
    /** 每个实例只打印一次"首块预读耗时"，避免 logcat 刷屏 */
    private var firstFillLogged = false

    override fun open(dataSpec: DataSpec): Long {
        val t0 = android.os.SystemClock.elapsedRealtime()
        transferInitializing(dataSpec)
        uri = dataSpec.uri

        // FLV 没有索引，每次 seek 都会重新 open。电视真机实测：播到片尾静置一段时间后
        // 底层 SMB 连接会失效，此时必须先用上次成功的配置透明重连，而不是直接报错定格。
        if (!SmbSession.isConnected && !SmbSession.ensureConnected()) {
            throw java.io.IOException(
                "SMB 会话已断开且自动重连失败：${SmbSession.lastError ?: "请到数据源页重新连接共享"}"
            )
        }
        // URI 形如 smb://host/share/a/b/c.flv：第 1 段是共享名，
        // 必须剥掉，否则会拼成 \\host\share\share\a\b —— 报 STATUS_OBJECT_PATH_NOT_FOUND。
        // 用 pathSegments（已做 URL 解码）而不是 path（中文/括号易出问题）。
        val segs = dataSpec.uri.pathSegments ?: emptyList()
        if (segs.isEmpty()) throw java.io.IOException("URI 里没有共享名：${dataSpec.uri}")
        val shareName = segs[0]
        val path = segs.drop(1).joinToString("\\")

        // 多源并存：如果这条片子属于另一台 SMB 主机，必须先把会话切到那台机器上。
        // SmbSession 是全局单会话，useShare 只能在**当前主机**里换共享；
        // 不重连就会出现「A 机的片子播成 B 机的目录不存在」。
        val uriHost = dataSpec.uri.host.orEmpty()
        if (uriHost.isNotEmpty() && SmbSession.hostName() != uriHost) {
            val cfg = runCatching {
                com.chunting.purecourse.data.SourceStore.list(com.chunting.purecourse.App.instance)
                    .firstOrNull { it.proto == com.chunting.purecourse.data.Proto.SMB && it.host == uriHost }
            }.getOrNull()
            if (cfg == null) {
                throw java.io.IOException("找不到主机 $uriHost 的数据源配置，无法播放")
            }
            val e = SmbSession.connect(cfg.host, cfg.share, cfg.user, cfg.pass, cfg.port)
            if (e != null) {
                throw java.io.IOException("连接 $uriHost 失败：${SmbSession.lastError ?: e}")
            }
        }

        // 整机模式：同一台主机下可能有多个共享，播放前先切到文件所在的那个共享。
        // 只切换 DiskShare，不重连会话，代价很小。
        if (SmbSession.shareNameOf() != shareName) {
            val e = SmbSession.useShare(shareName)
            if (e != null) throw java.io.IOException("切换共享「$shareName」失败：$e")
        }
        android.util.Log.d("SmbDataSource", "open share=$shareName path=[$path] pos=${dataSpec.position}")
        if (path.isEmpty()) throw java.io.IOException("URI 里没有文件路径：${dataSpec.uri}")
        val f = SmbSession.openFile(path)
        file = f
        // 起播链路计时：open 句柄本身也是一次往返，留着方便真机量化（logcat 关键字 SmbDataSource）
        android.util.Log.i(
            "SmbDataSource",
            "openFile ${android.os.SystemClock.elapsedRealtime() - t0}ms pos=${dataSpec.position} [$path]"
        )
        // 换文件/换位置：旧缓存一律作废（seek 后第一读会重新放大预读）
        cacheValid = false
        cacheLen = 0

        // 文件大小按 path 记一份：同一个文件 seek 会被反复 open（FLV 无索引），
        // 每次都发一条 FileAllInformation 查询纯属白等一个往返。
        // key 必须含共享名：不同共享下可能存在同名相对路径，否则会串大小。
        val sizeKey = "$shareName\\$path"
        val size = sizeCache[sizeKey] ?: run {
            val s = runCatching { f.fileInformation.standardInformation.endOfFile }.getOrDefault(0L)
            if (s > 0) {
                if (sizeCache.size > 128) sizeCache.clear()
                sizeCache[sizeKey] = s
            }
            s
        }
        // FLV 无索引，快移/seek 是按码率估算的字节偏移，可能落在文件末尾之外
        // （实测 MuMu 连按 7 次 60 秒快移，最后一次 open pos=70241486 已贴到文件尾）。
        // 若不 clamp，bytesRemaining 会变负数 → read() 里 ByteArray(负数) 抛异常，
        // ExoPlayer 既拿不到 END_OF_INPUT 也不发 STATE_ENDED，画面就永久定格在片尾。
        position = dataSpec.position.coerceIn(0, size.coerceAtLeast(0))
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length.coerceAtLeast(0)
            else -> (size - position).coerceAtLeast(0)
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        // open() 已 clamp 位置；这里再兜一层，负剩余长度按「结尾」处理而不是拿去开数组
        if (bytesRemaining == 0L || (bytesRemaining < 0L && bytesRemaining != C.LENGTH_UNSET.toLong()))
            return C.RESULT_END_OF_INPUT

        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length
        else minOf(bytesRemaining, length.toLong()).toInt().coerceAtLeast(0)

        if (file == null) return C.RESULT_END_OF_INPUT
        val n = if (cacheValid && position >= cacheStart && position + toRead <= cacheStart + cacheLen) {
            // 命中预读窗口：纯内存切片，0 次网络往返
            val offInCache = (position - cacheStart).toInt()
            System.arraycopy(cache, offInCache, buffer, offset, toRead)
            toRead
        } else {
            fillAndCopy(buffer, offset, toRead)
        }
        if (n <= 0) return C.RESULT_END_OF_INPUT
        position += n
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    /**
     * 缓存未命中：从当前 position 一次读 [PREFETCH_BYTES]（到文件尾自动截断），
     * 拷走本次需要的部分，剩下的留在内存给后续小块读复用。
     */
    private fun fillAndCopy(buffer: ByteArray, offset: Int, want: Int): Int {
        val start = position
        val fillLen = maxOf(want, PREFETCH_BYTES)
        val t0 = android.os.SystemClock.elapsedRealtime()
        val n = readAt(start, cache, fillLen)
        if (n <= 0) {
            cacheValid = false
            cacheLen = 0
            return 0
        }
        if (!firstFillLogged) {
            firstFillLogged = true
            android.util.Log.i(
                "SmbDataSource",
                "首块预读 ${android.os.SystemClock.elapsedRealtime() - t0}ms / $n bytes @" + start
            )
        }
        cacheStart = start
        cacheLen = n
        cacheValid = true
        val take = minOf(want, n)
        System.arraycopy(cache, 0, buffer, offset, take)
        return take
    }

    /**
     * 带自愈的底层读。
     *
     * 电视真机实测：SMB 会话被对端/系统悄悄断掉时，[SmbSession.isConnected] 仍是 true
     * （半开连接），旧实现直接抛 IOException，ExoPlayer 报错定格，用户只能"切到别的集再
     * 切回来"才能恢复。这里就地重连 + 重开句柄 + 从同一偏移重读一次。
     */
    private fun readAt(filePos: Long, dst: ByteArray, len: Int): Int {
        val f = file ?: return -1
        return try {
            f.read(dst, filePos)
        } catch (t: Throwable) {
            android.util.Log.w("SmbDataSource", "read fail @$filePos (${t.message})，尝试自愈")
            if (!heal()) throw java.io.IOException("SMB 读失败且重连无效：${t.message}", t)
            try {
                file!!.read(dst, filePos)
            } catch (t2: Throwable) {
                throw java.io.IOException("SMB 读失败：${t2.message}", t2)
            }
        }
    }

    /** 播放中的透明恢复：关旧句柄 → 强重连 → 切回本片所在共享 → 重开句柄 */
    private fun heal(): Boolean {
        val u = uri ?: return false
        val segs = u.pathSegments ?: return false
        if (segs.isEmpty()) return false
        val shareName = segs[0]
        val path = segs.drop(1).joinToString("\\")
        if (path.isEmpty()) return false
        runCatching { file?.close() }
        file = null
        cacheValid = false
        cacheLen = 0
        SmbSession.resetReconnectBackoff()
        if (!SmbSession.isConnected) SmbSession.ensureConnected(force = true)
        if (SmbSession.shareNameOf() != shareName) {
            runCatching { SmbSession.useShare(shareName) }
        }
        return try {
            file = SmbSession.openFile(path)
            android.util.Log.i("SmbDataSource", "自愈成功：$path @${position}")
            true
        } catch (t: Throwable) {
            android.util.Log.w("SmbDataSource", "自愈失败：${t.message}")
            file = null
            false
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        runCatching { file?.close() }
        file = null
        uri = null
        cacheValid = false
        cacheLen = 0
        cacheStart = -1L
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource()
    }

    companion object {
        private const val TAG = "SmbDataSource"

        /**
         * 单次预读放大倍数：1MB。
         *
         * 取 1MB 而不是更大：smbj 单条 SMB2 READ 通常协商上限就是 1MB，
         * 再大也只是自己多拆包；而 1MB 已足够覆盖片头 moov + 起播缓冲的全部小块读。
         * 内存代价：每个数据源实例一份 1MB 复用数组（播放时只有 1 个实例）。
         */
        private const val PREFETCH_BYTES = 1 shl 20

        /** 文件大小缓存（path → bytes）：FLV/无索引容器 seek 会反复 open 同一文件 */
        private val sizeCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

        /**
         * 统一的数据源工厂：`smb://` 走本类，`file://`/`content://` 走系统实现。
         * 交给 `DefaultDataSource` 按 scheme 自动分发，播放器侧不用判断。
         */
        fun defaultFactory(context: android.content.Context): DataSource.Factory =
            DefaultDataSource.Factory(context, Factory())
    }
}
