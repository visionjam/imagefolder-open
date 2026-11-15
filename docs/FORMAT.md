# 数据格式

所有隐藏内容都放在 PNG 的私有 ancillary chunk `tpWj` 中，插入位置为第一个 `IDAT` 之前。chunk 长度与 CRC32 遵循 PNG 规范（大端序）。chunk payload 的结构为：

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

## 旧格式（与原版兼容）

当不压缩、不加密、不添加文件夹时，每个文件单独一个 `tpWj` chunk：

```
{"name":"a.txt","mime":"text/plain"} 0x00 <a.txt 原始字节>
```

原版「图片文件夹」v1.1 可以读取这种图片。

## v0.2 封装（IFB2）

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

- KDF：PBKDF2-HMAC-SHA256，150,000 次迭代，256 位密钥
- 加密：AES-256-GCM，12 字节随机 nonce，128 位认证 tag
- AAD：`IFB2` 头部全部字节（magic 到 nonce），防止头部被篡改
- 每次打包使用新的随机 salt 与 nonce

密码错误或数据被篡改时 GCM 认证失败，抛出 `InvalidPasswordException`。

## 兼容性

| 生成方 | 内容 | 本应用 | 原版 v1.1 |
| --- | --- | --- | --- |
| 原版 / 本应用基础模式 | 逐个文件 chunk | ✅ | ✅ |
| 本应用高级模式 | IFB2（ZIP，可加密） | ✅ | ❌（会当作一个 `.zip` 文件导出） |
