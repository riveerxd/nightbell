# Nightbell 中文版（本 fork 分支 `zh-3.26.0`）

上游：[riveerxd/nightbell](https://github.com/riveerxd/nightbell)（Apache-2.0），基线 `v3.25.0` / `a9ab608`。
**本分支不是官方版本，与上游无隶属关系**，版权归原作者；本分支只叠加下面这些改动。

## 加了什么

- **运行时中英切换**：`ui/i18n/L10n.kt`（`tr` / `pick` / `res`）保留英文原文，按 `GlobalSettings.language`
  在组合期解析，切换语言即时重组、不重建 Activity。约 1400 条译文放在 `tools/zh_*.tsv`，
  由 `tools/gen_zh.py` 生成 `ui/i18n/Zh.kt`，Kotlin 里不写死中文。
- **接口抓包**：`data/capture/`。`VpnService` 只调 `setHttpProxy` 把系统代理指到 `127.0.0.1`，
  加哑路由、不承载用户流量。明文 HTTP 全量捕获；HTTPS 默认只看 `CONNECT host:port`，
  按域名白名单开启本机 MITM（根证书用 `okhttp-tls` 在设备上生成，私钥不出应用私有目录）。
- **监控请求明细**：每次检查的请求/响应留 20 条（全局 400 条），gzip/deflate 嗅探 + GBK 回退。
- **网络信息页**：2 秒轮询，SSID/BSSID/信号强度/运营商/IP/DNS/MTU。
- **秒级检查间隔**：`Monitor.intervalSeconds` 覆盖分钟级；WorkManager 最小 15 分钟，
  因此前台服务跑独立绝对节拍循环，有秒级监控时自动启用。

## 编译

```bash
# 需要 JDK 21 + Android SDK platform 36 + build-tools 36.0.0
# Gradle 8.13 不支持 JDK 25（新版 Android Studio 自带的是 25）
python3 tools/gen_zh.py        # 由 tools/zh_*.tsv 重新生成 ui/i18n/Zh.kt
./gradlew :app:assembleDebug   # 包名 me.river.nightbell.debug，可与官方版共存
```

Windows 上可直接用仓库根目录的 `build-zh.bat`（debug）与 `build-release.bat`（签名 release，
需要自备 `keystore/keystore.properties`）。两个脚本都从环境变量读 `JAVA_HOME` / `ANDROID_HOME`。

## 已知限制

- Android 7+ 未对用户 CA 做 opt-in 的应用、以及证书固定（pinning）的应用，HTTPS 解密必然失败。
- 抓包只在 VPN 开启时生效；清缓存或改白名单后需重新访问才会产生新的解密条目。
- release 包名与官方相同（`me.river.nightbell`），签名不同，装过官方版需先卸载。

## 许可证

Apache-2.0，见 [LICENSE](LICENSE)。上游代码版权归原作者 `riveerxd`。
