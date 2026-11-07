# 三入口底栏 + 个人资料 · 设计

2026-11-07 · 目标分支 `master` · 基于上游 `cao-awa/Oxype@92e7f9c`（提交信息 “Avatars.”）实测

> 状态：**待确认**。第 5 节的两个视觉选择还等你从画板上挑一个；其余内容已定。

## 1. 意图与缘由

现在的客户端只有「会话列表 → 聊天」两层，设置藏在列表右上角的文字按钮里，账号信息只是设置页顶部的两行文字。上游 Oxype 的网页端已经把界面组织成「会话 / 设置 / 个人资料」三块，并且**个人头像已经可用**（服务器保存图片，账号记录只留文件名）。客户端不该继续让用户翻进设置才能看到自己是谁、也无法换头像。

这次补齐的是导航骨架与个人资料这一块的真实能力，不是换皮：底栏让三个顶层区域随时可达，个人资料页把上游真实存在的字段（用户名、账号编号、头像）读出来并支持换/删头像，同时把网页端那套“设置”里客户端确实有的项目（服务器地址、主题、许可、退出）保留在设置页。

## 2. 目标

1. 登录后有三个顶层区域：**聊天、设置、个人资料**，由底部悬浮胶囊栏切换；在任一区域按返回都回到聊天（聊天页再按返回才退出应用）。
2. 底栏选中态有动效：高亮胶囊在选中项之间滑动，图标与文字颜色过渡，点击有按压反馈。
3. 顶层区域之间切换有轻量横向过渡，并各自保留滚动位置；进入聊天/群聊信息用“推入”动画、返回用“推出”动画。
4. 个人资料页展示并刷新：用户名、账号编号、头像；支持**设置头像**与**移除头像**。
5. 头像与资料读取遵循“服务器为准”：换服务器或换账号后必须重新读取，不允许显示上一账号/上一服务器的残留。
6. 聊天页与群聊信息页**不显示底栏**，输入框与返回逻辑保持现状。

## 3. 非目标

- **联系人 / 私聊**：Oxype 没有私聊系统，不做联系人入口、好友列表、最近通话。
- 账号资料里**没有**的字段一律不画：电话、生日、个人简介、动态、在线状态。
- 不改群聊信息页的只读定位：不加上传会话头像、改名、踢人、设管理员。
- 不同步服务器端的界面偏好（`getSettings` 的语言/主题/色调/输入格式）：Android 的主题仍是本地 `ThemeMode`，正文格式仍是纯文本发送。此项单独评估。
- 不引入图片加载库（Coil 等），继续用现有的 `SenderAvatar` / `AvatarImageLimits` 路线。
- 不做底栏角标/未读数（上游没有未读接口）。
- 不做 WebSocket 推送、消息编辑、媒体附件。

## 4. 约束（来自仓库现状与上游实测）

| 项 | 事实 |
| --- | --- |
| 单 Activity | `MainActivity` 只 `setContent { AppRoot(container) }`，导航是手写状态机（`AppNavigation.kt`） |
| 依赖 | 无 DI、无图片库、无 `material-icons-extended`；Compose BOM 2024.12.01，`animation` 已随 BOM 可用 |
| 协议层 | 所有路由/字段拼写只在 `data/oxype`；上层不得出现 `JSONObject` 或 URL 拼接 |
| 凭据守卫 | 任何挂起操作先取 `AuthOwner`，写状态/使凭据失效前用 `isCurrentAuth(owner)`；401 才登出，403 是单次错误 |
| 资料接口 | `GET /getUser/{uid}`（自己返回 `id/username/avatarUrl` 与 `pref*`，他人只返回 `id/username`） |
| 头像接口 | `GET /getAvatar/{uid}`（`kind/id/name/avatarUrl/hasAvatar/maxBytes/maxEdge/formats`）、`POST /uploadAvatar/{uid}`（body `image` 为 base64，可带 `data:` 前缀，≤3 MB、每边 ≤4096 px、PNG/JPEG/WebP/GIF，按魔数判断）、`POST /removeAvatar/{uid}`（幂等） |
| 头像存储 | 图片文件在服务器 `client/avatar/`，文件名带内容哈希（如 `u1-77802f6e.png`），URL 形如 `/avatar/u1-77802f6e.png`；换图即换 URL，可长期缓存 |
| 头像权限 | 只能改自己的（他人 403）；读头像要求“自己或至少同处一个会话”，否则 403 |
| 本机实测 | 用上游源码在 `127.0.0.1:12345` 起服务；注册 → 设置页 → 头像弹窗上传 → `avatarUrl` 变为 `/avatar/u1-77802f6e.png`，文件落在 `client/avatar/`，`getAvatar` 返回 `hasAvatar=true`、`maxBytes=3145728`、`maxEdge=4096` |
| 头像显示 | 服务器**不裁切**图片，正方形由显示端裁切（网页用 CSS `object-fit`）；客户端继续用 `ContentScale.Crop` |

## 5. 已经定下的选择

| 决定 | 来源 |
| --- | --- |
| 三个入口：聊天 · 设置 · 个人资料；**去掉联系人** | 你，明确要求（Oxype 无私聊） |
| 个人资料内容按上游网页端，不臆造字段 | 你，明确要求；已按上游源码与实机核对 |
| 个人头像要支持设置（服务器保存图片，其他资料也在服务器） | 你，明确要求；已实测通过 |
| 底栏外观：悬浮胶囊，左右留边，不贴屏幕底部 | 你给的 TG 截图；交互思路参考，不复制素材与具体数值 |
| 选中项高亮 + 点击反馈 + 页面切换动画 | 你，明确要求“补齐动画” |
| 聊天页与群聊信息页隐藏底栏 | 我的判断（聊天需要完整输入区，返回逻辑已独立） |

### 待你选择的两个视觉问题

画板：`.ryugi/artifacts/artboard/bottom-bar-and-profile/`（链接见本次消息）

- **Q1 · 个人资料页怎么排**（推荐 A）
  - A：大头像页头 + 两个操作按钮 + 分区卡片（账号 / 服务器）
  - B：紧凑列表，每行一项，头像旁一个“更换”
  - C：渐变头像卡 + 资料卡 + 服务器卡（信息最密）
- **Q2 · 底栏选中态怎么表现**（推荐 A）
  - A：选中项滑动的胶囊底色
  - B：只变图标与文字颜色（最安静）
  - C：胶囊 + 图标放大 + 文字加粗

未选的按推荐实现。

## 6. 规格

### 6.1 导航（`ui/AppNavigation.kt`、`ui/AppRoot.kt`）

`Destination` 增加两个顶层目的地，`Home` 改名为 `Chats`（避免与“首页”混淆；`navigationStateSaver` 的持久化键同步从 `"home"` 改为 `"chats"`，旧的 `"home"` 仍能被读取，映射到 `Chats`）：

```kotlin
internal sealed interface Destination {
    data object Auth : Destination
    data object Chats : Destination
    data object Settings : Destination
    data object Profile : Destination
    data class Chat(val sessionId: Long, val title: String, val subtitle: String) : Destination
    data class GroupInfo(val sessionId: Long, val title: String, val subtitle: String) : Destination
}
```

- 新增 `internal fun Destination.isTopLevel(): Boolean` = `Chats | Settings | Profile`。底栏只在它为真时组合。
- 新增 `internal fun tabIndexOf(destination: Destination): Int`（`Chats=0, Settings=1, Profile=2`，其他 `-1`）与 `internal fun Destination.sectionFor(): Destination`：`Chat`/`GroupInfo` 展开成 `Chats`，其余返回自身。切换标签用 `navigation.copy(destination = target)`，不保留旧标签的堆栈（本项目没有多级堆栈）。
- 返回（`BackHandler`）：
  - `Chat` → `Chats`（沿用现有 `leaveChat`：`chatViewModel.close()` + `homeViewModel.refresh()`）。
  - `GroupInfo` → `Chat`（沿用现有 `backToChat`，**不**调用 `close()`）。
  - `Settings` / `Profile` → `Chats`。
  - `Chats` → 不拦截，交给系统退出。
  - `Auth` → 不拦截。
- 凭据消失时 `credentialsChanged(present=false)` 仍回 `Auth`；`restoreFinished` 登录成功后落到 `Chats`（原 `Home`）。
- 每个顶层标签的 Compose 状态（滚动位置）用 `rememberSaveableStateHolder()` + 稳定键保留，键为目的地名（`"chats"/"settings"/"profile"`），聊天沿用现有 `retainedChatSessionId`。切标签不销毁其它标签的 `LazyListState`。

**不变量**：导航仍只由 `NavigationState` 派生；不允许在 Composable 里直接改字段或用新的导航库。

### 6.2 底栏组件（新增 `ui/components/OxypeBottomBar.kt`）

```kotlin
enum class BottomTab(val label: String) { CHATS("聊天"), SETTINGS("设置"), PROFILE("个人资料") }

@Composable
fun OxypeBottomBar(
    selected: BottomTab,
    onSelect: (BottomTab) -> Unit,
    modifier: Modifier = Modifier
)
```

视觉（按 Q2 的选择落地，默认 A）：

- 外层：`Modifier.padding(horizontal = 12.dp).navigationBarsPadding().padding(bottom = 12.dp)`，高 `64.dp`，`RoundedCornerShape(22.dp)`，`colors.surface` 底色 + `colors.divider` 1dp 描边 + 轻投影（`shadow(10.dp, shape, ambientColor = Black.copy(alpha=.18f))`）。
- 每项等宽（`Row` + `weight(1f)`），内部 `Column`：图标 22dp，文字 10.5sp，间距 3dp。
- 选中项：胶囊 `1/3` 宽、高 52dp、`RoundedCornerShape(16.dp)`、`colors.accent.copy(alpha = 0.14f)`；胶囊位置由 `Animatable<Float>` + `spring(dampingRatio = 0.72f, stiffness = 500f)` 滑动；图标与文字用 `animateColorAsState`（220ms，`FastOutSlowInEasing`）从 `textSecondary` 过渡到 `accent`。
- 点击：项内 `interactionSource` 的按下状态驱动 `animateFloatAsState` 到 `0.94f` 的轻微缩放（Q2-A），松手回 1。
- 图标**自绘** `ImageVector`（`ImageVector.Builder` 内联路径：气泡、齿轮、人像），不新增图标依赖；`contentDescription` 用标签文字，满足无障碍。
- 无选中项时（理论上不会出现）胶囊隐藏。

### 6.3 顶层页面过渡（`ui/AppRoot.kt`）

- 顶层三标签之间：`AnimatedContent`，`targetState = tabIndexOf(destination)`，`transitionSpec` 按方向决定 `slideInHorizontally { it / 12 } + fadeIn(tween(180))` / `slideOutHorizontally { -it / 12 } + fadeOut(tween(120))`；方向由前后索引比较得出（`initial` 与 `target` 的比较在 `transitionSpec` 内完成）。
- 进入 `Chat` / `GroupInfo`：`slideInHorizontally { it } + fadeIn()`；返回：`slideOutHorizontally { it } + fadeOut()`。这些目的地**不**参与标签的滑动，避免两层动画叠加。
- 动画时长总量控制在 220ms 内；不阻塞输入（`AnimatedContent` 默认即可）。系统“移除动画”设置下 Compose 动画会自动缩短，不需要额外判断。

### 6.4 聊天列表（`ui/home/HomeScreen.kt`）

- `Scaffold` 去掉 `floatingActionButton` 的“刷新”胶囊（底栏已提供空间，刷新挪到顶栏图标按钮，文字保留无障碍描述）；列表末尾的 `Spacer(88.dp)` 改为 `Spacer(92.dp)`（底栏高 64 + 底部留白 12 + 安全区由 `navigationBarsPadding` 处理，留 92dp 保证最后一行不被遮挡）。
- 顶栏保留“加入 / 创建”，新增“刷新”文本按钮，删除 `onOpenSettings`（改由底栏进入设置）。`HomeScreen` 的签名去掉 `onOpenSettings`，`AppRoot` 同步。
- 空状态文案不变；底栏始终显示，空列表也能切到设置/个人资料。

### 6.5 设置页（`ui/settings/SettingsScreen.kt`）

- 去掉顶栏的“返回”按钮（底栏已可切换），标题保留“设置”。由于设置页不再是详情页，`AppRoot` 里 `Destination.Settings` 的 `BackHandler` 改为回 `Chats`（见 6.1）。
- 内容顺序：账户摘要（现有用户名/编号，改为一行可点击进入个人资料）→ 服务器地址 → 主题 → 关于 → 退出登录 → 声明。
- 「账户」行点击 → `onOpenProfile()`（新增回调），直接切到 `Destination.Profile`。
- 其余行为不变（地址保存、主题、退出、许可弹窗）。

### 6.6 个人资料页（新增 `ui/profile/`）

按 Q1 的选择落地（默认 A：大头像页头 + 两个操作按钮 + 分区卡片）。

**`ProfileViewModel`**（注册进 `OxypeViewModelFactory`）：

```kotlin
data class ProfileUiState(
    val userId: Long? = null,
    val username: String? = null,
    val avatarUrl: String? = null,      // 已按 baseUrl 解析，null = 无头像
    val avatarLimits: AvatarLimits? = null,
    val isLoading: Boolean = false,
    val isUploading: Boolean = false,
    val isRemoving: Boolean = false,
    val message: String? = null,        // 一次性提示（成功/失败）
    val error: String? = null
)

data class AvatarLimits(
    val maxBytes: Long = 3L * 1024 * 1024,
    val maxEdge: Int = 4096,
    val formats: List<String> = listOf("image/png", "image/jpeg", "image/webp", "image/gif")
)
```

行为：

- `init` / `onScreenShown()`：调用 `repository.loadProfile()`；页面每次进入都重新请求（`Destination.Profile` 首次组合时 `LaunchedEffect(Unit)`）。
- `setAvatar(bytes: ByteArray)`：先本地校验（大小、magic bytes、边长），再 `repository.uploadAvatar(bytes)`；成功后更新 `avatarUrl` 并显示“头像已更新”。
- `removeAvatar()`：`repository.removeAvatar()`；成功后 `avatarUrl = null`，显示“头像已移除”。
- 401 → `repository.invalidateCredentials(owner)`（回登录页）；403 → 行内错误“没有权限修改该账号的头像”，**不**登出；400 → 使用服务器文案（例如尺寸/格式原因）。
- 全部动作带 `AuthOwner` 守卫；页面还带 **`profileGeneration`**（每次进入页面自增）守卫，晚到的上传/移除结果不得写进下一次进入的页面。

**Repository 新增**（`data/OxypeRepository.kt`）：

```kotlin
private val _profile = MutableStateFlow<ProfileSnapshot?>(null)
val profile: StateFlow<ProfileSnapshot?>

suspend fun loadProfile(): ProfileSnapshot
suspend fun uploadAvatar(imageBase64: String): ProfileSnapshot
suspend fun removeAvatar(): ProfileSnapshot
fun clearProfile()
```

`ProfileSnapshot`：

```kotlin
data class ProfileSnapshot(
    val userId: Long,
    val username: String,
    val avatarUrl: String?,      // 已解析为可加载的绝对 http(s) URL
    val hasAvatar: Boolean,
    val maxBytes: Long,
    val maxEdge: Int,
    val formats: List<String>
)
```

**缓存绑定**（对应 AGENTS 第 21 条同类要求）：`ProfileSnapshot` 记录 `generation|userId|baseUrl`；`generation`（`authGeneration`）、`userId` 或 `baseUrl` 任一变化都必须丢弃并重新读取。登出时 `clearProfile()`（在 `signOutLocally` 中调用）。`updateBaseUrl` 后不主动清空，但下次进入页面因 `baseUrl` 不匹配而重读。

**存储**：头像图片始终在服务器，客户端不落盘；只在内存里通过 `SenderAvatar` 的图片缓存显示。换头像后 URL 变化（内容哈希），旧缓存自然失效。

### 6.7 协议（`data/oxype`）

**模型**（`OxypeModels.kt`）新增 `UserProfile`：

```kotlin
data class UserProfile(
    val userId: Long,
    val username: String,
    val avatarUrl: String?,   // 原始值（可能相对），解析在 API 层完成
    val hasAvatar: Boolean,
    val maxBytes: Long,
    val maxEdge: Int,
    val formats: List<String>
)
```

**`OxypeApi`** 新增：

```kotlin
suspend fun profile(credentials: AuthCredentials): UserProfile
suspend fun uploadAvatar(credentials: AuthCredentials, imageBase64: String): UserProfile
suspend fun removeAvatar(credentials: AuthCredentials): UserProfile
```

- `profile`：`GET /getUser/{uid}`（带凭据）→ 读 `id`、`username`、`avatarUrl`；再 `GET /getAvatar/{uid}` 合并 `hasAvatar/maxBytes/maxEdge/formats`。后者失败**不**让整页失败：保留 `getUser` 的结果，`hasAvatar` 用 `avatarUrl.isNullOrBlank()` 推断，限制用默认值。
- `uploadAvatar`：`POST /uploadAvatar/{uid}`，body `{ "image": "<base64 或 data URL>" }`；读返回的 `avatarUrl/hasAvatar` 与限制。客户端**先**去掉 `data:` 前缀再发送（服务端两种都接受，去掉可以少几个字节并避免不同实现差异）。
- `removeAvatar`：`POST /removeAvatar/{uid}`（body 只带凭据），返回 `avatarUrl=""`、`hasAvatar=false`。
- 头像 URL 解析复用 `AvatarUrls.resolve(raw, http.baseUrl)`；`/avatar/u1-….png` 相对路径会拼到当前服务器。
- 字段别名沿用现有容错风格：`id/userId/userid`、`name/username`、`avatarUrl/avatar_url/avatar`；`hasAvatar/has_avatar`；`maxBytes/max_bytes`、`maxEdge/max_edge`、`formats`。

**错误分类**：`getUser`/`getAvatar`/`uploadAvatar` 的 404/403/400 均按 `OxypeErrorKind` 处理，不按文案猜。

### 6.8 选择图片与上传前的本地处理（新增 `ui/profile/AvatarPicker.kt`）

- 使用 `ActivityResultContracts.PickVisualMedia(ImageOnly)`（`activity-compose` 已在依赖里，无需新增库）。
- 通过 `ContentResolver.openInputStream` 读入，**先**做两件事：
  1. 流式读取上限 `MAX_BYTES + 1`（3 MB + 1），超过直接提示“图片不能超过 3 MB”；
  2. `BitmapFactory` 两遍解码：读 bounds → 若任一边 > 4096，按 2 的幂 `inSampleSize` 采样；再按最长边缩放到 ≤ 4096。
- 重新编码：优先 PNG（无损、头像常见），若结果仍 > 3 MB 则改用 JPEG 质量 90；仍超限则逐步降质量到 70，最后才报错。
- 编码在 `Dispatchers.Default`；进度用 `isUploading` 显示在头像上（半透明遮罩 + 进度圈）。
- 权限：`PickVisualMedia` 走系统照片选择器，不需要 `READ_MEDIA_IMAGES` 权限，`AndroidManifest.xml` 不改。

**纯 Kotlin 部分**抽成可测对象 `AvatarUploadRules`（无 Android 依赖）：

```kotlin
object AvatarUploadRules {
    const val MAX_BYTES: Int = 3 * 1024 * 1024
    const val MAX_EDGE: Int = 4096
    fun detectFormat(bytes: ByteArray): AvatarFormat?   // PNG/JPEG/WEBP/GIF 魔数
    fun dimensions(bytes: ByteArray, format: AvatarFormat): Pair<Int, Int>?
    fun stripDataUrlPrefix(value: String): String
    fun rejection(bytes: ByteArray): String?            // null = 可上传
}
```

与上游 `AvatarStorage.decode` 的魔数与尺寸解析保持一致（PNG IHDR、JPEG SOF、WebP VP8/VP8L/VP8X、GIF 头）；测试直接复用上游那套字节样本。

### 6.9 个人资料页的展示细节（Q1-A 默认稿）

- 页头：96dp 圆形头像（`SenderAvatar`，`size = 96.dp`，无图时用用户名首字母 + 现有配色），右下角相机徽标（只在无图时显示“+”）。
- 名称：22sp 加粗；副标题“账号编号 N”，12.5sp `textSecondary`。
- 操作行：两个等宽按钮“设置头像”（accent 文字）与“移除头像”（`textSecondary`；无头像时禁用）。
- “账号”卡片：用户名 / 账号编号 / 头像状态（“已设置 · 存储在服务器” / “未设置”）。
- “服务器”卡片：当前地址（只读展示；点按跳到设置页，方便改地址）。
- 页脚小字：“头像由服务器保存，其他资料也同样以服务器为准。”
- 刷新：顶栏右侧“刷新”文本按钮，重新 `loadProfile()`。
- 错误：`SnackbarHost` 显示 `message`/`error`；一次性消费。

### 6.10 状态保存

- `Destination.Profile` 无需额外参数；`navigationStateSaver` 增加 `"profile"` 键，`"home"` 兼容读成 `"chats"`。
- 个人资料的可见字段每次进入都从服务器取，不持久化到 `SharedPreferences`（除现有 `username` 用于设置页摘要）。
- 头像上传中的 `isUploading` 不跨越进程恢复（进程重建后页面重新加载）。

## 7. 边界情形（必须按此实现）

1. **换账号**：A 的头像/用户名不得出现在 B 的资料页。`ProfileSnapshot` 绑定 `generation|userId|baseUrl`，不匹配即当作无数据并重读。
2. **换服务器**：同一账号在新服务器可能不存在或没有头像；`baseUrl` 变化后必须重读，且 `avatarUrl` 用**新** baseUrl 解析。
3. **401 vs 403**：上传/移除收到 401 → 清凭据回登录；403 → 单次错误提示，保留登录（例如试图改他人账号不会发生，但服务端策略变化时必须这样处理）。
4. **图片不合规**：非 PNG/JPEG/WebP/GIF、>3 MB、任一边 >4096 → 本地拦截并给出具体原因，不发请求。
5. **上传失败**：保留原头像，显示错误；不允许把预览图先换成本地图（否则用户会以为已保存）。
6. **无头像**：显示首字母圆盘；“移除头像”禁用；`getAvatar` 返回空字符串是合法状态，不是错误。
7. **头像 URL 加载失败**：保留首字母兜底，不显示破图（沿用 `SenderAvatar` 现有行为）。
8. **轮询**：进入设置/个人资料时聊天页已离开，`ChatViewModel.close()` 在离开聊天时执行，轮询必须停止；切回聊天列表不重新打开任何会话。
9. **聊天中切标签不可达**：底栏在聊天页隐藏，无法从聊天直接跳到设置；这是有意的（返回列表后再切）。
10. **旋转/进程重建**：停在个人资料页时旋转保持页面；进程重建后重新读取资料。
11. **动画期间重复点击**：切换标签用 `/` 的 `AnimatedContent` 不排队；连续点击只保留最后一次目标，状态以 `navigation.destination` 为准。
12. **返回键**：个人资料/设置 → 聊天列表；聊天列表 → 退出应用；聊天 → 列表；群聊信息 → 聊天。

## 8. 测试清单（JVM，`FakeSessionStorage` + `ScriptedTransport`）

导航（扩充 `AppNavigationTest`）：
1. `tabIndexOf` 对三个顶层返回 0/1/2，对聊天/群聊信息返回 -1。
2. `sectionFor` 把 `Chat`/`GroupInfo` 归到 `Chats`。
3. `navigationStateSaver` 对 `"chats"`/`"settings"`/`"profile"` 往返；旧值 `"home"` 读成 `Chats`。
4. 从 `Profile` 收到凭据消失 → `Auth`。
5. `retainedChatSessionId` 对 `Profile`/`Chats`/`Settings` 仍为 0。

协议（新增 `ProfileApiTest`）：
6. `GET /getUser/1` + `GET /getAvatar/1` 合并为完整 `UserProfile`；相对 `avatarUrl` 解析到 baseUrl。
7. `getAvatar` 失败时仍返回 `getUser` 的结果，`hasAvatar` 由 `avatarUrl` 推断。
8. 字段别名：`avatar_url`、`has_avatar`、`max_bytes`、`max_edge` 都能读。
9. `uploadAvatar` 请求体带 `image`，且 `data:image/png;base64,` 前缀被剥离。
10. `uploadAvatar` 的 400 保留服务器原因；403 归类 `FORBIDDEN`；401 归类 `UNAUTHORIZED`。

规则（新增 `AvatarUploadRulesTest`）：
11. 四种格式的魔数识别；HTML 文本被拒绝。
12. PNG/JPEG/WebP/GIF 尺寸解析（复用上游测试样本）。
13. 3 MB 边界：等于上限通过，多 1 字节拒绝；单边 4096 通过，4097 拒绝。
14. `stripDataUrlPrefix` 对无前缀、有前缀、畸形 `data:` 三种输入。

仓库/ViewModel（新增 `ProfileRepositoryTest`、`ProfileViewModelTest`）：
15. 401 会 `invalidateCredentials(owner)` 且清空 profile；403 只设置错误、凭据保留。
16. 换账号后旧 profile 不可见；`loadProfile` 重新请求。
17. 改 baseUrl 后重新请求，头像 URL 用新地址。
18. 上传成功后 `avatarUrl` 更新、`hasAvatar=true`；移除成功后 `avatarUrl=null`。
19. 上传/移除的晚到结果被 `profileGeneration` 丢弃（切页面后不写状态）。

## 9. 留给构建者的判断

- 底栏图标的路径细节、胶囊的精确圆角与投影强度，按画板选定稿在真机上微调 ±2dp。
- 顶栏“刷新”是文字还是图标：沿用现有文字风格（“刷新”），保持与聊天页一致。
- 设置页“账户”行是否显示头像缩略图：默认显示（复用 `SenderAvatar`，24dp）；若拥挤可去掉。
- 上传前是否强制转成正方形：**不必**，显示端 `ContentScale.Crop` 已经处理；转正方形会增加实现面。
- `AnimatedContent` 与 `SaveableStateHolder` 的组合若在真机上出现列表位置跳动，改用 `Crossfade` + 手动 `rememberSaveable`。

## 10. 规模与时间

项目级改动：导航重构 + 3 个页面（底栏、个人资料、设置调整）+ 3 个新协议方法 + 图片选择/编码 + 约 19 个测试 + fixture 补路由 + 文档。

预估 1.5～2 个工作日（含真机走查）。拆分顺序：导航与底栏 → 协议与仓库 → 个人资料页与上传 → 动画打磨 → 文档与 fixture。

## 11. 风险

| 风险 | 应对 |
| --- | --- |
| 上游 `avatarUrl` 有时是相对路径、有时空串 | 已复用 `AvatarUrls.resolve`；空串 → null；测试覆盖 |
| 大头像上传耗时导致 ANR | 解码/缩放/编码全部在 `Dispatchers.Default`，读取有 3 MB 上限 |
| `getAvatar` 的 403（无共同会话）被误当登录失效 | 只按 `kind` 判断，403 不动 token |
| 底栏遮挡列表最后一行 | 列表尾部 92dp + `navigationBarsPadding()` |
| 动画在低端设备掉帧 | 只动 `offset`/`alpha`/`scale`，不动布局；时长 ≤220ms |
| 设置页与个人资料页重复展示账号信息 | 设置页保留一行摘要作为入口，详情只在个人资料页 |

## 12. 本次用到的产物

| 类型 | 路径 / 链接 | 决定了什么 |
| --- | --- | --- |
| 画板 `bottom-bar-and-profile` | `.ryugi/artifacts/artboard/bottom-bar-and-profile/`，链接随消息给出 | Q1 个人资料页排版（A/B/C，推荐 A）、Q2 底栏选中态（A/B/C，推荐 A） |
| 上游源码 | `cao-awa/Oxype@92e7f9c`（临时克隆，未进仓库） | 资料字段、头像接口与存储方式 |
| 本地实测 | `127.0.0.1:12345` 起的上游服务 | 上传/移除头像与 `getAvatar` 的真实返回 |
