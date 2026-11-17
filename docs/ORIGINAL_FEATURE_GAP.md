# 原版「图片文件夹」v1.1 功能清单与差异

- 分析对象：`ADB/imagefolder-open/workspace/apk/imagefolder-1.1.apk`
- 分析日期：2026-11-17
- 对照代码：当前工作区 `feat/v1.2.0-extract-share` 分支（versionName 1.2.0）
- 结论先行：**原版是一个功能范围很窄的 Xamarin.Forms 应用；开源版已经覆盖它的全部核心功能，并在格式、压缩、加密、分卷、归档、扫描、分享、多语言等方面明显超出。原版真正“我们还没有”的只有少量体验/兼容性细节，没有新的核心能力。**

## 1. 样本与验证

| 项目 | 值 |
| --- | --- |
| APK 路径 | `workspace/apk/imagefolder-1.1.apk` |
| 大小 | 38,790,848 B |
| SHA-256 | `6059E80F33F5BCA5BF164B6C782221E3863DF01BCA895B956EFE0D794B86484D` |
| 包名 | `com.imagefolder` |
| versionCode / versionName | 110 / 1.1 |
| minSdk / targetSdk | 21 / 30 |
| 签名证书 SHA-256 | `787a77c0ca83fb820dbf268b15642bcdfbf5e68f82b2d3b49924af5b8f804b15` |
| 技术栈 | Xamarin.Forms（.NET / Mono）+ SkiaSharp；业务逻辑在 `assemblies/ImageFolder.dll` |
| 内置版本说明 | `Version 1.1 / 2021-10-24 / 修了提取少8字节的bug`；上一版 `Version 1.0 / 2021-10-17 / 基本实现图片文件夹所有功能` |

验证过程：

1. 从 APK 中取出 `assemblies/ImageFolder.dll`，SHA-256 为
   `32869E3F5E9E900E49BF31438854E6E2F9495D6EE617FF7E33EECDE6AEAD85F1`，
   与 `workspace/analysis/assemblies/ImageFolder.dll` 完全一致。
2. `workspace/analysis/assemblies-dec/ImageFolder.dll` 是该 DLL 的 XALZ 解压副本；
   `workspace/analysis/decompiled` 是 ILSpy 反编译结果。本文引用的类/方法均来自这份副本。
3. 从解压后的 DLL 提取全部中文字符串，只有打包/提取/关于页面的界面文案
   （“选择表图”“选择里图”“备注”“全部保存”“查看大图”“兼容模式”等），
   没有发现未在反编译代码中出现的隐藏页面或功能入口。
4. Manifest 中只有 `MainActivity`、Xamarin 的 `IntermediateActivity`、
   Xamarin FileProvider 和 Mono runtime provider；没有 `ACTION_SEND` / `ACTION_VIEW`
   等分享/打开入口，也没有 Service 或自定义 Receiver。
5. APK 中没有 `assets/` 业务资源；`res/` 全部是 Xamarin/AndroidX 的模板资源。

## 2. 原版结构

原版只有两个 Tab：

- **打包 & 提取**（`ImageFolderPage`）
  - 打包：选表图（可选）→ 选里图（可多选，任意文件）→ 填写备注 → 生成
  - 提取：选图片（可多选）→ 解析 `tpWj` → 结果列表 → 全部保存
- **关于**（`AboutPage`）
  - 免责声明
  - “兼容模式(无法多选)”开关

启动时如果 `Setting.json` 里 `IsOpenFirstTime=true`，先弹一次“用户协议”对话框；
`OnSleep` 时把 `IsOpenFirstTime` 写回 false。

## 3. 原版功能清单（逐项）

### 3.1 启动与设置

- 首次启动弹“用户协议”，内容为免责声明，只有一个“接受”按钮
  （`MainPage.ShowAgreement`）。
- 设置文件 `Setting.json` 只有两个字段：`IsOpenFirstTime`、`CompatibleMode`
  （`MySetting` / `App`）。
- 兼容模式开关在“关于”页，离开页面时写回静态字段，下次启动从设置恢复。

### 3.2 打包

- 表图可选；不选时用代码画一个 300×300 的“树/气球”占位图
  （`IconGenerator.RectangleIcon`，圆球颜色每次随机 HSV 色相）。
- 里图可多选，**不限制文件类型**；选择结果会反转一次
  （`ButtonEins_Clicked` 里的 `picFileResEins.Reverse()`）。
- 备注是单行输入框，文字用内置 `simhei.ttf` 画在图片右上角，
  字号 = 图片对角线 × 0.0754133，黑色填充（`ImageFolderCodec.Encode`）。
- 表图用 SkiaSharp 解码后**统一重新编码为 PNG**；输出名
  `Tank_yyyyMMdd_HHmmss.png`。
- 每个里图写一个 `tpWj` chunk，数据布局是
  `JSON {name,mime} + 0x00 + 原始字节`，插入在第一个 `IDAT` 之前。
- 没有压缩、加密、分卷、文件夹、自定义文件名、输出格式选择。

### 3.3 提取

- 可多选图片（`FilePicker.PickMultipleAsync(PickOptions.Images)`）。
- 逐张遍历 PNG chunk，收集所有 `tpWj`，按第一个 `0x00` 拆分 JSON 头和文件体，
  以 JSON 的 `name` 作为还原文件名（`ImageFolderCodec.Decode`）。
- 多张图片的结果合并成一个列表；如果一张都没有隐藏内容，弹
  “这些图中没有图片文件夹！”。
- 结果页 `ImageListPage`：
  - 图片类型（png/jpg/jpeg/bmp/gif）显示 **125×125 缩略图** + 文件名；
  - 其他类型显示内置文件图标 + 文件名；
  - 点 png/jpg/jpeg/bmp → 进入 `ImagePage`“查看大图”（ScrollView + AspectFit，不可缩放）；
    gif 只显示缩略图，点击会走“先保存再打开”的分支；
  - 点非图片（含 gif）→ 如果还没保存，提示“这个文件需要先保存才能打开！”；
    已保存则用系统 `Launcher.OpenAsync` 打开。
- “全部保存”把列表里所有文件写到 `Pictures/图片文件夹/`，
  逐个调用 `MediaScannerConnection.scanFile`，然后用一个对话框列出
  “已保存 N 个文件至：Pictures/图片文件夹 / 可在 图片文件夹 相册中找到 / 文件名如下：…”。

### 3.4 兼容模式

- 开启后：
  - 里图选择从“任意文件多选”变成 **相册单选一张图片**（`MediaPicker.PickPhotoAsync`）；
  - 提取图片选择也从“多选”变成 **相册单选一张图片**。
- 目的：适配不支持多选/文件选择器异常的老设备。

### 3.5 关于页

- 免责声明标题和两段文字。
- 兼容模式开关。
- 没有版本号、项目地址、许可证、主题、语言、缓存管理等。

### 3.6 平台与 Manifest

- 竖屏锁定（`screenOrientation=portrait`）。
- 权限：`INTERNET`、`ACCESS_NETWORK_STATE`、`READ_EXTERNAL_STORAGE`、
  `WRITE_EXTERNAL_STORAGE`、`MANAGE_EXTERNAL_STORAGE`；
  `requestLegacyExternalStorage=true`、`allowBackup=true`。
- 代码里没有任何网络请求；`INTERNET` 应该是 Xamarin 模板默认带上的。
- 没有分享入口/分享目标，也没有 `ACTION_VIEW` 文件关联。

### 3.7 未接线代码

- `IPlatformService.GetLatestPictures(long timestamp)` 已实现（按 `datetaken` 倒序查
  MediaStore 最新图片），但没有任何 UI 调用，属于未接线代码，不算功能。

## 4. 功能对照表

图例：✅ 有；⚠️ 有但不完全等价；❌ 没有；➖ 不适用/刻意不同。

| 能力 | 原版 v1.1 | 当前开源版 | 说明 |
| --- | --- | --- | --- |
| 首次启动用户协议 | ✅ | ❌ | 原版弹一次免责声明；开源版只在“关于”页展示 |
| 表图可选 + 默认占位图 | ✅ | ✅ | 开源版是固定绿色苹果，原版是随机色相树/气球 |
| 里图任意类型多选 | ✅ | ✅ | 开源版用 SAF `OpenMultipleDocuments(*/*)` |
| 文件夹作为载荷 | ❌ | ✅ | 开源版额外支持 |
| 备注绘制到右上角 | ✅ | ✅ | 原版内置 SimHei；开源版用系统粗体 |
| 备注字号/超宽处理 | 对角线 7.54%，可能溢出 | 短边比例 + 缩放 + 省略号 | 开源版更稳 |
| 输出 PNG | ✅ | ✅ |  |
| 输出 JPEG / WebP | ❌ | ✅ | 开源版额外支持 |
| 自定义输出文件名 / 重命名 | ❌ | ✅ | 开源版额外支持 |
| 压缩（ZIP Deflate） | ❌ | ✅ | 开源版额外支持 |
| 加密（AES-256-GCM） | ❌ | ✅ | 开源版额外支持 |
| 分卷图种（1–9 张） | ❌ | ✅ | 开源版额外支持 |
| 提取多选图片 | ✅ | ✅ |  |
| 兼容模式下提取单选 | ✅ | ❌ | **当前缺口**：开源版兼容模式只影响打包页 |
| 多张图片结果合并 | ✅ | ✅ |  |
| 无隐藏内容提示 | ✅ | ✅ |  |
| 结果列表缩略图 | ✅ 125×125 | ❌ 仅类型图标 | **当前缺口** |
| 独立“查看大图”页 | ✅ | ⚠️ 预览对话框，最大 420dp | **当前缺口**（无全屏页/缩放） |
| 图片预览 | ✅ png/jpg/jpeg/bmp | ✅ 更多格式 | 开源版更全 |
| 文本预览 | ❌ | ✅ | 开源版额外支持 |
| 结果按文件夹逐层浏览 | ❌ | ✅ | 开源版额外支持 |
| 非图片“用其他应用打开” | ⚠️ 必须先保存 | ✅ 未保存也可用缓存打开 | 开源版更好 |
| 单个保存 | ⚠️ 只有全部保存 | ✅ | 开源版额外支持 |
| 全部保存到相册 | ✅ | ✅ |  |
| 保存结果对话框列出文件名/路径 | ✅ | ⚠️ Snackbar 汇总 | 细节差异 |
| 保存目录跟随语言 | ❌ 固定“图片文件夹” | ✅ 中文/英文 | 开源版更好 |
| 导出到文件夹（保留目录结构） | ❌ | ✅ | 开源版额外支持 |
| 分享出去 | ❌ | ✅ | 开源版额外支持 |
| 分享进来 | ❌ | ✅ | 开源版额外支持 |
| 文件夹扫描含隐藏内容的图片 | ❌ | ✅ | 开源版额外支持 |
| 全部文件访问（可选） | ❌ | ✅ | 开源版额外支持 |
| 解压 ZIP/7z/TAR/GZ/BZ2/XZ/RAR | ❌ | ✅ | 开源版额外支持 |
| 加密压缩包 / 分卷压缩包 | ❌ | ✅（RAR 加密除外） | 开源版额外支持 |
| 主题（跟随系统/浅色/深色） | ❌ | ✅ | 开源版额外支持 |
| 语言（跟随系统/中文/English） | ❌ | ✅ | 开源版额外支持 |
| 缓存清理 / 占用显示 | ❌ | ✅ | 开源版额外支持 |
| 防截屏（可选） | ❌ | ✅ | 开源版额外支持 |
| 横竖屏 | 竖屏锁定 | 跟随系统 | 开源版更灵活 |
| Android 5.0–9 | ✅ minSdk 21 | ❌ minSdk 29 | **兼容性缺口** |
| 无网络权限 | ❌ 申请了 INTERNET | ✅ 不申请 | 开源版隐私更好 |
| 保存走 MediaStore | ❌ 直接路径 + MediaScanner | ✅ | 开源版更符合现代 Android |
| 载荷上限 | 无显式上限（受内存限制） | 64 MB | 开源版有产品约束 |

## 5. 我们还没有的功能（按优先级）

> **状态（2026-11-17，v1.3.0）**：P1（兼容模式覆盖提取）、P2（首次启动用户协议）已完成；
> P3 只做了结果缩略图，全屏大图按用户要求不做。P4 / P5 / P6 维持原判断。

### P1. 兼容模式没有覆盖“提取”

原版在兼容模式下，**提取页也会从多选变成相册单选一张**：

- `ImageFolderPage.ButtonDtank_Clicked`：`if (compatibleMode) MediaPicker.PickPhotoAsync()`
  否则 `FilePicker.PickMultipleAsync(PickOptions.Images)`。

当前开源版：

- `PackScreen` 接收 `compatMode`，里图选择会切换成单张图片；
- `ExtractScreen` 没有 `compatMode` 参数，始终使用
  `ActivityResultContracts.PickMultipleVisualMedia(100)`。

影响：在“多选选择器异常”的老设备上，用户能打包，但提取仍然走多选，兼容模式没有真正兜底。

建议：给 `ExtractScreen` 增加 `compatMode` 参数；为 true 时改用
`PickVisualMedia` 单选并直接 `setExtractSources(listOf(uri))`。
如果希望更贴近原版，表图选择在兼容模式下也可以改成单选（原版表图本来就是取第一张）。

### P2. 首次启动“用户协议”

原版第一次启动会弹：

> 用户协议
> ————免责声明————
> 本应用仅供技术研究使用，使用者做出的行为及后果作者概不负责！
> 当您选择接受则默认您已经准备好为自己的不当行为承担责任！
> [接受]

当前开源版没有这个首次弹窗，只在“关于”页有免责声明。

建议：用 SharedPreferences/DataStore 存一个 `agreement_accepted` 标志；
首帧后弹 `AlertDialog`，只有一个“接受”按钮；接受后写入标志。
如果不想打断用户，也可以做成“首次进入打包页时显示”，但原版是启动即弹。

### P3. 结果缩略图 + 全屏大图

原版结果列表对图片显示 125×125 缩略图，点击进入独立的“查看大图”页；
当前开源版结果行只有 `PhotoLibrary` / `InsertDriveFile` 图标，
图片预览是最大 420dp 的对话框。

影响：提取出一组图片时，用户无法快速辨认内容，需要逐张点“预览”。

建议：

1. 在 `ExtractedRow` 里对图片条目异步解码缩略图（复用
   `ImageProcessor.decodePreviewBytes(bytes, 256)`，注意 `LazyColumn` 复用和内存）；
2. 点图片行/缩略图打开全屏 `Dialog`（或独立页面），支持双指缩放/双击缩放；
3. 保留现有“预览”按钮作为文本预览入口。

这是三个缺口里最值得做的体验项，工作量也最大。

### P4. Android 5.0–9 支持

原版 `minSdk=21`，当前开源版 `minSdk=29`（Android 10）。

这是产品取舍，不是简单补一个开关：

- 当前代码依赖 MediaStore 的 scoped storage 行为、`ImageDecoder`（API 28+）、
  Compose 的现代 API；
- 如果要支持 API 21–28，需要为保存、图片解码、权限、SAF 做分支，
  测试矩阵也会显著变大。

建议：除非明确有老设备用户，否则保持 Android 10+，在 README 的“已知限制”里
继续写清楚即可。

### P5. 内置中文字体（备注）

原版把 `simhei.ttf` 打进 APK（约 9.7 MB），保证任何设备上备注中文都能显示。
当前开源版用 `Typeface.SANS_SERIF + BOLD`，绝大多数 Android 设备有 Noto Sans CJK，
但极少数精简系统/非中文区域设备可能缺字形，备注会变方框。

建议：优先级低。若要做，选择许可证允许的子集字体（注意 GPL-3.0 兼容性和
`THIRD_PARTY_NOTICES.md`），或者只对缺字形的设备回退。

### P6. 其他细节（不建议单独开版本）

- **全部保存的结果反馈**：原版用对话框列出保存路径和每个文件名；开源版是 Snackbar
  “已保存 N 项到 …”。如果用户经常一次保存几十个文件，对话框更有用。
- **默认占位图**：原版每次随机色相；开源版固定绿色苹果。纯视觉差异。
- **选择器差异**：原版表图/提取用 SAF 文件选择器，可以直接选任意目录里的图片；
  开源版在支持系统照片选择器的设备上用照片选择器（主要列 MediaStore/云媒体），
  非媒体库目录需要走“扫描文件夹”或“全部文件访问”。这是流程差异，不是能力缺失。
- **64 MB 上限**：原版没有显式上限，但把整个文件读进 `byte[]`，大文件同样会 OOM；
  开源版的 64 MB 是明确的产品约束，不建议为了“对齐原版”而取消。

## 6. 不建议照搬的原版行为

- **`INTERNET` / `ACCESS_NETWORK_STATE` 权限**：原版代码没有网络请求，
  这两个权限是 Xamarin 模板默认带的。开源版的“无网络权限”是隐私优势，不要加回来。
- **竖屏锁定**：开源版跟随系统更灵活。
- **非图片必须先保存才能打开**：开源版用 FileProvider 缓存直接打开更好。
- **表图一律重编码为 PNG**：开源版保留 JPEG/WebP 更好。
- **固定中文相册名**：开源版跟随语言更好。
- **全分辨率解码表图**：开源版 4096 px 上限更安全。
- **无压缩/加密/分卷**：这些是开源版的核心增量，不是原版优点。

## 7. 建议

如果下一版只做一件事：**P1（兼容模式覆盖提取）**，它是明确的行为缺口，改动小。

如果做一轮体验版：**P1 + P2 + P3**。

P4 是产品决策；P5/P6 可以延后。

格式兼容性不受这些改动影响：PNG 基础模式仍写原版 `tpWj`，高级特性继续走 `IFB2`。
