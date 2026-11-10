package com.chunting.purecourse.data

import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * WebDAV 目录层：产出与 [RealCatalog] **完全同构**的 [RealCatalog.Snapshot]。
 *
 * 这样书架、详情、浏览、收藏、播放都不需要认识「这是 WebDAV」，
 * 只需要 `Cfg.uriOf()` 拼出 http(s) URL 即可。路径对内一律用 `\` 分隔。
 *
 * ## 为什么不再「开机全树扫描」
 *
 * 旧版为了自动识别所有课程，冷启动会对整棵树做 BFS（上限 420 次 PROPFIND）。
 * 实测单次 PROPFIND 在 115/OpenList 上要几百毫秒到数秒，420 次就是几分钟：
 *
 *  - 启动后书架几分钟内一片空白；
 *  - 扫描线程和浏览请求抢同一条 HTTP 通道，用户点进文件夹要排队；
 *  - 扫描结果每回来一批就整体替换 `fs`，正在看的目录会被抹掉再重建。
 *
 * 现在的规则只有三条，全部是「用户点哪层，就读哪层」：
 *
 *  1. 启动只读**根目录一层**（1 次请求），用来铺出浏览页第一屏；
 *  2. 书架上的每一门课，只读**它自己那一层**，看有没有视频决定是否建卡，
 *     **绝不递归子目录**（层级不齐是常态：有的 2 层到视频，有的 3 层）；
 *  3. 点开书时先用手里的集数立刻显示，再后台重读一次这一层（见 AppState.refreshCourseDir）。
 *
 * 没有任何预热、没有后台全量扫描 —— 100 个子目录也不会再拖死启动。
 */
object DavCatalog {

    /** 书架目录一层并发的路数。服务端多为单机 NAS / OpenList，6 路足够顶满上行。 */
    private const val WORKERS = 6

    private val pool = Executors.newFixedThreadPool(WORKERS)

    fun scan(
        cfg: SourceStore.Cfg,
        /** 用户手动加入书架的目录（源内相对路径，无源前缀） */
        extraShelved: List<String> = emptyList(),
        onProgress: ((Int) -> Unit)? = null,
        /**
         * 回调一次「本地已知的那部分」：卡片按记得的目录名先铺出来，
         * 真实集数等目录读回来再补。冷目录单次读取可能 8 秒，不该让用户盯着空书架等。
         */
        onPartial: ((RealCatalog.Snapshot) -> Unit)? = null,
    ): RealCatalog.Snapshot {
        val base = cfg.baseUrl
        if (base.isBlank()) {
            return RealCatalog.Snapshot(emptyList(), emptyMap(), emptyMap(), emptyMap(), "", "WebDAV 地址为空")
        }
        val rootRel = cfg.root.trim('\\', '/')
        val fallbackName = cfg.name.ifBlank { cfg.host }.ifBlank { "WebDAV" }

        val courses = ArrayList<Course>()
        val dirs = HashMap<Int, String>()
        val eps = HashMap<Int, List<Episode>>()
        val nodes = HashMap<String, FsNode>()
        val courseIdByPath = HashMap<String, Int>()
        var err: String? = null

        val shelved = extraShelved.map { it.trim('\\', '/') }.filter { it.isNotEmpty() }.distinct()

        // ---------- ① 根目录一层（唯一的“启动请求”） ----------
        val rootEntries = DavClient.list(base, cfg.user, cfg.pass, cfg.ignoreCert, rootRel).getOrNull()
        if (rootEntries == null) err = "读不到目录：$fallbackName"
        val rootVids = rootEntries.orEmpty()
            .filter { !it.isDir && RealCatalog.isVideo(it.name) }
            .sortedBy { RealCatalog.naturalKey(it.name) }
        if (rootEntries != null) nodes[rootRel] = nodeOf(rootRel, rootEntries, fallbackName)

        // ---------- ② 先按“本地记得的书架目录”铺卡片（0 次请求） ----------
        shelved.forEachIndexed { i, rel ->
            val nm = rel.substringAfterLast('\\')
            val (a, b) = RealCatalog.coverColors(i)
            courses += Course(
                id = i, name = nm, abbr = nm.take(2), c1 = a, c2 = b,
                curEp = 1, lastEpName = "尚未开始",
                lastAt = 0L, lastTxt = "尚未播放", py = nm, epCount = 0,
            )
            dirs[i] = rel
            eps[i] = emptyList()
            courseIdByPath[rel] = i
        }
        if (onPartial != null) {
            onPartial(snapshotOf(courses, dirs, eps, nodes, courseIdByPath, rootRel, err))
        }

        // ---------- ③ 每个书架目录各读一层，判有无视频 ----------
        // 只读这一层，**绝不递归子目录**：递归会顺带拉几十上百个子目录，
        // 那正是上一版“加一门课要等几分钟”的根源。
        val listed = fetchAll(base, cfg, shelved)

        courses.clear(); dirs.clear(); eps.clear(); courseIdByPath.clear()
        shelved.forEach { rel ->
            val vids = listed[rel].orEmpty()
                .filter { !it.isDir && RealCatalog.isVideo(it.name) }
                .sortedBy { RealCatalog.naturalKey(it.name) }
            // 这一层没有视频 = 不是“含视频那层”，不建卡（调用方已提示用户去下一层加）
            if (vids.isEmpty()) return@forEach
            val cid = courses.size
            buildCourse(
                cid, rel, rel.substringAfterLast('\\'), vids,
                courses, dirs, eps, courseIdByPath,
            )
            nodes[rel] = FsNode(rel, rel.substringAfterLast('\\'), vids.map { v ->
                FsItem(
                    ItemType.VID, RealCatalog.titleOf(v.name),
                    countText = RealCatalog.sizeText(v.size), relPath = join(rel, v.name),
                )
            })
        }

        // 根目录自己就是一摞视频：当成一门课（与 SMB 的 looseVids 分支一致）
        if (rootVids.isNotEmpty()) {
            buildCourse(
                courses.size, rootRel, fallbackName, rootVids,
                courses, dirs, eps, courseIdByPath,
            )
        }

        onProgress?.invoke(courses.size)
        return snapshotOf(courses, dirs, eps, nodes, courseIdByPath, rootRel, err)
    }

    /**
     * 用「当前已知的部分」拼一个快照。
     *
     * ⚠️ 必须**复制**集合：回调出去的 Snapshot 如果共享同一份可变 Map/List，
     * 后续的 add 会改到已经交给 UI 的对象，Compose 那边会看到“列表自己变了”，
     * 表现为卡片闪烁或数量错乱。
     */
    private fun snapshotOf(
        courses: List<Course>,
        dirs: Map<Int, String>,
        eps: Map<Int, List<Episode>>,
        nodes: Map<String, FsNode>,
        courseIdByPath: Map<String, Int>,
        rootKey: String,
        err: String?,
    ): RealCatalog.Snapshot {
        val fixed = nodes.mapValues { (_, n) ->
            FsNode(
                n.key, n.name,
                n.items.map { fi ->
                    fi.copy(course = fi.course ?: fi.relPath?.let { courseIdByPath[it] })
                },
            )
        }
        return RealCatalog.Snapshot(
            courses.toList(), HashMap(dirs), HashMap(eps), HashMap(fixed), rootKey, err,
        )
    }

    /** 把「一个含视频的目录」登记成一门课（课程 / 目录 / 剧集 / 路径反查表一起写） */
    private fun buildCourse(
        cid: Int,
        rel: String,
        name: String,
        vids: List<DavEntry>,
        courses: MutableList<Course>,
        dirs: MutableMap<Int, String>,
        eps: MutableMap<Int, List<Episode>>,
        courseIdByPath: MutableMap<String, Int>,
    ) {
        val list = vids.map { Episode(RealCatalog.titleOf(it.name), join(rel, it.name), it.size) }
        val (a, b) = RealCatalog.coverColors(cid)
        val nm = name.ifBlank { rel.substringAfterLast('\\') }
        courses += Course(
            id = cid, name = nm, abbr = nm.take(2), c1 = a, c2 = b,
            curEp = 1, lastEpName = "尚未开始",
            lastAt = 0L, lastTxt = "尚未播放", py = nm, epCount = list.size,
        )
        dirs[cid] = rel
        eps[cid] = list
        courseIdByPath[rel] = cid
    }

    /** 并发各读一层。返回「源内路径 → 条目」，读失败的值为 null。 */
    private fun fetchAll(base: String, cfg: SourceStore.Cfg, rels: List<String>): Map<String, List<DavEntry>?> {
        if (rels.isEmpty()) return emptyMap()
        val out = HashMap<String, List<DavEntry>?>(rels.size)
        if (rels.size == 1) {
            out[rels[0]] = DavClient.list(base, cfg.user, cfg.pass, cfg.ignoreCert, rels[0]).getOrNull()
            return out
        }
        val futures = rels.map { r ->
            pool.submit(
                Callable {
                    r to DavClient.list(base, cfg.user, cfg.pass, cfg.ignoreCert, r).getOrNull()
                }
            )
        }
        futures.forEach { f -> runCatching { f.get() }.getOrNull()?.let { (r, e) -> out[r] = e } }
        rels.forEach { if (!out.containsKey(it)) out[it] = null }
        return out
    }

    /**
     * 按需补一层（浏览页下钻 / 收藏页展开 / 点开书后台刷新共用）。
     * 每次只发 **1 个** PROPFIND，不做任何预取。
     */
    fun listDir(
        cfg: SourceStore.Cfg,
        rel: String,
        courseIdByPath: Map<String, Int>,
        entries: List<DavEntry>? = null,
    ): FsNode {
        val list = entries
            ?: DavClient.list(cfg.baseUrl, cfg.user, cfg.pass, cfg.ignoreCert, rel).getOrDefault(emptyList())
        return nodeOf(rel, list, cfg.name.ifBlank { cfg.host }, courseIdByPath)
    }

    /** 把一层的条目拼成 [FsNode]：文件夹在前、视频在后，都按自然序 */
    private fun nodeOf(
        rel: String,
        entries: List<DavEntry>,
        fallbackName: String,
        courseIdByPath: Map<String, Int> = emptyMap(),
    ): FsNode {
        val label = nameOf(rel, fallbackName)
        val d = entries.filter { it.isDir }.sortedBy { RealCatalog.naturalKey(it.name) }.map { e ->
            val p = join(rel, e.name)
            FsItem(
                ItemType.DIR, e.name, to = p, course = courseIdByPath[p],
                countText = "文件夹", relPath = p,
            )
        }
        val v = entries.filter { !it.isDir && RealCatalog.isVideo(it.name) }
            .sortedBy { RealCatalog.naturalKey(it.name) }.map { e ->
                FsItem(
                    ItemType.VID, RealCatalog.titleOf(e.name),
                    countText = RealCatalog.sizeText(e.size), relPath = join(rel, e.name),
                )
            }
        return FsNode(rel, label, d + v)
    }

    private fun nameOf(rel: String, fallback: String): String =
        if (rel.isEmpty()) fallback else rel.substringAfterLast('\\')

    private fun join(parent: String, name: String): String =
        if (parent.isEmpty()) name else "$parent\\$name"
}
