# v7 四项修复与验证记录

日期：2026-09-14。应用版本从 1.0 / versionCode 1 升为 **1.1 / versionCode 2**，数据库从 v6 升为 **v7**。

四项代码改动及本地验证已完成；最终一轮 `testDebugUnitTest assembleDebug` 通过，共 **628 项 JVM 测试、53 个套件，失败、错误、跳过均为 0**。Android 仪器测试源码也已编译通过。**新包覆盖安装、Android 上的迁移执行及整趟真实运行仍待真机验证**，下文分别列出清单。

- [交付 APK：blb-1.1-debug.apk](D:/SoftWare/Project/android/blb/build/deliverables/blb-1.1-debug.apk)
- [本轮完整源码变更清单](D:/SoftWare/Project/android/blb/docs/evidence/v7-source-changes.md)：与本轮开始前的 v6 工作区快照比较，共修改 85、新增 3、删除 3 个文件，未把原有未提交改动算入本轮。
- [最终完整构建日志](D:/SoftWare/Project/android/blb/.gradle/v7-build-catalog.log)；[53 个测试套件的实际结果](D:/SoftWare/Project/android/blb/.gradle/v7-evidence/jvm-suite-results.json)。

开始实施前已完整阅读 [v6 实现记录](D:/SoftWare/Project/android/blb/docs/v6-implementation-and-verification.md) 和 [v2 设计说明](D:/SoftWare/Project/android/blb/docs/plan-v2-catalog-and-ledger-audit.md)。本轮依照用户明确的新要求撤销“同章最多一个付费账号”的旧规则。未新增第三方依赖、Kotlin 或协程；未知数字保留 `-1`，金额不明仍停止，目录完整性与删除七项前提继续保留。

## 开始前四个问题的最终回答

1. **确认 A 的数字判据有缺陷，但旧运行是否同时有 B 无法还原。** 旧日志只有“跳过 12 行”，没有逐项名单。真实无编号章节若被旧代码读到必然会被丢弃。用户后来补齐的两页节点与选择观察证实：选择页的卷名和免费番外同形；普通目录的卷名父行有 `layoutRoot`，章节父行没有 id。现在有真实 dump，可用这些结构字段钉住纯判据，不必猜。实现同时要求两页完整、全部行逐位置同名，并修复稳定内容与实际滚动边界的联合到底判据。
2. **只有 `SRC_REMOTE_DETAIL` 可以补入第二、第三个账号已发生的付费事实。** `SRC_AUTO`、`SRC_MANUAL` 仍拒绝重复付费；`SRC_OWNED` 只表示零成本记录，不提供重复付费权限。购买入口在 [SubscribeRun.java:259](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/SubscribeRun.java:259) 调用 `requireStillUnowned`，重新读取全局无人拥有的下一章，紧邻 `SubscribeTask.run(...)` 之前；写入端也保留重复付费防线。
3. **选择方案甲。** v7 实体和签到表都移除三列，迁移保留全部历史行、主键、索引及外键；同次为章节增加未知初值为 `NULL` 的卷名列。失败时由升级事务回滚，已写故障注入的设备测试并编译通过；桌面 SQLite 成功与显式回滚演练通过。升级成功后不支持直接安装旧 APK 降级，应保留数据前滚修复或恢复完整一致性备份，不能靠清数据“回滚”。Android 上的实际迁移与失败回滚仍待验。
4. **每号：切号 → 签到并读余额 → 核验订阅前提 → 逐章订阅 → 下一号。** 队列读取实际启用账号，8 个启用账号就逐个处理 8 个。整套流程界面入口只在签到页，定时任务继续使用同一队列；订阅页保留同步目录、核对订阅清单，以及次要操作中的只跑订阅。

## 问题 1：目录遗漏无编号章节的证据与实现记录

> 代码与本地验证已完成。两页完整扫描、逐位置全文对照和回归测试已纳入最终构建。用户提供的旧节点 dump 是分类依据，新 APK 的覆盖安装与运行仍待真机验证。

### 分支判断：确认 A 的数字判据缺陷，尚不能排除旧运行也存在 B

用户提供的旧同步日志记载“目录 483 章”“跳过 12 行卷标题”。它只给出跳过总数，**没有这 12 行的逐项标题名单**，也没有本次扫描的 `scrolls / truncated / gapNote` 详情。因此不能写成“已在跳过名单里找到番外”，也不能据这份日志证明旧扫描已经到底、从而排除分支 B。

分支 A 的代码缺陷现在有明确证据链：旧 `finish()` 把 `printedNo < 0` 都当卷标题；用户补交的两份末尾节点证实，真实章节完整标题是 `藏在地下室的恶鬼（上）`、`藏在地下室的恶鬼（下）`、`迷信的可怖后果`，均没有行首“第 N 章”。这些章**一旦被旧扫描读到就一定会进入旧 skipped 分支**。无编号并不等于卷标题，可以确认；历史那一趟每一行具体经过哪个分支，旧摘要无法还原。

因此本轮同时修正章节/分节分类和 B 的到底判据。B 只有在“连续内容不变”与“明确的纵向列表容器确实无法继续前滚”同时满足时才能认到底；仅旧屏幕重复不能当完整证据。到达扫描上限或边界仍不明确时，报告截断并保持不可信，不能覆盖账本。

旧日志原文，来源 [v7-user-catalog-log.txt](D:/SoftWare/Project/android/blb/docs/evidence/v7-user-catalog-log.txt)：

```text
目录 483 章（新登记 0），重排 0 章，免费章补记 0 条；未购买任何章节
当前前台是 com.example.blb，正在把菠萝包拉回来
当前前台是 com.tencent.mobileqq，正在把菠萝包拉回来
当前前台是 com.example.blb，正在把菠萝包拉回来
点击 mine_tab
点击 library_tab
点击 search_entry
点击 书名 反派干部今天也在扮魔法少女
点击 catalog_entry
点击 download_entry
点击 回到顶部
目录 483 章（新登记 0），免费 64 章（补记 0 条，按 8 个启用账号每章各一条），这个号还能买 400 章，本机已下载但不知道是谁买的 6 章，跳过 12 行卷标题
目录：今天 16:49 扫过 · 483 章
正在返回首页，按返回（活动窗口 com.sfacg，菠萝包挂着 1 个窗口）
正在返回首页，按返回（活动窗口 com.sfacg，菠萝包挂着 1 个窗口）
正在返回首页，按返回（活动窗口 com.sfacg，菠萝包挂着 1 个窗口）
正在返回首页，按返回（活动窗口 com.sfacg，菠萝包挂着 1 个窗口）
按了 4 次返回仍未识别到首页导航（活动窗口 com.sfacg，菠萝包挂着 1 个窗口）
```

末尾返回失败属于旧日志中的另一个可见结果；本报告不把它解释成目录到底证据。

### 三行节点并排对比

证据来自用户先后提供的“选择章节页”和“普通目录页”末尾原始节点文本，不是从截图外观猜出的结构。两份完整原文见后文。节点 dump 没有显示完整资源包名前缀，以下 id 按其原样书写。

#### 选择章节页

| 对比项 | 正文最后一章 | “番外”卷名 | 第一个番外章 |
|---|---|---|---|
| 完整行文本 | `第23章 拥有烟火和月季花香的八百个梦境` | `番外` | `藏在地下室的恶鬼（上）` |
| 标题节点 | `TextView id="title"` | `TextView id="title"` | `TextView id="title"` |
| 所属列表 | `RecyclerView id="downloadRecycler"` | 同左 | 同左 |
| 直接父行 class / id | `RelativeLayout`，未列 id | `RelativeLayout`，未列 id | `RelativeLayout`，未列 id |
| 直接父行是否可点 | `CLICKABLE` | `CLICKABLE` | `CLICKABLE` |
| 最近可点祖先 | 上述直接父行 | 上述直接父行 | 上述直接父行 |
| `CHAPTER_LOCKED` | 有 `title_lock` | 未出现 | 未出现 |
| `CHAPTER_OWNED` | 未出现 `title_check` 或“已下载” | 未出现 | 未出现 |
| `CHAPTER_SELECTABLE` | 有 `item_cb` | 有 `item_cb` | 有 `item_cb` |
| 父行 bounds | `[28,1322-1092,1463]` | `[28,1604-1092,1745]` | `[28,1745-1092,1886]` |
| 父行高度 | 141 | 141 | 141 |
| 直接子节点数 | 3：锁、标题、勾选框 | 2：标题、勾选框 | 2：标题、勾选框 |

“最近可点祖先”是按原始父链及 `NodeMatcher.clickableAncestorOf` 的规则得出的对应关系；这里没有声称在用户设备上另行执行了一次探针。锁、已有、可选分别对应当前选择器中的 `title_lock`、`title_check/已下载`、`item_cb`。标记表仅描述 dump 当时的节点，不把没有锁解释成已经付过钱。

关键结论：**选择页中卷名和第一个番外章的这些结构完全相同**。无锁、两个子节点、勾选框、可点父行或 141 的行高，都不能把两者安全分开。正文有锁，也不能据此把所有无锁行判成卷名。

#### 普通目录页

| 对比项 | 正文最后一章 | “番外”卷名 | 第一个番外章 |
|---|---|---|---|
| 完整行文本 | `第23章 拥有烟火和月季花香的八百个梦境` | `番外` | `藏在地下室的恶鬼（上）` |
| 所属列表 | `ListView id="list_view"` | 同左 | 同左 |
| 父行 class | `RelativeLayout` | `RelativeLayout` | `RelativeLayout` |
| 父行 id | 未列 id | `layoutRoot` | 未列 id |
| 父行是否为列表直接子项 | 是 | 是 | 是 |
| 最近可点祖先 | 直接 `RelativeLayout CLICKABLE` 父行 | 同左 | 同左 |
| 标题与子节点数 | 唯一直接子节点 `TextView id="title"` | 同左 | 同左 |
| 父行 bounds | `[0,1572-1080,1710]` | `[0,1848-1080,1986]` | `[0,1986-1080,2124]` |
| 父行高度 | 138 | 138 | 138 |
| 三种购买状态标记 | 本页未出现 | 本页未出现 | 本页未出现 |

普通目录中可以用来区别分节与章节的父容器差异是 `layoutRoot`。三行同属 `list_view` 的直接 `RelativeLayout` 子项，都是可点父行、单个 `title` 子节点、138 高。标题自身宽度和屏幕坐标也有区别，但不把像素尺寸写成分类规则。

同一份普通目录 dump 中，`完结感言，以及反思` 也处于“无 id 可点行 + 唯一 title 子节点”的结构；不能仅凭它不像“第 N 章”或标题含“感言”就丢弃。它与“番外”卷名在树上的父行结构不同。

#### 用户补充的选择数量观察

来源 [v7-catalog-selection-user-observation.txt](D:/SoftWare/Project/android/blb/docs/evidence/v7-catalog-selection-user-observation.txt)，原文为：

```text
点“番外”后显示：已选3章。单独点“藏在地下室的恶鬼（上）”（只有这一章被勾选）显示1章
```

这支持“番外卷名会整组选中，而恶鬼上可以单独选中”的用户现象。它不是两份未选中 dump 内的状态，也不是本轮自动点击结果。**不把数量单独用作章节分类判据**：只有一章尚可选的卷，也可能显示“已选 1 章”。购买前“已选必须恰好 1 章”的护栏继续独立保留。

### 由证据推导的实现边界

当前实现及其证据边界如下：

1. **先完整读取普通目录。** 只处理经页面与容器选择器确认的 `list_view/ListView` 的直接行；要求可点 `RelativeLayout` 与唯一直接 `title/TextView` 子节点。父行 `layoutRoot` 是分节；符合这套结构且无父行 id 的行作为章节证据。未知 id、角色矛盾、缺少标题或结构不完整不能猜。规则写成 static 纯函数，JVM 用例使用这些真实结构字段；不依赖行高、标题关键词、无锁或勾选数量。
2. **再完整读取选择章节页。** 使用其独立纵向列表 `downloadRecycler/RecyclerView`，连同分节在内保留全部出现位置。普通目录和选择页须总行数相同、每一位置的完整标题完全相同，才可以把普通目录的章节/分节角色用于选择页的行。任一列表未读完整、顺序变化、标题不同或缺行，停止发布目录。不能只凭同名猜测对应位置。
3. **拒绝歧义并保留未知行。** 无编号章节全标题重复，或者同名同时充当章节与分节时，停止同步；不按旧位置给新出现的同名行继承旧购买。报告分别写“卷标题（已跳过）”和“无标号、无法判定（请核对）”；未知行逐条展示并使 `trustworthy()` 不通过，避免静默少章。
4. **完整性护栏仍成立。** 双页都要求从顶开始、连续拼接、稳定内容与确定滚动边界；只操作已确认的纵向列表，避免误把目录上方的横向卷/书签控件当成滚动目标。内容不变但仍可滚动时继续；无法确证到底时报告 `truncated`。保留 `gapNote`、`trustworthy()` 及错误时停止写账本的行为。
5. **编号与标题保留原义。** `findGap` 只有相邻两行的 `printedNo` 都非负才检查数字连续性；`-1` 始终表示无编号/未知，不作为 0 计算。账本 `chapterNo` 按完整目录的章节行顺序继续递增；分节不占章节号，番外不另开编号。正文末行标题里的“第23章”是显示文本中的卷内标号，不等于全书最后章节位置，也不能据 483 + 某个猜测数量宣布新总章数。
6. **按完整标题对齐并购买。** `CatalogAlign` 对无编号行也使用原始完整标题，保持已有章 id 与购买关系；重复同步幂等，尾部追加新番外不重排旧番外。真实选择页标题是 **`藏在地下室的恶鬼（上）`，无“番外 ”前缀、使用全角括号**。不能把文本目录里带卷名前缀的写法存作此页章节标题，也不能为命中而补“第 N 章”或替换括号。`SubscribeTask.findRow` 沿用完整标题精确查找及 `requireUniqueChapter` 全目录唯一性检查。
7. **远端明细按卷名与完整标题消歧。** `Chapter.volumeTitle` 独立保存普通目录分节。明细没有编号时，在双方卷名证据与完整标题一致、候选唯一的情况下参与对账；缺卷名、同卷同标题多候选或金额/日期不完整，继续报警并停下。旧库新增卷名列初值为 `NULL`，须通过完整目录读取补证，不编造卷名。目录行标题与服务器明细可能附带的卷名前缀分开处理。

本轮仅把章节身份识别补齐；付款仍须满足无人拥有、唯一整章、恰好选中一章、实付火券为 0、代券足够等原有前提。`MONEY_UNCLEAR`、删除七条前提及其它金额护栏不因番外而放松。

完整扫描与角色检查通过后，先确认回到顶部，再进入目录写事务。这样回顶失败就不会先修改账本，也不会发生“实际已经新增/补记，但调用方因为回顶异常拿不到结果报告”。这项顺序调整同样以本次双页目录修复为原因写在注释中。

主要修改文件（完整清单另见 [v7-source-changes.md](D:/SoftWare/Project/android/blb/docs/evidence/v7-source-changes.md)）：

- [CatalogScanner.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/CatalogScanner.java)、[CatalogSync.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/CatalogSync.java)、[CatalogQueue.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/CatalogQueue.java)：双页取证、角色判据、完整性和未知行报告。复用 v6 的 [CatalogAlign.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/CatalogAlign.java) 对齐实现，本轮新增真实番外标题、追加及幂等回归用例，不把它原先已有的源码修改算成本轮修改。
- [Keys.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/Keys.java)、[selectors.json](D:/SoftWare/Project/android/blb/app/src/main/assets/selectors.json)、[StepRunner.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/StepRunner.java)、[BlbAccessibilityService.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/BlbAccessibilityService.java)：明确页面/纵向列表选择器与双向短滑、滚动边界。
- [Chapter.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/data/Chapter.java)、[SubscriptionDao.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/data/SubscriptionDao.java)、[Db.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/data/Db.java)：卷名持久化、未知值与 v7 迁移。
- [RemoteLedgerRecovery.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/RemoteLedgerRecovery.java)、[SubscribedDetail.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/SubscribedDetail.java)、[SubscribeTask.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/SubscribeTask.java)：无编号明细的唯一解析及精确购买定位。
- [CatalogRowEvidence.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/CatalogRowEvidence.java)、[InspectorCapture.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/InspectorCapture.java)、[InspectorActivity.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/ui/InspectorActivity.java)、[ControlReceiver.java](D:/SoftWare/Project/android/blb/app/src/debug/java/com/example/blb/debug/ControlReceiver.java)、[TreeDump.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/TreeDump.java)：补充行证据导出，保留可点祖先、状态、子节点及坐标；不可变节点快照也通过公共节点接口输出坐标。
- [CatalogRowPolicyTest.java](D:/SoftWare/Project/android/blb/app/src/test/java/com/example/blb/auto/CatalogRowPolicyTest.java)、[CatalogScannerTest.java](D:/SoftWare/Project/android/blb/app/src/test/java/com/example/blb/auto/CatalogScannerTest.java)、[CatalogAlignTest.java](D:/SoftWare/Project/android/blb/app/src/test/java/com/example/blb/auto/CatalogAlignTest.java)、[SubscribeTaskTest.java](D:/SoftWare/Project/android/blb/app/src/test/java/com/example/blb/auto/SubscribeTaskTest.java)、[SubscribedDetailTest.java](D:/SoftWare/Project/android/blb/app/src/test/java/com/example/blb/auto/SubscribedDetailTest.java)：纯判据与回归覆盖。代表标题用例已按新 dump 换为真实无前缀、全角括号标题；半角括号和额外空格作为独立精确匹配边界用例，不写成真机文本。

### 验证状态与待真机清单

本节所用原文通过只读命令获取，命令实际均成功、退出码 0；完整输出即上面的旧日志、选择观察和下面两份 dump：

```powershell
Get-Content -LiteralPath 'docs/evidence/v7-catalog-tail-user-dump.txt' -Raw
Get-Content -LiteralPath 'docs/evidence/v7-directory-tail-user-dump.txt' -Raw
Get-Content -LiteralPath 'docs/evidence/v7-catalog-selection-user-observation.txt' -Raw
Get-Content -LiteralPath 'docs/evidence/v7-user-catalog-log.txt' -Raw
```

最后一次源码变更之后已执行 `testDebugUnitTest assembleDebug compileDebugAndroidTestJavaWithJavac`，完整命令见“共同构建记录”。实际输出为：

```text
BUILD SUCCESSFUL in 55s
47 actionable tasks: 11 executed, 36 up-to-date
```

该轮共 628 项 JVM 测试、53 个套件，失败、错误、跳过均为 0。其中 `CatalogRowPolicyTest` 22 项、`CatalogScannerTest` 29 项、`CatalogScannerClippingTest` 12 项、`CatalogAlignTest` 25 项、`SubscribeTaskTest` 24 项、`SubscribedDetailTest` 91 项、`SubscribeRunRecoveryTest` 10 项。覆盖已证实的卷/章结构、未知结构拒绝、两页缺行或乱序、无编号重名、相邻编号缺口、锁标记裁剪、真实番外标题定位、追加及重扫幂等。纯判据使用取证字段与内存夹具验证；这些结果不是新包真机运行。

待真机验证：

1. 覆盖安装新版本，保留原库；“同步目录”完成普通目录和选择页两次完整扫描，日志记录各自从顶、滚动、到底、截断、未知行及角色对照结果。
2. 对照完整目录核实总章数，包含正文、三个已提供标题的番外以及其它确认为章节的无编号行；跳过列表只剩真实分节。当前证据不能预先写死最终总数。
3. 重复同步同一目录不产生重复章节、不重排已有番外；在真实作者新增后再次同步只追加。出现同名无编号章时停下，不能继承错误购买。
4. 在选择页精确定位 `藏在地下室的恶鬼（上）`；按真实行状态处理已有/免费记录，验证可付费的番外在所有支付前提满足后仅购买一章，成功事实进入账本。不能把用户单选观察当成本轮购买成功。
5. 用实际服务器明细核对已购番外，核实“卷名 + 标题”在真实页面上的文本形式与唯一区分；缺证据仍报警，不凭标题猜。
6. 分节、懒加载或页面内容重复时，确认仍可滚动就不会提前认底；真正无法确定边界时报告截断并保留旧账本。

### 完整原始 dump 1：选择章节页末尾

来源：[v7-catalog-tail-user-dump.txt](D:/SoftWare/Project/android/blb/docs/evidence/v7-catalog-tail-user-dump.txt)。以下保留原始文字、节点层级、id、标记及坐标，不截取三行代替完整证据。

```text
FrameLayout [28,17-1092,2383]
  LinearLayout id="download_fragment" CLICKABLE [28,127-1092,2383]
    TextView text="" id="back_img" CLICKABLE [28,127-147,246]
    TextView text="选择章节" id="title_tv" [459,152-660,220]
    TextView text="全选" id="selected_all" CLICKABLE [974,157-1092,214]
    RecyclerView id="downloadRecycler" [28,246-1092,2168]
      RelativeLayout CLICKABLE [28,246-1092,335]
        TextView text="" id="title_lock" [73,246-111,289]
        TextView text="第15章 小小客人" id="title" [125,246-988,293]
        ImageView id="item_cb" CLICKABLE [1001,246-1047,287]
      RelativeLayout CLICKABLE [28,335-1092,476]
        TextView text="" id="title_lock" [73,382-111,430]
        TextView text="第16章 顾问的失算" id="title" [125,377-988,434]
        ImageView id="item_cb" CLICKABLE [1001,383-1047,428]
      RelativeLayout CLICKABLE [28,476-1092,617]
        TextView text="" id="title_lock" [73,523-111,571]
        TextView text="第17章 难得可怜" id="title" [125,518-988,575]
        ImageView id="item_cb" CLICKABLE [1001,524-1047,569]
      RelativeLayout CLICKABLE [28,617-1092,758]
        TextView text="" id="title_lock" [73,664-111,712]
        TextView text="第18章 铭刻于心" id="title" [125,659-988,716]
        ImageView id="item_cb" CLICKABLE [1001,665-1047,710]
      RelativeLayout CLICKABLE [28,758-1092,899]
        TextView text="" id="title_lock" [73,805-111,853]
        TextView text="第19章 从37.7亿年前到21世纪" id="title" [125,800-988,857]
        ImageView id="item_cb" CLICKABLE [1001,806-1047,851]
      RelativeLayout CLICKABLE [28,899-1092,1040]
        TextView text="" id="title_lock" [73,946-111,994]
        TextView text="第20章 摇剑小丑" id="title" [125,941-988,998]
        ImageView id="item_cb" CLICKABLE [1001,947-1047,992]
      RelativeLayout CLICKABLE [28,1040-1092,1181]
        TextView text="" id="title_lock" [73,1087-111,1135]
        TextView text="第21章 魔女复苏" id="title" [125,1082-988,1139]
        ImageView id="item_cb" CLICKABLE [1001,1088-1047,1133]
      RelativeLayout CLICKABLE [28,1181-1092,1322]
        TextView text="" id="title_lock" [73,1228-111,1276]
        TextView text="第22章 战斗终局" id="title" [125,1223-988,1280]
        ImageView id="item_cb" CLICKABLE [1001,1229-1047,1274]
      RelativeLayout CLICKABLE [28,1322-1092,1463]
        TextView text="" id="title_lock" [73,1369-111,1417]
        TextView text="第23章 拥有烟火和月季花香的八百个梦境" id="title" [125,1364-988,1421]
        ImageView id="item_cb" CLICKABLE [1001,1370-1047,1415]
      RelativeLayout CLICKABLE [28,1463-1092,1604]
        TextView text="完结感言，以及反思" id="title" [87,1505-988,1562]
        ImageView id="item_cb" CLICKABLE [1001,1511-1047,1556]
      RelativeLayout CLICKABLE [28,1604-1092,1745]
        TextView text="番外" id="title" [87,1646-988,1703]
        ImageView id="item_cb" CLICKABLE [1001,1651-1047,1697]
      RelativeLayout CLICKABLE [28,1745-1092,1886]
        TextView text="藏在地下室的恶鬼（上）" id="title" [87,1787-988,1844]
        ImageView id="item_cb" CLICKABLE [1001,1792-1047,1838]
      RelativeLayout CLICKABLE [28,1886-1092,2027]
        TextView text="藏在地下室的恶鬼（下）" id="title" [87,1927-988,1985]
        ImageView id="item_cb" CLICKABLE [1001,1933-1047,1979]
      RelativeLayout CLICKABLE [28,2027-1092,2168]
        TextView text="" id="title_lock" [73,2073-111,2122]
        TextView text="迷信的可怖后果" id="title" [125,2068-988,2126]
        ImageView id="item_cb" CLICKABLE [1001,2074-1047,2120]
    ImageView id="position" CLICKABLE [914,1735-1038,1859]
    ImageView id="goto_top" CLICKABLE [914,1887-1038,2011]
    ImageView id="goto_bottom" CLICKABLE [914,2044-1038,2168]
    TextView text="已选 0 章" id="tvSelect" [70,2208-211,2254]
    TextView
    TextView text="账户余额：0火券/46代券" id="tvAccount" [70,2286-601,2332]
    LinearLayout id="img_down" CLICKABLE [630,2199-1050,2342]
      TextView text="立即下载" id="tv_download" [749,2240-930,2301]
```

### 完整原始 dump 2：普通目录页末尾

来源：[v7-directory-tail-user-dump.txt](D:/SoftWare/Project/android/blb/docs/evidence/v7-directory-tail-user-dump.txt)。以下保留原始文字、节点层级、id、标记及坐标。

```text
FrameLayout [0,0-1080,2400]
  LinearLayout CLICKABLE [0,111-1080,2400]
    RelativeLayout id="pager_title_layout2" CLICKABLE [0,111-1080,353]
      TextView text="" id="back_img" CLICKABLE [0,111-121,232]
      TextView text="目录列表" id="edit_title" [440,137-640,205]
      RelativeLayout id="download_layout" CLICKABLE [851,130-1052,213]
        TextView text="" id="submit_icon" [879,147-918,196]
        TextView text="下载" [946,145-1024,197]
      HorizontalScrollView id="pager_tabstrip" [0,232-429,353]
        RelativeLayout CLICKABLE [0,232-214,353]
          TextView text="目录" id="tab_title" [67,232-147,353]
        RelativeLayout CLICKABLE [214,232-428,353]
          TextView text="书签" id="tab_title" [281,232-361,353]
      LinearLayout id="position" CLICKABLE [885,232-1037,353]
        TextView text="去当前" [935,269-1037,315]
    ViewPager id="pager" [0,381-1080,2400]
      RelativeLayout CLICKABLE [0,381-1080,2400]
        ListView id="list_view" [0,381-1080,2400]
          RelativeLayout CLICKABLE [0,381-1080,468]
            TextView text="第14章 会议战争" id="title" [0,381-1080,456]
          RelativeLayout CLICKABLE [0,468-1080,606]
            TextView text="第15章 小小客人" id="title" [0,480-1080,594]
          RelativeLayout CLICKABLE [0,606-1080,744]
            TextView text="第16章 顾问的失算" id="title" [0,618-1080,732]
          RelativeLayout CLICKABLE [0,744-1080,882]
            TextView text="第17章 难得可怜" id="title" [0,756-1080,870]
          RelativeLayout CLICKABLE [0,882-1080,1020]
            TextView text="第18章 铭刻于心" id="title" [0,894-1080,1008]
          RelativeLayout CLICKABLE [0,1020-1080,1158]
            TextView text="第19章 从37.7亿年前到21世纪" id="title" [0,1032-1080,1146]
          RelativeLayout CLICKABLE [0,1158-1080,1296]
            TextView text="第20章 摇剑小丑" id="title" [0,1170-1080,1284]
          RelativeLayout CLICKABLE [0,1296-1080,1434]
            TextView text="第21章 魔女复苏" id="title" [0,1308-1080,1422]
          RelativeLayout CLICKABLE [0,1434-1080,1572]
            TextView text="第22章 战斗终局" id="title" [0,1446-1080,1560]
          RelativeLayout CLICKABLE [0,1572-1080,1710]
            TextView text="第23章 拥有烟火和月季花香的八百个梦境" id="title" [0,1584-1080,1698]
          RelativeLayout CLICKABLE [0,1710-1080,1848]
            TextView text="完结感言，以及反思" id="title" [0,1722-1080,1836]
          RelativeLayout id="layoutRoot" CLICKABLE [0,1848-1080,1986]
            TextView text="番外" id="title" [0,1861-138,1972]
          RelativeLayout CLICKABLE [0,1986-1080,2124]
            TextView text="藏在地下室的恶鬼（上）" id="title" [0,1998-1080,2112]
          RelativeLayout CLICKABLE [0,2124-1080,2262]
            TextView text="藏在地下室的恶鬼（下）" id="title" [0,2136-1080,2250]
          RelativeLayout CLICKABLE [0,2262-1080,2400]
            TextView text="迷信的可怖后果" id="title" [0,2274-1080,2388]
      RecyclerView id="cate_recycler"
      LinearLayout id="error_layout" CLICKABLE
        ImageView id="empty_image" CLICKABLE
        TextView text="您还没有书签" id="message_text_view"
    ImageView id="goto_top" CLICKABLE [55,1950-181,2076]
    ImageView id="goto_bottom" CLICKABLE [55,2109-181,2235]
```

## 问题 2：按账号如实补记已经发生的多号订阅

### 根因与真实证据

旧规则把“一章最多一个付费账号”当成账本不变量，导致完整服务器明细也无法补入第二个账号，随后缺失完整核对证明又阻断整本书购买。用户明确说明，这些是以前手动用多个账号真实付费的历史，不是自动化新买出的重复。

两张原始长截图逐块读取后，能直接确认三个拒绝案例：皓平明细覆盖 `5/5`、微博账号覆盖 `6/6`，均因第 92 章已记在「五杯半雪碧」名下被拒；niiiiee 明细覆盖 `5/5`，因第 83 章已有同一买家被拒。这支持修改重复归属语义。截图证据索引见 [screenshot-reading.md](D:/SoftWare/Project/android/blb/.gradle/v7-evidence/screenshot-reading.md) 的“可以确认的事实”及“关键摘录”；这些是改动前的用户实录。

另外，截图中 w9899 的实际日志为：

```text
明细未完整：读到 6 条；已打开=true，从顶=true，到底=false，截断=false，
未读全=1，列表项覆盖=6/15；读取途中列表总项数改变，不能拼接两份明细
```

这条属于独立的证据不完整问题，仍须拦截。修复另外三个账号的已核实重复归属，不代表 w9899 自动获得完整证明，也不代表当前整本书已可以购买。

### 改动与边界

主要文件：

- [LedgerWritePolicy.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/data/LedgerWritePolicy.java)、[SubscriptionDao.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/data/SubscriptionDao.java)：区分写入来源，保留事务内核查和幂等写入；CSV 导入走单独的纯判据。
- [RemoteLedgerRecovery.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/RemoteLedgerRecovery.java)、[SubscribedDetail.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/SubscribedDetail.java)：允许按完整服务器明细补记跨号历史；本账号远端与本地事实相符时，其他账号也有记录不再成为 problem。
- [SubscriptionAuditQueue.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/SubscriptionAuditQueue.java)、[SubscriptionAuditPolicy.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/SubscriptionAuditPolicy.java)：仅在实际提交成功后写中性备注，并继续要求当天完整核对证明。
- [RemoteLedgerRepair.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/RemoteLedgerRepair.java)：保留删除的第七条前提，文案改为“这一章在别的号名下也有记录，不自动删”。
- [SubscribeRun.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/SubscribeRun.java:259)、[SubscriptionFragment.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/ui/SubscriptionFragment.java)：买前重新查询全局无人拥有章节；CSV 写入路径不能靠自填来源获得远端证据权限。

来源边界如下：

| 写入来源 | 本章没有其他账号付费记录 | 本章已有其他账号付费记录 |
|---|---|---|
| `SRC_REMOTE_DETAIL` | 完整核对证据和事实校验通过后可补记 | 完整证据确认后可补第二、第三个账号 |
| `SRC_AUTO` | 仍须满足所有购买前提 | 拒绝第二条付费归属 |
| `SRC_MANUAL` | 金额、日期等事实校验通过后可补录 | 拒绝第二条付费归属，并引导核对订阅清单 |
| `SRC_OWNED` | 仍表示零成本已有/共享记录 | 零成本记录可并存；该来源不能授权第二次付费 |

`AUTO` / `MANUAL` 的拒绝文案为：

> 这一章已有其他账号花过券；如果服务器上确实两个号都订过，请用『核对订阅清单』按明细补录

CSV 里的 `REMOTE_DETAIL` 字段只是备份内容，不是“已经完整读过服务器”的证明，不能借此新增跨号付费历史。与现有记录完全一致的备份重放仍幂等，保留原主键和原来源。同一账号重复行、金额或日期冲突、读不到金额、明细不完整继续拒绝。

买前的“无人拥有”检查位于当前 [SubscribeRun.java:259](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/SubscribeRun.java:259)，在调用 `SubscribeTask.run(...)` 前重新执行：

```java
requireStillUnowned(chapter,
        subs.findNextUnownedChapterFrom(plan.novel.id, plan.novel.startFrom()));
```

原有全局未拥有章节筛选与写入端重复付费防线仍保留。新不变量写入了类注释：账本逐账号对应服务器事实；自动化不能制造新的重复付费。

提交成功后的中性备注格式为：

```text
第 N 章服务器明细确认「本账号」订过，账本中另有「其他账号」的订阅记录，已照实补记
```

上面是代码实现的文案模板，**不是本轮真机运行日志**。核对清楚的多号历史本身不阻断购买；没有读到、证据不完整或今日没有完整核对证明仍会阻断。

### 验证命令、实际结果与未验证部分

JVM 判据覆盖位于 [LedgerWritePolicyTest.java](D:/SoftWare/Project/android/blb/app/src/test/java/com/example/blb/data/LedgerWritePolicyTest.java)、[SubscribedDetailTest.java](D:/SoftWare/Project/android/blb/app/src/test/java/com/example/blb/auto/SubscribedDetailTest.java)、[SubscriptionAuditPolicyTest.java](D:/SoftWare/Project/android/blb/app/src/test/java/com/example/blb/auto/SubscriptionAuditPolicyTest.java)、[SubscribeRunNextChapterTest.java](D:/SoftWare/Project/android/blb/app/src/test/java/com/example/blb/auto/SubscribeRunNextChapterTest.java)。`RemoteLedgerRecovery` 的用例在 `SubscribedDetailTest` 中，没有另建空壳测试类。

已通过的代表用例：

- `fourSourcesOnlyAllowRemoteHistoryToAddAnotherPaidAccount`：四种来源的边界。
- `aThirdRemoteAccountCanBeRecordedWithoutChangingExistingFacts`：第三个账号的真实历史可补入。
- `csvCannotClaimVerifiedRemoteEvidenceBySupplyingItsSourceField`、`importingAnIdenticalStoredRemoteFactKeepsItsIdBesideAnotherPaidAccount`：导入不能冒充证据，相同旧事实幂等。
- `anotherAccountsOwnershipAllowsRemoteRecoveryWithANeutralNote`、`currentAndForeignPaidOwnersAreValidWhenCurrentRemoteFactsMatch`：跨号补记与已对上事实不产生误报。
- `aDuplicateFactForTheSameAccountStillCannotPlanRecovery`、`paymentCanStartOnlyWhileTheFreshUnownedQueryReturnsTheSameChapter`：保留本账号重复记录与买前重新核查防线。

构建命令与最终真实输出见本文“共同构建记录”。本轮 628 个 JVM 测试全部通过；数据库迁移采用独立仪器测试，真实屏幕操作未写 JVM 单测。

**待真机验证：**

1. A 已在账本、B 完整服务器明细也有同章时，B 确实补入，出现中性备注，不再仅因重复归属进入存疑。
2. A、B 本地和服务器均有记录时，两账号均判为对上。
3. 重新完整读取 w9899 等证据不足账号；未完整之前整本仍停购，不能以此次修复绕过。
4. 自动订阅对任何账号已拥有的章节均不再次付款。

## 问题 3：删除整条广告功能，迁移到数据库 v7

### 根因、删除范围与主要文件

原来的签到流程、设置、结果模型、选择器、调试入口和数据库都参与了同一条用户已不需要的功能，单删按钮会遗留调用链和配置。因此按功能链路删除，没有保留空任务或空测试。

删除 `AdWatchTask.java`、`AdWatchTaskTest.java`、`AdSelectorsRealTextTest.java`。调用及配置清理涉及：

- 队列与运行结果：[DailyQueue.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/DailyQueue.java)、[CheckInQueue.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/CheckInQueue.java)、[CheckInTask.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/CheckInTask.java)、[RunReport.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/RunReport.java)、[DailyCheckInWorker.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/work/DailyCheckInWorker.java)、[AutomationService.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/AutomationService.java)。
- 自动化专用分支：[Keys.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/Keys.java)、[StepRunner.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/StepRunner.java)、[BlbAccessibilityService.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/BlbAccessibilityService.java)、[MultiRoot.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/MultiRoot.java)、[ReturnWatchdog.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/ReturnWatchdog.java)、[Selector.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/Selector.java)、[NodeMatcher.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/NodeMatcher.java)、[NodeView.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/NodeView.java)。公共滚动、节点查找、返回看门狗继续保留。
- 设置、界面与文字：[Prefs.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/util/Prefs.java)、[Texts.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/util/Texts.java)、[SettingsFragment.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/ui/SettingsFragment.java)、[CheckInRows.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/ui/CheckInRows.java)、[CheckInFragment.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/ui/CheckInFragment.java)、[StatusPalette.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/ui/StatusPalette.java)、[DetailActivity.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/ui/DetailActivity.java)、[fragment_settings.xml](D:/SoftWare/Project/android/blb/app/src/main/res/layout/fragment_settings.xml)、[fragment_checkin.xml](D:/SoftWare/Project/android/blb/app/src/main/res/layout/fragment_checkin.xml)、[activity_detail.xml](D:/SoftWare/Project/android/blb/app/src/main/res/layout/activity_detail.xml)、[strings.xml](D:/SoftWare/Project/android/blb/app/src/main/res/values/strings.xml)。
- 配置与调试：[selectors.json](D:/SoftWare/Project/android/blb/app/src/main/assets/selectors.json)、[ControlReceiver.java](D:/SoftWare/Project/android/blb/app/src/debug/java/com/example/blb/debug/ControlReceiver.java)、[debug/AndroidManifest.xml](D:/SoftWare/Project/android/blb/app/src/debug/AndroidManifest.xml)。只读目录取证入口仍保留，不属于这条链路。

已核查 [BalanceProbeReceiver.java](D:/SoftWare/Project/android/blb/app/src/debug/java/com/example/blb/debug/BalanceProbeReceiver.java) 与 [SearchProbeReceiver.java](D:/SoftWare/Project/android/blb/app/src/debug/java/com/example/blb/debug/SearchProbeReceiver.java)：本轮前后内容相同，其中的公共余额及搜索探测继续保留，不列作本轮修改文件。

相关结果、余额、队列锁、窗口、节点匹配等测试中删除专用用例；公共能力的用例仍保留。调度本身没有删除。`DetailActivity` 中对应的说明页及布局一并移除，没有留下空说明页。

### 数据库采用方案甲

主要文件：[CheckInLog.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/data/CheckInLog.java)、[CheckInRow.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/data/CheckInRow.java)、[CheckInDao.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/data/CheckInDao.java)、[Db.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/data/Db.java)、[AppDatabase.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/data/AppDatabase.java)、[Migration6To7Test.java](D:/SoftWare/Project/android/blb/app/src/androidTest/java/com/example/blb/data/Migration6To7Test.java)、[Migration5To6Test.java](D:/SoftWare/Project/android/blb/app/src/androidTest/java/com/example/blb/data/Migration5To6Test.java)。

选择甲是为了让当前实体、查询和数据库结构都彻底移除三个旧字段，避免以后误用。迁移按 v6 的实际保留列重建 `check_in_log`，用 `INSERT INTO … SELECT` 搬回全部历史行，并恢复唯一索引与外键。保留列正好为 `id/account_id/date_ymd/status/message/created_at`。同次迁移还给目录增加 `volume_title`，用于问题 1 的“卷名 + 标题”消歧；旧章卷名未知，保留 `NULL`，不猜。

完整迁移链为 1→2→3→4→5→6→7，不使用破坏性迁移。历史 1→2 SQL 和 v5/v6 测试夹具必须准确保留老列名，才能真正验证已有数据库升级。

回滚边界：

- 升级事务提交前失败，Room 升级事务应保留原 v6 结构、版本及历史数据，修复升级代码后重试。已写入并编译故障注入测试；尚未在 Android 设备运行该测试。
- 升级提交为 v7 后，没有提供 7→6 降级迁移，不能把“装回旧 APK”当成可用回滚。应优先保留 v7 数据向前修复，或使用实际完整且兼容的备份恢复。
- CSV 只覆盖其导出的业务内容，不是数据库、账号密钥和 KeyStore 的完整备份。不能通过卸载或清数据处理真实用户库。

### 验证命令与实际输出

桌面 SQLite 对真实迁移 SQL 做了保留数据、结构、外键、索引及显式回滚演练。命令：

```powershell
& '.gradle/verify-migration-6-7.ps1'
& '.gradle/v7-evidence/compare-room7-schema.ps1'
```

第一条命令的原始日志：

```text
CASE=success SQLITE_IN_MEMORY
name|result
account_novel_audit_rows_preserved|PASS
account_rows_preserved|PASS
chapter_rows_preserved|PASS
check_in_log_cascade_fk|PASS
check_in_log_rows_preserved|PASS
check_in_log_six_columns_exact|PASS
check_in_log_unique_index|PASS
five_history_rows|PASS
foreign_key_check_empty|PASS
ledger_audit_rows_preserved|PASS
novel_rows_preserved|PASS
old_chapter_volume_is_unknown|PASS
purchase_rows_preserved|PASS
rebuilt_table_has_autoincrement|PASS
schema_version_7|PASS
integrity_check
ok
CASE=rollback SQLITE_IN_MEMORY
name|result
account_novel_audit_rows_preserved|PASS
account_rows_preserved|PASS
chapter_rows_preserved|PASS
check_in_log_rows_preserved|PASS
foreign_key_check_empty|PASS
ledger_audit_rows_preserved|PASS
novel_rows_preserved|PASS
purchase_rows_preserved|PASS
rollback_full_old_rows|PASS
rollback_original_schema|PASS
rollback_version_6|PASS
integrity_check
ok
SQLITE_V6_TO_V7_CHECKS_PASSED
```

第二条命令将迁移结果与本轮 Room 自动生成的 v7 结构比较，原始输出：

```text
ROOM7_GENERATED_SCHEMA_MATCH tables=7 columns=58 indices_including_primary_key=7 foreign_keys=4
Matched: column names/types/nullability/defaults/primary-key positions, index columns/order/uniqueness, foreign-key update/delete rules.
```

这证实桌面 SQLite 成功路径 15 项、显式回滚路径 11 项均通过；两次完整性检查均为 `ok`。桌面的回滚是显式 `ROLLBACK` 演练，不能冒充 Android 上 Room 捕获迁移异常的实际运行结果。

`Migration6To7Test` 已覆盖五条不同历史签到行、七张旧表的数据、空值及二进制内容、重新打开、结构与约束，以及重建后注入异常、回滚后重试。仪器测试编译已通过；Android 执行仍待验证。JVM 及 APK 构建结果见“共同构建记录”。

按用户要求的宽泛检索命令及**完整实际输出**如下：

```text
rg -n --sort path 'AdWatch|ads|ad_|广告' app/src
app/src\androidTest\java\com\example\blb\data\AuditDaoTest.java:126:    public void twoIndependentReadsDeleteOnlyTheProvenOldExtraFact() {
app/src\androidTest\java\com\example\blb\data\Migration5To6Test.java:69:                    + "ad_available INTEGER NOT NULL, ads_watched INTEGER NOT NULL, "
app/src\androidTest\java\com\example\blb\data\Migration5To6Test.java:70:                    + "ads_remaining INTEGER NOT NULL, message TEXT, created_at INTEGER NOT NULL, "
app/src\androidTest\java\com\example\blb\data\Migration6To7Test.java:76:                    + "ad_available INTEGER NOT NULL, ads_watched INTEGER NOT NULL, "
app/src\androidTest\java\com\example\blb\data\Migration6To7Test.java:77:                    + "ads_remaining INTEGER NOT NULL, message TEXT, created_at INTEGER NOT NULL, "
app/src\main\assets\selectors.json:186:  "download_entry": [
app/src\main\assets\selectors.json:189:    { "id": "download_layout", "clickableAncestor": true },
app/src\main\java\com\example\blb\auto\Keys.java:88:    public static final String DOWNLOAD_ENTRY = "download_entry";
app/src\main\java\com\example\blb\data\Db.java:39:     * v1 → v2：加「登录方式 / 代券余额 / 起始章 / 广告计数」四类字段。
app/src\main\java\com\example\blb\data\Db.java:50:            db.execSQL("ALTER TABLE check_in_log ADD COLUMN ads_watched INTEGER NOT NULL DEFAULT 0");
app/src\main\java\com\example\blb\data\Db.java:51:            db.execSQL("ALTER TABLE check_in_log ADD COLUMN ads_remaining INTEGER NOT NULL DEFAULT -1");
app/src\test\java\com\example\blb\auto\MineBalanceRealTreeTest.java:57:    public void readsBothCurrenciesFromMinePage() throws Exception {
app/src\test\java\com\example\blb\auto\MineBalanceRealTreeTest.java:105:    public void readsWalletPageById() throws Exception {
app/src\test\java\com\example\blb\auto\MineBalanceRealTreeTest.java:124:    public void readsBatchPageLine() throws Exception {
app/src\test\java\com\example\blb\auto\RunGateTest.java:19:        ExecutorService threads = Executors.newFixedThreadPool(8);
app/src\test\java\com\example\blb\auto\RunGateTest.java:25:                attempts.add(threads.submit(() -> {
app/src\test\java\com\example\blb\auto\RunGateTest.java:40:            threads.shutdownNow();
app/src\test\java\com\example\blb\auto\StepRunnerWindowTest.java:51:            int reads;
app/src\test\java\com\example\blb\auto\StepRunnerWindowTest.java:54:                return reads++ == 0 ? null : home;
app/src\test\java\com\example\blb\auto\SubscribeRunRecoveryTest.java:391:                    || "download_layout".equals(node.viewId())) {
app/src\test\java\com\example\blb\auto\SubscribeRunRecoveryTest.java:472:                                .withId("download_layout").clickable(true)
app/src\test\java\com\example\blb\auto\SubscribeTaskTest.java:186:        int reads;
app/src\test\java\com\example\blb\auto\SubscribeTaskTest.java:216:            int at = Math.min(reads++, observations.size() - 1);
app/src\test\java\com\example\blb\auto\SubscribeTaskTest.java:315:        assertTrue(session.reads >= 2);
```

这次宽泛检索不是零命中：一部分是必须保留的历史迁移 SQL、冻结的旧数据库测试结构及历史注释；另一部分是 `download_entry/download_layout`、`Reads/reads/threads` 的子串误匹配。未使用拆字符串等方式隐匿老列名。当前运行链路、设置、选择器和实体已没有该功能；但“宽泛 grep 字面零输出”并未满足，报告如实列出上述原因。

进一步针对被删除的真实符号与文案，执行：

```text
rg -n --sort path 'AdWatch|\bAD_[A-Z_]+|KEY_ADS_PER_ACCOUNT|KEY_AD_ASSIST|KEY_AD_JUMP|adsPerAccount|setAdsPerAccount|isAdAssist|setAdAssist|isAdJump|setAdJump|adAvailable|adsWatched|adsRemaining|adPending|checkInAndAds|settings_ad_|settings_section_ads|checkin_ad_notice|detail_notice_|签到\s*→\s*广告\s*→\s*订阅' app/src
```

本次只读复查实际输出为空，退出码为 `1`（ripgrep 的“未匹配”）；与 [ad-feature-scan.txt](D:/SoftWare/Project/android/blb/.gradle/v7-evidence/ad-feature-scan.txt) 的空记录一致。旧三步流程串也没有匹配。

**待真机验证：**

1. 在原安装上覆盖升级，验证 v6→v7 保留真实签到历史、账号、章节、购买记录及核对留痕。
2. 执行 `Migration6To7Test`，核实 Android SQLite/Room 成功路径、故障回滚和重开。
3. 签到页无旧提示，设置无旧分区；主流程和调度正常。

## 问题 4：整套流程只保留签到页入口

### 根因与主要文件

两个页面同时提供整套流程入口，用户无法清楚区分“只订阅”和“逐号签到后订阅”。删除订阅页重复入口，将它的主操作固定为同步目录与核对订阅清单。

主要修改：[fragment_subscription.xml](D:/SoftWare/Project/android/blb/app/src/main/res/layout/fragment_subscription.xml)、[SubscriptionFragment.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/ui/SubscriptionFragment.java)、[fragment_checkin.xml](D:/SoftWare/Project/android/blb/app/src/main/res/layout/fragment_checkin.xml)、[CheckInFragment.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/ui/CheckInFragment.java)、[strings.xml](D:/SoftWare/Project/android/blb/app/src/main/res/values/strings.xml)、[DailyQueue.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/auto/DailyQueue.java)、[DailyCheckInWorker.java](D:/SoftWare/Project/android/blb/app/src/main/java/com/example/blb/work/DailyCheckInWorker.java)。

订阅页的 `run_daily`、绑定、点击、日流程预检及渲染已删除；`sub_daily_cost` 随入口删除。独立的下一章建议仍保留。“只跑订阅”留在下方次要操作横滚区，仍调用 `AutomationService.startSubscribe`。

签到页保留 `run_daily`，调用 `AutomationService.startDaily` → `DailyQueue`。按钮显示实际启用账号数；有 8 个启用账号时就是 8 个，不写死数量。提示按要求改为：

> 上面那颗＝一个号一个号走完签到 → 订阅；下面那颗只给启用的账号签到，不订阅。

每个账号的新步骤序列为：

```text
切到启用账号 → 签到（已签到则保留）→ 读取/回填余额
→ 按既有目录及核对证明检查订阅条件 → 逐章订阅至代券不足
→ 下一个启用账号
```

金额不明仍停下；没有完整核对证明仍不得购买；没有配置目标或订阅选择器不齐时，保留原有仅签到降级并说明原因；有目标却没有目录仍整趟停止。已有的用户可选目录同步逻辑没有借此删掉。

入口实现要区分：发布版界面只有签到页调用 `AutomationService.startDaily`；定时任务直接调用 `DailyQueue.run(app, this)` 并共享运行锁，**并不发送 `ACTION_RUN_DAILY`**。旧 debug 整套运行入口已删。两个预期入口均落到同一队列，定时功能继续保留。

### 验证命令、实际输出与未验证部分

当前源文件检索命令：

```text
rg -n --sort path 'run_daily|run_subscribe|startDaily|ACTION_RUN_DAILY|DailyQueue.run' app/src/main app/src/debug
```

实际输出如下（退出码 0）：

```text
app/src/main\java\com\example\blb\auto\AutomationService.java:38:    public static final String ACTION_RUN_DAILY = "com.example.blb.action.RUN_DAILY";
app/src/main\java\com\example\blb\auto\AutomationService.java:114:    public static void startDaily(Context context) {
app/src/main\java\com\example\blb\auto\AutomationService.java:115:        Intent intent = new Intent(context, AutomationService.class).setAction(ACTION_RUN_DAILY)
app/src/main\java\com\example\blb\auto\AutomationService.java:178:            case ACTION_RUN_DAILY:
app/src/main\java\com\example\blb\auto\AutomationService.java:290:                    DailyQueue.Summary s = DailyQueue.run(this, this);
app/src/main\java\com\example\blb\ui\CheckInFragment.java:79:        runDaily = v.findViewById(R.id.run_daily);
app/src/main\java\com\example\blb\ui\CheckInFragment.java:98:        runDaily.setText(getString(R.string.checkin_run_daily) + "\n"
app/src/main\java\com\example\blb\ui\CheckInFragment.java:106:            runDaily.setText(getString(R.string.checkin_run_daily) + "\n" + detail);
app/src/main\java\com\example\blb\ui\CheckInFragment.java:242:            AutomationService.startDaily(ctx);
app/src/main\java\com\example\blb\ui\SubscriptionFragment.java:114:        runSubscribe = v.findViewById(R.id.run_subscribe);
app/src/main\java\com\example\blb\work\DailyCheckInWorker.java:78:            DailyQueue.Summary summary = DailyQueue.run(app, this);
app/src/main\res\layout\fragment_checkin.xml:127:            android:id="@+id/run_daily"
app/src/main\res\layout\fragment_checkin.xml:133:            android:text="@string/checkin_run_daily"
app/src/main\res\layout\fragment_subscription.xml:114:                            android:id="@+id/run_subscribe"
app/src/main\res\values\strings.xml:35:    <string name="checkin_run_daily">跑今天的整套流程</string>
```

这些是源文件中的调用与界面定义，不是本轮真机截图或运行记录。

公共队列锁、余额、报告、节点与订阅保护测试随整轮 JVM 测试通过，实际构建记录见下一节。

**待真机验证：**

1. 订阅页仅有同步目录、核对订阅清单两颗主按钮；“只跑订阅”仍在下方横滚区。
2. 签到页点击整套流程后，实际 8 个启用账号按顺序各自签到并订阅。
3. “只签到”“只跑订阅”维持各自行为，定时任务仍正常且不与前台重复启动。

## 共同构建记录（最终结果）

使用与项目 Wrapper 相同版本的本地 Gradle 9.5.0，复用已存在的离线依赖。最后一轮实际命令如下，可执行文件绝对路径已只读核验存在：

```powershell
& 'D:\gradle-7.2\GradleRepository\.gradle\wrapper\dists\gradle-9.5.0-bin\bpmkcf6dvq0tjtjezkkgfy1ey\gradle-9.5.0\bin\gradle.bat' --gradle-user-home 'D:\SoftWare\Project\android\blb\.gradle\verification-home' --project-cache-dir 'D:\SoftWare\Project\android\blb\.gradle\v7-project-cache' --init-script '.gradle/v7-verification-init.gradle' --offline --no-daemon --no-configuration-cache --max-workers=2 --console=plain testDebugUnitTest assembleDebug compileDebugAndroidTestJavaWithJavac
```

[最终构建日志](D:/SoftWare/Project/android/blb/.gradle/v7-build-catalog.log) 末尾原文为：

```text
BUILD SUCCESSFUL in 55s
47 actionable tasks: 11 executed, 36 up-to-date
```

最终测试汇总保存在 [final-verification.json](D:/SoftWare/Project/android/blb/.gradle/v7-evidence/final-verification.json)，各套件明细保存在 [jvm-suite-results.json](D:/SoftWare/Project/android/blb/.gradle/v7-evidence/jvm-suite-results.json)。本次只读累加全部套件，结果与最终记录一致：

```json
{"suites":53,"tests":628,"failures":0,"errors":0,"skipped":0}
```

通过项为 JVM 纯判据测试、debug APK 构建和 Android 仪器测试源码编译；没有据此声称真机覆盖安装、Room 仪器测试或真实付费操作已经通过。构建使用原 debug 签名文件的逐字节一致工作区副本，证书与已有 APK 相同，没有新建签名身份。签名比对见 [signing-verification.txt](D:/SoftWare/Project/android/blb/.gradle/v7-evidence/signing-verification.txt)。

## 构建环境、交付包与真机限制

### 实际构建环境适配

最初直接执行用户要求的 Windows 等价命令 `./gradlew.bat testDebugUnitTest assembleDebug`，因为沙箱不能创建全局 Wrapper 的 `gradle-9.5.0-bin.zip.lck` 而失败，见 [首次 Wrapper 输出](D:/SoftWare/Project/android/blb/.gradle/v7-gradlew-attempt.log)。随后使用同版本的本地 Gradle 与工作区缓存，第一次打包仍因用户目录中的 `debug.keystore.lock` 写权限失败，见 [首次本地 Gradle 输出](D:/SoftWare/Project/android/blb/.gradle/v7-build-1.log)。

最终使用工作区构建目录、缓存及原 debug 签名文件的逐字节副本，完成前文记录的全部任务。没有下载新依赖或更换签名身份，也没有把早先失败写成成功；55 秒、628 项测试的结果对应最后一次源码改动之后的构建。之后只整理证据与报告，未再修改源码。

`git diff --check` 实际退出码为 `0`；输出只有工作区已有文件的换行符转换提示，没有空白错误，记录见 [diff-check.txt](D:/SoftWare/Project/android/blb/.gradle/v7-evidence/diff-check.txt)。

### 交付 APK

文件：[blb-1.1-debug.apk](D:/SoftWare/Project/android/blb/build/deliverables/blb-1.1-debug.apk)。

| 项目 | 实际结果 |
|---|---|
| versionName / versionCode | 1.1 / 2 |
| Room 版本 | 7 |
| 文件大小 | 8,416,480 字节 |
| SHA-256 | `261B03ACFC0BE8150E95E7EE18B6F7C0748AB48429DED6459AE02AE6535FE3BE` |
| APK 签名验证 | 退出码 0 |
| 签名证书 SHA-256 | `8258e103ae726ddf80b2aaa28beb2aaa92b6724240b59b8a52ea5c12d3d543b0` |

证书与原 APK 相同，见 [最终签名检查](D:/SoftWare/Project/android/blb/.gradle/v7-evidence/final-apk-certificate.txt) 和 [旧包证书比对](D:/SoftWare/Project/android/blb/.gradle/v7-evidence/signing-verification.txt)。签名一致是覆盖安装的必要条件，不代表已在用户设备覆盖成功。交付内容不包含签名密钥。

### 待真机验证

本轮收到了用户提供的真实旧日志、两份节点 dump、选择数量观察和长截图，并据此修改代码。这些属于取证；本轮没有通过 ADB 安装新 APK、操作真实账本或执行实际购买。各问题的具体验收步骤已分别列于对应章节，仍须验证：保留旧数据的覆盖升级与迁移、正文及无编号章节完整扫描、番外定位和服务器核对、多账号重复历史补记、删除后的两页界面，以及 8 个启用账号逐号签到和订阅、定时任务。

设备列表读取曾因 ADB 初始化失败：

```text
Cannot mkdir '\.android': Permission denied
```

随后请求提权执行只读 `adb devices -l`，被自动审批系统拒绝，返回：

```text
Automatic approval review failed: 404 Not Found
当前 API 不支持所选模型 codex-auto-review
```

自动审批拒绝的是读取设备列表；原因是审批服务不支持所选模型。未绕过该拒绝，覆盖安装、设备迁移测试及真实整趟运行保持“待真机验证”。
