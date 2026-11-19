package com.chunting.purecourse.net

import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/** 手机填写页上的一个字段 */
data class PhoneField(
    val key: String,
    val label: String,
    val value: String = "",
    val hint: String = "",
    val kind: Kind = Kind.TEXT,
    /** SELECT 用：value to 显示文字 */
    val options: List<Pair<String, String>> = emptyList(),
) {
    enum class Kind { TEXT, NUMBER, PWD, SELECT, CHECK }
}

/** 一次扫码要渲染的整张表单（由 TV 端每次请求实时生成，因此天然回读已填数据） */
data class PhoneForm(
    val mode: String,          // smb | dav
    val title: String,
    val subtitle: String,
    val fields: List<PhoneField>,
    val pwdSaved: Boolean = false,
    val pwdLen: Int = 0,
)

/**
 * 手机扫码 → 局域网填写 → 一次性回填到电视端表单。
 *
 * 设计要点：
 * 1. 页面**每次请求都重新渲染**，字段值取自电视端当前表单，所以第二次扫码看到的是已填好的列表；
 * 2. 密码永远不回显：密码框始终为空，提交空值 = 保持电视端原值，勾「清除」才真清空；
 * 3. 只在添加数据源页停留期间监听，离开页面立即 stop。
 */
object PhoneFormServer {

    @Volatile private var socket: ServerSocket? = null
    @Volatile private var provider: (() -> PhoneForm)? = null
    @Volatile private var submitter: ((Map<String, String>) -> Unit)? = null
    @Volatile var port: Int = 0
        private set

    private val main = Handler(Looper.getMainLooper())

    /** 启动监听，返回实际端口；0 = 启动失败（端口全被占） */
    fun start(
        preferred: Int,
        provider: () -> PhoneForm,
        onSubmit: (Map<String, String>) -> Unit,
    ): Int {
        this.provider = provider
        this.submitter = onSubmit
        socket?.let { if (!it.isClosed) return port }

        var bound: ServerSocket? = null
        var chosen = 0
        for (p in preferred until preferred + 12) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(p))
                bound = s
                chosen = p
                break
            } catch (_: Exception) {
                // 端口被占，试下一个
            }
        }
        if (bound == null) {
            port = 0
            return 0
        }
        socket = bound
        port = chosen
        thread(name = "phone-form-accept", isDaemon = true) {
            while (true) {
                val s = socket ?: break
                val client = try {
                    s.accept()
                } catch (_: Exception) {
                    break
                }
                thread(name = "phone-form-conn", isDaemon = true) { handle(client) }
            }
        }
        return chosen
    }

    fun stop() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        port = 0
    }

    /** 二维码里用的局域网地址（模拟器 NAT 地址作兜底） */
    fun ip(): String {
        var fallback = ""
        try {
            val ifs = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            for (ni in ifs) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (addr.isLoopbackAddress) continue
                    val s = addr.hostAddress ?: continue
                    if (s.indexOf('.') <= 0) continue       // 跳过 IPv6
                    if (s.startsWith("127.")) continue
                    if (s.startsWith("10.0.2.")) {
                        if (fallback.isEmpty()) fallback = s
                        continue
                    }
                    return s
                }
            }
        } catch (_: Exception) {
        }
        return fallback.ifEmpty { "127.0.0.1" }
    }

    // ================= 请求处理 =================

    private fun handle(client: Socket) {
        try {
            client.use { c ->
                c.soTimeout = 8000
                val input = c.getInputStream()
                val head = ByteArrayOutputStream()
                val tmp = ByteArray(4096)
                var headerEnd = -1
                while (headerEnd < 0) {
                    val n = input.read(tmp)
                    if (n <= 0) return
                    head.write(tmp, 0, n)
                    val cur = head.toByteArray()
                    headerEnd = indexOfCrLfCrLf(cur)
                    if (cur.size > 65536) return
                }
                val all = head.toByteArray()
                val headerText = String(all, 0, headerEnd, Charsets.ISO_8859_1)
                val lines = headerText.split("\r\n")
                val reqLine = lines.firstOrNull().orEmpty().split(" ")
                val method = reqLine.getOrElse(0) { "GET" }
                val rawPath = reqLine.getOrElse(1) { "/" }
                val contentLength = lines.drop(1)
                    .firstOrNull { it.lowercase().startsWith("content-length:") }
                    ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0

                val body = ByteArrayOutputStream()
                val firstChunk = all.size - (headerEnd + 4)
                if (firstChunk > 0) body.write(all, headerEnd + 4, firstChunk)
                var remaining = contentLength - body.size()
                while (remaining > 0) {
                    val n = input.read(tmp, 0, minOf(tmp.size, remaining))
                    if (n <= 0) break
                    body.write(tmp, 0, n)
                    remaining -= n
                }

                val path = rawPath.substringBefore('?')
                when {
                    method.equals("POST", true) && path.startsWith("/submit") -> {
                        val values = parseForm(String(body.toByteArray(), Charsets.UTF_8))
                        val count = values.size
                        main.post { submitter?.invoke(values) }
                        respond(c, pageDone(count))
                    }
                    path == "/" || path.startsWith("/index") -> {
                        val f = provider?.invoke()
                        respond(c, if (f == null) pageGone() else pageForm(f))
                    }
                    else -> respond(c, pageNotFound(), "404 Not Found")
                }
            }
        } catch (_: Exception) {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun indexOfCrLfCrLf(b: ByteArray): Int {
        for (i in 0..b.size - 4) {
            if (b[i] == 13.toByte() && b[i + 1] == 10.toByte() &&
                b[i + 2] == 13.toByte() && b[i + 3] == 10.toByte()
            ) return i
        }
        return -1
    }

    private fun parseForm(body: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (body.isEmpty()) return out
        for (pair in body.split('&')) {
            if (pair.isEmpty()) continue
            val k = pair.substringBefore('=')
            val v = if (pair.contains('=')) pair.substringAfter('=') else ""
            val key = runCatching { URLDecoder.decode(k, "UTF-8") }.getOrDefault(k)
            val value = runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
            out[key] = value
        }
        return out
    }

    private fun respond(socket: Socket, html: String, status: String = "200 OK") {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val out: OutputStream = socket.getOutputStream()
        val header = "HTTP/1.1 $status\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    // ================= 页面 =================

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")

    private val CSS = """
        :root{color-scheme:dark}
        *{-webkit-tap-highlight-color:transparent}
        body{margin:0;background:#0f1115;color:#e6e8ee;
             font:16px/1.55 -apple-system,"PingFang SC","Noto Sans SC",system-ui,sans-serif;
             padding:18px 14px 130px}
        h1{font-size:19px;margin:0 0 4px;color:#ff8a3d;letter-spacing:.3px}
        .sub{color:#8b93a6;font-size:13px;margin:0 0 6px}
        .tip{color:#6f7789;font-size:12px;margin:10px 0 0}
        label.lb{display:block;font-size:13px;color:#9aa3b8;margin:16px 0 6px}
        input[type=text],input[type=number],input[type=password],select{
             width:100%;box-sizing:border-box;font-size:17px;padding:13px 12px;
             border-radius:10px;border:1px solid #2a2f3a;background:#171a21;color:#e6e8ee}
        input:focus,select:focus{outline:none;border-color:#ff8a3d}
        .hint{font-size:12px;color:#6f7789;margin-top:6px}
        .sw{display:flex;align-items:center;gap:12px;margin-top:16px;background:#171a21;
            border:1px solid #2a2f3a;border-radius:10px;padding:13px}
        .sw span{font-size:15px;color:#dfe3ec}
        .sw input{width:22px;height:22px;accent-color:#ff8a3d;margin:0}
        .bar{position:fixed;left:0;right:0;bottom:0;padding:14px 14px calc(14px + env(safe-area-inset-bottom));
             background:linear-gradient(180deg,rgba(15,17,21,0),#0f1115 42%)}
        button{width:100%;font-size:18px;font-weight:700;padding:16px;border:0;border-radius:12px;
               background:#ff8a3d;color:#1a1207}
        .ok{text-align:center;padding-top:60px}
        .ok .big{font-size:56px;color:#3ddc84;line-height:1}
        .ok h2{font-size:21px;margin:18px 0 8px;color:#e6e8ee}
        .ok p{color:#8b93a6;font-size:14px;margin:6px 0}
        .ok a{display:inline-block;margin-top:26px;color:#ff8a3d;font-size:16px;
              text-decoration:none;border:1px solid #ff8a3d;border-radius:10px;padding:12px 26px}
    """.trimIndent()

    private fun head(title: String) = """
        <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
        <meta name="theme-color" content="#0f1115">
        <title>${esc(title)}</title><style>$CSS</style></head><body>
    """.trimIndent()

    private fun pageForm(f: PhoneForm): String {
        val sb = StringBuilder()
        sb.append(head("纯看课 · 手机填写"))
        sb.append("<h1>").append(esc(f.title)).append("</h1>")
        sb.append("<p class=\"sub\">").append(esc(f.subtitle)).append("</p>")
        sb.append("<form method=\"post\" action=\"/submit\">")
        sb.append("<input type=\"hidden\" name=\"mode\" value=\"").append(esc(f.mode)).append("\">")

        for (fd in f.fields) {
            when (fd.kind) {
                PhoneField.Kind.CHECK -> {
                    sb.append("<label class=\"sw\"><input type=\"checkbox\" name=\"")
                        .append(esc(fd.key)).append("\" value=\"1\"")
                    if (fd.value == "1") sb.append(" checked")
                    sb.append("><span>").append(esc(fd.label)).append("</span></label>")
                    if (fd.hint.isNotEmpty()) sb.append("<div class=\"hint\">").append(esc(fd.hint)).append("</div>")
                }
                PhoneField.Kind.SELECT -> {
                    sb.append("<label class=\"lb\">").append(esc(fd.label)).append("</label>")
                    sb.append("<select name=\"").append(esc(fd.key)).append("\">")
                    for ((v, t) in fd.options) {
                        sb.append("<option value=\"").append(esc(v)).append("\"")
                        if (v == fd.value) sb.append(" selected")
                        sb.append(">").append(esc(t)).append("</option>")
                    }
                    sb.append("</select>")
                    if (fd.hint.isNotEmpty()) sb.append("<div class=\"hint\">").append(esc(fd.hint)).append("</div>")
                }
                else -> {
                    val type = when (fd.kind) {
                        PhoneField.Kind.NUMBER -> "number"
                        PhoneField.Kind.PWD -> "password"
                        else -> "text"
                    }
                    val extra = when (fd.kind) {
                        PhoneField.Kind.NUMBER -> " inputmode=\"numeric\""
                        else -> " autocapitalize=\"off\" autocorrect=\"off\""
                    }
                    sb.append("<label class=\"lb\">").append(esc(fd.label)).append("</label>")
                    sb.append("<input type=\"").append(type).append("\" name=\"").append(esc(fd.key))
                        .append("\" value=\"").append(esc(fd.value)).append("\"").append(extra).append(">")
                    if (fd.hint.isNotEmpty()) sb.append("<div class=\"hint\">").append(esc(fd.hint)).append("</div>")
                }
            }
        }

        // 密码区：永远不显示已存密码，只给「留空=不改」与「清除」两个明确动作
        if (f.mode == "dav" || f.mode == "smb") {
            if (f.pwdSaved) {
                sb.append("<label class=\"sw\"><input type=\"checkbox\" name=\"clearpwd\" value=\"1\">")
                    .append("<span>清除电视上已保存的密码</span></label>")
            }
        }

        sb.append("<p class=\"tip\">同一 Wi-Fi 下使用 · 填写内容仅在本机局域网内传输</p>")
        sb.append("<div class=\"bar\"><button type=\"submit\">上传到电视</button></div>")
        sb.append("</form></body></html>")
        return sb.toString()
    }

    private fun pageDone(count: Int): String = head("已上传") + """
        <div class="ok">
          <div class="big">✓</div>
          <h2>已发送到电视</h2>
          <p>$count 项内容已填入电视端表单</p>
          <p>回到电视可继续「测试连接」或「保存」</p>
          <a href="/">再改一次</a>
        </div></body></html>
    """.trimIndent()

    private fun pageGone(): String = head("页面已失效") + """
        <div class="ok">
          <div class="big">·</div>
          <h2>电视已离开填写页</h2>
          <p>请回到电视的「添加数据源」页面重新扫码</p>
        </div></body></html>
    """.trimIndent()

    private fun pageNotFound(): String = head("未找到") + "<h1>404</h1><p class=\"sub\">请扫描电视上的二维码重新进入</p></body></html>"
}
