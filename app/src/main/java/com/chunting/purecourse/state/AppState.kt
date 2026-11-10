package com.chunting.purecourse.state

import android.os.Handler
import com.chunting.purecourse.App
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.chunting.purecourse.data.Course
import com.chunting.purecourse.data.Endpoint
import com.chunting.purecourse.data.Episode
import com.chunting.purecourse.data.Fav
import com.chunting.purecourse.data.FsItem
import com.chunting.purecourse.data.FsNode
import com.chunting.purecourse.data.ItemType
import com.chunting.purecourse.data.Proto
import com.chunting.purecourse.data.RealCatalog
import com.chunting.purecourse.core.Accent
import com.chunting.purecourse.core.ThemeMode
import com.chunting.purecourse.data.SettingsStore
import com.chunting.purecourse.data.Source
import com.chunting.purecourse.data.SourceStore
import com.chunting.purecourse.data.SrcPath

enum class Page(val route: String) {
    Home("home"), Detail("detail"), Browse("browse"),
    Settings("settings"), Sources("sources"), AddSrc("addsrc"), Player("player");

    companion object {
        fun of(route: String) = entries.first { it.route == route }
    }
}

enum class HomeTab { Shelf, Fav }
enum class Step { Proto, Form, Test }

/** 设置项类型（对应 SETGROUPS 的 ty 字段） */
enum class RowType { BOOL, ENUM, SORT, GOTO, DIAG, INFO }

data class SettingRow(
    val key: String,
    val title: String,
    val desc: String,
    val group: String,
    val type: RowType,
    val options: List<String> = emptyList(),
    val intOptions: List<Int> = emptyList(),
)

/** 多选态上下文：书架页移除 / 浏览页批量加入书架 */
enum class MultiCtx { Shelf, Browse }

/**
 * 全局状态机：原型的 `COURSES / FAVS / SETTINGS / SOURCES / add / multi / PLAY / SMB / FT /
 * FAVRM / SADJ` 全部状态 + 全部动作函数，一一对应地移植到 Compose 的 snapshot 状态。
 *
 * 焦点不走系统 FocusFinder（TV 上坐标式导航必出错，见工具链 §19.3），
 * 而是复刻原型的 REG / EXIT / NAV 区域导航，详见 Nav.kt。
 */
class AppState {

    private val ui = Handler(Looper.getMainLooper())
    private var tabTimer: Runnable? = null
    private var toastTimer: Runnable? = null

    // ================= 遥控器跟手性：区域表 / 收藏树 / 目录项缓存 =================
    // 实测卡顿根因：每按一次方向键，nav() 建一次 buildRegions()、regionOf() 又建一次、
    // setFocus()→containsTag() 再建一次；收藏页每次构建都要递归 ftRows() 并反复调 mcItems()。
    // 这里按「会影响区域内容的状态签名」缓存，签名不变直接复用同一份结果（详见 Nav.kt）。
    internal var regionsCache: Map<String, Region>? = null
    internal var regionsSig: String = ""
    private var ftRowsCache: List<McRow>? = null
    private var ftRowsSig: String = ""
    /** 浏览页左树（真实目录层级）的缓存 */
    private var brRowsCache: List<McRow>? = null
    private var brRowsSig: String = ""
    private val mcCache = LinkedHashMap<String, List<FsItem>>()
    private var mcSig: String = ""

    // ================= 基础 =================
    var page by mutableStateOf(Page.Home)
        private set
    var tab by mutableStateOf(HomeTab.Shelf)
        private set
    /**
     * 进入播放页之前所在的页面。
     * 用户要求「播完一门课就弹回播放前的界面」——不能写死回详情页：
     * 从书架 Hero 直接开播时来源是 Home，从详情页「继续播放」进来才是 Detail。
     */
    var playerFrom by mutableStateOf(Page.Home)
        private set
    var sel by mutableStateOf(0)              // 当前选中课程
    // 冷启动落点对齐原型 pageDefault('page-home') = tabLanding(tab) = #hero（内容首项），
    // 不是顶栏。否则装上新包第一下按返回就直接 finish()，用户会以为返回分级没生效。
    var focusTag by mutableStateOf("hero")
    var stageModeShelf = true                 // 顶栏配色：#stage[data-mode]

    // 每页的来源焦点（原型 ORIGIN）：返回该页时优先还原
    val origin = mutableMapOf<String, String>()
    var browFrom by mutableStateOf(Page.Home)

    // 每页默认落点（原型 pageDefault）
    var lastFocusByPage = mutableMapOf<String, String>()

    // ================= 数据（tv5：全部来自真实 SMB，Demo.kt 的假课程已停用）=================
    var courses by mutableStateOf(emptyList<Course>())
    var favs by mutableStateOf(emptyList<Fav>())
    var fs by mutableStateOf(emptyMap<String, FsNode>())
    var sources by mutableStateOf(emptyList<Source>())

    /**
     * 网络源 / 本地源的视图拆分。
     *
     * [sources] 里同时装着 SMB、WebDAV 和本地挂载点（它们都是"源"），
     * 但数据源页的卡片与按键索引只按网络源算：本地存储是一张固定卡，
     * 不应该出现在「编辑 / 重连 / 删除」那一排里 —— 它不是用户添加的配置，
     * 删了下次启动又会被探测回来，做成可删只会骗人。
     */
    val netSources: List<Source> get() = sources.filter { it.proto != Proto.LOCAL }
    val localSources: List<Source> get() = sources.filter { it.proto == Proto.LOCAL }

    /** 课程 id → 共享内真实目录；课程 id → 该目录下的真实视频文件（已按自然序排好） */
    var courseDirs by mutableStateOf(emptyMap<Int, String>())
        private set
    var courseEpisodes by mutableStateOf(emptyMap<Int, List<Episode>>())
        private set

    var catalogLoading by mutableStateOf(true)
        private set
    var catalogError by mutableStateOf<String?>(null)
        private set
    /** 扫描进度文案（非错误）。WebDAV 整棵树要跑几分钟，必须给实时反馈 */
    var catalogProgress by mutableStateOf<String?>(null)
        private set
    var rootKey by mutableStateOf("")
        private set

    // ================= 多源（M2）=================
    /**
     * 已保存的数据源配置（有序）。路由、扫描、收藏解析都以它为准；
     * 界面上的卡片列表 [sources] 是它的"带状态视图"。
     */
    @Volatile
    internal var sourceCfgs: List<SourceStore.Cfg> = emptyList()
    /** 每个源最近一次扫描结果（key = 源 id）。后台线程写、主线程读，用并发 Map */
    internal val snapBySrc = java.util.concurrent.ConcurrentHashMap<String, RealCatalog.Snapshot>()
    /**
     * 每个源「上次完整扫描」的缓存快照（key = 源 id）。
     * 只用来判断"这个源已经有完整结果了，别拿部分结果覆盖"，
     * 不参与渲染；渲染永远读 [snapBySrc]。
     */
    private val cacheSnapBySrc = java.util.concurrent.ConcurrentHashMap<String, RealCatalog.Snapshot>()
    /** 每个源的扫描进度文案 */
    private val srcProgress = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** 课程播放状态缓存（见 publishMerged 里的注释） */
    @Volatile
    private var metasCache: Map<String, com.chunting.purecourse.data.CourseMetaStore.Meta>? = null
    /** 扫描代号：换了数据源/重新加载时 +1，旧线程的结果一律丢弃 */
    @Volatile
    private var scanSeq = 0

    /** U 盘插拔广播接收器（见 [watchStorage]） */
    private var storageReceiver: android.content.BroadcastReceiver? = null

    /** 添加数据源页的快捷位置：全部是本机 192.168.1.10 上核实存在的共享，不是演示 IP */
    val discover: List<Endpoint> = RealCatalog.shortcuts.map {
        Endpoint(it.label, it.host, Proto.SMB, 445, it.pathText, it.user, it.pass)
    }
    val recent: List<Endpoint> = RealCatalog.shortcuts.take(3).map {
        Endpoint(it.label, it.host, Proto.SMB, 445, it.pathText, it.user, it.pass)
    }

    /**
     * 监听 U 盘 / SD 卡的插拔（存储热插拔）。
     *
     * 不监听的话，用户插上 U 盘后左树永远不出现那一行，必须手动重启应用 ——
     * 这是"插了没反应"的典型体验事故。
     */
    fun watchStorage() {
        if (storageReceiver != null) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                // 系统一次插拔会连发 mounted / unmounted / eject 多个广播，
                // 这里合并成一次重扫（本地重扫很便宜，但没必要跑三四遍）。
                refreshLocalSources()
            }
        }
        val f = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_MEDIA_MOUNTED)
            addAction(android.content.Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(android.content.Intent.ACTION_MEDIA_EJECT)
            addAction(android.content.Intent.ACTION_MEDIA_REMOVED)
            addDataScheme("file")
        }
        runCatching { App.instance.registerReceiver(r, f) }
        storageReceiver = r
    }

    fun unwatchStorage() {
        storageReceiver?.let { runCatching { App.instance.unregisterReceiver(it) } }
        storageReceiver = null
    }

    /**
     * 只重扫本地挂载点，不动正在跑的网盘扫描。
     *
     * 插拔 U 盘触发一次 `loadRealCatalog()` 会把 115 网盘整棵树重扫几分钟，
     * 用户只是插了个盘，没必要付这个代价。
     */
    fun refreshLocalSources() {
        Thread {
            val ctx = App.instance
            val locals = runCatching {
                com.chunting.purecourse.data.LocalStore.hosts(ctx)
            }.getOrDefault(emptyList())
            val prevLocalIds = sourceCfgs.filter { it.proto == Proto.LOCAL }.map { it.id }.toSet()
            // 已拔掉的盘：把它的快照丢掉，否则书架会继续挂着"已经不存在的课"
            snapBySrc.keys.filter { it in prevLocalIds && locals.none { c -> c.id == it } }
                .forEach { snapBySrc.remove(it) }

            val cfgs = locals + sourceCfgs.filter { it.proto != Proto.LOCAL }
            ui.post {
                sourceCfgs = cfgs
                sources = buildSources(cfgs)
                publishMerged(fromCache = true)
            }
            // 新插上的盘（还没有快照的那几个）各扫一次
            locals.filter { snapBySrc[it.id] == null }.forEach { cfg ->
                val snap = runCatching { scanOne(cfg, scanSeq) }.getOrNull() ?: return@forEach
                snapBySrc[cfg.id] = snap
                ui.post {
                    sources = buildSources(sourceCfgs)
                    publishMerged(fromCache = true)
                }
            }
        }.start()
    }

    /** 用户主动删除数据源后清空界面数据，同时给出可见原因（不自动恢复默认共享） */
    fun clearCatalog(reason: String) {
        catalogLoading = false
        catalogError = reason
        courses = emptyList()
        courseDirs = emptyMap()
        courseEpisodes = emptyMap()
        fs = emptyMap()
        favs = emptyList()
        sources = emptyList()
        snapBySrc.clear()
        srcProgress.clear()
        browseStack = listOf("")
        sel = 0
        setFocus(defaultFocus())
    }

    /** 顶栏副标题：显示真实数据源，不再写演示 IP */
    fun sourceLabel(): String {
        val cfgs = sourceCfgs.ifEmpty { runCatching { SourceStore.list(App.instance) }.getOrDefault(emptyList()) }
        if (cfgs.isEmpty()) return "未配置数据源"
        if (cfgs.size == 1) {
            val cfg = cfgs[0]
            return if (cfg.name.isBlank()) "${cfg.proto.short} · ${cfg.host} · ${cfg.shareText()}"
            else "${cfg.proto.short} · ${cfg.name}（${cfg.host}）· ${cfg.shareText()}"
        }
        val kinds = cfgs.map { it.proto.short }.distinct().joinToString("/")
        return "${cfgs.size} 个数据源 · $kinds"
    }

    // ---------------- 路径 ↔ 数据源 ----------------
    /**
     * 由「带源前缀的路径」找到它所属的数据源配置。
     *
     * 无前缀的旧路径回落到第一个源：这样即使某条历史数据没被迁移（例如用户在
     * 升级瞬间杀进程），也不会变成"读不到的鬼路径"。
     */
    fun cfgOfPath(path: String?): SourceStore.Cfg? {
        val id = SrcPath.idOf(path)
        if (id != null) {
            return sourceCfgs.firstOrNull { it.id == id } ?: SourceStore.get(App.instance, id)
        }
        // 无前缀路径（理论上只有旧数据）：归第一个**网络源**——不能归本地存储，
        // 否则一条老收藏会突然变成"U 盘里的目录"。
        return sourceCfgs.firstOrNull { it.proto != Proto.LOCAL }
            ?: SourceStore.list(App.instance).firstOrNull()
    }

    /** 路径去掉源前缀后的「源内相对路径」（网络层只认这个） */
    fun relOfPath(path: String): String = SrcPath.relOf(path)

    fun uriOfPath(path: String?): String? {
        if (path.isNullOrEmpty()) return null
        val cfg = cfgOfPath(path) ?: return null
        return cfg.uriOf(SrcPath.relOf(path))
    }

    fun uncOfPath(path: String?): String {
        if (path.isNullOrEmpty()) return "我的收藏"
        val cfg = cfgOfPath(path) ?: return SrcPath.relOf(path)
        return cfg.uncOf(SrcPath.relOf(path))
    }

    /** 路径的显示名（备注名优先 → 目录名 → 数据源名） */
    fun labelOfPath(path: String?): String {
        if (path.isNullOrEmpty()) return "全部数据源"
        aliasMap[SrcPath.of(cfgOfPath(path)?.id ?: "", SrcPath.relOf(path))]?.let { if (it.isNotBlank()) return it }
        val rel = SrcPath.relOf(path)
        val cfg = cfgOfPath(path)
        if (rel.isEmpty()) return cfg?.displayName() ?: "数据源"
        return rel.substringAfterLast('\\').ifEmpty { cfg?.displayName() ?: "" }
    }

    /** 路径的来源标签（`SMB · PC`）；构建卡片/树行时用 */
    fun srcTagOf(path: String?): String = cfgOfPath(path)?.tag() ?: ""

    /** 某个数据源的根路径（带源前缀）；卡片点进去就落在这里 */
    fun sourceRootPath(id: String): String {
        val rootRel = snapBySrc[id]?.rootKey ?: ""
        return SrcPath.of(id, rootRel)
    }

    /** 备注名表（路径 → 备注名）；扫描合并与树渲染共用 */
    var aliasMap by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    fun reloadAliases() {
        aliasMap = runCatching { com.chunting.purecourse.data.AliasStore.all(App.instance) }
            .getOrDefault(emptyMap())
    }

    /** 设置/清除某个目录的备注名（空 = 恢复真实名字） */
    fun setAlias(path: String?, name: String) {
        if (path.isNullOrBlank()) return
        val p = if (SrcPath.hasPrefix(path)) path else SrcPath.of(cfgOfPath(path)?.id ?: "", path)
        runCatching { com.chunting.purecourse.data.AliasStore.put(App.instance, p, name) }
        reloadAliases()
        // 立刻生效：课程名 / 树节点名都不重建扫描，只按新备注名重画
        val real = name.ifBlank { realNameOf(p, "") }
        courses = courses.map { c ->
            val dir = courseDirs[c.id]
            if (dir == p) c.copy(name = real, py = real) else c
        }
        // 左树 / 右列读的是 fs 节点的 name，不一起改就会出现"卡片名变了、目录树还是旧名"
        // 注意两种位置都要改：节点自身的 name（展开后的标题）和父节点里那条 item（列表行）
        fs = fs.mapValues { (k, n) ->
            val nn = if (k == p) n.copy(name = real) else n
            nn.copy(items = nn.items.map { fi -> if (fi.to == p || fi.relPath == p) fi.copy(name = real) else fi })
        }
        // 收藏树的左树行与右列标题**都来自 favs**（不是 fs），这一处漏改会出现
        // "备注名已落盘、收藏页却还是原名"——实测踩到过
        favs = favs.map { f -> if (f.to == p) f.copy(name = real) else f }
        // 书架上那个数据源大卡（SourcesPage）也挂备注名
        sources = sources.map { s -> if (sourceRootPath(s.id) == p) s.copy(name = real) else s }
        ftRowsCache = null; brRowsCache = null; mcCache.clear()
        toast(if (name.isBlank()) "已恢复原始名称" else "备注名：$name")
    }

    /** 去备注后的真实目录名（只从当前状态推导，不猜） */
    private fun realNameOf(path: String, fallback: String): String {
        val rel = SrcPath.relOf(path)
        // 根路径没有末段：清掉备注名要退回数据源自己的显示名，不能变空串
        if (rel.isEmpty()) return cfgOfPath(path)?.displayName()?.ifBlank { fallback } ?: fallback
        return rel.substringAfterLast('\\').ifEmpty { fallback }
    }

    // ---------------- 真实集数 / 真实集名 ----------------
    fun epCount(id: Int = sel): Int =
        courseEpisodes[id]?.size ?: courses.getOrNull(id)?.epCount ?: 0

    fun epName(id: Int, index: Int): String =
        courseEpisodes[id]?.getOrNull(index)?.name ?: "第 ${index + 1} 集"

    fun epPath(id: Int, ep: Int): String? =
        courseEpisodes[id]?.getOrNull(ep - 1)?.path

    fun courseDir(id: Int = sel): String? = courseDirs[id]

    fun courseIdByPath(): Map<String, Int> = courseDirs.entries.associate { (k, v) -> v to k }

    /**
     * 两条路径是否指向**同一个源里的同一个位置**。
     *
     * "在书架"这一类判定只能用它：SMB 与 WebDAV 里同名的文件夹（例如两处都有
     * 「02 乐理课程」）是两个完全不同的目录，绝不能因为名字一样就判成"已在书架"。
     *
     * 无前缀（旧数据）的路径按 [cfgOfPath] 的回落规则解释一次——只在**目标**无前缀时
     * 才做这个猜测；带前缀的目标一律严格比"源 id + 源内路径"。
     */
    fun sameShelfPath(a: String?, b: String?): Boolean {
        if (a.isNullOrEmpty() || b.isNullOrEmpty()) return false
        val aid = SrcPath.idOf(a)
        val bid = SrcPath.idOf(b)
        if (aid != null && bid != null) return aid == bid && relKey(a) == relKey(b)
        // 有一方是旧的无前缀路径：只能按源内路径比，源交给 cfgOfPath 统一解释
        val ga = aid ?: cfgOfPath(a)?.id
        val gb = bid ?: cfgOfPath(b)?.id
        return relKey(a) == relKey(b) && (ga == null || gb == null || ga == gb)
    }

    /** 源内路径的比较形式：统一反斜杠、去掉首尾分隔符 */
    private fun relKey(path: String): String =
        SrcPath.relOf(path).trim('\\', '/').replace('/', '\\')

    /**
     * 这个目录对应的**在架**课程 id；不在架返回 null。
     *
     * 关键点：
     *  1. 必须排除 `out = true`（已被移出书架）的课程。之前只查
     *     `courseDirs.values.contains(rel)`，移出书架时只把 Course.out 置 true、
     *     没有动 courseDirs，于是浏览页仍然标「★ 已在书架」，而书架页那张卡已经不见了。
     *  2. 必须按「源 id + 源内路径」比，不能只比源内路径：多个源里同名目录很常见，
     *     只比后半段会把 WebDAV 的目录误判成 SMB 那门已在书架的课。
     */
    fun shelfCourseOf(rel: String?): Int? {
        if (rel.isNullOrEmpty()) return null
        // "#fav" / "#c5" 这类树上伪节点不是真实目录，直接判「不在书架」
        if (rel.startsWith("#")) return null
        val tid = SrcPath.idOf(rel) ?: cfgOfPath(rel)?.id
        val key = relKey(rel)
        val id = courseDirs.entries.firstOrNull { (_, v) ->
            relKey(v) == key && (tid == null || SrcPath.idOf(v) == null || SrcPath.idOf(v) == tid)
        }?.key ?: return null
        val c = courses.getOrNull(id) ?: return null
        return if (c.out) null else id
    }

    /** 浏览页判断「这个目录是否已在书架」：按「源 id + 源内路径」比，不按名字比 */
    fun inShelfPath(rel: String?): Boolean = shelfCourseOf(rel) != null

    fun totalSizeText(id: Int): String = RealCatalog.sizeText(
        courseEpisodes[id]?.sumOf { it.size } ?: 0L
    )

    /** 同一层目录只发一次读取请求：mcItems 在重组时会被反复调用，不能每次都起线程 */
    private val pendingNodes = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    /**
     * 后台补一层目录节点（收藏页展开 / 浏览页下钻共用），不改动当前浏览栈。
     *
     * [force] = true 时即使已有该节点也重读一次（用于"用户主动再点一次"或剧集刷新）。
     */
    fun ensureNode(rel: String, force: Boolean = false) {
        if (!force && fs.containsKey(rel)) return
        // 先把配置拿到，再登记 pending：否则 load 失败直接 return 会让这个 key
        // 永久留在 pendingNodes 里，之后再也补不上（表现就是"一直正在读取"）
        val cfg = cfgOfPath(rel) ?: return
        val inner = SrcPath.relOf(rel)
        if (!pendingNodes.add(rel)) return
        // 列目录层要给的是「源内路径 → 课程 id」，不能把带前缀的全局 map 直接传下去：
        // 那边按源内路径查，键不匹配就永远标不出"这是课程目录"。
        val cbp = courseDirs.entries
            .filter { SrcPath.idOf(it.value) == cfg.id }
            .associate { SrcPath.relOf(it.value) to it.key }
        Thread {
            val node = when {
                cfg.proto == Proto.DAV -> runCatching {
                    com.chunting.purecourse.data.DavCatalog.listDir(cfg, inner, cbp)
                }.getOrNull()

                // 本地：直接读目录，不碰 SMB 会话（否则会去连一个不存在的 445 端口）
                cfg.proto == Proto.LOCAL -> runCatching {
                    RealCatalog.listDir(cfg, inner, cbp)
                }.getOrNull()

                else -> {
                    if (!com.chunting.purecourse.data.SmbSession.isConnected) {
                        com.chunting.purecourse.data.SmbSession.connect(cfg.host, cfg.share, cfg.user, cfg.pass, cfg.port)
                    }
                    runCatching { RealCatalog.listDir(cfg, inner, cbp) }.getOrNull()
                }
            }
            ui.post {
                pendingNodes.remove(rel)
                // 节点回来后再补一次源前缀：网络层给的是"源内路径"，界面层一律用全路径
                if (node != null) {
                    fs = fs + (rel to node.copy(
                        key = rel,
                        items = node.items.map { it2 ->
                            val tp = it2.to?.let { t -> SrcPath.of(cfg.id, t) }
                            val rp = it2.relPath?.let { t -> SrcPath.of(cfg.id, t) }
                            // 与 buildMerged 保持一致：树行也要套备注名
                            val al = (tp ?: rp)?.let { aliasMap[it] }
                            it2.copy(
                                name = if (!al.isNullOrBlank()) al else it2.name,
                                to = tp,
                                relPath = rp,
                                srcTag = cfg.tag(),
                            )
                        },
                    ))
                } else {
                    toast("读不到目录：${labelOfPath(rel)}")
                }
            }
        }.start()
    }

    /** 按完整路径打开浏览页，路径链即面包屑；缺的那一层异步补 */
    fun openRealDir(rel: String, keepFocus: Boolean = false, expandSelf: Boolean = true) {
        // 面包屑从"全部数据源"根开始，第一段就是源节点（@s1），与左树结构一致
        browseStack = RealCatalog.stackFor("", rel)
        // 路径上的祖先目录一律展开，否则会出现「已经进到第 4 层，
        // 左树却还停在第一层没展开」——用户以为左树点不开。
        // expandSelf=false 给「在树上按确认收起」用：那一层已经手动关掉了，
        // 这里再强制展开就会表现成「只能展开、收不起来」。
        val onPath = browseStack.filter { it.isNotBlank() && (expandSelf || it != rel) }
        if (onPath.isNotEmpty()) {
            ftOpen = ftOpen + onPath.associateWith { true }
        }
        ensureNode(rel)
        if (!keepFocus) setFocus("brow:0")
    }

    /** 底层错误码翻成人话，避免界面上直接出现 auth / network 这类黑话 */
    private fun humanError(err: String): String = when (err) {
        "auth" -> "账号或密码不对（到数据源页重新填写）"
        "full" -> "电脑 SMB 连接数已满，先在电脑上清理会话"
        "network" -> "连不上共享主机：检查 IP、是否同一网段、防火墙"
        "path" -> "共享名不存在，或没有访问权限"
        else -> err
    }

    /** 数据源在卡片上显示的「位置」文本 */
    private fun sourcePathTextOf(cfg: SourceStore.Cfg): String = when {
        // 本地源直接显示挂载点（`/storage/emulated/0`、`/storage/1A2B-3C4D`），
        // 用户插拔 U 盘时能一眼确认是哪一块
        cfg.proto == Proto.LOCAL -> cfg.host
        cfg.proto == Proto.DAV ->
            if (cfg.root.isEmpty()) cfg.baseUrl else "${cfg.baseUrl}/${cfg.root}"
        cfg.wholeHost -> "全部共享"
        cfg.root.isEmpty() -> cfg.share
        else -> "${cfg.share}/${cfg.root}"
    }

    /**
     * 把「多个源各自扫到的结果」合并成界面用的**一份**快照。
     *
     * 合并只做三件事：给所有路径加源前缀（[SrcPath]）、把课程 id 重新编号成连续下标、
     * 造一个「全部数据源」根节点。扫描层（RealCatalog / DavCatalog）一行都不用改，
     * 它们仍然只认识「自己这个源内部的相对路径」。
     *
     * 编号策略：按 [sourceCfgs] 的顺序依次顺延。同一源内部的课程顺序在增量扫描中是
     * append-only 的，所以只要源的顺序不变，某门课的 id 就不会在刷新过程中乱跳。
     */
    private fun buildMerged(): RealCatalog.Snapshot {
        val courses = ArrayList<Course>()
        val dirs = HashMap<Int, String>()
        val eps = HashMap<Int, List<Episode>>()
        val nodes = HashMap<String, FsNode>()
        val rootItems = ArrayList<FsItem>()
        var firstErr: String? = null

        for (cfg in sourceCfgs) {
            val snap = snapBySrc[cfg.id] ?: continue
            if (snap.err != null && firstErr == null) firstErr = snap.err
            val base = courses.size
            val tag = cfg.tag()
            val rootPath = SrcPath.of(cfg.id, snap.rootKey)

            snap.courses.forEach { c ->
                val p = SrcPath.of(cfg.id, snap.dirs[c.id] ?: "")
                courses += c.copy(id = base + c.id, srcId = cfg.id, srcTag = tag)
                dirs[base + c.id] = p
            }
            snap.episodes.forEach { (k, list) ->
                eps[base + k] = list.map { it.copy(path = SrcPath.of(cfg.id, it.path)) }
            }
            snap.fs.forEach { (k, node) ->
                val nk = SrcPath.of(cfg.id, k)
                nodes[nk] = node.copy(
                    key = nk,
                    items = node.items.map { fi ->
                        val toPath = fi.to?.let { SrcPath.of(cfg.id, it) }
                        val relP = fi.relPath?.let { SrcPath.of(cfg.id, it) }
                        // 备注名要在**这里**套上：收藏页左树展开时读的是 fs 子项名，
                        // 只改 favs / courses 是不够的（实测就是这一层漏了）
                        val al = (toPath ?: relP)?.let { aliasMap[it] }
                        fi.copy(
                            name = if (!al.isNullOrBlank()) al else fi.name,
                            to = toPath,
                            relPath = relP,
                            course = fi.course?.let { base + it },
                            srcTag = tag,
                        )
                    },
                )
            }

            val videoCount = snap.fs.values.sumOf { n -> n.items.count { it.type == ItemType.VID } }
            rootItems += FsItem(
                ItemType.DIR,
                aliasMap[rootPath]?.takeIf { it.isNotBlank() } ?: cfg.displayName(),
                to = rootPath,
                countText = when {
                    snap.err != null && snap.courses.isEmpty() -> "读取失败"
                    snap.courses.isNotEmpty() -> "${snap.courses.size} 门课程"
                    else -> "${snap.fs[snap.rootKey]?.items?.size ?: 0} 项"
                },
                relPath = rootPath,
                srcTag = tag,
            )
        }

        nodes[""] = FsNode("", "全部数据源", rootItems)
        return RealCatalog.Snapshot(courses, dirs, eps, nodes, "", firstErr)
    }

    /** 由配置 + 最近扫描状态生成数据源卡片（界面只认这个） */
    private fun buildSources(cfgs: List<SourceStore.Cfg>): List<Source> = cfgs.map { cfg ->
        val snap = snapBySrc[cfg.id]
        Source(
            name = aliasMap[sourceRootPath(cfg.id)]?.takeIf { it.isNotBlank() } ?: cfg.displayName(),
            proto = cfg.proto,
            host = cfg.host,
            port = cfg.port,
            path = sourcePathTextOf(cfg),
            // 起始目录可能本身就是一摞视频（如「幼儿园舞蹈」），只数文件夹会显示 0，
            // 让用户以为没读到东西。这里用「顶层条目总数」，目录和视频都算。
            dirs = snap?.let { it.fs[it.rootKey]?.items?.size ?: it.courses.size } ?: 0,
            ok = snap != null && (snap.err == null || snap.courses.isNotEmpty()),
            err = snap?.err,
            isNew = false,
            remember = true,
            id = cfg.id,
            progress = srcProgress[cfg.id],
        )
    }

    /**
     * 把合并后的结果发布到界面（必须在主线程调用）。
     *
     * [fromCache] = 用的是缓存或部分结果：只负责"先把内容显示出来"，不关加载态。
     * [resetUi]   = 整轮扫描结束：才允许重置浏览位置 / 焦点，否则边扫边上屏会把用户
     *               正在看的位置反复弹回根目录。
     */
    private fun publishMerged(fromCache: Boolean, resetUi: Boolean = false) {
        val cfgs = sourceCfgs
        if (cfgs.isEmpty()) {
            catalogLoading = false
            catalogError = "还没有配置数据源"
            courses = emptyList(); courseDirs = emptyMap(); courseEpisodes = emptyMap()
            fs = emptyMap(); favs = emptyList(); sources = emptyList()
            browseStack = listOf("")
            return
        }
        if (fromCache) {
            catalogLoading = true
            if (catalogProgress.isNullOrBlank()) {
                catalogProgress = "已显示上次扫描结果，正在后台刷新…"
            }
        }

        val merged = buildMerged()
        val hidden = runCatching { SourceStore.hiddenPaths(App.instance) }.getOrDefault(emptyList())
        // 播放状态每次 publish 都从 prefs 全量读一遍太贵（边扫边上屏会 publish 几十次），
        // 这里做一层内存缓存；touchCourse 写盘时会把它置空。
        val metas = metasCache ?: runCatching {
            com.chunting.purecourse.data.CourseMetaStore.loadAll(App.instance)
        }.getOrDefault(emptyMap()).also { metasCache = it }
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)

        // id 重编号后要保住"用户当前选中的是哪门课"：按目录路径重新定位，不能用旧下标
        val prevSelDir = courseDirs[sel]
        val prevPlayDir = courseDirs[playCourse]

        courses = merged.courses.map { c ->
            val dir = merged.dirs[c.id]
            val alias = dir?.let { aliasMap[it] }
            var nc = c
            if (alias != null && alias.isNotBlank()) nc = nc.copy(name = alias, py = alias)
            if (dir != null && hidden.contains(dir)) nc = nc.copy(out = true)
            val m = dir?.let { metas[it] }
            if (m != null) {
                nc = nc.copy(
                    lastAt = m.lastAt,
                    curEp = m.curEp.coerceIn(1, maxOf(1, nc.epCount)),
                    // 只认真正看完的集（旧版记录里没有这一段，读出来是空集）
                    doneSet = m.done.filter { it in 1..maxOf(1, nc.epCount) }.toSet(),
                    lastEpName = m.lastEpName.ifBlank { nc.lastEpName },
                    lastTxt = if (m.lastAt > 0) fmt.format(java.util.Date(m.lastAt)) else nc.lastTxt,
                )
            }
            nc
        }
        courseDirs = merged.dirs
        courseEpisodes = merged.episodes
        fs = merged.fs
        rootKey = ""
        sources = buildSources(cfgs)

        // 收藏树：每个源一个根节点 + 该源下用户收藏过的深层目录（跨源混排）
        val savedFavs = runCatching { SourceStore.favPaths(App.instance) }.getOrDefault(emptyList())
        favs = buildList {
            for (cfg in cfgs) {
                val snap = snapBySrc[cfg.id] ?: continue
                val rootPath = SrcPath.of(cfg.id, snap.rootKey)
                val videoCount = snap.fs.values.sumOf { n -> n.items.count { it.type == ItemType.VID } }
                add(
                    Fav(
                        aliasMap[rootPath]?.takeIf { it.isNotBlank() } ?: cfg.displayName(),
                        cfg.uncOf(snap.rootKey),
                        "${snap.courses.size} 门课程 · $videoCount 个视频",
                        rootPath,
                        cfg.tag(),
                    )
                )
                savedFavs.filter { SrcPath.idOf(it.rel) == cfg.id && it.rel != rootPath }.forEach { r ->
                    val nm = aliasMap[r.rel]?.takeIf { it.isNotBlank() }
                        ?: r.name.ifBlank { SrcPath.relOf(r.rel).substringAfterLast('\\') }
                    add(Fav(nm, cfg.uncOf(SrcPath.relOf(r.rel)), "已收藏目录", r.rel, cfg.tag()))
                }
            }
        }

        // 全源都失败才报错；部分目录 502 只提示，不清空已有内容。
        // 注意 fs 判据：WebDAV 现在只读根目录一层，fs 里就只有根节点这一个 key，
        // 用 "fs.size > 1" 会把"读到根目录但没课"误判成"什么都没读到"（红字报错盖住正常空书架）。
        // 所以只要没有任何源报错，就认为数据源是通的。
        val anyData = merged.courses.isNotEmpty() || merged.fs.size > 1 || merged.err == null
        catalogError = if (!anyData) merged.err?.let { humanError(it) } ?: "没有读到任何课程" else null
        if (!fromCache) {
            catalogLoading = false
            catalogProgress = null
            if (merged.err != null && merged.courses.isNotEmpty()) {
                toast("部分目录读取失败（已跳过）：${merged.err}")
            }
        }

        // 记住用户当前所在位置
        if (prevSelDir != null) {
            courseDirs.entries.firstOrNull { it.value == prevSelDir }?.key?.let { sel = it }
        }
        if (prevPlayDir != null && playCourse >= 0) {
            courseDirs.entries.firstOrNull { it.value == prevPlayDir }?.key?.let { playCourse = it }
        }

        if (resetUi) {
            if (browseStack.isEmpty()) browseStack = listOf("")
            if (courses.isNotEmpty()) {
                sel = if (prevSelDir != null) sel else recentIdx().coerceAtLeast(0)
            }
            ftSel = 0; ftRow = 0; ftKey = "#fav"; ftOpen = mapOf("#fav" to true)
            setFocus(defaultFocus())
        } else if (browseStack.isEmpty()) {
            browseStack = listOf("")
        }
    }

    /**
     * 冷启动 / 换数据源后重新扫描。
     *
     * 多源并存的关键点：**每个源并行扫描、各自带缓存、各自增量上屏**。
     * 一个源断了不影响另一个源的书架；WebDAV 慢也不会拖住 SMB 的显示。
     */
    fun loadRealCatalog() {
        catalogLoading = true
        catalogError = null
        catalogProgress = null
        val token = ++scanSeq
        reloadAliases()
        metasCache = null
        cacheSnapBySrc.clear()
        Thread {
            try {
                val ctx = App.instance
                SourceStore.ensureDefault(ctx)
                // 本地挂载点（内置存储 / U 盘）**每次实时探测、不落库**：
                // 拔盘后那条源就该消失，而不是留一条永远"连接失败"的死配置。
                // 顺序上放最前：左树第一层就是「本地存储 / U盘 · XXX / 本机SMB / 115网盘」。
                val localCfgs = runCatching {
                    com.chunting.purecourse.data.LocalStore.hosts(ctx)
                }.getOrDefault(emptyList())
                val cfgs = localCfgs + SourceStore.list(ctx)
                if (cfgs.isEmpty()) {
                    ui.post {
                        if (token != scanSeq) return@post
                        sourceCfgs = emptyList()
                        snapBySrc.clear()
                        publishMerged(fromCache = false, resetUi = true)
                    }
                    return@Thread
                }
                // 配置一读到就先把卡片放上去：WebDAV 全树要几分钟，
                // 这段时间数据源页不能是空的（用户会以为"没保存上"）。
                ui.post {
                    if (token != scanSeq) return@post
                    sourceCfgs = cfgs
                    sources = buildSources(cfgs)
                }

                // ① 先把每个源上次的结果放出来（秒开）
                //    本地源不读缓存：它就在本机，扫一遍是一两秒的事，
                //    用缓存反而会出现"U 盘已经拔了、书本上还挂着它的课"。
                //    WebDAV 也不读缓存：源上的目录经常改名/删除，跨天存下来的清单
                //    带着一堆早就没有的目录，点进去必然 404；现在启动只读根目录一层，
                //    快到根本不需要这份缓存来"提速"。
                var anyCache = false
                cfgs.filter { it.proto != Proto.LOCAL && it.proto != Proto.DAV }.forEach { cfg ->
                    val c = runCatching { com.chunting.purecourse.data.CatalogCache.load(ctx, cfg) }.getOrNull()
                    if (c != null && c.courses.isNotEmpty()) {
                        cacheSnapBySrc[cfg.id] = c
                        snapBySrc[cfg.id] = c
                        anyCache = true
                    }
                }
                if (anyCache) ui.post { if (token == scanSeq) publishMerged(fromCache = true) }

                // ② 各源并行全量扫描
                val latch = java.util.concurrent.CountDownLatch(cfgs.size)
                cfgs.forEach { cfg ->
                    Thread {
                        try {
                            if (token != scanSeq) return@Thread
                            val snap = scanOne(cfg, token)
                            if (token != scanSeq) return@Thread
                            snapBySrc[cfg.id] = snap
                            srcProgress.remove(cfg.id)
                            // 扫成功就落盘（下次冷启动直接用）。彻底失败时不要覆盖旧缓存，
                            // 否则一次网络抖动就把好好的缓存清成空。
                            // 本地源不落缓存：每次都是实时读盘，缓存只会变成"U 盘拔了还挂着课"。
                            // WebDAV 也不落缓存：源常增删改名，存下来的清单不可信（同 ① 的注释）。
                            if (cfg.proto != Proto.LOCAL && cfg.proto != Proto.DAV &&
                                (snap.courses.isNotEmpty() || snap.err == null)
                            ) {
                                runCatching { com.chunting.purecourse.data.CatalogCache.save(ctx, cfg, snap) }
                            }
                            ui.post { if (token == scanSeq) publishMerged(fromCache = true) }
                        } catch (t: Throwable) {
                            srcProgress.remove(cfg.id)
                            ui.post {
                                if (token != scanSeq) return@post
                                snapBySrc[cfg.id] = RealCatalog.Snapshot(
                                    emptyList(), emptyMap(), emptyMap(), emptyMap(), "",
                                    "扫描出错：${t.javaClass.simpleName}",
                                )
                                publishMerged(fromCache = true)
                            }
                        } finally {
                            latch.countDown()
                        }
                    }.start()
                }
                latch.await()
                ui.post {
                    if (token != scanSeq) return@post
                    srcProgress.clear()
                    publishMerged(fromCache = false, resetUi = true)
                }
            } catch (t: Throwable) {
                // 后台线程一旦抛异常，下面的 ui.post 就永远不执行：
                // catalogLoading 会一直 true、sources 还停在旧值，
                // 用户看到的就是"保存了但数据源里没有、书架也一直转"。
                ui.post {
                    if (token != scanSeq) return@post
                    catalogLoading = false
                    catalogProgress = null
                    catalogError = "读取数据源出错：${t.javaClass.simpleName}${t.message?.let { " · $it" } ?: ""}"
                }
            }
        }.start()
    }

    /** 扫一个源（后台线程）。只返回"源内路径"的快照，前缀由 [buildMerged] 加 */
    private fun scanOne(cfg: SourceStore.Cfg, token: Int): RealCatalog.Snapshot {
        val ctx = App.instance
        // 手动加入书架、但自动扫描没识别到的目录（用户点「＋ 加入书架」加的那些）
        val mine = SourceStore.shelfPaths(ctx)
            .filter { SrcPath.idOf(it) == cfg.id }
            .map { SrcPath.relOf(it) }
        return when {
            // 本地存储：直接读本机目录，几百毫秒到一两秒；不进 SMB 锁，
            // 否则会被网络扫描串行拖住（插着 U 盘却要等网盘扫完才出课程）。
            cfg.proto == Proto.LOCAL -> RealCatalog.scan(cfg, mine)

            cfg.proto == Proto.DAV -> {
                // 每探完一层报一次进度，否则用户会以为书架卡死了。
                com.chunting.purecourse.data.DavCatalog.scan(
                    cfg,
                    extraShelved = mine,
                    onProgress = { n ->
                        ui.post {
                            if (token != scanSeq) return@post
                            srcProgress[cfg.id] = "已发现 $n 门课"
                            catalogProgress = "正在扫描 ${cfg.displayName()}…已发现 $n 门课"
                            sources = buildSources(sourceCfgs)
                        }
                    },
                    onPartial = { partial ->
                        // ⚠️ 有缓存时**不能**拿部分结果覆盖：缓存是上一次的完整扫描，
                        // 部分结果一定比它少。之前无条件覆盖，实测书架会从 314 门
                        // 瞬间缩水到 12 门（只剩 SMB 的），扫完再涨回来 —— 用户看到就是"课程没了"。
                        // 没有缓存（首次扫描）时才用部分结果边扫边显示。
                        if (cacheSnapBySrc[cfg.id] == null) {
                            snapBySrc[cfg.id] = partial
                            ui.post { if (token == scanSeq) publishMerged(fromCache = true) }
                        }
                    },
                )
            }

            else -> {
                // ⚠️ SMB 会话是**全局单例**（SmbSession 只有一个 session/share），
                // 两个 SMB 源并行扫描会互相把对方的连接切走（换共享 / 换主机都会重连），
                // 结果两边都读不到东西。所以 SMB 这一路串行；WebDAV 走 HTTP 可以并行。
                synchronized(SMB_SCAN_LOCK) { RealCatalog.scan(cfg, mine) }
            }
        }
    }

    companion object {
        /** SMB 扫描串行锁（原因见 scanOne 里的注释） */
        val SMB_SCAN_LOCK = Any()

        /** 视频比例预设下标：0 原始（按 DAR 完整显示），其余为常用画幅 */
        const val RATIO_ORIGINAL = 0
        const val RATIO_COVER = 1
        const val RATIO_16_9 = 2
        const val RATIO_4_3 = 3
        const val RATIO_21_9 = 4
        const val RATIO_STRETCH = 5
    }

    /** 书架排序：recent / name */
    var sortMode by mutableStateOf(0)
        private set

    /** 已加入书架的目录 key（原型 shelvedKeys） */
    var shelvedKeys by mutableStateOf<Set<String>>(emptySet())
    private var favKeys = mutableSetOf<String>()

    // ================= 浏览页 =================
    var browseStack by mutableStateOf(listOf("root"))

    fun node(key: String = browseStack.last()): FsNode? = fs[key]
    fun curNode(): FsNode? = fs[browseStack.last()]

    // ================= 设置 =================
    var contPlay by mutableStateOf(true)
    var autoNext by mutableStateOf(true)
    var memory by mutableStateOf(true)
    var hwDec by mutableStateOf(true)
    var seekStep by mutableStateOf(10)
    var defaultSpeed by mutableStateOf(1.0f)
    var subFont by mutableStateOf(1)   // 0 小 1 中 2 大 3 特大
    var subEdge by mutableStateOf(2)   // 0 关 1 弱 2 中 3 强

    /** 外观：主题模式（0 跟随系统 / 1 明亮 / 2 夜晚） */
    var themeMode by mutableStateOf(0)
        private set

    /** 外观：强调色序号（6 套，见 core/Theme.kt） */
    var accentIndex by mutableStateOf(0)
        private set

    /** 多档调整态（原型 SADJ）：on / 行 index / 原值 */
    var sadjOn by mutableStateOf(false)
        private set
    var sadjIndex by mutableStateOf(-1)
        private set
    private var sadjOrig: Any? = null

    init {
        // 冷启动读回上次设置。tv7 之前这些字段全是内存态：把「自动下一节」关掉、
        // 电视待机重开后又变回「开」，用户会以为设置没生效。
        runCatching {
            val s = SettingsStore.load(App.instance)
            contPlay = s.contPlay
            autoNext = s.autoNext
            memory = s.memory
            hwDec = s.hwDec
            seekStep = s.seekStep
            defaultSpeed = s.speed
            subFont = s.subFont
            subEdge = s.subEdge
            sortMode = s.sortMode
            themeMode = s.themeMode
            accentIndex = s.accentIndex
        }
    }

    /** 设置改动后立刻落盘（布尔开关与多档项都走这里） */
    private fun persistSettings() {
        runCatching {
            SettingsStore.save(
                App.instance,
                SettingsStore.Snapshot(
                    contPlay = contPlay, autoNext = autoNext, memory = memory, hwDec = hwDec,
                    seekStep = seekStep, speed = defaultSpeed,
                    subFont = subFont, subEdge = subEdge, sortMode = sortMode,
                    themeMode = themeMode, accentIndex = accentIndex,
                ),
            )
        }
    }

    /**
     * 真实版本号：从 PackageManager 读，不再手写。
     * 之前设置页「关于」写死 v1.0.0-tv5，tv6 装上后仍显示 tv5（真机截图确认），属文案 bug。
     */
    val appVersion: String by lazy {
        runCatching {
            App.instance.packageManager
                .getPackageInfo(App.instance.packageName, 0).versionName
        }.getOrNull() ?: "1.0.0"
    }

    val settingRows: List<SettingRow> = listOf(
        SettingRow("thememode", "明暗主题", "跟随系统，或固定为明亮 / 夜晚", "外观", RowType.ENUM,
            options = ThemeMode.labels),
        SettingRow("accent", "强调色", "焦点框、进度条与高亮的颜色", "外观", RowType.ENUM,
            options = Accent.labels),
        SettingRow("__src", "管理数据源", "网络位置与本地数据统一管理", "数据源", RowType.GOTO),
        SettingRow("__smbdiag", "SMB 连接诊断", "连不上电脑时，先分清是连接数满还是网络问题", "数据源", RowType.DIAG),
        // 原「连续播放」会跨到书架里的下一门课；用户要求取消跨课程，改为播完返回
        SettingRow("contplay", "播完返回", "整门课播完后回到进入播放前的页面", "播放", RowType.BOOL),
        SettingRow("autonext", "自动下一节", "当前视频结束自动播放下一节", "播放", RowType.BOOL),
        SettingRow("memory", "记忆播放进度", "退出后从上次位置继续播放", "播放", RowType.BOOL),
        SettingRow("speed", "默认倍速", "新开播放默认使用的倍速", "播放", RowType.ENUM,
            options = listOf("0.75x", "1.0x", "1.25x", "1.5x", "2.0x")),
        SettingRow("seekstep", "快进/快退步长", "播放页按左右键快进、快退的秒数", "播放", RowType.ENUM,
            intOptions = listOf(5, 10, 15)),
        // 如实说明：播放内核固定启用硬解，这个开关目前只是记录偏好，关掉也不会软解。
        // 原来写「优先 GPU 解码」会让人以为可以关。
        SettingRow("hwdec", "硬件解码", "当前固定开启（关闭本项不会切软解）", "播放", RowType.BOOL),
        SettingRow("subfont", "字幕字号", "字幕显示大小", "字幕", RowType.ENUM,
            options = listOf("小", "中", "大", "特大")),
        SettingRow("subedge", "字幕描边", "提高亮背景下的可读性", "字幕", RowType.ENUM,
            options = listOf("关", "弱", "中", "强")),
        SettingRow("__sort", "排序方式", "书架卡片的排列顺序", "书架", RowType.SORT,
            options = listOf("最近播放", "名称")),
        SettingRow("__about", "课程播放器 v$appVersion", "本机运行 · 目录与视频全部来自 SMB 共享", "关于", RowType.INFO),
    )

    val groups: List<Pair<String, List<SettingRow>>> = listOf("外观", "数据源", "播放", "字幕", "书架", "关于")
        .map { g -> g to settingRows.filter { it.group == g } }

    fun rowValue(r: SettingRow): String = when (r.type) {
        RowType.BOOL -> if (boolOf(r.key)) "开" else "关"
        RowType.ENUM -> if (r.key == "seekstep") "$seekStep 秒" else choiceText(r.key)
        RowType.SORT -> if (sortMode == 0) "最近播放" else "名称"
        RowType.GOTO -> "${sources.size} 个数据源 ›"
        RowType.DIAG -> if (smbFull) "当前：连接数已满" else "20 条上限 · 查看"
        RowType.INFO -> "已是最新"
    }

    fun boolOf(key: String) = when (key) {
        "contplay" -> contPlay; "autonext" -> autoNext
        "memory" -> memory; "hwdec" -> hwDec; else -> false
    }

    fun toggleBool(key: String) {
        when (key) {
            "contplay" -> contPlay = !contPlay
            "autonext" -> autoNext = !autoNext
            "memory" -> memory = !memory
            "hwdec" -> hwDec = !hwDec
        }
        persistSettings()
    }

    fun choiceText(key: String): String = when (key) {
        "speed" -> {
            val v = defaultSpeed
            if (v == v.toInt().toFloat()) "${v.toInt()}.0x" else "${v}x"
        }
        "seekstep" -> "$seekStep 秒"
        "subfont" -> listOf("小", "中", "大", "特大")[subFont]
        "subedge" -> listOf("关", "弱", "中", "强")[subEdge]
        "__sort" -> if (sortMode == 0) "最近播放" else "名称"
        "thememode" -> ThemeMode.labelOf(themeMode)
        "accent" -> Accent.byIndex(accentIndex).label
        else -> ""
    }

    private fun choiceIdx(key: String): Int = when (key) {
        "speed" -> listOf(0.75f, 1f, 1.25f, 1.5f, 2f).indexOf(defaultSpeed).coerceAtLeast(0)
        "seekstep" -> listOf(5, 10, 15).indexOf(seekStep).coerceAtLeast(0)
        "subfont" -> subFont
        "subedge" -> subEdge
        "__sort" -> sortMode
        "thememode" -> themeMode
        "accent" -> accentIndex
        else -> 0
    }

    private fun setChoiceIdx(key: String, i: Int) {
        when (key) {
            "speed" -> defaultSpeed = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)[((i % 5) + 5) % 5]
            "seekstep" -> seekStep = listOf(5, 10, 15)[((i % 3) + 3) % 3]
            "subfont" -> subFont = ((i % 4) + 4) % 4
            "subedge" -> subEdge = ((i % 4) + 4) % 4
            "__sort" -> sortMode = ((i % 2) + 2) % 2
            "thememode" -> themeMode = ((i % 3) + 3) % 3
            "accent" -> accentIndex = ((i % 6) + 6) % 6
        }
    }

    fun openAdjust(r: SettingRow) {
        if (sadjOn) return
        sadjOn = true
        sadjIndex = settingRows.indexOf(r)
        sadjOrig = choiceIdx(r.key)
        toast("左右键调节 · 确认保存 · 返回取消")
    }

    fun adjustShift(d: Int) {
        if (!sadjOn) return
        val r = settingRows.getOrNull(sadjIndex) ?: return
        setChoiceIdx(r.key, choiceIdx(r.key) + d)
        toast("${r.title}：${rowValue(r)}")
    }

    fun adjustClose(save: Boolean) {
        if (!sadjOn) return
        val r = settingRows.getOrNull(sadjIndex)
        if (!save && r != null) setChoiceIdx(r.key, sadjOrig as Int)
        sadjOn = false
        sadjIndex = -1
        sadjOrig = null
        if (save && r != null) {
            persistSettings()
            toast("已保存 · ${r.title}：${rowValue(r)}")
        }
    }

    // ================= 多选（书架移除 / 浏览批量加入） =================
    var multiOn by mutableStateOf(false)
    var multiCtx by mutableStateOf(MultiCtx.Shelf)
    var multiSet by mutableStateOf<Map<Int, Boolean>>(emptyMap())

    fun enterMulti(ctx: MultiCtx) {
        if (multiOn) return
        multiOn = true; multiCtx = ctx; multiSet = emptyMap()
        // 焦点落到「第一门真实存在的课」而不是写死 book:0：
        // 课程 0 可能已被移出书架，那样焦点标签根本不在区域表里，
        // 下一次按方向键会先被判成"失焦"再重置，表现为光标移不动。
        val first = if (ctx == MultiCtx.Shelf) shelfOrder().firstOrNull()?.let { "book:$it" } else "brow:0"
        setFocus(first ?: defaultFocus())
        toast(if (ctx == MultiCtx.Shelf) "多选模式 · 方向键移动，确认勾选，返回退出"
        else "多选模式 · 勾选文件夹后批量加入书架")
    }

    fun exitMulti(msg: String? = null) {
        multiOn = false; multiSet = emptyMap()
        if (msg != null) toast(msg)
    }

    fun togglePick(i: Int) {
        multiSet = multiSet + (i to !(multiSet[i] ?: false))
    }

    // ================= 收藏页 Miller 树 =================
    var ftSel by mutableStateOf(0)
    var ftKey by mutableStateOf("#fav")
    var ftRow by mutableStateOf(0)
    var ftZone by mutableStateOf("tree")
    var ftOpen by mutableStateOf(mapOf("#fav" to true))

    /**
     * 收起一个树节点：**连同它的所有后代一起关掉**。
     *
     * 原来各处写的是 `ftOpen = ftOpen - key`，只清了这一层；子孙的 key 还留着 true，
     * 于是"收起父目录 → 再展开父目录"时整棵子树会自动复原成原来的展开状态。
     * 用户要的是收起即清零：下次展开只看到父目录的下一层。
     */
    fun closeNode(key: String?) {
        if (key.isNullOrBlank()) return
        val self = key.trimEnd('\\', '/')
        if (self.isEmpty()) return
        val prefix = "$self\\"
        ftOpen = ftOpen.filterKeys { k ->
            val kk = k.trimEnd('\\', '/')
            kk != self && !kk.startsWith(prefix) && !kk.startsWith("$self/")
        }
    }

    var favRmOn by mutableStateOf(false)
    var favRmSet by mutableStateOf<Set<String>>(emptySet())

    fun favRmEnter() {
        favRmOn = true; favRmSet = emptySet()
        toast("选择要取消收藏的目录 · 确认 勾选 · 返回 退出")
    }

    fun favRmExit(msg: String? = null) {
        favRmOn = false; favRmSet = emptySet()
        if (msg != null) toast(msg)
    }

    fun favRmToggle(key: String, pickable: Boolean, name: String) {
        if (!pickable) {
            toast("「$name」不是收藏项，无需取消收藏")
            return
        }
        favRmSet = if (key in favRmSet) favRmSet - key else favRmSet + key
    }

    fun favRmAllToggle() {
        val keys = ftRows().filter { it.parent == null }.map { it.key }.toSet()
        val allOn = keys.isNotEmpty() && favRmSet.containsAll(keys)
        favRmSet = if (allOn) emptySet() else keys
        toast(if (allOn) "已取消全选" else "已全选 ${keys.size} 个目录")
    }

    fun favRmCommit() {
        val n = favRmSet.size
        if (n == 0) {
            toast("请先勾选要取消收藏的目录")
            return
        }
        favs = favs.filterNot { it.to in favRmSet }
        runCatching { favRmSet.forEach { SourceStore.removeFavPath(App.instance, it) } }
        favRmOn = false; favRmSet = emptySet()
        ftSel = 0; ftRow = 0; ftZone = "tree"; ftKey = "#fav"; ftOpen = mapOf("#fav" to true)
        toast("已取消收藏 $n 个目录")
    }

    // ================= 播放页 =================
    /** 总时长：由播放器就绪后回填真实时长；换集先清 0，界面要能容忍「还没拿到时长」 */
    var totalSec by mutableStateOf(0)
        private set

    fun setDuration(sec: Int) {
        if (sec > 0) totalSec = sec
    }

    /**
     * 连按快进/快退的累加基准（毫秒）。
     * curSec 每 250ms 才由播放器回填一次；若两次按键落在同一帧内，以它为基准
     * 理论上会把多次按键叠成一次。这是代码审查发现的潜在风险，属防御性加固：
     * 实测连按 8 次（全屏态步长 seekStep=10 秒）得到 01:33，8×10=80 秒 + 自然播放，
     * 与预期一致，**未复现丢步**。不要把它写成「实测修复了快进 bug」。
     */
    private var seekAccumMs = -1L

    /** 播放器驱动的进度回写（每 250ms 一次，不写盘；写盘在暂停/退出/seek 时做） */
    fun tickFromPlayer(sec: Int) {
        // 播放器位置已经追上目标 → 累加基准作废，下一轮按键从真实位置重新起算
        if (seekAccumMs >= 0 && kotlin.math.abs(sec * 1000L - seekAccumMs) < 1500L) seekAccumMs = -1L
        // 换集瞬间 totalSec 会被清成 0，此时不能把进度夹到 0，否则播放看起来「卡住」
        if (sec != curSec) curSec = if (totalSec > 0) sec.coerceIn(0, totalSec) else sec.coerceAtLeast(0)
    }

    /** 需要真正 seek 到某个位置（由播放页消费后清空） */
    var seekRequest by mutableStateOf<Long?>(null)
        private set

    fun consumeSeek(): Long? {
        val v = seekRequest
        seekRequest = null
        return v
    }

    /**
     * 播放进度按「课程:集」独立保存（原型是每门课一条续播记录）。
     * 之前 curSec 是全局单值，导致「数据结构看到 11 分钟」会污染所有课程 —— 这是 bug，已修。
     * P1 接 Room 后这张表换成 Resume 实体（uri|pos|index），键仍是 courseId:ep。
     */
    var resumeMap by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    private var resumeLoaded = false

    /**
     * 续播键用「真实目录 + 集号」而不是数组下标：换数据源 / 目录重排后
     * 课程 id 会变，用下标当键会把 A 课的进度串到 B 课上。
     */
    fun playKey(courseId: Int = sel, ep: Int = courses.getOrNull(courseId)?.curEp ?: 1): String =
        "${courseDirs[courseId] ?: "course$courseId"}#$ep"

    var curSec by mutableStateOf(0)
        private set

    /**
     * 真正在播放的课程 id（进播放页时锁定的那个）。
     *
     * 为什么不能用 `sel`：退出播放后书架会把 `sel` 挪到焦点课程，而播放页销毁时
     * onDispose 还会再存一次进度 —— 那次就会把「最近播放 / 续播位置」写到**别的课**上
     * （实测：播完《小汤1》退回书架，书架顶部的「继续学习」被刷成了健身课）。
     */
    var playCourse by mutableStateOf(-1)
        private set

    /** 进入某集播放：先存旧的，再读该集自己的进度 */
    /** 首次访问时从磁盘灌一次（内存作为热缓存，写盘在 saveProgress 里） */
    private fun ensureResumeLoaded() {
        if (resumeLoaded) return
        resumeLoaded = true
        runCatching {
            resumeMap = com.chunting.purecourse.data.ResumeStore.loadAll(App.instance)
        }
    }

    fun loadProgress() {
        ensureResumeLoaded()
        seekAccumMs = -1L                 // 换集后旧目标不能带到新视频上
        // 「记忆播放进度」关掉时必须从 0 开始，不能偷偷读回上次的进度
        curSec = if (memory) resumeMap[playKey(if (playCourse >= 0) playCourse else sel)] ?: 0 else 0
    }

    /** 播放过这门课 → 更新「最近播放」与「上次看到」，书架排序与 hero 才能跟着动 */
    private fun touchCourse(courseId: Int = sel) {
        val c = courses.getOrNull(courseId) ?: return
        val name = epName(courseId, c.curEp - 1)
        val now = System.currentTimeMillis()
        val txt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
            .format(java.util.Date(now))
        updateCourse(courseId) {
            it.copy(lastAt = now, lastTxt = txt, lastEpName = name)
        }
        // 落盘：不写的话冷启动会退回「尚未播放」，书架排序也跟着乱
        metasCache = null
        val dir = courseDirs[courseId]
        if (!dir.isNullOrBlank()) {
            runCatching {
                com.chunting.purecourse.data.CourseMetaStore.put(
                    App.instance, dir,
                    com.chunting.purecourse.data.CourseMetaStore.Meta(now, c.curEp, name, c.doneSet),
                )
            }
        }
    }

    /**
     * 把某一集记为「看完」—— 只有真的播到片尾才会调用。
     *
     * 之前的「已看」是 `curEp - 1` 推出来的：用户在详情页跳到第 20 节看一眼，
     * 前 19 节立刻全变成「✓ 已看」。现在改成按集号记录，跳着看不会污染别人的进度。
     */
    fun markDone(courseId: Int = sel, ep: Int = courses.getOrNull(courseId)?.curEp ?: 1) {
        val c = courses.getOrNull(courseId) ?: return
        if (ep < 1) return
        if (c.epCount > 0 && ep > c.epCount) return
        if (ep in c.doneSet) return
        updateCourse(courseId) { it.copy(doneSet = it.doneSet + ep) }
        touchCourse(courseId)             // 顺手落盘，冷启动后勾还在
    }

    fun saveProgress() {
        val cid = if (playCourse >= 0) playCourse else sel
        val k = playKey(cid)
        val v = curSec
        touchCourse(cid)
        if (resumeMap[k] == v) return
        resumeMap = resumeMap + (k to v)
        // 必须落盘：只在内存里的话，杀进程/闪退后就回到 0 秒（用户会当成"续播坏了"）
        runCatching { com.chunting.purecourse.data.ResumeStore.put(App.instance, k, v) }
    }

    /**
     * 片尾处理（由 PlayerPage 的 ticker 在视频真的走到时长末尾时调用）。
     * 之前写了一个 advance()，注释说「由 ticker 调用」但全工程没有任何调用点，
     * 所以单曲循环 / 自动下一节 / 连续播放三个开关全是空转。这里补上真实行为。
     */
    fun onPlaybackEnded() {
        val c = courses.getOrNull(sel) ?: run { playing = false; return }
        when {
            loop -> Unit                      // 单曲循环由 PlayerPage 调 seekToMs(0)
            // 同课程内还有下一节 → 走「自动下一节」（这是同课程内的续播，不是跨课程）
            autoNext && c.curEp < epCount(sel) -> stepEp(1)
            // 最后一节播完：不跨课程，直接弹回进入播放前的界面
            contPlay -> leavePlayerAfterEnd()
            else -> playing = false
        }
    }

    /**
     * 片尾收尾：标记本集看完，回到**进入播放页之前**的那一页并还原焦点。
     *
     * 用户明确要求取消跨课程连播（原来这里会把 sel 切到书架里的下一门课）。
     * go() 用 record=false / noRecall=true：否则会把播放页的临时焦点（pstage / ptrack）
     * 写进详情页的来源记录，污染下一次「返回」的落点。
     */
    private fun leavePlayerAfterEnd() {
        playing = false
        // 真正播完了，才把这一集记成「已看」
        markDone(sel, courses.getOrNull(sel)?.curEp ?: 1)
        val back = if (playerFrom == Page.Detail) Page.Detail else Page.Home
        val tag = origin[Page.Player.route]
        go(back, record = false, noRecall = true)
        setFocus(
            if (tag != null && containsTag(tag)) tag
            else if (back == Page.Detail) "dact:0" else "hero"
        )
    }

    fun setPosition(sec: Int) {
        seekAccumMs = -1L                 // 绝对定位（进度条点击）作废累加基准
        curSec = if (totalSec > 0) sec.coerceIn(0, totalSec) else sec.coerceAtLeast(0)
        seekRequest = curSec * 1000L
        saveProgress()
    }
    var playing by mutableStateOf(true)
    var ctl by mutableStateOf(false)
    var mirror by mutableStateOf(false)
    var speed by mutableStateOf(1.0f)
    var speedMenu by mutableStateOf(false)
    var customOpen by mutableStateOf(false)
    var customVal by mutableStateOf(1.0f)
    var customSet by mutableStateOf(false)
    var loop by mutableStateOf(false)
    /** 右上角时长 OSD 的显示计时（对应原型 pOsd(hold) 的 setTimeout 语义） */
    var osdOn by mutableStateOf(false)
        private set
    private var osdRun: Runnable? = null

    fun osdShow(holdMs: Long) {
        osdOn = true
        osdRun?.let { ui.removeCallbacks(it) }
        val r = Runnable { osdOn = false }
        osdRun = r
        ui.postDelayed(r, holdMs)
    }

    /**
     * 两态必须同逻辑（原型修过的 bug）：控制层态只认计时器；
     * 全屏态在暂停时常显。不能出现「控制层里快移看不到时长」。
     */
    fun osdVisible(): Boolean = osdOn || (!ctl && !playing)

    fun seek(delta: Int) {
        // 连按期间以「上一次的目标位置」为基准累加，不依赖滞后的 UI 进度
        val base = if (seekAccumMs >= 0) seekAccumMs else curSec * 1000L
        val target = (base + delta * 1000L).coerceAtLeast(0L)
        seekAccumMs = target
        val sec = (target / 1000).toInt()
        curSec = if (totalSec > 0) sec.coerceIn(0, totalSec) else sec
        seekRequest = target
        saveProgress()
    }

    fun seekPercent(p: Float) {
        setPosition((p.coerceIn(0f, 1f) * totalSec).toInt())
    }

    fun togglePlay() {
        playing = !playing
        if (playing) osdShow(900)
    }

    fun openCtl() {
        if (ctl) return
        ctl = true
        setFocus("ptrack")
    }

    fun closeCtl() {
        if (!ctl) return
        ctl = false
        setFocus("pstage")
    }

    fun stepEp(d: Int) {
        saveProgress()                                   // 先把当前集写回
        val c = courses.getOrNull(sel) ?: return
        val n = epCount(sel).coerceAtLeast(1)            // 真实集数，可能是 4 / 12 / 48 / 79
        val from = c.curEp
        val next = (from + d).coerceIn(1, n)
        updateCourse(sel) {
            it.copy(
                curEp = next,
                lastEpName = epName(sel, next - 1),
            )
        }
        // 往后翻一集说明上一集已经过完了 → 记成已看；往前翻不记（回看旧课不该改进度）
        if (d > 0 && next != from) markDone(sel, from)
        totalSec = 0                                     // 换集后时长必须由新视频回填
        loadProgress()                                   // 再读新一集自己的进度
    }

    // 倍速：原型本来就有两套档位，各归其位，禁止混用
    // - presetSpeeds：设置页「默认倍速」ENUM / 步进循环，对应原型 SPEEDS = [0.75,1,1.25,1.5,2]
    // - playMenuSpeeds：播放页上拉菜单 data-spd，对应原型 #p-speed .psi = 0.5/0.75/1/1.5/2/3
    val presetSpeeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    val playMenuSpeeds = listOf(0.5f, 0.75f, 1f, 1.5f, 2f, 3f)

    /** 播放页「视频比例」上拉菜单的档位，索引即 ratioMode */
    val ratioPresets = listOf("原始比例", "铺满屏幕", "16:9", "4:3", "21:9", "拉伸全屏")

    fun spdText(v: Float): String {
        val r = Math.round(v * 100) / 100f
        return if (Math.round(r * 100) % 10 == 0) String.format("%.1fx", r) else String.format("%.2fx", r)
    }

    fun openSpeedMenu() {
        speedMenu = true
        // 原型 pOpenSpeed()：自定义命中 → custom；否则按菜单档位找；找不到 → 落到 1.0x（psi:3）
        val target = when {
            speed == customVal && customSet -> "psi:0"
            else -> {
                val i = playMenuSpeeds.indexOf(speed)
                if (i >= 0) "psi:${i + 1}" else "psi:3"
            }
        }
        setFocus(target)
    }

    fun closeSpeedMenu() {
        speedMenu = false
        setFocus("pmenu:2")
    }

    fun pickSpeed(v: Float) {
        speed = v
        customSet = false
        closeSpeedMenu()
    }

    fun openCustom() {
        customOpen = true
        customVal = snapCustom(speed)
    }

    fun snapCustom(v: Float): Float {
        val s = Math.round(v / 0.05f) * 0.05f
        return Math.round(s.coerceIn(0.2f, 3f) * 100) / 100f
    }

    fun customStep(d: Int) {
        customVal = snapCustom(customVal + d * 0.05f)
    }

    fun applyCustom() {
        customOpen = false
        customSet = true
        speed = customVal
        speedMenu = false
        setFocus("pmenu:2")
    }

    fun cancelCustom() {
        customOpen = false
        setFocus("psi:0")
    }

    // ==================================================================================
    // 视频比例：与倍速同一套交互（底部菜单按钮 → 按钮上方上拉列表 → 选中即生效）
    // 预设只给最常用的几档：原始（按 DAR 完整显示）／铺满（保比例裁切去黑边）／
    // 16:9、4:3、21:9 三档强制画幅／拉伸全屏（不保比例）。
    // ==================================================================================
    var ratioMenu by mutableStateOf(false)
    var ratioMode by mutableStateOf(RATIO_ORIGINAL)

    fun ratioText(): String = ratioPresets.getOrElse(ratioMode) { "原始比例" }

    fun openRatioMenu() {
        ratioMenu = true
        setFocus("prt:$ratioMode")
    }

    fun closeRatioMenu() {
        ratioMenu = false
        setFocus("pmenu:3")
    }

    fun pickRatio(i: Int) {
        ratioMode = i.coerceIn(0, ratioPresets.size - 1)
        ratioMenu = false
        setFocus("pmenu:3")
    }

    /** 播放器报错：先分类，再决定走失败层还是轻提示（不静默黑屏） */
    fun onPlayerError(msg: String) {
        val m = msg.lowercase()
        when {
            m.contains("0xc00000c9") || m.contains("request_not_accepted") ||
                m.contains("network_session_expired") || m.contains("insuff_server_resources") ||
                m.contains("logon_failure") -> {
                smbFull = true
                failVisible = true
                smbFailN = maxOf(1, smbFailN)
            }
            // 会话断开 / 自动重连失败：必须给可见的诊断层，不能只闪一个 toast 后静默定格
            m.contains("smb") || m.contains("未连接") || m.contains("重连失败") -> {
                openSmbDiag(
                    tag = "播放中断 · SMB 连接已断开",
                    title = "读不出视频：共享连接已断开",
                    sub = "播放期间与电脑共享的连接断了，自动重连没有成功。请确认电脑没有休眠或重启、网络仍通，再点下面的按钮重试。错误信息：${msg.take(80)}",
                    retryLabel = "重新连接并继续播放",
                    retry = {
                        Thread {
                            com.chunting.purecourse.data.SmbSession.resetReconnectBackoff()
                            val ok = com.chunting.purecourse.data.SmbSession.ensureConnected(force = true)
                            ui.post {
                                if (ok) {
                                    playing = true
                                    failVisible = false
                                    toast("已重新连上，继续播放")
                                } else {
                                    toast("还是连不上：检查电脑共享与网络")
                                }
                            }
                        }.start()
                    },
                )
            }
            m.contains("connect") || m.contains("timeout") || m.contains("host") ->
                toast("连不上片源：检查共享与网络")
            else -> toast("播放失败：${msg.take(60)}")
        }
    }

    // ================= SMB 连接数耗尽 ===================
    var smbFull by mutableStateOf(false)
    var smbFailN by mutableStateOf(0)
    var failVisible by mutableStateOf(false)
    var diagVisible by mutableStateOf(false)
    var diagTag by mutableStateOf("连接诊断 · Windows 共享拒绝连接")
    var diagTitle by mutableStateOf("电脑共享的连接数已满，不是网络问题")
    var diagSub by mutableStateOf("")
    var diagRetryLabel by mutableStateOf("我已清线程，重新连接")
    private var diagRetry: (() -> Unit)? = null
    private var diagPrevFocus: String? = null

    fun openSmbDiag(
        tag: String = "连接诊断 · Windows 共享拒绝连接",
        title: String = "电脑共享的连接数已满，不是网络问题",
        sub: String = "",
        host: String? = null,
        retryLabel: String = "我已清线程，重新连接",
        retry: (() -> Unit)? = null,
    ) {
        diagTag = tag
        diagTitle = title
        diagSub = sub.ifEmpty {
            "连 <b>${host ?: "电脑"}</b> 时被电脑拒绝了。电脑没坏、网络也没断，是那边的共享会话被占满了。"
        }
        diagRetryLabel = retryLabel
        diagRetry = retry
        diagPrevFocus = focusTag
        diagVisible = true
        setFocus("sd:0")
    }

    fun closeSmbDiag() {
        diagVisible = false
        val p = diagPrevFocus
        diagPrevFocus = null
        diagRetry = null
        if (p != null) setFocus(p)
    }

    fun smbDiagAct(v: String) {
        when (v) {
            "close" -> closeSmbDiag()
            // 只清"本机"的会话：服务端那条随之释放；别人的/僵尸会话清不掉（只能去电脑上跑 bat）
            "disconnect" -> {
                val n = com.chunting.purecourse.data.SmbSession.disconnectAll()
                toast(
                    if (n > 0) "已断开本机 $n 条 SMB 会话（电脑那边会随之释放）"
                    else "本机当前没有打开的 SMB 会话"
                )
            }
            "retry" -> {
                val cb = diagRetry
                closeSmbDiag()
                cb?.invoke()
            }
        }
    }

    fun playerFailShow() {
        smbFailN = maxOf(1, smbFailN)
    }

    fun pfRetry() {
        if (smbFull) {
            smbFailN++
            if (smbFailN >= 2) {
                openSmbDiag(
                    tag = "播放中断 · 连接被电脑拒绝",
                    title = "读不出视频：电脑共享的连接数已满",
                    host = sourceLabel(),
                    retryLabel = "我已清线程，重试读取",
                    retry = { pfRetry() },
                )
            } else {
                toast("还是被拒：电脑那边的会话没清，读不到数据")
            }
            return
        }
        playing = true
        setFocus("pstage")
        toast("已重新连上，继续播放")
    }

    /**
     * 启动时把「已保存的真实数据源」还原成一张卡片。
     *
     * 之前 sources 是纯内存的 Demo 列表，**杀进程就回到 4 张假卡片**，
     * 用户配好的共享等于白配。这里从 SourceStore 读回配置生成卡片，
     * 并在后台真连一次核实条目数（连不上就标红，不假装"已连接"）。
     */
    fun restoreRealSource() {
        // tv5：一次扫描同时产出数据源卡片、书架课程、剧集、浏览树与收藏根，
        // 不再有「演示卡片 + 后台补一个数字」这种各说各话的状态。
        loadRealCatalog()
    }

    // ================= 数据源 =================
    var addStep by mutableStateOf(Step.Proto)
    var addProto by mutableStateOf<Proto?>(null)
    /** 自定义显示名（可选）：留空就用 IP 当名字 */
    var addName by mutableStateOf("")
    var addHost by mutableStateOf("")
    var addPort by mutableStateOf("")
    var addPath by mutableStateOf("")
    var addUser by mutableStateOf("")
    var addPwd by mutableStateOf("")
    var addEditing by mutableStateOf<String?>(null)
    /** 正在编辑的**数据源 id**（null = 新增）。与 [addEditing]（正在编辑哪个输入框）不是一回事 */
    var addEditSourceId by mutableStateOf<String?>(null)
    var addRemember by mutableStateOf(true)
    /** WebDAV：是否走 HTTPS（false = 明文 http，内网 NAS / OpenList 常见） */
    var addDavHttps by mutableStateOf(false)
    /** WebDAV：忽略证书校验（自签证书 / 群晖 / Alist） */
    var addIgnoreCert by mutableStateOf(false)
    var addTestState by mutableStateOf("idle")   // idle | ok | bad
    /** 真实测试结果文案（成功：共享与条目数；失败：分类原因）。凭空编是禁止的 */
    var addTestMsg by mutableStateOf("")
    var addTestErr by mutableStateOf("")
    /** 测试时真实读到的条目数（保存成数据源卡片上的"已连接 · N 个顶层目录"，不再硬编码 6） */
    var addTestCount by mutableStateOf(0)

    /**
     * 「共享 / 路径」留空时，点「测试连接」会去枚举这台主机上的全部共享，
     * 结果放这里，右侧面板列出来供用户确认选择（不再强行要求先手打共享名）。
     */
    var addShares by mutableStateOf<List<com.chunting.purecourse.data.SmbShares.Share>>(emptyList())
    /** 正在枚举共享（右侧面板显示"正在查询…"） */
    var addSelectBusy by mutableStateOf(false)

    /**
     * 编辑态光标位置（字符下标）。
     *
     * 为什么不交给 TextField / 输入法自己管：电视输入法（搜狗 TV 版）会吃掉硬件方向键，
     * 光标只能停在末尾，用户就没法把光标移到前面去「退格删左边」。所以左右键在
     * MainActivity.dispatchKeyEvent 里被直接接管，只改这个值；输入框再把它同步成 selection。
     */
    var editCaret by mutableStateOf(0)
        private set

    fun fieldText(k: String?): String = when (k) {
        "alias" -> aliasText
        "name" -> addName
        "host" -> addHost
        "port" -> addPort
        "path" -> addPath
        "user" -> addUser
        "pwd" -> addPwd
        else -> ""
    }

    /** 进入编辑时把光标放到末尾（与用户直觉一致） */
    fun setCaretToEnd(k: String?) {
        editCaret = fieldText(k).length
    }

    /** 左右键：直接在字符下标上加减，不依赖输入法 */
    fun moveCaret(delta: Int) {
        val k = addEditing ?: return
        val len = fieldText(k).length
        editCaret = (editCaret + delta).coerceIn(0, len)
    }

    /** 输入 / 退格后由输入框回报真实 selection，避免两边不一致 */
    fun syncCaret(pos: Int) {
        val k = addEditing ?: return
        editCaret = pos.coerceIn(0, fieldText(k).length)
    }

    fun resetAddForm() {
        addStep = Step.Proto
        addProto = null
        addName = ""
        addHost = ""; addPort = ""; addPath = ""; addUser = ""; addPwd = ""
        addShares = emptyList()
        addEditing = null
        addEditSourceId = null
        addTestState = "idle"
        addTestMsg = ""
        addTestErr = ""
        addTestCount = 0
        addRemember = true
        addDavHttps = false
        addIgnoreCert = false

        // 预填「上次成功保存过」的配置（不含密码）：
        // TV 上打一遍 IP + 共享名很费劲，改端口或换目录时不该让用户从头再敲一遍。
        // 多源时取**最后添加的那个源**（列表末尾），它才是用户最近在折腾的。
        runCatching {
            val cfg = com.chunting.purecourse.data.SourceStore.list(App.instance).lastOrNull()
            if (cfg != null) fillFormFrom(cfg)
        }
    }

    /** 把一条已存配置灌进添加/编辑表单（密码由调用方决定是否回填） */
    fun fillFormFrom(cfg: SourceStore.Cfg, withPwd: Boolean = false) {
        addProto = cfg.proto
        addName = cfg.name
        addIgnoreCert = cfg.ignoreCert
        if (cfg.proto == Proto.DAV) {
            // baseUrl 是拼好的完整地址，回填时要拆回「协议 / 地址 / 端口 / 路径」
            val u = runCatching { java.net.URI(cfg.baseUrl) }.getOrNull()
            addDavHttps = u?.scheme?.equals("https", ignoreCase = true) == true
            addHost = u?.host ?: cfg.host
            addPort = u?.port?.takeIf { it > 0 }?.toString() ?: ""
            addPath = u?.path?.trim('/') ?: cfg.root
        } else {
            addHost = cfg.host
            addPort = cfg.port.toString()
            addPath = if (cfg.root.isEmpty()) cfg.share else "${cfg.share}/${cfg.root}"
        }
        addUser = cfg.user
        if (withPwd) addPwd = cfg.pass
    }

    /** 进入「编辑已有数据源」：把该源配置（含密码）灌回表单，并记住要在保存时覆盖它 */
    fun beginEditSource(id: String) {
        val cfg = SourceStore.get(App.instance, id) ?: run { toast("这个数据源已经不在了"); return }
        resetAddForm()
        fillFormFrom(cfg, withPwd = true)
        addEditSourceId = id
        addStep = Step.Form
        go(Page.AddSrc)
        setFocus("fld:host")
    }

    fun pickProto(p: Proto) {
        addProto = p
        // WebDAV 的端口留空，由 davUrlParts 按 http/https 取 80/443 默认值；
        // 预填 443 会让「明文 http + 19798」这类常见组合被悄悄改成 http://ip:443。
        addPort = if (p == Proto.DAV) "" else p.port.toString()
        addStep = Step.Form
        setFocus("fld:host")
    }

    fun fillFrom(e: Endpoint) {
        addProto = e.proto
        addHost = e.host; addPort = e.port.toString(); addPath = e.path
        // 之前把遮蔽用的圆点当成密码塞进字段，快捷位置一点必然认证失败
        addUser = e.user; addPwd = e.pass
        addStep = Step.Form
        setFocus("fld:host")
    }

    /**
     * WebDAV 表单 → (完整 baseUrl, 纯主机名)。
     *
     * 协议以表单上的 HTTP/HTTPS 开关为准；但若地址栏里显式写了 `https://` / `http://`
     * 前缀，以前缀为准（兼容用户直接粘贴一整条 URL）。
     * 端口同理：地址里写了 `host:port` 就用地址里的。
     */
    fun davUrlParts(): Pair<String, String> {
        var h = addHost.trim()
        val hasHttps = h.startsWith("https://", true)
        val hasHttp = h.startsWith("http://", true)
        h = when {
            hasHttps -> h.substring(8)
            hasHttp -> h.substring(7)
            else -> h
        }
        h = h.trim().trimEnd('/')
        val scheme = when {
            hasHttps -> "https"
            hasHttp -> "http"
            addDavHttps -> "https"
            else -> "http"
        }
        val hostOnly = h.substringBefore('/').substringBefore(':')
        val addrPort = h.substringBefore('/').substringAfter(':', "")
        val portPart = addrPort.ifEmpty { addPort.trim() }
        val path = addPath.trim().trim('/', '\\').replace('\\', '/')
        val sb = StringBuilder(scheme).append("://").append(hostOnly)
        if (portPart.isNotEmpty()) sb.append(':').append(portPart)
        if (path.isNotEmpty()) sb.append('/').append(path)
        return sb.toString() to hostOnly
    }

    /** 把「共享 / 路径」拆成 share 与 root：第一段是共享名，其余是共享内目录 */
    fun splitPath(): Pair<String, String> {
        val p = addPath.trim().trim('/', '\\')
        if (p.isEmpty()) return "" to ""
        val first = p.substringBefore('/').substringBefore('\\')
        val rest = p.removePrefix(first).trim('/', '\\')
        return first to rest
    }

    /**
     * 真实测试连接（不是占位判定）。
     *
     * 在后台线程连 SMB → 列一次目录 → 把真实条目数写回界面。
     * 失败按 lastError 分类成"账号密码 / 连接数满 / 网络不通 / 路径不存在"，
     * 让用户知道该改哪一项，而不是笼统地说"连接失败"。
     */
    fun startTestConn() {
        addStep = Step.Test
        addTestState = "idle"
        addTestMsg = ""
        addTestErr = ""
        setFocus("fbtn:0")

        val host = addHost.trim()
        val (share, root) = splitPath()
        val user = addUser.trim()
        val pwd = addPwd

        // ---- WebDAV 测试连接：PROPFIND 列一层，有响应即通过 ----
        if (addProto == Proto.DAV) {
            val (davUrl, davHost) = davUrlParts()
            if (davHost.isEmpty()) {
                addTestState = "bad"
                addTestErr = "服务器地址不能为空，例如 192.168.1.10"
                return
            }
            addSelectBusy = true
            // 必须切到结果页：原来 WebDAV 测完仍停在表单，成功时界面上
            // 一句反馈都没有（底部只在失败时显示），用户以为按了没反应。
            addEditing = null
            addStep = Step.Test
            addTestState = "idle"
            addTestMsg = ""
            addTestErr = ""
            addTestCount = 0
            val userD = addUser.trim()
            val pwdD = addPwd
            val ignore = addIgnoreCert
            // 路径已经并进 baseUrl，这里从这一层直接列
            Thread {
                val (err, count) = com.chunting.purecourse.data.DavClient.test(davUrl, userD, pwdD, ignore, "")
                ui.post {
                    addSelectBusy = false
                    if (err == null) {
                        addTestState = "ok"
                        addTestCount = count
                        addTestMsg = "已连上 WebDAV · $davUrl 下 $count 项"
                        addTestErr = ""
                    } else {
                        addTestState = "bad"
                        addTestErr = err
                        addTestCount = 0
                    }
                    setFocus("fbtn:0")
                }
            }.start()
            return
        }

        if (host.isEmpty()) {
            addTestState = "bad"
            addTestErr = "主机 / IP 不能为空"
            return
        }

        val port = addPort.toIntOrNull() ?: 445

        // 路径留空 = 整机模式：这台主机上有哪些共享，就全都作为左侧目录的根。
        // 不再逼用户「先选一个共享」—— 那等于把一台电脑拆成四五个数据源，
        // 换个共享还得回编辑页重填。
        if (share.isEmpty()) {
            addStep = Step.Form
            addTestState = "idle"
            addTestMsg = ""
            addTestErr = ""
            addSelectBusy = true
            toast("正在查询 $host 的共享列表…")
            Thread {
                val (err, list) = com.chunting.purecourse.data.SmbShares.list(host, user, pwd, port)
                ui.post {
                    addSelectBusy = false
                    if (list.isNotEmpty()) {
                        addShares = list
                        addTestState = "ok"
                        addTestCount = list.size
                        addTestMsg = "整机模式 · 这台主机的 ${list.size} 个共享都会出现在目录树里"
                        addTestErr = ""
                    } else {
                        addShares = emptyList()
                        addTestState = "bad"
                        val why = err?.lineSequence()?.firstOrNull()?.trim().orEmpty()
                        addTestErr = buildString {
                            append("读不到共享列表")
                            if (why.isNotEmpty()) append("（$why）")
                            append("。\n也可以手填一个共享名，例如 我的课程。")
                        }
                        setFocus("fld:path")
                    }
                }
            }.start()
            return
        }

        Thread {
            val err = com.chunting.purecourse.data.SmbSession.connect(host, share, user, pwd, port)
            var st = "bad"
            var msg = ""
            var errTxt = ""
            var count = 0
            if (err == null) {
                val n = runCatching { com.chunting.purecourse.data.SmbSession.list(root).size }
                if (n.isSuccess) {
                    st = "ok"
                    count = n.getOrThrow()
                    msg = "已连上 · \\\\$host\\$share" +
                        (if (root.isEmpty()) "" else "\\$root") +
                        " 下有 $count 项"
                } else {
                    errTxt = "共享已连上，但读不到路径「$root」：" +
                        (n.exceptionOrNull()?.message ?: "未知原因")
                }
            } else {
                count = 0
                errTxt = when (err) {
                    "auth" -> "账号或密码不对（Windows 也要求输入正确密码）"
                    "full" -> "电脑那边 SMB 连接数已满，先清掉僵尸会话再试"
                    "network" -> "连不上 $host:$port · 检查 IP、是否同一网段、电脑防火墙"
                    "path" -> "共享「$share」不存在，或没有访问权限"
                    else -> com.chunting.purecourse.data.SmbSession.lastError ?: err
                }
            }
            ui.post {
                addTestState = st
                addTestMsg = msg
                addTestErr = errTxt
                addTestCount = count
            }
        }.start()
    }

    fun saveSource(ok: Boolean) {
        val p = addProto ?: Proto.SMB
        val editId = addEditSourceId

        // ---- WebDAV 保存：地址 + 端口 + 路径 拼成完整 baseUrl，共享概念不适用 ----
        if (p == Proto.DAV) {
            val (url, hostOnly) = davUrlParts()
            if (hostOnly.isEmpty()) { toast("先填服务器地址（IP 或域名）"); return }
            val uri = runCatching { java.net.URI(url) }.getOrNull()
            val portNum = uri?.port?.takeIf { it > 0 }
                ?: if (url.startsWith("https", true)) 443 else 80
            val cfg = com.chunting.purecourse.data.SourceStore.Cfg(
                id = editId ?: "",
                proto = Proto.DAV,
                name = addName.trim(),
                host = hostOnly,
                share = "",
                user = addUser.trim(),
                pass = addPwd,
                // 路径已经并进 baseUrl，root 留空 = 从 baseUrl 这一层开始扫
                root = "",
                port = portNum,
                baseUrl = url,
                ignoreCert = addIgnoreCert,
            )
            finishSave(cfg, ok, editId)
            return
        }

        val (share, root) = splitPath()
        // 共享名为空 = 整机模式：一个 IP 一个数据源，所有共享都作为浏览根。
        // 只有确实没读到任何共享（addShares 空）且没手工填共享名时才提示一下。
        if (share.isEmpty() && addShares.isEmpty() && !ok && editId == null) {
            toast("先点「测试连接」读出共享列表，或直接手填一个共享名")
            return
        }
        val portNum = addPort.toIntOrNull() ?: p.port
        val cfg = com.chunting.purecourse.data.SourceStore.Cfg(
            id = editId ?: "",
            proto = p,
            name = addName.trim(),
            host = addHost.trim(),
            share = share,
            user = addUser.trim(),
            pass = addPwd,
            root = root,
            port = portNum,
        )
        finishSave(cfg, ok, editId)
    }

    /**
     * 落盘 + 上屏 + 重扫。
     *
     * 多源下这里是「新增或覆盖某一条」，不是"覆盖唯一那条"：
     * 保存 WebDAV 不会再把 SMB 顶掉。
     */
    private fun finishSave(cfg: com.chunting.purecourse.data.SourceStore.Cfg, ok: Boolean, editId: String?) {
        // 回读校验：写失败（磁盘满 / 序列化异常）要能立刻告诉用户，
        // 而不是提示"已保存"、回去一看根本没有。
        val saved = runCatching { com.chunting.purecourse.data.SourceStore.upsert(App.instance, cfg) }.getOrNull()
        if (saved == null) {
            toast("保存失败：配置没写入（${cfg.host.ifBlank { cfg.baseUrl }}），请再按一次保存")
            return
        }
        // 改了地址/路径 → 这个源的旧扫描缓存必须作废，否则会把上一个位置的内容显示出来
        if (editId != null) runCatching { com.chunting.purecourse.data.CatalogCache.clear(App.instance, saved) }

        val list = com.chunting.purecourse.data.SourceStore.list(App.instance)
        sourceCfgs = list
        sources = buildSources(list)
        val label = saved.displayName()
        resetAddForm()
        go(Page.Sources)
        toast(if (editId != null) "已更新「$label」，正在重新扫描…" else "已添加「$label」，正在扫描…")
        loadRealCatalog()
    }

    // ================= 弹层 =================
    var toastMsg by mutableStateOf<String?>(null)
    var toastSeq by mutableStateOf(0)
        private set

    fun toast(msg: String) {
        toastMsg = msg
        toastSeq++
        toastTimer?.let { ui.removeCallbacks(it) }
        val seq = toastSeq
        val r = Runnable { if (seq == toastSeq) toastMsg = null }
        toastTimer = r
        ui.postDelayed(r, 2400)
    }

    var cfmTitle by mutableStateOf("")
    var cfmDesc by mutableStateOf("")
    var cfmVisible by mutableStateOf(false)
    /** 确认键文案：默认「确认」；「播放并加入书架」这类动作用自己的动词更好懂 */
    var cfmOkText by mutableStateOf("确认")
    var cfmCancelText by mutableStateOf("取消")
    private var cfmCb: (() -> Unit)? = null
    private var cfmPrev: String? = null

    fun openCfm(
        title: String,
        desc: String,
        okText: String = "确认",
        cancelText: String = "取消",
        cb: () -> Unit,
    ) {
        cfmTitle = title; cfmDesc = desc; cfmCb = cb
        cfmOkText = okText; cfmCancelText = cancelText
        cfmPrev = focusTag
        cfmVisible = true
        setFocus("cfm:0")
    }

    fun closeCfm() {
        cfmVisible = false
        val p = cfmPrev
        cfmCb = null
        cfmPrev = null
        if (p != null) setFocus(p) else setFocus(defaultFocus())
    }

    fun cfmAct(v: String) {
        if (v == "cancel") {
            toast("已取消")
            closeCfm()
            return
        }
        val cb = cfmCb
        cfmVisible = false
        cfmCb = null
        cb?.invoke()
    }

    var fpopVisible by mutableStateOf(false)
    var fpopIndex by mutableStateOf(-1)
    /** 弹窗内容快照：标题/副标题/两个动作是否可用，全部在打开时定死，
     *  避免"弹窗打开后树状态变了 → 标题变成别的目录"这类错位 */
    var fpopName by mutableStateOf("")
    var fpopSub by mutableStateOf("")
    var fpopShelfDisabled by mutableStateOf(false)
    var fpopFavDisabled by mutableStateOf(false)
    private var fpopKey by mutableStateOf("")
    private var fpopPrev: String? = null
    /** 打开弹窗那一条的快照：收藏页与浏览页共用同一个弹窗，动作对象只能来自快照 */
    private var fpopItem: FsItem? = null

    /** 收藏页：按下目录行 → 打开文件夹操作小框 */
    fun openFpop(i: Int) {
        val key = ftKeyNow()
        val it = mcItems(key).getOrNull(i) ?: return
        openFpopFor(it, mcChild(key, i), "fpl:$i")
    }

    /**
     * 浏览页：按下目录行 → 打开**同一个**文件夹操作小框。
     *
     * 之前浏览页的确认键直接加书架，只在底部弹一句 toast，和收藏页的弹窗完全是两套东西；
     * 用户看到同一件事在两个页面上长得完全不一样。现在两页共用一个弹窗。
     */
    fun openFpopBrowse(i: Int) {
        val it = curNode()?.items?.getOrNull(i) ?: return
        if (it.type != ItemType.DIR) return
        openFpopFor(it, it.relPath, "brow:$i")
    }

    private fun openFpopFor(it: FsItem, ck: String?, prev: String) {
        if (it.type != ItemType.DIR) return
        fpopVisible = true
        fpopItem = it
        fpopKey = ck ?: ""
        fpopPrev = prev
        fpopName = it.name
        fpopSub = it.countText ?: "文件夹"
        fpopShelfDisabled = ck == null || onShelfKey(ck, it.name)
        fpopFavDisabled = ck == null || inFavKey(ck)
        // 焦点只落在"可用"的动作上；两个都不可用就停在第一项（确认无动作、返回关闭）
        setFocus(if (!fpopShelfDisabled) "fpop:0" else if (!fpopFavDisabled) "fpop:1" else "fpop:0")
    }

    /** 当前弹窗里"可聚焦的动作"下标（不可用的直接跳过，与原型 dis 态一致） */
    fun fpopEnabled(): List<Int> = buildList {
        if (!fpopShelfDisabled) add(0)
        if (!fpopFavDisabled) add(1)
        // 备注名只要这一条有真实路径就能改，永远可用
        if (fpopItem?.relPath != null) add(2)
    }

    /** 打开弹窗时那一条的快照（Nav.fpopAct 用） */
    fun fpopItemSnapshot(): FsItem? = fpopItem

    fun closeFpop() {
        fpopVisible = false
        fpopItem = null
        val p = fpopPrev
        fpopPrev = null
        if (p != null) setFocus(p) else setFocus("fpl:${ftRow}")
    }

    // ================= 备注名（解决多源同名目录）=================
    var aliasOpen by mutableStateOf(false)
        private set
    var aliasText by mutableStateOf("")
        private set
    /** 正在改备注的完整路径（带源前缀） */
    var aliasPath by mutableStateOf("")
        private set
    /** 弹窗标题里显示的真实目录名 */
    var aliasOrig by mutableStateOf("")
        private set
    private var aliasPrev: String? = null

    /**
     * 打开备注名编辑框。
     *
     * 为什么需要它：SMB 和 WebDAV 里都可能有「钢琴课」，扫描自动识别成课程后
     * 书架上是两张同名卡、收藏树上是两个同名节点，用户没法区分也没法选。
     * 备注名按「带源前缀的完整路径」存（[com.chunting.purecourse.data.AliasStore]），
     * 两个同名目录可以各自起名。
     */
    fun openAliasEditor(item: FsItem) {
        val p = item.relPath
        if (p.isNullOrBlank()) { toast("这个目录没有可识别的路径"); return }
        aliasPath = p
        aliasOrig = SrcPath.relOf(p).substringAfterLast('\\').ifEmpty { item.name }
        aliasText = aliasMap[p] ?: ""
        aliasOpen = true
        aliasPrev = focusTag
        // 进来直接就是输入态：遥控器上少按一次确认；退出编辑按返回或上/下键
        addEditing = "alias"
        setCaretToEnd("alias")
        setFocus("aliasfld")
    }

    fun closeAliasEditor(save: Boolean) {
        if (save) {
            // 备注名和原名完全一样就等于没改，顺手把旧备注清掉（否则改回原名永远删不掉备注）
            val v = aliasText.trim()
            setAlias(aliasPath, if (v == aliasOrig) "" else v)
        }
        aliasOpen = false
        addEditing = null
        val p = aliasPrev
        aliasPrev = null
        if (p != null && containsTag(p)) setFocus(p) else setFocus(defaultFocus())
    }

    /** 备注名输入框里的一次字符变化 */
    fun onAliasTextChange(v: String) {
        aliasText = v
        editCaret = editCaret.coerceIn(0, v.length)
    }


    // ================= 路由 =================
    fun go(p: Page, record: Boolean = true, focus: String? = null, noRecall: Boolean = false) {
        val from = page
        // 先取「上次离开该页时记录的落点」，再记录本次发起控件（原型 go() 的顺序）
        val prevOrigin = if (p == from) null else origin[p.route]
        // 顶栏 Tab 永远不作为「来源控件」被记录：它是"焦点到达即切页"的触发器，
        // 一旦在进入页面时被还原成焦点，就会立刻把页面弹回首页（原型踩过的坑）
        if (p != from && record && !focusTag.startsWith("top:")) origin[p.route] = focusTag
        if (p == Page.Browse && from != Page.Browse) browFrom = from
        // 记住「从哪一页进的播放器」：片尾播完要原路弹回，不能写死回详情页
        if (p == Page.Player && from != Page.Player) playerFrom = from
        page = p
        // 点开一本书 = 「显示」和「刷新」分两步：
        // 详情页立刻用手里的剧集画出来（0 等待），后台只补读这一层，读完有增删再替换。
        // 这是"点开书先显本地清单、再后台更新"的落点，冷目录单次 8 秒也不挡人。
        if (p == Page.Detail && from != Page.Detail) refreshCourseDir(sel)
        if (p == Page.Player) {
            // 锁定「正在播放的课程」：之后 sel 被别的页面挪动也不影响进度归属
            playCourse = sel
            resetPlayer()
            loadProgress()
            com.chunting.purecourse.data.SmbSession.onPlayerEnter()
            // 进播放页先判断是不是还卡在「连接数已满」：直接给原因，不装作在播
            smbFailN = 0
            failVisible = smbFull
            if (failVisible) setFocus("pfail:0")
        }
        if (from == Page.Player && p != Page.Player) {
            saveProgress()
            com.chunting.purecourse.data.SmbSession.onPlayerExit()
            loop = false; speedMenu = false; customOpen = false
        }
        val first = focus ?: if (!noRecall) prevOrigin else null
        if (first != null && containsTag(first)) setFocus(first) else setFocus(defaultFocus())
    }

    fun tabAct(t: HomeTab) {
        applyTab(t)
        if (page != Page.Home) go(Page.Home) else setFocus(tabLanding(t))
        setFocus(tabLanding(t))
    }

    fun applyTab(t: HomeTab) {
        tabTimer?.let { ui.removeCallbacks(it) }
        tabTimer = null
        tab = t
        if (t != HomeTab.Fav && favRmOn) favRmExit()
        if (t == HomeTab.Fav) {
            ftZone = "tree"
            // 进收藏页先把 ftKey 对齐到左树当前行，并把可能越界的 ftRow 收回 0
            ftKey = ftKeyNow()
            if (ftRow >= mcItems(ftKey).size) ftRow = 0
        }
        setFocus(tabLanding(t))
    }

    fun tabLanding(t: HomeTab): String =
        if (t == HomeTab.Fav) "ft:0" else "hero"

    private fun resetPlayer() {
        ctl = false
        playing = true
        mirror = false
        // 设置页的「默认倍速」必须真的生效：之前恒为 1f，那一行等于摆设
        speed = if (defaultSpeed in presetSpeeds) defaultSpeed else 1f
        speedMenu = false
        customOpen = false
        customVal = 1f
        customSet = false
        loop = false
        osdOn = false
    }

    fun defaultFocus(): String = when (page) {
        Page.Home -> tabLanding(tab)
        Page.Detail -> "dact:0"
        Page.Browse -> "brow:0"
        Page.Settings -> "set:0"
        Page.Sources -> "src:local"
        Page.AddSrc -> if (addStep == Step.Proto) "proto:0" else "fbtn:0"
        Page.Player -> when {
            failVisible -> "pfail:0"
            ctl -> "ptrack"
            else -> "pstage"
        }
    }

    /**
     * 区域表缓存签名：只列「会改变可聚焦项集合」的状态。
     * **焦点本身不在里面**——焦点移动不改变区域结构，这正是能缓存的前提。
     */
    internal fun regionsSignature(): String {
        val sb = StringBuilder(112)
        sb.append(page.route).append('|').append(tab).append('|').append(sel).append('|')
        sb.append(ftSel).append('|').append(ftZone).append('|').append(ftRow).append('|')
        sb.append(favRmOn).append('|').append(ftOpen.hashCode()).append('|')
        sb.append(courses.size).append('|').append(epCount(sel)).append('|').append(sortMode).append('|')
        sb.append(sources.size).append('|').append(addStep).append('|').append(addProto).append('|')
        sb.append(addShares.size).append('|').append(addSelectBusy).append('|')
        sb.append(cfmVisible).append(fpopVisible).append(diagVisible).append(speedMenu)
        sb.append(customOpen).append(failVisible).append(ctl).append('|')
        // 比例上拉菜单开合会新增一组可选档位，漏了它焦点就移不进弹窗
        sb.append(ratioMenu).append(ratioMode).append('|')
        sb.append(browseStack.lastOrNull()).append('|').append(fs.size).append('|')
        sb.append(favs.hashCode()).append('|').append(courseEpisodes.size)
        // 下面这些会改变**区域表的内容**（多选条 / 收藏批量条 / 弹窗可选项）。
        // 漏掉它们时签名不变 → 复用旧表 → 新出现的区域被判成"不存在"，
        // setFocus 直接 return，遥控器表现就是「光标移不到那几个按钮」。
        sb.append('|').append(multiOn).append(multiCtx).append(multiSet.size)
        sb.append('|').append(favRmSet.size)
        sb.append('|').append(fpopShelfDisabled).append(fpopFavDisabled).append(fpopIndex)
        sb.append('|').append(courseDirs.size).append('|').append(shelvedKeys.size)
        sb.append('|').append(courses.count { it.out })
        // 备注名弹窗出现/关闭会新增一组按钮；漏了它，弹窗打开后焦点移不到「保存」
        sb.append('|').append(aliasOpen).append(aliasPath.hashCode())
        return sb.toString()
    }

    /** 顶栏 Tab / 设置入口的 120ms 防抖切页（原型 queueTabSwitch） */
    fun setFocus(tag: String?) {
        if (tag == null) return
        // 重复设置同一个 tag 时不必再校验整张区域表（扫描完成 / 重组回调会反复调到这里）
        if (tag != focusTag && !containsTag(tag)) return
        focusTag = tag
        lastFocusByPage[page.route] = tag
        // 收藏页：焦点走到哪一行，右栏就跟着显示哪一行。
        // 之前只在 OK / RIGHT 时才更新 ftSel，纯上下移动时左右两栏会错位，
        // 右栏显示的还是上一个目录的内容（用遥控器很难发现，只会觉得"点了没反应"）。
        if (tag.startsWith("ft:")) {
            val i = tag.removePrefix("ft:").toIntOrNull()
            if (i != null && i != ftSel) {
                ftSel = i
                ftKey = ftRows().getOrNull(i)?.key ?: ftKey
                ftRow = 0
            }
        }
        when {
            tag.startsWith("top:tab:") -> scheduleTabSwitch(tag)
            tag == "top:set" -> scheduleTabSwitch("top:set")
        }
    }

    private fun scheduleTabSwitch(tag: String) {
        tabTimer?.let { ui.removeCallbacks(it) }
        val r = Runnable {
            tabTimer = null
            if (focusTag != tag) return@Runnable
            if (tag == "top:set") {
                if (page == Page.Settings || page == Page.Sources || page == Page.AddSrc) return@Runnable
                go(Page.Settings, record = false)
                setFocus("top:set")
                return@Runnable
            }
            val name = if (tag.endsWith("fav")) HomeTab.Fav else HomeTab.Shelf
            // 播放页顶栏整条隐藏，不存在这个路径；其余页面「光标停到哪个 Tab 就显示哪页」
            if (page == Page.Player) return@Runnable
            if (page == Page.Home && name == tab) return@Runnable
            tab = name
            if (name == HomeTab.Fav) ftZone = "tree"
            if (page != Page.Home) {
                // 从设置 / 数据源 / 浏览 / 详情直接切回首页（不记来源，避免污染返回栈）
                go(Page.Home, record = false, noRecall = true, focus = tag)
            } else {
                setFocus(tag)
            }
        }
        tabTimer = r
        ui.postDelayed(r, 120)
    }

    // ================= 课程数据更新 =================
    fun updateCourse(i: Int, block: (Course) -> Course) {
        courses = courses.mapIndexed { idx, c -> if (idx == i) block(c) else c }
    }

    /** 最近播放的课程 index（hero 永远取它） */
    fun recentIdx(): Int {
        var best = -1
        courses.forEachIndexed { i, c ->
            if (c.out) return@forEachIndexed
            if (best < 0 || c.lastAt > courses[best].lastAt) best = i
        }
        return best
    }

    /** 书架顺序（原型 shelfOrder）：未移出 + 排序方式 */
    fun shelfOrder(): List<Int> {
        val idx = courses.indices.filter { !courses[it].out }
        return when (sortMode) {
            1 -> idx.sortedBy { courses[it].py }
            else -> idx.sortedByDescending { courses[it].lastAt }
        }
    }

    /**
     * 从浏览页把**真实目录**加入书架。
     * 之前只建了一张空卡片，课程没有绑定目录，结果所有课程播的都是同一个 root 下的视频。
     * 现在同步登记「课程 → 目录 → 真实剧集」，播放页据此取片源。
     */
    fun addToShelfByNode(
        item: FsItem,
        quiet: Boolean = false,
        onDone: ((Int) -> Unit)? = null,
    ): Boolean {
        if (item.type != ItemType.DIR) {
            if (!quiet) toast("视频文件不能加入书架")
            return false
        }
        val rel = item.relPath
        if (rel == null) { if (!quiet) toast("这个文件夹没有可识别的路径"); return false }
        // 已经在架（且没被移出）才算重复；被移出过的目录要允许重新加回来
        if (shelfCourseOf(rel) != null) { if (!quiet) toast("「${item.name}」已在书架中"); return false }
        // 之前移出过书架（Course.out = true）：复用同一张卡片，不要新建一门同名课。
        // 同样只能比「源 id + 源内路径」——按名字找会复用别的源里同名目录那张卡。
        val reuseId = courseDirs.entries.firstOrNull { sameShelfPath(it.value, rel) }?.key
        val cfg = cfgOfPath(rel) ?: return false
        // 网络层只认「源内路径」，界面层的 @s1\... 前缀在这里剥掉
        val inner = SrcPath.relOf(rel)
        val srcId = cfg.id

        Thread {
            // 必须按协议分流。原来这里无条件走 SmbSession：WebDAV 数据源下
            // 会拿 http 的地址/账号去连 445 端口，必然失败，
            // 用户看到的是「这个文件夹里没有可播放的视频」——完全是误导。
            // Triple<原始文件名, 播放路径（源内）, 字节数>
            val vids: List<Triple<String, String, Long>> = listVideosOneLayer(cfg, inner)
            ui.post {
                if (vids.isEmpty()) { toast("「${item.name}」里没有可播放的视频"); return@post }
                // 回到界面层：所有路径补上源前缀，否则课程目录会变成"无主路径"，
                // 播放时取不到配置、收藏时也会串到第一个源
                val list = vids.map {
                    Episode(RealCatalog.titleOf(it.first), SrcPath.of(srcId, it.second), it.third)
                }
                val ep0 = Episode(list.first().name, list.first().path, list.first().size)
                val id: Int
                if (reuseId != null && courses.getOrNull(reuseId) != null) {
                    // 重新加入：沿用原卡片（保留封面配色与播放进度）
                    id = reuseId
                    courses = courses.mapIndexed { k, c ->
                        if (k != id) c else c.copy(
                            name = item.name, abbr = item.name.take(2), out = false,
                            epCount = list.size,
                            curEp = c.curEp.coerceIn(1, list.size),
                            lastEpName = c.lastEpName.ifBlank { ep0.name },
                        )
                    }
                } else {
                    id = courses.size
                    val (a, b) = RealCatalog.coverColors(id)
                    courses = courses + Course(
                        id = id, name = item.name, abbr = item.name.take(2),
                        c1 = a, c2 = b, curEp = 1,
                        lastEpName = "尚未开始", lastAt = System.currentTimeMillis(),
                        lastTxt = "刚刚", py = item.name, epCount = list.size,
                        srcId = srcId, srcTag = cfg.tag(),
                    )
                }
                courseDirs = courseDirs + (id to rel)
                courseEpisodes = courseEpisodes + (id to list)
                shelvedKeys = shelvedKeys + rel
                // 落盘：否则进程一退就没了，冷启动时由 RealCatalog.scan 重新读出来
                runCatching {
                    SourceStore.addShelfPath(App.instance, rel)
                    SourceStore.removeHiddenPath(App.instance, rel)
                }
                fs = fs + (rel to FsNode(rel, item.name, list.map { ep ->
                    FsItem(ItemType.VID, ep.name,
                        countText = RealCatalog.sizeText(ep.size), relPath = ep.path, srcTag = cfg.tag())
                }))
                if (!quiet) toast("已加入书架：${item.name}（${list.size} 个视频）")
                onDone?.invoke(id)
            }
        }.start()
        return true
    }

    /**
     * 读「一个目录、只读这一层」里的视频列表（后台线程调用）。
     *
     * 三条协议必须分开走：
     *  - WebDAV 走 HTTP 的 PROPFIND，Depth:1，一次请求就是一层；
     *  - 本地源直接读挂载点目录（U 盘没有 445 端口，不能碰 SmbSession）；
     *  - SMB 才走 SmbSession。
     *
     * 返回 Triple<原始文件名, 播放路径（源内）, 字节数>，调用方负责补源前缀。
     * **绝不递归子目录**：层级不齐是常态（有的 2 层到视频，有的 3 层），
     * 递归会把几十上百个子目录全拉一遍，正是上一版"加一门课等几分钟"的根源。
     */
    private fun listVideosOneLayer(
        cfg: SourceStore.Cfg,
        inner: String,
    ): List<Triple<String, String, Long>> = when {
        cfg.proto == Proto.DAV -> com.chunting.purecourse.data.DavClient
            .list(cfg.baseUrl, cfg.user, cfg.pass, cfg.ignoreCert, inner)
            .getOrDefault(emptyList())
            .filter { !it.isDir && RealCatalog.isVideo(it.name) }
            .sortedBy { RealCatalog.naturalKey(it.name) }
            .map { Triple(it.name, it.relPath, it.size) }

        cfg.proto == Proto.LOCAL -> com.chunting.purecourse.data.LocalStore
            .entries(cfg, inner)
            .filter { !it.isDir && RealCatalog.isVideo(it.name) }
            .sortedBy { RealCatalog.naturalKey(it.name) }
            .map { Triple(it.name, it.path, it.size) }

        else -> {
            if (!com.chunting.purecourse.data.SmbSession.isConnected) {
                com.chunting.purecourse.data.SmbSession.connect(cfg.host, cfg.share, cfg.user, cfg.pass, cfg.port)
            }
            val (share, sub) = cfg.splitRel(inner)
            runCatching {
                if (cfg.wholeHost) com.chunting.purecourse.data.SmbSession.listIn(share, sub)
                else com.chunting.purecourse.data.SmbSession.list(sub)
            }
                .getOrDefault(emptyList())
                .filter { !it.isDir && RealCatalog.isVideo(it.name) }
                .sortedBy { RealCatalog.naturalKey(it.name) }
                // 整机模式下 Episode.path 要带共享前缀，否则播放时 uriOf 会把第一段当成共享名
                .map { Triple(it.name, if (cfg.wholeHost) "$share\\${it.path}" else it.path, it.size) }
        }
    }

    /** 正在后台刷新剧集的课程目录（防止连点/重组触发重复请求） */
    private val refreshingDirs = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    /**
     * 点开一本书时，后台重读**这门课自己那一层**（只读一层，绝不下钻）。
     *
     * 顺序是「先显示、后更新」：
     *  1. 详情页立刻用手里的剧集画出网格（0 等待，冷目录 8 秒也不挡人）；
     *  2. 这一层读回来若有增删，再悄悄替换剧集与集数；
     *  3. 读取失败不弹错 —— 点开一本书不该被一次网络抖动打断，旧清单继续能用。
     *
     * 播放时选中的那一集路径本来就是"目录 + 文件名"，所以就算源那边文件名变了，
     * 也只是这一集播不了，不会把整门课的清单搞乱。
     */
    fun refreshCourseDir(id: Int) {
        val rel = courseDirs[id] ?: return
        val cfg = cfgOfPath(rel) ?: return
        if (!refreshingDirs.add(rel)) return
        val inner = SrcPath.relOf(rel)
        val srcId = cfg.id
        val tag = cfg.tag()
        Thread {
            val vids = runCatching { listVideosOneLayer(cfg, inner) }.getOrNull()
            ui.post {
                refreshingDirs.remove(rel)
                if (vids.isNullOrEmpty()) return@post
                // 期间课程可能被移出书架/重建，只更新「这一门还是这个目录」的那张卡
                val cid = courseDirs.entries.firstOrNull { it.value == rel }?.key ?: return@post
                val list = vids.map {
                    Episode(RealCatalog.titleOf(it.first), SrcPath.of(srcId, it.second), it.third)
                }
                // 清单没变就什么都不做：避免一次无意义的重组让正在看的详情页闪一下
                courseEpisodes[cid]?.let { old ->
                    if (old.size == list.size && old.map { it.path } == list.map { it.path }) return@post
                }
                courseEpisodes = courseEpisodes + (cid to list)
                courses = courses.mapIndexed { k, c ->
                    if (k != cid) c else c.copy(
                        epCount = list.size,
                        curEp = c.curEp.coerceIn(1, list.size),
                        doneSet = c.doneSet.filter { it in 1..list.size }.toSet(),
                    )
                }
                // 顺手把这一层的树节点也换成最新的：从浏览页再点进来不必等重新读
                if (fs.containsKey(rel)) {
                    val nm = fs[rel]?.name ?: labelOfPath(rel)
                    fs = fs + (rel to FsNode(
                        rel, nm, list.map { ep ->
                            FsItem(
                                ItemType.VID, ep.name,
                                countText = RealCatalog.sizeText(ep.size),
                                relPath = ep.path, srcTag = tag,
                            )
                        },
                    ))
                }
            }
        }.start()
    }

    fun addFav(item: FsItem) {
        val rel = item.relPath
        if (rel == null) { toast("这个文件夹没有可识别的路径"); return }
        // 再点一次 = 取消收藏。遥控器上没必要为这点事再开一个弹层确认。
        if (favs.any { it.to == rel }) {
            favs = favs.filterNot { it.to == rel }
            runCatching { SourceStore.removeFavPath(App.instance, rel) }
            ftRowsCache = null
            toast("已取消收藏：${item.name}")
            return
        }
        val cfg = cfgOfPath(rel) ?: return
        // 落盘存**真名**，显示才用备注名：item.name 现在已经被套上备注名了，
        // 直接把备注名写进收藏表，清掉备注后名字会"粘住"改不回来。
        val realName = realNameOf(rel, item.name.ifBlank { labelOfPath(rel) })
        val shown = aliasMap[rel]?.takeIf { it.isNotBlank() } ?: realName
        favs = favs + Fav(shown, uncOfPath(rel), item.countText ?: "文件夹", rel, cfg.tag())
        // 落盘：否则退出进程就没了
        runCatching { SourceStore.addFavPath(App.instance, realName, rel) }
        ftRowsCache = null
        toast("已收藏：${shown}")
    }

    // ================= Miller 模型（收藏页 / 浏览页共用） =================
    data class McRow(
        val key: String,
        val depth: Int,
        val parent: Int?,
        val name: String,
        val has: Boolean,
        val cnt: String,
        val pickable: Boolean,
        /** 来源标签：跨源同名目录就靠它区分（空 = 我的收藏这类伪节点） */
        val srcTag: String = "",
    )

    fun mcBaseItems(): List<FsItem> = favs.map {
        FsItem(ItemType.DIR, it.name, to = it.to, countText = it.countText, relPath = it.to,
            srcTag = it.srcTag.ifBlank { null })
    }

    /** 目录项缓存签名：目录加载 / 收藏增删 / 课程扫描都会让它变化 */
    private fun mcSigNow(): String =
        "${fs.size}/${favs.size}/${courses.size}/${courseEpisodes.size}"

    /**
     * 目录项。**必须缓存**：收藏页一屏就要调十几次（树每层 + 右侧列表 + 弹窗判定），
     * 每次按键重建区域表时还会翻倍；原来每次都重新 map 一遍剧集列表，是卡顿放大器。
     */
    fun mcItems(key: String): List<FsItem> {
        val sig = mcSigNow()
        if (sig != mcSig) { mcCache.clear(); mcSig = sig }
        mcCache[key]?.let { return it }
        val v = when {
            key == "#fav" -> mcBaseItems()
            key.startsWith("#c") -> {
                // 课程目录里显示的是真实文件名，不再是编造的「00_课程导论与学习方法.mp4」
                val ci = key.drop(2).toIntOrNull() ?: -1
                courseEpisodes[ci]?.map { ep ->
                    FsItem(ItemType.VID, ep.name,
                        countText = RealCatalog.sizeText(ep.size), relPath = ep.path)
                } ?: emptyList()
            }
            else -> {
                ensureNode(key)
                fs[key]?.items ?: emptyList()
            }
        }
        mcCache[key] = v
        return v
    }

    fun mcName(key: String): String = when {
        key == "#fav" -> "我的收藏"
        key.startsWith("#c") -> courses.getOrNull(key.drop(2).toIntOrNull() ?: -1)?.name ?: "课程"
        key == "" -> "全部数据源"
        else -> fs[key]?.name ?: labelOfPath(key)
    }

    fun mcChild(key: String, i: Int): String? {
        val node = mcItems(key).getOrNull(i) ?: return null
        if (node.type != ItemType.DIR) return null
        val ci = node.course
        if (ci != null) return "#c$ci"
        // 真实目录：即使这一层还没读过，也要返回路径并触发异步补节点，
        // 否则收藏页里所有目录的弹窗动作都被判成「不可用」。
        val to = node.relPath ?: node.to ?: return null
        if (!fs.containsKey(to)) ensureNode(to)
        return to
    }

    /**
     * 浏览页左侧目录树：按**真实目录层级**展开。
     *
     * 之前左树只渲染 `favs`（收藏项），于是「全部共享」下明明有 4 个共享，
     * 左树却只有一个根 —— 用户看到的"左边"其实不能浏览。
     * 现在以数据源根为起点，沿 [browseStack] 逐层展开，和右侧列表始终是同一棵树。
     */
    fun browseRows(): List<McRow> {
        val sig = "${rootKey}/${browseStack.hashCode()}/${ftOpen.hashCode()}/${fs.size}/${courses.size}"
        brRowsCache?.let { if (sig == brRowsSig) return it }
        val out = ArrayList<McRow>()

        fun walk(key: String, depth: Int, parent: Int?) {
            val items = mcItems(key)
            for (node in items) {
                if (node.type != ItemType.DIR) continue
                val ck = node.relPath ?: node.to ?: continue
                // 展开状态完全由 ftOpen 决定（跟收藏页同一套）：这样当前所在的那一层
                // 也能被用户主动收起，不会出现「按了收起又自己弹回来」。
                val expanded = ftOpen[ck] == true
                if (expanded) {
                    val kids = mcItems(ck)
                    val sub = kids.count { it.type == ItemType.DIR }
                    val self = out.size
                    out.add(
                        McRow(
                            key = ck, depth = depth, parent = parent, name = node.name,
                            has = sub > 0,
                            cnt = if (sub > 0) "$sub 个文件夹" else "${kids.size} 项",
                            pickable = true,
                            srcTag = node.srcTag ?: "",
                        )
                    )
                    walk(ck, depth + 1, self)
                } else {
                    // 「能不能展开」必须看**真实子目录数**，不能猜 countText 里有没有「文件夹」三字。
                    // 之前整机模式下共享节点的 countText 是"识别出的课程数"，课程共享顶层
                    // 三个目录都不直接含视频 → 记成"共享" → 判成不可展开，箭头画成了另一个符号，
                    // 四个共享里只有它的图标跟别人不一样（用户当场发现）。
                    val loaded = fs[ck]
                    val dirs = loaded?.items?.count { it.type == ItemType.DIR } ?: -1
                    out.add(
                        McRow(
                            key = ck, depth = depth, parent = parent, name = node.name,
                            has = if (dirs >= 0) dirs > 0
                            else node.countText?.contains("文件夹") == true,
                            cnt = if (dirs > 0) "$dirs 个文件夹" else node.countText ?: "",
                            pickable = true,
                            srcTag = node.srcTag ?: "",
                        )
                    )
                }
            }
        }

        walk(rootKey, 0, null)
        brRowsCache = out
        brRowsSig = sig
        return out
    }

    /** 树行：从「我的收藏」下的收藏目录开始平铺，支持逐层展开（原型 ftRows） */
    fun ftRows(): List<McRow> {        // 收藏页每次按键都会被 buildRegions() / 页面自身调用多次，递归整棵树太贵。
        // 展开状态、收藏增删、目录加载任一变化才重算，否则直接给上次结果。
        val sig = "${favs.size}/${ftOpen.hashCode()}/${fs.size}/${courses.size}/${courseEpisodes.size}"
        ftRowsCache?.let { if (sig == ftRowsSig) return it }
        val out = ArrayList<McRow>()
        fun walk(key: String, depth: Int, parent: Int?) {
            val items = mcItems(key)
            for (i in items.indices) {
                val node = items[i]
                val ck = mcChild(key, i) ?: continue
                val kids = mcItems(ck)
                var sub = 0
                for (j in kids.indices) if (mcChild(ck, j) != null) sub++
                val self = out.size
                out.add(
                    McRow(
                        key = ck, depth = depth, parent = parent, name = node.name,
                        has = sub > 0,
                        cnt = if (sub > 0) "$sub 个文件夹" else "${kids.size} 项",
                        pickable = parent == null,
                        srcTag = node.srcTag ?: "",
                    )
                )
                if (ftOpen[ck] == true) walk(ck, depth + 1, self)
            }
        }
        walk("#fav", 0, null)
        val res: List<McRow> = out
        ftRowsCache = res
        ftRowsSig = sig
        return res
    }

    /**
     * 这个目录是不是「已在书架」。
     *
     * [nm] 只是界面上的显示名，**不参与判定**：不同数据源里同名目录非常普遍
     * （SMB 和 WebDAV 各有一个「乐理课程」），按名字兜底会把后加的那个源里的目录
     * 判成"已在书架"，用户就再也加不进去了。判定一律只看「源 id + 源内路径」。
     */
    @Suppress("UNUSED_PARAMETER")
    fun onShelfKey(ck: String?, nm: String?): Boolean {
        if (ck.isNullOrEmpty()) return false
        if (shelfCourseOf(ck) != null) return true
        if (ck.startsWith("#")) {
            // "#c5" 这类课程 key：移出书架后同样要立刻变回「不在书架」
            val id = ck.drop(2).toIntOrNull() ?: return false
            return courses.getOrNull(id)?.out == false
        }
        return shelvedKeys.any { sameShelfPath(it, ck) }
    }

    fun inFavKey(ck: String?): Boolean = ck != null && favs.any { it.to == ck }
}
