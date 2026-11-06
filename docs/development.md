# 开发与联调

本文是环境搭建、日常命令、模拟器/fixture 联调和故障排查的操作手册。命令均在 Windows（Git Bash 或 cmd）下、仓库根目录 `D:/LIPis/Documents/code/oxype4Android` 执行。

## 1. 环境要求

| 项 | 要求 | 说明 |
| --- | --- | --- |
| JDK | 17 或更高 | 工程的 `compileOptions` 是 Java 17，编译产物 class 主版本为 61；Kotlin 编译由 AGP 9.4.1 内置支持，不需要单独装 Kotlin 编译器 |
| Android SDK | 需要 `platforms/android-36` 与 `build-tools` 36.x | `compileSdk = 36` |
| Gradle | 用仓库 wrapper（9.7.1），不要用系统 Gradle | `gradle/wrapper/gradle-wrapper.properties` |
| Python | 3.x，仅用于 `tools/ui-fixture`（标准库实现，无第三方包） | 不需要 pip 安装任何东西 |
| 设备 | Android 8.0+（minSdk 26）的模拟器或真机 | 联调推荐模拟器 + `adb reverse` |

本机参考（用 `echo $ANDROID_HOME` / `java -version` 自查，不要照抄别人机器）：

```bash
java -version            # 需要 17+
echo "$ANDROID_HOME"     # 例如 C:\Program Files\AndroidSdk
```

SDK 定位顺序：`ANDROID_HOME`（或 `ANDROID_SDK_ROOT`）→ 仓库根 `local.properties` 的 `sdk.dir`。`local.properties` 被 `.gitignore` 忽略，不要提交。需要时创建：

```properties
# Windows 路径在 properties 里要用双反斜杠
sdk.dir=C\:\\Program Files\\AndroidSdk
```

注意 `python` 与 `python3` 的差异：在 Windows 上 `python3` 可能落到 Microsoft Store 的别名桩并报 "Python was not found"，而 `python` 指向真实解释器。统一用 `python`，先 `python --version` 确认。

## 2. 日常命令

```bash
# 单元测试（JVM，无需设备）
./gradlew.bat --offline :app:testDebugUnitTest

# 构建 debug APK
./gradlew.bat --offline :app:assembleDebug

# Lint
./gradlew.bat --offline :app:lintDebug
```

`--offline` 假定依赖已在 `~/.gradle/caches`；首次构建、升级依赖或换机器时去掉它，需要联网。是否具备离线条件可以自查：`ls ~/.gradle/wrapper/dists` 存在 `gradle-9.7.1-bin`、`ls ~/.gradle/caches/modules-2` 存在，才适合加 `--offline`。

命令写法：Git Bash 里必须带 `./`（`./gradlew.bat`；裸 `gradlew.bat` 不在 PATH 里）。cmd.exe 里可以直接写 `gradlew.bat`；PowerShell 里需要 `.\gradlew.bat`（PowerShell 默认不执行当前目录下的命令）。`gradlew`（无扩展名）是 Linux/macOS 用的，在 Windows 上不适用。

产物与报告位置：

| 内容 | 路径 |
| --- | --- |
| debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| APK 元数据（applicationId、versionCode/Name、minSdk） | `app/build/outputs/apk/debug/output-metadata.json` |
| JUnit XML（权威用例统计） | `app/build/test-results/testDebugUnitTest/TEST-*.xml` |
| 测试 HTML 报告 | `app/build/reports/tests/testDebugUnitTest/index.html` |
| Lint 文本/HTML/SARIF | `app/build/reports/lint-results-debug.txt` / `.html` / `.sarif` |

### 不要只看 `BUILD SUCCESSFUL`

Gradle 会因为任务 `UP-TO-DATE` 直接成功，而测试可能根本没重跑。必须读 XML 里的数字：

```bash
# 每个测试类一行
for f in app/build/test-results/testDebugUnitTest/TEST-*.xml; do
  printf '%s\t' "$(basename "$f" .xml | sed 's/^TEST-//')"
  grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$f" | head -1
done

# 聚合
grep -ho 'tests="[0-9]*"' app/build/test-results/testDebugUnitTest/*.xml | grep -o '[0-9]*' | awk '{s+=$1} END {print "tests",s}'
grep -ho 'failures="[0-9]*"' app/build/test-results/testDebugUnitTest/*.xml | grep -o '[0-9]*' | awk '{s+=$1} END {print "failures",s}'
grep -ho 'errors="[0-9]*"' app/build/test-results/testDebugUnitTest/*.xml | grep -o '[0-9]*' | awk '{s+=$1} END {print "errors",s}'
```

`app/build` 中最近一次记录的结果是 **17 个测试类、257 个用例、0 failure / 0 error / 0 skipped**；这是仓库里的历史产物，不是你本次运行的结果——以你刚跑出来的 XML 为准。用例数无故减少就该追查（多半是测试被删或 `@Test` 丢失）。

### 安装到设备

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- `applicationId` 是 `dev.oxype.client`；签名冲突时先 `adb uninstall dev.oxype.client`。
- 低于 Android 8.0 会 `INSTALL_FAILED_OLDER_SDK`。

## 3. 用 MuMu 模拟器 + fixture 联调

fixture 是 `tools/ui-fixture/fixture_server.py`，一个**内存态 Oxype 协议模拟器**，只为本机走查 UI（群聊气泡、头像/首字母、发送、删除）。它不是真实服务端，也不在 APK 内。

### 3.1 启动 fixture

```bash
cd tools/ui-fixture
python -I fixture_server.py
```

- `-I` 是 Python 的隔离模式（隐含 `-E`、`-s`、`-P`）：忽略 `PYTHON*` 环境变量、不加载用户 site-packages，也不把脚本目录加进 `sys.path`，避免环境里的同名模块顶替标准库。fixture 只用标准库，所以这样启动最干净。
- 监听 `127.0.0.1:18080`。健康检查：

```bash
curl -s -w '\n%{http_code}\n' http://127.0.0.1:18080/health
# 期望：{"status": "ok", "fixture": "oxype-protocol-simulator", "sessions": [42], "messages": 8} 与 200
```

- 停止：该终端 Ctrl+C（后台启动的进程则结束对应 PID）。状态只在内存，重启即重置为 8 条消息。

### 3.2 连接模拟器

仓库自带的 fixture 说明（`tools/ui-fixture/README.md`）用的 serial 是 `127.0.0.1:16384`；MuMu 多开时每个实例的 adb 端口不同，以 `adb devices` 的实际输出为准。

```bash
# 让 adb 连上模拟器（端口按 MuMu 实际实例改；多开时每个实例端口不同，见 adb devices）
adb connect 127.0.0.1:16384
adb devices

# 把设备上的 18080 反向转发到宿主机 18080
adb -s 127.0.0.1:16384 reverse tcp:18080 tcp:18080

# 确认反向转发存在
adb -s 127.0.0.1:16384 reverse --list
```

reverse 的端口必须与 fixture 一致（都是 `18080`）。

### 3.3 在应用内配置并登录

1. 安装并启动应用。
2. 登录页（或设置页）把服务器地址填成 `http://127.0.0.1:18080` 并保存。
   - 走 `adb reverse` 时**必须用 `127.0.0.1`**；默认的 `http://10.0.2.2:12345` 是模拟器直连宿主机的路径，不经过 reverse，fixture 监听在 `127.0.0.1` 上时也不可达。
   - 也接受不带 scheme 的 `127.0.0.1:18080`，客户端会补 `http://`。
3. 用 fixture 预置账号登录：用户编号 `1`，密码 `testpass1`。会话列表里应出现会话 `42`「周末徒步小队」，4 名成员（fixture 的批量 `getSessions` 路由要求 body 里有 `userid`，而客户端批量调用不带凭据，所以列表实际是通过逐会话 `getSessionInfo` 补全的——这是预期行为，不是故障）。
4. fixture **没有 `/register`，也没有 `createSession`/`joinSessionByUuid`**，注册、创建会话、加入会话在这里都无法验证（会 404）。

可走查的点（fixture 数据为它们准备）：

| 场景 | fixture 数据 |
| --- | --- |
| 单字符 / 中文 / 英文 / 长中文换行 | 消息 1、2、3、4 |
| 显式多行文本 | 消息 5（含 `\n`） |
| emoji | 消息 6 |
| 自己的消息（右侧、无头像列） | 消息 7、8（sender = 1） |
| 群聊显示发送者名与头像 | 成员 2 无头像字段、3 有合法 PNG、4 的头像 404 |
| 首字母兜底 | 成员 2（无字段）与 4（404） |
| 发送 | 输入后发送，fixture 会真实追加序号 |
| 删除 | 长按自己的消息删除，fixture 移除记录 |

### 3.4 必须明确的边界

**fixture 联调不是真实 Oxype 端到端验证。** 它不覆盖：裸数组响应、字段别名、`lastMessage` 读不懂时的 `Unknown` 分支、`403` 会话拒绝、软失败 `seq:-1` / `success:false`、注册/创建/加入会话、HTTPS 与重定向。这些只在 JVM 单元测试里（`ScriptedTransport` 脚本化路由）被验证。因此：

- "fixture 里点通了" ≠ "真实服务可用"。
- 不要写"已对真实服务器验证"之类结论。

## 4. 抓日志

```bash
# 全量（先清缓冲再操作，日志更干净）
adb logcat -c
adb logcat

# 只看崩溃与 Activity 管理器的错误（应用代码本身没有 Log 调用）
adb logcat -s AndroidRuntime:E ActivityManager:E

# 只看本应用：先取 PID，再按 --pid 过滤（应用需已启动）
adb shell pidof dev.oxype.client
adb logcat --pid=<上一步输出的 PID>

# 保存到文件
adb logcat -d > /tmp/oxype-logcat.txt
```

多设备（真机 + 模拟器同时连着）时，给每条命令加 `-s 127.0.0.1:16384`，与 `adb devices` 列出的 serial 一致。

应用内没有 `android.util.Log` 调用，所以行为问题通常要看：

- 界面上的错误条/错误提示（`ChatSnapshot.error`、`HomeUiState.error` 的文案直接来自 `OxypeException.message`）。
- fixture 终端输出：它打印每条请求的路径、`appended session=… seq=…`，以及 `fixture error: …`。这是观察客户端实际发了什么的最直接手段。
- 网络类失败（`NETWORK`）只会在 UI 上显示"无法连接服务器"/"请求超时，请检查服务器地址"，不会进 logcat。

要临时加日志排查时，用完删除；不要提交调试日志，也不要把 token 打进日志。

## 5. 常见故障与排查顺序

按"先环境、再连接、后协议"的顺序查，不要一上来改代码。

### 5.1 构建失败：`Unresolved reference` / AGP 与 Kotlin 插件冲突

先确认没有误加 `org.jetbrains.kotlin.android`：

```bash
grep -rn "kotlin.android\|kotlin(\"android\")" build.gradle.kts app/build.gradle.kts settings.gradle.kts
```

正确输出为空。AGP 9.4.1 已内置 Kotlin 支持，本工程的插件块只有 `com.android.application` 与 `org.jetbrains.kotlin.plugin.compose`；无需（也不要）再声明 `org.jetbrains.kotlin.android`。JVM 目标由 `compileOptions` 的 `sourceCompatibility`/`targetCompatibility = JavaVersion.VERSION_17` 决定，工程里没有 `kotlinOptions` 块，不要新增。

### 5.2 构建失败：离线缓存缺失（`--offline` 下找不到依赖 / Gradle 发行版）

症状：`No cached version of … available for offline mode` 或 `Could not download gradle-9.7.1-bin.zip`。

排查顺序：

1. 去掉 `--offline` 重跑一次，确认是缓存问题而不是仓库/代理问题。
2. 检查 wrapper 缓存是否存在：`ls ~/.gradle/wrapper/dists` 应有 `gradle-9.7.1-bin`。
3. 依赖缓存：`ls ~/.gradle/caches/modules-2`。缺失说明这台机器没跑过在线构建。
4. 若是公司代理/镜像导致下载失败，先修网络，不要靠改 `build.gradle.kts` 换依赖版本绕过。

### 5.3 连接失败：`无法连接服务器` / `请求超时，请检查服务器地址`

`HttpUrlConnectionTransport` 把 `IOException`/`SocketTimeoutException` 统一映射成 `NETWORK`（默认连接/读取超时 15 秒）。依次确认：

1. fixture 是否还在跑：`curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18080/health` 应为 200。
2. `adb reverse --list` 是否还有 `tcp:18080 tcp:18080`。设备重连、模拟器重启、`adb kill-server` 后 reverse 会丢失 —— 重新执行 `adb -s <serial> reverse tcp:18080 tcp:18080`。
3. 应用里填的地址是否是 `http://127.0.0.1:18080`（不是 `10.0.2.2:12345`）。
4. 服务器地址末尾多写了路径（例如 `/api`）会拼出 404；`normalizeBaseUrl` 只补 scheme、去尾部 `/`，不解析路径。
5. 若服务器要求 HTTPS，明文会被拒或收到 3xx；客户端不跟随重定向，会报"服务器返回了重定向…请确认服务器地址使用 https"。

### 5.4 fixture 退出了

- 症状：应用一直 `NETWORK` 错误，终端里没有新请求日志。
- 检查：`curl http://127.0.0.1:18080/health` 连接被拒；`ps`/任务管理器看不住 Python 进程。
- 处理：重新 `cd tools/ui-fixture && python -I fixture_server.py`。fixture 被抓到未处理异常时只打印 `fixture error: …` 并返回 500，进程本身通常还活着；如果真退出了，重启会**重置全部内存状态**（消息回到 1..8，已删除的会回来）。
- 端口被占（`Address already in use`）：先结束占用 18080 的进程，或确认上一次的 fixture 还在跑。不要改端口了事——客户端地址、`adb reverse`、fixture 三处必须一致。

### 5.5 登录后立刻回到登录页

- 若是启动恢复：`isAlive` 返回 401 才会登出；fixture 启动时会预置一个已发放的 token（见 `fixture_server.py` 的 `_tokens` 初始化），所以正常重启应用不会掉线；但如果之前点过"退出登录"，fixture 的 `logout` 会把这个 token 移除，需要重新登录。
- 若在列表/聊天里：任一请求收到 401 会走 `invalidateCredentials`。先看 fixture 控制台哪个路由返回了 401（通常是 token 与路径里的 userid 不一致，或服务端重启过）。
- 403 **不会**导致登出；如果看到"没有权限执行此操作"，那是会话级问题。

### 5.6 消息列表为空但会话存在

- fixture 下非成员会拿到 `403`（`lastMessage`/`getSessionUsers`）或空数组（`getMessages`）。
- 客户端在 `lastMessage` 失败时会退化为 1..100 的推测窗口；若 fixture 正常，应能看到消息 1..8。
- 确认服务端地址指向的是 fixture 而不是另一台设备上的旧实例。

### 5.7 测试通过但行为不对

先确认测试真的跑了（见第 2 节的 XML 统计）。若 XML 时间是旧的，说明任务 `UP-TO-DATE`：

```bash
./gradlew.bat --offline :app:testDebugUnitTest --rerun-tasks
```

## 6. 不要做的事

- 不要提交 `build/`、`app/build/`、`.gradle/`、`.kotlin/`、`*.apk`、`*.aab`、`*.log`、`local.properties`。
- 不要为通过测试修改关键不变量（详见根目录 `AGENTS.md` 第 4 节）。
- 不要在没有设备的情况下声称做过 UI 验证；不要在没有真实服务端的情况下声称做过 E2E 验证。
- 不要把 fixture 的地址、账号、密码写进面向用户的文档当作默认值（默认值仍是 `http://10.0.2.2:12345`）。
