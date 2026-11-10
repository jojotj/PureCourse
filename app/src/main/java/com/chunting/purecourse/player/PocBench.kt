package com.chunting.purecourse.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 内核 PoC 台架：同一素材、同一 Surface、串行跑两个引擎，各测 4 项。
 * 结论只用于"拍板"，不做方案论证（文档 §6 已给排序）。
 */
object PocBench {

    const val TAG = "POC"

    data class Row(
        val engine: String,
        val file: String,
        val r: PrepResult,
        val seekMs: Long,
    )

    /**
     * 测试素材查找顺序：App 外部私有目录 → 内部私有目录 → /sdcard/Movies。
     * （Android 11+ 分区存储下，未申请 READ_MEDIA_VIDEO 时 `/sdcard/Movies` 是不可读的，
     *   所以主用 `getExternalFilesDir`，adb 直接 push 进去即可，无需任何权限。）
     */
    fun files(ctx: Context): List<File> {
        val names = listOf("poc_h264.mp4", "poc_hevc.mp4")
        val dirs = listOfNotNull(ctx.getExternalFilesDir(null), ctx.filesDir, File("/sdcard/Movies"))
        for (d in dirs) {
            val hit = names.map { File(d, it) }.filter { it.exists() && it.length() > 0 }
            if (hit.isNotEmpty()) return hit
        }
        return emptyList()
    }

    // ExoPlayer 必须在带 Looper 的线程创建/访问（主线程），否则抛 Player is accessed on the wrong thread
    suspend fun runAll(ctx: Context, onRow: suspend (Row) -> Unit): List<Row> = withContext(Dispatchers.Main) {
        val out = ArrayList<Row>()
        val st = SurfaceTexture(0)
        val surface = Surface(st)
        val list = files(ctx)
        Log.i(TAG, "files=" + list.joinToString { it.name + ":" + it.length() })

        for (f in list) {
            // 只保留 Media3 臂：libmpv 已从依赖中移除（见 build.gradle.kts 的说明），
            // 台架留着是为了换电视/换片子时还能一键复测解码与 seek。
            for (make in listOf<() -> PlaybackEngine>(
                { Media3Engine(ctx) },
            )) {
                val engine = make()
                var row: Row
                try {
                    engine.create()
                    engine.attach(surface)
                    engine.open(f.absolutePath)
                    val prep = engine.awaitPrepared(20_000)
                    var seek = -1L
                    if (prep.ok && prep.durationMs > 5_000) {
                        seek = engine.seek(prep.durationMs / 2)
                    }
                    row = Row(engine.name, f.name, prep, seek)
                } catch (t: Throwable) {
                    row = Row(
                        engine.name, f.name,
                        PrepResult(false, -1, -1, -1, "-", t.javaClass.simpleName + ": " + (t.message ?: "")),
                        -1,
                    )
                } finally {
                    runCatching { engine.release() }
                }
                val line = "engine=${row.engine} file=${row.file} ok=${row.r.ok} " +
                    "init=${row.r.initMs} first=${row.r.firstFrameMs} dur=${row.r.durationMs} " +
                    "seek=${row.seekMs} codec=${row.r.codec} err=${row.r.err}"
                Log.i(TAG, line)
                out += row
                onRow(row)
            }
        }
        runCatching { surface.release(); st.release() }
        out
    }
}
