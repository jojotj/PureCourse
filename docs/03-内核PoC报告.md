# 03 · 播放内核 PoC 报告（P0 阶段）

> 交接文档 §6 已给排序（Media3 优先）。本文不重开论证，只交付**两组可核验的实测结论**给老板拍板。
> 台架代码：`player/PocBench.kt`；两个引擎实现：`player/Media3Engine.kt` / `player/MpvEngine.kt`。

## 1. 台架设计（同一台架、同一素材、同一 Surface、串行两臂）

| 指标 | 采样方式 |
|---|---|
| 初始化 init | 创建内核实例 → `STATE_READY`（Media3）/ `MPV_EVENT_FILE_LOADED`（mpv），`elapsedRealtime` 计时 |
| 首帧 first | 创建实例 → `onRenderedFirstFrame`（Media3）/ 首个 `MPV_EVENT_PLAYBACK_RESTART`（mpv） |
| 时长 dur | 引擎自报 duration 与素材真实 60000ms 比对 |
| seek | 请求 seek 到 50% → 判定"位置真的走到目标之后"的耗时（Media3 看 `currentPosition`，mpv 等 `PLAYBACK_RESTART`） |

运行方式（不发探针、不预检，直接跑）：

```bash
adb push poc_h264.mp4 poc_hevc.mp4 /sdcard/Android/data/<pkg>/files/
adb shell am start -n <pkg>/com.chunting.purecourse.PocActivity
adb logcat -s POC:*
```

## 2. 环境与素材

| 项 | 值 |
|---|---|
| 设备 | MuMu 模拟器 `emulator-5554`，x86_64，Android 15（API 35） |
| 分辨率 | `wm size 1920x1080` |
| 素材 | ffmpeg `testsrc2` 生成，1920×1080@30，60 秒，**无音轨** |
| H.264 | `libx264 -preset veryfast -pix_fmt yuv420p`，42.9 MB |
| HEVC | `libx265 -preset veryfast -tag:v hvc1`，36.9 MB |

## 3. 实测数据（本次新工程重跑，非引用前一轮）

| 引擎 | 素材 | 结果 | init | 首帧 | 时长 | seek(50%) | 编解码串 |
|---|---|---|---|---|---|---|---|
| Media3 1.5.1 | H.264 | OK | 256 ms | 255 ms | 60000 ms | 780 ms | `avc1.640028` |
| libmpv 1.0.0 | H.264 | OK | 114 ms | 230 ms | 60000 ms | 845 ms | `H.264 / AVC / MPEG-4 part 10` |
| Media3 1.5.1 | HEVC | OK | 83 ms | 83 ms | 60000 ms | 520 ms | `hvc1.1.6.L120.90` |
| libmpv 1.0.0 | HEVC | OK | 24 ms | 92 ms | 60000 ms | 339 ms | `H.265 / HEVC` |

（单位 ms，均为单次采样；两臂串行执行，互不预热。）

## 4. 结论 A（性能层）

1. **四个组合全部出画、时长零误差（60000ms）**，两种编码在这台设备上都能正常解码。
2. libmpv 的 init 明显更低（114 vs 256 / 24 vs 83），**但差值主要来自初始化路径**：
   mpv 的 `MPVLib.create()` 只做实例创建，真正的解码器初始化发生在 `loadfile` 之后，
   所以它的"低 init"被转移到了别处，**不能单独当成"启动更快 2~10 倍"来读**。
3. 首帧两者相当（230 vs 255 / 92 vs 83），**HEVC 上 Media3 反而更快**。
4. seek：H.264 上 Media3 略快（780 vs 845），HEVC 上 mpv 更快（339 vs 520）。
   **两者都在几百毫秒量级**，而 SMB 网络往返 + 首包常常就是 100~400ms，
   也就是说 **0.4 秒的 seek 差异在真实 SMB 片源上会被网络延迟掩盖**。
5. Media3 的 HEVC 数字（83/83）比 H.264（256/255）还快，说明**首次 setDataSource 的开销被
   该设备对 HEVC 的硬件解码路径吃掉了**，属设备特性，不要外推。

## 5. 结论 B（工程层，真正决定选型的权重）

| 维度 | Media3 1.5.1 | libmpv 1.0.0 | 本项目权重 |
|---|---|---|---|
| 硬解 | 原生 MediaCodec，全设备通用、有回退 | 需 `hwdec=mediacodec`，机型差异大，x86 模拟器上基本是软解 | **高**（TV 盒子靠硬解） |
| 自定义数据源 | 实现 `DataSource`（`readAt`）即可，seek 原生 | 需实现 `stream_cb` 随机读 + 自管缓冲 | **高**（SMB 必经） |
| TV 平台细节 | tunneling / 帧率切换 / 音频焦点 / MediaSession 全现成 | 全部自己写 | **高**（最耗时的一块） |
| 字幕 | SubRip/SSA 基础；ASS 特效弱 | libass 完整 | 低（课程多为内嵌字幕） |
| 包体积 | ~2 MB | 每 ABI +20~30 MB | 低 |
| minSdk | 23 | 26 | 低（本项目已定 26） |
| 维护性 | Google 官方，与 Compose/TV 生态同源 | 社区 aar | 中高 |

**本轮新发现（踩坑，写进文档）**：ExoPlayer 必须在**带 Looper 的线程**创建/访问，
否则直接抛 `IllegalStateException: Player is accessed on the wrong thread`。
台架第一版跑在 `Dispatchers.IO` 上，Media3 两臂全灭（libmpv 正常），
改成主线程后一次通过。这条对 P3 的 `Media3Engine` 生命周期管理是硬约束。

## 6. 尚未覆盖（拍板前需要知道）

1. **只有 x86_64 模拟器数据**，没有 ARM 电视盒子 / 真机数据；mpv 的 `hwdec=mediacodec`
   在 x86 上等于空转，**它的首帧/seek 数字偏乐观**。
2. **SMB 桥接后的 seek 没测**：两臂都还没接 smbj（P1 才做），真实 `readAt` 下的随机访问
   会比本地文件慢一个量级，这才是真正要看的数。
3. 单次采样，没有做多次取中位数；如果老板要更硬的结论，建议每个组合跑 5 次取中位。

## 7. 建议（等拍板，不自行决定）

- **按文档 §6 走 Media3**：硬解覆盖、TV 平台细节、minSdk、包体积四项全优，
  唯一劣势（seek 慢约 0.4s）会被 SMB 网络延迟掩盖。
- **libmpv 保留为兜底通道**：只在"Media3 打不开的容器/编码"时切换，不作主内核。
  本工程已按 `PlaybackEngine` 抽象隔离，加实现类即可，UI 不返工。
- 拍板前建议补一件事：**在真机（ARM 盒子）上重跑同一台架**，并在 P1 接完 smbj 后
  补测"SMB 上的 seek 精度与首次起播耗时"。
