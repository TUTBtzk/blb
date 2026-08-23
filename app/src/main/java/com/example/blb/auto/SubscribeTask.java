package com.example.blb.auto;

import com.example.blb.data.Chapter;
import com.example.blb.data.Novel;
import com.example.blb.util.Texts;

/**
 * 给「当前已登录的这个账号」订阅指定的一章。
 *
 * <p>走的是<b>目录 → 下载 → 选择章节</b>这条批量购买页，而不是阅读器里的付费墙。
 * 实测阅读器是 FLAG_SECURE、而且只暴露 3 个容器节点，付费墙既读不到也点不了；
 * 批量页反过来什么都能读：每一行的锁标记、「已选 N 章」、「需 20 火券」、
 * 「账户余额：0火券/0代券」、「立即下载」，余额不够时还会明确变成「余额不足，快去充值吧」。
 *
 * <p>「买没买到」不靠成功提示的文案判断（那个文案还没验证过），而是看目标行的锁
 * 有没有消失。这样即使提示文案改了，也不会把没买成当成买成；反过来，如果真买成了
 * 但我们没认出来，下一轮开头的锁检查会把它认成「本来就有」，不会重复扣券。
 */
public final class SubscribeTask {

    private static final long NAV_TIMEOUT = 12_000;
    private static final long CONFIRM_TIMEOUT = 20_000;
    /** 选择章节页最多往下翻多少次找目标章。 */
    private static final int MAX_SCROLLS = 40;
    private static final long POLL_MS = 600;

    public enum Status {
        /** 真的订阅成功了（目标行的锁消失）。 */
        BOUGHT,
        /** 干跑：勾中了目标章、读到了价格和余额，没点「立即下载」。 */
        DRY_RUN,
        /** 这个号本来就能看这一章（没有锁）。 */
        ALREADY,
        /** 券不够。 */
        INSUFFICIENT,
        /** 流程没走通。 */
        FAILED
    }

    public static final class Result {
        public Status status = Status.FAILED;
        public int cost;
        public int coupons = -1;
        /** 代券余额，-1 表示没读到。 */
        public int vouchers = -1;
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
                    + "（翻了 " + MAX_SCROLLS + " 屏）。目录里的标号是「"
                    + Texts.cnChapterLabel(chapter.chapterNo) + "」这种中文写法，"
                    + "而且分卷会各自从第一章重排，建议给章节登记上准确标题");
        }

        // 没有锁 = 这个号已经能看了（买过，或者本来免费）。先查这个，才不会重复买。
        if (r.findIn(row.node, Keys.CHAPTER_LOCKED) == null) {
            boolean owned = r.findIn(row.node, Keys.CHAPTER_OWNED) != null;
            return new Result(Status.ALREADY, label + "没有锁"
                    + (owned ? "、还带着「已下载」" : "（可能是买过，也可能本来免费）")
                    + "，这个号已经能看了");
        }

        r.clickNode(label, row.node);
        String selected = r.readText(Keys.SELECTED_COUNT, 3_000);
        if (Texts.parseCount(selected) <= 0) {
            // 点行没勾上（菠萝包里点复选框图标是没反应的，只有点整行才行），
            // 这时候继续往下点「立即下载」等于对着未知的勾选状态花钱，必须停。
            leave(r);
            return new Result(Status.FAILED,
                    "点了" + label + "但「已选」没变（读到：" + text(selected) + "），没敢往下走");
        }

        int price = Texts.parseCount(r.readText(Keys.PRICE_HINT, 3_000));
        Texts.Balance balance = r.readBalance(3_000);

        if (r.findAny(Keys.INSUFFICIENT_COUPONS) != null) {
            Result result = new Result(Status.INSUFFICIENT,
                    label + " 需 " + (price > 0 ? price + " 券" : "?") + "，" + balance.describe());
            result.applyBalance(balance);
            leave(r);
            return result;
        }

        if (dryRun) {
            Result result = new Result(Status.DRY_RUN, "干跑：已勾中" + label
                    + "，" + (price > 0 ? "需 " + price + " 券，" : "") + balance.describe()
                    + "，没点「立即下载」");
            result.cost = Math.max(price, 0);
            result.applyBalance(balance);
            leave(r);
            return result;
        }
        return buy(r, chapter, label, price, balance);
    }

    /**
     * 真买：点「立即下载」→（如果有）二次确认 → 等目标行的锁消失。
     *
     * <p>没等到锁消失就算 FAILED，宁可漏记也不错记：错记成功会让我们以为买到了、
     * 后面真去读却没有；漏记的代价只是下一轮再走一遍，而下一轮开头的锁检查会拦住重复扣券。
     */
    private static Result buy(StepRunner r, Chapter chapter, String label,
                              int price, Texts.Balance balance) throws StepRunner.StepFailure {
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

            StepRunner.Outcome row = findRow(r, chapter, label);
            if (row != null && r.findIn(row.node, Keys.CHAPTER_LOCKED) == null) {
                boolean owned = r.findIn(row.node, Keys.CHAPTER_OWNED) != null;
                Result result = new Result(Status.BOUGHT, label + "的锁已消失"
                        + (owned ? "、并且标成「已下载」" : "")
                        + "，订阅到手" + (sawDone ? "" : "（界面没给成功提示，按锁判定）"));
                result.cost = price > 0 ? price : chapter.priceCoupons;
                result.applyBalance(r.readBalance(2_000));
                return result;
            }
            sleep(POLL_MS);
        }

        Result result = new Result(Status.FAILED, "点了「立即下载」但 "
                + CONFIRM_TIMEOUT / 1000 + " 秒内没看到" + label + "解锁"
                + (sawDone ? "（有成功提示，但锁还在，请手动核对）" : "")
                + "。券可能已经扣了，请手动确认；下一轮会先查锁，不会重复买");
        leave(r);
        return result;
    }

    /** 退出选择章节页。勾选状态随页面一起丢弃，比逐个取消勾选可靠。 */
    private static void leave(StepRunner r) throws StepRunner.StepFailure {
        r.back();
    }

    /** 起菠萝包 → 搜书 → 进目录 → 进「下载（选择章节）」页。已经在里面了就不重复走。 */
    private static void openChapterPicker(StepRunner r, Novel novel) throws StepRunner.StepFailure {
        r.launchTarget(25_000);
        if (r.findAny(Keys.SELECTED_COUNT) != null) return; // 已经在选择章节页

        if (r.findAny(Keys.DOWNLOAD_ENTRY) == null) {
            if (r.findAny(Keys.CATALOG_ENTRY) == null) openNovel(r, novel);
            r.click(Keys.CATALOG_ENTRY, NAV_TIMEOUT);
        }
        r.click(Keys.DOWNLOAD_ENTRY, NAV_TIMEOUT);
        r.waitFor(Keys.SELECTED_COUNT, NAV_TIMEOUT);
    }

    private static void openNovel(StepRunner r, Novel novel) throws StepRunner.StepFailure {
        if (Texts.isBlank(novel.title)) {
            throw new StepRunner.StepFailure(StepRunner.Kind.CONFIG, "目标小说没有书名，没法搜");
        }
        backToSearchEntry(r);
        r.click(Keys.SEARCH_ENTRY, NAV_TIMEOUT);
        r.setText(Keys.SEARCH_FIELD, novel.title, NAV_TIMEOUT);

        // 搜索页没有可见的「搜索」按钮，输入后结果自己出来；有按钮的版本就顺手点一下。
        StepRunner.Outcome submit = r.findAny(Keys.SEARCH_SUBMIT);
        if (submit != null) r.clickNode(Keys.SEARCH_SUBMIT, submit.node);

        StepRunner.Outcome result = r.scrollToText("搜索结果 " + novel.title, novel.title, 3);
        if (result == null) {
            throw new StepRunner.StepFailure(StepRunner.Kind.TIMEOUT,
                    "搜不到《" + novel.title + "》，或结果里书名对不上");
        }
        r.clickNode("搜索结果 " + novel.title, result.node);
        r.waitFor(Keys.CATALOG_ENTRY, NAV_TIMEOUT);
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
     * 只看当前屏。标题优先：菠萝包按卷重排标号（番外卷也从「第一章」开始），
     * 光靠标号可能撞上另一卷的同号章节，标题才是唯一的。
     */
    private static StepRunner.Outcome findRow(StepRunner r, Chapter chapter, String label)
            throws StepRunner.StepFailure {
        if (!Texts.isBlank(chapter.title)) {
            StepRunner.Outcome hit = r.findByText(label, chapter.title.trim());
            if (hit != null) return hit;
        }
        return r.findByRegex(label, Texts.chapterLabelRegex(chapter.chapterNo));
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
