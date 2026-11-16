# 发版指南

面向维护者。发布前请确保工作区干净、CI 通过。

## 1. 版本号

修改 `app/build.gradle.kts`：

```kotlin
versionCode = 12          // 每次发版 +1，v1.0.0 为 12
versionName = "1.0.0"     // 与 tag、CHANGELOG 和两份 README 保持一致
```

`versionCode` 必须严格递增，否则已安装的用户无法覆盖升级。同步更新 `CHANGELOG.md` 与 `README.md` / `README.zh-CN.md` 顶部的当前版本号。

## 2. 测试

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest   # 需要连接设备
```

## 3. 构建 release

每次发版**出两个包、传两个资产**：

```bash
./gradlew assembleRelease -Pabi=arm64-v8a   # 约 5.5 MB，只含 64 位 ARM
./gradlew assembleRelease                   # 约 7.4 MB，arm64-v8a + armeabi-v7a
```

| 构建命令 | 产物 / 资产名 | 给谁 |
| --- | --- | --- |
| `assembleRelease -Pabi=arm64-v8a` | `imagefolder-open-vX.Y.Z-arm64-v8a.apk` | 绝大多数现代手机，体积小 |
| `assembleRelease`（省略 `-Pabi`） | `imagefolder-open-vX.Y.Z-universal.apk` | 兜底：同时含 32 位 ARM，老设备用 |

文件名由 `app/build.gradle.kts` 的 `androidComponents` 生成，直接就是发布资产名，不要再手工改名。
两次构建会互相覆盖 `app/build/outputs/apk/release/` 下的产物，所以**先构建、签名、归档一个，再构建下一个**。

v0.10.0 起不再使用 `arm64-v8a_armeabi-v7a` 这种后缀；历史资产的名称保持不变，不要重传或改名。

release 构建已开启 R8 代码压缩与资源收缩。请把 `app/build/outputs/mapping/release/mapping.txt` 一并归档，便于解析线上崩溃堆栈。

## 4. 签名

发布密钥**不要提交到仓库**，请离线备份。丢失后将无法发布同签名更新。

两个包用同一把 key 签，证书必须与上一版一致，否则用户无法覆盖升级。

```bash
BT=$ANDROID_HOME/build-tools/35.0.0
KEYSTORE=/path/to/imagefolder-release.jks
KEY_ALIAS=imagefolder
PASS_FILE=/path/to/keystore-password.txt   # 两行：同一个密码写两次（见下方说明）
VERSION=vX.Y.Z

mkdir -p dist
for ABI in arm64-v8a universal; do
  APK=app/build/outputs/apk/release/imagefolder-open-$VERSION-$ABI.apk
  # 16 KB page-size devices: keep shared libraries 16 KB aligned.
  $BT/zipalign -f -P 16 4 "$APK" "dist/$ABI-aligned.apk"

  $BT/apksigner sign \
    --ks "$KEYSTORE" \
    --ks-pass "file:$PASS_FILE" \
    --key-pass "file:$PASS_FILE" \
    --ks-key-alias "$KEY_ALIAS" \
    --out "dist/imagefolder-open-$VERSION-$ABI.apk" \
    "dist/$ABI-aligned.apk"

  $BT/apksigner verify --print-certs "dist/imagefolder-open-$VERSION-$ABI.apk"
  rm -f "dist/$ABI-aligned.apk"
done
```

minSdk 29 时默认使用 v3 签名即可。密码一律用 `file:` 传入，不要用 `pass:` 或明文写在命令行/日志里。

**密码文件要写两行。** `--ks-pass file:` 和 `--key-pass file:` 指向同一个文件时，apksigner 会
按顺序往下读：第一行给 keystore，第二行给 key。只写一行会报
`Failed to read Key "imagefolder" password ... end of file reached`。
本项目 keystore 密码与 key 密码相同，两行写同一个值即可，用完把临时文件删掉。

## 5. 校验文件

```bash
cd dist
for ABI in arm64-v8a universal; do
  sha256sum "imagefolder-open-$VERSION-$ABI.apk" \
    > "imagefolder-open-$VERSION-$ABI.apk.sha256"
done
```

`.sha256` 文件内只写文件名，不要包含本机绝对路径。

## 6. 创建 GitHub Release

可以网页手动创建，或使用 GitHub REST API：

1. `POST /repos/{owner}/{repo}/releases`，填写 `tag_name`、`target_commitish`、`name`、`body`
2. `POST https://uploads.github.com/repos/{owner}/{repo}/releases/{id}/assets?name=...` 上传 **4 个资产**：
   `...-arm64-v8a.apk`、`...-universal.apk` 及各自的 `.sha256`

建议发布后通过 API 核对 asset 的 `digest` 与本地 SHA-256 一致。GitHub 下载 CDN 在部分网络下可能被重置，此时 API digest 是可靠的完整性依据。

## 7. 发布检查

- [ ] CI 通过
- [ ] **两个** APK（`arm64-v8a` + `universal`）都已签名，证书与上一版一致（可直接覆盖升级）
- [ ] 4 个资产的 digest 与本地 SHA-256 一致
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