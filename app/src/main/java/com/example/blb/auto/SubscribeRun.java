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

import java.util.HashSet;
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
 * <b>所有号合起来还没买过</b>的最小章，所以各个号的券摊在不同章上，
 * 合起来把进度往前推，而不是几个号买同一章。{@code touched} 保证同一轮里一章只试一次。
 *
 * <p>停止条件只有一条：<b>这个号的代券不够下一章 → 换下一个号</b>；所有号都不够 → 收工。
 * 没有章数上限 —— 一个号代券够就一直往下订到花光。
 */
public final class SubscribeRun {

    /** 一轮里固定不变的参数。 */
    public static final class Plan {
        public final Novel novel;
        /** 每号每日代券上限，0＝不限。 */
        public final int cap;
        public final long since;

        public Plan(Novel novel, int cap, long since) {
            this.novel = novel;
            this.cap = cap;
            this.since = since;
        }

        public static Plan from(Context context, Novel novel) {
            return new Plan(novel, Prefs.dailySpendCap(context), startOfToday());
        }

        public String describe() {
            return "订阅是真实购买：《" + novel.title + "》从第" + novel.startFrom() + "章起，"
                    + "按队列顺序一章一章往下订，一个号订到代券不够为止再换下一个号"
                    + "（不限章数）"
                    + (cap > 0 ? "，每号每日上限 " + cap + " 代券" : "");
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

    /** 计数。备注直接写进调用方的列表，省一次搬运。 */
    public static final class Tally {
        public int bought;
        public int ownedAlready;
        public int failed;
        /** 实付代券累计。 */
        public int spent;
        public final List<String> notes;
        /**
         * 这一趟<b>卡住</b>的那一章（章号），0＝没卡住。
         *
         * <p>用户 2026-09-03 的原话：「对比要订阅的章节能不能订阅，能就订阅，不能就判断
         * 下一个账号，永远也不会出现跳过某一章的情况」。所以队列绝不越过一章去买它后面的章，
         * 一趟里最多只会卡在一章上 —— 就是「所有号合起来还没买过的最小章」。这里记的不是
         * 「跳过了哪几章」，而是「整本停在第几章」：停下来让人看，比留一个洞接着往后买
         * 更符合「8 个号拼出完整一本」。
         */
        public int stuckChapterNo;
        /** 有几个号在这一章上没订下来（券不够也算 —— 那同样是「这个号订不了它」）。 */
        public int stuckTries;
        /** 最后一个号没订下来的原因，跑完要写进弹窗给人看。 */
        public String stuckReason;
        /** 这一趟真正买到的最大章号 —— 收尾自检靠它看出中间有没有空章。 */
        public int maxBoughtNo;

        public Tally(List<String> notes) {
            this.notes = notes;
        }
    }

    /**
     * 这一轮<b>已经有定论、不必再有号碰</b>的章。
     *
     * <p>它管的是唯一一件事：什么时候允许把一章从这一趟里划掉。答案只有三种 ——
     * 买到了、是免费章、本机已有所以谁都买不了。<b>「没走通」永远不在其中</b>。
     *
     * <p>原来这里是一个裸的 {@code Set<Long>}：进页面前就 add，除了「券不够」全都不撤。
     * 于是 2026-09-03 那趟第83章在前面某个号身上没走通一次，后面 6 个号全部跳过它 ——
     * 84、85、86、87 章都买到了，83 章空着，「8 个号拼出完整一本」当场破掉。中间一版
     * 放宽成「最多让 2 个号试」，也还是会跳；用户随后把规则说死了：「不能就判断下一个账号，
     * 永远也不会出现跳过某一章的情况」。所以现在失败一次都不许划掉，8 个号全试一遍，
     * 全都不行就<b>整本停在那一章</b>（{@link Tally#stuckChapterNo}），绝不往后买。
     *
     * <p>之所以还留着这层包装而不直接用 Set：「什么时候能 add」这条规则得有地方写下来 ——
     * 那正是上面那个 bug 的来源。
     */
    public static final class Settled {

        private final Set<Long> ids = new HashSet<>();

        /** 这一章有定论了吗＝这一轮还该不该有号试它。 */
        public boolean has(long chapterId) {
            return ids.contains(chapterId);
        }

        /** 只在「买到／免费章／本机已有买不了」时调 —— 失败绝不许调这个。 */
        void mark(long chapterId) {
            ids.add(chapterId);
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
     * <p><b>永远从「所有号合起来还没买过的最小章」开始，一章都不越过</b>：这一章订不下来
     * （券不够、找不到那一行、点了没选上）就<b>立刻返回换下一个号试同一章</b>，绝不改去买
     * 它后面的章。用户的原话：「对比要订阅的章节能不能订阅，能就订阅，不能就判断下一个账号，
     * 永远也不会出现跳过某一章的情况」。订下来了才往后走下一章，直到这个号的代券花光。
     *
     * @param balance 这一趟刚读到的余额；不知道就传 {@link Texts.Balance} 的未知值，
     *                会退回账号库里记的代券数。
     */
    public static void oneAccount(StepRunner r, StepRunner.Host host, SubscriptionDao subs,
                                  AccountDao accountDao, Plan plan, Account account,
                                  Texts.Balance balance, Settled settled, Tally tally)
            throws StepRunner.StepFailure {
        String name = account.displayName();
        // 预算只看代券：章节费两种券都能付，但菠萝包先扣代券，而用户不充值火券。
        int budget = balance.voucher >= 0 ? balance.voucher : account.lastKnownVouchers;
        int spentToday = plan.cap > 0 ? subs.spentVouchersSince(account.id, plan.since) : 0;

        if (!auditLedger(r, host, subs, plan, account, tally)) return;
        if (!syncCatalog(r, host, subs, plan, account, tally)) return;

        // 整条队列一次取完，不设条数上限：停下来的理由只能是「代券不够下一章」。
        List<Chapter> chapters = subs.findUnownedChaptersFrom(
                plan.novel.id, plan.novel.startFrom());
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
            // 有定论的才允许往后走（买到了／免费章／本机已有谁都买不了）。「没走通」不在其中。
            if (settled.has(chapter.id)) continue;

            // 进页之前先算一次「还买得起吗」。章节表里的单价是历史遗留（旧版按火券登记，
            // 扫目录不会写它），所以优先用上一章的实付代券当估价。
            int estimate = lastPaid > 0 ? lastPaid : chapter.priceCoupons;
            String stop = stopBecauseBroke(name, budget, estimate,
                    spentToday, plan.cap, chapter.chapterNo);
            if (stop != null) {
                host.log("  " + stop);
                // 买不起也是「这个号订不了这一章」：记一次，交给下一个号试同一章。
                noteBlocked(host, tally, chapter, stop);
                return;
            }

            host.log("  " + name + " 试第" + chapter.chapterNo + "章「" + chapter.title + "」");
            SubscribeTask.Result result = SubscribeTask.run(r, plan.novel, chapter);
            host.log("    " + result.message);
            if (result.coupons >= 0) account.lastKnownCoupons = result.coupons;
            if (result.vouchers >= 0) account.lastKnownVouchers = result.vouchers;
            if (result.coupons >= 0 || result.vouchers >= 0) {
                accountDao.setBalance(account.id, result.coupons, result.vouchers);
                // 买成之后页面上的余额就是权威预算：下一章买不买得起完全看它。
                if (result.vouchers >= 0) budget = result.vouchers;
            }

            if (!apply(subs, host, tally, account, chapter, result)) {
                // 没订下来：这一章一个字都不划掉，换下一个号来试它。绝不改去买后面的章。
                noteBlocked(host, tally, chapter,
                        result.status == SubscribeTask.Status.INSUFFICIENT
                                ? name + " 代券不够（页面上说余额不足）" : brief(result.message));
                return;
            }
            // 买到了／免费章／本机已有买不了：这一章有定论，这一轮别再有号重复试它。
            settled.mark(chapter.id);
            doneHere++;
            if (result.costVouchers > 0) lastPaid = result.costVouchers;
            if (result.status == SubscribeTask.Status.BOUGHT) spentToday += result.costVouchers;
        }
        if (doneHere > 0) {
            host.log("  " + name + " 这一轮走了 " + doneHere + " 章，整条队列已经走到底了");
        }
    }

    /**
     * 记一次「这个号没把这一章订下来」：券不够、找不到那一行、点了「已选」还是 0，一律算。
     *
     * <p>整趟共用一个 {@link Tally}，所以同一章被一个个号试过去就在这里累加。队列绝不越过
     * 一章，因此章号一变就说明前一章已经订下来了 —— 计数从头开始。跑完由
     * {@link #stuckNote(SubscriptionDao, Plan, Tally)} 判断它是不是真的没订下来
     * （它还是不是全局最小未买章），是就把「整本停在第几章、几个号试过、最后一个号为什么不行」
     * 写进弹窗 —— 2026-09-03 那趟弹窗上只有一句「没走通 1」，既没有章号也没有后果。
     */
    private static void noteBlocked(StepRunner.Host host, Tally tally, Chapter chapter,
                                    String reason) {
        if (tally.stuckChapterNo != chapter.chapterNo) {
            tally.stuckChapterNo = chapter.chapterNo;
            tally.stuckTries = 0;
        }
        tally.stuckTries++;
        tally.stuckReason = brief(reason);
        host.log("    第" + chapter.chapterNo + "章还是没订下来（第 " + tally.stuckTries
                + " 个号），下一个号继续试它 —— 绝不跳过它去买后面的章");
    }

    /** 失败原因截短了给小结用；完整那句上一行日志里就有。 */
    private static String brief(String message) {
        if (Texts.isBlank(message)) return "不明";
        String s = message.trim();
        return s.length() <= 60 ? s : s.substring(0, 60) + "…";
    }

    /**
     * 跑完之后那句「整本现在停在第几章」。返回 null＝没卡住（或者后来有号把它订下来了）。
     *
     * <p>这是「不跳过任何一章」这条规则在弹窗上唯一看得见的地方：队列绝不越过一章，所以
     * 一趟里最多只会停在一章上。它<b>不算失败</b> —— 8 个号都只是代券不够（今天最常见的
     * 收工方式）也会走到这里，那时候流程本身是跑完的，只是没钱往下买。真出错由
     * 「订阅没走通 N 个号」那一行说，真漏章由 {@link #gapNote} 说。
     */
    public static String stuckNote(SubscriptionDao subs, Plan plan, Tally tally) {
        if (subs == null || plan == null || tally == null) return null;
        return stuckNote(tally.stuckChapterNo, tally.stuckTries, tally.stuckReason,
                nextUnownedNo(subs, plan));
    }

    /**
     * 判据本身（拆出来是为了能单测：真机上要跑一整趟 8 个号才看得到一次）。
     *
     * <p>只有当这一章<b>确实还是</b>全局最小未买章时才报 —— 前面某个号没订下它、
     * 后面某个号订下了，那不叫卡住，一个字都不该说。
     *
     * @param nextUnownedNo 现在所有号合起来还没买过的最小章号，0＝没有了（整本都有主了）
     */
    static String stuckNote(int stuckNo, int tries, String reason, int nextUnownedNo) {
        if (stuckNo <= 0 || stuckNo != nextUnownedNo) return null;
        return "第" + stuckNo + "章没订下来（" + tries + " 个号都试过了），"
                + "整本停在这里 —— 后面的章一章都没往后买"
                + (Texts.isBlank(reason) ? "" : "。最后一个号：" + reason);
    }

    /**
     * 收尾自检：这一趟买到的章里有没有<b>越过</b>一章。返回 null＝没漏。
     *
     * <p>队列绝不越过一章，所以这句话正常永远不该出现 —— 留着它当不变量断言。还有两条路
     * 能让缺口悄悄出现：本机已下载但谁都买不了的那种章（会被放过去，见
     * {@link #apply} 的 DEVICE_HAS_IT 分支），以及 MIUI 半路杀掉进程之后系统重发的那一趟。
     * 用户就是这么发现 2026-09-03 那次漏订的（STATUS 说「下一章＝第83章」，那趟却买到了第87章）。
     */
    public static String gapNote(SubscriptionDao subs, Plan plan, Tally tally) {
        if (subs == null || plan == null || tally == null) return null;
        return gapNote(tally.maxBoughtNo, nextUnownedNo(subs, plan));
    }

    /** 判据本身。 */
    static String gapNote(int maxBoughtNo, int nextUnownedNo) {
        if (maxBoughtNo <= 0 || nextUnownedNo <= 0 || nextUnownedNo > maxBoughtNo) return null;
        return "这趟买到了第" + maxBoughtNo + "章，可第" + nextUnownedNo
                + "章还是空的 —— 中间漏了一章，这本书现在拼不完整";
    }

    /** 所有号合起来还没买过的最小章号；0＝没有了。 */
    private static int nextUnownedNo(SubscriptionDao subs, Plan plan) {
        Chapter next = subs.findNextUnownedChapterFrom(plan.novel.id, plan.novel.startFrom());
        return next == null ? 0 : next.chapterNo;
    }

    /**
     * 进下一章之前判断「这个号还买得起吗」。返回 null＝还买得起，否则返回该写进日志的那句话。
     *
     * <p>拆成一个纯函数是为了能单测：这是「一个号订到余额不足再换下一个号」的判据本身，
     * 而它在真机上要跑一整趟 8 个号才看得到一次。
     *
     * @param budget   这个号还剩多少代券（页面上刚读到的那个数）
     * @param estimate 下一章大概要多少代券，0＝不知道（那就进页面让菠萝包自己说）
     */
    static String stopBecauseBroke(String name, int budget, int estimate,
                                   int spentToday, int cap, int chapterNo) {
        if (estimate <= 0 || budget < 0) return null;
        if (budget < estimate) {
            return name + " 只剩 " + budget
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
            // 付费章 + 本机已下载 + 8 个号的账本里都查不到：买家真的说不清。这里判「有没有
            // 归属」是跨号问的（见 CatalogSync.Report#foreign）—— 只问当前号的那个版本，
            // 会把皓平买的章在另外 7 个号身上各报一次「没有归属」，2026-09-03 那趟刷出的
            // 8 条备注全是这样来的误报，而同一趟的逐章对账明明说「都对上了」。
            String note = catalog.foreign.size() + " 章在这台手机上已下载、但 8 个号的账本里"
                    + "都查不到归属（"
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
     *                                整趟就此收工。2026-08-24 那次事故就差这一下：三个号各点了
     *                                一次，两个真扣了券。
     */
    private static boolean apply(SubscriptionDao subs, StepRunner.Host host, Tally tally,
                                 Account account, Chapter chapter, SubscribeTask.Result result)
            throws StepRunner.StepFailure {
        String name = account.displayName();
        String label = "第" + chapter.chapterNo + "章";
        switch (result.status) {
            case BOUGHT:
                subs.upsertPurchase(Purchase.of(account.id, chapter.id,
                        result.cost, result.costVouchers, Purchase.SRC_AUTO));
                tally.bought++;
                tally.spent += result.costVouchers;
                if (chapter.chapterNo > tally.maxBoughtNo) tally.maxBoughtNo = chapter.chapterNo;
                host.log("  " + name + " 订到" + label + "，花 " + result.costVouchers + " 代券"
                        + (result.cost > 0 ? "＋" + result.cost + " 火券（不该发生，请核对）" : ""));
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
