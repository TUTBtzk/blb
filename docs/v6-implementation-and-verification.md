# 目录同步与订阅核对：实现及验证记录

本次改动基于开始工作时的未提交源码继续实现，没有还原或覆盖已有真机修复。

## 已确认的边界

- 一章最多一个付费账号、删除必须有证据且可撤销，适用于自动化、人工编辑、CSV 导入和级联删除全部入口。
- 任何启用账号缺少目标书的有效订阅证据，都阻止整本书继续购买；清单没有书名也不作“首次购买”的例外。
- 两次独立读屏均在事务外完成。真正修账使用短事务，再核对目录、该号及全书购买快照；事务里不等待屏幕。
- 同时漏记和多记、章数相等但章节不同，保留原有严格恢复护栏，转为存疑，不过滤矛盾记录后强行补删。
- 删除条件中的“其他号也有记录”包含免费 OWNED；付费唯一性则只看两种实际花费列大于零。

## 与原设计材料的差异

1. 原设计文档提到人工“确认删除并重订”。本次按提示词的七项条件与已确认的全入口约束执行，不提供绕过证据的强制删除入口；提供有留痕的撤销。
2. 原提示词将删前读屏列在事务步骤中。本次按确认后的方案采用“外部两次读屏，内部重新验证快照”，避免长期锁住数据库。
3. `ledger_audit.id` 的迁移语句显式写入 `NOT NULL`，与 Room 为 Java `long` 主键生成的列定义保持一致。

## 阶段 A：代码及本地验证通过

改了：

- `AccountNovelAudit`、`LedgerAudit`、`AuditDao`：独立存放核对进度及留痕。
- `Novel`、`AppDatabase`、`Db`：升级到 Room v6，只加列和新表，注册完整迁移链。
- `SubscribeRun`：买入记账后立即重算并记录下一章，包括购买成功后收尾异常的路径。
- `Migration5To6Test`：独立构造 v5 数据库，检查迁移、旧账号与旧购买、新建 v6 的列定义。
- `SubscribeRunNextChapterTest`：锁定下一章的可见文案和倒退判据。

验证：`testDebugUnitTest assembleDebug compileDebugAndroidTestJavaWithJavac`。

- 实际输出：`BUILD SUCCESSFUL in 58s`；47 项任务，9 项执行。
- JVM 测试：501 项，失败 0，错误 0。
- 设备端测试仅编译通过，未连接设备运行。
- 日志：`.gradle/v6-stage-A-build.log`；阶段源码：`.gradle/v6-stage-A/app/src`。

现象：买后日志钩子和迁移检查已落实；覆盖安装及旧账本仍在的真机现象尚未验证。

遗留：真机覆盖安装、设备测试、阶段提交。

## 阶段 B：代码及本地验证通过

改了：

- `CatalogQueue`、`CatalogStatus`：使用当前登录账号独立扫描，记录目录时间和章数，结束返回首页。
- `CatalogSync`：目录重排、新登记、免费章给所有启用账号补记、扫描时间在一个事务内提交。
- `Db`、`LedgerEdits`：沿用现有单线程执行器，队列写账保留运行占用。
- 服务、结论、订阅页：增加“同步目录”入口与常驻摘要，忙碌时禁用。

验证：`testDebugUnitTest assembleDebug`。

- 实际输出：`BUILD SUCCESSFUL in 50s`；40 项任务，16 项执行。
- JVM 测试：505 项，失败 0，错误 0。
- 日志：`.gradle/v6-stage-B-build.log`；阶段源码：`.gradle/v6-stage-B/app/src`。

现象：已实现“目录 N 章（新登记 M）”以及扫描时间摘要；尚未在手机上点击验证。

遗留：真机只读扫描、阶段提交。阶段 B 的中间状态仍保留旧的每账号扫描，待阶段 D 移除。

## 阶段 C：代码及本地验证通过

改了：

- `SubscribedDetail`：完整性必须有同一列表容器的总项数、从顶部到底部的连续覆盖和逐条解析证据；空树、停住的一屏或滚动失败都不算扫完。
- `RemoteLedgerRepair`：七项删除条件逐项检查，拒绝未找到书、近期购买、归属冲突、目录映射不唯一和混合错账；两次独立证据完全一致才给出删除计划。
- `AuditDao`、`LedgerAuditPayload`：短事务内复核目录及购买快照，删前保存完整原始记录，支持严格、幂等撤销。
- `SubscriptionAuditQueue`、`SubscriptionAuditPolicy`：逐号核对，漏记复用原有恢复路径，火券或账本写入不明时整趟停止。
- `LedgerWritePolicy`、`SubscriptionDao`、`AccountDao`、人工编辑及 CSV 导入入口：拒绝重复付费归属及无证据删账，修改事实时同步使旧核对结果失效。
- 服务、订阅页、详情页：增加核对入口、按书保存的常驻摘要、存疑与修正记录、撤销按钮。

验证：`testDebugUnitTest assembleDebug compileDebugAndroidTestJavaWithJavac`。

- 实际输出：`BUILD SUCCESSFUL in 53s`；47 项任务，16 项执行。
- JVM 测试：593 项，失败 0，错误 0，跳过 0。
- 设备端测试仅编译通过，未运行迁移、事务及撤销测试。
- 日志：`.gradle/v6-stage-C-build.log`；阶段源码：`.gradle/v6-stage-C/app/src`。

现象：纯判据已验证“清单没有书名不删账”、不完整明细不删账、两次证据不一致不删账；尚未在手机上核对八个账号。

遗留：三次真实核对场景、设备事务验收、阶段提交。

兼容性说明：导入必须显式提供可解析的火券、代券金额。旧七列 CSV 缺少代券金额会被拒绝，不再把未知支出补成 0；两种金额齐全时仍兼容原日期格式。

## 阶段 D：代码及本地验证通过

改了：

- `SubscribeRun`：删除每账号整本扫描；聚合行照常读取，花券前要求逐章核实，每一章购买前后都从数据库重新找下一章；归属不明或进度倒退时整本停止。
- `DailyQueue`、`SubscribeQueue`、`CatalogQueue`：空目录在启动预检中直接中止；默认只显示已有目录信息；用户开启过期自动同步时只用首号补扫一次，扫描或归位失败均整趟停止。
- `CatalogStatus`、`Prefs`、设置页：默认 24 小时时效，自动补扫及每次逐章核对默认关闭；过期信息同时写入日志与结论。
- `CatalogSync`、`SubscriptionAuditPolicy`：目录结构变更会使旧核对证明失效；任何启用账号缺少今天的完整证明，都阻止整本书继续购买。未改动目录的重扫保留已有证明。
- `RunReport`：每日流程与只订阅结论增加目录、下一章信息；队列异常收尾仍保留已发生的购买数量与支出。
- 目录集成回归与新增纯函数测试：独立扫描、空目录、24 小时边界、未知时间、首号限制、未知归属、跨日证明及目录故障全局停止。

验证：`testDebugUnitTest assembleDebug compileDebugAndroidTestJavaWithJavac`。

- 首轮 629 项测试通过；复核修正目录归位超时和失败日志保存异常的收尾路径后，重新运行全部检查。
- 最终实际输出：`BUILD SUCCESSFUL in 43s`；47 项任务，8 项执行。
- JVM 测试：633 项，54 个测试类，失败 0，错误 0，跳过 0。
- 日志：`.gradle/v6-stage-D-build.log`；阶段源码：`.gradle/v6-stage-D/app/src`。
- 与初始源码快照逐字节比较：`SubscribeTask`、`ChapterRowState`、`CheckInTask`、`AdWatchTask` 均未改动。

现象：纯判据已验证默认不扫描、空目录停机、未知归属不跨章以及任一启用账号缺证时整书阻止购买。手机上的真实队列日志尚未取得。

遗留：每日真实运行、阶段提交。

## 阶段 E：代码及本地验证通过

改了：

- `SubscriptionFragment`：接入整套流程，按钮显示真实启用账号数；四个运行入口和相关编辑操作统一跟随忙碌及预检状态禁用；目录为空与下一章均按本次读库结果显示，旧页面回调不能覆盖新目标。
- `fragment_subscription.xml`：主区竖排“同步目录 / 核对订阅清单 / 跑今天的整套流程”，每颗按钮下面常驻摘要；“只跑订阅”移至下方次要操作横滚区。
- `strings.xml`：清除开始订阅后默认扫描目录的旧承诺，明确逐号登录、代券花费及中途不要操作屏幕；未编写未经实测的耗时。

验证：`testDebugUnitTest assembleDebug compileDebugAndroidTestJavaWithJavac`，另做 XML、资源引用、旧文案及差异空白检查。

- 实际输出：`BUILD SUCCESSFUL in 49s`；47 项任务，15 项执行。
- JVM 测试：633 项，54 个测试类，失败 0，错误 0，跳过 0。
- 日志：`.gradle/v6-stage-E-build.log`；阶段源码：`.gradle/v6-stage-E/app/src`。
- 最终 APK：`.gradle/verification-build/app/outputs/apk/debug/app-debug.apk`，9,578,003 字节。

现象：布局结构与资源检查确认三个主入口依序竖排、各带摘要、支持多行文字。尚未取得真机截图，也没有填写实际耗时。

遗留：真机显示、实际耗时和阶段提交。

## 迁移定义的补充静态核验

将独立 v5 测试建表声明加上 `MIGRATION_5_6` 的四条语句，与本轮 Room v6 生成源码逐项比较，7 张业务表、60 列、6 个显式索引一致，含列名、类型、`NOT NULL`、`DEFAULT`、主键、外键和索引定义。迁移仅增加两列和两张表，无旧表删除或重建。

结果：`.gradle/migration-crosscheck/static-result.json`，状态 `STATIC_SCHEMA_MATCH`；复核脚本：`.gradle/migration-crosscheck/check-static-schema.ps1`。

这只是声明一致性核验。本机未找到可用 Python/SQLite 运行时，因此没有执行桌面 SQLite 迁移、`PRAGMA` 比较或旧行保留检查；真机 Room 打开和覆盖安装仍未验收。

## 阶段 F：真机验收尚未执行

连接预检再次确认：ADB 在创建 `\\.android` 时因 `Permission denied` 退出，连版本读取也未能完成，无法枚举设备。本次未覆盖安装、清数据、修改真实账本或执行购买。

待连接恢复后的验收顺序：

1. 不清数据覆盖安装，核实旧账号、目标小说和购买记录仍在；运行迁移和账本事务设备测试。
2. 同步目录：核对扫描章数、新登记、重排、免费补记及常驻时间，确认无购买。
3. 核对清单：核实八个启用账号逐号报告及最终结论。
4. 漏记与书名不匹配场景：应分别补回、保持零删除；故意制造漏记的验收数据须与生产账本隔离，不能绕过已确认的全入口删除护栏。删除路径另核实两次完整证据与可撤销留痕。
5. 跑整套流程：默认无整本目录扫描，每次真实购买后立刻显示下一章；实测后再填写实际耗时。

## 构建环境

实际终端是 PowerShell，使用 Git Bash 启动项目已有的 Gradle 9.5.0。直接运行包装器受到全局缓存锁写权限限制，因此沿用相同缓存发行版，以工作区内独立的 Gradle 用户目录、项目缓存及构建输出运行指定任务；没有下载依赖。

APK 输出：`.gradle/verification-build/app/outputs/apk/debug/app-debug.apk`。

## 当前外部阻塞

- Git 分支写入请求被自动审批拒绝，原因是审批服务返回 `404 Not Found`，提示当前 API 不支持 `codex-auto-review`。因此尚未创建分支或完成阶段提交，没有通过其他 Git 写入方式绕过；A 至 E 的源码快照及各阶段构建日志已分别保留。
- ADB 无法启动：报告 `Cannot mkdir '\\.android': Permission denied`；现有 5037 端口也未监听。尚未确认设备序列号，没有安装、清数据或执行购买。
- 迁移及修账设备测试只使用专用测试数据库，未碰生产 `blb.db`。
