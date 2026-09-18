package com.example.blb.auto;

import android.content.Context;

import com.example.blb.data.Account;
import com.example.blb.data.AccountDao;
import com.example.blb.data.AppDatabase;
import com.example.blb.data.Chapter;
import com.example.blb.data.Db;
import com.example.blb.data.Novel;
import com.example.blb.data.Purchase;
import com.example.blb.data.SubscriptionDao;
import com.example.blb.ui.LedgerEdits;
import com.example.blb.util.Prefs;
import com.example.blb.util.Texts;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 「用当前登着的这个号买尽量多的章」这一段，每日队列签到之后的订阅步骤和订阅页的
 * 「开始自动订阅」共用同一份实现 —— 两边各写一遍的时候，护栏总会有一边漏掉。
 *
 * <p>目录由订阅页独立同步；八个号共用同一份章节顺序，不应为了同一本书重复扫八遍。
 * 每个号仍读取清单聚合行，准备花券时必须有本号逐章核对和全书其他账号的有效证据。
 * 付费章归谁只认真实购买记录 —— 界面上那个「已下载」是本机状态、8 个号共用，读不出买家。
 *
 * <p>要买哪一章不看当前这个号，看账本：{@code findUnownedChaptersFrom} 给的是
 * <b>所有号合起来还没买过</b>的最小章，所以各个号的券摊在不同章上，
 * 合起来把进度往前推，而不是几个号买同一章。失败章仍留给下一个账号尝试，不能越过。
 *
 * <p>停止条件只有一条：<b>页面上出现「余额不足，快去充值吧」→ 换下一个号</b>；所有号都不够 → 收工。
 * 没有章数上限 —— 一个号代券够就一直往下订到页面说没钱为止。
 */
public final class SubscribeRun {

    /** 一轮里固定不变的参数。 */
    public static final class Plan {
        public final Novel novel;
        /** 每号每日代券上限，0＝不限。 */
        public final int cap;
        public final long since;
        public final long catalogScannedAt;

        public Plan(Novel novel, int cap, long since) {
            this.novel = novel;
            this.cap = cap;
            this.since = since;
            this.catalogScannedAt = novel.catalogScannedAt;
        }

        public static Plan from(Context context, Novel novel) {
            return new Plan(novel, Prefs.dailySpendCap(context), startOfToday());
        }

        public String describe() {
            return "订阅是真实购买：《" + novel.title + "》从第" + novel.startFrom() + "章起，"
                    + "按队列顺序一章一章往下订，一个号订到页面显示余额不足再换下一个号"
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
     * 买到了、是免费章、或账本已确认有归属。<b>「没走通」和只有本机下载状态永远不在其中</b>。
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

        /** 只有账本已经确认归属才调用；下载标记本身不能替代购买记录。 */
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
     * <p>2026-09-15 起不再需要传「这一趟刚读到的余额」：那时候它被用来在进页面之前猜价格，
     * 而猜价正是「订 1~2 章就换号」的原因（见 {@link #stopBecauseBroke}）。现在买不买得起
     * 只由页面回答；余额仍由调用方自己读、自己写回账号库（签到页和订阅页都要显示它）。
     */
    public static void oneAccount(StepRunner r, StepRunner.Host host, SubscriptionDao subs,
                                  AccountDao accountDao, Plan plan, Account account,
                                  Settled settled, Tally tally)
            throws StepRunner.StepFailure {
        try {
            runAccount(r, host, subs, accountDao, plan, account, settled, tally);
        } catch (StepRunner.StepFailure failure) {
            if (failure.kind == StepRunner.Kind.MONEY_UNCLEAR) {
                invalidateAfterFailure(r, host, plan, account, failure);
            }
            throw failure;
        } catch (RuntimeException failure) {
            StepRunner.StepFailure unclear = new StepRunner.StepFailure(StepRunner.Kind.MONEY_UNCLEAR,
                    "订阅账本或页面状态异常，整本停止：" + failure.getMessage());
            invalidateAfterFailure(r, host, plan, account, unclear);
            throw unclear;
        }
    }

    private static void runAccount(StepRunner r, StepRunner.Host host, SubscriptionDao subs,
                                    AccountDao accountDao, Plan plan, Account account,
                                    Settled settled, Tally tally)
            throws StepRunner.StepFailure {
        String name = account.displayName();
        List<Chapter> fullCatalog = subs.loadChapters(plan.novel.id);
        requireCatalogCount(fullCatalog == null ? -1 : fullCatalog.size());
        if (r.context() == null) throw new StepRunner.StepFailure(StepRunner.Kind.CONFIG,
                "核对订阅需要本地数据库上下文，未执行购买");
        AppDatabase db = Db.get(r.context());
        boolean willSpend = subs.findNextUnownedChapterFrom(plan.novel.id, plan.novel.startFrom()) != null;
        SubscriptionAuditQueue.AccountResult audit = SubscriptionAuditQueue.auditAccount(
                r, host, db, plan.novel, account, false,
                Prefs.isAlwaysDetailAudit(r.context()), willSpend);
        host.log("  " + audit.message);
        if (!audit.verified) {
            tally.failed++;
            tally.notes.add(audit.message);
            StepRunner.Kind kind = audit.failureKind != null && CheckInQueue.isGlobal(audit.failureKind)
                    ? audit.failureKind : StepRunner.Kind.MONEY_UNCLEAR;
            throw new StepRunner.StepFailure(kind, audit.message);
        }
        if (audit.backfilled > 0) tally.notes.add(audit.message);
        // 刚补回的当天真实花费也算额度；核账前缓存这个数字会在到达上限后再花一次券。
        int spentToday = plan.cap > 0 ? subs.spentVouchersSince(account.id, plan.since) : 0;
        int doneHere = 0;
        // 2026-09-15（第六次修复）：这个号上一章买成的是哪一章 —— 它的勾可能还留在页面上，
        // 下一次购买前要按它那一行清干净（见 SubscribeTask.clearSelectionResidue）。
        Chapter boughtHere = null;
        while (true) {
            if (host.isCancelled()) {
                throw new StepRunner.StepFailure(StepRunner.Kind.CANCELLED, "已取消");
            }
            // 第83章曾因旧的整批列表被跳过；每一轮只认数据库此刻的最小未拥有章。
            Chapter chapter = recomputeNextChapter(subs, plan, host);
            if (chapter == null) {
                if (doneHere > 0) host.log("  " + name + " 本轮订到当前已同步目录的末尾，共 " + doneHere + " 章");
                return;
            }
            if (settled.has(chapter.id)) throw new StepRunner.StepFailure(StepRunner.Kind.MONEY_UNCLEAR,
                    "第" + chapter.chapterNo + "章本轮已处理，账本却仍没有归属，整本停止以免漏章");
            String blocker = SubscriptionAuditPolicy.bookBlocker(db.accountDao().loadEnabled(),
                    db.auditDao().loadProgressOfNovel(plan.novel.id),
                    startOfToday(), System.currentTimeMillis());
            if (blocker != null) {
                tally.failed++;
                tally.notes.add(blocker);
                throw new StepRunner.StepFailure(StepRunner.Kind.MONEY_UNCLEAR, blocker);
            }

            // 2026-09-15 第五次反馈：以前这里拿「上一章的实付价」当这一章的估价，余额一低于它
            // 就判买不起、直接换号 —— 而这本书每章单价 10~14 代券不等，于是「有时订 1 章、
            // 有时订 2 章、余额还剩着就换号」。章节表里的 priceCoupons 是旧版按火券登记的
            // 历史遗留（扫目录写 -1），也不能当价。现在不再猜价：进页面让菠萝包自己说，
            // 页面上出现「余额不足，快去充值吧」才算这个号没钱了（见 SubscribeTask）。
            // 每日上限仍然是硬事实（账本里今天已经花掉多少），所以先按它拦一次。
            String stop = stopBecauseBroke(name, spentToday, plan.cap);
            if (stop != null) {
                host.log("  " + stop);
                noteBlocked(host, tally, chapter, stop);
                return;
            }

            host.log("  " + name + " 试第" + chapter.chapterNo + "章「" + chapter.title + "」");
            // 2026-09-14 允许补回跨号真实历史，但自动化不能新增重复；进入购买动作前再次现查所有号的归属。
            requireStillUnowned(chapter,
                    subs.findNextUnownedChapterFrom(plan.novel.id, plan.novel.startFrom()));
            SubscribeTask.Result result = SubscribeTask.run(r, plan.novel, chapter,
                    remainingDailyVouchers(plan.cap, spentToday), fullCatalog, boughtHere);
            // 钱已经付出去时先记购买事实。取消或后续余额回填失败都不能把这笔账丢掉。
            boolean completed;
            int boughtBefore = tally.bought;
            try {
                completed = applyDuringRun(subs, host, tally, account, chapter, result);
            } finally {
                // 2026-08-24 曾经扣券后因收尾失败丢账；即使购买后必须中止，
                // 也要把已经落库的进度念出来，不能让人误以为这一章还没买。
                if (tally.bought > boughtBefore) {
                    Chapter next = recomputeNextChapter(subs, plan, host);
                    if (next != null && (nextChapterRegressed(chapter.chapterNo, next.chapterNo)
                            || next.id == chapter.id)) {
                        throw new StepRunner.StepFailure(StepRunner.Kind.MONEY_UNCLEAR,
                                "刚买第" + chapter.chapterNo + "章，账本的下一章却是第"
                                        + next.chapterNo + "章，已停止以防前面漏章或账本发生变化");
                    }
                }
            }
            host.log("    " + result.message);
            if (result.coupons >= 0) account.lastKnownCoupons = result.coupons;
            if (result.vouchers >= 0) account.lastKnownVouchers = result.vouchers;
            if (result.coupons >= 0 || result.vouchers >= 0) {
                final SubscribeTask.Result paidResult = result;
                LedgerEdits.duringRun(host, () -> {
                    accountDao.setBalance(account.id, paidResult.coupons, paidResult.vouchers);
                    return null;
                });
            }

            if (host.isCancelled()) {
                throw new StepRunner.StepFailure(StepRunner.Kind.CANCELLED, "已取消，已发生的购买已记账");
            }
            if (!completed) {
                // 没订下来：这一章一个字都不划掉，换下一个号来试它。绝不改去买后面的章。
                // 2026-09-15：如实报告是哪一种「买不成」—— 页面说余额不足、还是这一章
                // 只能花火券买。以前一律写成「代券不够（页面上说余额不足）」，把火券章
                // 也混进余额不足里，日志看不出真相。
                noteBlocked(host, tally, chapter, name + " 买不起这一章：" + brief(result.message));
                return;
            }
            // 只有已经落到账本的购买或免费归属才有定论；本机下载状态不能使队列越过空章。
            settled.mark(chapter.id);
            doneHere++;
            if (result.status == SubscribeTask.Status.BOUGHT) {
                spentToday += result.costVouchers;
                boughtHere = chapter;   // 下一章买之前要按它清掉留在页面上的勾
            }
        }
    }

    static void requireCatalogCount(int count) throws StepRunner.StepFailure {
        if (count > 0) return;
        throw new StepRunner.StepFailure(StepRunner.Kind.CONFIG, count == 0
                ? "这本书的账本还没有章节：先在订阅页点『同步目录』"
                : "这本书的章节数读不到：先在订阅页点『同步目录』");
    }

    /** 2026-09-14 的来源边界只允许补历史；当前最小无主章有变化就停止，不能拿旧候选继续花券。 */
    static void requireStillUnowned(Chapter expected, Chapter current) throws StepRunner.StepFailure {
        if (expected != null && current != null && expected.id > 0 && expected.novelId > 0
                && expected.chapterNo > 0 && !Texts.isBlank(expected.title)
                && expected.id == current.id && expected.novelId == current.novelId
                && expected.chapterNo == current.chapterNo
                && Objects.equals(expected.title, current.title)
                && Objects.equals(expected.volumeTitle, current.volumeTitle)) return;
        throw new StepRunner.StepFailure(StepRunner.Kind.MONEY_UNCLEAR,
                "买前账本的最小无主章已变化或读不到，未执行购买；请核对订阅清单");
    }

    static void requireCatalog(SubscriptionDao subs, Novel novel) throws StepRunner.StepFailure {
        List<Chapter> chapters = subs.loadChapters(novel.id);
        requireCatalogCount(chapters == null ? -1 : chapters.size());
    }

    private static void invalidateProof(AppDatabase db, StepRunner.Host host,
                                        long accountId, long novelId) {
        LedgerEdits.duringRun(host, () -> { db.auditDao().invalidateAccount(accountId, novelId); return null; });
    }

    private static void invalidateAfterFailure(StepRunner runner, StepRunner.Host host, Plan plan,
                                               Account account, StepRunner.StepFailure reason)
            throws StepRunner.StepFailure {
        if (runner.context() == null) return;
        try {
            invalidateProof(Db.get(runner.context()), host, account.id, plan.novel.id);
        } catch (RuntimeException failure) {
            throw new StepRunner.StepFailure(StepRunner.Kind.MONEY_UNCLEAR,
                    reason.getMessage() + "；核对凭证未能失效：" + failure.getMessage());
        }
    }

    private static boolean applyDuringRun(SubscriptionDao subs, StepRunner.Host host, Tally tally,
                                          Account account, Chapter chapter, SubscribeTask.Result result)
            throws StepRunner.StepFailure {
        try {
            return LedgerEdits.duringRun(host, () -> apply(subs, host, tally, account, chapter, result));
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof StepRunner.StepFailure) throw (StepRunner.StepFailure) cause;
            }
            throw new StepRunner.StepFailure(StepRunner.Kind.MONEY_UNCLEAR,
                    "购买结果写入异常，整趟停止：" + failure.getMessage());
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
     * 曾让缺口悄悄出现：本机已下载但买家未知的章曾被放过去，以及 MIUI 半路杀进程后重发的那一趟。
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

    private static Chapter recomputeNextChapter(SubscriptionDao subs, Plan plan,
                                                StepRunner.Host host)
            throws StepRunner.StepFailure {
        try {
            Chapter next = subs.findNextUnownedChapterFrom(plan.novel.id, plan.novel.startFrom());
            host.log(nextChapterNote(next, plan.novel.startFrom()));
            return next;
        } catch (RuntimeException failure) {
            throw new StepRunner.StepFailure(StepRunner.Kind.MONEY_UNCLEAR,
                    "下一章读不到，已停止后续购买：" + failure.getMessage());
        }
    }

    static String nextChapterNote(Chapter next, int fromNo) {
        if (next == null) return "下一章：当前目录中第 " + fromNo + " 章起已全部有归属";
        return "下一章：第 " + next.chapterNo + " 章"
                + (Texts.isBlank(next.title) ? "（标题未登记）" : "「" + next.title + "」");
    }

    /** 空目录曾被当作「全部买完」；结束摘要也必须先区分没有章节与没有待买章节。 */
    static String currentNextChapterNote(SubscriptionDao subs, Novel novel) {
        List<Chapter> chapters = subs.loadChapters(novel.id);
        if (chapters == null) return "下一章：目录读不到，不能继续购买";
        if (chapters.isEmpty()) return "下一章：目录尚无章节，请先点『同步目录』";
        return nextChapterNote(subs.findNextUnownedChapterFrom(novel.id, novel.startFrom()),
                novel.startFrom());
    }

    /** 2026-09-03 买到第87章却漏了第83章，不能等整队跑完才发现进度倒退。 */
    static boolean nextChapterRegressed(int boughtNo, int nextNo) {
        return boughtNo > 0 && nextNo > 0 && nextNo < boughtNo;
    }

    /**
     * 进下一章之前判断「这个号今天还有额度吗」。返回 null＝还有，否则返回该写进日志的那句话。
     *
     * <p>拆成纯函数是为了能单测：它在真机上要跑一整趟 8 个号才看得到一次。
     *
     * <p><b>2026-09-15 起这里不再猜价格</b>。以前拿「上一章的实付价」当这一章的估价，
     * 余额一低于它就直接换号，而每章单价并不相同（实测 10~14 代券），于是出现
     * 「订 1~2 章、余额还剩着就换号」。章节表里的 {@code priceCoupons} 也不能当价：
     * 那是旧版按火券登记的历史遗留，扫目录只会写 -1。
     * 现在只保留一件确定的事实：账本里今天已经花掉多少（每日上限设为 0 时不拦）。
     * 「买不买得起」交给页面自己回答 —— 出现「余额不足，快去充值吧」才算没钱。
     *
     * @param spentToday 账本里这个号今天已经花掉的代券（含今天更早那一趟）
     * @param cap        每账号每日代券上限；0＝不限
     */
    static String stopBecauseBroke(String name, int spentToday, int cap) {
        if (cap > 0 && spentToday >= cap) {
            return name + " 今天已花 " + spentToday + " 代券，已达到每日上限 "
                    + cap + "，换下一个号";
        }
        return null;
    }

    /** 只传本章还能使用的每日额度；未知单价必须等页面实付读出来后再校验。 */
    static int remainingDailyVouchers(int cap, int spentToday) {
        return cap <= 0 ? -1 : Math.max(0, cap - Math.max(0, spentToday));
    }

    /**
     * 写账本。返回 false 表示这个号别再往下买了。
     *
     * @throws StepRunner.StepFailure {@code MONEY_UNCLEAR} —— 点过「立即下载」而结果不明，
     *                                券可能已经扣了。这一条会被两个队列都当成<b>全局</b>失败，
     *                                整趟就此收工。2026-08-24 那次事故就差这一下：三个号各点了
     *                                一次，两个真扣了券。
     */
    static boolean apply(SubscriptionDao subs, StepRunner.Host host, Tally tally,
                         Account account, Chapter chapter, SubscribeTask.Result result)
            throws StepRunner.StepFailure {
        String name = account.displayName();
        String label = "第" + chapter.chapterNo + "章";
        switch (result.status) {
            case BOUGHT:
                try {
                    subs.upsertPurchase(Purchase.of(account.id, chapter.id,
                            result.cost, result.costVouchers, Purchase.SRC_AUTO));
                } catch (RuntimeException failure) {
                    throw new StepRunner.StepFailure(StepRunner.Kind.MONEY_UNCLEAR,
                            name + " " + label + " 已扣券，但购买记录写入失败，整趟已停止："
                                    + failure.getMessage());
                }
                tally.bought++;
                tally.spent += result.costVouchers;
                if (chapter.chapterNo > tally.maxBoughtNo) tally.maxBoughtNo = chapter.chapterNo;
                host.log("  " + name + " 订到" + label + "，花 " + result.costVouchers + " 代券"
                        + (result.cost > 0 ? "＋" + result.cost + " 火券（不该发生，请核对）" : ""));
                if (result.abortRun) {
                    throw new StepRunner.StepFailure(StepRunner.Kind.MONEY_UNCLEAR,
                            name + " " + label + " 的购买已记账，整趟已停止：" + result.message);
                }
                return true;
            case ALREADY:
                // 免费章：谁登录都看得到，账本补一条，否则每轮都会再来一次。
                // 记成 OWNED 而不是 AUTO：一分券都没花。
                subs.upsertPurchase(Purchase.of(account.id, chapter.id, 0, 0, Purchase.SRC_OWNED));
                tally.ownedAlready++;
                host.log("  " + name + " " + label + " 是免费章，已补记账本");
                return true;
            case DEVICE_HAS_IT:
                List<Long> owners = subs.realPurchasedChapterIds(chapter.novelId);
                String problem = deviceOwnershipProblem(
                        owners != null && owners.contains(chapter.id), chapter.chapterNo);
                if (problem != null) {
                    tally.failed++;
                    tally.notes.add(problem);
                    throw new StepRunner.StepFailure(StepRunner.Kind.MONEY_UNCLEAR, problem);
                }
                host.log("  " + label + " 的归属已在账本里确认，继续重算下一章");
                return true;
            case INSUFFICIENT:
                // 页面上的「余额不足，快去充值吧」或者「实付里有火券/读不到实付」。
                // 两者都不买、不划掉这一章，交给下一个号来试。
                tally.notes.add(name + " 买不起" + label + "：" + brief(result.message));
                host.log("  " + name + " 买不起这一章，换下一个号：" + result.message);
                return false;
            case DAILY_LIMIT:
                tally.notes.add(name + " 每日额度不足（" + label + "）");
                host.log("  " + name + " " + result.message);
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

    static String deviceOwnershipProblem(boolean globallyOwned, int chapterNo) {
        return globallyOwned ? null : "第" + chapterNo
                + "章本机已有但账本没有买家，整本停止；请先核对订阅清单，不能跳过此章";
    }
}
