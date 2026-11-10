# 签名文件说明

本目录用于存放发布签名 keystore，**keystore 本体不入库**（见根目录 `.gitignore`）：
公开仓库里带 jks + 明文口令，等于把"可冒充本包发布更新"的能力交出去。

## 本机打包怎么做

1. 把 keystore 放到 `keystore/courseplayer.jks`；
2. 在根目录 `local.properties`（已 gitignore）里写口令：

```properties
signing.storePassword=你的store口令
signing.keyPassword=你的key口令
signing.keyAlias=你的别名
```

3. `./gradlew :app:assembleRelease`

口令也可以走环境变量：`SIGNING_STORE_PASSWORD` / `SIGNING_KEY_PASSWORD` / `SIGNING_KEY_ALIAS`。

## 别人 clone 下来会怎样

`keystore/` 里没有 jks、`local.properties` 里没有口令时，`app/build.gradle.kts` 会自动退回
**debug 签名**——包照样能编出来、能装上，只是签名不同：
**用不同签名的包覆盖安装会失败（签名不一致），必须先卸载旧包**，卸载会清掉数据源配置与播放进度。

自己要做正式发布，请生成自己的 keystore：

```bash
keytool -genkeypair -v -keystore courseplayer.jks -keyalg RSA -keysize 2048 \
        -validity 10000 -alias courseplayer
```
