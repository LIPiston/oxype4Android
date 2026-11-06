# Oxype Android 客户端

一个原创的 Android 客户端，用 Kotlin + Jetpack Compose + Material 3 实现，单 Activity。
它只对接 Oxype 的公开 HTTP 服务，不含任何 Telegram X 的源码、资源、图标、文案或视觉参数。

## 项目目标

交互上参考 Telegram X 在“尚未进行结构化 UI 改版”时期的思路：单 Activity、
列表到聊天页的清晰导航、倒序消息列表、统一的 `openChat(sessionId)` 入口、
把加载/分页/错误状态分开处理。

**这是交互层面的参考，不是移植。** 本项目不复制 Telegram X 的代码结构、类名、资源、
品牌、图标或具体视觉数值，也不会引入 TDLib / Telegram 协议。

第一阶段范围：

- 注册、登录、退出登录；启动时用 `isAlive` 校验本地保存的 token。
- 已加入会话列表、创建会话、用邀请 UUID 加入会话。
- 拉取历史消息（Oxype 的 `start..end` 两端闭区间）、发送纯文本、
  删除自己发送的消息、复制文本。
- 前台用 HTTP 序号轮询同步新消息。
- 设置：浅色/深色/跟随系统主题、服务器地址、退出登录。

## 与 Oxype 服务端通信

- 默认地址：`http://10.0.2.2:12345`。`10.0.2.2` 是 Android 模拟器访问宿主机的地址，
  默认端口 `12345`。
- 认证方式：每个已认证请求都携带 `userid + token` 这一对凭据。
  - POST 路由把凭据放在 JSON body 里。
  - GET 路由把凭据放在 query string 里。
- 响应可能带 Kalmia 的 `data` 外层包装，也可能是裸数组；客户端两种都接受。
- 消息 id 是会话内的递增序号，可以因删除而出现空洞。
- 状态码语义：
  - `401`：token 失效，客户端退回登录页。
  - `403`：已认证但无权限。它是单次操作/单个会话的错误，**不会**清除全局 token。

### 配置服务器地址

服务器地址在登录页和设置页都能改，修改后立即生效并持久化到
`SharedPreferences`（`oxype_client_session/base_url`）。

- 模拟器访问宿主机：`http://10.0.2.2:12345`（默认值）。
- 真机访问同一局域网内的服务器：`http://<电脑局域网 IP>:12345`，
  例如 `http://192.168.1.20:12345`。手机与电脑需在同一网段，且电脑防火墙放行该端口。
- 也接受不带 `http://` 的写法，客户端会自动补上；末尾 `/` 会被去掉。
- 明文 HTTP 是允许的（`usesCleartextTraffic="true"`），因为自建 Oxype 服务常跑在
  局域网 HTTP 上；服务器若要求 HTTPS 请直接填 `https://` 地址。

## 构建

需要 JDK 17 与 Android SDK（compileSdk 36）。构建脚本通过 `ANDROID_HOME`
（或 `ANDROID_SDK_ROOT`）定位 SDK，也可以把路径写进根目录的 `local.properties`：

```properties
# Windows 路径在 properties 文件里要写成双反斜杠
sdk.dir=C\:\\Users\\<你>\\AppData\\Local\\Android\\Sdk
```

```bash
# Windows
gradlew.bat --offline :app:assembleDebug

# Linux / macOS
./gradlew --offline :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

其他常用命令：

```bash
# 单元测试
gradlew.bat --offline :app:testDebugUnitTest

# 静态检查
gradlew.bat --offline :app:lintDebug
```

`--offline` 假定依赖已经在本地 Gradle 缓存中；首次构建可去掉它。

### 安装 debug APK

debug 包用默认的 debug keystore 签名，可以直接侧载，不需要额外配置：

```bash
# 已连接的设备或已启动的模拟器
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- 最低支持版本是 **minSdk 26（Android 8.0）**，`targetSdk` 与 `compileSdk` 都是 36。
  低于 8.0 的设备会拒绝安装（`INSTALL_FAILED_OLDER_SDK`）。
- 安装的是 debug 变体，`applicationId` 为 `dev.oxype.client`，未开启混淆。
- 卸载或换签名后重新安装时用 `adb install -r`；签名冲突时先 `adb uninstall dev.oxype.client`。

## 当前限制

- **只用 HTTP 轮询**：前台每 3 秒（`DEFAULT_POLL_INTERVAL_MILLIS`）轮询一次。实际调用链：

  - 轮询主路径（`OxypeRepository.pollOnce`）只用 `GET /lastMessage/{sessionId}`：
    它给出服务器上最新的消息序号，客户端只请求缺失的那一段 `getMessages(start..end)`。
    回答是明确的 `hasMessage = false` 时跳过请求；负载读不懂时才退化为一个固定的
    推测窗口（`speculativePollWindow`）。
  - `POST /receivedMessageSeq/{userid}/{sessionId}` 返回的是**已读位置**，不是会话的最新序号，
    因此客户端不用它锚定首屏：`openChat` 只信 `lastMessage`。`lastMessage` 读不到时，
    首屏直接改为从序号 1 开始的一个较宽窗口（`speculativeHistoryWindow`）。
    这种推测窗口既不推进水位，也不会被当成“已到最早消息”，下一轮轮询会从实际取到的
    序号继续向上请求，不会跳过中间范围。

  切到后台即停止。未实现 WebSocket `/oxype` 事件推送。
- **消息内容仅纯文本 / markdown**：`text` 与 `markdown` piece 都能解析显示，
  但没有富文本渲染器。
- **群聊成员信息**：打开会话后会额外请求 `GET /getSessionUsers/{sessionId}` 建立
  `senderId → 名称/头像` 的映射，用于群聊里显示发送者名字和头像。该请求失败不影响
  历史消息显示，名字退回 `用户 N`、头像退回名称首字母的彩色圆盘。
  头像字段不是 Oxype 的必选字段。正式接口里 `avatarUrl` 是**相对 URL**（如
  `/avatar/u1-3f9a1c04.png`），没有头像时为空字符串；客户端会把相对路径解析到
  当前配置的服务器地址，且只允许 `http`/`https`，不会把 token 带给第三方。
  服务端接受的图片上限为 3 MB（`3145728` 字节）、每边 4096 像素，客户端下载上限
  与之对齐为 3 MB，并先读 bounds 再按 2 的幂采样到约 96 px 解码。为了兼容旧的
  部署，仍按上面列出的字段别名读取；缺失、为空或非 HTTP(S) 时一律走首字母兜底。
- **不支持**：媒体/附件、消息编辑、已读回执、输入状态、推送通知、多账号、
  通话、频道、Stories、贴纸、机器人等。
- **没有端到端（E2E）测试**：自动化测试全部是 JVM 单元测试（用脚本化的假
  `HttpTransport` 覆盖协议与状态机）；UI 侧只在 MuMu 模拟器上配合本机协议
  fixture 做过手工 GUI 验证（列表刷新、返回后重入、单字符消息发送）。既没有连接
  真实 Oxype 服务器跑过集成用例，也没有基于真实服务端的 E2E 结果，因此“构建 +
  单元测试通过”不代表已对真实服务验证过。
- 会话级置顶、未读数、消息搜索均未实现。
