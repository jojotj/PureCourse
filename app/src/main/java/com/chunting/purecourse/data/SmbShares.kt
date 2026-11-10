package com.chunting.purecourse.data

import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ImpersonationLevel
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.NamedPipe
import com.hierynomus.smbj.share.PipeShare
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * 枚举某台 SMB 主机上的**所有共享**（对应 Windows 里 `smbclient -L` / 「网络」里看到的共享列表）。
 *
 * 为什么不能靠 smbj 现成 API：smbj 0.13.0 的 `Session` 只有 `connectShare(name)`，
 * **没有** `listShares()`（已用 javap 核对 0.13.0 的字节码，无此方法）。
 * 共享列表属于 srvsvc 远程服务，唯一标准做法是：
 *   连 `IPC$` → 打开 `srvsvc` 命名管道 → DCE/RPC bind → 调 `NetrShareEnum`（opnum 15）。
 * 这里就是这套流程的最小实现（无鉴权，SMB 会话本身已认证；与 smbclient 的做法一致）。
 *
 * 协议要点（NDR / little-endian）：
 * - 绑定的抽象语法 = SRVSVC UUID 4b324fc8-1670-01d3-1278-5a47bf6ee188 v3.0，
 *   传输语法 = NDR 8a885d04-1ceb-11c9-9fe8-08002b104860 v2.0
 * - 用 **Level 1**（SHARE_INFO_1）而不是 Level 0：Level 1 带共享类型，
 *   能过滤掉 IPC$ / 打印队列，只留可浏览的磁盘共享。
 *
 * 失败时返回人话错误，绝不编造共享名。
 */
object SmbShares {

    private const val TAG = "SmbShares"

    data class Share(
        val name: String,
        val remark: String,
        val disk: Boolean,
        /** 管理共享（C$ / ADMIN$ 这类带 STYPE_SPECIAL 的），默认不展示给用户挑 */
        val special: Boolean,
    )

    /** 最近一次请求 / bind ack 的十六进制，排障时打到界面上（不参与业务逻辑） */
    var lastReqHex: String = ""
        private set
    var lastAckHex: String = ""
        private set

    /**
     * 返回 Pair(错误文案, 共享列表)；成功时错误为 null。
     *
     * 带重试：反复进出 App 时，上一次的 `srvsvc` 管道可能还没被服务端回收，
     * 直接 open 会拿到 `STATUS_PIPE_NOT_AVAILABLE (0xC00000AC)`。
     * 这种情况下重连一次基本就能拿到（实测同一台机器连续枚举很容易撞上）。
     */
    fun list(host: String, user: String, pass: String, port: Int = 445): Pair<String?, List<Share>> {
        var last: Pair<String?, List<Share>> = null to emptyList()
        for (attempt in 0..2) {
            last = listOnce(host, user, pass, port)
            val err = last.first
            if (err == null) return last
            val retryable = err.contains("PIPE_NOT_AVAILABLE") || err.contains("0xc00000ac", true)
            if (!retryable || attempt == 2) return last
            Log.i(TAG, "枚举重试 ${attempt + 1}：$err")
            runCatching { Thread.sleep(300L * (attempt + 1)) }
        }
        return last
    }

    private fun listOnce(host: String, user: String, pass: String, port: Int): Pair<String?, List<Share>> {
        var client: SMBClient? = null
        var session: com.hierynomus.smbj.session.Session? = null
        var ipc: PipeShare? = null
        var pipe: NamedPipe? = null
        return try {
            val cfg = SmbConfig.builder()
                .withTimeout(10, TimeUnit.SECONDS)
                .withSoTimeout(20, TimeUnit.SECONDS)
                .build()
            val c = SMBClient(cfg)
            client = c
            val conn = c.connect(host, port)
            val ac = if (user.isBlank()) AuthenticationContext.anonymous()
            else AuthenticationContext(user, pass.toCharArray(), "")
            val s = conn.authenticate(ac)
            session = s

            val ipcShare = s.connectShare("IPC\$") as? PipeShare
                ?: return "这台主机不提供 IPC\$ 共享，无法查询共享列表" to emptyList()
            ipc = ipcShare

            pipe = ipcShare.open(
                "srvsvc",
                SMB2ImpersonationLevel.Impersonation,
                EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )

            val bindResp = pipe.transact(bindPdu())
            val bindType = if (bindResp.size > 2) bindResp[2].toInt() and 0xFF else -1
            if (bindType != 0x0C) {
                return "srvsvc 绑定失败：返回 0x${Integer.toHexString(bindType)}，前 32 字节 " +
                    bindResp.take(32).joinToString(" ") { "%02x".format(it) } to emptyList()
            }
            // bind_ack 里 secondary address 是变长的，必须按 NDR 对齐跳过再读 result，
            // 直接按固定偏移读会把 transfer_syntax 的 UUID 当成结果码
            var o = 24
            if (bindResp.size >= 26) {
                val secLen = u16(bindResp, o)
                o += 2 + secLen
                o = (o + 3) and 3.inv()
            }
            val contResult = if (bindResp.size >= o + 4) {
                o += 1 + 3        // num_results(1) + reserved(3)
                val cid = u16(bindResp, o)
                val res = u16(bindResp, o + 2)
                Log.i(TAG, "bind ack: p_cont_id=$cid result=$res secLen=${u16(bindResp, 24)}")
                res
            } else -1
            if (contResult != 0) {
                return "绑定被拒 result=$contResult（1=语法不支持 2=provider 拒绝 3=协商失败）" to emptyList()
            }

            val reqPdu = enumPdu(host)
            lastReqHex = reqPdu.joinToString(" ") { "%02x".format(it) }
            lastAckHex = bindResp.joinToString(" ") { "%02x".format(it) }
            val resp = pipe.transact(reqPdu)
            if (resp.size < 24) return "srvsvc 没有返回数据" to emptyList()
            val respType = resp[2].toInt() and 0xFF
            if (respType == 0x03) {
                val st = if (resp.size >= 28) u32(resp, 24) else 0L
                val hint = when (st) {
                    0x1c010002L -> "NDR 编码不被接受（我方报文有问题）"
                    0x1c000009L -> "没有权限（Windows 要求有效账号，来宾/匿名不行）"
                    0x1c010003L -> "服务端处理器失败"
                    0x1c01000bL -> "接口版本不匹配"
                    else -> "未知"
                }
                val dump = StringBuilder()
                dump.append("len=${resp.size} type=0x${Integer.toHexString(respType)} frag=${u16(resp, 8)}")
                dump.append(" callid=${u32(resp, 12)}")
                if (resp.size >= 32) {
                    dump.append("\nalloc=${u32(resp, 16)} cont=${u16(resp, 20)}")
                    dump.append(" fragcnt=${resp[22].toInt() and 0xFF}")
                    dump.append(" st24=0x${java.lang.Long.toHexString(u32(resp, 24))}")
                    dump.append(" st28=0x${java.lang.Long.toHexString(u32(resp, 0.coerceAtLeast(0) + 28))}")
                }
                dump.append("\n")
                for (o in 0 until resp.size step 8) {
                    dump.append("%02d: ".format(o))
                    for (j in o until minOf(o + 8, resp.size)) dump.append("%02x ".format(resp[j]))
                    dump.append("\n")
                }
                Log.w(TAG, "fault=$dump req=$lastReqHex ack=$lastAckHex")
                return "RPC fault 0x${java.lang.Long.toHexString(st)}：$hint" to emptyList()
            }
            if (respType != 0x02) return "srvsvc 返回了意外的报文（0x${Integer.toHexString(respType)}）" to emptyList()

            parseEnumResponse(resp)
        } catch (t: Throwable) {
            Log.w(TAG, "list shares failed: ${t.javaClass.simpleName}: ${t.message}")
            val msg = "${t.javaClass.simpleName}: ${t.message ?: ""}"
            val human = when {
                msg.contains("LOGON_FAILURE", true) || msg.contains("SmbAuthException", true) ->
                    "账号或密码不对，没法查询共享列表"
                msg.contains("ConnectException", true) || msg.contains("SocketTimeout", true) ||
                    msg.contains("NoRouteToHost", true) -> "连不上 $host:$port"
                msg.contains("STATUS_ACCESS_DENIED", true) ->
                    "账号没有枚举共享的权限（Windows 需要有效账号，不能是来宾）"
                else -> msg
            }
            human to emptyList()
        } finally {
            runCatching { pipe?.close() }
            runCatching { ipc?.close() }
            runCatching { session?.close() }
            runCatching { client?.close() }
        }
    }

    // ------------------------------------------------------------------ PDU 构造

    /** SRVSVC 抽象语法 UUID（v3.0）+ NDR 传输语法（v2.0），按 DCE 线上字节序写死 */
    private val ABSTRACT_SYNTAX = byteArrayOf(
        0xC8.toByte(), 0x4F, 0x32, 0x4B,
        0x70, 0x16,
        0xD3.toByte(), 0x01,
        0x12, 0x78,
        0x5A, 0x47, 0xBF.toByte(), 0x6E, 0xE1.toByte(), 0x88.toByte(),
        3, 0, 0, 0,
    )
    private val TRANSFER_SYNTAX = byteArrayOf(
        0x04, 0x5D, 0x88.toByte(), 0x8A.toByte(),
        0xEB.toByte(), 0x1C,
        0xC9.toByte(), 0x11,
        0x9F.toByte(), 0xE8.toByte(),
        0x08, 0x00, 0x2B, 0x10, 0x48, 0x60,
        2, 0, 0, 0,
    )

    /** 公共头 16 字节 */
    private fun header(type: Int, flags: Int, fragLen: Int, callId: Int, bb: ByteBuffer) {
        bb.put(5.toByte())               // rpc_vers
        bb.put(0.toByte())               // rpc_vers_minor
        bb.put(type.toByte())            // ptype
        bb.put(flags.toByte())           // pfc_flags
        bb.put(0x10.toByte()); bb.put(0.toByte()); bb.put(0.toByte()); bb.put(0.toByte())
        bb.putShort(fragLen.toShort())
        bb.putShort(0)                   // auth_length
        bb.putInt(callId)
    }

    private fun bindPdu(callId: Int = 1): ByteArray {
        // 2+2+4+4+2+1+1+20+20 = 56
        val frag = 16 + 56
        val bb = ByteBuffer.allocate(frag).order(ByteOrder.LITTLE_ENDIAN)
        header(0x0B, 0x03, frag, callId, bb)
        bb.putShort(4280)   // max_xmit_frag
        bb.putShort(4280)   // max_recv_frag
        bb.putInt(0)        // assoc_group
        bb.putInt(1)        // num_ctx_items
        bb.putShort(0)      // p_cont_id
        bb.put(1.toByte())  // num_transfer_syntaxes
        bb.put(0.toByte())  // 对齐
        bb.put(ABSTRACT_SYNTAX)
        bb.put(TRANSFER_SYNTAX)
        return bb.array()
    }

    /**
     * NetrShareEnum 请求（Level 1）。字段顺序按实际能通的报文还原，**不是**照 Samba IDL 直译：
     *
     * ```
     * ServerName   : referent(4) max(4) offset(4) actual(4) "\\\\host\0" 补 4 字节
     * Level        : 4
     * switch(Level): 4      ← level 必须再写一遍，这是 union 的 switch 值
     * InfoStruct   : referent(4) count(4)=0 array(4)=NULL
     * PreferedMax  : 4
     * ResumeHandle : 4
     * ```
     *
     * 两个致命坑（都是靠 Windows `net view` 能成功 + jcifs 的实测报文定位的）：
     * 1. **`level` 编码两次**。只写一次的话服务器读到的 switch 是下一个字段，
     *    与 Level 对不上，一律回 `0x6F7 = RPC_X_BAD_STUB_DATA`；
     * 2. `PreferedMaximumLength` / `ResumeHandle` 在 **InfoStruct 之后**，
     *    而 InfoStruct 自身是"指针 + 立即展开的结构体"，不是延迟区。
     *
     * 早期版本按 Samba 的 `srvsvc_NetShareEnumAll` 直译（server_unc / info_ctr / max_buffer /
     * resume_handle 顺序、level 只写一次），在这台 Windows 上 40 多组组合全部 bad stub。
     */
    private fun enumPdu(host: String, callId: Int = 2): ByteArray {
        val bare = host.trim().removePrefix("\\\\").removePrefix("//")
        val unc = "\\\\$bare"
        val units = unc.length + 1                 // 含结尾 NUL
        val raw = units * 2
        val padded = (raw + 3) and 3.inv()

        val stub = ByteBuffer.allocate(4 + 12 + padded + 4 + 4 + 4 + 4 + 4 + 4 + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        stub.putInt(0x00020000)                    // ServerName referent
        stub.putInt(units)                         // max_count
        stub.putInt(0)                             // offset
        stub.putInt(units)                         // actual_count
        for (ch in unc) stub.putChar(ch)
        stub.putChar('\u0000')
        repeat(padded - raw) { stub.put(0.toByte()) }
        stub.putInt(1)                             // Level = 1 (SHARE_INFO_1)
        stub.putInt(1)                             // switch(Level) —— 必须重复
        stub.putInt(0x00020004)                    // InfoStruct referent
        stub.putInt(0)                             // ShareInfoCtr1.count
        stub.putInt(0)                             // ShareInfoCtr1.array = NULL
        stub.putInt(0xFFFFFFFF.toInt())            // PreferedMaximumLength
        stub.putInt(0)                             // ResumeHandle
        val s = stub.array()

        val frag = 16 + 8 + s.size
        val bb = ByteBuffer.allocate(frag).order(ByteOrder.LITTLE_ENDIAN)
        header(0x00, 0x03, frag, callId, bb)   // ptype = REQUEST
        bb.putInt(s.size)      // alloc_hint
        bb.putShort(0)         // p_cont_id
        bb.putShort(15)        // opnum = NetrShareEnum
        bb.put(s)
        return bb.array()
    }

    // ------------------------------------------------------------------ 报文解析

    private fun u16(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or
            ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or
            ((b[o + 3].toLong() and 0xFF) shl 24)

    /**
     * 解析 NetrShareEnum 的 RESPONSE stub（实测响应 1020 字节 / 17 个共享）。
     *
     * 头部固定是 `Level(4) switch(4) arrayRef(4) count(4)`（实测 count 在 +12），
     * 之后是指针表和延迟区。指针表的具体排布依赖 NDR 的 referent 规则，
     * 不同实现对空字符串/特殊共享的排布会有出入，**硬按偏移取元素并不可靠**。
     *
     * 这里用"定位字符串区 + 顺序解析"：NDR 的变长宽字符串编码是自描述的
     * `max(4) offset(4) actual(4) UTF-16LE(actual×2) 补 4 字节`，
     * 且共享名与备注在报文里严格交替出现（name、remark、name、remark…）。
     * 起点靠结构自校验找到（max 合理、offset=0、actual=max、以 NUL 结尾、可打印），
     * 之后连续解析 count×2 个；数量对不上就以实际扫描结果为准。
     */
    private fun parseEnumResponse(raw: ByteArray): Pair<String?, List<Share>> {
        var fragLen = u16(raw, 8)
        if (fragLen <= 0 || fragLen > raw.size) fragLen = raw.size
        if (fragLen < 40) return "srvsvc 响应太短（$fragLen 字节）" to emptyList()

        val stubOff = 24   // 16 头 + alloc_hint(4) + p_cont_id(2) + opnum(2)
        val s = raw.copyOfRange(stubOff, fragLen)
        if (s.size < 16) return "srvsvc 响应太短" to emptyList()

        val level = u32(s, 0).toInt()
        if (level != 1 && level != 0) {
            return "srvsvc 返回了不认识的层级（$level）" to emptyList()
        }
        var count = u32(s, 12).toInt()
        if (count !in 1..4096) count = -1

        // 找字符串区起点：第一个"结构自校验通过、且非空"的位置
        var start = -1
        var p = 0
        while (p + 12 <= s.size) {
            val r = readNdrString(s, p)
            if (r != null && r.first.isNotBlank()) {
                start = p
                break
            }
            p += 4
        }
        if (start < 0) {
            val rc = u32(s, s.size - 4)
            return when (rc) {
                0L -> "主机返回空的共享列表" to emptyList()
                5L -> "账号没有枚举共享的权限（Windows 需要用有效账号，不能是来宾）" to emptyList()
                else -> "主机返回错误码 $rc，可能不允许枚举共享" to emptyList()
            }
        }

        val items = ArrayList<String>()
        var q = start
        val limit = if (count > 0) count * 2 else 4096
        while (items.size < limit) {
            val r = readNdrString(s, q) ?: break
            items += r.first
            q = r.second
        }
        if (items.isEmpty()) return "共享名解析失败" to emptyList()

        // STYPE_DISKTREE = 0，STYPE_SPECIAL = 0x80000000。
        // 响应里 type 的位置随实现变动，这里用 Windows 的通用约定判定：
        // 以 `$` 结尾的一律是管理/特殊共享（C$ / ADMIN$ / IPC$ / 各种盘符$）。
        val out = ArrayList<Share>(items.size / 2 + 1)
        var i = 0
        while (i < items.size) {
            val name = items[i]
            val remark = if (i + 1 < items.size) items[i + 1] else ""
            val special = name.endsWith("$")
            out += Share(name, remark, disk = !special, special = special)
            i += 2
        }
        val visible = out.filter { !it.special && !it.name.equals("IPC$", true) }
            .sortedBy { it.name.lowercase() }
        Log.i(TAG, "shares=${out.size} visible=${visible.size}")
        return if (visible.isEmpty())
            "这台主机没有可浏览的磁盘共享（只有 ${out.size} 个管理共享）" to emptyList()
        else null to visible
    }

    /** NDR 变长宽字符串：max(4) offset(4) actual(4) + UTF-16LE(actual × 2)，补到 4 字节 */
    private fun readNdrString(b: ByteArray, start: Int): Pair<String, Int>? {
        if (start < 0 || start + 12 > b.size) return null
        val maxCount = u32(b, start).toInt()
        val offset = u32(b, start + 4).toInt()
        val actual = u32(b, start + 8).toInt()
        if (maxCount !in 1..512 || offset != 0 || actual != maxCount) return null
        if (start + 12 + actual * 2 > b.size) return null
        val bytes = b.copyOfRange(start + 12, start + 12 + actual * 2)
        if (bytes.size < 2 || bytes[bytes.size - 2] != 0.toByte() || bytes[bytes.size - 1] != 0.toByte()) {
            return null
        }
        val txt = String(bytes, 0, bytes.size - 2, Charsets.UTF_16LE)
        // 只接受可打印内容：共享名/备注不会有控制字符，避免把二进制误判成字符串
        if (txt.any { it.code < 0x20 }) return null
        var next = start + 12 + actual * 2
        next = (next + 3) and 3.inv()
        return txt to next
    }
}
