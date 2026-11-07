# Oxype HTTP 契约（客户端实现依据）

本文记录**客户端当前实际依赖**的 Oxype HTTP 契约：它发了什么请求、期望什么形状的响应、把哪些偏差当成错误。它是实现说明，不是上游规范。

- 字段与语义以开发者正式文档为准；本文只描述本客户端的行为。
- 本机参考：`D:/LIPis/downloads/README_zh (1).md`（仅供人工查阅，**不是仓库依赖**，不能作为构建/测试输入，也不要把路径写进代码或脚本）。
- 路由字符串与字段拼写集中在 `app/src/main/java/dev/oxype/client/data/oxype/OxypeApi.kt`；序列化容错在 `OxypeJson.kt`；HTTP 与错误分类在 `OxypeHttpClient.kt`。服务端改动时只需要动这几个文件。

## 1. 认证方式

每个已认证请求携带 `userid` + `token` 这一对凭据，**不使用 `Authorization: Bearer`**。

| 方法 | 凭据位置 | 实现 |
| --- | --- | --- |
| POST | JSON body 的 `userid` / `token` 字段 | `OxypeHttpClient.postJson`：若 body 里已有 `userid`/`userId`/`token` 则不覆盖，否则补上 |
| GET | query string 的 `userid` / `token` | `OxypeHttpClient.get`：先放凭据，再合并调用方传入的 query |

细节：

- `userid` 是**登录账号的编号**，不是会话编号。路径里的 `{userid}` 与 body/query 里的 `userid` 必须一致（fixture 会对不一致返回 403）。
- GET 的 query 值用 `URLEncoder.encode(value, "UTF-8")` 编码。
- 客户端**不支持**通过请求头传凭据（虽然上游文档提到可回退到 `userid`/`X-User-Id` 与 `token`/`X-Token`，本实现没有走这条路径）。
- 登录与注册在拿到 token 之前当然没有凭据可带；`POST /getSessions` 是唯一在**已登录状态下**不带凭据的调用（见 5.6）。
- 头像请求完全脱离 `OxypeHttpClient`：`SenderAvatar` 直接用 `HttpURLConnection` 拉图，不发 `Authorization`、不带 token，因为 URL 可能指向第三方主机。

## 2. 基础地址

- 默认值 `OxypeHttpClient.DEFAULT_BASE_URL = "http://10.0.2.2:12345"`（`10.0.2.2` 是 Android 模拟器访问宿主机的别名）。
- `normalizeBaseUrl`：空串回退默认值；缺少 scheme 时补 `http://`；去掉末尾 `/`。
- 服务端返回的头像**相对 URL** 按**当前配置的 base URL** 解析（`AvatarUrls.resolve`），改地址后新请求的头像跟随新地址。
- 设置页与登录页都能改地址，立即生效并持久化到 `SharedPreferences` 文件 `oxype_client_session` 的 `base_url`。

## 3. 响应信封

上游用 Kalmia 包装：`{"data": <payload>}`。客户端两种形状都接受（`OxypeJson.unwrap`）：

| 实际响应 | 解包结果 |
| --- | --- |
| `{"data": {...}}` | `{...}` |
| `{"data": [...]}` | `[...]` |
| `[...]`（裸数组） | `[...]` |
| `{...}`（无 `data`） | 原样 |

`OxypeJson.arrayOf(value, vararg keys)` 进一步接受"数组直接给"或"对象里某个 key 是数组"。各路由允许的 key 在下文标注（例如 `sessions`/`list`）。

裸标量也被接受（`OxypeHttpClient.parseOrNull` 用 `JSONTokener`）：`true`、数字、带引号的字符串。用于 `isAlive` 的裸布尔、`sendMessage` 的裸数字、`deleteMessage` 的裸布尔、邀请 id 的字符串。

**解析前置检查**：响应体（去空白后）必须以 `{ [ " - 0..9 t f n` 之一开头，否则判为 `PROTOCOL`。这防止误把代理/网关的 HTML 错误页当成合法 JSON。

空响应体（2xx）解码为 `null`，不算错误。

## 4. 错误分类

非 2xx 时**先按状态码分类**，再用响应体补充文案（`OxypeHttpClient.decode` / `kindFor`）：

| HTTP | `OxypeErrorKind` | 客户端含义 |
| --- | --- | --- |
| 400、422 | `BAD_REQUEST` | 请求参数/邀请无效/密码太弱 |
| 401 | `UNAUTHORIZED` | token 缺失、过期、被吊销 → 清本地凭据回登录页 |
| 403 | `FORBIDDEN` | 已认证但无权 → 单次/单会话错误，不清 token |
| 404 | `NOT_FOUND` | 会话/消息/用户不存在 |
| 300–399 | `PROTOCOL` | 客户端不跟随重定向；提示确认地址用 https 且不再跳转 |
| 500–599 | `SERVER` | 服务端故障 |
| 其他 | `PROTOCOL` | 未归类的状态码 |
| （无 HTTP） | `NETWORK` | 连接失败或超时（`HttpUrlConnectionTransport` 默认 15 秒连接/读取） |
| （2xx 但读不懂） | `PROTOCOL` | 协议变更或代理返回非 JSON |

错误文案优先取 `error_message` → `error_details_message` → `message`，并跳过 Kalmia 通用的 `Server protocol error…` 横幅；都没有时用状态码的默认中文文案。`OxypeException` 同时带 `httpStatus`。

### 401 与 403 的分工（客户端最重要的语义区分）

- **401 是凭据信号**：`OxypeRepository.invalidateCredentials(owner)` → 清本地 token、清列表与聊天、`sessionExpired = true`、导航回登录页。只在 `isRejectedToken(e)`（`kind == UNAUTHORIZED`）时发生。
- **403 是权限信号**：
  - 打开聊天或轮询时收到 → `chatAccessDenied = true`，停止该会话轮询并显示错误；重新打开会话清除标记（可能已被重新邀请）。
  - 错误的 token 被别的账号替换后，旧的 401 也不能登出新账号（`AuthOwner` 代际校验）。
  - 恢复时 `isAlive` 返回 403 不会被当成 token 失效（保持登录）。
- 判断只看 `OxypeErrorKind`，绝不按字符串匹配。

## 5. 端点

以下 `<uid>` 指登录账号编号，`<sid>` 指会话编号。5.1–5.13 与 5.15 是客户端实际调用的路由；5.14 与 5.16 是 `OxypeApi` 里存在但当前 UI 未调用的方法，单列出来以免误认。

### 5.1 `POST /register`

请求体：`username`（最长 15，由 `AuthUiState.MAX_USERNAME` 限制）、`password`（6–20，`MIN_PASSWORD`/`MAX_PASSWORD`）。

响应（`data` 内）：对象，含 `userid`（别名 `userId`、`id`）与 `token`（非空白）。

客户端行为：成功即视为已登录（`finishSignIn`：存凭据、`authGeneration++`、`authState = READY`）。缺 `userid` 或 `token` → `PROTOCOL`（"注册响应缺少账户信息"/"缺少用户编号"/"缺少令牌"）。

### 5.2 `POST /login/<uid>`

请求体：只有 `password`（此时还没有 token，所以整条请求不带凭据）。账号编号只在路径里。

响应：对象，含 `token`（非空白）。缺少 → `PROTOCOL`。

### 5.3 `POST /isAlive/<uid>`

请求体：`token`。

响应：`is_alive`（别名 `isAlive`、`alive`、`valid`）为布尔；也接受裸 `true`/`false`。**默认按存活处理**：非布尔且非对象的负载、以及对象里缺少该字段，都会返回 `true`（`OxypeApi.isAlive`）。只有明确的 `false` 才表示无效。

客户端行为：启动恢复时调用一次。只有明确 `false` 或 401 才登出；网络错误/5xx/无法解析都**保留** token（`restoreSession` 中 `alive == null` 分支）。

### 5.4 `POST /logout/<uid>`

请求体：`token`。响应内容客户端不读（`OxypeApi.logout` 忽略 payload）。

客户端行为：尽力而为。服务端失败也照样清本地凭据、清列表、清聊天、`authGeneration++`。

### 5.5 `GET /getJoinedSessions/<uid>`

响应：`sessions`（别名 `list`）为 long 数组；也接受裸数组。

客户端行为：仅取 id 列表，随后用 `getSessions` 换详情。HTTP 失败会抛 `OxypeException`；只有"响应能解析但没有 `sessions` 数组"才退化为空列表（`OxypeJson.arrayOf` 返回 `null`，`longList` 给空），此时后续不会发出 `getSessions`，列表显示为空。

### 5.6 `POST /getSessions`

请求体：`sessions`（long 数组，来自上一步）。空列表时客户端**不发请求**，直接返回空。

与 `login`/`register` 一样，这条路由**不携带凭据**：`OxypeApi.sessionsByIds` 调 `postJson` 时没有传 `credentials`，因此不会注入 `userid`/`token`（login/register 是因为此时还没有凭据）。批量请求的任何失败（包括 401）都在 `sessionDetails` 里被吞掉，改为逐会话调用 5.7 补全；逐会话请求才会带凭据，并在 401 时穿透到调用方。这也是 fixture 下批量路由不可用（fixture 要求 body 里有 `userid`）但列表仍能显示的原因。

响应：数组（`sessions`/`list` key 或裸数组），每项至少需要 `sessionId`（别名 `session_id`、`id`）；没有 id 的行被跳过。可选字段：

| 字段 | 别名 | 用途 |
| --- | --- | --- |
| `sessionName` | `name`、`title` | 显示名；缺失时 `"会话 <sid>"` |
| `description` | — | 副标题 |
| `owner` | `ownerId`、`owner_id` | 成员数计算与完整性判断 |
| `members` | `memberIds`、`member_ids` | 成员 id |
| `admins` | `adminIds`、`admin_ids` | 管理员 id |
| `avatarUrl` 等 | 见第 7 节 | 会话头像；`SessionSummary` 不读，群聊信息页读 `SessionDetail` |

`SessionSummary.isComplete` = `ownerId > 0 || memberIds.isNotEmpty()`。不完整的行会用 5.7 补全。

### 5.7 `POST /getSessionInfo/<sid>` 与 `GET /getSession/<sid>`

客户端优先 POST；POST 失败（非 401）或解析出的 `SessionDetail` 不完整时，回退 GET。两者都不可用或不完整时抛 `PROTOCOL`（"会话信息不完整：服务器没有返回成员或所有者"）或保留第一个传输错误。

`SessionDetail` 读取：`sessionId`（别名 `session_id`、`id`，缺失用请求里的 id）、`sessionName`/`name`/`title`、`description`、`owner`/`ownerId`、`isOwner`/`is_owner`、`isAdmin`/`is_admin`、管理员 id `admins`（别名 `adminIds`、`admin_ids`）、会话头像（第 7 节的别名，`SessionDetail.avatarUrl`）、成员列表 `users`/`members`/`list`（每项 `userid`/`userId`/`id` + `username`/`name` + 头像别名）；没有对象成员时退回 `members` 的 id 数组并生成 `用户 <id>`。

`admins` 与会话头像都是**可选**：缺失时分别是空列表与 `null`，不是错误（`OxypeApi.parseSessionDetail`）。

`SessionDetail.isComplete` = `ownerId > 0 || members.isNotEmpty()`。这是为了防止"只有名字"的短响应被渲染成"0 位成员"。

批量解析的特例（`OxypeApi.sessionDetails`）：

- 401 从任何一条会话的补全请求中冒出时**穿透**到调用方（会让应用登出）。
- 403 与 404 等属于单会话，跳过并继续。
- 全部失败（`collected` 为空且有 `firstFailure`）才抛错，避免把网络故障显示成空列表。

群聊信息页的读取（`OxypeRepository.loadGroupInfo`）把这两条路由合起来：

1. `sessionInfo`（先 POST，失败或响应不完整再 GET）是**必需**的：401 清凭据；POST 的 403 会先尝试 GET，两者都不行时把第一个传输错误原样抛给页面（不清 token）。
2. `sessionMembers` 是**补充**：失败只让成员名退回 `用户 N`；只有 401 会登出。
3. 合并时以 `sessionInfo` 的成员为准，目录只补 `username`/`avatarUrl`；占位名才会被替换，真实名不覆盖。
4. `isOwner`/`isAdmin` 由 `ownerId`/`adminIds` + 当前用户 id 决定，服务端布尔只在 id 缺失时回退（见 `GroupInfo`）。

### 5.8 `POST /createSession`

请求体：`name`、`description`（可为空串），凭据由 client 注入。

响应：对象，至少含 `session_id`（别名 `sessionId`、`id`）；`sessionName`/`name` 缺失时用请求里的 name；`inviteUuid`（别名 `invite_uuid`、`uuid`）缺失时为空串。

客户端行为：成功后若仍是同一登录，会 `refreshSessions()`；`HomeViewModel` 把 `inviteUuid` 作为一次性提示展示（"邀请码：…"）。

### 5.9 `POST /joinSessionByUuid`

请求体：`uuid`（客户端先 `trim()`）。

响应：对象，至少含 `sessionId`（别名 `session_id`、`id`），其余按 `SessionDetail` 解析。

客户端行为：成功后刷新列表。无效/过期邀请由服务器返回 400 → `BAD_REQUEST`，直接显示服务端文案。

### 5.10 `POST /sendMessage/<sid>`

请求体：只有 `pieces`（凭据自动注入）：

```json
{"pieces": [{"type": "text", "text": "…"}]}
{"pieces": [{"type": "reply", "messageId": 12}, {"type": "text", "text": "…"}]}
```

客户端发 `type: "text"` 的片段，引用回复时在**首位**插入一个 `type: "reply"` 片段；`markdown` 只用于解析（见第 6 节）。

回复片段的硬约束：

- 只发 `messageId`（引用的目标序号）。发送者 id、名字与文本快照由**服务端**写入；客户端自己拼的快照会被忽略。
- `reply` 必须**排在第一位**。服务端对出现在其他位置的 reply 返回 400（fixture 文案「reply 片段必须在首位」），所以不要为了排版把文本放在前面。
- 引用目标不存在时服务端返回 404（fixture 文案 `Message N to reply to was not found`），整条消息不会写入。
- 由此推论：**编辑一条引用消息时必须重发 reply 片段**，否则保存后的消息会丢掉引用。

响应（任一形状）：

- `{"seq": 12}` → 12
- 裸数字 `12` → 12
- 其他对象字段别名 `id`、`messageId`

`seq <= 0` 一律视为**软拒绝**（`OxypeApi.sendText` 返回 `null`）。上游用 `seq: -1` 表示"调用者不是该会话成员、未发送"。客户端把它显示为"消息未发送：你可能已不在该会话中"，而不是异常。发送失败时客户端**保留**回复目标与草稿，只有成功、切会话、关闭聊天或登出才清除。

### 5.11 `POST /getMessages/<sid>`

请求体：`start`、`end`，**两端都含**（双闭区间）。

响应：消息数组（`messages`/`list` key 或裸数组）。语义要点：

- 范围内被删除的序号直接缺席，所以返回条数可以少于窗口长度；这是正常结果。
- **空数组是合法的"这个范围没有消息"**，不是错误。2xx 空响应体会先解码成 `null`，`OxypeApi.messages` 对 `null` 也返回空列表，即同样按"该范围没有消息"处理。
- 但若响应**不是数组**（例如 `{"unexpected":true}`），或数组中某项不是对象，或某条消息缺 id/sender/pieces，则整次请求判为 `PROTOCOL`。原因：只返回"可读的那几行"无法与"整段都被删除"区分，调用方会推进水位跳过没见过的消息。

`start` 可能为 0（翻页到序列空间底部时 `coerceAtLeast(0)`），客户端的 `getMessages` 不对 0 做特殊处理。

### 5.12 `GET /lastMessage/<sid>`

响应三态（`OxypeApi.lastMessage` → `LastMessageResult`）：

| 响应 | 结果 |
| --- | --- |
| `{"hasMessage": false}` | `Empty`（会话确实没有消息） |
| `{"hasMessage": true, "id": 12, …}` | `Present(LastMessageInfo)`；`id` 别名 `seq`、`messageId`，必须 `>= 1` |
| 非对象、缺 `hasMessage`、`hasMessage=true` 但 id 缺失/非法 | `Unknown` |

`hasMessage` 别名 `has_message`；布尔接受 `true/false`、`"true"/"false"`、`"1"/"0"`、`"yes"/"no"`、非零数字。

可选字段：`sender`/`senderId`、`senderName`/`sender_name`、`timestamp`、`content`/`text`。当前客户端只用 `id`（以及必要时预览字段进模型）。

403 在这条路由上是**会话级拒绝**（非成员），会被原样抛出并让聊天显示错误、停止轮询；不会被降级成 `Unknown` 的推测轮询。

### 5.13 `POST /deleteMessage/<sid>`

请求体：`messageId`。

响应：`{"success": bool}`（别名 `deleted`）或裸布尔。`success: false` 是软失败（非成员、id 不存在、无权限），返回 `false` 而不抛异常；上层只是不删本地行并刷新。

客户端行为：只有 `true` 才把 id 从存储移除并写入墓碑。

### 5.14 `POST /receivedMessageSeq/<uid>/<sid>`

响应：`{"seq": 11}` 或裸数字。客户端接受 `seq`/`value`，默认 0。

**当前 UI 不使用它**（`OxypeApi.receivedMessageSeq` 存在但无调用方）。它是"该账号在此会话的已读位置"，**不是**会话最新序号。不要用它锚定首屏或推进水位。

### 5.15 `GET /getSessionUsers/<sid>`

响应：对象，成员列表在 `users`（别名 `members`、`list`）。每项：`userid`（别名 `userId`、`id`）、`username`（别名 `name`；空串视为无名字 → `用户 <id>`）、头像别名（见第 7 节）。

客户端行为：打开聊天后异步取，得到 `senderId → SessionMember` 映射。响应非对象或无列表 → 空目录（不报错）。名字/头像缺失只影响显示，不影响消息。

### 5.16 `POST /createSessionInvite/<sid>`

响应：字符串或对象（`uuid`/`inviteUuid`/`invite_uuid`）。

**当前 UI 不使用**（`OxypeApi.createInvite` 无调用方）。

### 5.17 客户端未调用但上游存在

`GET /getUser/{uid}`、`POST /getMessage/{sid}`、`POST /editMessage/{sid}`、`POST /updateSession/{sid}`、`GET /getHash?path=`、头像上传、WebSocket `/oxype` 等均不在本客户端实现范围内。看到相关字段（如 `editedAt`）只说明客户端能**解析**，不代表有对应功能。

## 6. 消息结构

一条消息（`ChatMessage`）：

| 字段 | 别名 | 客户端要求 |
| --- | --- | --- |
| `id` | `seq`、`messageId` | 必须 `>= 1`，否则整条是协议错误 |
| `sender` | `senderId`、`sender_id` | 必须存在且 `>= 0` |
| `timestamp` | `time` | 缺失 → 0（UI 显示 `--:--`） |
| `editedAt` | `edited_at` | 缺失 → 0；`> 0` 表示"已编辑"标记 |
| `pieces` | `content` | 必须是数组；缺失 → 协议错误 |

`pieces` 每项：

| 字段 | 说明 |
| --- | --- |
| `type` | `text` → `TEXT`；`markdown` 或 `md` → `MARKDOWN`；`reply` → 引用（见下）；缺失/null → 按 `TEXT`；其他值 → **跳过该片段** |
| `text` | 别名 `content`、`value`，必须存在（`reply` 片段除外） |

规则：

- 全部片段被跳过（未知类型）→ "消息没有可显示的内容" → 协议错误，整条丢弃而不是画空气泡。
- `ChatMessage.plainText` 直接拼接各片段文本，**没有富文本渲染器**（`markdown` 也只是原样显示）。
- `ChatMessage.isEdited` = `editedAtMillis > 0`。
- 同一 id 重复到达时保留一条；只有 `editedAt` 更大才替换已存内容（`MessageSyncCoordinator.isNewerRevision`）。

### 6.1 `reply` 片段（引用）

服务端把引用写成消息 `pieces` 里的一个片段，位置在首位：

```json
{"type": "reply", "messageId": 12, "sender": 3, "senderName": "林间小道", "text": "原话"}
```

客户端解析（`OxypeApi.parseReplyPiece` → `ChatMessage.reply: ReplyQuote?`）：

| 字段 | 别名 | 客户端要求 |
| --- | --- | --- |
| `messageId` | `message_id`、`id`、`seq` | 必须 `>= 1`；缺失或非法时**整个引用被丢弃**，消息本身的正文照常显示 |
| `sender` | `senderId`、`sender_id` | 可选，`>= 0` |
| `senderName` | `sender_name`、`username`、`name` | 可选；空串/纯空白视为缺失 |
| `text` | `content`、`value` | 可选；空串/纯空白视为缺失 |

要点：

- 引用是**元数据**，不进入 `plainText`，也不影响"没有可显示内容"的判定。一条只含 reply 片段（没有 text）的消息仍会因为没有正文而报协议错误。
- 只有第一个 `reply` 片段生效（服务端只允许一个）。
- `messageId` 是唯一硬依赖；`sender`/`senderName`/`text` 缺失时 UI 用本地已加载的消息补全（作者名 → `用户 N` → `未知用户`，正文缺失则不画那一行）。**快照字段拼写无法从客户端验证**，所以按可选处理。
- 引用的是自己的消息时，作者行显示"我"，优先于快照里写入的昵称（`QuotePresentation.quoteAuthorName`）。
- 快照是发送时冻结的：目标消息之后被删除，引用块仍然显示当时的作者与文本。
- 跳回原消息只在已加载窗口内有效；不在窗口内时显示"原消息不在当前已加载的消息里"。

## 7. 头像

客户端读取成员记录中第一个存在的头像字段：`avatarUrl`、`avatar_url`、`avatar`、`photoUrl`、`photo_url`、`photo`。空串/null 都视为"没有头像"。

`AvatarUrls.resolve(raw, baseUrl)` 的规则：

| 输入 | 结果 |
| --- | --- |
| `https://cdn.example/a.png` | 原样保留（可以是第三方 CDN） |
| `//cdn.example/a.png` | 取 baseUrl 的 scheme |
| `/avatar/u1-3f9a1c04.png`（上游文档的常见形状） | 拼到 baseUrl 的 origin |
| `avatar/u1-3f9a1c04.png` | 拼成 `baseUrl + "/" + 值` |
| `file:`、`data:`、`javascript:`、畸形值、空串 | `null` → UI 用首字母彩色圆盘 |

下载与解码限制（`AvatarImageLimits`）：

| 常量 | 值 | 含义 |
| --- | --- | --- |
| `MAX_BYTES` | `3 * 1024 * 1024`（3145728） | 与服务端上传上限一致；超过则放弃 |
| `TARGET_PX` | `96` | 长边目标像素；按 2 的幂 `inSampleSize` 采样 |
| `AVATAR_TIMEOUT_MILLIS` | `5000`（`SenderAvatar.kt` 私有常量） | 连接/读取超时 |
| `AvatarCache.MAX_ENTRIES` | `16`（`SenderAvatar.kt` 私有常量） | 已解码位图 LRU 上限 |

先 `contentLengthLong` 预检，再在读流时二次限流（`readAtMost`），宁可返回 `null` 也不解码半截图片。上游文档给出的上传限制是"最大 3 MB、每边 4096 像素"（`maxBytes`/`maxEdge`），客户端下载侧只对齐字节上限，边长由采样控制——`TARGET_PX = 96` 保证不会分配接近原图尺寸的位图。

## 8. 非成员（软失败）的表现

Oxype 对"不是该会话成员"的处理在不同路由上不一致，客户端因此必须按路由分别对待：

| 路由 | 非成员表现 | 客户端行为 |
| --- | --- | --- |
| `GET /lastMessage/<sid>` | `403` | 视为会话级拒绝：显示错误、`chatAccessDenied = true`、停止轮询，不清 token |
| `POST /getMessages/<sid>` | 可能是 `403`，也可能 200 + 空数组 | 403 → 同上；空数组 → 视为"该范围没有消息"（无法区分，靠 `lastMessage` 的 403 兜住） |
| `POST /sendMessage/<sid>` | `200 {"seq": -1}` | 软拒绝，显示"消息未发送：你可能已不在该会话中" |
| `POST /deleteMessage/<sid>` | `200 {"success": false}` | 软失败，不删本地行 |
| `POST /receivedMessageSeq/…` | 未使用；fixture 对非成员软返回 `{"seq": 0}` | 客户端不调用 |
| `GET /getSession`、`GET /getSessionUsers`、`POST /getSessionInfo` | `403` | 列表补全：单会话跳过（401 除外）；成员目录：静默失败；群聊信息页：`sessionInfo` 的 403 显示为页面错误 + 重试，成员目录的 403 只是名字退回 `用户 N` |

设计意图：**空数组/`seq:-1`/`success:false` 都不能证明"内容不存在"**，只有 `lastMessage` 的 403 是明确信号。如果没有这条信号，一个被踢出的账号会看到一个永远安静、永远轮询的空会话。反过来，403 也绝不能清除全局登录——用户对其他会话仍然有效。

补充：`OxypeErrorKind` 里声明了 `NOT_JOINED`，但当前没有任何代码产生这个值——软拒绝是通过 `sendText` 返回 `null`、`deleteMessage` 返回 `false` 表达的。加新路由时不要假设 `NOT_JOINED` 已经被填充。

## 9. fixture 与真实服务的差异

`tools/ui-fixture/fixture_server.py` 只实现上述子集，用于本机 UI 走查：

- 端口 `18080`，所有**成功**响应都套 Kalmia `data` 信封（**不覆盖裸数组/裸标量**这些客户端支持的形状）；错误响应是 `{"error_message": …}`。
- 有：`login`、`isAlive`、`logout`、`getUser`、`getJoinedSessions`、`getSessions`、`getSessionInfo`、`getSession`、`getSessionUsers`、`receivedMessageSeq`、`lastMessage`、`getMessages`、`sendMessage`（含 reply 片段）、`deleteMessage`、静态头像（含会话头像 `/avatars/session-42.png`）、`/health`。
- 没有：`register`、`createSession`、`joinSessionByUuid`、`createSessionInvite`。
- 预置账号：用户 `1` / 密码 `testpass1`；会话 `42`「周末徒步小队」，`owner = 1`、`admins = [1, 2]`、带会话头像；消息序号 1..9，其中 7 引用 4、9 引用 8。
- reply 的三条规则在 fixture 上都能复现：首位之外 → 400「reply 片段必须在首位」；未知目标 → 404 `Message N to reply to was not found`；目标被删后快照仍保留。
- 状态全在内存，重启清零。

因此 fixture 通过**不代表**真实服务通过；本文描述的所有容错（裸数组、别名、`Unknown`、软拒绝）在 fixture 中都不会被触发。
