# 09 · SMB 真机联调与真播放（P1-lite + P3 验收）

日期：2026-11-05
环境：Windows 11 宿主 + MuMu 模拟器（x86_64 / Android 15）、共享 `\\<host>\课程共享` → `L:\课程共享`
账号：`guest`

> 本文只写**实测到的数字**。所有数据来自 `logcat -s SMBPROBE` 与 Windows 端 `Get-SmbSession`
> 的实际读数，截图见 `docs/s1-*.png`。

---

## 1. 联调探针（可复跑）

`app/src/main/java/com/chunting/purecourse/SmbProbeActivity.kt`，只由 adb 唤起、不在桌面暴露：

```bash
export MSYS2_ARG_CONV_EXCL='*'
adb shell "am start -n <pkg>/com.chunting.purecourse.SmbProbeActivity \
  --es host 10.0.2.2 --es share 课程共享 --es user guest --es pass guest \
  --es path '02 乐理课程（13门课）/张晖-乐理基础知识-入门篇(4061)'"
adb logcat -s SMBPROBE
```

它做五件事：连接 → 列目录 → 取大小 → 顺序读 4MB → **随机读片头/中间/片尾各 64KB**，
并把配置写进 `SourceStore`，**一次注入 = App 里永久可用**（省去在 TV 上用遥控器敲密码）。

⚠️ 参数坑：`--es pass ""`（空串）会让 `am start` 直接报
`IllegalArgumentException: Argument expected after "pass"`，且**整条命令失败、Activity 根本不启动**。
含括号/空格的路径要用单引号包住整条 `am start` 命令。

## 2. 实测数据

| 指标 | 数值 | 说明 |
|---|---|---|
| TCP/SMB 协商 | 通 | 模拟器 `10.0.2.2:445` 可达；之前"发探测包 0 字节返回"是 shell 转义把二进制包弄坏了，不是防火墙 |
| 认证 | 28ms（冷）/ 11ms（热） | `guest/guest` |
| `list` 根目录 | **9ms / 5 项** | 5 个顶级课程分类 |
| `list` 课程目录 | **7ms / 12 项** | 12 集 .flv，大小 15~50MB |
| 取文件大小 | **2ms** | |
| 顺序读 4MB | **33.6MB/s** | 课程码率约 1.2Mbps ≈ 0.15MB/s，带宽余量 ~200 倍 |
| **随机读 @0 / @中间 / @片尾** | **2ms / 10ms / 6ms** | seek 可用的直接证据 |
| 会话数（本机） | 恒 **1** | |
| 会话数（Windows 端 `Get-SmbSession`） | **1 条 / 1 个文件句柄** | 播放中实测，用户 `NAS\guest` |
| 8 次连续 seek 后 | 仍 **1 条会话 / 0 个残留句柄** | 无泄漏 |

结论：**`§5` 要求的"播放期间并发 ≤2 条"已达成，实际是 1 条。**

## 3. 片源格式调研（决定内核选型的关键）

抽样 12 个文件（跨 5 个课程分类）：

| 容器 | 视频编码 | 分辨率 | 音频 |
|---|---|---|---|
| FLV | **H.264** | 1280×720（1 个 1920×1080） | AAC 48kHz 立体声 |

全部是 H.264 + AAC，**Media3 的 `FlvExtractor` + 硬解可直接播**，不需要 libmpv 兜底。
（若是 VP6/FLV1 就必须换 mpv，所以这一项必须实测、不能凭"FLV"这个后缀推断。）

## 4. 真播放验证（截图 `docs/s1-*.png`）

| 项 | 结果 | 证据 |
|---|---|---|
| 出画面 | ✅ 真实课程视频（老师讲课/谱例/片头） | `s1-03-flv.png`、`s1-06-top-grad.png` |
| 「演示片源」角标 | ✅ 已消失（说明走的是 `smb://`） | `s1-06` |
| 真实时长 | ✅ `00:00:11 / 00:03:25`（与 ffprobe 一致） | `s1-06` |
| 快移 60 秒 | ✅ 26s → 86s 精确 | `s1-05-seek.png` |
| 倍速 2.0x | ✅ 菜单显示「2.0x」高亮，2:48 在约 18 秒内冲到片尾 | `s1-17`、`s1-18` |
| 镜像 | ✅ 「课程共享」文字左右翻转，**控制层 UI 不翻转** | `s1-22-mirror-on.png` |
| 续播（杀进程） | ✅ 2:10 退出 → 重进从 2:20 连续，未归零 | `s1-10`、`s1-11` |

## 5. 本轮修掉的 4 个真 bug

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | 一直播本地演示片，SMB 像没生效 | `resolvePlaySource` 在主线程做网络 IO → `NetworkOnMainThreadException` 被 `runCatching` 吞掉，静默退回本地 | 显式 `withContext(Dispatchers.IO)`；失败必须打日志（不再静默） |
| 2 | `STATUS_OBJECT_PATH_NOT_FOUND`，路径里 share 名重复 | `Uri.path` 是 `/课程共享/02 乐理…`，代码整段当共享内路径，拼成 `\\host\share\share\…` | 改用 `Uri.pathSegments.drop(1)`，并利用其已 URL 解码的特性处理中文/括号 |
| 3 | 杀进程后续播回 0 秒 | `resumeMap` 只在内存 | 新增 `data/ResumeStore`（SharedPreferences，键 `courseId:ep`），`saveProgress` 同步落盘 |
| 4 | 白底课件上左上角课程名看不清 | 原型两段渐变 `rgba(0,0,0,.82)→transparent` 是为暗色占位画面设计的 | 改三段并加深（`0xE8 → 0xB8@42% → transparent`），尾部 padding 70→96 |

第 4 条属于**接真数据后才暴露的问题**：原型画面是暗色占位图，永远看不出文字对比度不足。
这类问题只能在真片源上验收，凭空比对原型截图是发现不了的。

## 6. 尚未做（诚实边界）

- **数据源页的「测试连接」仍是假判定**（`addHost.isNotEmpty()`）。真实连接走的是探针/持久化配置，
  App 内的表单还没接 `SmbSession.connect`——因为 **TV 上没有输入法，密码框根本无法输入**。
  下一步必须先做「遥控器软键盘」（自绘九宫格/全键盘），否则添加数据源这一步在真机上走不通。
- **课程 ↔ 真实目录的映射还是过渡方案**：当前用「数据源 root 目录里第 N 个视频」当第 N 集，
  真正的绑定应由浏览页选中目录后写入 `purecourse_course_map`（P2）。
  所以现在书架里 8 门演示课程点进去，播的都是同一个目录的不同集。
- **FLV 的 seek 每次都要重新 `open` 数据源**（Media3 对无索引容器的处理方式）。
  实测 2~10ms、无句柄泄漏，但**连续快速拖动时能否保持流畅未做压测**（当前只看单次）。
- `idleReleaseSec`（空闲 30 秒断开）已实现但**未验证定时器真的触发**：实测都是"杀进程即断"。
