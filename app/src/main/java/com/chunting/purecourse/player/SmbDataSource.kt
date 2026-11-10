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

    override fun open(dataSpec: DataSpec): Long {
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

        val size = f.fileInformation.standardInformation.endOfFile
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

        val f = file ?: return C.RESULT_END_OF_INPUT
        val tmp = ByteArray(toRead)
        val n = try {
            f.read(tmp, position)
        } catch (t: Throwable) {
            throw java.io.IOException("SMB 读失败：${t.message}", t)
        }
        if (n <= 0) return C.RESULT_END_OF_INPUT
        System.arraycopy(tmp, 0, buffer, offset, n)
        position += n
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
        bytesTransferred(n)
        return n
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
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource()
    }

    companion object {
        /**
         * 统一的数据源工厂：`smb://` 走本类，`file://`/`content://` 走系统实现。
         * 交给 `DefaultDataSource` 按 scheme 自动分发，播放器侧不用判断。
         */
        fun defaultFactory(context: android.content.Context): DataSource.Factory =
            DefaultDataSource.Factory(context, Factory())
    }
}
