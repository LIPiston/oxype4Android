# 回复消息 + 群聊信息 · 设计

日期：2026-11-07
状态：待批准（批准后才开始写代码）

## 1. 意图与缘由

Oxype 服务端新增了 `reply` 消息片段：一条消息可以引用同一会话里的另一条消息，引用内容是**服务端在发送时写入的快照**（发送者、发送者名字、当时的正文）。本客户端目前把不认识的片段类型直接跳过，所以：

- 一条正常回复会丢掉引用块，只剩回复正文——用户看不到"在回谁"；
- 回复只能靠肉眼在上下文里找原话，长会话里基本失效；
- 客户端也**发不出**回复。

同时，客户端目前没有任何地方能读到会话本身的信息。`getSessionInfo` 早就返回名称、简介、所有者、管理员、成员列表和会话头像，但 UI 只把它当成"会话列表补全"，用户点进群聊后无法看到"这个群有谁、谁在管理"。这两件事都要补上，视觉方向参照 Telegram 的引用块与会话信息页（只取交互思路，不搬任何素材或具体数值）。

## 2. 目标

1. 能读：任何带 `reply` 片段的消息在气泡顶部画出引用块（名字 + 最多两行的正文）。
2. 能发：长按菜单、左滑气泡、双击气泡右侧三种入口都能发起回复；输入框上方显示待回复条；发送后引用随消息一起提交。
3. 能跳：点引用块跳回被引用的消息并短暂高亮；不在已加载窗口里时明确告知，不假装成功。
4. 能看：点聊天页标题进入整页"群聊信息"，展示会话头像、名称、成员数、简介、成员列表（群主/管理员标记）、会话编号。
5. 容错优先：服务端引用片段的字段拼写无法在本地验证，缺名字/缺正文/缺发送者时用本地已加载的同 id 消息补齐，绝不画空白引用块，也绝不因为引用片段而丢弃整条消息。
6. 全程不破坏现有不变量：序号空洞、双闭区间、3 秒前台轮询、水位只跟实际取回、删除墓碑、401 登出 / 403 只影响单会话、账号与会话代际守卫。

## 3. 非目标

- **不做消息编辑**。文档提到"编辑一条回复必须重发 `reply` 片段"，客户端没有编辑入口，本次不新增；只在 `docs/protocol.md` 记录这条规则，避免以后实现编辑时丢引用。
- 不做：回复通知、@提及、转发、置顶引用、引用跳转到**未加载**的历史（见 §6.5 的决定）。
- 不做群成员管理（改简介、踢人、设管理员）、会话头像上传、搜索成员。
- 不改服务端，不引入 TDLib/Telegram 协议，不引入任何第三方库。
- 不为 `reply` 或群聊信息新增"仅在真机上能跑"的断言；JVM 测试是准入门槛。

## 4. 约束

| 约束 | 来源 |
| --- | --- |
| 路由字符串、字段名、别名、信封形状只允许出现在 `data/oxype` | `AGENTS.md` §3 |
| `OxypeRepository` 不解析 JSON；ViewModel 不发 HTTP；Composable 只渲染 | 同上 |
| `reply` 必须是 `pieces` 的**第一个**元素，否则服务端 400；被引用消息不在本会话则 404 | 上游文档 `sendMessage` / `editMessage` |
| 发送时调用方**只提供** `messageId`；`text` 由服务端填充，请求里带 text 也会被忽略 | 同上 |
| 引用是快照：原消息被编辑或删除后，引用仍显示当时措辞 | 同上 |
| 消息 id 是会话内递增序号、可因删除出现空洞；`getMessages` 是 `start..end` 双闭区间 | `AGENTS.md` §4 |
| 轮询只在聊天页 RESUMED 时进行，间隔 3 秒 | 同上 |
| 水位只推进到实际取回的范围；发送回读要求目标 seq 确实在响应里 | 同上 |
| 401 → 清凭据回登录页；403 → 单会话错误，绝不清 token | 同上 |
| 任何会挂起的操作都要先取 `AuthOwner` / 绑定代际，回来前校验 | 同上 |
| 未知片段类型跳过；跳过后没有可显示内容 → 整条消息是协议错误 | 现状 `OxypeApi.parseMessage` |
| 没有可用的真实 `reply` 服务端，字段拼写只能按文档推断 | 本次勘察结论 |

**关键未知**：存储态 `reply` 片段的完整字段名。文档只说服务端会复制"发送者、发送者名字与正文"，没有给出示例。因此解析**只把 `messageId` 当硬依赖**，其余字段全部走别名 + 兜底。

## 5. 已经定下的选择（用户决定）

| 编号 | 问题 | 选择 |
| --- | --- | --- |
| Q1 | 引用块长什么样 | **A：左侧竖条引用（Telegram 经典）**。3dp 竖条 + 名字 + 最多两行正文，嵌在气泡内顶部 |
| Q2 | 怎么发起回复 | **B：长按菜单 + 左滑气泡 + 双击气泡右侧**（A 的长按菜单项是其中一部分） |
| Q3 | 群聊信息怎么看 | **A：整页群聊信息**，点聊天页标题进入；`刷新` 留在右上角，并在它左边加一条分隔符，提示标题可点、两个动作彼此独立 |
| — | 点引用块跳回原消息 | 已批准；原消息不在当前已加载窗口里时提示"原消息不在当前已加载的消息里"，不按 id 去拉历史 |
| — | fixture | 给 `tools/ui-fixture` 加 `reply` 支持，便于在模拟器上走查 |

## 6. 规格

### 6.1 模型（`data/oxype/OxypeModels.kt`）

```kotlin
enum class MessagePieceKind { TEXT, MARKDOWN, REPLY }

/**
 * 引用快照。messageId 是跳回目标；其余字段服务端可能缺失。
 */
data class ReplyQuote(
    val messageId: Long,
    val senderId: Long?,      // null：服务端没给，或给了非法值
    val senderName: String?,  // null：没给；空串按没给处理
    val text: String?         // null：没给；空串按没给处理
)

data class ChatMessage(
    val id: Long,
    val senderId: Long,
    val timestampMillis: Long,
    val editedAtMillis: Long,
    val pieces: List<MessagePiece>,   // 只含 TEXT / MARKDOWN，回复片段不进这里
    val reply: ReplyQuote? = null
)
```

- `plainText` 仍是"正文片段拼接"，**不含引用文本**（复制、最后一条预览、气泡正文都因此自动正确）。
- `isUsable` 规则不变：`id > 0 && pieces.isNotEmpty()`。只有 `reply` 片段、没有正文的消息仍按协议错误处理（服务端本身也拒绝空正文）。
- `MessagePiece` 保持 `(kind, text)` 两字段，不塞 `messageId`——引用是消息级概念，不是正文片段。`MessagePieceKind.REPLY` 只用于"识别并跳过"，让解析逻辑可读。

`data/oxype/OxypeModels.kt` 另外新增：

```kotlin
/** 群聊信息页需要的一次性数据。 */
data class GroupInfo(
    val sessionId: Long,
    val name: String,
    val description: String,
    val ownerId: Long,
    val adminIds: List<Long>,
    val members: List<SessionMember>,
    val avatarUrl: String?,
    /** 已按 §6.1 的单一来源算好，UI 不再二次判断。 */
    val isOwner: Boolean,
    val isAdmin: Boolean
) {
    val memberCount: Int get() = members.size
}
```

`SessionDetail` 增加 `adminIds: List<Long> = emptyList()` 与 `avatarUrl: String? = null`。

**注意（勘察结论，不是假设）**：`parseSessionDetail` 目前**完全不读** `admins`，也**不读**会话级 `avatarUrl`——它只读成员记录里的头像；admin 列表今天只从批量 `getSessions` 里解析（代码注释也写着 "The detail route has no admin list"）。所以这是新增协议面，不是"顺手读进来"：

- `admins` 别名沿用 `parseSessions` 已用的那组：`admins` / `adminIds` / `admin_ids`。
- 会话级头像别名与成员头像同一组（`avatarUrl` / `avatar_url` / `avatar` / `photoUrl` / `photo_url` / `photo`），并同样走 `AvatarUrls.resolve(raw, baseUrl)`，只放行 http(s)。
- `POST /getSessionInfo` 与 `GET /getSession` 两条路径都读；任一缺失就是空列表 / `null`，不报错。
- `isComplete` 的定义不变（`ownerId > 0 || members.isNotEmpty()`），admin 与头像不参与完整性判断。

**"我是不是群主/管理员"只有一个来源**：优先用 `ownerId`/`adminIds` 与当前账号**本地计算**，服务端的 `isOwner`/`isAdmin` 只在 `ownerId`/`adminIds` 缺失时兜底。理由是同一个页面上头部徽标与成员行标签必须一致：如果头部读服务端布尔、成员行读 id 列表，两者不一致时会出现"标题说我是管理员、名单里没有徽标"。计算规则（实现时修正：布尔兜底必须先折算成 id 再统一判断，否则同一条「id 缺失」分支下头部与成员行仍会不一致）：

```kotlin
// 先把布尔-only 的答案折进 id
val ownerId = if (detail.ownerId > 0L) detail.ownerId
              else if (detail.isOwner) currentUserId else 0L
val adminIds = if (detail.ownerId > 0L || detail.adminIds.isNotEmpty()) detail.adminIds
               else if (detail.isAdmin) listOf(currentUserId) else emptyList()

val iAmOwner = ownerId == currentUserId
val iAmAdmin = iAmOwner || adminIds.contains(currentUserId)
```

成员行的角色同样只读 `ownerId`/`adminIds`（`GroupInfo.roleOf`），所以两处永远同源。

### 6.2 解析（`data/oxype/OxypeApi.kt`）

`parseMessage` 的片段循环改成：

1. 先识别 `type`：
   - `reply` → 交给 `parseReplyPiece(piece)`，**不加入** `pieces`；
   - `markdown`/`md` → `MARKDOWN`；`text`/缺失 → `TEXT`；
   - 其他 → 跳过（现状）。
2. `parseReplyPiece` 规则：
   - `messageId` 读取别名 `messageId` / `message_id` / `id` / `seq`，必须 `>= 1`；否则**整块忽略**（不报错、不丢正文）。
   - `senderId` = `sender` / `senderId` / `sender_id`，非法或缺失 → `null`（不用 0 冒充）。
   - `senderName` = `senderName` / `sender_name` / `username` / `name`，`trim()` 后为空 → `null`。
   - `text` = `text` / `content` / `value`，`trim()` 后为空 → `null`。
   - 出现多个 reply 片段时**只取第一个**（服务端只允许首位，其余按容错忽略）。
   - 位置不做限制（存储态若把 reply 放在后面也照样识别），但**发送**时永远放首位。
3. `pieces` 为空仍抛 `没有可显示的内容`，错误文案不变。

解析不得因为 reply 片段缺字段而让整次 `getMessages` 变 `PROTOCOL`——只有正文片段缺失（现状）才是协议错误。

### 6.3 发送（`data/oxype/OxypeApi.kt` + `OxypeRepository.kt`）

```kotlin
suspend fun sendText(
    credentials: AuthCredentials,
    sessionId: Long,
    text: String,
    replyTo: Long? = null
): Long?
```

- `replyTo == null`：请求体与今天完全一致（单 `{"type":"text","text":…}` 片段）——回归测试必须守住。
- `replyTo != null`：`pieces = [{"type":"reply","messageId":<replyTo>}, {"type":"text","text":<text>}]`，**reply 片段不带 text**。
- 返回值与软拒绝规则不变：`seq <= 0` → `null`（"你可能已不在该会话中"）。
- 404（被引用消息不在本会话）由 `OxypeHttpClient` 归为 `NOT_FOUND`，仓库把服务端文案放进错误条；不清 token、不动水位。

仓库签名：

```kotlin
suspend fun sendText(text: String, replyTo: Long? = null): Boolean
```

内部除 `api.sendText(..., replyTo)` 外**全部逻辑不变**：`isSending` 置位/清除、`refreshTail(seq-4..seq)`、`requirePresent`、`isContiguousWithWatermark` 才 `acknowledgeSend`、失败不动水位。

### 6.4 UI：气泡里的引用块（Q1-A）

新增 `ui/components/QuoteBlock.kt`（纯展示 + 一个点击回调）：

- 外层 `Row`/`Box`：`border-left 3.dp`（`colors.accent`），左内边距 8dp，上下 2dp，底部与正文 4dp 间距；整块可点（有 ripple），点按区域覆盖整块宽度。
- 第一行：引用者名字，`labelMedium`、`colors.accent`、单行省略号。
- 第二行：引用正文，`bodySmall`、`colors.textSecondary`、`maxLines = 2`、`TextOverflow.Ellipsis`。正文为空时这一行不画（不留空行）。
- `MessageBubble` 在发送者名字之下、正文之上插入该块；未回复的消息完全不变。
- 引用块颜色不随气泡左右侧变化（同一套 token），深色主题下同样可读。

名字与正文的取值是**纯函数**，放 `ui/chat/QuotePresentation.kt`（便于 JVM 测试）：

```kotlin
internal fun quoteAuthorName(
    quote: ReplyQuote,
    participants: Map<Long, SessionMember>,
    currentUserId: Long
): String
// 优先级：quote.senderId == 当前用户 → "我"
//        → quote.senderName
//        → participants[senderId]?.username
//        → "用户 N"（senderId 有效） / "未知用户"
//
// 空串/纯空白一律当作"没给"，继续往下走。
//
// 「自己」先判，而不是先取快照名：服务端快照会写入发送者当时的昵称，若快照名优先，
// 引用自己的消息会显示昵称而违反 §7「引用的是自己发的消息 → 显示我」。

internal fun quoteBodyText(quote: ReplyQuote, local: ChatMessage?): String
// quote.text ?: local?.plainText ?: ""（空串 = 不画正文行）

internal fun resolveQuote(
    quote: ReplyQuote,
    participants: Map<Long, SessionMember>,
    loaded: Map<Long, ChatMessage>,
    currentUserId: Long
): QuoteView
```

**为什么自己发的消息在引用块里显示"我"，而气泡外不发名字**：气泡外的发送者标签本来就不画（自己的消息不需要标注作者），但引用块是在回答"我在回谁"，省略作者会让块里第一行变成空的。所以这两处规则不同，是有意的：引用块永远有作者行，自己就是"我"。一对一聊天里成员目录可能为空，此时退回 `用户 N`——与气泡外对发送者的兜底保持一致。

`QuoteView(author: String, body: String, targetMessageId: Long, isJumpable: Boolean)`：`isJumpable = loaded.containsKey(quote.messageId)`（引用块点击时再决定弹提示还是滚动，见 §6.6）。

### 6.5 UI：发起回复（Q2-B）

`ChatViewModel` 新增：

```kotlin
data class ReplyTarget(
    val messageId: Long,
    val authorName: String,   // 展示用，发送时不需要
    val body: String          // 已截断到 200 字的预览
)

// ChatUiState 新增
val replyTarget: ReplyTarget? = null
```

动作：

- `beginReply(message: ChatMessage)`：用 `senderDisplayName(message.senderId, participants)` 与 `message.plainText` 组装目标；同一会话内重复调用直接覆盖。
- `cancelReply()`：清空。
- `send()`：把 `replyTarget?.messageId` 传给 `repository.sendText(text, replyTo)`；成功后清空 `replyTarget`，**失败保留**（用户可以重试或取消）。
- `open()` 切到另一个会话、`close()`、`resetForSignedOut()`：`replyTarget = null`，与 `draft` 一起清。所有回调继续走 `bindingGeneration` 守卫，晚到的发送结果不得清掉新会话的引用目标。

`ChatScreen` 的三种入口（全部映射到 `viewModel::beginReply`）：

1. **长按菜单**：`MessageActionsDialog` 在"复制文本"上方新增"回复"，仍在同一 `AlertDialog` 内。
2. **左滑气泡**：水平拖动超过 64.dp 且以横向为主时触发；拖动过程中气泡最多左移 72.dp（带阻尼），松手回弹。右侧显示圆形提示（返回箭头图标）随位移淡入。竖直滚动优先：拖动位移以纵向为主时不触发，也不拦截列表滚动。
3. **双击气泡右侧**：双击落点在气泡宽度的右侧 40% 时触发；落点在左侧时双击无动作（给将来的"点赞/回应"留位，不在本次实现）。

实现方式：气泡外层改用 `Modifier.pointerInput(message.id) { detectTapGestures(onTap = {}, onDoubleTap = { offset -> … }, onLongPress = { … }) }`，另用一个 `pointerInput(message.id)` 处理水平拖动。两者分工，长按与双击不会互相吞掉。**这是本次唯一有真机风险的地方**：若在模拟器上出现"滑动手势吃掉点击"或"长按不触发"，保留长按菜单这一条人工兜底，并当场汇报，不把有缺陷的手势当成已完成。

输入框上方新增回复条（`ReplyPreviewStrip`）：

- 背景 `colors.surfaceRaised`，左侧 3dp `colors.accent` 竖条，圆角 10dp，关闭按钮是右侧 22dp 圆形 ✕。
- 第一行 `回复 <作者>`，`labelMedium`、`colors.accent`，单行省略。
- 第二行引用正文，`labelSmall`、`colors.textSecondary`，单行省略。
- 位置：`MessageComposer` 内部、输入框之上；`imePadding` 继续由外层处理。

### 6.6 UI：跳回原消息

- 引用块可点且 `loaded` 里有该 id：`LazyListState.animateScrollToItem(index)`，随后把该消息 id 设为 `flashMessageId`，`MessageBubbleWithActions` 用 `animateColorAsState` 把气泡背景在 1.2 秒内提亮一次（浅色：`outgoingBubble/incomingBubble` 与 `colors.accent` 18% 混合；深色同理），然后自动清除。
- 索引计算是纯函数（`ui/chat/ChatListItems.kt`）：

```kotlin
internal fun lazyIndexForMessage(items: List<ChatListItem>, messageId: Long): Int?
// 返回 LazyColumn 里的下标：1 + items.indexOfFirst { it is Message && it.message.id == messageId }
// 找不到返回 null；1 是列表顶部的 `bottom-spacer`
```

- `lazyIndexForMessage == null`（原消息在窗口外或已被删除）：用 `SnackbarHost` 展示"原消息不在当前已加载的消息里"，2 秒后消失。**不按 id 单独去拉历史**：id 有空洞，"按 id 取一条"没有可靠路由，硬做会引入新的同步路径。
- 原消息已被删除（墓碑）也算"不在当前已加载的消息里"。
- 点击引用块**不会**触发气泡的长按/双击逻辑。

### 6.7 UI：群聊信息页（Q3-A）

导航：

```kotlin
internal sealed interface Destination {
    …
    data class GroupInfo(
        val sessionId: Long,
        val title: String,
        val subtitle: String
    ) : Destination
}
```

- 聊天页标题区域整体可点（`Modifier.clickable`，含 ripple），副标题保持不变；点击 → `Destination.GroupInfo(sessionId, title, subtitle)`。
- 右上角 `刷新` 前加一条 `VerticalDivider`（1dp 宽、20dp 高、`colors.divider`），让"标题/刷新"两个点击区有视觉间隔。
- 返回键：`GroupInfo → Chat`。**进入群聊信息时不清空聊天状态**（不调 `chatViewModel.close()`），返回后沿用同一会话的已加载消息；`ChatScreen` 重新组合时走 `open()` 的"同一会话"捷径，轮询由 `repeatOnLifecycle` 自动恢复。
- `navigationStateSaver` 增加 `"groupinfo"`：存 `sessionId`、`title`、`subtitle`。恢复后深链直接落在群聊信息页；返回仍回聊天页。
- 登出/吊销 token 时沿用现有规则（`destination !is Auth → Auth`）。

`Destination.GroupInfo` 带 `title`/`subtitle` 的用途只有一个：**加载占位**。点进页面的瞬间用聊天页已知的名称与"N 位成员"先画头部，数据回来后被服务端结果替换；加载失败时也仍然是这两个值，至少不是一片空白。它们必须进 `navigationStateSaver`，否则进程死亡恢复后占位为空。

数据：

```kotlin
// OxypeRepository
suspend fun loadGroupInfo(sessionId: Long): GroupInfo
```

1. 取 `AuthOwner`（没有凭据 → 抛 `UNAUTHORIZED`，与 `refreshSessions` 一致）。
2. `api.sessionInfo(credentials, sessionId)`：已有路由，按 §6.1 补读 `admins` 与 `avatarUrl`。
3. `api.sessionMembers(credentials, sessionId)`：已有路由，取名字/头像。
4. 合并：以 `sessionInfo` 的成员为准（它才带 owner/admin），成员顺序保持服务端给的顺序；`sessionMembers` 只补 `username`/`avatarUrl`。补不到就用 `用户 <id>` + 首字母圆盘。
5. 守卫：每次挂起后校验 `isCurrentAuth(owner)`；401 → `invalidateCredentials(owner)` 并抛错；403 → 原样抛给 ViewModel，**不清 token**。
6. `sessionMembers` 失败不致命：返回只有 id 的成员列表（名字为 `用户 <id>`）。`sessionInfo` 失败才是整体失败。

`GroupInfoViewModel`（新增，注册进 `OxypeViewModelFactory`）持有：

```kotlin
data class GroupInfoUiState(
    val sessionId: Long = 0L,
    val isLoading: Boolean = true,
    val info: GroupInfo? = null,
    val error: String? = null,
    val currentUserId: Long = 0L
)
```

`open(sessionId)` 用 `bindingGeneration` 守卫晚到结果；`refresh()` 重跑；`consumeError()` 清错误。

`GroupInfoScreen`（新增 `ui/groupinfo/`）：

- `Scaffold` + `TopAppBar`：返回按钮 + 标题"群聊信息"，无右上动作。
- 头部卡片（`colors.surface`）：84dp 圆形头像（复用 `SenderAvatar`，无图时用会话名首字母 + 由名称决定的配色；服务端没给会话头像时退回到"群聊"两字的固定色盘）、19sp 名称、`群聊 · N 位成员` 副标题。`iAmOwner` / `iAmAdmin`（按 §6.1 的单一来源计算）为真时在副标题追加 `· 我是群主` / `· 我是管理员`。
- "简介"卡片：`description` 为空时显示 `未填写简介`（`colors.textSecondary`），不留空白卡。
- "成员 N"卡片：每行 34dp 头像 + 名字 + 角色标签。角色优先级：`ownerId == userId` → `群主`（accent 底）；`adminIds.contains(userId)` → `管理员`（`surfaceRaised` 底）；其他人无标签。自己那行名字后缀 `（我）`。头部徽标与本表使用同一份 `ownerId`/`adminIds`，不会互相矛盾。
- "会话编号"卡片：`sessionId`，等宽字体可选，方便报障。
- 加载中：居中 `CircularProgressIndicator`；失败：居中文案 + "重试"按钮（复用 `colors.danger`，不弹对话框）。

### 6.8 fixture（`tools/ui-fixture/fixture_server.py`）

- 每条消息允许带 `reply` 字段：`{messageId, sender, senderName, text}`。
- `sendMessage` 读取 `pieces`：
  - 校验 `reply` 只能出现在第 0 位，否则 `400 {"error_message":"reply 片段必须在首位"}`；
  - 引用目标不存在于本会话 → `404 {"error_message":"Message N to reply to was not found"}`；
  - 存入快照：从目标消息复制 `sender`、`senderName`（查 `USERS`）、`text`；目标后续被删除也不影响已存引用；
  - `reply` 片段的 `text` 若客户端误传也忽略。
- `_message_payload` 输出 `pieces`：有引用时 `[{"type":"reply","messageId":…,"sender":…,"senderName":…,"text":…}, {"type":"text","text":…}]`；否则与今天一致。
- `_session_detail` 补充 `admins` 与会话级 `avatarUrl`（fixture 里给会话 42 一个头像路径），否则群聊信息页的管理员标签与群头像是空的、无法走查。`isOwner`/`isAdmin` 仍按登录用户计算，但客户端以 id 为准（见 §6.1）。
- 预置数据加 2 条回复（一条引用他人、一条引用自己），这样一打开会话就能看到引用块与跳转，不依赖先发消息。
- fixture 仍然**不代表真实服务**：它没有 `register`/`createSession`/`joinSessionByUuid`，也不验证未知字段名的容错。

### 6.9 文档

- `docs/protocol.md`：
  - 第 6 节消息结构：`reply` 片段规则（必须首位、只传 `messageId`、服务端写快照、`editMessage` 整体替换因此必须重发 reply）、客户端容错（缺 `messageId` 忽略该块；缺名字/正文用本地消息兜底）。
  - 第 5.10 节：请求体两种形状（纯文本 / 回复），400 与 404 的含义。
  - 5.7：补 `admins`、`avatarUrl` 的读取，新增 `loadGroupInfo` 的合并与失败规则。
  - 第 9 节 fixture 差异：新增 `reply` 能力与两条预置回复。
- `docs/architecture.md`：新增两条路径（"发送回复"、"打开群聊信息"），`ChatUiState.replyTarget`、`GroupInfoViewModel`、`Destination.GroupInfo` 入图。
- `AGENTS.md` §3 文件地图加 `ui/groupinfo/`、`ui/components/QuoteBlock.kt`、`ui/chat/QuotePresentation.kt`；§4 增加一条不变量："reply 是快照且必须首位，唯一硬依赖是 `messageId`"；§8 把"回复/群聊信息"移入已实现。

## 7. 边界情形（必须按此实现）

| 情形 | 行为 |
| --- | --- |
| reply 片段缺 `messageId` | 忽略引用块，正文照常显示，不报协议错误 |
| reply 片段只有 `messageId` | 引用块名字取本地成员目录，正文取本地同 id 消息；都没有则只画名字、不画正文行 |
| 引用的消息已被删除 | 快照文本仍在（服务端保证），跳转时提示"原消息不在当前已加载的消息里" |
| 引用的是自己发的消息 | 引用块作者行显示"我"（气泡外的发送者标签仍不画，两者规则不同，原因见 §6.4） |
| 回复发送返回 404 | 错误条显示服务端文案，草稿保留、回复目标保留、水位不动 |
| 回复发送返回 `seq:-1` | 现有软拒绝文案，回复目标保留 |
| 发送过程中切换会话 | 晚到结果被 `bindingGeneration` 拦下，不污染新会话 |
| 发送成功但回读缺该 seq | 水位不动，下一轮轮询继续覆盖它（现状规则） |
| 引用块在深色主题下 | 用 token，不硬编码颜色 |
| 群聊信息 403 | 页面显示错误 + 重试，token 保留 |
| 群聊信息成员目录失败 | 展示 `用户 N` 占位，页面不整体失败 |
| 群聊信息页从进程死亡恢复 | 导航恢复 `groupinfo`，重新拉一次数据 |
| 进入群聊信息再返回 | 聊天消息不重新加载、不清空、轮询恢复 |

## 8. 测试清单（JVM，`FakeSessionStorage` + `ScriptedTransport`）

新增/扩展的测试类：

1. `OxypeApiReplyTest`（`data/oxype`）
   - 存储态含 reply 片段 → `ChatMessage.reply` 正确，`plainText` 不含引用文本。
   - 缺 `sender`/`senderName`/`text` → 对应字段为 `null`，消息仍可解析。
   - 缺 `messageId` → `reply == null`，正文保留。
   - 多个 reply 片段 → 取第一个。
   - reply 在非首位（存储态）→ 仍识别（容错）。
   - 只有 reply、没有正文 → 仍报"没有可显示的内容"（保持现状）。
   - 发送（有 replyTo）请求体：`pieces[0].type == "reply"`、`messageId` 正确、**没有** `text` 字段；`pieces[1]` 是 text。
   - 发送（无 replyTo）请求体与旧格式逐字节一致（单 text 片段）。
2. `ChatReplyTest`（`data`）
   - `repository.sendText(text, replyTo)` 成功 → 回读窗口仍按 `seq-4..seq`，消息进 store。
   - 回复目标 404 → 返回 `false`，`ChatSnapshot.error` 含服务端文案，**token 未清**。
   - 编辑后的回复（`editedAt` 更大、pieces 仍含 reply）合并后引用不丢。
3. `QuotePresentationTest`（`ui/chat`）
   - 名字兜底链（自己 → "我" → 快照名 → 目录名 → `用户 N` → `未知用户`）。
   - 正文兜底链；空正文不画行；`isJumpable` 判定。
4. `ChatListLayoutTest`（扩展）
   - `lazyIndexForMessage` 命中/未命中（含空格与日期分隔行的偏移）。
5. `ChatViewModelTest`（扩展）
   - `beginReply` / `cancelReply`；发送成功清目标；失败保留；切会话、`close()`、签出清目标；晚到结果不改新会话。
6. `GroupInfoTest`（`data`）
   - `admins` + `avatarUrl` 解析（含蛇形别名；缺失 → 空列表 / `null`）。
   - 成员目录合并（有目录 / 无目录 / 目录 403）。
   - 角色优先级：`ownerId` 命中 → 群主；`adminIds` 命中 → 管理员；两者都没命中 → 无标签。
   - **矛盾输入**：服务端 `isAdmin = true` 但 `adminIds` 不含当前账号 → 以 id 为准（不显示管理员），保证头部与成员行一致。
   - `sessionInfo` 403 → 抛 `FORBIDDEN` 且不清凭据；401 → 清凭据。
7. `AppNavigationTest`（扩展）
   - `GroupInfo` 保存/恢复；登出后回到 Auth。
8. `GroupInfoViewModelTest`
   - 加载成功/失败/重试；绑定代际守卫。

门槛：`./gradlew.bat --offline :app:testDebugUnitTest`，XML 统计 `failures=0 errors=0`，用例数不少于现有 257 条（预计新增 25–35 条）。另外 `:app:assembleDebug` 与 `:app:lintDebug` 必须通过。

## 9. 留给构建者的判断

- 手势组合的具体阈值（64dp/72dp/40%）可微调，但三种入口都要在模拟器上实际走通。
- 高亮动画的时长与色值比例可微调，只要在浅色/深色下都看得出来且不刺眼。
- 群聊信息页的卡片间距、字号以现有 `OxypeColors` 与 Material 3 排版为准，不引入新字体。
- 引用块在极窄气泡（短消息 + 长引用）下的换行表现，以两行截断为底线。
- 是否把"复制文本"菜单顺序调整（回复放最上）由构建者按手感决定，但"回复"必须在菜单里。

## 10. 规模与时间

规模：**项目**（跨协议层、仓库、ViewModel、两个新界面、fixture、文档、测试）。

预计 5–8 小时：

| 阶段 | 预计 |
| --- | --- |
| 模型 + 解析 + 发送 + 单元测试 | 1.5–2h |
| 气泡引用块 + 三种手势 + 回复条 | 1.5–2.5h |
| 跳转/高亮 + 快照与导航守卫测试 | 1–1.5h |
| 群聊信息页 + ViewModel + 测试 | 1.5–2h |
| fixture + 模拟器走查 + 文档 | 1h |

## 11. 风险

1. **存储态 reply 字段名不可验证**（无公开服务端）。缓解：只硬依赖 `messageId`，其余全部可空 + 本地兜底。如果真实服务用了完全不同的名字（例如 `ref`），引用块会退化成"只有名字或完全没有"，**不会**丢正文，也不会报错——届时只需改 `parseReplyPiece` 一处。
2. **手势冲突**（左滑 vs 列表滚动 vs 长按）。缓解：水平/垂直主轴判定 + 长按菜单兜底 + 模拟器实测；若左滑实现导致滚动异常，先保住长按与双击。
3. **跳转只在已加载窗口内**。长会话里较老的引用会提示失败。这是刻意的取舍：客户端没有"按 id 取单条"的路由，id 又有空洞，新增一条同步路径的代价高于收益。
4. **群聊信息页会额外发 2 个请求**（`getSessionInfo` + `getSessionUsers`）。只在进入页面时发，不做轮询。

## 12. 本次用到的产物

| 类型 | 名称/ID | 路径或链接 | 决定了什么 |
| --- | --- | --- | --- |
| 画板 | `reply-and-group-info`（artifact id 见同目录 `.artifact-id`） | `.ryugi/artifacts/artboard/reply-and-group-info/`；链接 `http://localhost:27319/artboard/haMkeXd4Ffj6F0kQxFXAt` | 第一轮三个问题共 7 块稿 |
| 验收画板 | `verify-reply-group-info` | `.ryugi/artifacts/artboard/verify-reply-group-info/`；链接 `http://localhost:27319/artboard/mhot9k3XfrIlftjEbh1Fv` | 模拟器实测截图：引用块、双击边界、群聊信息页、往返保留滚动位置 |
| 画板页 | `q1-a` | `.../pages/design/q1-a.html` | **已选**：左侧竖条引用（3dp 竖条 + 名字 + 两行正文） |
| 画板页 | `q2-b` | `.../pages/design/q2-b.html` | **已选**：长按菜单 + 左滑 + 双击右侧；输入框上方回复条 |
| 画板页 | `q3-a` | `.../pages/design/q3-a.html` | **已选**：整页群聊信息（头像、名称、成员数、简介、成员角色、会话编号） |
| 参考帧 | `QQ20261106-184330.gif` / `QQ20261106-193334-HD.mp4`（用户提供，未入库） | 本地 `Downloads` | 桌面端 Oxype 客户端的引用块、回复条、右键菜单视觉参考 |

未选方案（记录以免重复讨论）：`q1-b` 填充圆角引用块、`q1-c` 单行紧凑引用、`q2-a` 只做长按菜单、`q3-b` 底部弹出面板。
