package com.chunting.purecourse.data

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 扫描结果的本地缓存。
 *
 * ## 为什么必须有
 *
 * WebDAV（115 网盘经 OpenList 中转）单次 PROPFIND 实测约 7 秒，
 * 实测整棵树 317 个目录 → 8 路并发也要 4~5 分钟。
 * 没有缓存的话，每次冷启动、每次按返回退出再进来，都要重新等一遍；
 * 电视上表现为"打开 App 书架是空的，要好几分钟才出来"。
 *
 * 有缓存后：**先秒开上次的结果，再后台静默刷新**。
 *
 * ## 为什么要带配置指纹
 *
 * 换数据源（SMB ↔ WebDAV、换起始目录）后旧缓存必须失效，
 * 否则会把上一个源的课程显示出来。
 */
object CatalogCache {

    /** 每个数据源一个缓存文件：多源并存时互不覆盖（M2） */
    private fun fileName(id: String) = "catalog_cache_$id.json"
    private const val VERSION = 3

    /** 配置指纹：任何一项变化都视为"换了源"，旧缓存作废 */
    fun keyOf(cfg: SourceStore.Cfg): String = listOf(
        cfg.proto.name, cfg.host, cfg.port.toString(), cfg.share, cfg.root, cfg.baseUrl,
    ).joinToString("|")

    private fun file(ctx: Context, cfg: SourceStore.Cfg) =
        File(ctx.filesDir, fileName(cfg.id.ifBlank { "default" }))

    fun load(ctx: Context, cfg: SourceStore.Cfg): RealCatalog.Snapshot? = runCatching {
        val f = file(ctx, cfg)
        if (!f.exists()) return null
        val o = JSONObject(f.readText())
        if (o.optInt("v") != VERSION) return null
        if (o.optString("cfg") != keyOf(cfg)) return null

        val courses = ArrayList<Course>()
        val ca = o.optJSONArray("courses") ?: JSONArray()
        for (i in 0 until ca.length()) {
            val c = ca.getJSONObject(i)
            courses += Course(
                id = c.getInt("id"),
                name = c.getString("n"),
                abbr = c.optString("ab"),
                c1 = Color(c.optInt("c1")),
                c2 = Color(c.optInt("c2")),
                curEp = c.optInt("cur", 1),
                doneSet = c.optJSONArray("dset")?.let { a ->
                    (0 until a.length()).mapNotNull { j -> a.optInt(j).takeIf { v -> v > 0 } }.toSet()
                } ?: emptySet(),
                lastEpName = c.optString("len", "尚未开始"),
                lastAt = c.optLong("lat", 0L),
                lastTxt = c.optString("ltx", "尚未播放"),
                py = c.optString("py"),
                out = c.optBoolean("out", false),
                epCount = c.optInt("cnt", 0),
            )
        }

        val dirs = HashMap<Int, String>()
        o.optJSONObject("dirs")?.let { d ->
            d.keys().forEach { k -> k.toIntOrNull()?.let { dirs[it] = d.getString(k) } }
        }

        val eps = HashMap<Int, List<Episode>>()
        o.optJSONObject("eps")?.let { e ->
            e.keys().forEach { k ->
                val id = k.toIntOrNull() ?: return@forEach
                val arr = e.optJSONArray(k) ?: return@forEach
                eps[id] = (0 until arr.length()).mapNotNull { j ->
                    val a = arr.optJSONArray(j) ?: return@mapNotNull null
                    Episode(a.optString(0), a.optString(1), a.optLong(2, 0L))
                }
            }
        }

        val fs = HashMap<String, FsNode>()
        o.optJSONObject("fs")?.let { fo ->
            fo.keys().forEach { k ->
                val no = fo.optJSONObject(k) ?: return@forEach
                val ia = no.optJSONArray("i") ?: JSONArray()
                val items = (0 until ia.length()).mapNotNull { j ->
                    val a = ia.optJSONArray(j) ?: return@mapNotNull null
                    FsItem(
                        type = if (a.optString(0) == "V") ItemType.VID else ItemType.DIR,
                        name = a.optString(1),
                        to = a.optString(2).ifBlank { null },
                        course = if (a.isNull(3)) null else a.optInt(3),
                        countText = a.optString(4).ifBlank { null },
                        inShelf = a.optBoolean(5, false),
                        videoLen = a.optString(6).ifBlank { null },
                        relPath = a.optString(7).ifBlank { null },
                    )
                }
                fs[k] = FsNode(k, no.optString("n"), items)
            }
        }

        RealCatalog.Snapshot(
            courses = courses,
            dirs = dirs,
            episodes = eps,
            fs = fs,
            rootKey = o.optString("rootKey"),
            err = null,
        )
    }.getOrNull()

    /** 扫描成功后落盘。失败（磁盘满 / 序列化异常）不影响主流程。 */
    fun save(ctx: Context, cfg: SourceStore.Cfg, snap: RealCatalog.Snapshot) {
        runCatching {
            val o = JSONObject()
            o.put("v", VERSION)
            o.put("cfg", keyOf(cfg))
            o.put("rootKey", snap.rootKey)
            o.put("ts", System.currentTimeMillis())

            val ca = JSONArray()
            snap.courses.forEach { c ->
                ca.put(
                    JSONObject()
                        .put("id", c.id).put("n", c.name).put("ab", c.abbr)
                        .put("c1", c.c1.toArgb()).put("c2", c.c2.toArgb())
                        .put("cur", c.curEp).put("dset", JSONArray(c.doneSet.sorted()))
                        .put("len", c.lastEpName).put("lat", c.lastAt).put("ltx", c.lastTxt)
                        .put("py", c.py).put("out", c.out).put("cnt", c.epCount)
                )
            }
            o.put("courses", ca)

            val d = JSONObject()
            snap.dirs.forEach { (k, v) -> d.put(k.toString(), v) }
            o.put("dirs", d)

            val e = JSONObject()
            snap.episodes.forEach { (k, list) ->
                val arr = JSONArray()
                list.forEach { ep -> arr.put(JSONArray().put(ep.name).put(ep.path).put(ep.size)) }
                e.put(k.toString(), arr)
            }
            o.put("eps", e)

            val fo = JSONObject()
            snap.fs.forEach { (k, node) ->
                val ia = JSONArray()
                node.items.forEach { it2 ->
                    ia.put(
                        JSONArray()
                            .put(if (it2.type == ItemType.VID) "V" else "D")
                            .put(it2.name)
                            .put(it2.to ?: "")
                            .put(it2.course ?: JSONObject.NULL)
                            .put(it2.countText ?: "")
                            .put(it2.inShelf)
                            .put(it2.videoLen ?: "")
                            .put(it2.relPath ?: "")
                    )
                }
                fo.put(k, JSONObject().put("n", node.name).put("i", ia))
            }
            o.put("fs", fo)

            // 先写临时文件再改名，避免写到一半被杀进程留下坏 JSON
            val tmp = File(ctx.filesDir, "${fileName(cfg.id.ifBlank { "default" })}.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file(ctx, cfg))) {
                file(ctx, cfg).writeText(o.toString())
                tmp.delete()
            }
        }
    }

    fun clear(ctx: Context, cfg: SourceStore.Cfg) {
        runCatching { file(ctx, cfg).delete() }
    }

    /** 删除某个源的全部缓存（源被删除时调用） */
    fun clearById(ctx: Context, id: String) {
        runCatching { File(ctx.filesDir, fileName(id)).delete() }
    }
}
