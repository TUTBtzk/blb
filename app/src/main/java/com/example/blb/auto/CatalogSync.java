package com.example.blb.auto;

import com.example.blb.data.Chapter;
import com.example.blb.data.Account;
import com.example.blb.data.AppDatabase;
import com.example.blb.data.Db;
import com.example.blb.data.LedgerWritePolicy;
import com.example.blb.data.Novel;
import com.example.blb.data.Purchase;
import com.example.blb.data.SubscriptionDao;
import com.example.blb.util.Texts;
import com.example.blb.ui.LedgerEdits;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 把「选择章节」页扫到的东西写进账本：整本书的章节顺序，以及<b>免费章</b>按所有启用账号回填的
 * 「已拥有」记录。付费章归谁只认真实购买记录 —— 界面上读不出来（见 {@link ChapterRowState}）。
 *
 * <p>没有这一步，自动订阅是空转的：{@code chapter} 表空着，
 * {@link SubscriptionDao#findUnownedChaptersFrom} 什么都取不到，队列每轮只打一句
 * 「没有待订阅的章节」。612 章手工登记不可能，所以章节表只能对着真实界面自己长出来。
 *
 * <p><b>两道护栏，任一不过就一章都不买</b>：
 * <ol>
 *   <li>扫描本身必须可信（{@link CatalogScanner.Result#trustworthy()}）—— 没扫到底、
 *       或者行首标号链上有缺口，都说明翻页漏了内容，这样的账本会让「下一章该买哪一章」算错；</li>
 *   <li>台账里第 k 章的标题必须和这次扫到的第 k 行对得上。对不上的时候先<b>按标题重新对号</b>
 *       （{@link CatalogAlign}）—— 这本书在连载，作者往中间插一章，后面每一章的位置就整体后移，
 *       账本里的章号必须跟着搬。搬得动就搬完接着买；搬不动（买过的那一章在界面上找不到了、
 *       同名行认不出是哪一行、顺序被打乱）才停下如实报告。</li>
 * </ol>
 *
 * <p>加在<b>末尾</b>的新章不需要重排：位置没动，扫一遍就由 {@link #write} 登记进账本 ——
 * 所以作者新增章节由订阅页的「同步目录」登记，不能再要求手工逐章输入。
 */
public final class CatalogSync {

    public static final class Report {
        /** 账本写成了吗。false = 一个字都没写。 */
        public boolean ok;
        /** 给日志看的一句话。 */
        public String message;
        /** 界面上一共几章。 */
        public int scanned = -1;
        /** 这次新登记进账本的章数。 */
        public int added;
        /** 免费章行数（没有锁），这份证据对全部启用账号成立。 */
        public int free = -1;
        /** 这个号还能花券买的章数（付费、还有勾选圈）。 */
        public int buyable = -1;
        /** 这次新补记的「免费章已拥有」记录数。 */
        public int backfilled;
        public int accounts;
        public int realignedCount;
        /** 账本说这个号买过、界面上却还能勾选要花券的章 —— 对不上，必须让人知道。 */
        public final List<String> contradictions = new ArrayList<>();
        /**
         * 付费、本机已下载、<b>8 个号的账本里都没有归属</b>的章 —— 真的说不清是谁买的。
         *
         * <p>「已下载」是本机状态、8 个号共用，所以<b>这一条必须跨号问</b>：皓平买的章在
         * 另外 7 个号登录时照样写着「已下载」。2026-09-03 那趟只问了当前号，于是同一批章
         * （第48、49、50…）在每个号身上各被报一次「没有归属」—— 8 条备注全是误报，
         * 而同一趟的逐章对账（{@link SubscribedDetail}）明明每个号都说「都对上了」。
         *
         * <p>真落到这个名单里的章既不能回填成当前号拥有（2026-08-25 第49章就是那样多出
         * 一个订阅者的），也买不了（没有勾选圈）。只能报出来让人对着
         * 「我的 → 代券 → 订阅清单」核对是谁买的。
         */
        // 保留稳定的章节 id，供核账补回购买记录后重新核实，避免报告已经修复的漏账。
        public final Map<Long, String> foreign = new LinkedHashMap<>();
        /** 已核实的分节行，原样带出；无标号或 heading 本身均不能证明这是分节。 */
        public final List<String> skipped = new ArrayList<>();
        public final List<String> unresolved = new ArrayList<>();
        /** 作者动过目录、这次按界面重排了章号时，写这一句；没动过就是 null。 */
        public String realigned;
        /** 扫描时的第一行，扫完靠它核对「真的回到顶部了」。 */
        public String firstRowTitle;
    }

    private CatalogSync() {
    }

    /**
     * 普通目录核实角色 → 选择章节页逐行对照并读状态 → 确认回顶 → 写账本。
     *
     * @param accountId 当前账号只用于提示付费记录矛盾；免费章对所有启用账号补记 OWNED。
     */
    public static Report sync(StepRunner r, SubscriptionDao subs, Novel novel, long accountId)
            throws StepRunner.StepFailure {
        Report out = new Report();
        // 2026-09-14 两份真机 dump 证实：下载页卷名与免费番外同形，普通目录的 layoutRoot 才能区分。
        // 两页都从顶部完整读到底，并逐位置对照；不在扫描时勾选，也不按书名硬编码卷标题。
        SubscribeTask.openCatalogDirectory(r, novel);
        CatalogScanner.toTop(r, null, CatalogScanner.DEFAULT_MAX_SCROLLS);
        CatalogScanner.Result directory = rescanIfGap(r, CatalogScanner.scanDirectory(r), null);
        logScanEvidence(r, "普通目录", directory);
        if (!directory.trustworthy()) {
            out.scanned = directory.chapters.size();
            out.skipped.addAll(directory.skipped);
            out.unresolved.addAll(directory.unresolved);
            out.firstRowTitle = firstTitle(directory);
            out.message = "普通目录没扫干净，这轮不写账本也不买："
                    + scanProblem(directory) + classificationNote(out);
            restoreAfterFailedScan(r, out.firstRowTitle, directory.scrolls);
            return out;
        }
        SubscribeTask.openChapterPicker(r, novel);
        CatalogScanner.toTop(r, null, CatalogScanner.DEFAULT_MAX_SCROLLS);
        CatalogScanner.Result scan = rescanIfGap(r, CatalogScanner.scan(r, directory), directory);

        out.scanned = scan.chapters.size();
        out.free = scan.freeCount();
        out.buyable = scan.buyableCount();
        out.skipped.addAll(scan.skipped);
        out.unresolved.addAll(scan.unresolved);
        out.firstRowTitle = firstTitle(scan);

        // 2026-09-14 只报「跳过12行」仍无法核对番外是否被丢掉，类别和原文必须一起留下。
        logScanEvidence(r, "选择章节", scan);

        if (!scan.trustworthy()) {
            out.message = "目录没扫干净，这轮不写账本也不买："
                    + scanProblem(scan) + classificationNote(out);
            restoreAfterFailedScan(r, out.firstRowTitle, scan.scrolls);
            return out;
        }

        // 2026-09-14 两页对照加严了回顶证明；先归位再提交，不能写完账才抛错而丢掉实际补记报告。
        CatalogScanner.toTop(r, out.firstRowTitle, scan.scrolls);
        List<Long> freeAccounts = new ArrayList<>();
        if (r.context() == null) {
            // 离线回归只提供内存账本和当前账号；生产入口由 Context 取得全部启用账号。
            if (accountId > 0) freeAccounts.add(accountId);
            if (!realign(subs, novel, scan, out)) return out;
            write(subs, novel, accountId, freeAccounts, scan, out);
            out.ok = true;
        } else {
            AppDatabase db = Db.get(r.context());
            try {
                LedgerEdits.duringRun(r.host(), () -> db.runInTransaction(() -> {
                    List<Chapter> before = subs.loadChapters(novel.id);
                    for (Account account : db.accountDao().loadEnabled()) freeAccounts.add(account.id);
                    // 重排和新增必须一起成功；以前搬完号再失败会留下只改了一半的目录。
                    if (!realign(subs, novel, scan, out)) throw new Rejected(out.message);
                    write(subs, novel, accountId, freeAccounts, scan, out);
                    if (!LedgerWritePolicy.sameChapters(before, subs.loadChapters(novel.id))) {
                        // 作者插章会改变远端明细的本地映射；结构变过就不能复用旧的逐章凭证。
                        db.auditDao().invalidateNovel(novel.id);
                    }
                    long scannedAt = System.currentTimeMillis();
                    if (db.auditDao().setCatalogScan(novel.id, scannedAt, out.scanned) != 1) {
                        throw new Rejected("目标小说已变化，这次目录未写入");
                    }
                    novel.catalogScannedAt = scannedAt;
                    novel.catalogChapterCount = out.scanned;
                    return true;
                }));
                out.ok = true;
            } catch (Rejected failure) {
                out.message = failure.getMessage();
                out.added = out.backfilled = out.realignedCount = 0;
                return out;
            }
        }
        out.accounts = freeAccounts.size();
        out.message = "目录 " + out.scanned + " 章（新登记 " + out.added + "）"
                + "，免费 " + out.free + " 章（补记 " + out.backfilled
                + " 条，按 " + out.accounts + " 个启用账号每章各一条）"
                + "，这个号还能买 " + out.buyable + " 章"
                + (out.foreign.isEmpty() ? ""
                : "，本机已下载但不知道是谁买的 " + out.foreign.size() + " 章")
                + classificationNote(out);
        return out;
    }

    private static final class Rejected extends RuntimeException {
        Rejected(String message) { super(message); }
    }

    private static String firstTitle(CatalogScanner.Result scan) {
        String title = scan.allRows.isEmpty() ? null : scan.allRows.get(0).title;
        return title == null || title.isEmpty() ? null : title;
    }

    private static void restoreAfterFailedScan(StepRunner r, String firstTitle, int scrolls)
            throws StepRunner.StepFailure {
        try {
            CatalogScanner.toTop(r, firstTitle, scrolls);
        } catch (StepRunner.StepFailure failure) {
            if (failure.kind == StepRunner.Kind.CANCELLED) throw failure;
            // 2026-09-15 回顶失败不能覆盖已经取得的坏行与扫描进度，否则又只剩一句笼统错误。
            r.log("不完整扫描后的回顶也未完成；保留原扫描结果：" + failure.getMessage());
        }
    }

    private static String scanProblem(CatalogScanner.Result scan) {
        return scan.stateConflict != null ? scan.stateConflict : scan.gapNote != null ? scan.gapNote
                : scan.truncated ? "翻了 " + scan.scrolls + " 屏仍未取得到底证据"
                : scan.chapters.isEmpty() ? "一行章节都没读到" : "存在不能确认身份的目录行";
    }

    private static void logScanEvidence(StepRunner r, String page, CatalogScanner.Result scan) {
        r.log(page + "扫描证据：已读章节=" + scan.chapters.size() + "，scrolls=" + scan.scrolls
                + "，endProbes=" + scan.endProbes + "，truncated=" + scan.truncated
                + "，gapNote=" + (scan.gapNote == null ? "无" : scan.gapNote)
                + "，状态冲突=" + (scan.stateConflict == null ? "无" : scan.stateConflict)
                + "，全部目录行=" + scan.allRows.size()
                + "，首行「" + (scan.allRows.isEmpty() ? "未读到" : scan.allRows.get(0).title)
                + "」，末行「" + (scan.allRows.isEmpty() ? "未读到" : scan.allRows.get(scan.allRows.size() - 1).title) + "」");
        r.log(page + "卷标题（已跳过）" + scan.skipped.size() + " 行：" + String.join("、", scan.skipped));
        r.log(page + "未确认目录行（请核对）" + scan.unresolved.size() + " 行："
                + String.join("、", scan.unresolved));
        for (int i = 0; i < Math.min(3, scan.rowProblems.size()); i++) {
            r.log(page + "未读全原因：" + scan.rowProblems.get(i));
        }
    }

    static String classificationNote(Report report) {
        return "；卷标题（已跳过）" + report.skipped.size() + " 行"
                + (report.unresolved.isEmpty() ? "" : "；未确认目录行（请核对）"
                + report.unresolved.size() + " 行：" + String.join("、", report.unresolved));
    }

    /**
     * 作者更新之后把账本对回界面。返回 false = 对不上又搬不了，这一轮一章都不买。
     *
     * <p>加在末尾的新章不走这里 —— 位置没动，下面的 {@link #write} 直接登记进去。
     * 这一步管的是<b>往中间插了章</b>或改了标题：位置整体后移，账本里的章号必须跟着搬，
     * 否则「第 68 章」照着买会买到界面上的第 69 行。搬号只动 {@code chapter.chapter_no}，
     * 购买记录挂在 {@code chapter.id} 上，一条都不动。
     */
    private static boolean realign(SubscriptionDao subs, Novel novel,
                                   CatalogScanner.Result scan, Report out) {
        CatalogAlign.Plan plan = CatalogAlign.plan(subs.loadChapters(novel.id), scan.chapters,
                novel.startFrom(), new HashSet<>(subs.realPurchasedChapterIds(novel.id)));
        if (plan.blocked != null) {
            out.message = "目录和账本对不上，这轮一章都不买：" + plan.blocked;
            return false;
        }
        if (plan.nothingToDo()) return true;

        subs.realign(plan.renumber, plan.dropIds, plan.newStartChapterNo, novel.id);
        if (plan.newStartChapterNo > 0) {
            // 这一轮手上这份 Novel 也得跟着改，否则「从第几章起买」还按旧位置算。
            novel.startChapterNo = plan.newStartChapterNo;
        }
        out.realigned = plan.describe();
        out.realignedCount = plan.renumber.size();

        // 搬完再核对一次「账本第 k 章就是界面第 k 行」。这一步本不该失败，
        // 失败说明搬号没落库（比如撞了唯一索引）—— 那就绝不能拿这份账本去买。
        String left = findDrift(subs.loadChapters(novel.id), scan.chapters);
        if (left != null) {
            out.message = "按界面重排章号之后账本还是对不上，这轮一章都不买：" + left;
            return false;
        }
        return true;
    }

    /**
     * 标号链上有缺口时回到顶部重扫一遍。
     *
     * <p>缺口的意思是「这一份目录不能用」，但绝大多数缺口是<b>读得太早</b>造成的、不是真漏行：
     * 2026-08-24 15:26 那趟第 4 个号刚按完「回到顶部」列表只铺出 4 行，翻一屏就跳到了标号 13，
     * 于是那个号一章都没买。第二遍照样有缺口才当真 —— 护栏不放松，只是不拿一次抖动当结论。
     */
    static CatalogScanner.Result rescanIfGap(StepRunner r, CatalogScanner.Result first,
                                                     CatalogScanner.Result directory)
            throws StepRunner.StepFailure {
        if (first.stateConflict != null) {
            r.log("目录存在已确认的状态冲突，不用重扫覆盖原证据：" + first.stateConflict);
            return first;
        }
        if (first.gapNote == null || first.allRows.isEmpty()) return first;
        r.log("  目录第一遍有缺口（" + first.gapNote + "），回到顶部重扫一遍");
        try {
            CatalogScanner.toTop(r, firstTitle(first), first.scrolls);
        } catch (StepRunner.StepFailure failure) {
            if (failure.kind == StepRunner.Kind.CANCELLED) throw failure;
            r.log("重扫前未能确认回顶，保留第一遍的不完整证据：" + failure.getMessage());
            return first;
        }
        CatalogScanner.Result second = first.directoryScanned
                ? CatalogScanner.scanDirectory(r) : CatalogScanner.scan(r, directory);
        if (second.stateConflict != null) return second;
        if (second.gapNote == null) return second;
        // 两遍都有缺口：取行数多的那一份报给用户，缺口位置更接近真相。
        return second.chapters.size() >= first.chapters.size() ? second : first;
    }

    /**
     * 免费章谁登录都能看，独立扫描不应让其余七个号的免费章统计永远为零。
     *
     * <p>为什么只认免费章：「已下载」是本机的下载状态、8 个号共用（皓平买完第49章下载到本机，
     * 换五杯半雪碧登录那一行照样写着「已下载」）。2026-08-25 用户核对订阅清单发现
     * 「第49章实际上只有皓平购买了，却显示已订阅：皓平、五杯半雪碧」—— 就是这里按「已下载」
     * 回填出来的第二个订阅者。2026-09-14 已确认服务器上的跨号购买可以是真实事实，
     * 但本机下载文件仍不能证明当前账号付过钱；付费归属必须来自购买记录或完整远端明细。
     *
     * <p><b>「有没有归属」跨号问，「是不是我的」才问当前号</b>：同一个「已下载」标记，
     * 8 个号看到的是同一份文件。2026-09-03 那趟把这两件事混成了一个 {@code recorded}，
     * 于是别的号买过的章被当成「没有归属」报了 8 遍（见 {@link Report#foreign}）。
     */
    private static void write(SubscriptionDao subs, Novel novel, long accountId,
                              List<Long> freeAccounts,
                              CatalogScanner.Result scan, Report out) {
        // 全书跨号的归属集合，一次取完：8 个号里任何一个买过，这一章就是有主的。
        Set<Long> ownedByAnyone = new HashSet<>(subs.realPurchasedChapterIds(novel.id));
        for (int i = 0; i < scan.chapters.size(); i++) {
            CatalogScanner.Row row = scan.chapters.get(i);
            int no = i + 1; // 章号＝界面上的位置，不是行首那个标号（分卷会各自从 1 重排）
            Chapter before = subs.chapterByNo(novel.id, no);
            Chapter chapter = subs.ensureChapter(novel.id, no, row.title, -1);
            if (chapter == null) throw new Rejected("第" + no + "章未能登记，目录写入已回滚");
            if (before == null) {
                out.added++;
            }
            if (!row.title.equals(chapter.title) || !Objects.equals(row.volumeTitle, chapter.volumeTitle)) {
                // 只在「兼容但不完全一样」时才发生（台账里是手工登记的短标题），
                // 顺手换成界面上的全文，之后按标题精确定位那一行才找得到。
                chapter.title = row.title;
                chapter.volumeTitle = row.volumeTitle;
                subs.updateChapter(chapter);
            }
            if (row.state.free()) {
                for (long freeAccount : freeAccounts) {
                    if (subs.countRealPurchase(freeAccount, chapter.id) == 0) {
                        subs.upsertPurchase(Purchase.of(freeAccount, chapter.id, 0, 0,
                                Purchase.SRC_OWNED));
                        out.backfilled++;
                    }
                }
                continue;
            }
            boolean mine = accountId > 0 && subs.countRealPurchase(accountId, chapter.id) > 0;
            switch (verdict(row.state, mine, ownedByAnyone.contains(chapter.id))) {
                case FOREIGN:
                    out.foreign.put(chapter.id, "第" + no + "章");
                    break;
                case CONTRADICTION:
                    out.contradictions.add("第" + no + "章");
                    break;
                default:
                    break;
            }
        }
    }

    /** 一行扫完之后该怎么记账。 */
    enum Verdict {
        /** 免费章、当前这个号还没记过 → 补一条 OWNED（一章 8 条，8 个号各一条）。 */
        BACKFILL_FREE,
        /** 什么都不做：已经记过了，或者是等着被买的付费章。 */
        NOTHING,
        /** 本机已下载、可 8 个号都查不到归属 → 报出来让人核对，账本一个字都不写。 */
        FOREIGN,
        /** 账本说这个号买过、界面上却还要花券才看得到 → 报出来让人核对。 */
        CONTRADICTION
    }

    /**
     * 「这一行该怎么记账」。<b>拆成纯函数是为了钉住那个误报</b>：「有没有归属」必须跨号问
     * （{@code ownedByAnyone}），「是不是我买的」才问当前号（{@code mine}）—— 同一个「已下载」
     * 标记 8 个号看到的是同一份文件。2026-09-03 那趟把两者混成一个变量，于是皓平买的那批章
     * 在另外 7 个号身上各被报了一次「没有归属」，8 条备注全是假的，而同一趟的逐章对账
     * 明明每个号都说「都对上了」。
     *
     * @param mine           当前这个号的账本里有这一章的记录（含免费章那条 OWNED）
     * @param ownedByAnyone  8 个号里任何一个的账本里有这一章的记录
     */
    static Verdict verdict(ChapterRowState state, boolean mine, boolean ownedByAnyone) {
        if (state.free()) return mine ? Verdict.NOTHING : Verdict.BACKFILL_FREE;
        if (state.deviceHasIt()) return ownedByAnyone ? Verdict.NOTHING : Verdict.FOREIGN;
        if (state.buyable() && mine) return Verdict.CONTRADICTION;
        return Verdict.NOTHING;
    }

    /**
     * 台账第 k 章和这次扫到的第 k 行对不对得上。<b>重排之后的复核</b>用它 ——
     * {@link CatalogAlign} 按名字把章号搬完，这一步确认真的落库了、账面和界面逐行一致。
     *
     * <p>台账比界面长（作者删章了）也算对不上 —— 那时候后面的位置同样是错的。
     * 台账标题为空、或者只差行首那个标号，都算对得上：号会变、名字不会（见 {@link #sameChapter}）。
     */
    static String findDrift(List<Chapter> ledger, List<CatalogScanner.Row> rows) {
        if (ledger == null || ledger.isEmpty()) return null;
        for (Chapter c : ledger) {
            if (c.chapterNo < 1 || c.chapterNo > rows.size()) {
                return "账本里有第" + c.chapterNo + "章，界面上一共只有 " + rows.size() + " 行";
            }
            String scanned = rows.get(c.chapterNo - 1).title;
            if (!sameChapter(c.title, scanned)) {
                return "账本里第" + c.chapterNo + "章是「" + text(c.title)
                        + "」，界面上第 " + c.chapterNo + " 行却是「" + scanned + "」";
            }
        }
        return null;
    }

    /**
     * 台账标题和界面行文本算不算同一章。
     *
     * <p><b>认名字、不认行首那个号</b>：行文本里印着标号（「67   周日工作」），作者往中间插一章，
     * 同一章的行文本就变成「68   周日工作」。号会变，名字不会 —— 所以两边都去掉行首标号再比。
     * 「位置整体后移」这件事由 {@link CatalogAlign} 按名字重新对号解决，不靠这里报错。
     */
    static boolean sameChapter(String ledgerTitle, String scanned) {
        if (Texts.isBlank(ledgerTitle)) return true; // 从没登记过标题，谈不上对不上
        String raw = ledgerTitle.replaceAll("[\\s\\u00a0\\u3000]+", " ").trim();
        String title = Texts.chapterTitle(scanned);
        // 手填的纯标题可能以数字开头；先按正文原样对，不能把正文数字当成又一个章号。
        return raw.equals(title) || Texts.chapterTitle(ledgerTitle).equals(title);
    }

    private static String text(String s) {
        return Texts.isBlank(s) ? "（没登记标题）" : s.trim();
    }
}
