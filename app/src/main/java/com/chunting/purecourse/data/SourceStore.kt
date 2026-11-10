package com.chunting.purecourse.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 数据源配置的本地持久化（M2：多源并存）。
 *
 * ## 从「一条配置」到「一组配置」
 *
 * M1 时这里只有一条 Cfg：保存 WebDAV 会覆盖 SMB，用户没法同时挂两个源。
 * 现在改成一个有序列表，每条带不可变的 [Cfg.id]（`s1` / `s2` …），
 * 所有路径用 `@s1\...` 前缀和它关联（见 [SrcPath]）。
 *
 * 旧的单源配置在第一次读取时**自动迁移**成列表里的第一条，不会丢配置。
 * 旧的收藏 / 书架 / 已移出路径（无前缀）也在这个阶段归到第一个源。
 *
 * 密码仍是明文存在 App 私有 SharedPreferences 里（其他 App 无法读取）。
 * TV 盒子通常单用户、无多账号，这是可接受的最低成本方案。
 */
object SourceStore {

    private const val PREF = "purecourse_source"

    // ---- 旧版（单源）字段：只读，用于一次性迁移 ----
    private const val K_NAME = "name"
    private const val K_HOST = "host"
    private const val K_SHARE = "share"
    private const val K_USER = "user"
    private const val K_PASS = "pass"
    private const val K_ROOT = "root"
    private const val K_PORT = "port"
    private const val K_PROTO = "proto"
    private const val K_BASEURL = "base_url"
    private const val K_IGNORECERT = "ignore_cert"

    // ---- 新版（多源）字段 ----
    private const val K_LIST = "sources_v2"
    private const val K_NEXT = "next_src_seq"

    private const val K_CLEARED = "user_cleared"
    private const val K_SHARE_HIST = "share_hist"
    private const val K_MIGRATED = "whole_host_migrated"
    private const val K_SHELF = "shelf_paths"
    private const val K_FAV = "fav_paths"
    /** 被用户「移出书架」的目录。扫描会自动把含视频的目录识别成课程，
     *  不记这一份的话，重启后移出的课程又会自己回到书架。 */
    private const val K_HIDDEN = "removed_paths"
    /** 旧版路径列表（无源前缀）已经归一化过的标记 */
    private const val K_PATHS_V2 = "paths_v2_migrated"

    /** 一条收藏记录：目录名 + 带源前缀的完整路径 */
    data class FavRec(val name: String, val rel: String)

    // ======================= Cfg =======================

    data class Cfg(
        /** 稳定标识（`s1` / `s2` …）。空 = 尚未入库，[upsert] 时补上 */
        val id: String = "",
        /** 协议：SMB 或 WebDAV（两者共用同一套 Cfg，靠 proto 分派） */
        val proto: Proto = Proto.SMB,
        /** 自定义显示名（用户可以在添加数据源时起名，例如「PC」）；空 = 就用 IP 显示 */
        val name: String = "",
        val host: String,
        /** 共享名。**空 = 整机模式**：这台主机的所有共享都作为浏览根，不再锁死一个共享 */
        val share: String = "",
        val user: String = "",
        val pass: String = "",
        /** 共享内的起始目录，空 = 共享根；用 `\` 分隔，不带首尾斜杠（整机模式下忽略） */
        val root: String = "",
        val port: Int = 445,
        /** WebDAV 根地址，例如 https://nas:5006/dav（proto = DAV 时必填） */
        val baseUrl: String = "",
        /** WebDAV：忽略证书校验（自签证书 / 群晖 / Alist 常见） */
        val ignoreCert: Boolean = false,
    ) {
        /** 整机模式：一个 IP 就是一个数据源，共享列表由服务端枚举 */
        val wholeHost: Boolean get() = proto == Proto.SMB && share.isBlank()

        val isValid: Boolean get() = when (proto) {
            Proto.DAV -> baseUrl.isNotBlank()
            Proto.LOCAL -> host.isNotBlank()
            Proto.SMB -> host.isNotBlank()
        }

        /** 卡片 / 树节点上给人看的数据源名 */
        fun displayName(): String = when {
            name.isNotBlank() -> name
            proto == Proto.DAV -> host.ifBlank { baseUrl }.ifBlank { "WebDAV" }
            proto == Proto.LOCAL -> "本地存储"
            else -> host.ifBlank { "SMB" }
        }

        /**
         * 来源标签：多个源混排时用来区分同名目录（SMB / DAV + 名字）。
         *
         * 本地源不再套「本地 · 」，因为挂载点名字本身就是「本地存储」「U盘 · 闪迪」，
         * 再拼一次会变成「本地 · 本地存储」这种重复话。
         */
        fun tag(): String =
            if (proto == Proto.LOCAL) displayName() else "${proto.short} · ${displayName()}"

        /**
         * 把统一路径拆成「共享名 + 共享内路径」。
         *
         * 整机模式下所有路径的第一段都是共享名（例如 `课程共享\02 乐理课程`），
         * 单一共享模式下路径本身就是共享内路径，共享名取配置里的。
         */
        fun splitRel(relPath: String): Pair<String, String> {
            val clean = relPath.trim('\\', '/').replace('/', '\\')
            if (proto == Proto.DAV || proto == Proto.LOCAL) return "" to clean
            if (!wholeHost) return share to clean
            if (clean.isEmpty()) return "" to ""
            return clean.substringBefore('\\') to clean.substringAfter('\\', "")
        }

        /** 统一路径 → 播放 URI（SMB = smb://，WebDAV = http(s)://，本地 = file://） */
        fun uriOf(relPath: String): String {
            if (proto == Proto.DAV) return DavClient.urlOf(baseUrl, relPath) ?: baseUrl
            if (proto == Proto.LOCAL) {
                val f = java.io.File(host, relPath.trim('\\', '/').replace('\\', '/'))
                // 用 Uri.fromFile 而不是手拼 "file://" + path：中文 / 空格 / # 号
                // 不编码会让 Media3 解析错位，表现是"能列出名字但点播放就报错"。
                return android.net.Uri.fromFile(f).toString()
            }
            val (sh, inner) = splitRel(relPath)
            val clean = inner.replace('\\', '/')
            return when {
                sh.isBlank() -> "smb://$host/"
                clean.isEmpty() -> "smb://$host/$sh"
                else -> "smb://$host/$sh/$clean"
            }
        }

        /** 给人看的那种路径，原型里到处是 `\\HOST\SHARE\...`；WebDAV 用完整 URL */
        fun uncOf(relPath: String): String {
            if (proto == Proto.DAV) {
                val base = baseUrl.trim().trimEnd('/')
                val p = relPath.trim('\\', '/').replace('\\', '/')
                return if (p.isEmpty()) base else "$base/$p"
            }
            if (proto == Proto.LOCAL) {
                return java.io.File(host, relPath.trim('\\', '/').replace('\\', '/')).absolutePath
            }
            val (sh, inner) = splitRel(relPath)
            val clean = inner.replace('/', '\\')
            return when {
                sh.isBlank() -> "\\\\$host"
                clean.isEmpty() -> "\\\\$host\\$sh"
                else -> "\\\\$host\\$sh\\$clean"
            }
        }

        /** 顶栏 / 卡片上显示的共享描述 */
        fun shareText(): String = when {
            proto == Proto.LOCAL -> displayName()
            proto == Proto.DAV -> name.ifBlank { host }
            wholeHost -> "全部共享"
            else -> share
        }
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ======================= 序列化 =======================

    private fun toJson(c: Cfg): JSONObject = JSONObject()
        .put("id", c.id)
        .put("proto", c.proto.name)
        .put("name", c.name)
        .put("host", c.host)
        .put("share", c.share)
        .put("user", c.user)
        .put("pass", c.pass)
        .put("root", c.root)
        .put("port", c.port)
        .put("baseUrl", c.baseUrl)
        .put("ignoreCert", c.ignoreCert)

    private fun fromJson(o: JSONObject): Cfg = Cfg(
        id = o.optString("id"),
        proto = runCatching { Proto.valueOf(o.optString("proto", Proto.SMB.name)) }
            .getOrDefault(Proto.SMB),
        name = o.optString("name"),
        host = o.optString("host"),
        share = o.optString("share"),
        user = o.optString("user"),
        pass = o.optString("pass"),
        root = o.optString("root"),
        port = o.optInt("port", 445),
        baseUrl = o.optString("baseUrl"),
        ignoreCert = o.optBoolean("ignoreCert", false),
    )

    /** 旧版单源配置（读不到 / 不完整 → null） */
    private fun legacyCfg(s: android.content.SharedPreferences): Cfg? {
        val proto = runCatching { Proto.valueOf(s.getString(K_PROTO, Proto.SMB.name) ?: Proto.SMB.name) }
            .getOrDefault(Proto.SMB)
        val host = s.getString(K_HOST, "") ?: ""
        val baseUrl = s.getString(K_BASEURL, "") ?: ""
        if (proto == Proto.DAV) {
            if (baseUrl.isBlank()) return null
        } else if (host.isBlank()) return null
        return Cfg(
            proto = proto,
            name = s.getString(K_NAME, "") ?: "",
            host = host,
            share = s.getString(K_SHARE, "") ?: "",
            user = s.getString(K_USER, "") ?: "",
            pass = s.getString(K_PASS, "") ?: "",
            root = s.getString(K_ROOT, "") ?: "",
            port = s.getInt(K_PORT, 445),
            baseUrl = baseUrl,
            ignoreCert = s.getBoolean(K_IGNORECERT, false),
        )
    }

    private fun writeAll(ctx: Context, list: List<Cfg>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        sp(ctx).edit().putString(K_LIST, arr.toString()).apply()
    }

    /** 全部数据源（有序）。第一次调用时会把旧版单源配置迁移进来 */
    fun list(ctx: Context): List<Cfg> {
        val s = sp(ctx)
        val raw = s.getString(K_LIST, "") ?: ""
        if (raw.isNotBlank()) {
            val list = runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            }.getOrDefault(emptyList())
            if (list.isNotEmpty()) {
                normalizePaths(ctx, list)
                return list
            }
        }
        // 迁移：旧单源 → 一条带 id 的记录
        val legacy = legacyCfg(s)
        if (legacy != null) {
            val id = newId(ctx)
            val migrated = listOf(legacy.copy(id = id))
            writeAll(ctx, migrated)
            s.edit().putBoolean(K_CLEARED, false).apply()
            normalizePaths(ctx, migrated)
            return migrated
        }
        return emptyList()
    }

    fun get(ctx: Context, id: String?): Cfg? =
        if (id.isNullOrBlank()) null else list(ctx).firstOrNull { it.id == id }

    fun newId(ctx: Context): String {
        val s = sp(ctx)
        // 序号只增不减：删掉 s1 后再新增，绝不能重新生成 s1，
        // 否则旧收藏 / 旧书架路径（@s1\...）会指向另一个源，串成别的目录。
        val n = s.getInt(K_NEXT, 1)
        s.edit().putInt(K_NEXT, n + 1).apply()
        return "s$n"
    }

    /** 新增或按 id 覆盖。返回落库后**回读校验**过的配置；写失败返回 null */
    fun upsert(ctx: Context, cfg: Cfg): Cfg? {
        val id = cfg.id.ifBlank { newId(ctx) }
        val target = cfg.copy(id = id)
        val old = list(ctx).toMutableList()
        val idx = old.indexOfFirst { it.id == id }
        if (idx >= 0) old[idx] = target else old.add(target)
        writeAll(ctx, old)
        sp(ctx).edit().putBoolean(K_CLEARED, false).apply()
        rememberShare(ctx, cfg.share)
        // 回读校验：写失败（磁盘满 / 序列化异常）时要能立刻告诉用户，
        // 而不是提示"已保存"、回去一看根本没有。
        return get(ctx, id)?.takeIf { it.host == target.host && it.port == target.port && it.baseUrl == target.baseUrl }
    }

    fun remove(ctx: Context, id: String) {
        val left = list(ctx).filterNot { it.id == id }
        writeAll(ctx, left)
        // 该源的收藏 / 书架 / 已移出记录一并清掉，否则下次加回同 id 会"复活"旧课程
        val marker = "@$id"
        fun purge(key: String) {
            val lines = (sp(ctx).getString(key, "") ?: "").split('\n').filter { it.isNotBlank() }
            // 收藏表是 `path<TAB>name`，判前缀只看第一段
            val kept = lines.filterNot {
                val p = it.substringBefore('\t').trim()
                p == marker || p.startsWith("$marker\\")
            }
            sp(ctx).edit().putString(key, kept.joinToString("\n")).apply()
        }
        purge(K_SHELF)
        purge(K_FAV)
        purge(K_HIDDEN)
        if (left.isEmpty()) sp(ctx).edit().putBoolean(K_CLEARED, true).apply()
    }

    /** 用户主动删除全部：清空配置并记住，避免下次冷启动又被默认共享覆盖 */
    fun clear(ctx: Context) {
        sp(ctx).edit().clear().putBoolean(K_CLEARED, true).apply()
    }

    // ======================= 默认源 / 迁移 =======================

    /**
     * 首次启动写入用户提供的真实共享配置。
     *
     * 为什么要预置：TV 的输入法虽然能打中文，但「课程共享/02 乐理课程（13门课）」
     * 这种共享名手工敲一遍成本很高；而且自动化测试（adb input text）根本输不了中文。
     * 预置只是默认值，用户随时可以在「数据源 → 添加数据源」里改掉，不是写死不可变。
     */
    fun ensureDefault(ctx: Context) {
        // 干净包：一律不预置数据源，首启就是空的，由用户自己在「添加数据源」里加。
        // 端口等表单默认值不受影响（SMB 仍是 445）。
        if (com.chunting.purecourse.BuildConfig.CLEAN_BUILD) return
        val existing = list(ctx)
        if (existing.isNotEmpty()) { migrateToWholeHost(ctx); return }
        // 用户主动删过数据源 → 不许自动把默认共享塞回去，否则「删除」是假的
        if (sp(ctx).getBoolean(K_CLEARED, false)) return
        val cfg = RealCatalog.defaultCfg()
        upsert(ctx, cfg.copy(id = newId(ctx)))
    }

    /**
     * 一次性迁移：把「从没改过的默认单共享配置」升级成整机模式。
     *
     * 只迁移**完全等于旧默认值**的配置；用户自己改过的共享/路径一律不动。
     */
    private fun migrateToWholeHost(ctx: Context) {
        val s = sp(ctx)
        if (s.getBoolean(K_MIGRATED, false)) return
        val all = list(ctx)
        val fixed = all.map { cfg ->
            if (cfg.proto == Proto.SMB &&
                cfg.share == RealCatalog.DEFAULT_SHARE && cfg.root == RealCatalog.DEFAULT_ROOT
            ) cfg.copy(share = "", root = "") else cfg
        }
        if (fixed != all) writeAll(ctx, fixed)
        s.edit().putBoolean(K_MIGRATED, true).apply()
    }

    /**
     * 旧路径列表补源前缀（一次性）。
     *
     * 旧版本的 shelf/fav/hidden 存的都是不带前缀的相对路径，只有一个源所以没有歧义；
     * 现在必须归到某个源才能解析。归给**列表里第一条**，与"迁移时旧配置就是第一条"一致。
     */
    private fun normalizePaths(ctx: Context, cfgs: List<Cfg>) {
        if (cfgs.isEmpty()) return
        val s = sp(ctx)
        if (s.getBoolean(K_PATHS_V2, false)) return
        val first = cfgs.first().id

        /** 纯路径表：每行就是一条路径 */
        fun fixPlain(key: String) {
            val lines = (s.getString(key, "") ?: "").split('\n').filter { it.isNotBlank() }
            val out = lines.map { line ->
                if (SrcPath.hasPrefix(line)) line else SrcPath.of(first, line)
            }
            s.edit().putString(key, out.joinToString("\n")).apply()
        }

        /** 收藏表：每行 `path<TAB>name` */
        fun fixFav() {
            val lines = (s.getString(K_FAV, "") ?: "").split('\n').filter { it.isNotBlank() }
            val out = lines.map { line ->
                val path = line.substringBefore('\t').trim()
                val name = line.substringAfter('\t', "").trim()
                val p = if (SrcPath.hasPrefix(path)) path else SrcPath.of(first, path)
                if (name.isEmpty()) p else "$p\t$name"
            }
            s.edit().putString(K_FAV, out.joinToString("\n")).apply()
        }

        fixPlain(K_SHELF)
        fixPlain(K_HIDDEN)
        fixFav()
        migratePlayState(ctx, first)
        s.edit().putBoolean(K_PATHS_V2, true).apply()
    }

    /**
     * 播放进度 / 课程播放状态也跟着改键。
     *
     * 这两个 store 用「目录路径」当键，路径加上源前缀后新旧键对不上，
     * 不迁移的话用户升级后**所有课程都会变回"尚未播放"**，续播位置也归零——
     * 用户看到的是"你们这个版本把我进度全弄没了"。
     * 旧键（无前缀）归到第一个源，与上面路径表的迁移口径一致。
     */
    private fun migratePlayState(ctx: Context, firstId: String) {
        runCatching {
            val p = ctx.getSharedPreferences("purecourse_course_meta", Context.MODE_PRIVATE)
            val ed = p.edit()
            p.all.forEach { (k, v) ->
                if (v is String && k.isNotBlank() && !SrcPath.hasPrefix(k)) {
                    ed.putString(SrcPath.of(firstId, k), v)
                    ed.remove(k)
                }
            }
            ed.apply()
        }
        runCatching {
            val p = ctx.getSharedPreferences("purecourse_resume", Context.MODE_PRIVATE)
            val ed = p.edit()
            p.all.forEach { (k, v) ->
                if (v is Int && k.isNotBlank() && !SrcPath.hasPrefix(k)) {
                    // 续播键格式：`<目录>#<集号>`，前缀加在目录段前面，`#N` 原样保留
                    val idx = k.lastIndexOf('#')
                    val dir = if (idx > 0) k.substring(0, idx) else k
                    val tail = if (idx > 0) k.substring(idx) else ""
                    ed.putInt(SrcPath.of(firstId, dir) + tail, v)
                    ed.remove(k)
                }
            }
            ed.apply()
        }
    }

    // ======================= 收藏 =======================

    /**
     * 收藏目录（持久化）。
     *
     * 之前收藏只存在内存里：加完看着成功，一旦进程退出（电视上随手按 Home 就会被回收）
     * 就全没了 —— 收藏页只剩自动生成的根节点。这里落一份到 SharedPreferences。
     * 存储格式：每行 `path<TAB>name`，path 带源前缀，name 允许为空。
     */
    fun favPaths(ctx: Context): List<FavRec> =
        (sp(ctx).getString(K_FAV, "") ?: "")
            .split('\n').mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val rel = line.substringBefore('\t').trim()
                val name = line.substringAfter('\t', "").trim()
                if (rel.isEmpty()) null else FavRec(name, rel)
            }.distinctBy { it.rel }

    fun addFavPath(ctx: Context, name: String, rel: String) {
        if (rel.isBlank()) return
        val old = favPaths(ctx).toMutableList()
        old.removeAll { it.rel == rel }
        old.add(FavRec(name.trim(), rel))
        sp(ctx).edit()
            .putString(K_FAV, old.joinToString("\n") { "${it.rel}\t${it.name}" })
            .apply()
    }

    fun removeFavPath(ctx: Context, rel: String) {
        val old = favPaths(ctx).toMutableList()
        if (!old.removeAll { it.rel == rel }) return
        sp(ctx).edit()
            .putString(K_FAV, old.joinToString("\n") { "${it.rel}\t${it.name}" })
            .apply()
    }

    // ======================= 共享名历史 =======================

    /**
     * 用过的共享名历史（最近在前）。
     *
     * 用途：「共享 / 路径」留空时给候选，省得在电视上重新手打共享名。
     * 为什么需要它：SMB 的共享枚举要走 srvsvc 的 NetrShareEnum，
     * 实测这台主机的服务端拒绝（WERR 87 / bad stub），拿不到列表，
     * 所以只能用「自己历史里见过的共享」当候选 —— 不编造、不假装枚举成功。
     */
    fun shareHistory(ctx: Context): List<String> =
        (sp(ctx).getString(K_SHARE_HIST, "") ?: "")
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun rememberShare(ctx: Context, share: String) {
        if (share.isBlank()) return
        val old = shareHistory(ctx).toMutableList()
        old.remove(share)
        old.add(0, share)
        sp(ctx).edit().putString(K_SHARE_HIST, old.take(12).joinToString("\n")).apply()
    }

    // ======================= 书架 / 已移出 =======================

    /**
     * 手动加入书架的目录（带源前缀的完整路径）。
     *
     * 之前 `addToShelfByNode` 只往内存里的 courses 追加一条，进程一退就没了——
     * 「加入书架」形同虚设。这里落盘，冷启动时由 [RealCatalog.scan] 重新读成课程。
     */
    fun shelfPaths(ctx: Context): List<String> =
        (sp(ctx).getString(K_SHELF, "") ?: "")
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun addShelfPath(ctx: Context, rel: String) {
        if (rel.isBlank()) return
        val old = shelfPaths(ctx).toMutableList()
        if (old.contains(rel)) return
        old.add(rel)
        sp(ctx).edit().putString(K_SHELF, old.joinToString("\n")).apply()
    }

    fun removeShelfPath(ctx: Context, rel: String) {
        val old = shelfPaths(ctx).toMutableList()
        if (!old.remove(rel)) return
        sp(ctx).edit().putString(K_SHELF, old.joinToString("\n")).apply()
    }

    /** 用户手动移出书架的目录（扫描时据此把课程标成「已移出」） */
    fun hiddenPaths(ctx: Context): List<String> =
        (sp(ctx).getString(K_HIDDEN, "") ?: "")
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun addHiddenPath(ctx: Context, rel: String) {
        if (rel.isBlank()) return
        val old = hiddenPaths(ctx).toMutableList()
        if (old.contains(rel)) return
        old.add(rel)
        sp(ctx).edit().putString(K_HIDDEN, old.joinToString("\n")).apply()
    }

    fun removeHiddenPath(ctx: Context, rel: String) {
        val old = hiddenPaths(ctx).toMutableList()
        if (!old.remove(rel)) return
        sp(ctx).edit().putString(K_HIDDEN, old.joinToString("\n")).apply()
    }
}
