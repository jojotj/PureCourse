# 发布前清单（现在请勿执行）

> 本文只是**备忘**。2026-11-08 明确：本工程当前处于调试期，可能仍有大量缺陷，
> 目录已按 GitHub 标准组织好，但**尚未发布**。等整体联调完成后按本清单走一遍再推远端。

## 一、必须做（不处理会泄露内网信息）

| # | 位置 | 内容 | 处理方式 |
|---|---|---|---|
| 1 | ~~`app/src/main/java/.../data/RealCatalog.kt`~~ | 真实 IP / 账号 / shortcuts 已迁到 `app/src/local/.../BuiltinPresets.kt`（2.0.4 起） | **已解决**：发布一律用 `clean` flavor，`app/src/clean/.../BuiltinPresets.kt` 全空，真实值不参与编译。推远端前确认 `src/local` 是否要一并入库（若入库，git 历史里仍有真实 IP，建议删除该目录后再提交，或整仓改历史） |
| 2 | `app/.../data/SourceStore.kt` → `ensureDefault()` | 首次启动会把预置源写进 SharedPreferences | **已解决**：clean 变体 `BuildConfig.CLEAN_BUILD = true`，`ensureDefault` 直接返回，首启无任何源 |
| 3 | `docs/04、08、09、10、12、13、14、15` 共 8 份文档 | 正文里有 `192.168.1.10` / `192.168.1.11`（电视）/ `NAS\guest` / 账号 `guest` | 全局替换成 `192.168.1.10` / `guest` 之类示例值 |
| 4 | `app/build.gradle.kts` → `signingConfigs` | `storePassword` / `keyPassword` 目前是明文 `courseplayer` | 改为从 `local.properties` 或环境变量读取，仓库内不留明文口令 |

## 二、建议做（影响仓库体积与观感）

| # | 内容 | 说明 |
|---|---|---|
| 5 | `docs/` 截图瘦身 | 当前 `docs/` 根 + `evidence/` 共 604 张、约 250 MB。其中大量是调试过程图，正文未引用。可先统计引用再剔除，能把 clone 体积压到 1/5 |
| 6 | `dist/*.apk` 是否入库 | 现在入库（约 12.4 MB / ABI），好处是访客直接下载；坏处是每次发版都进 git 历史。若改用 GitHub Release 附附件，可把 `dist/*.apk` 加进 `.gitignore` |
| 7 | `keystore/courseplayer.jks` | 当前 `.gitignore` 已排除，别人 clone 后会用 debug 签名出包（能装，不能覆盖升级正式包）。若打算长期维护，建议只在 Release 里附正式包 |
| 8 | `LICENSE` | 现为 MIT，确认署名信息（年份 / 作者名）是否正确 |

## 三、发布动作（联调完成后）

```bash
# 1. 按上面 1~4 做脱敏，并在真机复验一次首启流程
# 2. 重新出包（对外发布的必须是 clean flavor）
./gradlew clean assembleCleanRelease
# 3. 校验签名（v1 必须为 true，TCL 电视只认 v1）
apksigner verify --verbose dist/PureCourse-<ver>-clean-armeabi-v7a.apk
# 4. 建远端、首推
git remote add origin <你的仓库地址>
git branch -M main
git push -u origin main
# 5. 打标签并在 GitHub Release 附两个 ABI 的 APK
git tag -a v2.0.0 -m "2.0.0"
git push origin v2.0.0
```

## 四、当前状态（2026-11-08）

- 目录结构、README、CHANGELOG、LICENSE、`.gitignore`、`.gitattributes` 已按公开仓库标准就位
- `git` 已在本地初始化并提交，**未添加远端、未推送**
- 源码保留本机真实默认数据源（方便调试），集中在 `app/src/local/.../BuiltinPresets.kt`；`local.properties` 被忽略
- 2.0.4 起发布包改走 `clean` flavor，已实测：clean 包 dex 内搜不到 `192.168.1.10` / `guest` / `课程共享` / `舞蹈共享`，
  首启数据源列表为空、书架 0 门课程
- `1.0` 工程未做任何改动
