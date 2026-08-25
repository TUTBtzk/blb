package com.example.blb.auto;

import android.content.Context;
import android.text.TextUtils;

import com.example.blb.data.Account;
import com.example.blb.data.AccountDao;
import com.example.blb.data.Chapter;
import com.example.blb.data.Novel;
import com.example.blb.data.Purchase;
import com.example.blb.data.SubscriptionDao;
import com.example.blb.util.Prefs;
import com.example.blb.util.Texts;

import java.util.List;
import java.util.Set;

/**
 * 「用当前登着的这个号买尽量多的章」这一段，签到队列的第 3 步和订阅页的
 * 「开始自动订阅」共用同一份实现 —— 两边各写一遍的时候，护栏总会有一边漏掉。
 *
 * <p>每个号进来先把目录整本扫一遍（{@link CatalogSync}）：章节表就是这样长出来的，
 * 而且顺带把<b>免费章</b>按当前账号写进账本。以前这两件事要手工做，
 * 于是自动订阅每轮只会打一句「没有待订阅的章节」，一个动作都没有。
 * 付费章归谁只认真实购买记录 —— 界面上那个「已下载」是本机状态、8 个号共用，读不出买家。
 *
 * <p>要买哪一章不看当前这个号，看账本：{@code findUnownedChaptersFrom} 给的是
 * <b>所有号合起来还没真买过</b>的最小章（干跑记录不算），所以各个号的券摊在不同章上，
 * 合起来把进度往前推，而不是几个号买同一章。{@code touched} 保证同一轮里一章只试一次。
 *
 * <p>停止条件是「这个号的代券不够 → 换下一个号」，不是章数。章数上限只是选择器错位时的安全阀。
 */
public final class SubscribeRun {

    /** 一轮里固定不变的参数。 */
    public static final class Plan {
        public final Novel novel;
        public final boolean dryRun;
        /** 安全阀：选择器一旦错位，最多错这么多章就停。真正的停止条件是代券花光。 */
        public final int maxChapters;
        public final int cap;
        public final long since;
        /** 保险丝：这一整趟最多真买几章，0＝不限。见 {@link Prefs#realBuyLimit}。 */
        public final int buyLimit;
        /**
         * 只用来在真买成一章的<b>当场</b>扣掉常驻授权的当日额度（{@link BuyMandate#noteBought}）。
         * 单测里是 null —— 判定逻辑本身不需要它。
         */
        private final Context app;

        public Plan(Novel novel, boolean dryRun, int maxChapters, int cap, long since,
                    int buyLimit) {
            this(null, novel, dryRun, maxChapters, cap, since, buyLimit);
        }

        private Plan(Context app, Novel novel, boolean dryRun, int maxChapters, int cap, long since,
                     int buyLimit) {
            this.app = app;
            this.novel = novel;
            this.dryRun = dryRun;
            this.maxChapters = maxChapters;
            this.cap = cap;
            this.since = since;
            this.buyLimit = buyLimit;
        }

        public static Plan from(Context context, Novel novel) {
            return new Plan(context.getApplicationContext(), novel, Prefs.isDryRun(context),
                    Prefs.maxChaptersPerRun(context), Prefs.dailySpendCap(context),
                    startOfToday(), Prefs.realBuyLimit(context));
        }

        /**
         * 这一趟真买的章数够了吗 —— 够了就整趟收工，剩下的号一个都不动。
         *
         * <p>只数真买（{@code tally.bought}）：干跑一分券没花，不该占用这条额度。
         */
        public boolean reachedBuyLimit(int bought) {
            return hasBuyLimit() && bought >= buyLimit;
        }

        /** 这一趟的保险丝装着吗（干跑那趟不算：干跑一分券都不花）。 */
        public boolean hasBuyLimit() {
            return !dryRun && buyLimit > 0;
        }

        /**
         * 每个号这一轮最多试多少章。
         *
         * <p>它只是<b>安全阀</b>（选择器一旦错位，最多错这么多章就停），真正的停止条件是
         * 「代券不够下一章 → 换号」。干跑以前只走 1 章，那样看不出「一个号连着订到余额不足」
         * 到底对不对；现在干跑走同样的章数，靠 {@link SubscribeRun#stopBecauseBroke} 在内存里
         * 模拟扣券来决定什么时候换号。
         */
        public int perAccount() {
            return Math.max(1, maxChapters);
        }

        public String describe() {
            return (dryRun ? "订阅走干跑（不花券，模拟扣券来决定什么时候换号）" : "订阅是真实购买")
                    + "：《" + novel.title + "》从第" + novel.startFrom() + "章起，"
                    + "一个号连着往下订到代券不够为止再换下一个号（安全阀：每号最多 "
                    + maxChapters + " 章）"
                    + (cap > 0 ? "，每号每日上限 " + cap + " 代券" : "")
                    + (hasBuyLimit()
                    ? "；保险丝：这一趟只准真买 " + buyLimit + " 章，买到就收工并把干跑重新打开" : "");
        }
    }

    /**
     * 取「集中订阅」的那本书。
     *
     * <p>正常靠 {@code novel.is_target} 这一位。但这一位在设置界面上要点三下才能设
     * （订阅页 → 选择目标小说 → 挑一本），而这个 App 的使用者按不动屏幕；
     * 2026-08-24 实测它<b>两次</b>莫名回到了 0（章节和 190 条账本记录都还在，只有这一位丢了），
     * 结果整轮订阅只打一句「还没有设定集中订阅的目标小说」就结束 —— 一个动作都没有。
     *
     * <p>所以：只登记了一本书的时候，那本就是目标，顺手把标志位补回去。
     * 登记了多本又没标目标才算真的说不清 —— 那种情况返回 null，绝不替他猜是哪本。
     */
    public static Novel resolveTarget(SubscriptionDao subs, StepRunner.Host host) {
        Novel target = subs.targetNovel();
        if (target != null) return target;
        List<Novel> all = subs.loadNovels();
        if (all.size() != 1) return null;
        Novel only = all.get(0);
        subs.markTarget(only.id);
        only.isTarget = true;
        if (host != null) {
            host.log("账本里只登记了《" + only.title + "》一本，但它没被标成集中订阅目标"
                    + "（这一位丢了）—— 按唯一一本处理，并把标志位补回去");
        }
        return only;
    }

    /**
     * 每趟干跑开始前，把上一趟留下的干跑痕迹抹掉。
     *
     * <p>干跑记录是攒着的：上一趟判给甲的第 49 章会一直留在账本里，这一趟判给乙时两条并存，
     * 账本看起来就像「同一章被两个号订了」—— 而「一章只归一个号」正是要靠账本核对的那条约束。
     * 清掉之后账本里的 DRY_RUN 只讲这一趟的事。真买那一趟一条都不动（真记录本来就该攒）。
     */
    public static void clearOldDryRuns(SubscriptionDao subs, Plan plan, StepRunner.Host host) {
        if (!plan.dryRun) return;
        int stale = subs.countDryRuns(plan.novel.id);
        if (stale <= 0) return;
        subs.clearDryRuns(plan.novel.id);
        if (host != null) {
            host.log("先抹掉上一趟留下的 " + stale + " 条干跑痕迹（只是判定记录，一分券都没花过）"
                    + "—— 这样这一趟的账本才能直接看出「一章只归一个号」");
        }
    }

    /**
     * 真买那一趟跑完，把干跑重新打开 —— 真买是一次性授权，用完自己回到安全档。
     *
     * <p>为什么非要自动回档：关掉干跑这件事在界面上是「设置页按一颗开关＋确认一次」，
     * 而这个 App 的使用者手指动不了 —— 他<b>关不掉</b>。忘了关就等于下一趟（包括每天定时那趟）
     * 继续花他的代券，而定时那趟没人在旁边看。
     *
     * <p>它现在<b>不看这一趟买成了几章</b>，只看「进来的时候干跑是关着的吗」，而且由调用方
     * 放在 {@code finally} 里 —— 三种以前会漏掉回档的情况都被这样堵上：
     * <ul>
     *   <li>{@code BUY_UNTIL_BROKE} 那种授权上限是 0（买到代券花光），永远到不了上限；</li>
     *   <li>一章都没买成的那趟（没买到就不回档＝开关一直开着）；</li>
     *   <li>跑之前就中止的那趟（没有启用的账号、selectors 缺 key、没设目标小说）。</li>
     * </ul>
     */
    public static void restoreDryRunAfterRealBuy(Context context, StepRunner.Host host) {
        if (Prefs.isDryRun(context)) return;   // 本来就是安全档（干跑那趟）
        Prefs.setDryRun(context, true);
        if (host != null) {
            host.log("真买那一趟到此结束 —— 已自动把干跑重新打开（真买是一次性授权）。"
                    + "下一趟想再真买，要重新授权一次。");
        }
    }

    /** 计数。备注直接写进调用方的列表，省一次搬运。 */    public static final class Tally {
        public int bought;
        public int dryRun;
        public int ownedAlready;
        public int failed;
        /** 实付代券累计。 */
        public int spent;
        public final List<String> notes;

        public Tally(List<String> notes) {
            this.notes = notes;
        }
    }

    private SubscribeRun() {
    }

    /** 本地时区今天零点，每日花费上限按这个切。 */
    public static long startOfToday() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /**
     * 让当前登着的这个号买章，买不动了就返回（由调用方换号）。
     *
     * @param balance 这一趟刚读到的余额；不知道就传 {@link Texts.Balance} 的未知值，
     *                会退回账号库里记的代券数。
     */
    public static void oneAccount(StepRunner r, StepRunner.Host host, SubscriptionDao subs,
                                  AccountDao accountDao, Plan plan, Account account,
                                  Texts.Balance balance, Set<Long> touched, Tally tally)
            throws StepRunner.StepFailure {
        String name = account.displayName();
        // 预算只看代券：章节费两种券都能付，但菠萝包先扣代券，而用户不充值火券。
        int budget = balance.voucher >= 0 ? balance.voucher : account.lastKnownVouchers;
        int spentToday = plan.cap > 0 ? subs.spentVouchersSince(account.id, plan.since) : 0;

        if (!auditLedger(r, host, subs, plan, account, tally)) return;
        if (!syncCatalog(r, host, subs, plan, account, tally)) return;

        int perAccount = plan.perAccount();
        List<Chapter> chapters = subs.findUnownedChaptersFrom(
                plan.novel.id, plan.novel.startFrom(), perAccount + touched.size());
        if (chapters.isEmpty()) {
            host.log("  没有待订阅的章节：从第" + plan.novel.startFrom()
                    + "章起的每一章都已经有号拥有了");
            return;
        }

        int doneHere = 0;
        int lastPaid = 0;   // 上一章的实付代券。同一本书各章同价，拿它预判下一章买不买得起。
        for (Chapter chapter : chapters) {
            if (host.isCancelled()) {
                throw new StepRunner.StepFailure(StepRunner.Kind.CANCELLED, "已取消");
            }
            if (doneHere >= perAccount) {
                host.log("  " + name + " 这一轮到此为止（安全阀：每号最多 "
                        + perAccount + " 章）");
                return;
            }
            if (touched.contains(chapter.id)) continue;

            // 进页之前先算一次「还买得起吗」。章节表里的单价是历史遗留（旧版按火券登记，
            // 扫目录不会写它），所以优先用上一章的实付代券当估价。
            int estimate = lastPaid > 0 ? lastPaid : chapter.priceCoupons;
            String stop = stopBecauseBroke(name, plan.dryRun, budget, estimate,
                    spentToday, plan.cap, chapter.chapterNo);
            if (stop != null) {
                host.log("  " + stop);
                return;
            }

            touched.add(chapter.id);
            host.log("  " + name + " 试第" + chapter.chapterNo + "章「" + chapter.title + "」");
            SubscribeTask.Result result = SubscribeTask.run(r, plan.novel, chapter, plan.dryRun);
            host.log("    " + result.message);
            if (result.coupons >= 0) account.lastKnownCoupons = result.coupons;
            if (result.vouchers >= 0) account.lastKnownVouchers = result.vouchers;
            if (result.coupons >= 0 || result.vouchers >= 0) {
                accountDao.setBalance(account.id, result.coupons, result.vouchers);
                // 干跑不扣券，页面上的余额一动不动 —— 拿它当预算，「买到余额不足」就永远不会
                // 发生，干跑会一路勾到安全阀，把后面几个号该拿的章全占掉。所以干跑只信自己
                // 模拟扣出来的那个数（见下面），真买才用页面上的新余额。
                if (!plan.dryRun && result.vouchers >= 0) budget = result.vouchers;
            }

            if (!apply(subs, host, tally, account, chapter, result, plan)) {
                // 券不够只是这个号买不起，别把这一章从整轮里划掉 —— 后面的号可能买得起。
                if (result.status == SubscribeTask.Status.INSUFFICIENT) touched.remove(chapter.id);
                return;
            }
            doneHere++;
            if (result.costVouchers > 0) lastPaid = result.costVouchers;
            if (result.status == SubscribeTask.Status.BOUGHT) spentToday += result.costVouchers;
            if (plan.reachedBuyLimit(tally.bought)) {
                host.log("  真买上限到了（这一趟只准真买 " + plan.buyLimit
                        + " 章）—— 整趟收工，后面的号一个都不动");
                return;
            }
            if (result.status == SubscribeTask.Status.DRY_RUN) {
                budget -= result.costVouchers;
                spentToday += result.costVouchers;
                host.log("    干跑记账：模拟扣 " + result.costVouchers + " 代券，"
                        + name + " 名下还剩 " + budget + " 代券（真实余额一分没动）");
            }
        }
        if (doneHere > 0) {
            host.log("  " + name + " 这一轮走了 " + doneHere + " 章，取到的这一批章都过完了");
        }
    }

    /**
     * 进下一章之前判断「这个号还买得起吗」。返回 null＝还买得起，否则返回该写进日志的那句话。
     *
     * <p>拆成一个纯函数是为了能单测：这是「一个号订到余额不足再换下一个号」的判据本身，
     * 而它在真机上要跑一整趟 8 个号才看得到一次。
     *
     * @param budget   这个号还剩多少代券（真买＝页面上刚读到的；干跑＝模拟扣券之后的）
     * @param estimate 下一章大概要多少代券，0＝不知道（那就进页面让菠萝包自己说）
     */
    static String stopBecauseBroke(String name, boolean dryRun, int budget, int estimate,
                                   int spentToday, int cap, int chapterNo) {
        if (estimate <= 0 || budget < 0) return null;
        if (budget < estimate) {
            return name + (dryRun ? " 模拟扣券后只剩 " : " 只剩 ") + budget
                    + " 代券，不够买第" + chapterNo + "章（约 " + estimate + " 代券），换下一个号";
        }
        if (cap > 0 && spentToday + estimate > cap) {
            return name + " 今天已花 " + spentToday + " 代券，再买第" + chapterNo
                    + "章会超过每日上限 " + cap + "，换下一个号";
        }
        return null;
    }

    /**
     * 买之前先拿服务器端的「我的 → 代券 → 订阅清单」核一次账本。返回 false＝这个号不买。
     *
     * <p>为什么要在买之前多走这几次点按：「这一章归谁」原先只有账本一个来源，账本写歪了
     * App 自己看不出来 —— 2026-08-25 第49章被误挂两个号，发现它的是用户，靠的正是这份清单
     * （见 {@link VoucherLedger}）。账本不可信的时候接着买，就会重复买或者永远跳过某一章，
     * 而这两件事都直接违反「8 个号拼出完整一本、不多订不漏订」。
     *
     * <p>核两层：先是清单那一行（按书聚合：订了几章、花了多少火券），再点<b>整行</b>进
     * 「订阅明细」<b>逐章</b>核（见 {@link SubscribedDetail}）。用户那两条硬约束是逐章的，
     * 聚合那一层只答得出「总数差了几章」，答不出差在哪一章。
     *
     * <p>所以对不上就<b>整趟</b>停下（{@code MONEY_UNCLEAR}），不是只跳过这个号：账本是所有
     * 号共用的，它错了，换个号接着买同样是错的。读不到不算对不上（清单读不出来并不说明账本
     * 错了），只把话写进小结让人看见。
     */
    private static boolean auditLedger(StepRunner r, StepRunner.Host host, SubscriptionDao subs,
                                       Plan plan, Account account, Tally tally)
            throws StepRunner.StepFailure {
        if (!VoucherLedger.configured(r.selectors())) {
            host.log("  selectors.json 里没配「代券 → 订阅清单」那几条，这一趟跳过对账");
            return true;
        }
        VoucherLedger.Located found = VoucherLedger.locate(r, plan.novel.title);
        VoucherLedger.Reading ui = found.reading;
        int paid = subs.countPaidPurchases(account.id, plan.novel.id);
        int fire = subs.sumFireSpent(account.id, plan.novel.id);
        VoucherLedger.Audit audit = VoucherLedger.reconcile(
                account.displayName(), plan.novel.title, ui, paid, fire);
        host.log("  " + audit.message);
        if (!audit.ok) {
            r.ensureHome(4);
            tally.notes.add(audit.message);
            throw new StepRunner.StepFailure(StepRunner.Kind.MONEY_UNCLEAR, audit.message);
        }
        if (!audit.checked) tally.notes.add(audit.message);
        // 聚合那一层只看得出「总数差了几章」。用户那两条硬约束是逐章的（「每一章只能有一个账号
        // 订阅」、「8 个号拼出完整一本」），所以再点整行进「订阅明细」逐章核一遍 —— 差在哪一章，
        // 只有这一页说得出来。
        VoucherLedger.Audit detail = auditDetail(r, host, subs, plan, account, found);
        // 读完停在清单页／明细页，而搜书那一步是从首页开始的。
        r.ensureHome(4);
        if (detail == null) return true;
        host.log("  " + detail.message);
        if (detail.ok) {
            if (!detail.checked) tally.notes.add(detail.message);
            return true;
        }
        tally.notes.add(detail.message);
        throw new StepRunner.StepFailure(StepRunner.Kind.MONEY_UNCLEAR, detail.message);
    }

    /** 逐章那一层。返回 null＝这一趟没走（选择器没配齐，或清单里压根没有这本书那一行）。 */
    private static VoucherLedger.Audit auditDetail(StepRunner r, StepRunner.Host host,
                                                   SubscriptionDao subs, Plan plan,
                                                   Account account, VoucherLedger.Located found)
            throws StepRunner.StepFailure {
        if (!SubscribedDetail.configured(r.selectors())) {
            host.log("  selectors.json 里没配「订阅明细」那几条，这一趟只做了按书聚合的对账");
            return null;
        }
        if (found.row == null) return null;   // 清单里没这本书那一行，上面已经核过了
        List<SubscribedDetail.Entry> entries =
                SubscribedDetail.read(r, found.row, plan.novel.title);
        for (SubscribedDetail.Entry e : entries == null
                ? java.util.Collections.<SubscribedDetail.Entry>emptyList() : entries) {
            host.log("    明细：" + e.describe());
        }
        return SubscribedDetail.reconcile(account.displayName(), account.id, plan.novel.title,
                entries, subs.loadPaidRowsOfNovel(plan.novel.id), System.currentTimeMillis());
    }

    /** 扫目录并写账本。返回 false = 这个号一章都不买。 */
    private static boolean syncCatalog(StepRunner r, StepRunner.Host host, SubscriptionDao subs,
                                       Plan plan, Account account, Tally tally)
            throws StepRunner.StepFailure {
        String name = account.displayName();
        CatalogSync.Report catalog = CatalogSync.sync(r, subs, plan.novel, account.id);
        host.log("  " + catalog.message);
        if (catalog.realigned != null) {
            // 章号搬家是账本的结构性变化，光写日志不够 —— 队列小结里也得看得见。
            tally.notes.add(catalog.realigned);
            host.log("    " + catalog.realigned);
        }
        if (!catalog.skipped.isEmpty()) {
            host.log("    跳过的无标号行（卷标题一类）：" + TextUtils.join("｜", catalog.skipped));
        }
        if (!catalog.foreign.isEmpty()) {
            // 付费章 + 本机已下载 + 这个号账本里没记：买家很可能是另一个号（「已下载」是本机
            // 状态、8 个号共用）。绝不回填成这个号拥有 —— 2026-08-25 第49章就是那样多出
            // 一个订阅者的。报出来让人对着「我的 → 代券 → 订阅清单」核对是谁买的。
            String note = catalog.foreign.size() + " 章在这台手机上已下载、但账本里没有归属（"
                    + TextUtils.join("、",
                    catalog.foreign.subList(0, Math.min(5, catalog.foreign.size())))
                    + (catalog.foreign.size() > 5 ? "…" : "")
                    + "），一个字都没写，请对着订阅清单核对是哪个号买的";
            tally.notes.add(note);
            host.log("    " + note);
        }
        if (!catalog.contradictions.isEmpty()) {
            // 账本说这个号买过、界面上却还要花券。不自动改账本：删记录会让它被重新买一次，
            // 留着又会让它一直被跳过。这件事必须由人看过再决定。
            String note = name + " 有 " + catalog.contradictions.size()
                    + " 章账本说买过、界面上却还要花券才看得到（" + TextUtils.join("、",
                    catalog.contradictions.subList(0, Math.min(5, catalog.contradictions.size())))
                    + "…），请核对";
            tally.notes.add(note);
            host.log("    " + note);
        }
        if (!catalog.ok) {
            tally.failed++;
            return false;
        }
        return true;
    }

    /**
     * 写账本。返回 false 表示这个号别再往下买了。
     *
     * @throws StepRunner.StepFailure {@code MONEY_UNCLEAR} —— 点过「立即下载」而结果不明，
     *                                券可能已经扣了。这一条会被两个队列都当成<b>全局</b>失败，
     *                                整趟就此收工。2026-08-24 那次事故就差这一下：失败不占真买
     *                                保险丝的额度，于是三个号各点了一次，两个真扣了券。
     */
    private static boolean apply(SubscriptionDao subs, StepRunner.Host host, Tally tally,
                                 Account account, Chapter chapter, SubscribeTask.Result result,
                                 Plan plan)
            throws StepRunner.StepFailure {
        String name = account.displayName();
        String label = "第" + chapter.chapterNo + "章";
        switch (result.status) {
            case BOUGHT:
                subs.upsertPurchase(Purchase.of(account.id, chapter.id,
                        result.cost, result.costVouchers, Purchase.SRC_AUTO));
                tally.bought++;
                tally.spent += result.costVouchers;
                // 常驻授权的当日额度当场扣掉 —— 进程随时会被 MIUI 杀掉（SwipeUpClean），
                // 等跑完再结算的话，系统重发的那一趟会拿着满额度把同样的章再买一遍。
                if (plan != null && plan.app != null) BuyMandate.noteBought(plan.app, 1);
                host.log("  " + name + " 订到" + label + "，花 " + result.costVouchers + " 代券"
                        + (result.cost > 0 ? "＋" + result.cost + " 火券（不该发生，请核对）" : ""));
                return true;
            case DRY_RUN:
                subs.upsertPurchase(Purchase.of(account.id, chapter.id, 0, 0, Purchase.SRC_DRY_RUN));
                tally.dryRun++;
                host.log("  " + name + " " + label + " 干跑通过（判定买得起，没扣券）");
                return true;
            case ALREADY:
                // 免费章：谁登录都看得到，账本补一条，否则每轮都会再来一次。
                // 记成 OWNED 而不是 AUTO：一分券都没花。
                subs.upsertPurchase(Purchase.of(account.id, chapter.id, 0, 0, Purchase.SRC_OWNED));
                tally.ownedAlready++;
                host.log("  " + name + " " + label + " 是免费章，已补记账本");
                return true;
            case DEVICE_HAS_IT:
                // 本机已下载但看不出是哪个号买的：绝不按当前号记账（那正是第49章多出一个
                // 订阅者的原因），也买不了。跳过这一章，这个号接着试下一章。
                tally.notes.add(label + " 本机已有但不知道是谁买的（" + name
                        + " 买不了它），请对着订阅清单核对");
                host.log("  " + name + " 跳过" + label + "：本机已下载，买家不明");
                return true;
            case INSUFFICIENT:
                tally.notes.add(name + " 代券不够（" + label + "）");
                host.log("  " + name + " 买不起，换下一个号");
                return false;
            default:
                tally.failed++;
                host.log("  " + name + " " + label + " 没走通：" + result.message);
                if (result.abortRun) {
                    // 钱可能已经动了而看不出买成没买成：整趟停下，让人核对，绝不换号接着点。
                    tally.notes.add(name + " " + label + " 结果不明，整趟已停下，请核对余额");
                    throw new StepRunner.StepFailure(StepRunner.Kind.MONEY_UNCLEAR,
                            name + " " + label + "：" + result.message);
                }
                return false;
        }
    }
}
