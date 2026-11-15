# 图片文件夹

[English](README.md) | [简体中文](README.zh-CN.md)

把任意文件藏进 PNG / JPEG / WebP 图片的 Android 工具。界面使用 **Jetpack Compose + Material Design 3** 实现，兼容原版「图片文件夹」的数据格式。

> 本项目是针对已停止维护的原应用「图片文件夹」的独立开源重实现。代码与素材全部重写，不包含原应用的任何代码或资源。
>
> 当前版本：**v0.5.1** · [更新日志](CHANGELOG.md)

<p align="center">
  <img src="docs/screenshots/screen-pack.png" width="32%" />
  <img src="docs/screenshots/screen-extract.png" width="32%" />
  <img src="docs/screenshots/screen-about.png" width="32%" />
</p>

## 功能

- **打包**：选择可选的表图（载体图片）、添加任意多个文件、可写一段备注
  - 不选表图时自动生成绿色苹果示意图
  - 备注会绘制在图片右上角
  - 输出格式跟随表图：PNG / JPEG / WebP 原样保留，HEIC/AVIF 等转为 PNG
  - 输出文件名沿用原版风格：`Tank_20261115_123456.png` / `.jpg` / `.webp`
- **文件夹**：通过系统目录选择器添加整个文件夹，保留目录结构与空目录
- **压缩**：ZIP Deflate 压缩，减小载荷体积
- **加密**：AES-256-GCM + PBKDF2-HMAC-SHA256（150,000 次），提取时需要密码
- **提取**：多选 PNG / JPEG / WebP 图片，自动识别其中的隐藏内容
  - 图片可直接预览
  - 单个保存或全部保存到相册
  - 非图片文件保存后可调用系统应用打开
  - 按目录结构「导出到文件夹」
- **解压**：提取结果中的压缩包可直接解压
  - ZIP（ZipCrypto / AES 密码）、7z（密码）
  - TAR、GZ、BZ2、XZ（含 `.tar.gz` / `.tar.bz2` / `.tar.xz`）
  - 分卷 ZIP（`.z01`… + `.zip` 或 `.zip.001`…）与分卷 7z（`.7z.001`…）
  - 保留目录结构；嵌套压缩包可继续解压；缺少分卷时列出缺失文件
  - RAR 暂不支持
- **多语言与主题**：语言可跟随系统或手动选择简体中文 / English；主题同样支持跟随系统 / 浅色 / 深色
- **兼容模式**：仅从相册单选一张图片，适配选择器异常的设备
- **Material 3**：动态取色、深色模式、Edge-to-Edge

### 格式兼容性

| 载体 / 打包方式 | 本应用 | 原版「图片文件夹」v1.1 |
| --- | --- | --- |
| PNG，不压缩、不加密、无文件夹 | ✅ | ✅ 可提取 |
| PNG，压缩 / 加密 / 含文件夹 | ✅ | ❌ 只能看到一个 `.zip` 数据块 |
| JPEG（任意模式） | ✅ | ❌ 不识别 |
| WebP（任意模式） | ✅ | ❌ 不识别 |

PNG 未启用高级特性时仍写入原版的逐个 `tpWj` chunk，因此基础用法完全互通；高级特性使用 v0.2 的 `IFB2` 封装。JPEG / WebP 载体使用各自容器中的私有段/块，只有本应用能读取。

## 数据格式

三种载体共用同一份 `JSON 头 + 0x00 + 原始字节` 载荷：

- **PNG**：写入私有 ancillary chunk（4 字符名称 `tpWj`），位置在第一个 `IDAT` 之前
- **JPEG**：写入 APP15（`0xFFEF`）段，插入在第一个 SOS 之前；超过 64KB 时自动拆分为多个段
- **WebP**：写入私有 RIFF chunk `IFBW`，追加在图像数据之后

```
PNG 签名
IHDR
tpWj  <- 文件 1：{"name":"文件名","mime":"类型"} 0x00 <原始字节>
tpWj  <- 文件 2：...
IDAT
IEND
```

v0.2 的 ZIP / 加密内容使用 `IFB2` 封装写入同一个载荷，详见 [docs/FORMAT.md](docs/FORMAT.md)。压缩包解压支持见 [docs/ARCHIVES.md](docs/ARCHIVES.md)。

- PNG chunk 长度与 CRC32 按 PNG 规范使用大端序；JPEG/WebP 的分段长度分别使用大端/小端序
- 普通看图软件会忽略未知 chunk / APP15 段，图片仍可正常显示

## 下载

从 [Releases](https://github.com/visionjam/imagefolder-open/releases) 下载最新签名的 APK。

## 构建

要求：

- JDK 17 或更高
- Android SDK Platform 35、Build-Tools 35.0.0

```bash
./gradlew assembleDebug
./gradlew assembleRelease   # R8 + 资源收缩，当前产物约 3MB
```

仓库的 `settings.gradle.kts` 已优先配置阿里云 Maven 镜像，Gradle wrapper 默认使用腾讯云镜像（见 `gradle/wrapper/gradle-wrapper.properties`），国内网络可直接构建。海外网络可自行调整仓库顺序或换回 Gradle 官方下载地址。

## 测试

```bash
./gradlew testDebugUnitTest
```

单元测试覆盖：

- `tpWj` chunk 编解码往返与 PNG 结构 / CRC 校验
- 读取原版布局与 Python 参考实现生成的互操作样张
- JPEG APP15 分段写入/提取、超过 64KB 的自动拆分
- WebP RIFF chunk 写入/提取、奇数长度 chunk 与填充
- ZIP 目录结构、压缩与空目录保留
- AES-256-GCM 加解密、错误密码、随机盐/nonce
- 路径穿越与恶意 ZIP 防护
- 损坏数据与非法输入

当前共 **45 个 JVM 单元测试 + 6 个真机用例**。

真机端到端测试（需要连接设备）：

```bash
./gradlew connectedDebugAndroidTest
```

发版流程见 [docs/RELEASING.md](docs/RELEASING.md)。

## 项目结构

```
app/src/main/java/com/imagefolder/open/
├── core/PngChunkCodec.kt      # tpWj chunk 读写（PNG 载体）
├── core/JpegCarrierCodec.kt   # APP15 分段读写（JPEG 载体）
├── core/WebpCarrierCodec.kt   # IFBW chunk 读写（WebP 载体）
├── core/CarrierCodec.kt       # 载体检测与统一入口
├── core/HiddenPayloadCodec.kt # 共享 JSON 头 + 数据载荷
├── core/BundleCodec.kt        # ZIP + AES-256-GCM 封装
├── core/BundlePaths.kt        # 安全、唯一的归档路径
├── core/ArchiveExtractor.kt   # ZIP/7z/TAR/GZ/BZ2/XZ 解压
├── core/ImageProcessor.kt     # 封面解码、备注绘制、PNG/JPEG/WebP 编码
├── data/FolderReader.kt       # SAF 目录枚举、结构保留
├── data/FolderExporter.kt     # 按目录结构导出
├── data/GallerySaver.kt       # MediaStore 保存
├── data/UriUtils.kt           # 内容 URI 读取
├── MainViewModel.kt           # 状态与业务编排
└── ui/                        # Compose Material 3 界面
```

## 已知限制

- 需要 Android 10（API 29）及以上
- 打包时会把载荷文件完整读入内存，超大文件（数百 MB）可能导致内存不足
- JPEG / WebP 载体按质量 95 重新编码（会丢失原图 EXIF 等元数据）；PNG 载体无损
- JPEG / WebP 载体只有本应用能提取，原版「图片文件夹」v1.1 只识别 PNG
- 加密密码不会保存，丢失后无法恢复
- RAR、RAR 分卷、ISO / CAB / DEB / RPM、ZSTD 暂不支持
- 分卷必须全部出现在当前提取结果中（同一批图片/加密包），不会自动在设备上搜索缺失分卷
- 图片型载荷保存到 `Pictures/图片文件夹`，其他类型保存到 `Download/图片文件夹`；完整目录结构请用「导出到文件夹」
- 从 Release 版切换到 debug 版（或反之）需要先卸载，因为签名不同

## 免责声明

本应用仅供技术研究与个人备份使用。请勿用于传播违法内容或侵犯他人权益，使用者的行为及后果由使用者自行承担。

## 致谢

- 解压格式与库选型参考了 [ZipXtract](https://github.com/WirelessAlien/ZipXtract)（GPL-3.0）
- 使用 Apache Commons Compress、XZ for Java（公共领域）、Zip4j 等开源库

## License

Copyright (C) 2026 Vision Jam

本项目采用 **GNU General Public License v3.0**，详见 [LICENSE](LICENSE)。
