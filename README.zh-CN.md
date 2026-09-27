<div align="center">

# WrtHub 安卓版

[English](README.md) | **简体中文**

在手机上管理 OpenWrt / ImmortalWrt 路由器的原生安卓 App。

</div>

> 📱 **用 iPhone？** WrtHub 已经上架 App Store，如果你需要，可以去 App Store 直接搜索 **WrtHub**。

## 功能

**概览**
- 首页仪表盘：CPU、内存、温度、连接数、延迟和实时流量，可自选首页显示哪些卡片
- 按接口的实时流量图
- 在线设备列表，可自定义设备名称；黑名单一键禁止设备上网
- 配置备份与固件升级
- 桌面小组件

**网络**
- 防火墙：端口转发与通信规则
- 网络接口：协议、链路状态与流量
- 网络诊断：Ping / Traceroute / Nslookup
- 网络代理（OpenClash）
- 设备限速（eqos）
- 流量监控，长期统计（vnStat）
- 局域网测速：测手机与路由器之间的链路
- Wi-Fi 设置、扫描与中继

**文件**
- NAS 共享（Samba）
- FileBrowser
- FTP 服务（vsftpd），内置文件浏览

**系统**
- **AI 终端**：完整的网页终端（ttyd）加 AI 助手。用自然语言描述要做的事，确认 AI 给出的命令后由 App 执行，并自动总结执行结果。使用 DeepSeek，需要你自己的 API Key；可选的「自动执行」模式默认关闭。
- 网络唤醒（etherwake / wol）
- SSH 设置
- 系统日志与内核日志
- 软件包管理（opkg / apk）
- 定时任务（cron）
- 灯光配置（LED）

依赖路由器插件的功能，在插件未安装时会显示为灰色，点进去可以一键跳到软件包管理安装。

## 使用要求

- Android 8.0（API 26）及以上
- 基于 OpenWrt 的路由器（OpenWrt、ImmortalWrt 等），带 LuCI 和 rpcd，手机能访问到
- 可选的路由器插件，只有用到对应功能时才需要：`luci-app-ttyd`、`luci-app-wol`、`luci-app-vnstat2`、`luci-app-openclash`、`luci-app-eqos`、`samba4`、`vsftpd`、`luci-app-filebrowser`

## 工作方式

App 和 LuCI 网页后台走同一条路：用路由器的登录账号，通过 ubus JSON-RPC（`/ubus`）与路由器通信，路由器上不需要额外安装任何代理程序。路由器账号密码和 AI 的 API Key 都加密保存在手机本地（EncryptedSharedPreferences）。

## 编译

1. 安装 Android Studio（或 JDK 11+ 与 Android SDK）。
2. 克隆本仓库后用 Android Studio 打开，或者在命令行编译：

   ```bash
   ./gradlew assembleDebug
   ```

   APK 输出在 `app/build/outputs/apk/debug/`。

3. 运行单元测试：

   ```bash
   ./gradlew testDebugUnitTest
   ```

## 目录结构

```
app/src/main/java/com/whykangkang/wrthub/
├── api/        路由器接口（ubus / LuCI / cgi-io）、AI 助手、插件检测
├── manager/    设备列表、设置、常用功能、服务目录
├── model/      数据模型
├── ui/         各页面：概览、实时、设备、服务、设置
├── widget/     桌面小组件
└── util/       工具类
app/src/main/assets/   AI 系统提示词与 ttyd 终端桥接脚本
```
