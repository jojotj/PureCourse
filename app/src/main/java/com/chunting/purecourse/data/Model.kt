package com.chunting.purecourse.data

import androidx.compose.ui.graphics.Color

/** 课程（= 一个 SMB 文件夹），对应原型 COURSES[] 与文档 §4 的 Course 实体 */
data class Course(
    val id: Int,
    val name: String,
    val abbr: String,          // 原型 s：封面缩写
    val c1: Color,             // 封面渐变起色
    val c2: Color,             // 封面渐变止色
    var curEp: Int,            // 原型 cur：当前集（= 最后观看的那一集，续播就进这里）
    /**
     * 真正看完的集号（1-based）。
     *
     * 以前用 `curEp - 1` 推「已看集数」，于是「跳到第 20 节看一眼」会把前 19 节全标成已看 ——
     * 用户可能只是想抽查其中几节。现在只有**播到片尾**的集才进这个集合。
     */
    var doneSet: Set<Int> = emptySet(),
    val lastEpName: String,    // 原型 last
    var lastAt: Long,          // 原型 lastAt：202610282014 形式的整数
    var lastTxt: String,       // 原型 lastTxt：「今天 20:14」
    var py: String,            // 拼音排序键
    var out: Boolean = false,  // 已移出书架
    /** 真实视频文件数：一门课可能是 4 集、12 集、48 集甚至 79 集，不能写死 */
    var epCount: Int = 0,
    /** 所属数据源 id（M2 多源并存，见 [SrcPath]）；空 = 旧数据/未知 */
    val srcId: String = "",
    /** 来源标签文字（例如 `SMB · PC`），书架混排时用来区分同名课程 */
    val srcTag: String = "",
) {
    /** 已看完的集数（只是 [doneSet] 的大小，不再由 curEp 推算） */
    val doneEp: Int get() = doneSet.size

    val pct: Int get() = if (epCount <= 0) 0
    else Math.round(doneEp.toFloat() / epCount * 100).coerceIn(0, 100)
}

enum class ItemType { DIR, VID }

/** 目录 / 文件树节点的一条（浏览页与收藏页共用） */
data class FsItem(
    val type: ItemType,
    val name: String,
    val to: String? = null,      // 子目录 key
    val course: Int? = null,     // 该目录即某门课程
    val countText: String? = null,
    var inShelf: Boolean = false,
    val videoLen: String? = null, // 原型 c 里的时长/体积文本
    /** 该条目在共享内的真实相对路径（目录与视频都带），用于播放、收藏、加入书架 */
    val relPath: String? = null,
    /** 来源标签（`SMB · PC` / `DAV · 115`）；由多源合并时填，树/列表上用来区分同名目录 */
    val srcTag: String? = null,
)

data class FsNode(val key: String, val name: String, val items: List<FsItem>)

/** 收藏项（目录书签）。path 带源前缀，跨源收藏靠它区分同名目录 */
data class Fav(
    val name: String,
    val path: String,
    val countText: String,
    val to: String,
    val srcTag: String = "",
)

enum class Proto(val label: String, val short: String, val port: Int, val desc: String) {
    SMB("SMB / CIFS", "SMB", 445, "Windows 共享与 NAS 最常用"),
    DAV("WebDAV", "DAV", 443, "基于 HTTP，适合远程与网盘"),

    /**
     * 本地存储（电视内置存储 / U 盘 / SD 卡）。
     *
     * **不是「另一种协议」，而是把挂载点当成一条数据源**：一个挂载点 = 一条 Cfg，
     * `host` 存挂载点绝对路径（`/storage/emulated/0`、`/storage/1A2B-3C4D`），
     * 其余字段为空。这样左树第一层自然就是「本地存储」「U盘 · XXX」，
     * 展开收起、右栏、加书架、收藏、备注名全部与 SMB / WebDAV 共用一套逻辑，
     * 平板端 / 手机端不需要另写一套。
     *
     * 这些 Cfg 由 [LocalStore] 自动探测生成，**不入库**（拔盘即消失，不会留死配置）。
     */
    LOCAL("本地存储", "本地", 0, "电视内置存储与 U 盘"),
    // FTP/SFTP、NFS 已按用户要求从产品范围移除（2026-11-07）。
    // 只做 SMB + WebDAV 两条协议，避免"空壳卡片"。
    ;

    companion object {
        /**
         * 「添加数据源」页可选的协议。
         *
         * LOCAL 不在里面：本地存储不需要填地址/账号，由 [LocalStore] 自动探测，
         * 让它出现在协议选择里，用户点进去只会看到一个填不了的表单。
         */
        val selectable: List<Proto> get() = entries.filter { it != LOCAL }
    }
}

/** 数据源（界面上的一张卡片）。id 与 [SourceStore.Cfg.id] 一一对应 */
data class Source(
    val name: String,
    val proto: Proto,
    val host: String,
    val port: Int,
    val path: String,
    var dirs: Int = 0,
    var ok: Boolean = false,
    var err: String? = null,     // "full" = 连接数已满
    var isNew: Boolean = false,
    var remember: Boolean = true,
    val id: String = "",
    /** 扫描进度文案（多源并发时每张卡各自显示） */
    var progress: String? = null,
)

/** 局域网发现 / 最近使用（添加数据源页的右侧栏） */
data class Endpoint(
    val name: String,
    val host: String,
    val proto: Proto,
    val port: Int,
    val path: String,
    val user: String = "guest",
    /** 真实密码；快捷位置必须带，否则 fillFrom 会把遮蔽圆点当密码送去认证 */
    val pass: String = "",
)

// 界面上的集数一律来自 [Course.epCount]（真实视频文件数），
// 原 P0 的 `const val TOTAL = 12` 已删除：留着迟早会被误用回写死的 12 集。
