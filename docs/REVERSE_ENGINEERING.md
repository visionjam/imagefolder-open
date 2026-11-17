# 逆向分析记录

对象：原版「图片文件夹」v1.1（包名 `com.imagefolder`，versionCode 110，2021-10-24 最后更新）。

本记录只描述程序行为与数据格式，用于实现互操作，不包含原应用代码。

> 本文描述原版 v1.1。开源版 v0.2 新增的 ZIP 压缩、AES-256-GCM 加密与 `IFB2` 封装见 [FORMAT.md](FORMAT.md)。
>
> 原版功能清单、与当前开源版的逐项对照，以及仍然缺失的体验项见 [ORIGINAL_FEATURE_GAP.md](ORIGINAL_FEATURE_GAP.md)。

## 技术栈

- Xamarin.Forms（.NET / Mono），APK 内业务逻辑位于 `assemblies/ImageFolder.dll`
- 该 DLL 在 APK 中以 Xamarin 的 `XALZ`（LZ4 block）格式压缩
- 图像处理使用 SkiaSharp；UI 使用 Xamarin.Forms
- 主程序集反编译后可读性很高，核心逻辑只有十余个类

## 核心格式：PNG 自定义 chunk

原版不是像素级隐写，而是利用 PNG 的自定义数据块：

| 项目 | 值 |
| --- | --- |
| chunk 名称 | `tpWj`（硬编码） |
| chunk 属性 | ancillary（首字母小写）、private（第二字母小写）、safe-to-copy |
| 数据布局 | `JSON 头 + 0x00 + 文件原始字节` |
| JSON 字段 | `name`（文件名）、`mime`（MIME 类型） |
| 写入位置 | 第一个 `IDAT` 之前，每个文件一个 chunk |
| 长度 / CRC | PNG 规范，大端序，CRC32 覆盖 type + data |

解码端不校验 CRC，仅按 chunk 长度遍历并匹配 `tpWj`。

## 打包流程（原版）

1. 用户选择表图（可选）与多个里图（要隐藏的文件）
2. 表图用 SkiaSharp 解码；若未提供，则绘制一个 300×300 的萝卜图标
3. 备注文字用内置 SimHei 字体绘制在右上角：
   - 字号 = 图片对角线 × 0.07541333
   - 黑色填充，描边宽度 8
4. 将图像重新编码为 PNG
5. 为每个文件构造 `tpWj` chunk 并插入
6. 输出文件名 `Tank_yyyyMMdd_HHmmss.png`

## 提取流程（原版）

1. 用户多选图片
2. 逐张按 PNG 结构遍历 chunk
3. 收集所有 `tpWj` chunk
4. 以第一个 `0x00` 分隔 JSON 头与文件体
5. 以 JSON 中的 `name` 作为还原文件名

## 保存行为

- 保存目录：`Pictures/图片文件夹/`
- 保存后调用 `MediaScannerConnection.scanFile` 使其出现在相册
- 非图片文件同样先保存，再用系统应用打开

## 兼容性说明

开源版保持：

- 相同的 chunk 名称 `tpWj`
- 相同的 `JSON + 0x00 + 数据` 布局
- 相同的 chunk 插入位置与 CRC 规则

因此**基础模式**（不压缩、不加密、无文件夹）下两个应用生成的图片可以互相打开。开源版在以下方面有改进：

- 表图解码时应用 EXIF 方向
- 使用系统字体绘制备注（不再打包 SimHei）
- 保存走 MediaStore，无需存储权限

## 开源版 v0.2 扩展

开启压缩、加密或文件夹时会改用 `IFB2` 封装：一个 `tpWj` chunk 内包含 ZIP（目录结构 / Deflate）以及可选的 AES-256-GCM 密文。原版会把整段封装当作一个 `.zip` 文件导出，因此高级特性生成的图片需要开源版提取。完整布局见 [FORMAT.md](FORMAT.md)。
