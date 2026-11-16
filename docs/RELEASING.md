# 发版指南

面向维护者。发布前请确保工作区干净、CI 通过。

## 1. 版本号

修改 `app/build.gradle.kts`：

```kotlin
versionCode = 10          // 每次发版 +1，v0.9.0 为 10
versionName = "0.9.0"     // 与 tag、CHANGELOG 和两份 README 保持一致
```

`versionCode` 必须严格递增，否则已安装的用户无法覆盖升级。同步更新 `CHANGELOG.md` 与 `README.md` / `README.zh-CN.md` 顶部的当前版本号。

## 2. 测试

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest   # 需要连接设备
```

## 3. 构建 release

```bash
./gradlew assembleRelease                  # arm64-v8a + armeabi-v7a
./gradlew assembleRelease -Pabi=arm64-v8a  # 只打 64 位 ARM（产物更小）
```

产物：`app/build/outputs/apk/release/app-release-unsigned.apk`。
发布资产按 ABI 命名，与 v0.9.0 保持一致：双 ABI 用 `imagefolder-open-vX.Y.Z-arm64-v8a_armeabi-v7a.apk`，单 ABI 用 `imagefolder-open-vX.Y.Z-arm64-v8a.apk`。

release 构建已开启 R8 代码压缩与资源收缩。请把 `app/build/outputs/mapping/release/mapping.txt` 一并归档，便于解析线上崩溃堆栈。

## 4. 签名

发布密钥**不要提交到仓库**，请离线备份。丢失后将无法发布同签名更新。

```bash
BT=$ANDROID_HOME/build-tools/35.0.0
KEYSTORE=/path/to/imagefolder-release.jks
KEY_ALIAS=imagefolder
PASS_FILE=/path/to/keystore-password.txt   # 一行密码；不要写进命令行或日志

# 16 KB page-size devices: keep shared libraries 16 KB aligned.
$BT/zipalign -f -P 16 4 \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  dist/app-release-aligned.apk

$BT/apksigner sign \
  --ks "$KEYSTORE" \
  --ks-pass "file:$PASS_FILE" \
  --key-pass "file:$PASS_FILE" \
  --ks-key-alias "$KEY_ALIAS" \
  --out dist/imagefolder-open-vX.Y.Z-arm64-v8a_armeabi-v7a.apk \
  dist/app-release-aligned.apk

$BT/apksigner verify --print-certs \
  dist/imagefolder-open-vX.Y.Z-arm64-v8a_armeabi-v7a.apk
```

minSdk 29 时默认使用 v3 签名即可。密码一律用 `file:` 传入，不要用 `pass:` 或明文写在命令行/日志里；本项目 keystore 密码与 key 密码相同，共用一个文件即可。

## 5. 校验文件

```bash
cd dist
sha256sum imagefolder-open-vX.Y.Z-arm64-v8a_armeabi-v7a.apk \
  > imagefolder-open-vX.Y.Z-arm64-v8a_armeabi-v7a.apk.sha256
```

`.sha256` 文件内只写文件名，不要包含本机绝对路径。

## 6. 创建 GitHub Release

可以网页手动创建，或使用 GitHub REST API：

1. `POST /repos/{owner}/{repo}/releases`，填写 `tag_name`、`target_commitish`、`name`、`body`
2. `POST https://uploads.github.com/repos/{owner}/{repo}/releases/{id}/assets?name=...` 上传 APK 与 `.sha256`

建议发布后通过 API 核对 asset 的 `digest` 与本地 SHA-256 一致。GitHub 下载 CDN 在部分网络下可能被重置，此时 API digest 是可靠的完整性依据。

## 7. 发布检查

- [ ] CI 通过
- [ ] APK 签名证书与上一版一致（可直接覆盖升级）
- [ ] asset digest 与本地 SHA-256 一致
- [ ] Release notes 写明兼容性变化
- [ ] 真机安装 Release APK 并启动验证
- [ ] R8 mapping 与 `.apk.sha256` 一并归档，便于解析崩溃堆栈与复核完整性
- [ ] 若界面有变化，更新 `docs/screenshots/` 与 README

## CI 说明

GitHub Actions 使用 runner 预装的 Android SDK。不要重新引入 `android-actions/setup-android@v3`——它在新 runner 上会执行已移除的 `sdkmanager tools` 并失败。

确保 `gradlew` 在 git 中保持可执行位：

```bash
git update-index --chmod=+x gradlew
```
