package com.example.blb.auto;

import com.example.blb.data.Chapter;
import com.example.blb.data.Novel;
import com.example.blb.data.Purchase;
import com.example.blb.data.SubscriptionDao;
import com.example.blb.util.Texts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 把「选择章节」页扫到的东西写进账本：整本书的章节顺序，以及<b>免费章</b>按当前账号回填的
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
 * 所以「作者更新了新章，App 的记录也跟着更新」靠的是每一趟都重扫一遍目录，不是手工登记。
 */
public final class CatalogSync {

    /** 台账里手工登记的标题可能只有「久违的笑」，界面上是「11   久违的笑」。 */
    private static final Pattern LEADING_NO = Pattern.compile("^\\d{1,5}\\s*");

    public static final class Report {
        /** 账本写成了吗。false = 一个字都没写。 */
        public boolean ok;
        /** 给日志看的一句话。 */
        public String message;
        /** 界面上一共几章。 */
        public int scanned;
        /** 这次新登记进账本的章数。 */
        public int added;
        /** 免费章行数（没有锁）—— 这些才可以按当前账号补进账本。 */
        public int free;
        /** 这个号还能花券买的章数（付费、还有勾选圈）。 */
        public int buyable;
        /** 这次新补记的「免费章已拥有」记录数。 */
        public int backfilled;
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
        public final List<String> foreign = new ArrayList<>();
        /** 跳过的无标号行（卷标题这类），原样带出来写日志。 */
        public final List<String> skipped = new ArrayList<>();
        /** 作者动过目录、这次按界面重排了章号时，写这一句；没动过就是 null。 */
        public String realigned;
        /** 扫描时的第一行，扫完靠它核对「真的回到顶部了」。 */
        public String firstRowTitle;
    }

    private CatalogSync() {
    }

    /**
     * 进选择章节页 → 扫完 → 写账本 → 把列表滚回顶部（后面买章要从顶上往下找）。
     *
     * @param accountId 现在登录的是哪个号；<b>免费章</b>会记成它的
     *                  {@link Purchase#SRC_OWNED}。付费章一个字都不写。
     */
    public static Report sync(StepRunner r, SubscriptionDao subs, Novel novel, long accountId)
            throws StepRunner.StepFailure {
        Report out = new Report();
        SubscribeTask.openChapterPicker(r, novel);
        CatalogScanner.Result scan = rescanIfGap(r, CatalogScanner.scan(r));

        out.scanned = scan.chapters.size();
        out.free = scan.freeCount();
        out.buyable = scan.buyableCount();
        out.skipped.addAll(scan.skipped);
        if (!scan.chapters.isEmpty()) out.firstRowTitle = scan.chapters.get(0).title;

        if (!scan.trustworthy()) {
            out.message = "目录没扫干净，这轮不写账本也不买："
                    + (scan.chapters.isEmpty() ? "一行章节都没读到"
                    : scan.truncated ? "翻了 " + scan.scrolls + " 屏还没到底（这本书太长）"
                            : scan.gapNote);
            CatalogScanner.toTop(r, out.firstRowTitle, scan.scrolls);
            return out;
        }

        if (!realign(subs, novel, scan, out)) {
            CatalogScanner.toTop(r, out.firstRowTitle, scan.scrolls);
            return out;
        }
        write(subs, novel, accountId, scan, out);
        out.ok = true;
        out.message = "目录 " + out.scanned + " 章（新登记 " + out.added + "）"
                + "，免费 " + out.free + " 章（新补记 " + out.backfilled + "）"
                + "，这个号还能买 " + out.buyable + " 章"
                + (out.foreign.isEmpty() ? ""
                : "，本机已下载但不知道是谁买的 " + out.foreign.size() + " 章")
                + (out.skipped.isEmpty() ? "" : "，跳过 " + out.skipped.size() + " 行卷标题");
        CatalogScanner.toTop(r, out.firstRowTitle, scan.scrolls);
        return out;
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
    private static CatalogScanner.Result rescanIfGap(StepRunner r, CatalogScanner.Result first)
            throws StepRunner.StepFailure {
        if (first.gapNote == null || first.chapters.isEmpty()) return first;
        r.log("  目录第一遍有缺口（" + first.gapNote + "），回到顶部重扫一遍");
        CatalogScanner.toTop(r, first.chapters.get(0).title, first.scrolls);
        CatalogScanner.Result second = CatalogScanner.scan(r);
        if (second.gapNote == null) return second;
        // 两遍都有缺口：取行数多的那一份报给用户，缺口位置更接近真相。
        return second.chapters.size() >= first.chapters.size() ? second : first;
    }

    /**
     * 落库：章节按位置登记；<b>只有免费章</b>按当前账号回填「已拥有」。
     *
     * <p>为什么只认免费章：「已下载」是本机的下载状态、8 个号共用（皓平买完第49章下载到本机，
     * 换五杯半雪碧登录那一行照样写着「已下载」）。2026-08-25 用户核对订阅清单发现
     * 「第49章实际上只有皓平购买了，却显示已订阅：皓平、五杯半雪碧」—— 就是这里按「已下载」
     * 回填出来的第二个订阅者。它直接违反「每一章只能有一个账号订阅」，还会让这一章
     * 永远算成「有人有了」而漏订。所以付费章的归属只认真实购买记录，界面上读不出来。
     *
     * <p><b>「有没有归属」跨号问，「是不是我的」才问当前号</b>：同一个「已下载」标记，
     * 8 个号看到的是同一份文件。2026-09-03 那趟把这两件事混成了一个 {@code recorded}，
     * 于是别的号买过的章被当成「没有归属」报了 8 遍（见 {@link Report#foreign}）。
     */
    private static void write(SubscriptionDao subs, Novel novel, long accountId,
                              CatalogScanner.Result scan, Report out) {
        // 全书跨号的归属集合，一次取完：8 个号里任何一个买过，这一章就是有主的。
        Set<Long> ownedByAnyone = new HashSet<>(subs.realPurchasedChapterIds(novel.id));
        for (int i = 0; i < scan.chapters.size(); i++) {
            CatalogScanner.Row row = scan.chapters.get(i);
            int no = i + 1; // 章号＝界面上的位置，不是行首那个标号（分卷会各自从 1 重排）
            Chapter before = subs.chapterByNo(novel.id, no);
            Chapter chapter = subs.ensureChapter(novel.id, no, row.title, 0);
            if (chapter == null) continue;
            if (before == null) {
                out.added++;
            } else if (!row.title.equals(chapter.title)) {
                // 只在「兼容但不完全一样」时才发生（台账里是手工登记的短标题），
                // 顺手换成界面上的全文，之后按标题精确定位那一行才找得到。
                chapter.title = row.title;
                subs.updateChapter(chapter);
            }
            if (accountId <= 0) continue;
            boolean mine = subs.countRealPurchase(accountId, chapter.id) > 0;
            switch (verdict(row.state, mine, ownedByAnyone.contains(chapter.id))) {
                case BACKFILL_FREE:
                    subs.upsertPurchase(Purchase.of(accountId, chapter.id, 0, Purchase.SRC_OWNED));
                    out.backfilled++;
                    break;
                case FOREIGN:
                    out.foreign.add("第" + no + "章");
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
        String a = ledgerTitle.trim();
        String b = scanned == null ? "" : scanned.trim();
        if (a.equals(b)) return true;
        return strip(a).equals(strip(b));
    }

    private static String strip(String s) {
        return LEADING_NO.matcher(s).replaceFirst("").trim();
    }

    private static String text(String s) {
        return Texts.isBlank(s) ? "（没登记标题）" : s.trim();
    }
}
