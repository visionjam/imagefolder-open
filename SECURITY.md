# Security Policy

## Supported versions

Security fixes target the latest release and the current `main` branch. Older
releases may not receive fixes.

## Reporting a vulnerability

Please use GitHub's private vulnerability reporting (Security → Advisories) on
this repository. Do not open a public issue for a security-sensitive report.
Include the affected version, reproduction steps, and impact. There is no bug
bounty; credit can be added to the release notes if you want it.

## Threat model

### What encryption protects

- The contents of an encrypted bundle.
- The names of files inside the encrypted bundle.

New bundles use AES-256-GCM with PBKDF2-HMAC-SHA256 at 600,000 iterations.
Bundles created by older versions keep working because the iteration count is
stored in the bundle header and the reader uses that value.

### What it does not protect

- **Existence.** A carrier image visibly contains a private PNG chunk, JPEG
  APP15 segment, or WebP `IFBW` chunk. Anyone inspecting the container can tell
  that it carries hidden data.
- **Outer metadata.** The JSON header exposes a generic name (for example
  `图片文件夹.zip`), MIME type, encrypted/compressed flags, and the entry count.
- **The cover image.** The picture itself, and whatever it shows, is not
  protected.
- **Recipients with the app and password.** Anyone who has both can extract the
  payload.

### Storage access

By default the app uses only the system Storage Access Framework to read the
folders and files you pick. It does not request broad storage permissions.

On Android 11+ you can optionally grant **All files access**
(`MANAGE_EXTERNAL_STORAGE`) in system settings to browse ordinary folders that
the system picker cannot open, such as the internal storage root or Download.
This permission is used only for local browsing and scanning; the app still has
no network permission and uploads nothing. It does not grant access to
`Android/data` or `Android/obb`.

### Transit and platform behavior

Chat and social platforms may recompress images, convert them to another
format, or strip unknown chunks/segments. Hidden data can be lost in transit.
Sending the image as a file reduces, but does not eliminate, this risk. The app
cannot verify that a platform has preserved the payload.

### Password handling

Passwords are never stored. Losing the password means the payload cannot be
recovered. There is no backdoor or recovery key.

## Out of scope

- Attacks that require an already compromised device or operating system.
- Data loss caused by platform recompression or a forgotten password.
- The safety of payload files after they are opened in another app.

## 中文摘要

- 加密保护载荷内容与内层文件名；不隐藏隐藏数据的存在、条目数量和载体图片本身。
- 聊天平台可能重新压缩图片导致隐藏数据丢失；请尽量以「文件」形式发送。
- 密码不会保存，丢失后无法恢复。
- 「全部文件访问」是可选的系统权限，仅用于本地浏览；不改变无网络权限的立场，且仍无法读取 `Android/data` / `Android/obb`。
- 漏洞报告请走 GitHub Security Advisories，不要开公开 issue。
