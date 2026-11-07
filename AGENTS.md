# AGENTS.md

面向后续接手本仓库的编码智能体。读完这一份就应当知道：什么不能碰、代码在哪、哪些不变量一改就会坏、怎么验证。

## 1. 项目定位与不可越界的原则

本仓库是一个**原创**的 Oxype Android 客户端。它只对接 Oxype 的公开 HTTP 服务。

- 交互思路参考 Telegram X 早期（单 Activity、列表到聊天页、倒序消息列表、统一的 `openChat(sessionId)` 入口），**仅限交互思路**。
- 不得复制 Telegram X 的源码、目录结构、类名、资源、图标、文案、品牌或具体视觉数值。
- 不引入 TDLib，不实现 Telegram 协议，不把本项目描述成任何官方或"移植"版本。
- 不把 Oxype 的私有实现细节当作事实：协议依据是客户端当前实现（见 `docs/protocol.md`），并以开发者正式文档为准。
- 应用内"许可与说明"（`SettingsScreen.kt` 的 `LicenceDialog`）与界面上的独立声明是产品的一部分，不要删除。

## 2. 技术基线

全部为当前仓库事实，改版本前先确认上游兼容性。

| 项 | 值 | 位置 |
| --- | --- | --- |
| 语言 / UI | Kotlin + Jetpack Compose + Material 3 | `app/build.gradle.kts` |
| Activity 数量 | 1（`MainActivity`） | `app/src/main/java/dev/oxype/client/MainActivity.kt` |
| 插件 | `com.android.application` 9.4.1、`org.jetbrains.kotlin.plugin.compose` 2.4.21 | 根 `build.gradle.kts` |
| Gradle | 9.7.1（wrapper） | `gradle/wrapper/gradle-wrapper.properties` |
| compileSdk / targetSdk | 36 / 36 | `app/build.gradle.kts` |
| minSdk | 26 | 同上 |
| applicationId / namespace | `dev.oxype.client` | 同上 |
| Java 兼容级别 | 17（`compileOptions` 的 source/target；产物 class 主版本 61，即 Java 17） | 同上 |
| Compose BOM | 2024.12.01 | 同上 |
| 协程 | kotlinx-coroutines-android 1.7.3 | 同上 |
| 测试 | JUnit 4.13.2 + kotlinx-coroutines-test 1.7.3 + `org.json:json:20240303` | 同上 |

**AGP 9 的硬约束：不要添加 `org.jetbrains.kotlin.android` 插件。** 本工程用 AGP 9.4.1 内置的 Kotlin 支持，插件块只声明 `com.android.application` 与 `org.jetbrains.kotlin.plugin.compose`。再声明 `org.jetbrains.kotlin.android` 会同时应用两套 Kotlin 编译配置，导致重复的 Kotlin 编译任务/插件冲突。本仓库也没有 `kotlinOptions` 块、没有 `gradle/libs.versions.toml` 版本目录——不要顺手引入。

其他事实：

- 没有依赖注入框架。`AppContainer` 在 `OxypeApplication.onCreate` 中手工创建一次，再由 `OxypeViewModelFactory` 交给四个 ViewModel。
- 没有 `android.util.Log` 调用。排查问题靠 JUnit 输出、fixture 控制台与 `adb logcat` 的系统/框架错误。
- 明文 HTTP 是刻意允许的（`usesCleartextTraffic="true"`，自建 Oxype 常在局域网 HTTP 上）。`allowBackup="false"`，且 `backup_rules.xml` / `data_extraction_rules.xml` 排除了 `oxype_client_session`，因为里面存 token。不要打开备份。

## 3. 分层与文件地图

```
app/src/main/java/dev/oxype/client/
├── MainActivity.kt                 唯一 Activity，只负责 setContent { AppRoot(container) }
├── data/
│   ├── OxypeRepository.kt          唯一状态源：认证、会话列表、聊天快照、个人资料、轮询、发送、删除
│   ├── oxype/                      协议层（唯一的协议知识来源）
│   │   ├── HttpTransport.kt        传输接缝：HttpRequest/HttpResponse/接口定义
│   │   ├── HttpUrlConnectionTransport.kt  真实实现，每请求一个连接，15s 超时
│   │   ├── OxypeHttpClient.kt      base URL 归一化、凭据注入、Kalmia 信封解包、状态码→异常
│   │   ├── OxypeApi.kt             路由与字段拼装/解析（所有 path 字符串只在这里）
│   │   ├── OxypeJson.kt            容错读取：蛇形/驼峰别名、data 信封、宽松布尔与数字
│   │   ├── OxypeModels.kt          AuthCredentials / ChatMessage / SessionSummary / LastMessageResult / OxypeException
│   │   └── AvatarUrls.kt           头像相对 URL 解析，只放行 http(s)
│   ├── sync/MessageSyncCoordinator.kt  单个会话的消息存储、分页窗口、水位、墓碑、去重
│   └── session/SessionStore.kt     SharedPreferences：token、用户名、base URL、主题；SessionStorage 接口
└── ui/
    ├── AppContainer.kt             AppContainer / OxypeApplication / OxypeViewModelFactory / ThemeSettings
    ├── AppRoot.kt                  Compose 根，恢复占位、目的地切换 + 过渡动画、底栏宿主、主题
    ├── AppNavigation.kt            NavigationState/Destination 状态机 + tabIndexOf/sectionFor + rememberSaveable 持久化
    ├── auth/                       AuthScreen + AuthViewModel（登录/注册/服务器地址）
    ├── home/                       HomeScreen + HomeViewModel（列表、创建、加入、刷新）
    ├── chat/                       ChatScreen + ChatViewModel + ChatListItems + SenderPresentation
    │                               + QuotePresentation（引用作者/正文/可跳转）+ ReplyGestures（左滑/双击）
    ├── groupinfo/                  GroupInfoScreen + GroupInfoViewModel（整页群聊信息）
    ├── profile/                    ProfileScreen + ProfileViewModel + AvatarPicker + AvatarEncoding
    │                               + AvatarUploadRules（纯 Kotlin：格式/尺寸/大小校验）
    ├── components/                 MessageBubble、QuoteBlock、BubbleWidthPolicy、SenderAvatar、
    │                               AvatarImageLimits、SessionRow、OxypeBottomBar（三入口胶囊底栏）
    ├── settings/                   SettingsScreen + SettingsViewModel（账户入口、地址、主题、退出、许可）
    └── theme/                      OxypeTheme / OxypeColors / oxypeColors
```

职责边界（越界就是缺陷）：

- **协议变更只改 `data/oxype` 层。** 路由字符串、字段名、别名、信封形状、状态码映射都封闭在这一层；上层不得出现 `JSONObject` 或 URL 拼接。
- `OxypeRepository` 负责编排与状态，不解析 JSON，不决定路由。
- `MessageSyncCoordinator` 是纯 Kotlin（无 Android 依赖），负责顺序、去重、水位与墓碑；它不认识 HTTP，也不认识 Compose。
- ViewModel 只把 Repository 的 `StateFlow` 映射为 UI 状态、发起动作、做绑定代际守卫；**不自己发 HTTP**。
- Composable 只渲染状态与转发事件。展示规则（宽度、首字母、分组判定）抽成无 Compose 依赖的纯函数/对象，便于 JVM 测试。
- `MainActivity` 不做业务逻辑。

## 4. 关键不变量（改动前必须先读代码确认）

以下每一条都有对应测试守着；破坏它们通常不会编译失败，只会静默丢消息或误登出。

1. **消息序号是会话内递增序号，可因删除出现空洞。** 序号不是全局 id，也不能假设连续。`getMessages` 返回少于请求窗口是正常结果，空数组同样是合法答案（`OxypeApi.messages`）。不要把"短页"当错误，也不要假设"返回条数 == 窗口大小"。
2. **`getMessages` 是 `start..end` 双闭区间。** 请求体字段就是 `start`/`end`，两端都含（`OxypeApi.messages`，`MessageSyncCoordinator` 的窗口计算）。
3. **消息类型只有 `text` 与 `markdown`。** 未知 piece 类型被跳过；若跳过之后没有可显示内容，整条消息是协议错误而不是空气泡（`OxypeApi.parseMessage`）。
4. **轮询只在聊天页处于 RESUMED 时进行。** `ChatScreen` 用 `repeatOnLifecycle(Lifecycle.State.RESUMED)` 调 `onForeground()` / `onBackground()`；`ChatViewModel` 只在首屏加载成功后才 `startPolling()`。离开页面、进入后台、切换会话都必须停止轮询。默认间隔 `OxypeRepository.DEFAULT_POLL_INTERVAL_MILLIS = 3_000L`。
5. **水位只推进到实际取回的范围。** `/lastMessage` 给的是服务器最新序号，但一次请求有窗口上限（`DEFAULT_POLL_WINDOW = 30`）。只能 `acknowledgeFetchedWindow(window.start, window.endInclusive)`，**不能**直接用水位以外的 `newest` 推进，否则积压中间段会被永久跳过。推测窗口（`speculativePollWindow` / `speculativeHistoryWindow`）只允许水位跟随实际返回的消息 id。
6. **推测窗口不得声称"已到最早消息"。** `speculativeHistoryWindow()` 的起点是猜的，`applyHistory(..., speculative = true)` 不设 `reachedTop`，也不把窗口末端记入水位。`reachedTop` 只能由真实以 0/1 为起点（或 `nextHistoryWindow` 走到边界）的窗口证明。首屏锚点只信 `/lastMessage`；`receivedMessageSeq` 是**已读位置**，绝不用来锚定首屏。
7. **删除墓碑。** `deleteMessage` 只有服务器确认成功后才 `remove(messageId)`；该 id 进入 `tombstones`，之后到达的历史页、轮询、发送回读都不能把它复活。墓碑只在"打开一个新会话"时随新 coordinator 消失（`clear()` 刻意保留墓碑）。
8. **发送尾部缺 seq 不得越水位。** 发送后 `refreshTail` 请求 `seq-4..seq`（`TAIL_LOOKBACK = 5`）并要求 `seq` 确实在响应里（`mergeWindow(..., requirePresent = around)`）；只有 `contains(around)` 为真且 `isContiguousWithWatermark(seq)` 才 `acknowledgeSend(seq)`。回读失败或响应缺失该条时水位不动，下一轮轮询必须继续请求包含 `seq` 的范围。
9. **403 ≠ 401。**
   - `401` = token 失效：经 `invalidateCredentials(owner)` 清本地凭据并回登录页。
   - `403` = 已认证但无权：属于**单次操作/单个会话**的错误，绝不清除全局 token。聊天里 403 会置 `chatAccessDenied` 并停止该会话轮询，只有重新打开会话才清除。
   - 判断只走 `OxypeErrorKind.UNAUTHORIZED` / `FORBIDDEN`，不要按消息文本或 `httpStatus` 猜。
10. **账号代际 / 凭据所有权守卫。** 任何会挂起的操作在第一次挂起前取 `AuthOwner`（含 `authGeneration` 与凭据对），写状态或使凭据失效前用 `isCurrentAuth(owner)` 校验。同一账号重新登录也是**不同 owner**（token 变了）。会话切换用 `chatGeneration` / `ChatViewModel.bindingGeneration` 守卫，晚到的分页、发送、成员、错误关闭都不得写进新会话。`dismissChatError` 还要带 `chatVersion`。
11. **读取与展示分离。** `OxypeRepository._chat` 的 `ChatSnapshot` 才是聊天页的权威状态；`ChatViewModel` 只镜像它。清错误必须同时清 Repository（`dismissChatError`），否则下一次 publish 会把旧错误复制回来。
12. **认证恢复三态。** `AuthRestoreState` = `CHECKING` / `READY` / `SIGNED_OUT`。`credentials == null` 不能区分"还没校验"和"已登出"，所以恢复期必须显示占位而不是登录页或空聊天。`isAlive` 只有明确 false 或 401 才登出；网络/5xx 视为"无法判断"，保留 token。
13. **协议层错误分类先看状态码。** 非 2xx 先按状态分类，再用响应体补充文案；HTML 错误页不得被当成 JSON 解析成功。2xx 但解析失败是 `PROTOCOL`，空响应体解码为 `null`。
14. **头像 URL 是不可信输入。** 只放行 `http`/`https`；相对路径解析到当前服务器；请求头像时**不带** token（可能指向第三方）。下载上限 `AvatarImageLimits.MAX_BYTES = 3 * 1024 * 1024`，先读 bounds 再按 2 的幂采样到 `TARGET_PX = 96`。
15. **`reply` 片段必须在 `pieces` 首位，且只发 `messageId`。** 服务端写发送者/名字/文本快照并拒绝非首位 reply（400）；客户端拼的快照会被忽略。编辑引用消息必须重发 reply 片段，否则保存后引用丢失（`OxypeApi.sendText`）。`reply` 不进入 `plainText`，也不参与"没有可显示内容"的判定。
16. **引用解析只有 `messageId` 是硬依赖。** 缺它则丢弃整块引用但保留正文；`sender`/`senderName`/`text` 缺失时回退到本地已加载消息（作者名 → `用户 N` → `未知用户`，正文缺失不画那一行）。引用自己的消息，作者行**先判自己显示「我」**，再取快照名（`QuotePresentation.quoteAuthorName`，对应 spec §7）。
17. **跳回原消息只在已加载窗口内。** 用 `ChatListItems.lazyIndexForMessage` 定位；不在窗口内时显示「原消息不在当前已加载的消息里」，不要伪造滚动位置。
18. **回复目标只在成功发送、切会话、`close()`、登出时清除。** 发送失败必须同时保留 `replyTarget` 与草稿，让用户原样重试（`ChatViewModel.send`）。发送期间用户新选的引用目标属于**下一条消息**，成功回调只能清它自己那一次选择（`replySelectionRevision`）。
19. **进入群聊信息页不得关闭聊天。** `Destination.GroupInfo` 返回时只切回 `Destination.Chat`，不调用 `chatViewModel.close()`，否则转写、草稿与滚动位置全丢、并重跑首屏历史。`groupinfo` 的 saver 要带占位标题/副标题。聊天的 `LazyListState` 由 `retainedChatSessionId` 提供的稳定键经 `SaveableStateHolder` 保留；真正离开会话时该键必须落回 `0`。
20. **群主/管理员只有一个来源。** 先把服务端布尔-only 的答案折进 `ownerId`/`adminIds`，再统一按 id 计算 `isOwner`/`isAdmin`；头部徽标与成员行标签都经 `GroupInfo.roleOf`，页面不得自行二次判断（`OxypeRepository.loadGroupInfo`）。
21. **群聊信息缓存绑定完整登录身份。** `generation|userId|baseUrl` 任一变化都必须重新加载；重新进入页面要重新请求（只有旋转复用缓存），否则会长期显示上一个账号/上一个服务器的成员与角色。
22. **个人资料缓存绑定同一个身份键。** `ProfileSnapshot.identity = generation|userId|baseUrl`；`loadProfile` / `uploadAvatar` / `removeAvatar` 在第一次挂起前取 `AuthOwner`，回来先用 `isCurrentAuth` 校验再发布。`login` / `signOutLocally` 都要 `clearProfile()`。资料页的 401 走 `invalidateCredentials(owner)`，403 只做行内提示（上传自己的头像不会 403，但共享会话之外的 `getAvatar` 会）。头像**存服务器**（`/avatar/u<id>-<hash>.<ext>`），客户端不落盘、只做内存图片缓存。
23. **头像上传的本地校验必须与服务端同规则。** 格式按 magic bytes、长度上限 3 MB、任一边 4096（`AvatarUploadRules`，纯 Kotlin，有 JVM 测试）；不要按扩展名或声明 MIME 判断，也不要在头部读不出尺寸时按尺寸拒绝。图片选择后的解码/缩放/重编码只在 `Dispatchers.Default` 上做（`AvatarPicker`），失败给可读文案而不是抛异常。

## 5. 测试与验证命令（Windows / Git Bash）

在仓库根目录执行（Git Bash 下需带 `./`；cmd 里可写 `gradlew.bat`，PowerShell 需 `.\gradlew.bat`）。

```bash
# 单元测试（JVM，无 Android/设备依赖；最近记录 395 个用例 / 31 个测试类）
./gradlew.bat --offline :app:testDebugUnitTest

# 构建 debug APK，产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew.bat --offline :app:assembleDebug

# 静态检查
./gradlew.bat --offline :app:lintDebug
```

`--offline` 依赖本地 Gradle 缓存已就绪；首次构建或换机器时去掉 `--offline`。不要为了"验证"去安装全局依赖。

**不要只看 `BUILD SUCCESSFUL`。** 必须看用例统计：

- JUnit XML：`app/build/test-results/testDebugUnitTest/TEST-*.xml`，每个文件的 `<testsuite>` 上带 `tests`、`skipped`、`failures`、`errors` 四个属性，这是权威数字（Gradle 的"0 tests"也可能 BUILD SUCCESSFUL）。聚合示例（Git Bash）：

```bash
grep -ho 'tests="[0-9]*"' app/build/test-results/testDebugUnitTest/*.xml | grep -o '[0-9]*' | awk '{s+=$1} END {print "tests",s}'
grep -ho 'failures="[0-9]*"' app/build/test-results/testDebugUnitTest/*.xml | grep -o '[0-9]*' | awk '{s+=$1} END {print "failures",s}'
```

- HTML 报告：`app/build/reports/tests/testDebugUnitTest/index.html`。
- Lint 文本结果：`app/build/reports/lint-results-debug.txt`。

测试位于 `app/src/test/java/dev/oxype/client/`，共 31 个测试类。`testsupport/TestSupport.kt` 提供 `FakeSessionStorage`（内存版 `SessionStorage`）与 `ScriptedTransport`（按路由脚本化的 `HttpTransport`）。新行为必须用这两者写可回归的 JVM 测试，不要新增只在真机上能跑的断言。

## 6. 本机联调（fixture，不是真实服务器）

`tools/ui-fixture/` 是一个**内存态 Oxype 协议模拟器**（Python 标准库，无第三方依赖），只用于在模拟器里走查 UI：三入口底栏与页面切换、群聊、气泡宽度、头像/首字母兜底、个人资料与头像上传/移除、发送、引用回复、删除、群聊信息页。它不在 APK 里，也不代表上游服务。

```bash
# 1) 启动 fixture（监听 127.0.0.1:18080）
cd tools/ui-fixture
python -I fixture_server.py

# 2) 让设备/模拟器的 18080 指回宿主机（serial 按实际设备替换）
adb -s 127.0.0.1:16384 reverse tcp:18080 tcp:18080

# 3) 在应用的登录页/设置页把服务器地址填成
#    http://127.0.0.1:18080
#    登录：用户编号 1，密码 testpass1
#    会话：42「周末徒步小队」
#    个人资料：底栏第三项，可设置/更换/移除本人头像
```

注意：

- 走 `adb reverse` 时**必须填 `127.0.0.1:18080`**，不要填默认的 `10.0.2.2:12345`——后者是模拟器直连宿主机，不经过 reverse。
- fixture 实现了 `POST /login/{userid}`、`POST /isAlive/{userid}`、`POST /logout/{userid}`、`GET /getUser/{userid}`、`GET /getAvatar/{userid}`、`POST /uploadAvatar/{userid}`、`POST /removeAvatar/{userid}`、`GET /getJoinedSessions/{userid}`、`POST /getSessions`、`POST /getSessionInfo/{sessionId}`、`GET /getSession/{sessionId}`、`GET /getSessionUsers/{sessionId}`、`POST /receivedMessageSeq/{userid}/{sessionId}`、`GET /lastMessage/{sessionId}`、`POST /getMessages/{sessionId}`、`POST /sendMessage/{sessionId}`（含首位 reply 片段）、`POST /deleteMessage/{sessionId}`，外加静态头像（成员头像 `/avatars/alex.png`、本人头像 `/avatars/zhouzhou.png`、会话头像 `/avatars/session-42.png`）、上传后由 `/avatar/u1-<hash>.png` 提供的图片，以及 `/health`。**它没有 `/register`，也没有 `createSession`/`joinSessionByUuid`/`createSessionInvite`**，所以 fixture 下只能登录预置账号、走查已有会话，不能验证注册、创建或加入。
- 头像路由按真实规则实现：`getAvatar` 对共享会话的账号返回 200、对陌生人 403；`uploadAvatar` 只接受自己的 uid（否则 403）、按 magic bytes 校验格式与 3 MB / 4096 像素上限（否则 400）、文件名带内容哈希；`removeAvatar` 幂等并删掉旧文件。走查时上传/移除后 `getUser` 的 `avatarUrl` 会跟着变，可以确认资料页以服务器为准。
- 会话 42 预置 `owner = 1`、`admins = [1, 2]`、带会话头像；消息 1..9，其中 7 引用 4、9 引用 8。reply 的三条规则都能在 fixture 上复现：非首位 → 400「reply 片段必须在首位」，未知目标 → 404 `Message N to reply to was not found`，目标被删后快照仍保留。
- 设备重连、模拟器重启、`adb kill-server` 之后 reverse 会丢失，重新执行第 2 步。
- 停止：fixture 终端 Ctrl+C（或杀掉后台进程）。fixture 状态只在内存里，重启即重置。

**必须明确：fixture 联调 ≠ 真实 Oxype 端到端验证。** 仓库没有任何针对真实服务端的集成/E2E 用例；"构建 + 单元测试通过"不代表对真实服务器验证过。

## 7. 提交前自检清单

- [ ] 新增/修改的行为有能捕获回归的 JVM 测试，且 `:app:testDebugUnitTest` 的 XML 显示 `failures=0 errors=0`，用例数没有无故减少。
- [ ] 协议相关改动只落在 `data/oxype`；没有把路由或字段名泄漏到 Repository/ViewModel/Compose。
- [ ] 没有提交 `build/`、`.gradle/`、`.kotlin/`、`*.apk`、`*.aab`、`*.log`、`local.properties`（`.gitignore` 已覆盖，别用 `git add -f` 绕过）。
- [ ] 没有把 token、密码、真实服务器凭据写进代码、日志、测试 fixture 或文档；新增日志也不要打印 `AuthCredentials`。
- [ ] 没有为了通过测试放宽不变量（例如把 403 当 401、用水位外的 `newest` 推进、去掉 `requirePresent`）。
- [ ] 文案保持中文 UI；不引入营销式表述或未实现能力的描述。
- [ ] 不修改 `README.md` 的历史结论：如果代码与 README 冲突，以代码为准并单独报告，由维护者决定 README 怎么改。

## 8. 已实现 / 明确未实现

已实现（有代码与测试）：

- 注册、登录、退出登录；启动时 `isAlive` 校验本地 token，401 登出、网络失败保留。
- 已加入会话列表（`getJoinedSessions` + `getSessions`，缺失字段用 `getSessionInfo`/`getSession` 补全）、创建会话、用邀请 UUID 加入。
- 三入口悬浮胶囊底栏（聊天 / 设置 / 个人资料）：胶囊弹簧滑动、图标文字颜色过渡、按压反馈；聊天页与群聊信息页不显示底栏。顶层切换有短横向过渡，进入会话是右侧推入、chat ↔ 群聊信息是淡入淡出。
- 首屏历史（`/lastMessage` 锚定，30 条窗口；锚点不可用时的 100 条推测窗口）、向上翻页、前台 3 秒 HTTP 轮询。
- 发送纯文本、引用回复（长按菜单 / 左滑 / 双击右侧，正文与作者快照，点击跳回已加载的原消息）、删除自己发送的消息、复制文本。
- 群聊发送者名称与头像（失败时 `用户 N` + 名称首字母彩色圆盘）。
- 群聊信息页（点击聊天标题进入）：会话头像、简介、成员列表与群主/管理员标签、`（我）`、会话编号；返回不关闭聊天。
- 个人资料页（底栏第三项，或设置页账户行进入）：用户名、账号编号、头像；支持从系统图片选择器设置/更换/移除**本人头像**（`uploadAvatar`/`removeAvatar`），上传前本地按服务端规则校验；头像由服务器保存，客户端不落盘。
- 服务器地址可改（登录页与设置页，持久化到 `oxype_client_session`）、浅色/深色/跟随系统主题。
- 单 Activity 导航、状态保存与恢复（含 `groupinfo`/`profile` 目的地、各标签滚动位置）、退出/过期回登录页。

明确未实现（不要写成已有）：

- 媒体/附件（图片、文件、语音、视频）、消息编辑（虽然能解析 `editedAt`，但没有编辑入口）、已读回执展示与输入状态。
- 推送通知、WebSocket `/oxype` 事件推送（只用轮询）。
- 多账号、通话、频道、Stories、贴纸、机器人、群成员管理、会话级置顶、未读数、消息搜索。
- 用户资料的编辑：用户名/密码没有修改入口，个人资料页只读显示（服务端也没有对应的公开路由）。**会话头像**的上传/移除（`uploadSessionAvatar` 等）未接入。
- `createSessionInvite` 与 `receivedMessageSeq` 在 `OxypeApi` 中可用但当前 UI 未调用。
- 端到端/E2E 测试与真实服务器集成验证。
