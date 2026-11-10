# 1.0 开发记录存档（README-legacy）

> 本文件是 2.0 之前的 README 原文归档，保留了从 tv5 到 tv9.16 的逐版交付记录、真机验收数据与踩坑细节。
> 面向新访客的说明看仓库根目录的 README.md，版本演进概览见 CHANGELOG.md。

---

# 纯看课 · 安卓 TV 课程播放器（1.0）

把已定稿的 HTML 交互原型 `merged-shelf-list.html` 1:1 落地成**可安装的 Android TV APK**。

- 界面 **100% 自研**（Compose for TV 只借主题基座，控件全手写，不用 Leanback 的 Card/Carousel）
- 交互基础是**遥控器五键**（上下左右 / 确认 / 返回），任何功能都必须纯遥控器闭环，**长按不是唯一入口**
- SMB **真连真读**（P1 起接 smbj，允许失败，但不许"演示态假成功"）
- 播放内核：**Media3(ExoPlayer) + 自定义 `SmbDataSource`**（双 PoC 已拍板，libmpv 未进包，见 docs/03）

当前阶段：**P0 / P1-lite / P2（真实 SMB 目录接入）/ P3 / 电视真机专项完成**。
真实 SMB 共享（`\\192.168.1.10\课程共享` 等）可连、可列、可播，会话恒 1 条、随机读 2~10 ms；
真课程视频可播且可 seek / 倍速 / 镜像 / 续播（docs/09）；数据源表单接 TV 自带输入法、真连测试、
配置落盘还原（docs/10）。TCL 电视实测：视频走 `OMX.MS.AVC.Decoder` 硬解、起播 496 ms、
1x Janky 0.00% / 2x Janky 4.71%、无 ANR 或崩溃（docs/12）。

**当前交付包 `1.0.0-tv9.8`（versionCode 19）**。数据层是真实 SMB 目录（tv5 起），
片尾开关根因已修（tv6），tv7 做了片尾回退 / 跟手性 / 全站视觉，tv8 修可用性硬伤，
tv8.1 补输入框光标，tv8.2 加数据源别名，**tv9.x 把数据源模型改成「整机模式」**。

**tv9.8 做的 6 件事**（详见 `docs/evidence/tv9.8-browse-parity.txt`）
- **数据源页与收藏页改成同一套版式**：右栏面包屑行删掉（路径移进标题行右端，与收藏页同一位置）、
  行高 76→72、去掉左侧 5px 色条与外扩焦点框、圆角 12、面板底 + Line 细描边、
  字号统一 23/17、行内按钮换成收藏页「⋯」同材质的方片、补上底部提示条。
- **左树图标统一**：原来 `depth==0` 画 ▦、其它画 ▤，同一层的四个共享图标都不一样；
  现在所有目录一律 ▤，箭头统一 ▸ / ▾ / ·。
- **修「课程共享」不能展开**：整机模式下共享节点的 `countText` 用的是"识别成课程的目录数"，
  课程共享顶层三个目录都不直接含视频 → 记成"共享" → 被判定为不可展开，箭头画成了另一个符号。
  现在按**真实文件夹数**计数（`nDirs`），树里 `has` 也改为读真实子目录数。
- **修「明明不在书架却显示已在书架」**：移出书架只把 `Course.out=true`，`courseDirs` 没动，
  而 `inShelfPath()` 只查 `courseDirs` → 浏览页仍然标「★ 已在书架」。
  新增 `shelfCourseOf()`（排除 out）统一判定；移出时同时清 `shelvedKeys` 并落盘 `removed_paths`，
  冷启动不再把移出的课扫回来；重新加入时**复用原卡片**（不新建同名课程）。
- **数据源页确认键改成打开同一个文件夹操作小框**：原来是直接加书架 + 底部弹 toast，
  和收藏页的「＋加入书架 / ☆收藏当前」完全是两套东西；现在两页共用一个弹窗、同一个快照对象。
- **修书架页多选后光标移不到「移出书架 / 全选 / 完成」**：`regionsSignature()` 漏了
  `multiOn/multiCtx/multiSet/favRmSet/fpop*` 等会改变**区域表内容**的状态 → 缓存签名不变、
  复用旧表 → 新出现的 `multi:0/1/2` 被判成"不存在"，`setFocus` 直接 return。
  补齐签名；同时把多选条的 ↑ 改为原地不动（上方顶栏已被多选条盖住）、↓ 落回 hero。

**tv9.2 做的 6 件事**（详见 `docs/evidence/tv9.2-whole-host.txt`）
- **一个 IP = 一个数据源**：`Cfg.share` 留空即整机模式，服务端枚举出的**每个共享都是浏览根**，
  不用再「编辑里选一个共享」。统一路径语义 `共享名\子目录\…`，会话层按首段自动切共享。
- **修共享句柄争用**：所有 SMB IO 串行化，新增原子 `listIn(share, path)`；
  `connect` 也进锁（之前两线程并发建会话互相覆盖）→ 深层目录不再偶发「空目录」。
- **断线自愈**：会话失效后强制重连重试一次，`soTimeout` 20s→120s（浏览停顿不再被判超时）。
- **收藏持久化**：以前只存内存，电视按一次 Home 就全丢；现在落 `SourceStore.fav_paths`，
  行内按钮回显「★ 已收藏」，再按一下取消。
- **播放状态持久化**：新增 `CourseMetaStore`（key 用目录路径，不用会移位的课程 id），
  冷启动不再退回「尚未播放」，「继续学习」能正确指向最近那门课。
- **修「进度记到别的课」**：退出播放后书架会改 `sel`，播放页 `onDispose` 又存一次进度，
  把「最近播放」写到焦点课程上。现在用进播放页时锁定的 `playCourse`。

**tv8.2 内容**（详见 `docs/evidence/tv8.2-shares-and-name.txt`）
- 数据源可自定义名称（表单「名称」行，留空则显示 IP；卡片 / 顶栏都用别名）
- 「共享 / 路径」留空会真的去枚举该主机全部共享（手写 srvsvc `NetrShareEnum`，
  smbj 0.13 没有 `listShares`，报文按 jcifs-ng 的真实编码逐字段对齐）

**tv8.1 修掉的 2 项**（详见 `docs/evidence/tv8.1-caret.txt`）
- **光标移不动、只能整行重输**：电视输入法只有「退格删左边」，没有正向删除。
  现在光标由 `AppState.editCaret` 统管，遥控器左右键直接改它；输入框用 `TextFieldValue`
  双向同步（输入法自己的 `setSelection` 不触发 `onValueChange`，必须反向回报）。
- **键盘收起后遥控器左右键还是不动**：编辑态 BACK 改两段 ——
  第一次只收键盘、**编辑态保留**（接着就能用遥控器移光标 / 退格删字），第二次才退出编辑。
- 说明：曾想按「输入法是否弹出」分流左右键，实测该信号在本工程拿不到
  （`dumpsys input_method` 里 `mInputShown=true`，而本 App 收到 `imeBottom=0`、
  `frame hidden=0/1440`，因为 `adjustPan` + 沉浸全屏不派发 IME inset），
  所以改为不依赖它的确定性规则；键盘屏上左右箭头走 `InputConnection`，与我们的接管不冲突。

**tv8 修掉的 9 项**（详见 `docs/evidence/tv8-acceptance.txt`）
- **设置页按到最下面的「关于」看不到、光标消失**：滚动原来只保证"分组标题"可见，
  又把"露出 1px"当成可见；改成「整行在视口内」才算可见 + 滚到目标行 + 底部留白。
- **输入框左右键不能移光标、删不掉后面的字**：`dispatchKeyEvent` 把编辑态放行的
  事件又无条件 `return true` 吞了，改为 `super.dispatchKeyEvent`；输入框换
  `TextFieldValue`，自己接管左右键改 selection（TV 输入法不处理硬件方向键）。
- 冷启动有张卡片莫名高亮（`sel` 未对齐 Hero 的 `recentIdx()`）；
- 设置项不持久化（新增 `SettingsStore`，9 项跨冷启动保留）；
- 播放页返回键落点与片尾回退不一致（统一用 `playerFrom`）；
- 次要文字太暗、「硬件解码」文案误导、详情页主按钮颜色不统一、收藏页底部留白。

**tv7 内容**：片尾不跨课程（播完回播放前界面）、遥控器跟手性（区域表缓存 /
收藏页方向键死角修复 / 按需无动画滚动）、全站视觉重做（深色层级 + 白环橙光 +
实体卡片 + 胶囊按钮）。

完整证据：`docs/evidence/tv8-acceptance.txt`、`tv7-acceptance.txt`；
tv6 见 `docs/evidence/tv6-acceptance.txt`；tv5 见 docs/04 §tv5；
tv4（字号/行距）见 docs/14、tv3（返回键分级）见 docs/13、tv2 见 docs/12 §7。

> ⚠️ TCL 电视（`192.168.1.11`）目前仍是旧版本，**tv9.8 待 U 盘安装**
> （电视端一律不推送，只用 U 盘 + 系统安装器；拷 U 盘时用短文件名，长中文名会导致 `classes.dex` 损坏）。
> tv7 真机已验证：新视觉、收藏页方向键（原来动不了的死角）、片尾回退、动态版本号。
> **按用户要求全程不向电视推送文件**；TCL 只能用 U 盘 + 系统安装器。
>
> 历史记录：2026-11-06 17:09 首次 U 盘装 tv6 连试 3 次失败，已定位为
> **U 盘上那份文件 `classes.dex` 单块损坏**（8161 字节差异集中在 3 MiB 起的
> 8 KiB 块、v2 验签 `CHUNKED_SHA256 digest mismatch`），**不是 APK 构建问题**；
> 改用短名干净副本重拷后安装成功。完整证据 `docs/evidence/tv6-install-failure.txt`。
>
> 误判纠正：tv7 记录里说「片尾回书架后焦点落在第 6 张卡片」，
> 实际那是卡片的**选中态**（`sel` 没对齐 Hero），焦点本身是对的。
> tv8 已把 `sel` 对齐到 `recentIdx()`，不再出现这张无关高亮。

下一步：
- 电视 U 盘装 tv7，补验真机观感与片尾回退；
- `SmbSession` 加同步锁 + 补空闲断开定时器，让「最多一条」成为并发安全保证；
- P4 完整压测（20 并发、长按 800ms、休眠唤醒、HDMI ARC）→
  本地 U 盘 / SD 卡浏览（当前如实标注「尚未实现」）。

---

## 1. 构建

环境（本机已就绪）：

| 项 | 版本 / 路径 |
|---|---|
| JDK | 21（`C:\Users\Jojotj\pytools\rea\jdk-21.0.12.1+1`） |
| Gradle | 8.13（`C:\Users\Jojotj\pytools\gradle\gradle-8.13`） |
| AGP / Kotlin | 8.13.2 / 2.1.20 |
| compileSdk / targetSdk / minSdk | 36 / 34 / 21（移除 libmpv 后下调；电视 API 30 实测正常） |
| Android SDK | `C:\Users\Jojotj\WorkBuddy\2026-10-20-00-33-37\audioplayer\sdk` |

```powershell
$env:JAVA_HOME = "C:\Users\Jojotj\pytools\rea\jdk-21.0.12.1+1"
& "C:\Users\Jojotj\pytools\gradle\gradle-8.13\bin\gradle.bat" ":app:assembleDebug" --console=plain
& "C:\Users\Jojotj\pytools\gradle\gradle-8.13\bin\gradle.bat" ":app:assembleRelease" --console=plain
```

产物（按 ABI 拆包）：`app/build/outputs/apk/{debug,release}/app-{arm64-v8a,armeabi-v7a,x86_64}-{debug,release}.apk`
发布副本放 `dist/`。

> 工程路径含中文（`E:\Github\纯看课\1.0`），已在 `gradle.properties` 关掉 AGP 的路径 ASCII 校验
> （`android.overridePathCheck=true`）。

## 2. 安装与调试

```bash
ADB="C:\Users\Jojotj\AppData\Roaming\SideQuest\platform-tools\adb.exe"

# MuMu 模拟器验收 TV 版式（结束后 wm size reset 复原）
"$ADB" shell wm size 1920x1080
"$ADB" install -r app/build/outputs/apk/debug/app-x86_64-debug.apk
"$ADB" shell am start -n com.chunting.purecourse.dbg/com.chunting.purecourse.MainActivity

# 直达某页（自动化验收用）
"$ADB" shell am start -n com.chunting.purecourse.dbg/com.chunting.purecourse.MainActivity --es page player
#   page ∈ {player, playerctl, settings, sources, addsrc, detail, browse, fav, smbfull, diag}

# 内核 PoC 台架
"$ADB" shell am start -n com.chunting.purecourse.dbg/com.chunting.purecourse.PocActivity
"$ADB" logcat -s POC:*
```

遥控器五键等价键值：`19` 上 / `20` 下 / `21` 左 / `22` 右 / `23` 或 `66` 确认 / `4` 返回。
注意：**全屏态按确认 = 播放/暂停切换**（与原型 `pEnter()` 一致），不是"收起面板"；
控制层态按确认才执行焦点控件动作。自动化测量时误按会拿到"暂停态 0 帧"的假数据（docs/12 §3.5）。

### 桌面上会看到两个「课程库」，怎么区分

两个包可以共存（同名应用装不上，所以 debug 用 `applicationIdSuffix` 隔开）：

| 桌面显示 | 包名 | 来源 | 用途 |
|---|---|---|---|
| **课程库**（标准橙、无徽标） | `com.chunting.purecourse` | `dist/课程库-1.0-TV-tv6(装这个).apk`（release，正式签名，v6） | **交付用**，装电视就装这个 |
| **课程库·调试**（深橙 + 右下白点徽标） | `com.chunting.purecourse.dbg` | `app/build/outputs/apk/debug/*` | 开发迭代用，可随时 `install -r` 覆盖，不会动到 release 包 |

```bash
# 只想要一个：卸掉调试包（保留交付包）
adb uninstall com.chunting.purecourse.dbg
# 或者反过来，只留调试包
adb uninstall com.chunting.purecourse
```

两者的数据互相独立（不同包名 = 不同沙盒）。

> **日常验收只看「课程库」（release）**。改完代码我会 `assembleRelease` 出包；
> 在**手机 / 模拟器**上可 `adb install -r` 覆盖（同签名不丢数据）；
> 在 **TCL 电视**上必须 U 盘 + 系统安装器（ADB 侧安装通道被封，见 docs/11、docs/12 §8）。
> 调试包只在需要抓 logcat / 断点时才临时装。

## 3. 目录结构

```
app/src/main/java/com/chunting/purecourse/
├── MainActivity.kt          单 Activity，dispatchKeyEvent 统一接管五键
├── App.kt                   Application
├── PocActivity.kt           内核 PoC 台架界面（adb 唤起，不进原型 UI）
├── core/Dims.kt             设计令牌（颜色/圆角/焦点环），数值逐条抄自原型 CSS
├── data/                    Model.kt · RealCatalog.kt（真实 SMB 扫描）· SourceStore.kt
│                            · SmbSessionImpl.kt · ResumeStore.kt（Demo.kt 已在 tv5 删除）
├── state/
│   ├── AppState.kt          全部状态 + 动作（对应原型全局变量与函数）
│   ├── Nav.kt               区域导航 REG/EXIT + activate + 各页交互动作
│   └── KeyRouter.kt         五键分层路由 + back() 分级返回
├── ui/
│   ├── Stage.kt             1920×1080 逻辑画布（min(vw/1920, vh/1080) 等比缩放）
│   ├── TopBar.kt            常驻顶栏（播放页整条隐藏）
│   ├── TvWidgets.kt         文本/封面渐变/进度条/暂停符/自绘图标
│   ├── Overlays.kt          多选条 / Toast / 二次确认 / SMB 诊断 / 文件夹小框
│   └── pages/               Shelf · Fav · Detail · Browse · Settings · Sources · AddSource · Player
└── player/                  PlaybackEngine · Media3Engine · SmbDataSource · PocBench（libmpv 已移除）
```

## 4. 焦点系统（与原型同构，不用系统 FocusFinder）

原型自研了 `REG / EXIT / NAV` 区域导航；本工程完整移植：

- 每个区域声明 `Kind`（H/V/GRID/SINGLE）与列数，方向键按**下标**算目标，越界走**显式出口**
- 书架 6 列：`down` 落到下一行同列，末行不满 6 本时落到下一行最接近的一列（补 ghost 占位）
- 焦点样式全站唯一：橙色描边 + 双层外环 + 轻微缩放（`Modifier.tvFocus`）
- 每页有默认落点，返回时优先还原来源控件
- **为什么不用 Compose 焦点**：TV 上默认 FocusFinder 按几何坐标找邻居，行数一变 / 出现隐藏元素就必然有方向移不到

## 5. 视觉对齐方式（可复跑）

1. 用 puppeteer 以 **1920×1080 / deviceScaleFactor=1** 打开原型，逐页截基准图并导出关键元素的
   盒模型（x/y/w/h/font/radius）→ `docs/ref-*.png`、`docs/ref-01-metrics.json`、`docs/ref-pages-metrics.json`
2. `adb exec-out screencap` 截同一页 → `docs/app-*.png`
3. 按 metrics 里的实测数值校正 dp（例如 `.sx` 的 112 是从**舞台顶部**算的，不是从顶栏下沿）

> 注意：`前期准备/ui-01-shelf.png` 是**旧版**截图（4 列 + 顶栏 4 个 Tab），
> 与当前定稿原型（6 列 + hero 右侧「选择/移除」）不一致，不要拿它当基准。

## 6. 已知边界（截至 tv8）

- 课程 ↔ 真实目录绑定已在 tv5 完成（每门课按真实文件数生成独立剧集列表）
- 数据源的编辑 / 重连 / 删除已在 tv5 改为真动作；`SourceStore` 只存一条配置
- 覆盖安装会继承旧数据源 root（tv4→tv5 实测暴露），当前无配置版本迁移
- 设置页版本号已在 tv7 改为从 `PackageManager` 读取，不再写死（tv6 显示 tv5 的问题已修）
- SMB 会话管理未加同步锁；并发重连理论上可能短暂泄漏第二条 TCP（未复现）
- `idleReleaseSec` 默认 30 秒但缺空闲断开定时器，退出播放页后连接保持到手动断开/进程结束
- Room 持久化未接入，续播用 SharedPreferences（杀进程重启已验证不丢）
- 长按 800ms、真实休眠唤醒、HDMI ARC / 功放直通、P4 占满 20 条并发的失败层 UI：未验证
- **字体锐度受电视硬件限制**：4K 面板但系统只给 1080p 画布，HWC 放大 2×，App 侧无法消除；
  已通过两轮放大字号缓解（tv3 统一字号、tv4 再放大三处），详见 docs/13 §5、docs/14 §3~4；
  MuMu fit=1.333 而电视 fit=1.000，是「模拟器清楚、电视发糊」的定量原因
- 收藏左栏「按协议分组小标题」、书架手动排序：未做（原型也未做）

## 7. 文档

- [`docs/01-页面拓扑与交互约束.md`](docs/01-页面拓扑与交互约束.md) — 接手方对原型的完整复述
- [`docs/02-继承前序结论.md`](docs/02-继承前序结论.md) — VLC 改造踩过的 4 个坑与本文的落地方式
- [`docs/03-内核PoC报告.md`](docs/03-内核PoC报告.md) — Media3 / libmpv 两组实测结论（等拍板）
- [`docs/04-路线图与验收.md`](docs/04-路线图与验收.md) — P0 验收结果 + P1~P5 计划
- [`docs/05-视觉对齐记录.md`](docs/05-视觉对齐记录.md) — 逐页对齐过程与已知偏差
- [`docs/06-分辨率适配.md`](docs/06-分辨率适配.md) — 1080P / 2K / 4K 为何显示完全一致（含实测数据）
- [`docs/07-SMB连接策略.md`](docs/07-SMB连接策略.md) — 能查什么/能清什么/怎么只占一条会话
- [`docs/08-P3内核接线与联调.md`](docs/08-P3内核接线与联调.md) — Media3 + SMB 数据源接线、验证证据与卡点
- [`docs/09-SMB联调与真播放.md`](docs/09-SMB联调与真播放.md) — 真实共享联调实测（延迟/吞吐/会话数）与真片源播放验收
- [`docs/10-数据源输入与真实连接.md`](docs/10-数据源输入与真实连接.md) — 接系统输入法（编辑态/导航态隔离）、真实测试连接、配置持久化
- [`docs/11-电视安装通道排查.md`](docs/11-电视安装通道排查.md) — TCL 电视 adb 安装被封的实证过程、参数对齐、U 盘安装指引
- [`docs/12-电视真机专项验收.md`](docs/12-电视真机专项验收.md) — **电视端专项验收**：硬解路径、起播/1x/2x 性能、显示与音频、遥控器与 TV 输入法、稳定性、两个真 bug 的修复与自纠记录
- [`docs/13-返回键与字号及4K锐度专项.md`](docs/13-返回键与字号及4K锐度专项.md) — 返回键分级 / 退出、收藏页字号统一、4K 面板 1080p 画布 HWC 放大导致字体发虚的硬件证据与 tv3 回归
- [`docs/14-字号放大与行距及4K锐度复验.md`](docs/14-字号放大与行距及4K锐度复验.md) — 清理电视推送文件、三处字号放大、设置页行距放松、原始帧锐度与 1080p→4K HWC 放大/MuMu fit 对照、tv4 回归
- [`docs/evidence/tv6-acceptance.txt`](docs/evidence/tv6-acceptance.txt) — tv5 电视真机补验、片尾 bug 根因定位与归因自纠、tv6 修复、MuMu 回归、SMB 单连接服务端实测

## 8. 许可

MIT，见 [LICENSE](LICENSE)。
