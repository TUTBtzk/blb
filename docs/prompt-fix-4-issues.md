# 任务：修四个真机问题（在已完成的 v6 实现上继续改）

上一轮的实现记录在 `docs/v6-implementation-and-verification.md`，设计说明在 `docs/plan-v2-catalog-and-ledger-audit.md`。**先把这两份读完**，再动手。下面的行号以当前工作区为准，可能有一两行偏移。

先重申不变的口径（上一轮已经定好的，这一轮继续遵守）：

- 纯判据拆成 static 纯函数并写 JVM 单测；碰数据库/碰屏幕的代码不写单测。
- 注释写「为什么」，并且带上这个决定是被哪次真机事故逼出来的。
- 读不到的数字一律 `-1`（不知道），绝不用 `0` 顶替。
- 涉及钱的判断 fail-closed；不确定就停下，不许猜。
- 不加第三方依赖，不引入 Kotlin/协程。
- **不许编造真机现象**。设备连不上就如实写「待真机验证」，并列出待验证清单。

这一轮有四个问题，**四个都要改**，改完按第 5 节的格式汇报。

---

## 问题 1：目录扫描漏掉「番外」，只扫到正文最后一章

### 现象
用户点「同步目录」后，账本里只有正文（第 1 章 ~ 正文最后一章），**番外一章都没有**。

### 已知线索
- 仓库根目录的 `小说目录.txt` 是这本书的目录文本：正文各卷形如 `第N章 标题`（分节标题写作 `【铃兰花】``【星彩】``【红莲】``【焰火】``【凌唯】`），而 **`【番外】` 分节下的条目是「番外 藏在地下室的恶鬼（上）」这种没有「第N章」的写法**。
- `CatalogScanner.finish()`（约 416 行）当前的判据是：`printedNo < 0` 的行一律进 `out.skipped`（注释写的是「卷标题同样有勾选框，绝不能当成可以逐章购买的行」），只有 `printedNo >= 0` 才进 `out.chapters`。
- `Texts.rowChapterNo()` 只认行首的 `第N章/第N张/第N ` 形式；「番外 藏在地下室的恶鬼（上）」返回 `-1`。

### 你必须先取证，不许直接猜
1. 先看一次真机「同步目录」的日志里那句 `跳过 N 行卷标题（…）`：
   - 如果番外标题出现在这份跳过名单里 → **分支 A：番外被当成卷标题丢掉了**；
   - 如果番外标题既不在跳过名单里、也不在账本里 → **分支 B：扫描提前结束了（没滚到番外那几行）**，那时去看 `scrolls / truncated / gapNote`。
2. 用项目已有的节点探测器（`ui/InspectorActivity`、`auto/InspectorCapture`、debug 的 `ControlReceiver`）在真机上把这三行的无障碍节点**并排抓下来**：
   - 正文最后一章那一行；
   - `【番外】`（或界面上的分节标题）那一行；
   - 第一个番外章那一行。
   要比对：行文本、view id、有没有可点祖先（`NodeMatcher.clickableAncestorOf`）、行内有没有 `Keys.CHAPTER_LOCKED / CHAPTER_OWNED / CHAPTER_SELECTABLE`、行高、子节点数。
   **把这段 dump 原样贴进报告**，判据必须由它推出来。

### 分支 A 的改法（最可能）
把「这一行是不是章节行」从一个数字判据改成一个**可测的纯函数**，例如 `CatalogScanner.isChapterRow(...)`，放进 `app/src/test` 钉死：

- 有行首标号（现状）→ 章节行；
- 无标号 → 按第 1 步取证出来的结构差异判定；判据要能同时满足：
  - **番外章行进 chapters**；
  - **分节标题行（`【铃兰花】``【番外】`「世界线的变动，学生会长的恋爱」这类）绝不进 chapters** —— 它们带勾选框，进去之后「立即下载」会一次买下一整批（`SubscribeTask` 里「已选必须正好 1 章」那道护栏会拦下来，但账本从那一刻起就是错的）。
- **判据不足时的兜底**：不许静默丢弃。把无法判定的行单独列进报告，例如把 `Report.skipped` 拆成「卷标题（已跳过）」和「无标号、无法判定（请核对）」两类，日志和结论里分开写。用户宁可看到「这 3 行我没敢记」，也不能再出现「番外悄悄没了」。

### 分支 B 的改法
`CatalogScanner.scan()` 的到底判据现在是「连续两屏 signature 完全相同」。分节标题、懒加载、页脚都可能造成假到底。改成必须同时满足：连续两屏内容不变 **且** 列表容器确实滚不动了（`scrollForward` 失败或容器已到 `scrollY` 上限），任一条不成立就不算到底，如实报 `truncated`。

### 连带必须一起改（不做这些，收了番外反而更糟）
- **`CatalogScanner.findGap()`**：它比较相邻两行的 `printedNo`。番外行没有标号，必须改成「只有相邻两行**都有**标号时才做连续性检查」，否则会误报「缺 N 行」并直接拒绝写账本。
- **章号仍然是「界面第几行」**：番外接在正文后面继续编号（612、613…），不要另起编号，也不要给番外单独编号。
- **`CatalogAlign`**：按标题搬家对无标号行同样要成立（标题就是行文本本身）。
- **`SubscribeTask.findRow()`**：它按 `chapter.title` 精确匹配行文本，番外行必须能被 `findExactText` 命中；`requireUniqueChapter` 要求全标题唯一 —— 用真实番外标题补一条测试。
- **核对订阅清单对番外**：`SubscribedDetail.parseRow` 对「番外 藏在地下室的恶鬼（上）」解析不出章号（`Entry.known() == false`），这些条目现在落进 `unknown` 分支只报警。番外进账本之后，**明细里的番外条目必须能按「卷名 + 标题」唯一匹配到本地目录行**，否则每一趟核对都会因为番外而对不上。改 `RemoteLedgerRecovery.resolve`：无章号的条目在有唯一标题匹配时也参与比对；匹配不唯一就照旧只报警、不猜。
- **重扫幂等**：作者新增一个番外章之后，再跑一次「同步目录」应该只是追加，不许把已有番外章的章号搬乱。

### 验收
- 扫完的账本章数 = 正文 + 番外；日志里「跳过」只剩真正的分节标题。
- 番外章能在选择章节页被定位、被购买；买完账本里有它。
- 「核对订阅清单」对已购番外章能对上，不再因为番外报存疑。
- 单测覆盖：番外行进 chapters、分节标题行不进 chapters、`findGap` 不被无标号行干扰。

---

## 问题 2：一章被多个账号订阅时，补漏被拦下，变成「存疑」

### 现象
点「核对订阅清单」后，凡是**同一章有多个账号订阅过**的，一律存疑，**不会真的补上**。

### 用户的事实（这条决定语义）
这些重复是**用户以前在菠萝包里手动用多个号订阅同一章**造成的，**服务器明细里确实两个号都有这一章，钱也真的花了两次**。账本要**如实记录已经发生的事实**，不能把事实当成错账。

### 现在的根因（逐个点名，都要改）
1. `data/LedgerWritePolicy.decide()` 约 37-39 行：`else if (paid(incoming) && paid(row)) return reject("这一章已有其他账号花过券，不能再记给第二个号…")` —— **这是补漏被拦下的直接原因**。
2. `auto/RemoteLedgerRecovery.plan()` 约 121-123 行：明细里有、但这一章已记在别的号名下 → `fail("…已记在「X」名下")`。
3. `auto/SubscribedDetail.compareResolved()` 约 424-428 行：同一章同时记在「我」和别的号名下 → 直接进 `problems`。`reconcile` 的类注释还把「一章两个号」写成硬约束被破坏。
4. `auto/RemoteLedgerRepair`（删除判定）条件⑦：该章记在别的号名下 → 拒绝删除。**这条保留**，但错误信息要改成「这一章在别的号名下也有记录，不自动删」，不要说成「账本错了」。
5. `auto/SubscriptionAuditPolicy.bookBlocker()`：任何启用账号今天没有完整核对证明 → **整本书禁止购买**。所以一个号因为「重复归属」没核实，就把整本书卡住了 —— 这是用户看到「存疑之后什么也没发生」的放大原因。

### 改后的语义（写进注释，别只改代码）
- **旧不变量作废**：「一章最多一个号花过券」不再成立。
- **新不变量**：账本必须等于服务器事实 —— 明细里有的章，账本必须有（补记）；账本有的章，明细里必须有（否则存疑/删除判定）。重复归属**是允许的事实**，不是错误。
- **自动购买这条一点都不能松**：买之前必须确认这一章在账本里**没有任何账号拥有**（`findUnownedChaptersFrom`）。自动化绝不许制造新的重复 —— 重复只可能来自服务器上已经发生的事。
- **写入来源要分开**：
  - `Purchase.SRC_REMOTE_DETAIL`（核对订阅清单按明细补记）→ **允许**同一章补到第二个、第三个号名下；
  - `Purchase.SRC_AUTO`（自动买到）与 `SRC_MANUAL`（人工补录）→ **仍然拒绝**重复付费归属，拒绝文案改为：「这一章已有其他账号花过券；如果服务器上确实两个号都订过，请用『核对订阅清单』按明细补录」。
- 补记成功的文案/备注改成中性陈述，例如：`第 N 章服务器上确实有两个号订过（另一个是「X」），已照实补记`，并且**不阻断**后续购买。
- `SubscriptionAuditPolicy.bookBlocker`：只有「根本没读到 / 证据不完整 / 今天没核过」才拦；「重复归属」这种**已经核实清楚**的情况不许拦。

### 验收
- 造一条真实场景：第 N 章在服务器上被 A、B 两个号订过，账本里只有 A → 点「核对订阅清单」→ **B 的记录必须被补上**，日志有一句中性备注，整本书**不进入存疑、不阻断购买**。
- 反向也成立：账本里 A、B 都有、服务器明细也都有 → 核对结果算「对上了」，不是错误。
- 自动订阅在任何情况下都不许给一章写第二条付费记录（保留测试）。
- 单测覆盖：`LedgerWritePolicy` 四种来源 × 重复/不重复；`RemoteLedgerRecovery.plan` 允许跨号补记；`compareResolved` 不再把重复归属算成 problem。

---

## 问题 3：删掉所有观看广告的代码

用户不需要看广告了。**整条广告链路全删**，包括设置项、界面提示、数据库字段、选择器 key、单测和 debug 入口。

至少要清掉的（当前工作区实际存在的）：

- 删文件：`auto/AdWatchTask.java`、`test/auto/AdWatchTaskTest.java`、`test/auto/AdSelectorsRealTextTest.java`。
- `auto/Keys.java`：所有 `AD_*` 常量（约 34 处引用）。
- `util/Prefs.java`：`KEY_ADS_PER_ACCOUNT / KEY_AD_ASSIST / KEY_AD_JUMP` 及 `adsPerAccount/setAdsPerAccount/isAdAssist/setAdAssist/isAdJump/setAdJump`（约 13 处）。
- `auto/DailyQueue.java`（约 32 处）：`checkInAndAds()` 退化成「切号 → 签到 → 订阅」；删掉 `AdWatchTask.Mode`、`quota`、`adsWatched`、`adPending` 等字段与统计。
- `auto/CheckInQueue.java`（约 8 处）：`record(...)` 签名里的广告参数。
- `data/CheckInLog.java`：`adAvailable / adsWatched / adsRemaining` 三个字段；`data/CheckInRow.java`、`data/CheckInDao.java`（2 处）、`ui/CheckInRows.java`（10 处）里跟着它们的读取与统计。
- `auto/CheckInTask.java`（3 处）：签到结果里的「广告入口在不在」。
- `auto/StepRunner.java`（约 45 处）、`auto/BlbAccessibilityService.java`（8 处）、`auto/MultiRoot.java`、`auto/ReturnWatchdog.java`、`auto/Selector.java`、`auto/NodeMatcher.java`、`auto/NodeView.java`、`ui/StatusPalette.java`：只删**广告专用**的部分，公共能力（滚动、节点查找、返回看门狗）不许动。
- `auto/RunReport.java`（4 处）：结论里「广告看完 N 个」这类行。
- `work/DailyCheckInWorker.java`（3 处）：`attended` 参数本来就是为广告设的，跟着简化；**定时任务本身保留**。
- `ui/SettingsFragment.java` + `res/layout/fragment_settings.xml` + `res/values/strings.xml`（12 处）：整块「广告辅助点击」设置区与全部广告文案（含 `checkin_ad_notice`、`settings_ad_*`、`settings_section_ads`、`detail_notice_*`，以及 `checkin_run_hint`、`sub_daily_cost`、`account_vouchers_hint`、`accessibility_description` 里提到广告的句子）。
- `assets/selectors.json`：广告相关的 key（删掉，别留着当死配置）。
- `util/Texts.java`（3 处）：`parseRemaining`、`EXHAUSTED` 这类只服务广告的解析。
- debug 侧：`debug/ControlReceiver.java`、`debug/BalanceProbeReceiver.java`、`debug/SearchProbeReceiver.java` 及 `debug/AndroidManifest.xml` 里的广告入口。
- 单测：`RunReportTest`、`ui/EntrySummaryTest`、`util/TextsBalanceTest`、`auto/RunGateTest`、`auto/StepRunnerWindowTest`、`auto/SubscribeTaskTest`、`auto/MineBalanceRealTreeTest`、`auto/MultiRootTest`、`auto/NodeMatcherTest`、`auto/OfflineRunner` 里凡是广告相关的用例一并删掉；**不要为了让编译通过而留下空壳**。

数据库这块，二选一，在报告里说明选了哪个、为什么：

- **方案甲（推荐）**：从 `CheckInLog` 实体删掉三个广告字段，并写 `MIGRATION_6_7` 重建 `check_in_log` 表（照抄 v6 的列定义，`INSERT INTO … SELECT` 搬数据，保留全部历史行），`AppDatabase.version = 7`；照 `androidTest/.../Migration5To6Test.java` 的样子写 `Migration6To7Test`，断言老行不丢、新表结构正确。
- **方案乙**：保留三列不删（降低迁移风险），但**代码里一个字都不许再引用**，并在实体上写注释说明「广告功能已删除，这三列仅为兼容旧库保留，下次结构变更时一并清掉」。

两种都不许出现「半删除」：`grep` 一遍 `app/src`，除了历史注释和这份计划文档，不该再有任何 `AdWatch`/`ads`/`ad_`/`广告` 的痕迹。**把这条 grep 的命令和输出贴进报告。**

验收：签到页不再有广告提示；设置页没有广告分区；`./gradlew assembleDebug` 与 `testDebugUnitTest` 全绿；`app/src` 里 grep 不到广告代码。

---

## 问题 4：订阅页和签到页各有一颗「跑今天的整套流程」，冲突了

用户要求：**删掉订阅页那颗，保留签到页那颗**，并且签到页那颗做的事 = **对 8 个启用账号分别「签到 + 订阅」**（广告已删，不再有第二步）。

要做的：

- `res/layout/fragment_subscription.xml`：删掉 `@+id/run_daily` 那颗按钮（约 90 行）以及它下面的那行摘要（`sub_daily_cost` 文案那处）。
- `ui/SubscriptionFragment.java`：删掉 `runDaily` 的绑定与点击（约 118 行）、摘要渲染（约 303 行）、`startDaily` 调用（约 571 行）以及相关预检文案（约 558 行）；**订阅页只保留 `同步目录` / `核对订阅清单` 两颗主按钮**，`只跑订阅`（`run_subscribe` → `AutomationService.startSubscribe`）留在下面的次要操作横滚区，行为不变。
- `ui/CheckInFragment.java` + `res/layout/fragment_checkin.xml`：保留 `run_daily`，确认它走的是 `AutomationService.startDaily` → `DailyQueue`（逐号：切号 → 签到 → 订阅）。
- 文案：
  - `checkin_run_hint` 改成「上面那颗＝一个号一个号走完签到 → 订阅；下面那颗只给启用的账号签到，不订阅。」
  - `sub_daily_cost` 里的「签到 → 广告 → 订阅」一并清掉（如果它随问题 4 一起被删，就在报告里说明）。
  - 全项目搜一遍「签到 → 广告 → 订阅」这类残留串（含 toast、日志、注释）。
- `DailyQueue` 的类注释也要跟着改（它现在写的是三件事：签到、广告、订阅）。

验收：订阅页看不到「跑今天的整套流程」；签到页那颗一点，8 个启用账号逐个签到 + 订阅；`AutomationService` 里 `ACTION_RUN_DAILY` 只剩签到页与定时任务两个入口。

---

## 5. 交付与汇报格式

- 四个问题**分开汇报**，每条都写：改了哪些文件、根因是什么、验证命令与实际输出、还没验证的部分。
- 每轮结束跑 `./gradlew testDebugUnitTest assembleDebug`，贴真实输出；不通过不算完成。
- 需要真机的部分（问题 1 的节点 dump、覆盖安装、真跑一趟）如果做不了，**如实写「待真机验证」并列出清单**，不要编。
- 不确定的规则先问，不要自己发明；尤其是问题 2 里「哪些来源允许重复」这条边界。
- 不许为了让测试通过而放松既有护栏（`MONEY_UNCLEAR`、`gapNote`、扫描的 `trustworthy()`、删除的七条前置条件）。

## 6. 开始之前先回答我四个问题

1. 问题 1 你判断是分支 A 还是分支 B？你依据的是哪一条证据？如果拿不到真机 dump，你打算怎么把判据钉死？
2. 问题 2 里，你准备把「允许重复」的边界划在哪几个写入来源上？自动购买那道「买前必须确认无人拥有」的检查你会放在哪一行？
3. 问题 3 你选方案甲还是方案乙？为什么？迁移写坏的话怎么回滚？
4. 问题 3 和问题 4 改完，`DailyQueue` 一个号的一趟还剩哪几步？把新的步骤序列写出来。
