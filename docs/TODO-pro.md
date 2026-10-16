# YTDLnis Pro 待办（留给后续会话）

## 1. 年龄限制的 YouTube 视频下载失败（未解决）

### 现象
- 视频：`https://youtu.be/yd1G8nptNfE`（有年龄限制）。没有年龄限制的视频能正常下载。
- 用户已在 App 的 Cookies 页添加过 YouTube cookies；用同一账号在 App 内浏览器打开视频时，YouTube 弹过一次"是否继续"，点了以后能播放。之后再下载，仍然失败。
- yt-dlp 日志（两次相同）：

```
[youtube] yd1G8nptNfE: Downloading web embedded client config
[youtube] yd1G8nptNfE: Downloading web embedded player API JSON
[youtube] yd1G8nptNfE: This video is age-restricted and YouTube is requiring account age-verification; some formats may be missing
[youtube] yd1G8nptNfE: Downloading web creator client config
[youtube] yd1G8nptNfE: Downloading web creator player API JSON
[youtube] yd1G8nptNfE: Downloading tv downgraded player API JSON
WARNING: [youtube] yd1G8nptNfE: Some web_creator client https formats have been skipped as they are missing a URL. YouTube may have enabled the SABR-only streaming experiment for your account. See https://github.com/yt-dlp/yt-dlp/issues/12482
ERROR: [youtube] yd1G8nptNfE: Sorry, this content is age-restricted
```

- 命令里带了 `--cookies /data/user/0/top.lifeee.ytdlnis/cache/cookies.txt`。
- 用户的 yt-dlp 无法在 App 内更新：报 `failed to update youtube-dl`（见第 2 条），所以 yt-dlp 版本可能偏旧。

### 已知的代码事实（已读代码确认）
- `cookies.txt` 不是实时的：`WebViewActivity` 保存时调用 `CookieViewModel.getCookiesFromDB(url)`，从 WebView 的 Chromium `Cookies` 数据库里按域名拷一份快照存进 App 数据库；`CookieViewModel.updateCookiesFile()` 再把已启用的条目写进 `cache/cookies.txt`。之后在 WebView 里发生的变化（例如年龄确认、YouTube 轮换 cookies）不会自动同步进这个文件。
- `getCookiesFromDB` 只按目标域名（`host_key LIKE`）取 cookies。
- `updateCookiesFile()` 的拼接逻辑看起来有问题：`if (!cookieTXT.contains(line)) cookieTXT.append(it.content)` 是"某一行不存在就把整段内容再追加一遍"，可能产生重复行（上游代码，未验证是否影响 yt-dlp 读取）。

### 待核查（都还没验证）
1. 用户的 yt-dlp 实际版本（设置 → 关于 / 更新页）。先让第 2 条的更新修复生效，更新到最新版再试；SABR 警告往往与旧版有关。
2. 用户是否在**点完"继续"之后重新保存过 cookies**（删掉旧条目 → 内置浏览器登录 → 打开该视频点继续 → 立刻保存）。上一次会话只给了步骤，没得到结果。
3. `cookies.txt` 里是否真有登录态的 cookies（`.youtube.com` 的 `SAPISID`、`__Secure-3PSID`、`LOGIN_INFO` 等）。只取到未登录 cookies 时 yt-dlp 不会走登录态。
4. 该账号是否真的完成了 YouTube 的年龄验证（网页端弹"继续"不一定等于账号已验证；部分地区要求身份或信用卡验证，yt-dlp 的报错文字正是 "requiring account age-verification"）。
5. 查 yt-dlp 当前（2026-10）对年龄限制视频的官方说明和已知问题（issue #12482 及相关），确认是否需要特定 `player_client` 或 PO Token；不要凭记忆给出 `--extractor-args`。

### 可能的改进方向（未决定，先核查再做）
- 下载前自动从 WebView 的 cookie 库刷新一次 YouTube cookies，避免快照过期。
- 在 Cookies 页显示快照时间和是否包含登录态 cookies，方便用户判断。
- 修 `updateCookiesFile()` 的重复追加。

## 2. yt-dlp 应用内更新失败（已改代码，待真机验证）

- 现象：更新 yt-dlp 报 `failed to update youtube-dl`，没有原因。
- 原因（读代码）：`YTDLUpdater` 先请求 `api.github.com/repos/yt-dlp/.../releases/latest`（10 秒超时），失败一律抛同一句错误。GitHub API 未登录限流 60 次/小时/IP，VPN 共享 IP 容易被用光。
- 已改（提交 `efb08c9`；#70、#71 构建因 Edge 403 未发布，随下一个发布版本一起出）：API 失败时改读 `https://github.com/<repo>/releases/latest` 的跳转拿版本号，直接从 `releases/download/<tag>/<bin>` 下载；报错带上 HTTP 状态码或异常信息。
- 待办：新版本发布后请用户更新一次 yt-dlp，确认是否成功；失败的话看新的报错原因。

## 3. Edge 免费语音的令牌算法（已修，需关注）

- 2026-10 中旬起 Edge read-aloud 服务只接受 `Sec-MS-GEC-Version: 2-…` 和 v2 令牌（`<16 hex>-<64 hex>`），旧的纯 SHA-256 令牌返回 403。
- 已移植到 `dubbing-core/.../EdgeGec.kt`，来源 edge-tts `src/edge_tts/drm.py`（generate_sec_ms_gec_v2）；测试向量由参考实现生成。
- 以后 Edge 再次全部 403 时，先对比 edge-tts 最新的 `constants.py` / `drm.py` 是否又改了算法或版本号。CI 的 LiveTest `edgeTtsProducesRealMandarinSpeech` 会最先报出来。
