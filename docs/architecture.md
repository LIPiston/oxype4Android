# 架构与数据流

本文描述客户端当前的分层、各层职责、以及九条主要路径（启动认证恢复、会话列表、打开聊天、首屏历史、轮询、发送、引用回复、删除、群聊信息）的实际数据流。所有类名、常量都可以在代码里检索到。

## 1. 分层总览

```
┌──────────────────────────────────────────────────────────────┐
│ Compose 界面                                                  │
│ AuthScreen / HomeScreen / ChatScreen / GroupInfoScreen /       │
│ SettingsScreen                                                │
│ components: MessageBubble, QuoteBlock, SenderAvatar, …         │
└───────────────────────────┬──────────────────────────────────┘
                            │ 事件向上、StateFlow 向下
┌───────────────────────────▼──────────────────────────────────┐
│ ViewModel                                                     │
│ AuthViewModel / HomeViewModel / ChatViewModel /               │
│ GroupInfoViewModel / SettingsViewModel                        │
│ 绑定代际守卫、动作发起、把 Repository 状态映射为 UI 状态        │
└───────────────────────────┬──────────────────────────────────┘
                            │ suspend 调用 + StateFlow
┌───────────────────────────▼──────────────────────────────────┐
│ OxypeRepository（唯一状态源）                                   │
│ credentials / authState / sessions / chat / sessionExpired     │
│ 编排认证、列表、消息同步、轮询生命周期、群聊详情、错误归一       │
└──────────┬───────────────────────────────┬───────────────────┘
           │                               │
┌──────────▼─────────────┐   ┌─────────────▼───────────────────┐
│ MessageSyncCoordinator │   │ data/oxype 协议层                │
│ 单会话消息存储          │   │ OxypeApi → OxypeHttpClient       │
│ 窗口/水位/墓碑/去重      │   │ → HttpTransport                  │
└────────────────────────┘   └─────────────┬───────────────────┘
                                           │
                              ┌────────────▼───────────────┐
                              │ HttpUrlConnectionTransport │
                              └────────────────────────────┘
```

依赖方向严格向下。`MessageSyncCoordinator` 与 `OxypeHttpClient` 之间没有依赖：Repository 把 API 返回的 `List<ChatMessage>` 交给 coordinator，再把 coordinator 的结果写进 `ChatSnapshot`。

各层职责：

| 层 | 负责 | 不负责 |
| --- | --- | --- |
| `data/oxype/OxypeHttpClient` | base URL 归一化（`normalizeBaseUrl`）、POST 把 `userid`+`token` 放进 JSON body、GET 放进 query、Kalmia `data` 解包、状态码→`OxypeErrorKind` | 路由语义、缓存、消息顺序 |
| `data/oxype/OxypeApi` | 每个路由的 path、请求字段、响应解析与别名、把"读不懂"升级为 `PROTOCOL` | 重试、状态发布 |
| `data/oxype/OxypeJson` | 蛇形/驼峰别名、`data` 信封、宽松的布尔/数字/字符串读取、错误文案提取 | 任何业务判断 |
| `data/sync/MessageSyncCoordinator` | 单会话消息的升序存储、去重、`editedAt` 修订覆盖、窗口计算、水位推进、墓碑 | 网络、协商、UI |
| `data/OxypeRepository` | 认证生命周期、列表刷新、打开/关闭聊天、首屏、翻页、发送回读、引用目标、删除、群聊详情合并、轮询循环、跨会话/跨账号守卫 | JSON 解析、Compose |
| ViewModel | 绑定 UI 与会话、把错误转成可显示的文案、保留草稿与回复目标、暴露各自的 UiState（`AuthUiState`/`HomeUiState`/`ChatUiState`/`GroupInfoUiState`/`SettingsUiState`） | 直接发 HTTP、决定协议细节 |
| Compose | 渲染、手势、导航事件 | 任何状态所有权 |

`AppContainer` 是手工组合根：一个 `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`、一个 `SessionStore`、一个 `OxypeHttpClient`、一个 `OxypeRepository`；`OxypeViewModelFactory` 按类型返回五个 ViewModel。没有 DI 框架，也没有第二个 Repository 实例。

引用相关的展示规则同样抽成纯函数：`ui/chat/QuotePresentation.kt`（作者名/正文/可跳转判定）、`ui/chat/ReplyGestures.kt`（左滑与双击的几何判定）、`ui/chat/ChatListItems.kt#lazyIndexForMessage`（消息 id → LazyColumn 下标）。

## 2. 状态所有权与流向

Repository 暴露五个 `StateFlow`：

| 属性 | 类型 | 含义 |
| --- | --- | --- |
| `credentials` | `AuthCredentials?` | 当前登录；`null` 表示没有可用凭据 |
| `authState` | `AuthRestoreState` | `CHECKING` / `READY` / `SIGNED_OUT`，区分"还没校验"和"已登出" |
| `sessions` | `List<SessionSummary>` | 最近一次成功解析的会话列表 |
| `chat` | `ChatSnapshot?` | 当前打开会话的权威快照；`null` 表示没有打开会话 |
| `sessionExpired` | `Boolean` | 本地 token 被服务端拒绝的一次性提示 |

`ChatSnapshot` 字段：`sessionId`、`messages`（升序）、`participants`（`Map<Long, SessionMember>`）、`isLoadingInitial`、`isLoadingOlder`、`isSending`、`reachedTop`、`error`、`isPolling`。

流向是单向的：

```
HttpTransport → OxypeHttpClient → OxypeApi → Repository 编排
    → MessageSyncCoordinator 合并 → Repository.publish(...) → ChatSnapshot（StateFlow）
    → ChatViewModel.applySnapshot → ChatUiState（StateFlow）→ ChatScreen 渲染
```

界面读状态用 `collectAsStateWithLifecycle()`（五个 Screen 都是）。界面写状态只通过 ViewModel 的方法。

`ChatViewModel` 额外维护了几项 **UI 专属** 状态，它们不在 Repository 里：`draft`、`replyTarget`（`ReplyTarget(messageId, authorName, body)`）、`title`/`subtitle`、`currentUserId`、`isGroup`（由 `isGroupConversation` 从 `participants` 与 `messages` 推导）。

## 3. 路径一：启动认证恢复

触发：`AppRoot` 的 `LaunchedEffect(Unit)` 调 `repository.restoreSession()`。

```
restoreSession()
├─ 已经是 READY 且有凭据 → 直接返回 true（旋转重建不重复校验）
├─ sessionStore.credentials() == null → authState = SIGNED_OUT，返回 false
├─ http.updateBaseUrl(sessionStore.baseUrl)
├─ 记录 generation = authGeneration
├─ api.isAlive(stored)  →  POST /isAlive/{userid}，body 只有 token
│    ├─ true / 无法判断（网络、5xx、取消以外的异常）→ alive = null 或 true
│    ├─ 401 → alive = false
│    └─ CancellationException → 重新抛出（不据此登出）
├─ generation 已变（期间有人登录）→ 返回当前是否有凭据
├─ alive == false → signOutLocally(expired = true)，返回 false
└─ 否则：sessionExpired = false；authGeneration++；credentials = stored
         → authState = READY（若发布过程中被观察者登出则为 SIGNED_OUT）
```

配套状态机 `NavigationState`（`AppNavigation.kt`）只做两件事：

- `credentialsChanged(present)`：恢复完成后，凭据出现 → 若当前在 Auth 则去 Home；凭据消失 → 任何已登录目的地回 Auth。登录、登出、服务端吊销共用这一条信号。
- `restoreFinished(signedIn)`：结束 `restoring` 占位，选择初始目的地。

`restoring` 是**进程级**门闩：`navigationStateSaver` 只持久化目的地，恢复时用当前 `repository.authState.value == AuthRestoreState.CHECKING` 重新决定 `restoring`。这样进程重建会先显示 `RestorePlaceholder`（不闪登录页），而旋转沿用已解析的结果。

`ChatViewModel.open` 在真正请求前还有一道就绪门：`repository.credentials.value != null || repository.authState.first { it != CHECKING } == READY`，避免进程重建时用未校验的 token 请求并弹出错误的"请先登录"。

## 4. 路径二：会话列表

触发：`HomeViewModel` 在 `init` 中订阅 `repository.credentials`，一旦有凭据就 `refresh()`。**不在 init 里直接加载**，因为 ViewModel 早于恢复完成创建。

```
HomeViewModel.refresh()
└─ repository.refreshSessions()
   ├─ owner = authOwner()（没有则抛 UNAUTHORIZED "请先登录"）
   ├─ api.joinedSessionIds(owner)        GET /getJoinedSessions/{userid}  → List<Long>
   ├─ isCurrentAuth(owner) 检查
   ├─ api.sessionDetails(owner, ids)
   │   ├─ POST /getSessions {sessions:[…]} 批量（这条调用不带凭据，见 protocol.md 5.6）
   │   ├─ 每行 isComplete == false（ownerId<=0 且 memberIds 为空）→ POST /getSessionInfo/{id}
   │   │   失败若不是 401 则记录首个失败、继续；仍保留只有名字的批量行
   │   ├─ 401 直接抛出（会让整个应用回登录页）
   │   └─ 全部失败才抛第一个失败，避免把网络故障显示成"空列表"
   ├─ isCurrentAuth(owner) 检查
   └─ sessions = 解析结果
```

`HomeViewModel` 用 `boundOwner` 记住列表属于哪个登录；凭据变化时取消在途请求并清空行，避免 A 的列表落到 B 的界面。

展示上，`SessionRow` 的副标题优先级是：`lastMessage.text` → `description` → `"${memberCount} 位成员"`。`SessionSummary.lastMessage` 当前没有赋值来源（列表接口不返回预览），因此实际落到 `description` 或成员数。

## 5. 路径三：打开聊天

触发：`ChatScreen` 的 `LaunchedEffect(sessionId)` → `viewModel.open(…)`；`HomeScreen` 的行点击通过 `onOpenChat(sessionId)` 进入 `Destination.Chat(sessionId, title, subtitle)`。

```
ChatViewModel.open(sessionId, title, subtitle)
├─ sessionId == 当前 sessionId → 只更新标题/副标题；watchRepository()；
│   若前台 && initialLoadSucceeded && !isLoadingInitial → repository.startPolling()
└─ 否则：generation = ++bindingGeneration
        清空 messages/participants/draft/error，isLoadingInitial = true
        watchRepository()（只启动一次，收集 repository.chat）
        opening = launch { 就绪门 → repository.openChat(sessionId)
                           成功 → initialLoadSucceeded = true，前台则 startPolling()
                           失败 → isLoadingInitial = false，保留 repository 发布的 error }
```

Repository 侧：

```
openChat(sessionId)
├─ stopPolling()
├─ owner = authOwner()（无则写一条 "请先登录" 快照并抛 UNAUTHORIZED）
├─ generation = ++chatGeneration
├─ coordinator = MessageSyncCoordinator()   ← 新会话 = 新 coordinator（墓碑也随之清空）
├─ chatAccessDenied = false
├─ chat = ChatSnapshot(sessionId, isLoadingInitial = true, reachedTop = false)
│      （先替换快照，避免旧会话消息出现在新标题下）
├─ showInitialHistory(owner, sessionId, sync)（见路径四）
├─ 失败时：401 → invalidateCredentials(owner)；403 → chatAccessDenied = true
└─ finally：仅当 isCurrentChat(generation, sessionId) 才发布
            ChatSnapshot(messages = sync.messages, isLoadingInitial = false,
                         reachedTop = sync.reachedTop, error = …)
   （成功且未被拒）异步 scope.launch { loadParticipants(...) }，不阻塞首屏
   （失败）重新抛出，交给 ViewModel 呈现
```

`loadParticipants` 走 `GET /getSessionUsers/{sessionId}`，把 `List<SessionMember>` 变成 `participants`。它失败是静默的（名字退回 `用户 N`），只有 401 会触发登出。

关闭：`ChatViewModel.close()` → `repository.closeChat()` → `stopPolling()` + `clearChat()`（`chatGeneration++`、`coordinator = null`、`chatAccessDenied = false`、`chat = null`）。返回列表时 `AppRoot` 还会 `homeViewModel.refresh()`。

## 6. 路径四：首屏历史

```
showInitialHistory(owner, sessionId, sync)
├─ newest = newestFromLastMessage(api.lastMessage(owner, sessionId))
│     GET /lastMessage/{sessionId}
│     Present(info) → info.id；Empty / Unknown → null
│     403 / 401 重新抛出（403 让聊天显示"无权限"而不是静默空会话）
├─ window = newest != null
│     ? (newest - DEFAULT_HISTORY_PAGE + 1)..newest，起点 coercedAtLeast(0)
│     : sync.speculativeHistoryWindow()  → 1..100
└─ page = api.messages(owner, sessionId, window.start, window.endInclusive)
   sync.applyHistory(window.start, window.endInclusive, page, speculative = newest == null)
```

为什么不用 `receivedMessageSeq`：它是**已读位置**，不等于会话末尾。用 `marker-30..marker` 会把已读之后的全部消息挡在首屏外，还会被误当成"历史到头"。`ChatAccessRegressionTest` 的 `a read marker of two does not anchor the first screen` 固定了这一点。

`openChat` 从不请求 `0..0`：这个范围必然为空，会被误读成"空会话"。锚点缺失时用 1..100 的宽窗口，并标记为 `speculative`。

翻页：`ChatScreen` 用 `reverseLayout = true` 的 `LazyColumn`，所以"更早"在列表远端。最后一个可见 item 的 index 达到 `totalItemsCount - 2`（并且列表非空）时，用 `distinctUntilChanged().filter { it }` 触发一次 `viewModel.loadOlder()` → `repository.loadOlder()` → `sync.nextHistoryWindow()`（以 `oldestRequestedSeq` 为边界往回 30 条），再 `applyHistory` + 发布。`nextHistoryWindow()` 返回 `null` 且不是 `reachedTop` 时，说明首屏从未成功，发布 `EMPTY_HISTORY_ERROR = "消息尚未加载，请重新打开或稍后重试"`，而不是谎称已到顶。

## 7. 路径五：轮询

生命周期：`ChatScreen` 的 `LaunchedEffect(lifecycleOwner, sessionId)` 使用 `repeatOnLifecycle(Lifecycle.State.RESUMED)`，进入 RESUMED 调 `onForeground()`，离开调 `onBackground()`。`onForeground` 只在 `initialLoadSucceeded` 且不在初始加载时启动。所以轮询严格绑定"聊天页在前台"。

```
startPolling(intervalMillis = DEFAULT_POLL_INTERVAL_MILLIS = 3000)
├─ 已有 pollJob 在跑 → 返回
├─ 没有 chat 或没有 credentials → 返回
├─ chatAccessDenied → 返回（403 之后不再重试，重开会话才清除）
├─ publish(isPolling = true)
└─ launch { while (isActive) { delay(interval); owner = authOwner() ?: break
             try { pollOnce() }
             catch UNAUTHORIZED → invalidateCredentials(owner); break
             catch FORBIDDEN → chatAccessDenied = true; 发布错误; break
             catch 其他 → 发布错误，下一轮继续 }
           finally { publish(isPolling = false) } }
```

```
pollOnce()
├─ owner / snapshot / sync / sessionId / generation 快照
├─ probe = api.lastMessage(owner, sessionId)
│    ├─ 401 → invalidateCredentials；403 → chatAccessDenied + 发布错误；两者都重新抛出
│    ├─ 其他异常 → LastMessageResult.Unknown
│    └─ 正常 → Empty / Present / Unknown
├─ 校验 isCurrentAuth(owner)、isCurrentChat(...)
├─ probe is Empty → 返回 false（不发消息请求）
├─ window = newest != null ? sync.planCatchUp(newest) : sync.speculativePollWindow()
│    （planCatchUp: 起点 = 水位+1（无水位则 1），终点 = min(newest, 起点+30-1)；空则返回 null）
├─ page = api.messages(owner, sessionId, window.start, window.endInclusive)
│    失败 → 403 置 chatAccessDenied；发布错误并重新抛出（水位不动）
├─ 再次校验 auth/chat
├─ newest != null → sync.acknowledgeFetchedWindow(window.start, window.endInclusive)
│    （只推进到"取回的窗口末端"，不是 newest）
├─ added = sync.mergeIncoming(page)   ← 推测窗口下水位只随真实返回的消息前进
└─ added > 0 → 发布 messages + reachedTop，返回 true
```

`LastMessageResult` 的三态是这套逻辑的核心：

| 值 | 含义 | 行为 |
| --- | --- | --- |
| `Empty` | 服务器明确回答 `hasMessage = false` | 跳过消息请求（true 的"没有新消息"） |
| `Present` | 有可用的最新序号 | 只请求缺失的区间，可按窗口推进水位 |
| `Unknown` | 负载缺失/不完整/非对象 | 退化为固定推测窗口，不得当成空会话 |

把 `Unknown` 当成 `Empty` 会让一次服务端字段变更表现为"永远安静的空会话"；把 `Unknown` 当成 `Present` 会用一个读不懂的 id 推进水位。三态就是为避免这两种误合并。

## 8. 路径六：发送

```
ChatViewModel.send()
├─ 草稿空白或 isSending → 返回
├─ 立即清空 draft、isSending = true（绑定代际快照），记下 replyTarget
└─ repository.sendText(text, replyTo = replyTarget?.messageId)
   ├─ owner / snapshot / sync / generation
   ├─ text.isBlank() → false
   ├─ publish(isSending = true, error 清除)
   ├─ seq = api.sendText(owner, sessionId, text, replyTo)
   │     POST /sendMessage/{sessionId}
   │     pieces = [reply?] + [{type:text,text}] —— reply 片段必须首位
   │   seq == null（服务器 {seq:-1} 软拒绝）→ 发布"消息未发送：你可能已不在该会话中"，返回 false
   ├─ refreshed = refreshTail(owner, sessionId, sync, around = seq)
   │     POST getMessages(seq-4..seq)，要求 seq 出现在响应里
   │    → sync.mergeWindow(start, seq, page, requirePresent = seq)
   │    → 返回 sync.contains(seq)
   ├─ 校验 isCurrentAuth / isCurrentChat（不通过就返回 true，不写状态）
   ├─ refreshed && sync.isContiguousWithWatermark(seq) → sync.acknowledgeSend(seq)
   │    窗口是客户端从下方挑的，只有与水位连续时才算"已知无缺口"
   │    回读失败或响应缺 seq 时水位不动，下一轮轮询必须再请求包含 seq 的范围
   └─ publish(messages, isSending = false)，返回 true
```

ViewModel 侧：`sent == false` 时恢复草稿（`draft.ifEmpty { text }`）并清 `isSending`，否则只清 `isSending`。所有早退路径都必须恢复这两项，否则发送按钮永久禁用。

没有乐观插入：消息进入界面的唯一路径是 coordinator 存储 + Repository 发布。

`sendText` 的 `replyTo` 只影响请求体：发送成功、切会话、`close()`、登出才清 `replyTarget`；发送失败时 ViewModel **保留**它，草稿也一起恢复，用户可以原样重试。`REPLY_PREVIEW_CHARS = 200` 限制预览条的正文长度。

## 9. 路径七：引用回复

入口有三个，都汇到 `ChatViewModel.beginReply(message)`：

- 长按气泡 → 菜单里的"回复"；
- 在气泡上右起点双击（`ReplyGestures.isReplyDoubleTap`：x 落在气泡宽度的右侧 40%，即 `x >= width * 0.6`）；
- 在气泡上左滑超过 64dp（`ReplyGestures.isReplySwipe`：水平位移占优且超过阈值）。

```
beginReply(message)
├─ message.id <= 0 → 忽略
└─ replyTarget = ReplyTarget(id, senderDisplayName(...), plainText.trim().take(200))
   再次调用只是替换目标：没有引用栈

渲染
├─ 输入框上方的 ReplyPreviewStrip：作者 + 摘要 + 取消
└─ 每条消息的 MessageBubble 若有 reply → 渲染 QuoteBlock（左侧竖条 + 作者 + 最多两行正文）
   ├─ 作者：自己 → "我"；否则快照名 → 目录名 → 用户 N → 未知用户
   ├─ 正文：快照文本；缺失时用已加载的原消息补；仍为空则不画该行
   └─ 可点：lazyIndexForMessage(listItems, targetId) 命中 → 动画滚动 + 高亮 1.2 秒
                未命中（原消息在已加载窗口外）→ Snackbar "原消息不在当前已加载的消息里"
```

三个关键取舍：

- **引用块永远有作者行**，即使气泡外的发送者标签不画（自己的消息）。两者规则不同是有意的：标签回答"谁发的"，引用块回答"我在回谁"。
- **跳回只在已加载窗口内**。窗口外的消息没有本地坐标，伪造滚动位置比给出明确提示更糟。
- **快照字段不可验证**，所以作者名/正文的兜底链全部是纯函数并单独测试；缺字段只会少画一行，不会让整条消息变成协议错误（`messageId` 才是硬依赖）。

## 10. 路径八：删除

```
ChatViewModel.deleteMessage(id) → repository.deleteMessage(id)
├─ owner / snapshot / generation
├─ deleted = api.deleteMessage(owner, sessionId, id)  POST {messageId}
│     支持 {"success":bool} 与裸 boolean；{success:false} 是软失败（非成员）
├─ isCurrentChat 检查
├─ deleted → coordinator.remove(id)（写入墓碑 + 从存储移除）
│             publish(messages = coordinator.messages)
└─ 异常：401 → invalidateCredentials；发布错误；返回 false
```

墓碑（`tombstones: HashSet<Long>`）在 coordinator 生命周期内永久有效：删除时已经发出的历史页、轮询响应、发送回读可能还带着这条 id，`mergeRecords` / `mergeIncoming` 都会先查墓碑再写入，所以删除不会被"迟到数据"复活。`clear()` 刻意不清墓碑；只有新会话的新 coordinator 才从服务器重新开始。

删除不会改写别人消息里已经冻结的引用快照：引用块显示的是发送当时的作者与文本。

## 11. 路径九：群聊信息

触发：聊天页标题块可点（右侧"刷新"前有一条 `VerticalDivider`，所以点标题不会触发刷新）→ `onOpenGroupInfo(sessionId, title, subtitle)` → `Destination.GroupInfo(sessionId, title, subtitle)`。

```
GroupInfoScreen(LaunchedEffect(sessionId)) → GroupInfoViewModel.open(sessionId, reload)
├─ reload = 首次进入该页（旋转复用，不闪 spinner）
├─ 缓存按 `generation|userId|baseUrl` 绑定：换账号/重新登录/换服务器都重新加载
└─ 否则 isLoading = true → repository.loadGroupInfo(sessionId)

loadGroupInfo(sessionId)
├─ owner = authOwner()
├─ detail = api.sessionInfo(...)        POST /getSessionInfo；失败或不完整再 GET /getSession
│    401 → invalidateCredentials 后抛出；403/网络错误在两条路由都失败时原样抛给页面
├─ directory = api.sessionMembers(...)  GET /getSessionUsers
│    失败不致命：401 登出，其他一律返回空目录
├─ 合并：以 detail 的成员为准，directory 只补 username/avatarUrl，
│        占位名"用户 N"才被替换，真实名不覆盖
├─ 归一化：先服务端布尔-only 的答案折进 id
│    ownerId = detail.ownerId > 0 ? detail.ownerId : (detail.isOwner ? currentUserId : 0)
│    adminIds = (ownerId > 0 || detail.adminIds 非空) ? detail.adminIds
│                                              : (detail.isAdmin ? [currentUserId] : [])
├─ isOwner = ownerId == currentUserId
├─ isAdmin = isOwner || adminIds.contains(currentUserId)
└─ GroupInfo(sessionId, name, description, ownerId, adminIds, members, avatarUrl, isOwner, isAdmin)
```

页面渲染 `GroupInfo` 时不再做第二次判断：头部的"我是群主/我是管理员"来自 `isOwner`/`isAdmin`，成员行的标签来自同一个 `GroupInfo.roleOf(userId)`（它只读同一对 `ownerId`/`adminIds`），因此不可能互相矛盾。`title`/`subtitle` 只是从聊天页带过来的占位，答案到达后由 `info` 覆盖。

`GroupInfoViewModel.open(sessionId, reload)` 的缓存按 `generation|userId|baseUrl` 绑定：旋转时 `reload = false` 复用已加载结果（不闪 spinner），重新进入页面时 `reload = true` 重新问服务器（名字、角色、成员会变）；换账号、同一账号重新登录（新 token）或换服务器地址都会让缓存失效。`refresh()` 用于错误页的"重试"，会保留屏幕上的内容直到新答案到达。每次 `load` 自增自己的 `generation`，晚到的结果不得写进后来打开的页面。

聊天页的滚动位置是 Compose 自己的状态（`rememberLazyListState`），用一个与目的地无关的键保留：`retainedChatSessionId` 对 `Chat(42)` 与 `GroupInfo(42)` 返回同一个值，所以从群聊信息返回时列表回到原处；一旦真正离开会话（列表/设置/另一个会话）就落回 `0`，下次打开从最新消息开始。

导航：`Destination.GroupInfo` 用 saver key `groupinfo` 持久化（含占位标题），进程重建后直接回到该页；返回时**不调用** `chatViewModel.close()`，所以转写、草稿和滚动位置都还在，`repeatOnLifecycle` 会重新拉起轮询。

## 12. 关键状态机与守卫一览

### 12.1 认证

```
authState:  CHECKING ──restoreSession(ok)──▶ READY
              │                              │
              ├──restoreSession(401/无本地)──▶ SIGNED_OUT
              │                              │
              └──login/register 成功─────────▶ READY
                                              │
                              logout / 401 / invalidateCredentials ──▶ SIGNED_OUT
```

- `credentials` 与 `authState` 分开：前者回答"有没有 token"，后者回答"校验结束了没有"。
- `authGeneration` 每次登录/恢复/登出都自增；`AuthOwner(generation, credentials)` 是每个挂起操作的票据。

### 12.2 会话实例代际

| 计数器 | 位置 | 何时自增 | 作用 |
| --- | --- | --- | --- |
| `chatGeneration` | `OxypeRepository` | `openChat`、`clearChat`（关闭/登出） | 旧会话的分页、发送、轮询、成员加载不得写入新会话 |
| `bindingGeneration` | `ChatViewModel` | `open` 切换会话、`close`、`resetForSignedOut` | 旧绑定的异步尾巴不得改草稿/错误/忙碌标志 |
| `groupInfoGeneration`（`GroupInfoViewModel.generation`） | `GroupInfoViewModel` | 每次 `open`/`refresh` 的 `load` | 晚到的群聊详情不得覆盖用户随后打开的另一个页 |
| `repositoryChatVersion` | `ChatViewModel` | `open` 重置、`applySnapshot` 时同步为 `repository.chatVersion` | `consumeError` 关闭错误时定位到具体会话实例 |

`chatGeneration`、`bindingGeneration`、`repositoryChatVersion` 配合 `isCurrentChat(generation, sessionId, sync)`（还要求 `coordinator === sync`）形成完整守卫：同 id 重开也算不同实例。群聊信息页是独立的叶子页，用自己的 `GroupInfoViewModel.generation`。

### 12.3 `MessageSyncCoordinator` 水位

| 字段 | 含义 | 推进规则 |
| --- | --- | --- |
| `newestKnownSeq` | 已确认为"无缺口已知"的最高序号 | `applyHistory` 非推测窗口→`requestedEnd`；推测窗口→实际返回消息的 id；`mergeWindow`→与水位连续时的 `endInclusive`；`acknowledgeFetchedWindow`→窗口末端；`acknowledgeSend`→调用方确认连续的 `seq`；`mergeIncoming`→每条新消息 id |
| `oldestRequestedSeq` | 曾请求过的最低起点（`NO_SEQUENCE = -1` 表示没有） | `applyHistory` 取 `min`；空页也记录，避免同一空洞反复循环 |
| `reachedTop` | 是否已证明到了最早消息 | 只有非推测窗口的 `oldestRequestedSeq <= 1`，或 `nextHistoryWindow` 遇到边界 |

关键推论：水位是"连续性断言"，不是"最大值"。删除造成的空洞由服务器跳过并**仍然算已处理的窗口**（因此 `acknowledgeFetchedWindow` 允许把水位推到窗口末端，避免空窗口被反复请求）；但客户端自己猜的窗口不能这么算。

### 12.4 `LastMessageResult` 三态

见第 7 节表格。它由 `OxypeApi.lastMessage` 产生，`MessageSyncCoordinator.newestFromLastMessage` 只把 `Present` 转成序号，`Empty` / `Unknown` 都返回 `null`，由调用方决定"跳过"还是"推测"。

## 13. 分层的取舍

- **手写导航、无 Navigation 库**：三个顶层页 + 两个详情页（聊天、群聊信息），回退规则只有四条（chat→list、groupinfo→chat、settings→list、list→系统返回）。用 `NavigationState` + `rememberSaveable` 让登录/登出/吊销共用一条凭据信号，逻辑可在 JVM 上测（`AppNavigationTest`）。代价是嵌套页/深链要自己扩展。
- **单 Repository + StateFlow，而不是每个页面各自请求**：账号切换、后台轮询、发送回读都需要"谁的状态属于谁"的统一判断，集中在一处才可能一致。代价是 `OxypeRepository.kt` 偏大，所有编排都在一个类里。
- **协议知识全部收敛在 `data/oxype`**：上游字段拼写不稳定（蛇形/驼峰、`data` 信封/裸数组、`pieces`/`content`），别名与兜底必须只有一处。代价是 `OxypeApi` 较长且充满容错分支。
- **`MessageSyncCoordinator` 是纯 Kotlin**：顺序、双闭区间、空洞、去重、水位、墓碑全部能在 JVM 上直接测（`MessageSyncCoordinatorTest` 32 个用例），不需要设备或 mock 框架。代价是它必须由调用方正确驱动——水位规则写错不会在编译期暴露。
- **没有乐观更新**：发送后回读权威记录，界面不做"假消息"。代价是多一次请求、发送反馈稍慢；换来的是不会出现重复气泡、错误 id、时间戳与服务器不一致。
- **不缓存会话与消息到磁盘**：`SessionStore` 只存 token、用户名、base URL、主题。代价是冷启动必须联网；换来的是不需要处理本地与服务端的一致性、也不需要为退出登录清理消息库。
- **轮询而非 WebSocket**：实现简单、错误面小、行为可预测（`lastMessage` 探测 + 缺失区间补取）。代价是 3 秒延迟与额外请求，且"非成员"这类软失败需要 403 特殊处理。
- **引用块优先用快照，缺字段才回退本地**：服务端冻结的引用是唯一能在原消息被删后仍正确显示的来源；本地补全只用于快照不完整的服务器。代价是兜底链有四个分支，因此全部抽成纯函数单独测试。
- **群聊信息页只读**：没有邀请、改名、踢人、设管理员等入口，避免在未知权限语义下猜测写操作。代价是页面只展示，`admins`/头像缺失时静默回退。
- **无 DI 框架**：一个 Repository、五个 ViewModel，手工组合根更直观，也避免在 unit test 里搭 DI。代价是将来拆模块时构造链要手动传递。
