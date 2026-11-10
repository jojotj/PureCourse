package com.chunting.purecourse.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import com.chunting.purecourse.data.SourceStore.Cfg
import java.io.File

/**
 * 本地存储挂载点探测：把「电视内置存储 + 每个 U 盘 / SD 卡」变成一条条数据源配置。
 *
 * ## 为什么做成「一个挂载点 = 一条 Cfg」
 *
 * 左树第一层本来就是各条数据源的根（`本机SMB` / `115网盘`），把挂载点也做成 Cfg 之后，
 * 「本地存储」「U盘 · 闪迪」直接出现在同一层，展开收起、右栏、加书架、收藏、备注名
 * 全部走已有那套逻辑 —— **不需要为本地存储另写一个页面**。
 * 这是"平板端 / 手机端逻辑也不用改"的前提：以后加内置存储以外的挂载方式（如 SD 卡）
 * 只是多一条 Cfg。
 *
 * ## 为什么不落库
 *
 * 用户拔掉 U 盘后，那条配置就应该消失。落库会留下一条永远"连接失败"的死数据源，
 * 而且插回去 id 可能对不上。所以每次都实时探测，id 由卷 UUID 派生（同一根 U 盘每次一致），
 * 这样收藏 / 书架 / 续播里的 `@l_xxx\...` 路径在插回同一根 U 盘后仍然有效。
 */
object LocalStore {

    /**
     * 系统垃圾 / 无权限目录：扫进去只会污染书架。
     *
     * 只列真正不可能放课程的那几个。不要随手加 "data"、"movie" 这类常见词 ——
     * U 盘上真有可能有个叫 data 的课程文件夹，误过滤就是"我明明插了盘，课没了"。
     */
    private val SKIP_DIRS = setOf(
        "Android", "LOST.DIR", "\$RECYCLE.BIN", "System Volume Information",
    )

    /**
     * 是否拿到了「所有文件访问权限」。
     *
     * Android 11+ 起，分区存储会让 `File.listFiles()` 在 U 盘 / 内置存储上直接返回 null，
     * 只有 MANAGE_EXTERNAL_STORAGE 才能按路径遍历。拿不到时不是"没有文件"，
     * 必须明确告诉用户去授权，否则用户会以为软件坏了。
     */
    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        } else true

    /** 当前可见的本地挂载点（顺序：内置存储在前，U 盘按名字排） */
    fun hosts(ctx: Context): List<Cfg> {
        val meta = volumeMeta(ctx)

        val built = LinkedHashMap<String, Cfg>()

        // ① 内置存储：固定 id `l0`，跨启动稳定（它不会消失，做成"永远在的那一条"）
        val internal = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
        if (internal != null && internal.isDirectory) {
            built["l0"] = Cfg(
                id = "l0",
                proto = Proto.LOCAL,
                name = "本地存储",
                host = internal.absolutePath,
            )
        }

        // ② U 盘 / SD 卡：扫 /storage 与 /mnt/media_rw。
        // 不用 StorageManager.getStorageVolumes() 当唯一来源 —— 部分电视固件（含本项目的
        // TCL Android 11）在那里返回空列表，U 盘插着也不出现。目录扫描是兜底。
        val roots = listOf(File("/storage"), File("/mnt/media_rw"))
        val seen = HashSet<String>()
        for (root in roots) {
            val kids = runCatching { root.listFiles() }.getOrNull() ?: continue
            for (d in kids) {
                if (!d.isDirectory) continue
                if (d.name == "emulated" || d.name == "self") continue
                val path = d.absolutePath
                if (!seen.add(path)) continue
                // 和内置存储指向同一个目录时不重复列出
                if (internal != null && runCatching { d.canonicalPath == internal.canonicalPath }.getOrDefault(false)) continue
                // /mnt/media_rw 与 /storage 常指向同一块盘，按 canonicalPath 去重
                val canon = runCatching { d.canonicalPath }.getOrDefault(path)
                if (built.values.any { runCatching { File(it.host).canonicalPath == canon }.getOrDefault(false) }) continue

                val info = meta[path]
                val id = idOf(d, info)
                built[id] = Cfg(
                    id = id,
                    proto = Proto.LOCAL,
                    name = usbName(info?.desc, id),
                    host = path,
                )
            }
        }

        // 同名 U 盘（两根一模一样的闪迪）要在左树上分得清
        val out = ArrayList<Cfg>()
        val nameCount = HashMap<String, Int>()
        for (c in built.values) {
            val n = (nameCount[c.name] ?: 0) + 1
            nameCount[c.name] = n
            out += if (n == 1) c else c.copy(name = "${c.name} ($n)")
        }
        return out.toList()
    }

    private data class VolInfo(val desc: String?, val uuid: String?)

    /**
     * 挂载点 → 卷信息（显示名 / UUID），键是挂载点绝对路径。
     *
     * `StorageVolume.getDirectory()` 与 `getUuid()` 是 API 30 / 24；更早的版本用反射取
     * 隐藏方法 `getPath()`。全部拿不到也不算错 —— 会退回用目录名派生 id，
     * 只是「两根同型号 U 盘」可能分不清。
     */
    private fun volumeMeta(ctx: Context): Map<String, VolInfo> {
        val map = HashMap<String, VolInfo>()
        runCatching {
            val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return emptyMap()
            val vols = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) sm.storageVolumes else emptyList()
            for (v in vols) {
                val dir: File? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    v.directory
                } else {
                    runCatching { v.javaClass.getMethod("getPath").invoke(v) as? String }
                        .getOrNull()?.let { File(it) }
                }
                if (dir == null) continue
                val desc = runCatching { v.getDescription(ctx) }.getOrNull()
                val uuid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    runCatching { v.uuid }.getOrNull()
                } else null
                map[dir.absolutePath] = VolInfo(desc, uuid)
            }
        }
        return map
    }

    /**
     * 稳定的源 id：`l` + 卷 UUID（去掉非字母数字）。
     *
     * 同一根 U 盘每次插入必须得到同一个 id，否则上一次的收藏 / 书架路径
     * （`@l_1a2b\课程`）就指向不存在的源，用户看到的是"U 盘一拔一插，书架全空了"。
     */
    private fun idOf(dir: File, info: VolInfo?): String {
        val seed = info?.uuid?.takeIf { it.isNotBlank() }
            ?: info?.desc?.takeIf { it.isNotBlank() }
            ?: dir.name
        val clean = seed.filter { it.isLetterOrDigit() }.lowercase()
        return "l_" + clean.ifBlank { "usb" }
    }

    private fun usbName(desc: String?, id: String): String {
        val d = desc?.trim().orEmpty()
        // 描述本身是 "USB drive" 这类泛称时，补上 id 尾部便于区分
        val tail = id.removePrefix("l_").takeLast(4).uppercase()
        return if (d.isEmpty()) "U盘 · $tail" else "U盘 · $d"
    }

    /**
     * 列一层本地目录（`inner` 为挂载点内相对路径，`\` 分隔）。
     *
     * 走 `File.listFiles()` 而不是 MediaStore：我们要的是**目录结构**（课程 = 一个文件夹），
     * MediaStore 只给扁平的媒体条目，拼不出课程树。
     */
    fun entries(cfg: Cfg, inner: String): List<SmbEntry> {
        val root = File(cfg.host)
        val dir = if (inner.isBlank()) root else File(root, inner.trim('\\', '/').replace('\\', '/'))
        val kids = runCatching { dir.listFiles() }.getOrNull() ?: return emptyList()
        val base = inner.trim('\\', '/').replace('/', '\\')
        return kids.asSequence()
            .filter { !it.name.startsWith(".") }
            .filter { !(it.isDirectory && SKIP_DIRS.contains(it.name)) }
            .map { f ->
                SmbEntry(
                    name = f.name,
                    isDir = f.isDirectory,
                    size = if (f.isDirectory) 0L else runCatching { f.length() }.getOrDefault(0L),
                    modifiedMs = runCatching { f.lastModified() }.getOrDefault(0L),
                    path = if (base.isEmpty()) f.name else "$base\\${f.name}",
                )
            }
            .toList()
    }

    /** 这个挂载点现在还在不在（U 盘被拔掉后不再重扫，避免一直报错） */
    fun isPresent(cfg: Cfg): Boolean = runCatching { File(cfg.host).isDirectory }.getOrDefault(false)
}
