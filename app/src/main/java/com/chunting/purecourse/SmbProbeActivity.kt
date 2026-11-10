package com.chunting.purecourse

import com.chunting.purecourse.data.BuiltinPresets

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chunting.purecourse.data.SmbSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SMB 真机联调探针 —— 只由 adb 唤起，不在桌面暴露。
 *
 * ```
 * adb shell am start -n <pkg>/com.chunting.purecourse.SmbProbeActivity \
 *   --es host 10.0.2.2 --es share <共享名> --es user <账号> --es pass xxx \
 *   --es path "02 乐理课程（13门课）\张晖-乐理基础知识-入门篇(4061)"
 * ```
 *
 * 验证 5 件事（全部实测、不猜）：
 * 1. 连接耗时
 * 2. 列目录（真实条目数 + 前若干项）
 * 3. 取文件大小
 * 4. 顺序读 4MB 的吞吐
 * 5. **随机读**（片头 / 中间 / 片尾各 64KB）—— 这是 seek 能否可用的前提
 *
 * 结果同时打到 logcat（tag=SMBPROBE）与屏幕，方便截图存档。
 */
class SmbProbeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = intent.getStringExtra("host") ?: "10.0.2.2"
        val share = intent.getStringExtra("share") ?: BuiltinPresets.SHARE
        val user = intent.getStringExtra("user") ?: BuiltinPresets.USER
        val pass = intent.getStringExtra("pass") ?: ""
        val path = intent.getStringExtra("path") ?: ""

        val lines = mutableStateListOf<String>()
        fun log(s: String) {
            Log.i("SMBPROBE", s)
            lines.add(s)
        }

        setContent {
            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    // 只验证「枚举共享」这一件事：am start ... --es shares 1
                    if (intent.getBooleanExtra("shares", false)) {
                        val t = System.currentTimeMillis()
                        val (err, list) = com.chunting.purecourse.data.SmbShares.list(host, user, pass, 445)
                        log("══ NetrShareEnum 共享枚举 ══ ${System.currentTimeMillis() - t}ms")
                        log("err=${err ?: "（无）"}")
                        log("count=${list.size}")
                        list.forEach { log("  [${if (it.special) "special" else "disk"}] ${it.name}  ${it.remark}") }
                        return@withContext
                    }
                    val t0 = System.currentTimeMillis()
                    log("host=$host share=$share user=$user path=[$path]")
                    val err = SmbSession.connect(host, share, user, pass)
                    val tConn = System.currentTimeMillis() - t0
                    if (err != null) {
                        log("✗ connect 失败(${tConn}ms)：$err")
                        log("lastError=${SmbSession.lastError}")
                        return@withContext
                    }
                    log("✓ connect 成功 ${tConn}ms")
                    // 探针顺带落配置：一次 adb 注入 = App 里永久可用
                    if (intent.getBooleanExtra("save", true)) {
                        com.chunting.purecourse.data.SourceStore.upsert(
                            this@SmbProbeActivity,
                            com.chunting.purecourse.data.SourceStore.Cfg(
                                host = host, share = share, user = user, pass = pass,
                                root = intent.getStringExtra("root") ?: path,
                            )
                        )
                        log("✓ 已写入数据源配置（App 重启后仍有效）")
                    }

                    // 2) 列目录
                    val t1 = System.currentTimeMillis()
                    val list = runCatching { SmbSession.list(path) }
                        .onFailure { log("✗ list 异常：${it.javaClass.simpleName}: ${it.message}") }
                        .getOrElse { emptyList() }
                    log("✓ list 用 ${System.currentTimeMillis() - t1}ms，共 ${list.size} 项")
                    list.take(8).forEach {
                        log(if (it.isDir) "  [D] ${it.name}" else "  [F] ${it.name}  ${it.size / 1024 / 1024}MB")
                    }

                    // 找一个视频文件；若当前就在课程目录，直接取第一个
                    val video = list.filter {
                        !it.isDir && Regex("\\.(flv|mp4|mkv|avi|ts|wmv)$", RegexOption.IGNORE_CASE)
                            .containsMatchIn(it.name)
                    }.sortedBy { it.name }.firstOrNull()
                    if (video == null) {
                        log("ⓘ 该目录没有视频文件，跳过读测试")
                        log("ALL_DONE")
                        return@withContext
                    }

                    // 3) 大小
                    val t2 = System.currentTimeMillis()
                    val size = runCatching { SmbSession.fileSize(video.path) }.getOrElse { -1L }
                    log("✓ fileSize=${size}（${size / 1024 / 1024}MB），${System.currentTimeMillis() - t2}ms")

                    // 4) 顺序读 4MB
                    val f = runCatching { SmbSession.openFile(video.path) }
                        .onFailure { log("✗ openFile 异常：${it.message}") }
                        .getOrNull()
                    if (f == null) {
                        log("ALL_DONE"); return@withContext
                    }
                    try {
                        val buf = ByteArray(256 * 1024)
                        val t3 = System.currentTimeMillis()
                        var total = 0L
                        var pos = 0L
                        while (total < 4L * 1024 * 1024 && pos < size) {
                            val n = f.read(buf, pos)
                            if (n <= 0) break
                            total += n
                            pos += n
                        }
                        val dt = System.currentTimeMillis() - t3
                        val mbps = if (dt > 0) total * 1000.0 / dt / 1024 / 1024 else 0.0
                        log("✓ 顺序读 ${total / 1024}KB 用 ${dt}ms = ${"%.1f".format(mbps)}MB/s")

                        // 5) 随机读：片头/中间/片尾各 64KB —— seek 可行性的直接证据
                        val spots = listOf(0L, size / 2, (size - 65536).coerceAtLeast(0))
                        for (p in spots) {
                            val b = ByteArray(65536)
                            val t4 = System.currentTimeMillis()
                            val n = runCatching { f.read(b, p) }.getOrElse { -1 }
                            val d = System.currentTimeMillis() - t4
                            log("  随机读 @${p / 1024 / 1024}MB → ${n}B, ${d}ms")
                        }
                    } finally {
                        runCatching { f.close() }
                    }
                    log("sessions=${SmbSession.localSessions}")
                    log("ALL_DONE")
                }
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0E0B09))
                    .padding(24.dp)
            ) {
                Text(
                    "SMB 联调探针  (adb logcat -s SMBPROBE)",
                    color = Color(0xFFFF8A4C), fontSize = 20.sp, fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(12.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(lines) { l ->
                        Text(
                            l,
                            color = if (l.startsWith("✗")) Color(0xFFFF7B6B) else Color(0xFFE8E0D8),
                            fontSize = 15.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}
