# 数据格式

隐藏内容可以写入三种图片容器。三者共用同一份载荷布局：

```
<JSON 头> 0x00 <数据>
```

JSON 头字段：

| 字段 | 说明 |
| --- | --- |
| `name` | 文件名（必填） |
| `mime` | MIME 类型（可选） |
| `format` | `ifv2` 表示 v0.2 封装；旧格式无此字段 |
| `enc` | 是否加密（布尔） |
| `comp` | 是否压缩（布尔） |
| `entries` | 条目数量（整数，含目录） |

## PNG 载体

PNG 使用私有 ancillary chunk `tpWj`，插入位置为第一个 `IDAT` 之前。chunk 长度与 CRC32 遵循 PNG 规范（大端序）。

### 旧格式（与原版兼容）

当不压缩、不加密、不添加文件夹时，每个文件单独一个 `tpWj` chunk：

```
{"name":"a.txt","mime":"text/plain"} 0x00 <a.txt 原始字节>
```

原版「图片文件夹」v1.1 可以读取这种图片。

### v0.2 封装（IFB2）

启用压缩 / 加密 / 文件夹时，整个包写入一个 chunk。数据部分为：

```
"IFB2"          4 字节 magic
version         1 字节 (1)
flags           1 字节 (bit0=加密, bit1=压缩)
iterations      4 字节 (PBKDF2 迭代次数，未加密时为 0)
saltLength      1 字节 (加密时 16，否则 0)
nonceLength     1 字节 (加密时 12，否则 0)
salt            saltLength 字节
nonce           nonceLength 字节
payload         ZIP 数据；加密时为 AES-GCM 密文 + 16 字节 tag
```

### ZIP 部分

- UTF-8 文件名
- 目录条目以 `/` 结尾，用于保留空目录
- `comp = true` 时使用 Deflate，否则使用 Stored
- 提取时校验路径：拒绝绝对路径、`..`、反斜杠、空段
- 限制：最多 100,000 个条目，解压后总量最多 512 MB

### 加密部分

- KDF：PBKDF2-HMAC-SHA256，新包使用 600,000 次迭代，256 位密钥；读取时始终使用头部记录的 `iterations`，因此 150,000 次的旧包仍可解开
- 加密：AES-256-GCM，12 字节随机 nonce，128 位认证 tag
- AAD：`IFB2` 头部全部字节（magic 到 nonce），防止头部被篡改
- 每次打包使用新的随机 salt 与 nonce

密码错误或数据被篡改时 GCM 认证失败，抛出 `InvalidPasswordException`。

## JPEG 载体

JPEG 使用 APP15（`0xFFEF`）段承载载荷，插入在第一个 SOS 扫描之前。APP 段会被所有 JPEG 解码器跳过，像素数据不受影响。

每个 APP15 段的 payload 为：

```
"IFBJ"      4 字节 magic
version     1 字节 (1)
part index  2 字节（大端序，从 0 开始）
part count  2 字节（大端序）
data        分段数据
```

- 拼接所有分段的 `data` 后，得到 `JSON 头 + 0x00 + 数据`
- 单段 payload 最大 65533 字节，扣除 9 字节段头后每段最多携带 65524 字节
- 超过单段容量时自动拆分；最多 65535 段
- 提取时扫描 SOS 之前的 APP15 段，校验 magic、版本、序号与总段数后重组
- 普通看图软件会忽略 APP15 段，图片正常显示；部分编辑器重新保存时会丢弃 APP 段

## WebP 载体

WebP 使用私有 RIFF chunk `IFBW`，追加在图像数据 chunk 之后，并同步更新 RIFF 文件长度。WebP 规范要求解码器忽略未知 chunk。

chunk payload 直接为：

```
<JSON 头> 0x00 <数据>
```

- chunk 长度为 32 位小端序，按 RIFF 规范补齐到偶数长度
- 读取时遍历 RIFF chunk，找到 `IFBW` 后解析载荷
- Android、浏览器与桌面看图软件会忽略未知 chunk，图片正常显示

## 载体选择与编码

- 输出格式跟随表图：PNG、JPEG、WebP 原样保留
- HEIC、AVIF 等其他格式会解码后转为 PNG
- JPEG / WebP 输出按质量 95 重新编码，原图 EXIF 等元数据不会保留；PNG 输出无损
- 未选择表图时生成 PNG 示意图

## 兼容性

| 载体 / 生成方 | 内容 | 本应用 | 原版 v1.1 |
| --- | --- | --- | --- |
| PNG / 原版或本应用基础模式 | 逐个文件 chunk | ✅ | ✅ |
| PNG / 本应用高级模式 | IFB2（ZIP，可加密） | ✅ | ❌（会当作一个 `.zip` 文件导出） |
| JPEG / 本应用 | APP15 分段 + IFB2 | ✅ | ❌ 不识别 |
| WebP / 本应用 | IFBW chunk + IFB2 | ✅ | ❌ 不识别 |
