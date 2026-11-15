# 压缩包解压支持

从图片中提取出的文件如果是压缩包，可以直接在应用内解压，解压结果会追加到提取列表中，并保留目录结构。

## 支持的格式

| 格式 | 扩展名 | 密码 | 说明 |
| --- | --- | --- | --- |
| ZIP | `.zip` `.jar` `.apk` | ✅ ZipCrypto / AES | 使用 Zip4j；支持 ZIP64 |
| 分卷 ZIP | `.z01`…`.zNN` + `.zip`，或 `.zip.001`… | ✅ | 需要同组分卷都在提取结果中 |
| 7z | `.7z` | ✅ AES-256 | 使用 Apache Commons Compress |
| 分卷 7z | `.7z.001`… | ✅ | 按序拼接后解压 |
| TAR | `.tar` | — | |
| GZIP | `.gz` `.tgz` | — | `.tar.gz` / `.tgz` 自动识别内层 TAR；否则作为单文件解压 |
| BZIP2 | `.bz2` `.tbz` `.tbz2` | — | `.tar.bz2` 自动识别内层 TAR |
| XZ | `.xz` `.txz` | — | `.tar.xz` 自动识别内层 TAR |
| RAR | `.rar` | — | ✅ 无密码 RAR5（含固实）与非固实 RAR4；使用 libarchive |
| 分卷 RAR | `.rar` + `.r00`…，或 `.part1.rar`… | — | ✅ 需要同组分卷都在提取结果中 |

暂不支持：加密 RAR（密码或加密头）、固实 RAR4、ISO/CAB/DEB/RPM、ZSTD 等。libarchive 的 RAR 实现不提供解密；加密 RAR 会给出明确提示。

## 行为

- 点击提取结果行中的「解压」即可。
- **分卷**：点击任一分卷，应用会从当前提取结果中收集同组文件。缺少分卷时会列出缺失文件名，不会尝试解压。
- 分卷 ZIP 同时支持 `.z01` 风格和 `.zip.001` 风格；分卷 7z 要求 `.001`、`.002`……连续。
- 分卷 RAR 同时支持旧式 `.rar` + `.r00`、`.r01`… 和新式 `.part1.rar`、`.part2.rar`…（含 `part01` 补零写法）。
- 加密 ZIP / 7z 会弹出密码输入框；密码错误会重新提示。
- 解压结果以压缩包文件名作为目录前缀（例如 `photos.zip` → `photos/...`），避免与已有结果冲突。
- 解压出的文件如果本身还是压缩包，可以继续解压（嵌套）。
- 解压结果可以和普通提取结果一样预览、保存到相册或「导出到文件夹」。

## 安全限制

- 拒绝绝对路径、`..`、反斜杠、空路径段、NUL 和控制字符
- 最多 100,000 个条目
- 解压后总量最多 512 MB
- ZIP 使用临时文件解析，结束后立即删除

## 实现与许可证

| 库 | 用途 | 许可证 |
| --- | --- | --- |
| Apache Commons Compress | 7z / TAR / GZ / BZ2 | Apache-2.0 |
| Zip4j | ZIP（含密码） | Apache-2.0 |
| XZ for Java | XZ | Public Domain |
| libarchive | RAR4 / RAR5 | BSD-2-Clause |
| zhanghai libarchive-android | libarchive 的 Android JNI 绑定 | Apache-2.0 |

RAR 后端使用 `app/libs/libarchive-android-1.1.6-libarchive-3.8.8.aar`：Java 绑定取自
`me.zhanghai.android.libarchive:library:1.1.6`（兼容 compileSdk 35），native 库取自
`1.1.7`（libarchive 3.8.8，含 2026 年的 RAR5 修复）。两个版本的 Java API 与 JNI 方法一致。

格式与库选型参考了 [ZipXtract](https://github.com/WirelessAlien/ZipXtract)（GPL-3.0）；本项目未直接复制其代码。
