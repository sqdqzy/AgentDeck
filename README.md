# AgentDeck

手机端 AI Agent 宿主。**网页版 AI 负责推理，手机本地作为工具执行环境。**

**[下载 APK](https://github.com/sqdqzy/AgentDeck/releases/latest)** · Android 8.0+（minSdk 26）· 免 root，Shizuku 为可选增强

AgentDeck 把 13 个主流 AI 网站收敛成一个「站点卡片」网格。点击任意卡片进入一个**纯网页**会话页——
网页自己的顶部栏、消息列表、输入框、按钮全部原样保留，App 不叠加任何界面，唯一叠加的是一个
48dp 的可拖动圆形悬浮按钮和一行状态提示。Agent 在后台以 `<tool_call>` XML 协议驱动网页模型，
模型请求的工具（读写文件、HTTP 请求、命令沙箱、Shizuku 设备控制）在手机上真实执行，
结果以 `<observation>` 回填给模型，形成闭环。

### 悬浮球的唯一职责

**单击悬浮球 = 把系统提示词（工具清单 + 协议规则）填进网页自己的输入框。**

它不发送。填完之后由用户自己补充问题、自己按站点自己的发送键。
用户已经写好的内容**保留在框里**，提示词追加在它前面——**只追加，不覆盖**。

长按（500ms）弹出菜单：

| 菜单项 | 行为 |
|---|---|
| 填入系统提示词 | 与单击等价，填入但不发送 |
| 查看系统提示词 | 弹窗预览当前提示词全文 |
| 复制系统提示词 | 复制到剪贴板 |
| 审计日志 | 跳转审计日志页 |
| 站点适配器 | 进入选择器校准 |
| 关闭会话 | 停止监听并退出 |

**没有「运行任务」「终止任务」**——监听是常驻的，不需要手动开启，见 3.5。

---

## 一、技术栈与版本矩阵

> 所有版本均经实测核验（AAR 元数据 `minCompileSdk`、API 签名反编译、官方源码比对），
> 不是从文档推测。

| 组件 | 版本 | 说明 |
|---|---|---|
| Gradle | 8.13 | AGP 8.13 的最低要求 |
| Android Gradle Plugin | 8.13.2 | |
| Kotlin | 2.3.21 | |
| KSP | 2.3.13 | 2.3.0 起改用独立版本号；要求 AGP ≥ 8.12、Gradle ≥ 8.13 |
| Compose BOM | 2026.06.01 | ui / foundation 1.11.4 |
| Material 3 | **1.5.0-alpha18** | 见下方「为什么是 alpha18」 |
| Hilt | **2.58** | 见下方「为什么不是 2.60」 |
| Room | 2.8.5 | |
| androidx.webkit | 1.18.0 | 多 Profile API（`setProfile` / `Profile` / `ProfileStore`） |
| Shizuku | 13.1.5 | Maven Central 最新版 |
| kotlinx.coroutines | 1.10.2 | |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 | |

### 为什么 material3 用 1.5.0-alpha18

Material 3 Expressive 的公开 API 有严格的版本门槛：

- **material3 1.4.0**（BOM 默认）：`MotionScheme`、`MaterialExpressiveTheme` 全部是 `internal`，
  外部模块根本调不到；`MaterialShapes` 不存在。
- **material3 1.5.0-alpha18**：`MaterialExpressiveTheme` 公开（其 `@Material3ExpressiveApi`
  是 `@RestrictTo(LIBRARY_GROUP_PREFIX)`，不是需要 opt-in 的实验注解）、`MotionScheme.expressive()`
  公开、`MaterialShapes` 35 形状库与 `Morph.toPath()` 公开。
  **`minCompileSdk = 35`、`minAgpVersion = 8.6.0`**，正好卡在硬性约束上。
- material3 1.5.0-beta01 及以后：`minCompileSdk = 37`，与 compileSdk 35 冲突。

结论：1.5.0-alpha18 是**唯一**同时满足「Material 3 Expressive」与「compileSdk 35」的版本。
BOM 的 1.4.0 在 `libs.versions.toml` 中被显式覆盖。

### 为什么 Hilt 用 2.58

Hilt 2.60.1 的 Gradle 插件直接拒绝 AGP 8：

```
The Hilt Android Gradle plugin is only compatible with Android Gradle plugin (AGP) version 9.0.0 or higher
```

Hilt 2.59 不报这个错，但运行时崩在：

```
NoClassDefFoundError: com/android/build/api/artifact/ScopedArtifact$POST_COMPILATION_CLASSES
```

该枚举常量只存在于 AGP 9.x。**Hilt ≤ 2.58 才能配 AGP 8.13**。

---

## 二、项目结构

```
app/src/main/java/com/agentdeck/
├── AgentDeckApp.kt              Hilt Application
├── AppViewModel.kt              跨页面共享状态（风险确认、设置、审计日志）
├── MainActivity.kt              唯一 Activity + NavHost
├── data/
│   ├── model/SiteCard.kt        站点卡片模型 + 区域枚举
│   ├── model/BuiltInSites.kt    13 个内置站点（中国区 9 + 海外区 4）
│   ├── db/                      Room：站点卡片、适配器、审计日志
│   ├── prefs/SettingsStore.kt   DataStore：沙箱等级、L2 开关、白名单、悬浮按钮位置
│   └── repo/SiteRepository.kt   站点与适配器持久化 + 各站默认适配器
├── web/
│   ├── SiteAdapter.kt           JSON 驱动的站点适配器模型
│   ├── WebViewHost.kt           DOM 桥接：注入、探测、分片提取、选择器捕获
│   ├── WebViewProfileManager.kt 每卡片独立 Profile
│   ├── SessionRegistry.kt       Activity ↔ 前台服务的 WebView 交接点
│   └── ShizukuManager.kt        Shizuku 三态 + 配置一致性校验
├── agent/
│   ├── AgentState.kt            状态机状态定义 + AgentStatus 快照
│   ├── AgentLoop.kt             常驻实时监听循环（探测 → 稳定 → 解析 → 执行 → 回填）
│   ├── XmlToolParser.kt         从末尾往前配对的标签闭合兜底解析
│   ├── ChunkedTextPuller.kt     分片拉取回复正文
│   ├── PromptTemplates.kt       内置系统提示词 + observation 构造
│   └── PromptProvider.kt        按当前权限动态构建系统提示词
├── tools/
│   ├── Tool.kt                  工具定义（name/level/schema/executor/timeout/maxOutput）
│   ├── ToolRegistry.kt          按当前权限动态构建注册表
│   ├── ToolProvider.kt          组装有效工具集
│   ├── L1Tools.kt               文件 + HTTP（路径围栏、域名白名单）
│   ├── L2Sandbox.kt             命令沙箱（白名单、清洗环境、限并发）
│   ├── L3Shizuku.kt             Shizuku 设备控制（固定命令模板）
│   └── AuditLog.kt              审计日志写入与读取
├── service/AgentForegroundService.kt  前台服务承载常驻监听
├── core/
│   ├── CrashHandler.kt           全局 UncaughtExceptionHandler
│   ├── CrashReporter.kt          异常捕获、日志落盘、错误上报
│   ├── ErrorActivity.kt          报错窗口（独立进程 :error）
│   ├── LogExporter.kt            复制到剪贴板 / 写入 Downloads / 分享
│   └── DebugCrashReceiver.kt     仅 debug：用于验证报错窗口的调试入口
├── di/                          Hilt 模块
└── ui/
    ├── home/                    首页两列网格、卡片编辑弹窗
    ├── session/                 会话页（全屏 WebView + 悬浮按钮）
    ├── settings/                设置、审计日志、适配器校准、关于
    ├── onboarding/              风险声明
    ├── error/ErrorScreen.kt      报错窗口界面（固定经典蓝）
    ├── widget/                  FloatingAgentButton / FloatingMenu
    └── theme/                   Material 3 主题 + 报错窗口专用经典蓝配色
```

---

## 三、核心机制

### 3.1 XML 提取的三层判断逻辑

**第一层 · 时序门禁**
`AgentLoop.awaitStableMessage` 独立追踪三个信号：停止按钮消失 / textContent 连续 1200ms 不变 /
DOM 节点数稳定。文本稳定且站点停止生成（或 DOM 也稳定）才进入 PARSING。流式输出中途绝不会被提取。
单条消息最长观察 120s，之后取当前内容。

**第二层 · 分片拉取（防长度截断）**
`evaluateJavascript` 的回调会被截断，因此：

1. JS 侧把最后一条 assistant 消息存入 `window.__agentText`；
2. Android 侧读取 `window.__agentText.length`；
3. 按 64K 字符分片拉取，页面侧 `encodeURIComponent` 编码，
   **分片边界不切分 UTF-16 代理对**，Android 侧 `URLDecoder.decode` 拼接还原。

**第三层 · 标签闭合兜底**
AI 用 `write_file` 写入含 XML 字面量的文件时，content 内会出现字面量标签，导致外层标签被提前闭合。

- *协议层*：系统提示词强制 content 中 `<` 写作 `\u003c`、`>` 写作 `\u003e`；
  超过 8000 字符拆成 `mode=overwrite` + `mode=append` 多次写入。
- *解析层*：`XmlToolParser` 分两段工作。
  1. **上下文扫描**：先扫一遍文本，标记出所有落在 `<args>` 载荷的 **JSON 字符串字面量**内的位置。
     写在引号里的标签是模型引用的内容，不是标记，绝不能充当块边界。
  2. **反向配对**：**从字符串末尾往前**找最后一个**未被引用**的闭合标签，再往前配对最近的
     **未被引用**的开标签；取出该块后把 `searchEnd` 移到该开标签**之前**继续。
     同时要求块体恰好分解为 1 个 `<name>` + 至多 1 个 `<args>`，剩余部分不得含 `<`。
  因为外层闭合标签总是文本中最后一个，而被引用的标签不参与配对，
  **内层字面量标签（无论开标签还是闭标签）都不会提前截断或劫持外层块**。
  **禁止**非贪婪正则从头匹配，**禁止**标准 DOM 解析器直接 parse 整段
  （payload 可能含未转义的 `<`，不是良构 XML）。
- *审计层*：每次解析记录 tool_call 数量、每个块的字符长度、是否走兜底路径。

> **实测覆盖**：解析器经 40 条独立断言验证（`XmlToolParser` + `L1Tools` 路径沙箱在 JVM 上直跑），
> 包含「payload 内嵌完整 `<tool_call>…</tool_call>` 引用」「payload 含字面量闭标签」
> 「多行/转义引号载荷」「未闭合块不产生调用」「纯工具块不误判为 final」等场景。

### 3.1.1 final 提取与循环终止

模型可以用 `<final>结论</final>` 收尾，也可以直接用自然语言回答。解析层的规则是：

1. 显式 `<final>` 元素优先；
2. 否则取工具块之外的**自由文本**作为结论；
3. 但若自由文本中含 `<tool_call` / `<name>` / `<args>` 等 XML 碎片，
   判定为**格式错误**（触发格式纠正重注入），而不是误当作答案。

由此，`AgentLoop` 的终止判定 `hasFinal && !hasToolCalls` 成立：
有工具调用时不终止，纯文字回答时正确进入 FINAL 态。

### 3.2 工具与沙箱

| 层级 | 工具 | 约束 |
|---|---|---|
| L1（永远可用） | `read_file` `write_file` `list_dir` `web_fetch` `http_request` | 路径限定 `filesDir/agent-workspace/<sessionId>/`，canonical path 防 `../` 逃逸；**绝对路径（POSIX 与 Windows）一律拒绝**，不做静默重写；HTTP 域名白名单、20s 超时、256KB 截断 |
| L2（开关控制） | `run_command` | 命令白名单 `ls/cat/echo/grep/find/date/wc/head/tail`；工作目录锁定 workspace；30s 超时；64KB 输出截断；环境变量清洗；并发进程上限 4 |
| L3（需 Shizuku） | `tap` `swipe` `key_event` `read_clipboard` `read_notifications` `open_url` | 全部走预定义命令模板，AI 无法自由拼 shell；坐标/按键/URL 均做白名单校验 |

工具注册表按当前权限**动态构建**：模型看到的工具清单 = 用户实际授权的工具清单。
未授权 Shizuku 时 L3 工具根本不出现在注册表里。

### 3.3 WebView 多 Profile 隔离

每张卡片使用独立 profile `agentdeck-<cardId>`。`WebViewCompat.setProfile` 必须在对
WebView 做任何其他操作之前调用，否则 provider 抛 `IllegalStateException`。
每个 Profile 有独立的 CookieManager 与 WebStorage，登录态完全隔离且重启后保留。
首页卡片底部的登录状态圆点即来自该 Profile 的 Cookie 查询。

### 3.4 反检测

UA 换成真实 Chrome 串（去掉 WebView 默认的 `wv` 标记）；`navigator.webdriver`、
`window.chrome`、`navigator.plugins`、`navigator.languages`、`navigator.permissions`
全部在文档开始时打补丁；注入节奏加 0–400ms 随机抖动；用 `InputEvent` 合成事件；
不启用 Accessibility 注入路径。设置页明确告知自动化可能违反服务条款。

### 3.5 前台服务与常驻实时监听

Agent Loop 跑在 `AgentForegroundService` 中。WebView 必须留在 Activity（用户要看页面），
因此由 `SessionRegistry` 负责交接：会话页发布 WebView，服务取走驱动它。

**监听是常驻的，没有「开始任务」按钮。** 会话页一打开，前台服务就启动，`AgentLoop` 立刻进入
`IDLE → 探测 → 稳定 → 解析 → 执行 → 回填` 的循环。每 700ms 探测一次页面，用
`长度:签名` 指纹去重，同一条消息只处理一次。

为什么这样设计：网页模型往往是在**对话进行到一半**时才请求工具的。如果必须先点一个
「运行任务」按钮，这个最常见的场景就没法处理；而一个「任务开始」按钮点了没反应
（只是打开检测），除了让人困惑之外没有任何作用。

停止监听的唯一方式是「关闭会话」——那是语义上真实存在的动作。

监听状态在**通知栏**和**会话页左上角一行状态提示**上同时可见：
`监听中` / `等待网页回复` / `正在解析网页回复` / `正在执行 <tool>` /
`正在回填执行结果` / `模型已给出结论`。
状态提示始终只有一行，绝不会长成面板挡住聊天。

### 3.5.1 选择器校准：不遮挡页面

校准的目的是**让用户点击真实网页上的目标元素**。所以校准界面绝不能覆盖页面：

| 曾经的错误做法 | 现在的做法 |
|---|---|
| 全屏 Scaffold 覆盖整个 WebView | 顶部一个 ~50dp 的小 chip（可拖拽） |
| 底部弹出「确定 / 发送」按钮面板 | 设置项放在用户主动打开的**模态对话框**里 |

chip 上单击或长按打开设置对话框（选择要校准的字段 / 手动输入选择器），
右侧一个关闭按钮退出校准。

> **实测**：修复前，展开的校准浮层吞掉了它覆盖范围内 **16/21** 个网格采样点的全部触摸，
> 用户根本点不到网页元素；修复后 **2/21**，与「无浮层」基线完全一致
> （那 2 个点被站点自身元素拦截，与本应用无关）。

### 3.5.1.1 消息去重：同一条回复只处理一次

监听器靠指纹识别「这条消息处理过了」。指纹由**消息序号 + 文本长度 + 文本签名**组成，
三者缺一不可：

- 文本长度与签名，用来识别同一条消息在流式输出中不断增长的过程；
- 消息序号，用来区分「同一条消息」与「内容完全相同的新回复」。

> **踩过的坑**：回填 observation 之后，早期实现把指纹**清空**了，指望这样能
> 让模型对回填内容的回答被当成新消息。但回填**不会**改变页面上最后一条
> assistant 消息——它仍是刚才那条 tool_call。指纹被清空后，这条没变的消息
> 又被当成新消息，于是**同一个工具调用被无限重复执行**（实测 45 秒内执行了
> **19 次**）。
>
> 正确做法是**不清空**指纹：模型的新回答长度和签名自然不同，会被自动识别为新消息。
> 修复后 45 秒内稳定为 **1 次**。

另外，`formatRetries` 与 `PromptTemplates.buildFormatCorrection` 这段原本没有接上的
死代码也一并启用：解析不出工具调用、但回复里含有 XML 碎片时，说明模型想写
工具块却写坏了，此时注入一次格式纠正（最多 2 次）而不是干等。

### 3.5.2 Web 容器：站点 CSS 完整渲染

聊天页曾经出现「CSS 全丢」：纯白或纯灰的界面、只有几个按钮、输入框跑到页面最顶部、
或者页面上半截正常下半截错乱。早期把原因归到 UA 与 client hints 不一致、全局代理
导致 TLS 失败——**那两条都不是真正的原因**，真正的根因是：

**布局视口高度被算成了 0，而且引擎此后不再纠正它。**

`100vh` 的值就是布局视口；`(min-height:…)` 媒体查询也走同一个数字。当 WebView 被
以「高 0」测量一次，引擎把这个 0 记成布局视口，之后 `window.innerHeight` 会报告
真实高度，但 `vh` 永远是 `0px`。于是：

| 现象 | 原因 |
|---|---|
| 纯灰 / 纯白，一个字都没有 | 根容器 `height:100vh` = 0，整页高度 0，只剩背景色 |
| 只有几个按钮（文心一言） | 按钮有像素高度，其余靠 `vh` 撑的骨架全塌 |
| 输入框跑到最顶部（豆包、元宝） | flex 容器高度 0，子项全部挤在顶部 |
| 黑白鸳鸯锅（智谱） | 部分区域用 `px`/`rem` 正常，部分用 `vh` 塌掉 |

**决定性对照实验**（同一台设备、同一个引擎 `Chromium 110.0.5481.154`，渲染同一个
`100vh` 测试页）：

| 承载方式 | `100vh` |
|---|---|
| 设备自带浏览器 | **640** ✅ |
| `setContentView(FrameLayout)` + `addView` | **640** ✅ |
| Compose `AndroidView { webView }` | **0** ❌ |

设备与引擎都没问题，**只有 Compose 的挂载路径会产出「高 0」的测量**。另外还实测到：
改 UA、改 `useWideViewPort`/`loadWithOverviewMode`、改 client hints、重新 `loadUrl`、
`Page.setDocumentContent`、`Emulation.setDeviceMetricsOverride`、窗口旋转、缩放抖动——
**全部无法修正 `vh`**（旋转后 `vw` 会跟着变，`vh` 仍是 0，说明只有高度轴坏死）。

修复：**把网页从 Compose 里搬出来**，直接挂在 Activity 的根 `FrameLayout` 上（正是
实测中 `vh=640` 的那种挂法），Compose 作为同一容器里的上层透明图层。

```
root (FrameLayout)
  ├── child 0: 会话页 WebView（运行期插入，MATCH_PARENT）
  └── child 1: OverlayHost → ComposeView（上层 UI）
```

配套改动三处：

1. **`WebViewShelf`**：唯一决定网页挂在哪的类。`forceRelayout` 用 `EXACTLY` 规格重新
   测量一次，这是唯一能把引擎里那个 0 改掉的形状；`onPageFinished` 后探针仍报 `vh=0`
   就调用它，作为第二道防线（探针此前因双重 JSON 编码从未生效，已修）。
2. **`OverlayTouch` / `OverlayHost`**：Compose 全屏图层会吃掉整个手势流——实测点击
   页面时文档收到 **0 个**触摸事件。现在覆盖层只在它真正画了控件的矩形内拦截（悬浮球、
   菜单、校准胶囊），其余触摸全部交给网页。这同时修掉了「按钮全屏覆盖、点不到网页」。
3. **UA 改为移动版 Chrome**（Android 14 / Pixel 8，仍不含 `wv`）。桌面 UA 会让站点
   下发桌面布局，在 360dp 宽的屏幕上侧边栏就吃掉三分之二；移动 UA 拿到的才是手机版
   布局。实测 `vh` 在两种身份下取值相同，因此换 UA 不会让塌陷问题复发。

> **实测（9 个内置站点，逐站点开页面 + CDP 探针 + 截图统计）**：
> `deepseek / kimi / doubao / lingguang / yuanbao / chatglm / qwen / yiyan / nvidia`
> 全部 `vh=640`、`(min-height:1px)` 媒体查询命中、截图颜色数 243～327112（修复前为 1）。
> 复验脚本：`tools-verify/sites_verify.py`。

另经核对，站点 CSS 只用了 `:has()`（Chrome 105+）与 `color-mix()`（Chrome 111+，仅降级不丢样式），
不存在「浏览器版本太低导致样式丢失」的情况。

**遗留验证限制**：本轮在 MuMu 虚拟机上无法验证「手指能否点到网页」——该模拟器把每个
应用渲染到独立的虚拟显示，`input tap` 注入的却是主显示的桌面，`screencap` 也只截到
桌面。渲染结论来自 CDP（渲染进程级，与显示无关），触摸需在真机上确认。

### 3.5.3 发送动作：写入、定位、确认

「站点适配器里校准了发送按钮，点了还是不发」——这不是定位没生效，而是**两个独立的缺陷
叠在一起**，任一个都会让点击变成空动作。

#### 缺陷一：写进去的值，站点没有认

`el.value = text` 再派一个 `input` 事件，只改了 DOM，没有走浏览器的编辑管线。框架没有
记录这次编辑，它的发送按钮就一直保持禁用状态。DeepSeek 的按钮实测同时带着
`ds-button--disabled` 类和 `opacity: 0.4`，点它没有任何反应。

| 写入方式 | DeepSeek 发送按钮 |
|---|---|
| `value` setter + `new Event('input')` | 仍禁用（`opacity: 0.4`）❌ |
| `execCommand('insertText')` | 立即启用（`opacity: 1`）✅ |
| 输入法通道（`InputConnection.commitText`） | 立即启用 ✅ |

现在的写入分三级，按「最像真人」的顺序：

1. **输入法通道**（首选）。`webView.onCreateInputConnection()` 拿到 Android 的
   `InputConnection`，先 `performContextMenuAction(selectAll)` 清选区，再
   `commitText()`。这是硬件键盘和输入法走的同一条路，**每个编辑器都认**——智谱的
   Ant Design textarea、Kimi 的 ProseMirror 只接受这条路径。
2. **`execCommand('insertText')`**。走浏览器编辑管线（`beforeinput` → 变更 →
   `input`），对普通 textarea 够用。
3. **`value` 赋值**。最笨、哪儿都能写、但站点看不见。放到最后，并且补发
   `beforeinput`／`input`／`change`。

三级都会在读回输入框长度后判定，**不以「调用没抛异常」为成功**。

#### 缺陷二：发送按钮不一定是 `<button>`

内置适配器里那些手写的 `button.send-button`、`button.send-btn` 在今天的页面上几乎全部
落空，而真实控件长这样：

| 站点 | 真实发送控件 |
|---|---|
| DeepSeek | `div[role=button].ds-button--primary.ds-button--circle` |
| Kimi | `div.send-button-container`（无 `role`） |
| 智谱 | `div.send-button-right` > `div.button-right-inner`（监听器在内层） |
| 灵光 | `div.input-button-SEl1BODX`（哈希类名，无 `role`、无 `send`） |
| 千问 | `button.send-button[aria-label="发送"]` |
| 文心一言 | `div.cs-input-ds-send-btn` |

定位因此分三层：**校准值**（用户 pin 的 selector，可用即用）→ **自命名的控件**
（class/aria-label/testid 含 `send`/`submit`/`发送`）→ **形状与位置评分**（在写作区
内、与输入框同一水平带、位于输入框右半侧、尺寸像按钮；麦克风/上传/插件/登录/ICP备案等
邻居扣分）。

点击时先用 `document.elementFromPoint(中心点)` 取**最内层节点**再派事件——智谱的监听器
绑在里层 `div` 上，直接点外层容器不会触发。

#### 缺陷三：点了不等于发了

旧实现只要 `click()` 没抛异常就返回 `true`，然后开始等一个永远不会来的回复。现在每次
按压后都要向页面求证：

- 输入框把文字交出来了，**或**
- 消息数量增加了，**或**
- 那段文字出现在页面正文里（对发送后仍保留草稿的站点，这是唯一证据）

三种手法依次升级：`click` → 完整指针序列（`pointerdown`/`mousedown`/`pointerup`/
`mouseup`，照顾只监听 `pointerdown` 的控件）→ 回车键。全部失败会明确报「站点没有接收
这条消息」，而不是假装成功。

> **实测（`tools-verify/send_check.py`，脚本在运行期从 `WebViewHost.kt` 抽取，与
> 应用执行的是同一份代码）**：
> `deepseek / doubao / yiyan / qwen / chatglm / nvidia` → **SENT**；
> `kimi / yuanbao / lingguang` → 链路正常但触发站点登录墙（**NEEDS LOGIN**）。
>
> **端到端实测（`tools-verify/send_live.py`，走应用自身代码路径）**：
> ```
> input injection: 1174/1174 chars via input method
> send press[click]: ok=true target=ds-button--primary... at=298,486
> send confirmed: box 1174->0, messages 0->2, mentions 0->1
> ```
> 随后 AgentLoop 自动回填 147 字符的格式纠正并再次发送——**回填→发送这条链路本身也通了**。

调试构建可以用 `agentdeck://session/<id>?fill=1` 让会话页自己完成一次「填提示词 → 发送」，
用于在没有可用触摸注入的设备上验证这条路径。

### 3.5.4 工具调用协议：样例、容错、不打断生成

发送通了以后暴露出来的是四个叠在一起的问题——它们的共同表现都是「发了，但什么都没发生」。

**一、提示词里没有样例。** 规则只写了「输出 XML」「args 必须是合法 JSON」，从没给出一个完整
的块。设备上 DeepSeek 于是把 `<tool_call>` 当外壳、把 JSON 直接塞进去：

```
<tool_call>
{"name":"list_dir","params":
```

解析器要求 `<name>` 与 `<args>` 两个子元素，这样的回复必被判为格式错误，纠正两次后「已跳过」。
修复：提示词给出**逐字可抄的样例**（`<tool_call>` / `<name>list_dir</name>` /
`<args>{"path":"."}</args>` / `</tool_call>`），周期提醒也复述形状。
明确只在文字里禁止「只写 JSON」，**不给出错误样例的代码块**——模型会照抄最近的例子。

**二、九个站点是九个厂商的模型，协议一定会漂移。** 所以解析器加了有界恢复：正文没有 `<name>`
元素时，按 JSON 载荷解码，工具名取 `name|tool|tool_name|toolName`，参数取
`args|params|arguments|parameters|input`，并解开被二次编码的载荷与 OpenAI 的
`{"function":{...}}` 形状。恢复出来的调用在审计日志里标记 `usedFallbackParse`。
解析器保持零依赖（单测里 `org.json` 是 stub），JSON 走自己的扫描器。

**三、在模型还在写的时候就注入，会把生成打断。** 新的判据不看站点：「开了没关的
`<tool_call>` 就是还在写」。看到一个未闭合的块就继续等，并把指纹留着让下一轮重读同一
条消息；等到超过 24 轮仍未闭合，才判定为模型放弃了这次调用。

> 为什么不用站点自己的「停止」按钮：`tools-verify/stop_probe.py` 在生成过程中扫了整页，
> DeepSeek **没有任何元素**在 class / aria-label / title / data-testid 里带
> `stop`、`abort` 或 `停止`——它是个无标签图标。基于名称的探测器会一直报「已结束」，
> 比没有更糟，因为它看起来是可信的。

**四、草稿变长后，发送按钮定位会走飞到消息区。** 定位器原来从输入框向上固定走 6 层找
composer。输入框随草稿长高时，6 层已经落在消息列表里，于是选中模型回答里的一个可点
`span` 按下——真实按钮在下方 130 像素处，从未被试过。实测同一个页面：旧逻辑按在
`218,458`（无 class），新逻辑按在 `314,582` 的 `ds-button--primary`（34×34）。

修复：向上走**直到不再是 composer 尺寸**（父节点高度超过输入框 2.2 倍或宽度超过 1.6 倍
即停），并改用「到 composer 右下角的距离」评分，不再用「与输入框中心线对齐」——后者在
高输入框下必然失效。另外增加视口过滤：视口外的控件按不到。

**五、发送失败不再终止监听。** 观察结果已经写进输入框、只是按下没生效时，用户仍可手动
发送；为此结束整个会话是过度反应。现在只更新状态行并继续监听。

> **单元测试**：`XmlToolParserTest` 与 `PromptTemplatesTest` 共 26 项全通过。
> 运行需要 JDK 21——JDK 24 配 Gradle 8.13 会在配置阶段报
> `Could not create task 'AndroidUnitTest' ... Type T not present`，与本项目代码无关。
> 命令：`./gradlew -Dorg.gradle.java.home=<jdk21> :app:testDebugUnitTest`。

> **端到端实测（`tools-verify/send_live.py`，修复后，全新对话）**：
> ```
> input injection: 1477/1477 chars via execCommand     ← 系统提示词
> send confirmed: box 1477->0, messages 0->2, mentions 0->1
> input injection:   96/96  chars via execCommand      ← list_dir 的观察
> send confirmed: box  96->0, messages 3->5
> input injection: 1209/1209 chars via input method    ← read_file 的观察（输入框已撑高）
> send confirmed: box 1209->0, messages 6->5
> ```
> 模型最终的 `<final>` 准确说出工作目录里的两个文件与其字节数。此前同样长度的观察
> （605 字符）在这一步失败：`send did not take`。

### 3.6 系统提示词：只能追加，不能覆盖

内置提示词承载协议规则（XML 契约、`observation` 回传、格式纠正、`\u003c` 转义、
8000 字符拆分）与工具契约，**一旦被覆盖，Agent Loop 必然失效**。
因此设置页的「系统提示词」改为**追加**语义：

- 内置提示词永远保留，`@@TOOLS@@` 与 `@@WORKSPACE@@` 两个占位符照常注入；
- 用户填写的内容以 `# 用户补充说明` 段落**追加在内置提示词之后**。

该语义由 `PromptTemplatesTest` 与独立验证脚本各断言一遍。

同一语义也适用于**填入网页输入框**：单击悬浮球时，先读出输入框里已有的内容，
提示词写在前面、用户内容保留在后面，**不自动发送**。

### 3.6.1 提示词的动态构建

`PromptProvider`（`@Singleton`）在每次需要时按当前设置重建提示词：

- 工具清单 = `ToolProvider.tools(sandboxLevel, l2Enabled, disabledTools)` 的实际结果，
  未授权的工具根本不出现在清单里；
- `@@TOOLS@@` / `@@WORKSPACE@@` 占位符照常注入；
- 用户补充说明追加在内置提示词之后。

因此提示词里看到的工具，永远就是这台设备此刻真正能调用的工具。

### 3.7 报错窗口

应用发生**任何形式**的报错都不会直接关闭，而是弹出报错窗口显示完整日志。
四个操作：**复制日志**、**下载日志**、**重启应用**、**退出应用**。

设计要点：

| 要点 | 做法 | 原因 |
|---|---|---|
| 捕获 | `CrashHandler`（全局 `UncaughtExceptionHandler`） | 覆盖所有线程的未捕获异常 |
| 已捕获的错误 | `CrashReporter.report(origin, throwable)` | Agent 循环、前台服务的异常也走同一个窗口，而不是被状态行吞掉 |
| 进程隔离 | `ErrorActivity` 声明 `android:process=":error"` | 主进程崩溃后状态不可用，无法承载可用界面 |
| 不杀应用 | **不调用** `previous?.uncaughtException()` | 调用它会触发平台的「应用已停止」对话框并结束进程，违背需求 |
| 保留任务栈 | Intent 只用 `FLAG_ACTIVITY_NEW_TASK` | 加 `FLAG_ACTIVITY_CLEAR_TASK` 会清掉主任务，关掉窗口就回不去了 |
| 重启 / 退出 | 显式 `Process.killProcess` 杀主进程 | 报错窗口在独立进程里，`Runtime.exit` 只杀自己 |
| 配色 | 固定经典蓝（`ClassicBlue.kt`），浅色/深色两套 | 不跟随壁纸取色，出错时界面在所有设备上必须一致；但仍跟随系统深浅色 |

日志同时写入 `filesDir/crash-logs/agentdeck-error-<时间戳>.log`，
「下载日志」通过 MediaStore 写入系统 Downloads 目录（Android 10+），
Android 9 及以下走 FileProvider。

---

## 四、构建

```bash
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

### 环境注意事项

本机工程位于含中文的路径下，AGP 的 Windows 路径检查会拦截，`gradle.properties` 中已加：

```properties
android.overridePathCheck=true
```

---

## 五、验证

`tools-verify/` 下是针对模拟器的端到端验证脚本。它们不依赖手工操作，
全部自己完成「开应用 → 造场景 → 断言」的全过程。

| 脚本 | 验证内容 | 实测 |
|---|---|---|
| `run_verify.py` | 解析器 40 条断言 + L1 路径围栏 + 提示词追加语义 | **49/49** |
| `session_verify.py` | 会话页打开、CDP 附加、页面渲染指标 | **STYLED** |
| `cdp_probe.py` | 纯 socket 的 DevTools 协议客户端 + 页面样式探测 | — |
| `ui_verify.py` | 无障碍树断言：悬浮按钮、菜单项、**菜单中无「运行任务/终止任务」**、实时状态条 | **13/13** |
| `calibration_verify.py` | 校准 chip 不遮挡页面，**真实点击网页元素能捕获选择器** | **6/6** |
| `prompt_fill_verify.py` | 单击悬浮球 → 提示词入框 → 用户原话保留 → **未自动发送** | **10/10** |
| `watch_verify.py` | 常驻监听**无需任何用户操作**即完成「检测 → 执行 → 回填」，且**只执行一次** | **11/11** |
| `repeat_check.py` | 工具调用重复执行检测（同一消息只处理一次） | 45s 内恒为 1 |
| `touch_block.py` / `touch_map.py` | 浮层开/关时网页触摸命中率的定量对比 | 16/21 → 2/21 |
| `send_check.py` | 九个站点的写入 + 定位 + 发送（JS 在运行期从源码抽取） | 6 SENT / 3 NEEDS LOGIN |
| `send_live.py` | 走应用自身代码路径的「填提示词 → 发送」端到端 | 3 步注入全部送达 |
| `find_send_probe.py` | 长草稿（输入框撑高）下发送控件是否仍被正确定位 | 314,582 ✅ |
| `stop_probe.py` | 生成过程中整页扫描「停止」控件（结论：不存在标签） | 0 hits |
| `prompt_shape_probe.py` | 从源码取提示词原文重放，看模型是否给出 `<name>/<args>` | 形状一致 ✅ |

```bash
export AGENTDECK_DEVICE=127.0.0.1:7555
python tools-verify/prompt_fill_verify.py deepseek
python tools-verify/watch_verify.py deepseek
python tools-verify/ui_verify.py deepseek
python tools-verify/calibration_verify.py deepseek
```

> MuMu 模拟器把应用跑在**虚拟显示器**上，`screencap` 抓不到画面，
> `input` 事件也必须带 `-d <displayId>` 才能送达应用所在的显示器。
> 验证脚本因此以 `uiautomator dump` 的无障碍树作为 UI 层取证手段，
> 以 CDP 作为页面层取证手段。
>
> 另外两点实测踩到的坑：adb server 会被回收（脚本内每次 adb 前先 `connect`）；
> 消息条 2.6 秒后自动消失（断言必须轮询而不是读一次）。

---

## 六、合规声明

本应用通过 DOM 注入模拟输入与点击驱动第三方 AI 网站。

- 自动化交互**可能违反目标网站的服务条款**，账号可能被限流或封禁；
- 自动发送与工具执行**可能修改、覆盖或删除数据**；
- 授予 Shizuku 权限后，应用进程**具备系统级操作能力**；
- 建议**仅在个人账号与可控环境中使用**。

首次启动必须阅读并确认风险声明页方可进入。
