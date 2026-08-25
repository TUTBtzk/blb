package com.example.blb.auto;

import com.example.blb.data.Chapter;
import com.example.blb.data.Novel;
import com.example.blb.util.Texts;

/**
 * 给「当前已登录的这个账号」订阅指定的一章。
 *
 * <p>走的是<b>目录 → 下载 → 选择章节</b>这条批量购买页，而不是阅读器里的付费墙。
 * 实测阅读器是 FLAG_SECURE、而且只暴露 3 个容器节点，付费墙既读不到也点不了；
 * 批量页反过来什么都能读：每一行的标记、「已选 N 章」、「需 20 火券」、
 * 「账户余额：0火券/0代券」、「立即下载」，余额不够时还会明确变成「余额不足，快去充值吧」。
 *
 * <p><b>「买没买到」的判据是 2026-08-24 那次真买用 40 代券换来的</b>（详见
 * {@link ChapterRowState}）：付费章买完之后锁并不会消失，只是从「锁上的锁」变成
 * 「打开的锁」，而这两种锁在无障碍树里一模一样。旧判据等锁消失，于是券扣了却判成失败、
 * 账本一条没记。现在改成两条独立证据，任一成立就算买到：
 * <ul>
 *   <li>目标行出现「已下载」（{@link Keys#CHAPTER_OWNED}）；</li>
 *   <li>页面上那行「账户余额」里的<b>代券掉了</b> —— 钱真的付出去了。</li>
 * </ul>
 *
 * <p>两条都没等到就是 {@link Status#FAILED}，而且<b>整趟收工</b>（{@link Result#abortRun}）：
 * 点过「立即下载」而结果不明，说明券有可能已经扣了；这时候换下一个号接着点，就是那次
 * 「三个号各点了一次、两个真扣了券、保险丝一次没跳」的事故。宁可停下让人核对。
 */
public final class SubscribeTask {

    private static final long NAV_TIMEOUT = 12_000;
    /**
     * 点了「立即下载」之后最多等多久看证据。
     *
     * <p>放宽到 30 秒是因为等不到的代价变大了：现在「结果不明」会让整趟停下
     * （{@link Result#abortRun}），而买完之后「已下载」要等这一章真的下载完才出现。
     * 宁可多等 10 秒，也不要把一次买成误判成事故。
     */
    private static final long CONFIRM_TIMEOUT = 30_000;
    /** 选择章节页最多往下翻多少次找目标章。 */
    private static final int MAX_SCROLLS = 40;
    private static final long POLL_MS = 600;

    public enum Status {
        /** 真的订阅成功了（目标行出现「已下载」，或者页面上的代券掉了）。 */
        BOUGHT,
        /** 干跑：勾中了目标章、读到了价格和余额，没点「立即下载」。 */
        DRY_RUN,
        /** 免费章，谁登录都看得到，不用花券。 */
        ALREADY,
        /**
         * 付费章、本机已下载，但<b>不知道是哪个号买的</b>（「已下载」8 个号共用）。
         * 既不记账本、也买不了（没有勾选圈），跳过这一章接着往下试。
         */
        DEVICE_HAS_IT,
        /** 券不够。 */
        INSUFFICIENT,
        /** 流程没走通。 */
        FAILED
    }

    public static final class Result {
        public Status status = Status.FAILED;
        /** 实付火券。守住「只花代券」的话它永远是 0。 */
        public int cost;
        /** 实付代券。真正花掉的就是这一项。 */
        public int costVouchers;
        public int coupons = -1;
        /** 代券余额，-1 表示没读到。 */
        public int vouchers = -1;
        /**
         * 点过「立即下载」而结果不明 —— 券可能已经扣了。整趟必须停下，不许换号接着点。
         *
         * <p>这一位是 2026-08-24 那次事故的直接补丁：失败不占真买保险丝的额度，
         * 于是三个号各点了一次「立即下载」，两个真扣了券，账本一条没记。
         */
        public boolean abortRun;
        public String message;

        Result(Status status, String message) {
            this.status = status;
            this.message = message;
        }

        void applyBalance(Texts.Balance balance) {
            coupons = balance.fire;
            vouchers = balance.voucher;
        }
    }

    private SubscribeTask() {
    }

    public static Result run(StepRunner r, Novel novel, Chapter chapter, boolean dryRun)
            throws StepRunner.StepFailure {
        openChapterPicker(r, novel);

        String label = "第" + chapter.chapterNo + "章";
        StepRunner.Outcome row = locateChapter(r, chapter, label);
        if (row == null) {
            return new Result(Status.FAILED, "选择章节页里找不到" + label
                    + "（翻了 " + MAX_SCROLLS + " 屏）。账本里这一章记的标题是「"
                    + text(chapter.title) + "」，跟界面上的行文本对不上就找不到；"
                    + "正常情况下章节是扫目录自动登记的，标题就是行文本本身");
        }

        // 先看这一行要不要花券。判据只有两条靠得住（见 ChapterRowState）：
        // 「没有锁」＝免费章，谁都看得到；「有锁 + 已下载」＝本机有文件但买家可能是别的号。
        ChapterRowState state = ChapterRowState.read(r, row.node);
        if (state.free()) {
            return new Result(Status.ALREADY, label + " " + state.describe()
                    + " → 免费章，谁登录都看得到，不用花券");
        }
        if (state.deviceHasIt()) {
            // 「已下载」是本机状态、8 个号共用：2026-08-25 就是按它把皓平买的第49章
            // 回填给了五杯半雪碧。所以这里一个字都不写账本，也没法买（右边没有勾选圈）。
            return new Result(Status.DEVICE_HAS_IT, label + " " + state.describe()
                    + " → 这台手机上已经有这一章了，但看不出是哪个号买的：不记账本、也买不了"
                    + "（没有勾选圈，点下去「已选」是 0 章），跳过。"
                    + "请在「我的 → 代券 → 订阅清单」里核对是谁买的");
        }

        r.clickNode(label, row.node);
        String selected = r.readText(Keys.SELECTED_COUNT, 3_000);
        int selectedCount = Texts.parseCount(selected);
        if (selectedCount != 1) {
            // 必须正好一章。0 = 点行没勾上（菠萝包里点复选框图标是没反应的，只有点整行才行）；
            // 大于 1 = 勾中的不是单独一章 —— 卷标题行和「全选」都会一下勾上一整批，
            // 那时候按「立即下载」会把一整卷买下来。两种都不许往下走。
            leave(r);
            return new Result(Status.FAILED, "点了" + label + "之后「已选」是 "
                    + text(selected) + "（读成 " + selectedCount + " 章，要的是正好 1 章），"
                    + (selectedCount > 1 ? "勾中的不止这一章，没敢按「立即下载」"
                    : "勾选没生效（这一行 " + state.describe() + "），没敢往下走"));
        }

        int price = Texts.parseCount(r.readText(Keys.PRICE_HINT, 3_000));
        Texts.Balance balance = r.readBalance(3_000);
        String payText = r.readText(Keys.PAY_DETAIL, 3_000);
        Texts.Payment pay = Texts.parsePayment(payText);

        if (r.findAny(Keys.INSUFFICIENT_COUPONS) != null) {
            // 页面自己说余额不足。这时候「立即下载」实测是直接消失的，物理上买不了。
            Result result = new Result(Status.INSUFFICIENT,
                    label + " 页面显示余额不足（需 " + (price > 0 ? price + " 券" : "?")
                            + "，" + balance.describe() + "），换下一个号");
            result.applyBalance(balance);
            unselect(r, row.node, label);   // 换号前把勾撤掉，免得留给下一趟
            leave(r);
            return result;
        }

        if (!pay.vouchersOnly()) {
            // 「只花代券」这条硬约束唯一的判据。读不到实付一律当成不能买 ——
            // 编一个 0 出来就等于拿用户的火券去赌。
            Result result = new Result(Status.INSUFFICIENT, label + " 不能只用代券买："
                    + (pay.known() ? pay.describe() + "，实付里有火券"
                    : "读不到「实付」那一行（读到：" + text(payText) + "）")
                    + "，" + balance.describe() + "，换下一个号");
            result.applyBalance(balance);
            unselect(r, row.node, label);
            leave(r);
            return result;
        }

        if (dryRun) {
            Result result = new Result(Status.DRY_RUN, "干跑：已勾中" + label
                    + "（已选 1 章）"
                    + (price > 0 ? "，标价 " + price + " 券" : "")
                    + "，" + pay.describe() + "，" + balance.describe()
                    + " → 判定「只花代券，买得起」，但没点「立即下载」");
            result.cost = pay.fire;
            result.costVouchers = pay.voucher;
            result.applyBalance(balance);
            // 同一个号接着买下一章时还会回到这一页，勾必须先取消掉 —— 取消不掉就别往下走。
            int left = unselect(r, row.node, label);
            if (left != 0) {
                Result stuck = new Result(Status.FAILED, label
                        + " 判定「买得起」，但取消勾选之后「已选」还是 " + left
                        + " 章。带着多余的勾去买下一章，会一次买下不止一章 —— 这个号就此停下");
                stuck.applyBalance(balance);
                leave(r);
                return stuck;
            }
            leave(r);
            return result;
        }
        return buy(r, chapter, label, pay, balance);
    }

    /**
     * 把刚勾上的那一章取消掉，返回取消之后「已选」还剩几章（读不到当 -1）。
     *
     * <p>为什么非要有这一步：这一页的勾选是留在页面上的，退出去再进来不保证清空。
     * 一个号连着买好几章时，上一章的勾没取消，下一章进来「已选」就是 2 —— 那时候按
     * 「立即下载」会<b>一次买下两章</b>，多花的券要不回来。所以这件事必须核对，不能想当然。
     */
    private static int unselect(StepRunner r, NodeView row, String label)
            throws StepRunner.StepFailure {
        if (!r.pressOrLog("取消勾选 " + label, row)) return -1;
        return Texts.parseCount(r.readText(Keys.SELECTED_COUNT, 3_000));
    }

    /**
     * 真买：点「立即下载」→（如果有）二次确认 → 等两条独立证据里的任意一条。
     *
     * <p>证据一是目标行出现「已下载」，证据二是页面上那行「账户余额」里的<b>代券掉了</b>。
     * 之所以要第二条：2026-08-24 那次真买两个号的券都扣了（53→33、23→3），而当时唯一的判据
     * 是「等锁消失」，锁却永远不消失 —— 判成失败、账本一条没记，于是账面和现实对不上，
     * 「不多订」这条约束当场破掉。钱动了就是买成了，这是最硬的一条。
     *
     * <p>两条都没等到 → {@link Status#FAILED} 且 {@link Result#abortRun}：券可能已经扣了，
     * 后面的号一个都不许再点「立即下载」。
     */
    private static Result buy(StepRunner r, Chapter chapter, String label,
                              Texts.Payment pay, Texts.Balance balance)
            throws StepRunner.StepFailure {
        int before = balance.voucher;   // 点之前页面上的代券，-1＝没读到
        r.click(Keys.SUBSCRIBE_BUTTON, NAV_TIMEOUT);
        r.guardCaptcha();

        boolean confirmed = false;
        boolean sawDone = false;
        long deadline = System.currentTimeMillis() + CONFIRM_TIMEOUT;
        while (System.currentTimeMillis() < deadline) {
            if (r.findAny(Keys.INSUFFICIENT_COUPONS) != null) {
                Result result = new Result(Status.INSUFFICIENT,
                        label + " 券不够，" + balance.describe());
                result.applyBalance(balance);
                leave(r);
                return result;
            }
            if (!confirmed) {
                StepRunner.Outcome c = r.findAny(Keys.SUBSCRIBE_CONFIRM);
                if (c != null) {
                    r.clickNode(Keys.SUBSCRIBE_CONFIRM, c.node);
                    r.guardCaptcha();
                    confirmed = true;
                    continue;
                }
            }
            if (!sawDone) sawDone = r.findAny(Keys.SUBSCRIBE_DONE) != null;

            // 证据一：目标行出现「已下载」。
            StepRunner.Outcome row = findRow(r, chapter, label);
            ChapterRowState state = row == null ? null : ChapterRowState.read(r, row.node);
            // 证据二：页面上的代券掉了 —— 钱真的付出去了。
            Texts.Balance now = r.readBalance(1_000);
            int paid = before >= 0 && now.voucher >= 0 ? before - now.voucher : -1;

            if ((state != null && state.downloaded) || paid > 0) {
                Result result = new Result(Status.BOUGHT, label + " 订到手："
                        + (state != null && state.downloaded ? "这一行已经标成「已下载」" : "")
                        + (paid > 0 ? (state != null && state.downloaded ? "，而且" : "")
                        + "页面上的代券从 " + before + " 掉到 " + now.voucher
                        + "（真扣了 " + paid + " 代券）" : "")
                        + "（" + pay.describe() + "）"
                        + (sawDone ? "" : "（界面没给成功提示，按上面这些证据判定）"));
                // 花了多少以实际掉的那个数为准；读不到才退回「实付」那一行。
                result.cost = pay.fire;
                result.costVouchers = paid > 0 ? paid : pay.voucher;
                if (paid > 0 && pay.known() && paid != pay.voucher) {
                    result.message += "。注意：「实付」写的是 " + pay.voucher
                            + " 代券，实际掉了 " + paid + " 代券，按实际掉的记账";
                }
                result.applyBalance(now.known() ? now : r.readBalance(2_000));
                return result;
            }
            sleep(POLL_MS);
        }

        Result result = new Result(Status.FAILED, "点了「立即下载」但 "
                + CONFIRM_TIMEOUT / 1000 + " 秒内既没看到" + label + "标成「已下载」，"
                + "也没看到代券减少（点之前是 " + (before >= 0 ? before + " 代券" : "读不到") + "）"
                + (sawDone ? "，虽然有成功提示" : "")
                + "。券有可能已经扣了 —— 整趟就此停下，请人工核对余额和这一章的状态；"
                + "后面的号一个都不再点「立即下载」");
        result.abortRun = true;
        result.applyBalance(r.readBalance(2_000));
        leave(r);
        return result;
    }

    /** 退出选择章节页。勾选状态随页面一起丢弃，比逐个取消勾选可靠。 */
    private static void leave(StepRunner r) throws StepRunner.StepFailure {
        r.back();
    }

    /**
     * 起菠萝包 → 搜书 → 进目录 → 进「下载（选择章节）」页。已经在里面了就不重复走。
     *
     * <p>public 是为了 debug 构建里的现场取样器能复用同一条路 —— 取样器自己再写一遍搜书，
     * 就等于用另一条路去解释这条路上看到的现象。
     */
    public static void openChapterPicker(StepRunner r, Novel novel) throws StepRunner.StepFailure {
        r.launchTarget(25_000);
        if (r.findAny(Keys.SELECTED_COUNT) != null) return; // 已经在选择章节页

        if (r.findAny(Keys.DOWNLOAD_ENTRY) == null) {
            if (r.findAny(Keys.CATALOG_ENTRY) == null) openNovel(r, novel);
            r.click(Keys.CATALOG_ENTRY, NAV_TIMEOUT);
        }
        r.click(Keys.DOWNLOAD_ENTRY, NAV_TIMEOUT);
        r.waitFor(Keys.SELECTED_COUNT, NAV_TIMEOUT);
    }

    /**
     * 搜书并点进书籍详情页。
     *
     * <p><b>只认「书名画在书名控件上」的那一行</b>（{@link Keys#NOVEL_TITLE_ROW}）。真机
     * 2026-08-24 校准出来的两个坑，缺一个就点不进详情页：
     * <ul>
     *   <li>搜索输入框 {@code inputSearch} 的文本也正好等于刚输进去的书名 —— 纯按文本找会先
     *       命中输入框，点它什么都不会发生，然后一直等不到「目录」。这是之前失败的全部原因。</li>
     *   <li>建议列表第一行是「以“书名”为关键字进行搜索」，包含匹配会抢先命中它；而它底下还
     *       压着「历史搜索」里同名的标签（看不见，点了也是空点）。</li>
     * </ul>
     *
     * <p>建议列表里的书名行点下去<b>直接进详情页</b>，不必经过结果页；进不去才回头点
     * 「以…为关键字进行搜索」提交一次，再在结果页找 {@code tvbBookTitle}。
     */
    private static void openNovel(StepRunner r, Novel novel) throws StepRunner.StepFailure {
        if (Texts.isBlank(novel.title)) {
            throw new StepRunner.StepFailure(StepRunner.Kind.CONFIG, "目标小说没有书名，没法搜");
        }
        backToSearchEntry(r);
        r.click(Keys.SEARCH_ENTRY, NAV_TIMEOUT);
        r.setText(Keys.SEARCH_FIELD, novel.title, NAV_TIMEOUT);
        r.sleepHuman();

        // 先在建议下拉里点书名那一行。
        String label = "书名 " + novel.title;
        if (tryOpen(r, label, novel.title)) return;

        // 没进去：提交一次搜索，再在结果页找。这个 App 没有可见的「搜索」键，
        // 提交靠点「以“…”为关键字进行搜索」那一条（search_submit 留着兜底）。
        StepRunner.Outcome submit = r.findAny(Keys.SEARCH_SUBMIT, Keys.SEARCH_KEYWORD_ROW);
        if (submit != null) {
            r.log("  改从结果页找：先点「" + submit.key + "」提交搜索");
            r.clickNode(submit.key, submit.node);
            r.sleepHuman();
            if (tryOpen(r, label, novel.title)) return;
        }

        throw new StepRunner.StepFailure(StepRunner.Kind.TIMEOUT,
                "搜《" + novel.title + "》之后，建议下拉和结果页里都没有「书名控件上文本正好等于"
                        + "书名」的那一行（novel_title_row 一条候选都没命中；刻意不按纯文本找："
                        + "搜索框自己的文本也等于书名，点它没有任何反应）");
    }

    /** 点书名那一行，并确认真的进了详情页（详情页才有「目录」入口）。 */
    private static boolean tryOpen(StepRunner r, String label, String title)
            throws StepRunner.StepFailure {
        StepRunner.Outcome hit = r.scrollToRowWithText(Keys.NOVEL_TITLE_ROW, label, title, 3);
        if (hit == null) return false;
        r.clickNode(label, hit.node);
        try {
            r.waitFor(Keys.CATALOG_ENTRY, NAV_TIMEOUT);
            return true;
        } catch (StepRunner.StepFailure e) {
            if (e.kind != StepRunner.Kind.TIMEOUT) throw e;
            r.log("  点了「" + title + "」但没进详情页（等「目录」超时），退回去再试一条");
            r.back();
            return false;
        }
    }

    /** 搜索入口在书库页。停在别的页面时先退出来，再切到书库。 */
    private static void backToSearchEntry(StepRunner r) throws StepRunner.StepFailure {
        for (int i = 0; i < 3; i++) {
            if (r.findAny(Keys.SEARCH_ENTRY) != null) return;
            StepRunner.Outcome lib = r.findAny(Keys.LIBRARY_TAB);
            if (lib != null) {
                r.clickNode(Keys.LIBRARY_TAB, lib.node);
                if (r.findAny(Keys.SEARCH_ENTRY) != null) return;
            }
            r.back();
        }
    }

    /**
     * 在选择章节页里定位目标章：每翻一屏都同时试「章节标题」和「标号」，而不是先按标题
     * 翻完整本再从底部按标号翻第二遍。
     */
    private static StepRunner.Outcome locateChapter(StepRunner r, Chapter chapter, String label)
            throws StepRunner.StepFailure {
        for (int i = 0; i <= MAX_SCROLLS; i++) {
            StepRunner.Outcome hit = findRow(r, chapter, label);
            if (hit != null) return hit;
            if (i == MAX_SCROLLS || !r.scrollForward()) return null;
            r.sleepHuman();
        }
        return null;
    }

    /**
     * 只看当前屏。<b>标题必须完全相等</b>：章节是扫目录自动登记的，账本里的标题就是那一行
     * 的全文（「67   周日工作」），所以相等匹配一定能命中。
     *
     * <p>刻意不用包含匹配 —— 「71留宿之夜」是「171留宿之夜」的子串，包含匹配会认错行；
     * 也不再退回按「第 N 章」找：这一页的行文本里根本没有「第…章」三个字，
     * 而分卷会各自从 1 重排标号，按标号找注定会撞上另一卷的同号章节。
     */
    private static StepRunner.Outcome findRow(StepRunner r, Chapter chapter, String label)
            throws StepRunner.StepFailure {
        if (Texts.isBlank(chapter.title)) return null;
        return r.findExactText(label, chapter.title.trim());
    }

    private static String text(String s) {
        return Texts.isBlank(s) ? "读不到" : s.trim();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
