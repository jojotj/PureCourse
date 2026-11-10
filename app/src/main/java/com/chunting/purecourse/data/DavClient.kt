package com.chunting.purecourse.data

import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import javax.xml.parsers.DocumentBuilderFactory

/**
 * WebDAV 最小客户端（M1）。
 *
 * 只做三件事，全部围绕「列目录 / 播放 / 探活」：
 *  1. `PROPFIND Depth: 1` 列一层目录（DOM 解析，零额外依赖）
 *  2. Basic 认证
 *  3. 可选的「忽略自签证书」（群晖 / Alist / 内网 NAS 基本都需要）
 *
 * 路径约定与 SMB 完全一致：对外用 `\` 分隔的「根目录内相对路径」，
 * 只是拼 URL 时才换成 `/`。这样 [RealCatalog.Snapshot] 可直接复用，
 * 浏览页 / 书架 / 详情 / 播放都不用为 DAV 单独改一套。
 */
data class DavEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    /** 根目录内的相对路径，`\` 分隔 */
    val relPath: String,
)

object DavClient {

    private const val PROPFIND_BODY =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<d:propfind xmlns:d=\"DAV:\"><d:prop>" +
            "<d:displayname/><d:resourcetype/><d:getcontentlength/>" +
            "</d:prop></d:propfind>"

    private val XML_TYPE = "application/xml; charset=utf-8".toMediaType()

    // ---------------- 信任所有证书（仅当用户显式勾选「忽略证书校验」时使用） ----------------
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @Volatile private var cachedClient: OkHttpClient? = null
    @Volatile private var cachedIgnore: Boolean = false
    @Volatile private var cachedBrowse: OkHttpClient? = null
    @Volatile private var cachedBrowseIgnore: Boolean = false

    /** 只在用户显式勾选「忽略证书校验」时放行自签证书 */
    private fun applyTrustAll(b: OkHttpClient.Builder, ignoreCert: Boolean) {
        if (!ignoreCert) return
        runCatching {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<javax.net.ssl.TrustManager>(trustAll), SecureRandom())
            b.sslSocketFactory(ctx.socketFactory, trustAll)
            b.hostnameVerifier { _, _ -> true }
        }
    }

    /**
     * 列目录 / 浏览专用通道。
     *
     * 与播放通道[client]彻底分开，两条通道各自独立排队：
     *  - 播放要长读超时（90s）并允许重试，否则局域网抖动会把正在播的片子打断；
     *  - 浏览要“立刻成功或立刻失败”，读超时 15s、禁重试。
     * 以前两者共用一条通道，一次卡死的 PROPFIND 会把后面所有目录请求排在后面，
     * 用户看到的就是“点进文件夹要等半天”。
     */
    @Synchronized
    fun browseClient(ignoreCert: Boolean): OkHttpClient {
        cachedBrowse?.let { if (cachedBrowseIgnore == ignoreCert) return it }
        val b = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            // 浏览请求不重试：失败就立刻把错误交给界面，让用户自己再点一次，
            // 比“悄悄重试三次再报错”好得多（那会变成十几秒的白屏）。
            .retryOnConnectionFailure(false)
            .dispatcher(
                okhttp3.Dispatcher().apply {
                    maxRequests = 16
                    maxRequestsPerHost = 6
                }
            )
            .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
        applyTrustAll(b, ignoreCert)
        val c = b.build()
        cachedBrowse = c
        cachedBrowseIgnore = ignoreCert
        return c
    }

    @Synchronized
    fun client(ignoreCert: Boolean): OkHttpClient {
        cachedClient?.let { if (cachedIgnore == ignoreCert) return it }
        val b = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            // 视频播放要 Range 续传，禁止 OkHttp 自己缓存整段
            .retryOnConnectionFailure(true)
            // 默认 maxRequestsPerHost = 5，会把 8 路并发悄悄压成 5 路；
            // 单次 PROPFIND 要 7 秒，这个上限直接决定整棵树扫多久。
            .dispatcher(
                okhttp3.Dispatcher().apply {
                    maxRequests = 24
                    maxRequestsPerHost = 12
                }
            )
            // 默认只留 5 个空闲连接，并发探测时会反复握手
            .connectionPool(okhttp3.ConnectionPool(16, 5, TimeUnit.MINUTES))
        if (ignoreCert) applyTrustAll(b, ignoreCert)
        val c = b.build()
        cachedClient = c
        cachedIgnore = ignoreCert
        return c
    }

    fun authHeader(user: String, pass: String): String? =
        if (user.isBlank() && pass.isBlank()) null else Credentials.basic(user, pass)

    /** 根 URL + 相对路径 → 完整 URL（逐段编码，空格/中文都能处理） */
    fun urlOf(baseUrl: String, rel: String): String? {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return null
        val hb = base.toHttpUrlOrNull()?.newBuilder() ?: return null
        rel.trim('\\', '/').split('\\', '/').filter { it.isNotBlank() }
            .forEach { hb.addPathSegment(it) }
        val url = hb.build()
        return if (rel.isBlank()) url.toString().let { if (it.endsWith("/")) it else "$it/" } else url.toString()
    }

    /**
     * 目录 URL：**一定以 `/` 结尾**。
     *
     * 不补尾斜杠时，服务端会先回 301 让客户端补斜杠再重发，等于每个目录多付一次往返。
     * 播放用的是文件 URL（[urlOf]），那里不能补斜杠，所以这两个函数必须分开。
     */
    fun urlOfDir(baseUrl: String, rel: String): String? {
        val u = urlOf(baseUrl, rel) ?: return null
        return if (u.endsWith("/")) u else "$u/"
    }

    /**
     * 解码 URL 里的百分号编码。
     *
     * 不能直接 `URLDecoder.decode`：它是 **form 解码**，会把 path 里合法的 `+`
     * 变成空格（文件名 "a+b.mp4" 会被改成 "a b.mp4"，后续就找不到文件）。
     * 先把 `+` 保护成 `%2B` 再解码，等价于纯 percent-decoding。
     */
    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    /**
     * href（绝对 URL 或绝对路径）→ 根目录内相对路径。
     *
     * ⚠️ 这里必须**两边都解码后再比**：`HttpUrl.encodedPath` 返回的是原始编码串
     * （`/dav/115/%E4%BA%8E%E6%96%AF...`），而 href 我们是解码过的。
     * 一旦拿编码串去 startsWith 解码串，中文路径永远匹配不上，
     * 相对路径就会退化成带 `dav/115/...` 全前缀的垃圾（表现为课程名错乱、目录层级全崩）。
     */
    private fun relOf(href: String, baseUrl: String): String {
        val basePath = decode(
            baseUrl.trim().trimEnd('/').toHttpUrlOrNull()?.encodedPath ?: ""
        ).trimEnd('/')
        var p = href.trim()
        if (p.startsWith("http://", true) || p.startsWith("https://", true)) {
            p = p.toHttpUrlOrNull()?.encodedPath ?: p
        }
        p = decode(p)
        if (basePath.isNotEmpty()) {
            // 加 `/` 边界，避免 baseUrl=/dav/a 误吃 /dav/ab/x
            p = when {
                p == basePath -> ""
                p.startsWith("$basePath/") -> p.substring(basePath.length)
                else -> p
            }
        }
        return p.trim('/').replace('/', '\\')
    }

    /**
     * 列一层目录。成功返回条目（不含自身），失败返回带分类的异常文案。
     * 必须在后台线程调用。
     */
    fun list(
        baseUrl: String,
        user: String,
        pass: String,
        ignoreCert: Boolean,
        rel: String,
    ): Result<List<DavEntry>> {
        // 目录 URL 必须补尾斜杠，见 urlOfDir 的注释
        val target = urlOfDir(baseUrl, rel)
            ?: return Result.failure(IllegalArgumentException("URL 为空或格式不对"))
        val req = Request.Builder()
            .url(target)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_TYPE))
            .header("Depth", "1")
            .apply { authHeader(user, pass)?.let { header("Authorization", it) } }
            .build()
        return runCatching {
            browseClient(ignoreCert).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw DavHttpException(resp.code, resp.message)
                }
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                parse(bytes, baseUrl, rel)
            }
        }
    }

    private fun parse(bytes: ByteArray, baseUrl: String, requestedRel: String): List<DavEntry> {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        val selfRel = requestedRel.trim('\\')
        val out = ArrayList<DavEntry>()
        for (i in 0 until responses.length) {
            val node = responses.item(i)
            val el = node as? org.w3c.dom.Element ?: continue
            val href = el.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent?.trim() ?: continue
            val rel = relOf(href, baseUrl)
            // PROPFIND Depth:1 会把「请求的这个目录自己」也返回，跳过它
            if (rel == selfRel) continue
            if (rel.isBlank()) continue
            val isDir = el.getElementsByTagNameNS("DAV:", "collection").length > 0
            val size = el.getElementsByTagNameNS("DAV:", "getcontentlength").item(0)
                ?.textContent?.trim()?.toLongOrNull() ?: 0L
            val name = rel.substringAfterLast('\\')
            out += DavEntry(name, isDir, size, rel)
        }
        return out
    }

    /** 探活：能列出一层目录就算通过。返回 (错误文案?, 条目数) */
    fun test(
        baseUrl: String,
        user: String,
        pass: String,
        ignoreCert: Boolean,
        rel: String = "",
    ): Pair<String?, Int> = list(baseUrl, user, pass, ignoreCert, rel).fold(
        onSuccess = { null to it.size },
        onFailure = { humanize(it) to 0 },
    )

    private fun humanize(t: Throwable?): String = when (t) {
        is DavHttpException -> when (t.code) {
            401, 403 -> "认证失败（HTTP ${t.code}）· 账号或密码不对，或该目录无权限"
            404 -> "路径不存在（HTTP 404）· 检查 URL 是否正确"
            405 -> "服务器不支持 WebDAV（HTTP 405）· 确认该地址是 WebDAV 端点"
            301, 302 -> "地址被重定向（HTTP ${t.code}）· 请直接填写最终 URL"
            else -> "服务器返回 HTTP ${t.code} ${t.message}"
        }
        is java.net.UnknownHostException -> "域名解析失败 · 检查地址拼写"
        is javax.net.ssl.SSLHandshakeException ->
            "证书校验失败 · 自签证书请打开「忽略证书校验」"
        is java.net.SocketTimeoutException -> "连接超时 · 检查网络 / 端口 / 防火墙"
        else -> t?.message?.takeIf { it.isNotBlank() } ?: "连接失败"
    }
}

class DavHttpException(val code: Int, override val message: String) : Exception(message)
