# 给实现者的提示词（复制下面整段发给 GPT）

---

你是一名熟悉 Android 无障碍自动化与本地账本系统的资深工程师。你要在一个**已经在真机上跑着的** Android 项目里，按一份既定计划实现两个新功能。这份提示词是自包含的：读完它再动手，不要凭经验猜项目结构。

## 0. 仓库与构建

- 仓库根目录：`D:\SoftWare\Project\android\blb`（Windows；shell 是 Git Bash，用 `./gradlew`）
- 技术栈：Java（不是 Kotlin）、Room、Material Components、单 Activity + 多个 Fragment、前台 Service + AccessibilityService。**没有任何网络请求**，所有数据在本地 SQLite。没有协程 / RxJava / 第三方库。
- 构建：`./gradlew assembleDebug`
- JVM 单测：`./gradlew testDebugUnitTest`（测试在 `app/src/test/java/com/example/blb/`）
- 设备测试：`./gradlew connectedDebugAndroidTest`（在 `app/src/androidTest/`）
- 当前 Room 版本：`AppDatabase.version = 5`

## 1. 必读文件（按这个顺序读，读完再写代码）

| 文件 | 它负责什么 |
|---|---|
| `app/src/main/java/com/example/blb/auto/SubscribeRun.java` | 每个账号的购买循环：$\to$ 目录同步 $\to$ 核账 $\to$ 逐章真买。**本计划要大改它** |
| `.../auto/CatalogSync.java` | 把扫到的目录写进账本（章节表 + 免费章回填 + 按标题重排章号） |
| `.../auto/CatalogScanner.java` | 整本目录的滚动扫描与可信性护栏（扫不干净就拒绝写账本） |
| `.../auto/CatalogAlign.java` | 作者插章/删章时按标题把章号搬家 |
| `.../auto/VoucherLedger.java` | 「我的 → 代券 → 订阅清单」那一行（**按书聚合**的只读第二来源） |
| `.../auto/SubscribedDetail.java` | 点开清单那一行后的**逐章**明细（只读） |
| `.../auto/RemoteLedgerRecovery.java` | 拿远端逐章明细严格校验后事务补账（纯判定 + 提交分离） |
| `.../auto/DailyQueue.java` | 每天的整套流程：逐号 签到 → 广告 → 订阅 |
| `.../auto/AutomationService.java` | 前台 Service、队列分发、通知、Mode 枚举 |
| `.../data/SubscriptionDao.java` | 所有账本 SQL（注意里面每条 `@Query` 上的中文注释） |
| `.../data/AppDatabase.java`、`.../data/Db.java` | Room 数据库定义与 v1→v5 迁移写法 |
| `.../ui/SubscriptionFragment.java` + `app/src/main/res/layout/fragment_subscription.xml` | 订阅页 |
| `.../ui/DetailActivity.java`、`.../auto/RunReport.java`、`.../util/Prefs.java`、`.../auto/Keys.java` | 二级页面、跑完弹的结论、设置开关、选择器 key |

仓库里还有一份更详细的设计说明：`docs/plan-v2-catalog-and-ledger-audit.md`。**它的内容与本提示词第 3~6 节一致**，有冲突时以本提示词为准，并在回复里指出冲突。

## 2. 代码约定（违反任何一条都会被打回）

1. **注释写「为什么」，不写「做了什么」**，而且通常要带上这个决定是被哪次真机事故逼出来的（项目里所有注释都是这个风格，照着那个密度和语气写）。
2. **不加任何第三方依赖**，不引入 Kotlin / 协程 / RxJava / 新的架构模式。
3. **涉及钱的判断一律 fail-closed**：「读不到」不等于「对不上」；不确定就停下等人，绝不猜。
4. **读不到的数字一律用 `-1` 表示「不知道」**，绝不用 `0` 顶替。
5. **纯判据必须拆成 static 纯函数**并写进 `app/src/test` 的单测里钉住；碰数据库、碰屏幕的代码不写单测。
6. **不碰钱的写操作**（写账本、改设置）走已有的 `Db.io(...)` 单线程执行器与 `LedgerEdits`；**不许另起线程池**。
7. 用户的手指基本动不了屏幕，界面上任何「只有点开才看得到」的信息对他等于不存在 —— 摘要必须直接写在列表行上。

## 3. 领域词典与四条不变量

| 名词 | 含义 |
|---|---|
| **目录** | 这本书在「选择章节」页上的有序章节行；`chapter.chapter_no` = 界面第几行 |
| **账本** | 哪个号买了哪一章（`purchase` 表） |
| **订阅清单** | 服务器上「这个号在这本书订了几章、花了多少火券」，**按书聚合的一行**（只读） |
| **订阅明细** | 点开清单那一行之后的**逐章**列表（只读） |
| **下一章** | 起始章之后、账本里**没有任何号拥有**的最小章 |

**四条不变量（不许破坏，每条都要有测试）**

- **I1** 目录是位置账：`chapter_no` 必须等于界面第几行；作者插章由 `CatalogAlign` 按标题搬家，搬不动就整趟停。
- **I2** 「下一章」**永远现算，绝不落库成字段**：买之前、买之后各查一次账本。
- **I3** 一章最多一个号花过券（`cost_coupons>0 OR cost_vouchers>0`）——免费章的 `source='OWNED'` 记录不在此列。
- **I4** 删除账本记录一律**有证据、有留痕、可撤销**；证据不足就转「存疑」，绝不删。

## 4. 用户每天的使用方式（这是本计划的验收场景）

1. 打开 App → 订阅页 → 点 **同步目录**；
2. 点 **核对订阅清单**；
3. 点 **跑今天的整套流程**（签到 → 广告 → 订阅）。

用户要解决的三件事：
- 目录扫描**不要**再占整套流程的时间（现在它在 `SubscribeRun.oneAccount` 里，**每个号扫一遍**，一趟 8 遍，每遍失败都会让整趟 `MONEY_UNCLEAR` 中止）；
- 订阅清单和账本对不上要能修：漏记的补上、多记的删掉；
- 8 个号订同一本书，一章只能有一个号买；买完一章要立刻反映到「下一章」上。

## 5. 数据模型改动（Room v5 → v6）

```sql
-- ① novel 加两列
ALTER TABLE novel ADD COLUMN catalog_scanned_at    INTEGER NOT NULL DEFAULT 0;
ALTER TABLE novel ADD COLUMN catalog_chapter_count INTEGER NOT NULL DEFAULT 0;

-- ② 每个号 × 每本书的核对进度
CREATE TABLE IF NOT EXISTS account_novel_audit (
  account_id         INTEGER NOT NULL,
  novel_id           INTEGER NOT NULL,
  aggregate_at       INTEGER NOT NULL DEFAULT 0,   -- 聚合行最近读取时间
  aggregate_chapters INTEGER NOT NULL DEFAULT -1,  -- 读到几章，-1＝读不到
  detail_at          INTEGER NOT NULL DEFAULT 0,   -- 逐章明细最近「完整」读取时间
  detail_chapters    INTEGER NOT NULL DEFAULT -1,
  ledger_marker      TEXT,                         -- 核对那一刻该号在这本书上的账本指纹
  PRIMARY KEY(account_id, novel_id)
);

-- ③ 核对动作流水 + 删除留痕
CREATE TABLE IF NOT EXISTS ledger_audit (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  at         INTEGER NOT NULL,
  account_id INTEGER NOT NULL,
  novel_id   INTEGER NOT NULL,
  kind       TEXT    NOT NULL,   -- BACKFILL | DELETE | SUSPECT | RESTORE
  chapter_id INTEGER NOT NULL DEFAULT 0,
  chapter_no INTEGER NOT NULL DEFAULT 0,
  title      TEXT,
  detail     TEXT                -- 人读的一句话 + 被删行的原始内容（据此可重建）
);
```

- `ledger_marker` = `COUNT(*) || ':' || IFNULL(MAX(id),0)`（该号在这本书上的付费行）。
- 新增实体 `data/AccountNovelAudit.java`、`data/LedgerAudit.java`，加进 `AppDatabase.entities`，`version = 6`。
- 新增 `data/AuditDao.java` —— **不要**继续往已经 516 行的 `SubscriptionDao` 里塞。
- 在 `Db.java` 加 `MIGRATION_5_6` 并注册进 `.addMigrations(...)`。
- 建表语句必须与实体声明**逐列一致**（列名 / NOT NULL / DEFAULT），否则 Room 打开数据库时会抛 `IllegalStateException`。

## 6. 分阶段任务（按顺序做；每个阶段做完都必须能单独验证）

### 阶段 A：地基（不改任何现有行为）

1. 完成第 5 节的全部数据模型改动。
2. 在 `SubscribeRun.oneAccount` 里，把「下一章」的重算抽成一个私有小函数，并在**每买完一章**之后调用它，写一行日志：`下一章：第 N 章「标题」`。
3. 单测：`app/src/test/java/com/example/blb/auto/SubscribeRunNextChapterTest.java`。
4. 验收：`./gradlew testDebugUnitTest assembleDebug` 全绿；不清数据覆盖安装（`adb install -r`）后 App 能打开、老账本与老账号都在。

### 阶段 B：`同步目录` 按钮

1. 新建 `auto/CatalogQueue.java`（对齐 `DailyQueue` 的写法与摘要风格）：
   - 解析目标书（复用 `SubscribeRun.resolveTarget`）；没有目标书 → 报「先在订阅页选一本目标小说」，**不启动**；
   - 检查选择器 `Keys.REQUIRED_FOR_SUBSCRIBE`，缺 key 就报缺哪个；
   - 用**当前登录着的号**（不主动切号）：`r.ensureHome()` → `CatalogSync.sync(r, subs, novel, accountId)`；
   - 扫完写 `catalog_scanned_at / catalog_chapter_count`；
   - 结束时必须 `r.ensureHome()`（独立按钮要自己收尾）。
2. 改 `CatalogSync.write`：免费章的 `OWNED` 记录**给所有启用账号各补一条**（现在只补当前登录的那个号）。日志要写清"补记 N 条（8 个号各一条）"。
3. `AutomationService`：加 `ACTION_RUN_CATALOG`、`Mode.CATALOG("同步目录")`、`startCatalog(ctx)`、`runQueue` 分支。
4. `RunReport.ofCatalog(...)`：扫到几章 / 新登记几章 / 重排几章 / 免费章补几条。
5. 订阅页加按钮 + 一行常驻摘要：`目录：今天 09:12 扫过 · 612 章`（没扫过就写「还没扫过」）。
6. 验收：点一次按钮，日志出现「目录 612 章（新登记 N）」，摘要时间刷新，**一章都没买**。

### 阶段 C：`核对订阅清单` 按钮（风险最高，先写纯函数和测试）

1. 新建 **纯函数** `auto/RemoteLedgerRepair.java`（不碰数据库、不碰屏幕，照 `RemoteLedgerRecovery` 的写法）：
   ```java
   static Plan deletionPlan(String who, String book, long accountId,
                            VoucherLedger.Reading aggregate,
                            List<SubscribedDetail.Entry> entries,   // 完整明细
                            List<Chapter> chapters,                 // 本地目录
                            List<PurchaseRow> paidRowsOfNovel,      // 全书付费行
                            long now);
   ```
   只有当下面**七条全部成立**时才产出「删除计划」，否则产出「存疑」：

   | # | 条件 | 防的是什么 |
   |---|---|---|
   | ① | 聚合行 `found==true` 且 `chapters >= 0` | 书名不一致 / 清单没加载出来 → 一次删光全书 |
   | ② | 明细完整读到（到底、未截断、每条都能解析出章号与标题） | 翻页只读一半 → 把没读到的章当成「清单没有」 |
   | ③ | 明细条数 == 聚合章数 | 两层互相印证 |
   | ④ | 待删记录 `source != 'OWNED'` | 免费章本来就不出现在清单里 |
   | ⑤ | `now - purchased_at > 15 分钟`（用 `SubscribedDetail.FRESH_MS`） | 页脚写着「清单约5分钟更新一次」 |
   | ⑥ | 待删章在明细里缺席，且**章号+标题**能在本地目录里唯一对应 | 作者插章导致错位时删错行 |
   | ⑦ | 该章没有记在别的号名下 | 「一章两个号」是另一类问题，只报告 |

2. 单测 `RemoteLedgerRepairTest`：把上面 ①~⑦ **逐条写成反例**，每条都断言「不删、转存疑」。
3. 新建 `auto/SubscriptionAuditQueue.java`：对 8 个启用账号逐个
   `AccountSwitcher.ensureLoggedIn` → `VoucherLedger.locate`（聚合行）→ `SubscribedDetail.read`（逐章）→ 判定 → 提交 → 写 `account_novel_audit` + `ledger_audit`。
   分叉规则：
   - 选择器没配齐 → 这个号记「未核对」，**继续下一个号**；
   - `found == false`（清单里没有这本书那一行）→ **一个字都不删**，只记「这一次没能核对」；
   - 清单上花了火券 → 硬错误，报告并停止；
   - `chapters > 账本` → 走**已有的补记路径**（`RemoteLedgerRecovery.plan` + `restoreRemotePurchases` + 二次核对），不要另写一套；
   - `chapters < 账本` → 走 `deletionPlan`；
   - 相等 → 走已有的 `SubscribedDetail.reconcile` 逐章复核。
   **单号失败不终止整队**；`MONEY_UNCLEAR` 这类全局失败仍然整趟停。
4. 删除的提交顺序（同一个事务）：先补后删 → 事务内校验快照没变（章节表 / 该号付费行 / 全书付费行，照 `restoreRemotePurchases` 的做法）→ **删前定向复核**（退出明细页 → 重新点清单那一行 → 再读一次明细，两次都缺席才删）→ 写 `ledger_audit(kind='DELETE', detail=被删行的完整内容)`。
5. 提供撤销：`AuditDao.restore(auditId)` 用留痕里的原始内容重建那条 purchase。
6. `AutomationService.startAudit(ctx)` + `Mode.AUDIT("核对清单")`；`RunReport.ofAudit(...)`；订阅页按钮 + 摘要（`最后核对 今天 09:20 · 8 个号 · 补记 3 · 修正 1`）；新增 `DetailActivity.PAGE_SUSPECT` 页列出 `ledger_audit` 最近 N 条。
7. **结束时**：重算一次「下一章」写进日志与结论。
8. 验收（分三次做，每次都要有现象）：
   - 正常跑：8 个号逐个出现「XX 在《…》上：…」，结束弹结论；
   - 补记：手工删掉某号一条真记录 → 必须报「账本漏记 1 章」并补回，**不删任何东西**；
   - **不删（关键）**：把目标书书名临时改成一个对不上的名字 → 必须只报「清单里没有这本书那一行，这一次没能核对」，**一条记录都不许删**。

### 阶段 D：整套流程不再扫目录（收益最直接，也可以提到阶段 B 之后先做）

1. 删掉 `SubscribeRun.oneAccount` 里的 `syncCatalog(...)` 调用（以及只服务于它的 `catalogFailure`）；`Plan` 增加 `catalogScannedAt`。
2. **目录护栏**（替换掉被删的那道）：
   - 章节表为空 → `StepRunner.StepFailure(CONFIG, "这本书的账本还没有章节：先在订阅页点『同步目录』")`，整趟停 —— **绝不允许像现在这样只打一句「没有待订阅的章节」然后什么都不做**；
   - `catalog_scanned_at` 超过 N 小时（默认 24，放 `Prefs`）→ 日志和最终结论里明说「目录是 X 小时前扫的，作者可能有新章没进账本」。默认**不**自动重扫；设置页给一个「过期就自动扫一次（用第一个号）」开关，默认关。
3. **对账减负**：聚合行每个号每趟都读；逐章明细只在 ①聚合对不上 ②这个号今天没做过逐章 ③`ledger_marker` 变了 ④这个号这趟将要真花钱 时才读，否则写一行「这个号 X 分钟前已逐章核过，账本没变，跳过逐章」。设置页给一个「每次都逐章核对」开关（默认关）。
4. **买完立刻更新「下一章」**：每买完一章重算一次并写日志；如果重算出的章号**比刚买的那一章还小**，立刻停下并报告（说明前面有章被漏了或账本刚被改过）。
5. `DailyQueue.Summary` 加 `catalogNote`；`RunReport.ofDaily` 加「目录：今天 09:12 扫过 · 612 章」和「下一章：第 N 章」。
6. 验收：跑整套流程，日志里**不再出现**整本目录扫描（只有「目录：今天 09:12 扫过」），每次买完紧跟一行「下一章：第 N 章」。

### 阶段 E：界面与文案

1. 订阅页主按钮区改成竖排三个按钮（次要操作仍在下面横滚）：`同步目录` / `核对订阅清单` / `跑今天的整套流程`，各带一行常驻摘要。
2. 三个按钮都跟随 `AutomationBus.busy()` 禁用（已有观察者，照现有写法接）。
3. 按钮上要写明代价，例如「核对订阅清单：要 8 个号逐个登录，中途别动屏幕」。**具体耗时等真机跑完一次后填实测值 —— 不要编一个时间。**
4. 文案清理：**删掉/改掉**订阅页那句「开始后会先扫描最新目录，检查有没有新章」，以及 `sub_nothing_to_do` 里「用『添加章节』登记新章」的口径。
5. 新增 `Keys.REQUIRED_FOR_CATALOG`（= `REQUIRED_FOR_SUBSCRIBE`）与 `Keys.REQUIRED_FOR_AUDIT`（= `VoucherLedger.REQUIRED` + `SubscribedDetail.REQUIRED`），设置页自检跟着显示。

### 阶段 F：真机验收

按第 4 节的用户顺序完整走一遍，逐条回报现象。

## 7. 明确不要做的事

- ❌ 不要改 `SubscribeTask` 的购买动作（选章 / 勾选 / 立即下载 / 余额判据），也不要动 `ChapterRowState`。
- ❌ 不要改签到和广告。
- ❌ 不要把「下一章」存成数据库字段或内存里的长期缓存。
- ❌ 不要在七条前置条件之外删除任何 `purchase` 记录；`found == false` 时**永远不许删**。
- ❌ 不要给这个 App 加任何网络请求。
- ❌ 不要为了让测试通过而放松现有的护栏（`gapNote`、`MONEY_UNCLEAR`、扫描的 `trustworthy()` 等）。
- ❌ 不要顺手重构无关文件。

## 8. 交付与汇报格式

- 每个阶段一个 commit，message 用中文，一句话说清**做了什么 + 为什么**。
- 每阶段结束跑 `./gradlew testDebugUnitTest assembleDebug` 并贴出结果；不通过不算完成。
- 每阶段回复用这个格式：
  ```
  ## 阶段 X 完成
  改了：<文件列表，每个一句话>
  验证：<命令 + 实际输出摘要>
  现象：<真机/日志能看到什么>
  遗留：<没做的、不确定的、需要用户拍板的>
  ```
- 任何与现有代码护栏冲突的地方，**先停下来说明冲突**，不要偷偷改掉护栏。
- 不确定的规则（尤其是删除相关的判断）**先问，不要自己发明**。

## 9. 开始之前请先回答我三个问题

1. 你打算先从哪个阶段开始？为什么？
2. 第 5 节的数据模型改动里，迁移建表语句与 Room 实体的列定义要怎么保证一致？你准备怎么验证迁移没有写坏？
3. `deletionPlan` 的七条前置条件里，哪一条你认为最容易在真机上被误触发？你打算加什么额外的日志或断言来暴露它？
