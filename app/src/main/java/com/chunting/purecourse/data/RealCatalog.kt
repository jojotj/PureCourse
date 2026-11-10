package com.chunting.purecourse.data

import androidx.compose.ui.graphics.Color

/**
 * 真实 SMB 目录层（tv5）。
 *
 * 这一层取代 Demo.kt 的假课程 / 假目录树：**书架上的每一门课、详情页的每一集、
 * 浏览页的每一行，都必须来自共享里真实存在的目录与文件**。界面上不再出现
 * 「高等数学（上）」「第 1 讲 课程导论与学习方法」「\\192.168.1.20\courses」这类编造文案。
 *
 * 约定：所有路径都是「共享内相对路径」，`\` 分隔，与 [SmbSession.list] 的
 * [SmbEntry.path] 完全一致，可直接交给 [SourceStore.Cfg.uriOf] 拼成 smb:// URI。
 *
 * 本对象只做纯计算与网络读取，不持有 UI 状态；扫描结果由 AppState 一次性发布到主线程，
 * 避免后台线程与 Compose snapshot 并发写。
 */
data class Episode(
    /** 给人看的集名：真实文件名去掉扩展名与尾部 (数字ID) */
    val name: String,
    /** 共享内相对路径，用于拼 smb:// */
    val path: String,
    val size: Long,
)

object RealCatalog {

    // ---- 预置配置由 flavor 源集提供（src/local 含真实值，src/clean 全空）----
    // 这样发布包里连字符串常量都不存在，反编译也拿不到本机共享信息。
    val DEFAULT_HOST: String get() = BuiltinPresets.HOST
    val DEFAULT_SHARE: String get() = BuiltinPresets.SHARE
    val DEFAULT_USER: String get() = BuiltinPresets.USER
    val DEFAULT_PASS: String get() = BuiltinPresets.PASS
    val DEFAULT_ROOT: String get() = BuiltinPresets.ROOT

    /** 添加数据源页的「真实快捷位置」——每一条都在本机共享里核实过存在 */
    data class Shortcut(
        val label: String,
        val host: String,
        val share: String,
        val root: String,
        val user: String,
        val pass: String,
    ) {
        /** 表单里的「共享 / 路径」字段：第一段是共享名 */
        val pathText: String get() = if (root.isEmpty()) share else "$share/$root"
    }

    /** 预置快捷位置同样由 flavor 源集提供：自用包四条真实位置，发布包为空表 */
    val shortcuts: List<Shortcut> get() = BuiltinPresets.shortcuts

    private val VIDEO = Regex(
        "\\.(flv|mp4|mkv|avi|ts|wmv|mov|m4v|webm|rmvb|mp3|m4a|wav)$",
        RegexOption.IGNORE_CASE,
    )
    private val TRAILING_ID = Regex("\\s*[（(]\\d+[)）]\\s*$")

    fun isVideo(name: String): Boolean = VIDEO.containsMatchIn(name)

    /** 「第10节」必须排在「第2节」后面：数字段左补零再整体比较 */
    fun naturalKey(name: String): String =
        Regex("\\d+").replace(name) { it.value.padStart(10, '0') }

    /** `第1节：引导片(18239).flv` → `第1节：引导片` */
    fun titleOf(fileName: String): String =
        fileName.substringBeforeLast('.').replace(TRAILING_ID, "").trim().ifEmpty { fileName }

    fun sizeText(size: Long): String = when {
        size >= 1L shl 30 -> "%.1f GB".format(size / 1073741824.0)
        size >= 1L shl 20 -> "%.0f MB".format(size / 1048576.0)
        else -> "%.0f KB".format(size / 1024.0)
    }

    val COVERS = listOf(
        Color(0xFF3E6DD6) to Color(0xFF16224A),
        Color(0xFFB0553E) to Color(0xFF4A1D14),
        Color(0xFF4C9E7A) to Color(0xFF12362A),
        Color(0xFF8A5BD6) to Color(0xFF2A1A4A),
        Color(0xFFD6913E) to Color(0xFF4A2C10),
        Color(0xFF3E9ED6) to Color(0xFF10303F),
        Color(0xFFC04C74) to Color(0xFF3D1226),
        Color(0xFF6D7B8C) to Color(0xFF232A33),
    )

    fun coverColors(i: Int): Pair<Color, Color> = COVERS[((i % COVERS.size) + COVERS.size) % COVERS.size]

    /** 一次扫描的完整快照，由 AppState 一次性发布 */
    data class Snapshot(
        val courses: List<Course>,
        val dirs: Map<Int, String>,
        val episodes: Map<Int, List<Episode>>,
        val fs: Map<String, FsNode>,
        val rootKey: String,
        val err: String? = null,
    )

    /**
     * 默认数据源 = **整机模式**（share 留空）。
     *
     * 之前默认写死一个共享，结果同一台电脑要开好几个数据源才能换共享。
     * 现在默认就是「这台 IP 上的所有共享都是根目录」，左树一眼看全。
     */
    fun defaultCfg(): SourceStore.Cfg = SourceStore.Cfg(
        host = DEFAULT_HOST,   // 发布包为空串，自用包为本机 IP
        share = "",
        user = DEFAULT_USER,
        pass = DEFAULT_PASS,
        root = "",
    )

    /** 统一路径 → 面包屑上显示的短名 */
    fun labelOf(cfg: SourceStore.Cfg, rel: String): String {
        if (cfg.proto == Proto.LOCAL) {
            if (rel.isEmpty()) return cfg.displayName()
            return rel.substringAfterLast('\\')
        }
        if (cfg.proto == Proto.DAV) {
            if (rel.isEmpty()) return cfg.name.ifBlank { cfg.host }.ifBlank { "WebDAV" }
            return rel.substringAfterLast('\\')
        }
        if (rel.isEmpty()) return if (cfg.wholeHost) "全部共享" else cfg.share
        return rel.substringAfterLast('\\').ifEmpty { cfg.share }
    }

    /**
     * 列一层目录（浏览页下钻时后台调用）。
     *
     * 整机模式下 rel 的第一段是共享名：先 [SmbSession.useShare] 切过去，
     * 再把剩余部分交给 [SmbSession.list]，返回的路径统一补回共享前缀。
     */
    fun listDir(cfg: SourceStore.Cfg, rel: String, courseIdByPath: Map<String, Int>): FsNode {
        val (share, inner) = cfg.splitRel(rel)
        if (cfg.wholeHost && share.isBlank()) {
            // 根层：共享列表由 scan 填好，这里只做兜底
            val (err, shares) = SmbShares.list(cfg.host, cfg.user, cfg.pass, cfg.port)
            val items = if (err != null) emptyList() else shares.map {
                FsItem(ItemType.DIR, it.name, to = it.name,
                    countText = it.remark.ifBlank { "共享" }, relPath = it.name)
            }
            return FsNode(rel, "全部共享", items)
        }
        // 本地源和 SMB 走同一个出口：只换「怎么列目录」，后面的课程识别、排序、
        // 路径拼接一模一样 —— 这是"平板端逻辑不用改"的关键。
        val entries = entriesOf(cfg)(share, inner)
        fun full(p: String): String = if (cfg.wholeHost) "$share\\$p" else p

        val d = entries.filter { it.isDir }.sortedBy { naturalKey(it.name) }.map { e ->
            FsItem(
                ItemType.DIR, e.name, to = full(e.path),
                course = courseIdByPath[full(e.path)],
                countText = "文件夹", relPath = full(e.path),
            )
        }
        val v = entries.filter { !it.isDir && isVideo(it.name) }
            .sortedBy { naturalKey(it.name) }.map { e ->
                FsItem(ItemType.VID, titleOf(e.name), countText = sizeText(e.size),
                    relPath = full(e.path))
            }
        return FsNode(rel, labelOf(cfg, rel), d + v)
    }

    /**
     * 扫描数据源起始目录：把「直接含视频文件的一级子目录」当成一门课程。
     * 若起始目录本身就是一摞视频（例如「幼儿园舞蹈」），则它自己算一门课。
     *
     * 每个子目录只 list 一次，结果同时用于生成课程剧集与浏览页节点，不重复往返。
     * 必须在后台线程调用。
     */
    fun scan(cfg: SourceStore.Cfg, extraShelved: List<String> = emptyList()): Snapshot {
        if (cfg.proto == Proto.LOCAL) return scanLocal(cfg, extraShelved)
        val base = if (cfg.wholeHost) scanWholeHost(cfg) else scanSingle(cfg)
        if (base.err != null || extraShelved.isEmpty()) return base
        return addShelved(cfg, base, extraShelved)
    }

    /**
     * 列一层的统一出口：`share` = 共享名（本地源忽略），`inner` = 共享内相对路径。
     *
     * SMB 与本地存储的**唯一差别**就在这个函数里；课程识别、集数排序、节点构建
     * （[buildInto]）完全共用，所以本地源不需要另写一套扫描器。
     */
    private fun entriesOf(cfg: SourceStore.Cfg): (String, String) -> List<SmbEntry> = { share, inner ->
        when {
            cfg.proto == Proto.LOCAL -> LocalStore.entries(cfg, inner)
            cfg.wholeHost -> SmbSession.listIn(share, inner)
            else -> SmbSession.list(inner)
        }
    }

    /**
     * 扫一个本地挂载点（内置存储 / U 盘）。
     *
     * 与 SMB 的规则一模一样：**一级子目录里直接含视频 → 一门课**；
     * 挂载点根自己就是一摞视频时，根本身算一门课。
     */
    fun scanLocal(cfg: SourceStore.Cfg, extraShelved: List<String> = emptyList()): Snapshot {
        val noPerm = "未授予「所有文件访问」权限，读不到 U 盘（到 设置 → 本地存储 里授权）"
        if (!LocalStore.hasAllFilesAccess()) {
            return Snapshot(emptyList(), emptyMap(), emptyMap(), emptyMap(), "", noPerm)
        }
        if (!LocalStore.isPresent(cfg)) {
            return Snapshot(emptyList(), emptyMap(), emptyMap(), emptyMap(), "", "存储位置不可用（U 盘可能已拔出）")
        }
        val top = entriesOf(cfg)("", "")
        val base = buildSnapshot(cfg, "", top, prefix = "")
        return if (extraShelved.isEmpty()) base else addShelved(cfg, base, extraShelved)
    }

    /** 单一共享模式：扫起始目录下的一级子目录 */
    private fun scanSingle(cfg: SourceStore.Cfg): Snapshot {
        val connErr = SmbSession.connect(cfg.host, cfg.share, cfg.user, cfg.pass, cfg.port)
        if (connErr != null) {
            return Snapshot(emptyList(), emptyMap(), emptyMap(), emptyMap(), cfg.root, connErr)
        }
        val top = runCatching { SmbSession.list(cfg.root) }.getOrElse {
            return Snapshot(
                emptyList(), emptyMap(), emptyMap(), emptyMap(), cfg.root,
                "读不到起始目录「${cfg.root.ifEmpty { cfg.share }}」：${it.message}",
            )
        }
        return buildSnapshot(cfg, cfg.root, top, prefix = "")
    }

    /**
     * 把用户手动加入书架、但扫描时没被自动识别的目录补成课程。
     *
     * 落盘的是统一路径（例如 `课程共享\05 听力课程`），冷启动时在这里重新 list 一次，
     * 免得「加入书架」只活到进程结束。
     */
    private fun addShelved(
        cfg: SourceStore.Cfg,
        snap: Snapshot,
        paths: List<String>,
    ): Snapshot {
        val courses = snap.courses.toMutableList()
        val dirs = snap.dirs.toMutableMap()
        val eps = snap.episodes.toMutableMap()
        val nodes = snap.fs.toMutableMap()

        for (rel in paths) {
            if (dirs.values.contains(rel)) continue
            val (share, inner) = cfg.splitRel(rel)
            val entries = runCatching { entriesOf(cfg)(share, inner) }.getOrDefault(emptyList())
            val vids = entries.filter { !it.isDir && isVideo(it.name) }
                .sortedBy { naturalKey(it.name) }
            if (vids.isEmpty()) continue
            fun full(p: String): String = if (cfg.wholeHost) "$share\\$p" else p
            val id = courses.size
            val (a, b) = coverColors(id)
            val title = rel.substringAfterLast('\\').ifEmpty { share }
            courses += Course(
                id = id, name = title, abbr = title.take(2), c1 = a, c2 = b,
                curEp = 1, lastEpName = "尚未开始",
                lastAt = 0L, lastTxt = "尚未播放", py = title, epCount = vids.size,
            )
            dirs[id] = rel
            eps[id] = vids.map { Episode(titleOf(it.name), full(it.path), it.size) }
            nodes[rel] = FsNode(rel, title, vids.map { v ->
                FsItem(ItemType.VID, titleOf(v.name), countText = sizeText(v.size),
                    relPath = full(v.path))
            })
        }
        return snap.copy(courses = courses, dirs = dirs, episodes = eps, fs = nodes)
    }

    /**
     * 整机模式：一个 IP 就是一个数据源，根节点是**这台主机上所有可浏览的共享**。
     *
     * 之前是「一个数据源 = 一个共享」，于是同一台电脑要开多个数据源，
     * 换个共享还得进编辑页重填 —— 那是把 SMB 的 `\\host\share` 结构硬压成了单根目录。
     * 现在共享本身就是浏览树的第一层，左边一次就能看到全部 4 个。
     *
     * 统一路径约定：`共享名\共享内路径`（例如 `课程共享\02 乐理课程`），
     * 这样播放/收藏/加书架都只需要一条 rel，不用再分两套字段。
     */
    private fun scanWholeHost(cfg: SourceStore.Cfg): Snapshot {
        val connErr = SmbSession.connect(cfg.host, "", cfg.user, cfg.pass, cfg.port)
        if (connErr != null) {
            return Snapshot(emptyList(), emptyMap(), emptyMap(), emptyMap(), "", connErr)
        }
        val (err, shares) = SmbShares.list(cfg.host, cfg.user, cfg.pass, cfg.port)
        if (err != null) {
            return Snapshot(emptyList(), emptyMap(), emptyMap(), emptyMap(), "", err)
        }
        if (shares.isEmpty()) {
            return Snapshot(emptyList(), emptyMap(), emptyMap(), emptyMap(), "", "这台主机没有可浏览的共享")
        }

        val courses = ArrayList<Course>()
        val dirs = HashMap<Int, String>()
        val eps = HashMap<Int, List<Episode>>()
        val nodes = HashMap<String, FsNode>()
        val rootItems = ArrayList<FsItem>()

        /** 处理一个共享：返回 false = 这次没读到内容（可能是真空，也可能是切共享超时） */
        fun takeShare(sh: SmbShares.Share): Boolean {
            val top = runCatching { SmbSession.listIn(sh.name, "") }.getOrDefault(emptyList())
            if (top.isEmpty()) return false
            // relDir 必须是共享名本身：buildInto 用它当节点 key，写成 "" 会让
            // 4 个共享的节点互相覆盖，最后只剩一个（左侧树也就展不开子层）
            buildInto(
                cfg, prefix = sh.name, relDir = sh.name, top = top, shareName = sh.name,
                courses = courses, dirs = dirs, eps = eps, nodes = nodes,
            )
            val videos = top.count { !it.isDir && isVideo(it.name) }
            val nDirs = top.count { it.isDir }
            rootItems += FsItem(
                ItemType.DIR, sh.name, to = sh.name,
                // 计数要数**真实文件夹数**，不是"被识别成课程的目录数"：
                // 之前用 sub（课程数），顶层三个目录都不直接含视频的共享就会显示成"共享"，
                // 连带左侧树判成不可展开、图标跟别的共享不一样。
                countText = when {
                    nDirs > 0 -> "$nDirs 个文件夹"
                    videos > 0 -> "$videos 个视频"
                    else -> sh.remark.ifBlank { "空共享" }
                },
                relPath = sh.name,
            )
            return true
        }

        val empty = ArrayList<SmbShares.Share>()
        for (sh in shares) if (!takeShare(sh)) empty += sh

        // 切共享偶发超时（实测和 WebDAV 扫描并发时更容易发生）：一次失败就记成"空共享"，
        // 会整段丢掉那个共享下的所有课程，用户看到的是"我的乐理课全没了"。
        // 这里对没读到内容的共享再补一次；真空共享重试也只是多一次毫秒级调用。
        for (sh in empty) {
            runCatching { Thread.sleep(400) }
            if (!takeShare(sh)) {
                rootItems += FsItem(
                    ItemType.DIR, sh.name, to = sh.name,
                    countText = "空共享", relPath = sh.name,
                )
            }
        }

        nodes[""] = FsNode("", "全部共享", rootItems)
        return Snapshot(courses, dirs, eps, nodes, "")
    }

    /** 单一共享模式：把起始目录下的一级子目录当成课程 */
    private fun buildSnapshot(
        cfg: SourceStore.Cfg,
        rootRel: String,
        top: List<SmbEntry>,
        prefix: String,
    ): Snapshot {
        val courses = ArrayList<Course>()
        val dirs = HashMap<Int, String>()
        val eps = HashMap<Int, List<Episode>>()
        val nodes = HashMap<String, FsNode>()
        buildInto(cfg, prefix, rootRel, top, cfg.share, courses, dirs, eps, nodes)
        return Snapshot(courses, dirs, eps, nodes, rootRel)
    }

    /**
     * 把一层目录展开成「课程 + 节点」，多个共享复用同一段逻辑。
     * @param prefix 统一路径前缀（整机模式 = 共享名，单一共享模式 = ""）
     * @param relDir 本层目录的统一路径
     * @return 本层里被识别成课程的目录数
     */
    private fun buildInto(
        cfg: SourceStore.Cfg,
        prefix: String,
        relDir: String,
        top: List<SmbEntry>,
        shareName: String,
        courses: MutableList<Course>,
        dirs: MutableMap<Int, String>,
        eps: MutableMap<Int, List<Episode>>,
        nodes: MutableMap<String, FsNode>,
    ): Int {
        fun join(name: String): String = if (relDir.isEmpty()) name else "$relDir\\$name"

        val dirEntries = top.filter { it.isDir }.sortedBy { naturalKey(it.name) }
        val looseVids = top.filter { !it.isDir && isVideo(it.name) }.sortedBy { naturalKey(it.name) }
        val dirItems = ArrayList<FsItem>()
        var made = 0

        for (d in dirEntries) {
            val path = join(d.name)
            val kids = runCatching { entriesOf(cfg)(shareName, d.path) }.getOrDefault(emptyList())
            val vids = kids.filter { !it.isDir && isVideo(it.name) }.sortedBy { naturalKey(it.name) }

            nodes[path] = FsNode(
                path, d.name,
                kids.filter { it.isDir }.sortedBy { naturalKey(it.name) }.map { sub ->
                    FsItem(ItemType.DIR, sub.name, to = join("${d.name}\\${sub.name}"), countText = "文件夹",
                        relPath = join("${d.name}\\${sub.name}"))
                } + vids.map { v ->
                    FsItem(ItemType.VID, titleOf(v.name), countText = sizeText(v.size),
                        relPath = join("${d.name}\\${v.name}"))
                },
            )

            val id = if (vids.isEmpty()) null else courses.size
            if (id != null) {
                val list = vids.map { Episode(titleOf(it.name), join("${d.name}\\${it.name}"), it.size) }
                val (a, b) = coverColors(id)
                courses += Course(
                    id = id, name = d.name, abbr = d.name.take(2), c1 = a, c2 = b,
                    curEp = 1, lastEpName = "尚未开始",
                    lastAt = 0L, lastTxt = "尚未播放", py = d.name, epCount = list.size,
                )
                dirs[id] = path
                eps[id] = list
                made++
            }
            dirItems += FsItem(
                ItemType.DIR, d.name, to = path, course = id,
                countText = if (vids.isEmpty()) "文件夹" else "${vids.size} 个视频",
                relPath = path,
            )
        }

        val looseItems = looseVids.map { v ->
            FsItem(ItemType.VID, titleOf(v.name), countText = sizeText(v.size),
                relPath = join(v.name))
        }

        // 本层自己就是一摞视频：把它作为一门课，否则书架会是空的
        if (made == 0 && looseVids.isNotEmpty()) {
            val list = looseVids.map { Episode(titleOf(it.name), join(it.name), it.size) }
            val id = courses.size
            val (a, b) = coverColors(id)
            val title = labelOfDisp(cfg, prefix, relDir)
            courses += Course(
                id = id, name = title, abbr = title.take(2), c1 = a, c2 = b,
                curEp = 1, lastEpName = "尚未开始",
                lastAt = 0L, lastTxt = "尚未播放", py = title, epCount = list.size,
            )
            dirs[id] = relDir
            eps[id] = list
            made++
        }

        nodes[relDir] = FsNode(relDir, labelOfDisp(cfg, prefix, relDir), dirItems + looseItems)
        return made
    }

    /** 统一路径 → 显示名（整机模式下去掉共享前缀） */
    private fun labelOfDisp(cfg: SourceStore.Cfg, prefix: String, relDir: String): String {
        if (cfg.proto == Proto.LOCAL) {
            return if (relDir.isEmpty()) cfg.displayName() else relDir.substringAfterLast('\\')
        }
        if (relDir.isEmpty()) return if (cfg.wholeHost) (prefix.ifEmpty { "全部共享" }) else cfg.share
        return relDir.substringAfterLast('\\')
    }

    /**
     * 浏览页从数据源根到某个目录的路径链（用于面包屑与返回分级）。
     * 整机模式下根是空串，第一段就是共享名。
     */
    fun stackFor(root: String, rel: String): List<String> {
        if (rel == root || !rel.startsWith(root)) return listOf(root)
        val rest = rel.removePrefix(root).trim('\\')
        if (rest.isEmpty()) return listOf(root)
        val out = ArrayList<String>()
        out += root
        var cur = root
        rest.split('\\').forEach { seg ->
            cur = if (cur.isEmpty()) seg else "$cur\\$seg"
            out += cur
        }
        return out
    }
}
