package com.chunting.purecourse.player

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.chunting.purecourse.data.DavClient
import com.chunting.purecourse.data.Proto
import com.chunting.purecourse.data.SourceStore

/**
 * 播放数据源路由：
 *  - `http(s)://`  → OkHttpDataSource（带 Basic 认证 + 可选忽略自签证书，支持 Range 续传）
 *  - `smb://`      → 自写 SmbDataSource
 *
 * ## 为什么按 URI 分派，而不是"按当前保存的配置分派"
 *
 * M1 时这里读的是「唯一那条配置」的协议。多源并存后，同一个 ExoPlayer 会先后播放
 * SMB 课程和 WebDAV 课程的片子，`createDataSource()` 时**根本不知道该播哪个源**——
 * 按"最后保存的那个源"猜，会出现「SMB 的片子被交给 OkHttp、直接 404」这种必然错误。
 *
 * 现在 `createDataSource()` 返回一个代理 DataSource，等 `open(dataSpec)` 拿到真实 URI
 * 之后再按 scheme 选通道。认证信息也按 URI 反查对应源，多源各用各的账号。
 */
class SourceRoutingFactory(private val ctx: Context) : DataSource.Factory {

    private val smb = SmbDataSource.Factory()

    override fun createDataSource(): DataSource = RoutingDataSource(ctx, smb)

    private class RoutingDataSource(
        private val ctx: Context,
        private val smb: SmbDataSource.Factory,
    ) : DataSource {

        private var listener: TransferListener? = null
        private var delegate: DataSource? = null
        private var uri: Uri? = null

        override fun addTransferListener(transferListener: TransferListener) {
            listener = transferListener
        }

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            runCatching { delegate?.close() }
            delegate = null

            val scheme = dataSpec.uri.scheme?.lowercase().orEmpty()
            val created = when (scheme) {
                "http", "https" -> {
                    val url = dataSpec.uri.toString()
                    // 按完整 URL 前缀匹配到具体那个 WebDAV 源，用**它自己的**账号与证书策略
                    val cfgs = runCatching { SourceStore.list(ctx) }.getOrDefault(emptyList())
                    val cfg = cfgs.firstOrNull {
                        it.proto == Proto.DAV && it.baseUrl.isNotBlank() &&
                            url.startsWith(it.baseUrl.trimEnd('/'))
                    } ?: cfgs.firstOrNull { it.proto == Proto.DAV }
                    val f = OkHttpDataSource.Factory(DavClient.client(cfg?.ignoreCert ?: false))
                    val headers = HashMap<String, String>()
                    cfg?.let { DavClient.authHeader(it.user, it.pass)?.let { h -> headers["Authorization"] = h } }
                    // 有些 WebDAV 网关（OpenList / 某些 NAS）会拒绝没有 UA 的请求
                    headers["User-Agent"] = "PureCourse/1.0 (Android)"
                    f.setDefaultRequestProperties(headers)
                    f.createDataSource()
                }

                // 本地存储 / U 盘：file:// 必须交给 FileDataSource。
                // 之前没有这个分支，file:// 会被丢给 SmbDataSource，
                // 表现是"U 盘里的课能列出来，一点播放就报错"。
                "file" -> FileDataSource.Factory().createDataSource()

                else -> smb.createDataSource()
            }
            listener?.let { created.addTransferListener(it) }
            delegate = created
            return created.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate?.read(buffer, offset, length) ?: 0

        override fun getUri(): Uri? = uri

        override fun close() {
            runCatching { delegate?.close() }
            delegate = null
            uri = null
        }

        override fun getResponseHeaders(): Map<String, List<String>> =
            delegate?.responseHeaders ?: emptyMap()
    }
}
