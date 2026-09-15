# 计划 v2：把「同步目录」和「核对订阅清单」从整套流程里拆出来

> 目标读者：实现这份计划的人。每一步都写清楚**改哪个文件、怎么改、改完怎么验证**。
> 代码基线：当前工作区（未提交状态），Room DB v5。

---

## 0. 这份计划要对齐的目的

用户每天的动作（口述原文）：

1. 打开 App → 订阅页 → 点**扫描目录**；
2. 点**扫描订阅清单**；
3. 点**跑今天的整套流程**（签到 → 广告 → 订阅）。

| 编号 | 目的 | 现在的状况 | 本计划怎么满足 |
|---|---|---|---|
| G1 | 目录扫描不占整套流程的时间 | 目录扫描在 `SubscribeRun.oneAccount` 里，**每个号扫一遍**（一趟 8 遍），每遍失败都 `MONEY_UNCLEAR` 整趟中止 | 拆成独立按钮 + 独立队列；流程里一次都不扫 |
| G2 | 订阅清单和账本对不上就修 | 「清单有、账本没有 → 补记」已经有了（`RemoteLedgerRecovery`）；「账本有、清单没有 → 删除」**故意没做** | 新按钮 = 8 个号全量核对；补记沿用已有路径；**删除新做，但带完整证据链 + 留痕 + 可撤销** |
| G3 | 8 个号订同一本，一章只能一个号买 | 已经成立：`findNextUnownedChapterFrom` 现算 + `Settled` + 买前对账 | 把「下一章现算」变成**看得见的日志/摘要**，并加一条不变量断言 |
| G4 | 买完一章立刻更新「下一章」 | 账本层面已经立即落库；但日志/界面上看不见 | 每买完一章重算一次并写进日志；核对完清单后也重算一次 |
| G5 | 作者更新了，账本要跟上 | `CatalogSync` 会追加新章、按标题搬家 | 保留，只把调用点从"每号一次"改成"按钮一次" |

**非目标（这次明确不做）**：不改购买动作本身（`SubscribeTask` 的选章/勾选/立即下载/余额判据）、不改签到与广告、不做定时自动核对、不做云同步、不改「一章一个号」的语义。

---

## 1. 名词与不变量

| 名词 | 含义 | 存在哪 |
|---|---|---|
| **目录** | 这本书在「选择章节」页上的有序章节行 | `chapter` 表；`chapter_no` = 界面第几行 |
| **账本** | 哪个号买了哪一章 | `purchase` 表 |
| **订阅清单** | 服务器上「这个号在这本书订了几章、花了多少火券」——**按书聚合的一行** | 只读，`VoucherLedger` |
| **订阅明细** | 点开清单那一行之后的逐章列表 | 只读，`SubscribedDetail` |
| **下一章** | 起始章之后，账本里**没有任何号拥有**的最小章 | 现算：`SubscriptionDao.findNextUnownedChapterFrom` |

### 四条不变量（每条都要有测试钉住）

- **I1 目录是位置账**：`chapter.chapter_no` 必须等于界面第几行；作者插章由 `CatalogAlign` 按标题搬家，搬不动就停。
- **I2「下一章」永远现算，绝不落库成字段**：买之前、买之后各查一次账本。
- **I3 一章最多一个号花过券**：`SELECT chapter_id FROM purchase WHERE cost_coupons>0 OR cost_vouchers>0 GROUP BY chapter_id HAVING COUNT(*)>1` 必须为空（免费章的 `OWNED` 记录不在此列）。
- **I4 删除一律有证据、有留痕、可撤销**：证据不足时改成「存疑」，绝不删。

---

## 2. 数据模型改动（DB v5 → v6）

### 2.1 `novel` 加两列

```sql
ALTER TABLE novel ADD COLUMN catalog_scanned_at    INTEGER NOT NULL DEFAULT 0;  -- 目录最近一次扫成功的时间
ALTER TABLE novel ADD COLUMN catalog_chapter_count INTEGER NOT NULL DEFAULT 0;  -- 那次扫到几章
```

用途：摘要显示「目录：今天 09:12 · 612 章」；流程判断目录是否过期（**只提示，不参与能不能买的判断**）。

### 2.2 新表 `account_novel_audit`（每个号 × 每本书的核对进度）

```sql
CREATE TABLE IF NOT EXISTS account_novel_audit (
  account_id         INTEGER NOT NULL,
  novel_id           INTEGER NOT NULL,
  aggregate_at       INTEGER NOT NULL DEFAULT 0,   -- 聚合行最近读取时间
  aggregate_chapters INTEGER NOT NULL DEFAULT -1,  -- 那次读到的章数，-1＝读不到
  detail_at          INTEGER NOT NULL DEFAULT 0,   -- 逐章明细最近**完整**读取时间
  detail_chapters    INTEGER NOT NULL DEFAULT -1,
  ledger_marker      TEXT,                         -- 核对那一刻该号在这本书上的账本指纹
  PRIMARY KEY(account_id, novel_id)
);
```

`ledger_marker` = `COUNT(*) || ':' || IFNULL(MAX(id),0)`（该号在这本书上的付费行）。
用途：流程里判断「这个号已经核过、账本此后没变」→ 跳过逐章明细重读（见 D3）。

### 2.3 新表 `ledger_audit`（核对动作流水 + 删除留痕）

```sql
CREATE TABLE IF NOT EXISTS ledger_audit (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  at         INTEGER NOT NULL,
  account_id INTEGER NOT NULL,
  novel_id   INTEGER NOT NULL,
  kind       TEXT    NOT NULL,          -- BACKFILL | DELETE | SUSPECT | RESTORE
  chapter_id INTEGER NOT NULL DEFAULT 0,
  chapter_no INTEGER NOT NULL DEFAULT 0,
  title      TEXT,
  detail     TEXT                       -- 人读的一句话 + 被删行的原始内容（可据此重建）
);
```

### 2.4 落地方式

- 新增实体 `data/AccountNovelAudit.java`、`data/LedgerAudit.java`，加入 `AppDatabase.entities`，`version = 6`。
- 新增 `data/AuditDao.java`（不要继续往已经 516 行的 `SubscriptionDao` 里塞）。
- `Db.java` 加 `MIGRATION_5_6`，并加进 `.addMigrations(...)`。
- **建表语句必须和实体声明逐列一致**（列名、NOT NULL、DEFAULT），否则 Room 打开数据库时会抛 `IllegalStateException`。
- 验证：不清数据覆盖安装（`adb install -r`），能正常打开、老账本还在。

---

## 3. 三个按钮的行为规格

三个按钮都只对**「集中订阅目标」那一本**生效（`SubscribeRun.resolveTarget`）。队列接口都带 `novelId` 参数，将来要多本时只改 UI。

### 3.1 `同步目录`（新队列 `auto/CatalogQueue.java`）

1. 解析目标书；没有 → 报「先在订阅页选一本目标小说」，不启动。
2. 检查选择器：`Keys.REQUIRED_FOR_SUBSCRIBE` 缺 key → 报缺哪个，不启动。
3. 用**当前登录着的号**（不主动切号）：`r.ensureHome()` → `CatalogSync.sync(r, subs, novel, accountId)`（已有：进选章页 → 整本扫 → 可信性护栏 → 按标题重排章号 → 写章节表 → 免费章回填 → 滚回顶部）。
4. **新增**：扫完把 `catalog_scanned_at / catalog_chapter_count` 写进 `novel`。
5. **新增**：结束时 `r.ensureHome()`（独立按钮必须自己收尾，不能像现在这样靠业务流程接下一步）。
6. 产出 `CatalogQueue.Summary` → `RunReport.ofCatalog(...)`：扫到几章、新登记几章、重排几章、免费章补几条、跳过几行卷标题。

**改 `CatalogSync.write`**：免费章的 `OWNED` 记录**给所有启用账号各补一条**（现在只补当前登录那个号）。
理由：免费章 8 个号都看得到，这不是猜；只补一个号会让另外 7 个号的「免费章」统计永远是 0。功能上不影响（`findUnownedChaptersFrom` 是"任何号有记录就算有主"）——这是**统计口径**修正，日志写清"补记 N 条（8 个号各一条）"。

**不做**：不自动买任何章；`CatalogSync.rescanIfGap` 的抖动重扫保留。

### 3.2 `核对订阅清单`（新队列 `auto/SubscriptionAuditQueue.java`）

对 8 个启用账号逐个：

```
AccountSwitcher.ensureLoggedIn(runner, account, accountDao, 单号?)   // 已有
  ↓
① 聚合层：VoucherLedger.locate(r, novel.title)                        // 已有
  ↓
② 逐章层：SubscribedDetail.read(r, found.row, novel.title)             // 已有
  ↓
③ 判定：RemoteLedgerRecovery.plan(...) 补记（已有） / RemoteLedgerRepair.deletionPlan(...) 删除（新）
  ↓
④ 提交：SubscriptionDao.restoreRemotePurchases(...)（已有） / 新的删除事务（新）
  ↓
⑤ 记录：account_novel_audit + ledger_audit
```

**分叉规则**（每个号独立，单号失败不终止整队；`MONEY_UNCLEAR` 这类全局失败仍整趟停）：

| 聚合层看到的结果 | 动作 |
|---|---|
| 选择器没配齐 | 这个号记「未核对」，继续下一个号（不中止） |
| `found == false`（清单里没有这本书那一行） | **一个字都不删**；若账本有记录 → 记「清单里没有这本书那一行，这一次没能核对」。原因太多：没加载完、停在「漫画」tab、**书名字样和账本不一致**、滚动没找到 —— 按书名删会一次清空整本书 |
| `fireSpent()`（清单上花了火券） | 硬错误，报告并停止（沿用现有语义） |
| `chapters > 账本` | **补记**：读明细 → `RemoteLedgerRecovery.plan` 严格校验 → 事务补账 → 二次核对（全是已有代码） |
| `chapters < 账本` | **删除判定**（见 3.3） |
| 相等 | **逐章复核**（`SubscribedDetail.reconcile`，已有）；对不上再按上面两条分叉 |

**结束时**：重算「下一章」写进日志与结论 —— 这就是"扫完清单如果发现最新章节已经订了，就立刻更新"。

### 3.3 删除判定（本计划风险最高的一段）

**纯函数 `auto/RemoteLedgerRepair.java`**（不碰数据库，可单测），签名对齐已有的 `RemoteLedgerRecovery.plan`：

```java
static Plan deletionPlan(String who, String book, long accountId,
                         VoucherLedger.Reading aggregate,
                         List<SubscribedDetail.Entry> entries,   // 完整明细
                         List<Chapter> chapters,                 // 本地目录
                         List<PurchaseRow> paidRowsOfNovel,      // 全书付费行
                         long now);
```

**前置条件，少一条就不删（改为"存疑"）：**

| # | 条件 | 防的是 |
|---|---|---|
| ① | 聚合行 `found==true` 且 `chapters >= 0` | 书名不一致 / 清单没加载出来 → 一次删光全书 |
| ② | 明细**完整**读到（到底、未截断、每条都能解析出章号与标题） | 翻页只读到一半 → 把没读到的章当成"清单没有" |
| ③ | 明细条数 == 聚合章数 | 两层互相印证，任一层读错都不删 |
| ④ | 待删记录 `source != 'OWNED'`（免费章不参与） | 免费章本来就不出现在清单里 |
| ⑤ | `now - purchased_at > 15 分钟`（`SubscribedDetail.FRESH_MS`） | 页脚写着"清单约5分钟更新一次"，刚买完的章还没同步 |
| ⑥ | 待删章在明细里缺席，且**章号+标题**能在本地目录里唯一对应 | 作者插章导致章号错位时删错行 |
| ⑦ | 该章没有记在**别的号**名下 | "一章两个号"是另一类问题，只报告，不在这里删 |

**执行顺序（同一个事务）**：
1. 先补后删（补记可能改变章号到章节的映射）。
2. 事务内校验快照没变：章节表、该号付费行、全书付费行（复用 `sameChapterSnapshot / samePaidSnapshot` 的做法）。
3. **删前定向复核**：退出明细页 → 重新点清单那一行 → 再读一次明细；两次都缺席才删（对付"读到一半"和"页面没刷新"）。
4. 删除写 `ledger_audit(kind='DELETE', detail=被删行的完整内容)`。
5. 任一步不满足 → 写 `ledger_audit(kind='SUSPECT')`，**不删**，报告出来。

**存疑章的后果**：流程里视为"不敢买"——整本停在这一章（沿用现有 fail-closed 语义，绝不跳过它去买后面的）；界面单独一页列出来，用户点一下"确认删除并重订"再走一次。

**撤销**：用 `ledger_audit` 里存的原始行内容重建那条 purchase（新的 `AuditDao.restore(purchaseId)`）。

### 3.4 `跑今天的整套流程`（改 `SubscribeRun` / `DailyQueue`）

- **D1 去掉每号目录扫描**：删掉 `SubscribeRun.oneAccount` 里的 `syncCatalog(...)` 调用（以及只服务于它的 `catalogFailure`）。`SubscribeRun.Plan` 增加 `catalogScannedAt`（从 `novel` 读）。
- **D2 目录护栏（替换掉被删的那道）**：
  - 章节表为空 → `StepRunner.StepFailure(CONFIG, "这本书的账本还没有章节：先在订阅页点『同步目录』")`，整趟停。**不能像现在这样只打一句"没有待订阅的章节"然后什么都不做**——扫描搬出去之后那会变成常态。
  - `catalog_scanned_at` 超过 N 小时（默认 24，放 `Prefs`）→ 日志和结论里明说"目录是 X 小时前扫的，作者可能有新章没进账本"。默认**不**自动重扫；设置页给一个"过期就自动扫一次（用第一个号）"开关，默认关。
- **D3 对账减负**（这条让"先点核对订阅清单"真的省下流程里的时间）：
  - **聚合行**：每个号每趟都读（便宜，能挡住"券扣了没记账"这种要动钱的事）。
  - **逐章明细**：满足任一才读 —— ① 聚合对不上；② 这个号今天没做过逐章（`detail_at` 不是今天）；③ `ledger_marker` 与上次核对时不一致；④ 这个号这一趟将要真花钱（待订阅列表非空）。否则写一行"这个号 X 分钟前已逐章核过，账本没变，跳过逐章"。
  - 设置页给"每次都逐章核对"开关（默认关），怀疑账本时强制全核。
- **D4 买完立刻更新「下一章」**：`oneAccount` 每买完一章 → 重新 `findNextUnownedChapterFrom` → 写日志 `下一章：第 N 章「标题」`。若重算出来的章号**比刚买的那一章小** → 立刻停下并报告（说明前面有章被漏了或账本刚被改过）——这是 I2 的不变量断言。
- **D5 结论**：`DailyQueue.Summary` 加 `catalogNote`；`RunReport.ofDaily` 加"目录：今天 09:12 扫过 · 612 章"与"下一章：第 N 章"。

---

## 4. 界面

### 4.1 订阅页（`fragment_subscription.xml` + `SubscriptionFragment`）

主按钮区从「一个主按钮 + 5 个横滚次要操作」改成**竖排三个主按钮**（次要操作不变，仍在下面横滚）：

```
[ ⟳ 同步目录 ]        摘要：今天 09:12 扫过 · 612 章 · 下一章 第 613 章
[ ☑ 核对订阅清单 ]    摘要：最后核对 今天 09:20 · 8 个号 · 补记 3 · 修正 1
[ ▶ 跑今天的整套流程 ] 摘要：（沿用现有"下一章该买：第 N 章 / 建议用：某某"）
[ 存疑与已修正（2）]   → DetailActivity.PAGE_SUSPECT（只在有内容时显示）
```

- 三个按钮都走 `AutomationBus.busy()` 禁用（已有观察者），都在签到页能停。
- 摘要必须**不点也看得见**（使用者手指动不了，只有点开才看得到的信息等于不存在 —— 这一页已有的设计原则）。
- 按钮要写明代价，例如"核对订阅清单：要 8 个号逐个登录，中途别动屏幕"。**具体耗时等真机跑完一次后填实测值**（不要编）。

### 4.2 新页 `DetailActivity.PAGE_SUSPECT`

列出 `ledger_audit` 最近 N 条：时间、号、章、动作（补记/修正/存疑/撤销）、依据一句话。每条修正记录带"撤销"。

### 4.3 文案

- 新增 `strings.xml`：`sub_sync_catalog`、`sub_audit_ledger`、`sub_suspect_entry` 等。
- **改掉**订阅页那句「开始后会先扫描最新目录，检查有没有新章」（`SubscriptionFragment.confirmSubscribe`）以及 `sub_nothing_to_do` 里"用「添加章节」登记新章"的口径。
- 选择器自检：新增 `Keys.REQUIRED_FOR_CATALOG`（= `REQUIRED_FOR_SUBSCRIBE`）与 `REQUIRED_FOR_AUDIT`（= `VoucherLedger.REQUIRED` + `SubscribedDetail.REQUIRED`），设置页自检跟着显示。

### 4.4 服务与通知

- `AutomationService`：新增 `ACTION_RUN_CATALOG` / `ACTION_RUN_AUDIT`、`Mode.CATALOG("同步目录")` / `Mode.AUDIT("核对清单")`、`startCatalog(ctx)` / `startAudit(ctx)`、`runQueue` 两个分支。
- `RunReport`：新增 `ofCatalog(...)` / `ofAudit(...)`，沿用"弹窗只放结论、点名最多 3 个"的现有风格。

---

## 5. 每天的用法（改造后）

| 你的步骤 | 按钮 | 它做什么 | 备注 |
|---|---|---|---|
| 1 | **同步目录** | 一个号把整本目录扫一遍 → 建/更新章节表、给 8 个号补免费章、记下扫描时间 | 不额外登录（用当前登着的号） |
| 2 | **核对订阅清单** | 8 个号逐个：读清单聚合行 + 逐章明细 → 补漏记、删多记（有证据才删）、标存疑 → 重算「下一章」 | 8 次登录；顺带写下 D3 需要的核对时间戳 |
| 3 | **跑今天的整套流程** | 8 个号：签到 → 广告 → 聚合对账（今天已核过且账本没变就跳过逐章）→ 买 → 每买一章立刻更新「下一章」 | 目录扫描彻底不在这一趟里 |

三步都可以重复点、可以从中间被打断后接着点（都幂等）。

---

## 6. 测试与验收

### 6.1 单元测试（`app/src/test/java/com/example/blb/auto/`）

- `RemoteLedgerRepairTest`：3.3 的 ①~⑦ **逐条反例**，每条都断言"不删、转存疑"（`found=false` 不删、章数读不到不删、明细截断不删、15 分钟内不删、`OWNED` 不删、别的号名下不删、章号+标题不能唯一对应不删）。
- `CatalogFreshnessTest`：空账本 → 停并给出"先点同步目录"；过期 → 警告文案；未过期 → 不警告。
- `SubscribeRunNextChapterTest`：买完一章后重算的"下一章"等于 `findNextUnownedChapterFrom`；构造"重算值 < 刚买章号" → 必须停下并报告。
- `RunReportTest`（已有，增补）：目录/清单/存疑三种结论文案。
- `AuditSkipTest`：D3 四条跳过条件的真值表。

### 6.2 Instrumented（`app/src/androidTest/`）

- `AuditDaoTest`：`account_novel_audit` 的 marker 比较；`ledger_audit` 写入 → 撤销 → purchase 正确重建。
- `SubscriptionDaoTest`（已有，增补）：删除事务的快照校验（章节表/付费行被改过 → 回滚）。

### 6.3 真机验收（按用户的日常顺序走）

1. **覆盖安装**（不清数据）：能打开，v5→v6 迁移成功，老账本、老账号都在。
2. **同步目录**：日志出现"目录 612 章（新登记 N）"；订阅页摘要变成"今天 HH:MM 扫过 · 612 章"。
3. **核对订阅清单**：8 个号逐个出现"XX 在《…》上：…"；结束弹结论（补记 N / 修正 N / 未能核对 N）。
4. **整套流程**：日志里**不再出现**整本目录扫描（只有"目录：今天 09:12 扫过"）；每次买完紧跟一行"下一章：第 N 章"。
5. **补记验证**：手工删掉某号一条真记录 → 跑「核对订阅清单」→ 必须报"账本漏记 1 章"并补回，**不删任何东西**。
6. **删除验证**：造一条假记录（标记花过券、日期昨天、明细里没有）→ 跑核对 → 删除，并在「存疑与已修正」页看到，可撤销。
7. **不删验证（关键）**：把目标书书名临时改成一个对不上的名字 → 跑核对 → 必须只报"清单里没有这本书那一行，这一次没能核对"，**一条记录都不许删**。

### 6.4 反例清单（实现时逐条自问）

- 清单页停在「漫画」tab 会怎样？
- 明细页滚到 100 屏还没到底会怎样？
- 第 71 章那种"71留宿之夜"粘连标题还会不会误判？（已有 `NO_GLUED_TO_TITLE`）
- 8 个号里有 1 个登录失败，剩下 7 个还跑不跑？（跑，如实报告）
- 跑到第 5 个号时用户按了「停止」？（已核对的时间戳保留，下次跳过，不重复登录）

---

## 7. 风险、回滚、工作量

### 7.1 风险与对策

| 风险 | 对策 |
|---|---|
| 删除误杀（最严重） | 3.3 的七条前置条件 + 删前定向复核 + 留痕可撤销；`found=false` 永不删 |
| 迁移失败打不开 App | 改动前先用订阅页「导出 CSV」备份账本；回滚 = 重装 + 导入 |
| 跳过逐章导致漏掉一次真实不一致 | 聚合层仍每号每趟都核；每次跳过都写日志；设置里可强制"每次都逐章核对" |
| 目录过期没扫 → 漏订新章 | 流程开始时提示（不静默）+ 结论里写"目录是 X 小时前扫的" |
| 删除后那一章被重买（花冤枉钱） | 删除后立刻在同一趟里把「下一章」退回并显示；结论点名"第 M 章退回待订" |

### 7.2 实施顺序与提交切分

| 阶段 | 内容 | 可否单独验证 |
|---|---|---|
| A | DB v6 + 实体/DAO + 下一章现算的日志钩子（不改行为） | ✅ 覆盖安装验证迁移 |
| B | `CatalogQueue` + 同步目录按钮 + 免费章按 8 个号补 | ✅ 只扫不买，随时可验证 |
| C | `RemoteLedgerRepair`（纯函数 + 单测）→ 再接进 `SubscriptionAuditQueue` | ✅ 先落纯函数和测试 |
| D | `SubscribeRun` 去掉每号扫描 + 目录护栏 + 对账减负 + 买后更新下一章 | ✅ 用日志验证"不再扫目录" |
| E | 界面三个按钮 + 摘要 + 存疑页 + 文案 | ✅ |
| F | 真机按 6.3 走一遍 | ✅ |

**建议顺序**：A → B → D（先把"流程里不再扫目录"跑通，收益立刻可见）→ C（删除最危险，放在有测试之后）→ E → F。

---

## 8. 与旧行为的差异对照（给实现者核对）

| 事项 | 现在 | 改造后 |
|---|---|---|
| 目录扫描 | `SubscribeRun.oneAccount` 每号一次；失败 → 整趟 `MONEY_UNCLEAR` | 独立按钮一次；失败只影响这次扫描，不动已有账本 |
| 免费章回填 | 只补当前登录号 | 补所有启用账号（统计口径修正） |
| 章节表为空 | 静默"没有待订阅的章节" | 明确停下并指向「同步目录」 |
| 逐章明细对账 | 每个号每趟都读整页 | 今天已核过且账本没变 → 跳过（可强制全核） |
| 清单比账本少 | 报告并**整趟停**（不删） | 有证据 → 删除并留痕；证据不足 → 存疑、停下、界面可处理 |
| 清单比账本多 | 严格校验后事务补记（已有） | 不变 |
| 「下一章」 | 账本层面已实时 | 每买完一章写进日志；越界（变小）即停并报告 |
