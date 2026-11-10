# 发布记录与发版流程

> 2026-11-10 首次公开发布：<https://github.com/jojotj/PureCourse> · Release `v2.0.8`

## 一、首次发布已完成的脱敏与瘦身

| # | 项 | 处理结果 |
|---|---|---|
| 1 | 源码内真实内网信息 | `app/src/local/.../BuiltinPresets.kt`（本机 SMB 主机 / 共享名 / 账号）**不入库**，仓库只提供 `BuiltinPresets.kt.example` 模板；`.gitignore` 忽略 `app/src/local/**/*.kt` |
| 2 | 文档正文 | `docs/*.md`、`app/src/main`、`app/src/clean` 内 48 处真实主机 / 共享名统一替换为示例值（`192.168.1.10` / `课程共享` / `舞蹈共享` 等） |
| 3 | 发布包预置数据 | 一律用 `clean` flavor：`BuildConfig.CLEAN_BUILD = true`，`ensureDefault()` 直接返回，首启无任何数据源 |
| 4 | 签名口令 | 从 `local.properties`（`signing.storePassword` / `signing.keyPassword`）或环境变量读取，仓库内无明文；`keystore/*.jks` 本体不入库 |
| 5 | 开发过程截图 | `docs/**/*.png|jpg` 不随仓库分发（549 张 / 248 MB）；仓库只保留 `assets/screenshots/` 下 4 张成品图 |
| 6 | APK 归档 | `dist/` 只入库当前发布版本的两个包，历史版本走 Release 附件 |
| 7 | git 历史 | 首次公开前将历史压缩为单个提交，旧历史（含带真实 IP 的源码与 549 张截图）不进远端；本地保留分支 `backup-pre-publish` 备查 |

## 二、发布前自检

```bash
# 1. clean 包里搜不到任何本机信息
unzip -p dist/PureCourse-<ver>-clean-armeabi-v7a.apk classes*.dex | strings | grep -E "192\.168\.|课程共享" || echo "clean"

# 2. 签名：v1 必须为 true（部分电视只认 v1）
apksigner verify --verbose dist/PureCourse-<ver>-clean-armeabi-v7a.apk

# 3. 包内 versionName / versionCode 与文件名一致
aapt dump badging dist/PureCourse-<ver>-clean-armeabi-v7a.apk | head -1
```

## 三、发版流程

```bash
# 1. 改 app/build.gradle.kts 顶部 appVersionCode / appVersionName（单一真源）
# 2. 在 docs/CHANGELOG.md 顶部记一条
./gradlew clean assembleCleanRelease        # dist/ 自动归档两个 ABI
# 3. 提交 + 推 + 打标签
git add -A && git commit -m "<ver>: <摘要>" && git push
git tag -a v<ver> -m "<ver>" && git push origin v<ver>
# 4. 建 Release，附 dist/ 两个包
gh release create v<ver> dist/PureCourse-<ver>-clean-*.apk --title "<ver> · <摘要>" --notes-file notes.md
```

## 四、待办

- README 截图会随版本更新，新增截图请放 `assets/screenshots/`（体积控制在 100 KB / 张以内）
- 若将来要改用「Release 附件 + 不入库 APK」的方式，把 `.gitignore` 里的 `!dist/...` 例外行删掉即可
