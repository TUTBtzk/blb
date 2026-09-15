package com.example.blb.auto;

import java.util.ArrayList;
import java.util.List;

/**
 * 一趟跑完之后弹在屏幕上的那句结论。
 *
 * <p>为什么要单独有它，而不直接把日志最后一行拿去弹：日志那一行是给排查用的
 * （「签到 0，已签 6，失败 0；订阅 1 章（花 10 代券）；22 章在这台手机上已下载、
 * 但 8 个号的账本里都查不到归属（第49章、第53章…），一个字都没写，请对着订阅清单核对是谁买的」），
 * 一屏都放不下，而且要紧的结论被对账细节埋在中间。使用者手指动不了 —— 他既拉不开通知栏、
 * 也点不进「运行日志」，弹窗上这几行就是他能读到的全部，所以只留他关心的那几件事：
 * 签到成了几个、哪个号没成、订了几章花了多少券、为什么停下。
 *
 * <p>它<b>不碰任何 Android API</b>（连 {@code TextUtils.join} 都不用），
 * 这样能进普通单元测试 —— 界面代码这个项目里测不了。
 */
public final class RunReport {

    /** 没成的号最多点名几个，再多只报个数：弹窗要一眼读完，不是让人在上面翻。 */
    private static final int MAX_NAMES = 3;

    /** 标题，例如「签到完成」。出了问题会换成「…没全成」，让他一眼知道要不要看下面。 */
    public final String title;
    /** 正文，一行一句。 */
    public final List<String> lines;
    /** 全都顺利＝true。弹窗拿它决定标题的颜色。 */
    public final boolean allGood;

    private RunReport(String title, List<String> lines, boolean allGood) {
        this.title = title;
        this.lines = lines;
        this.allGood = allGood;
    }

    /** 正文拼成一段（弹窗里一个 TextView 就显示得下）。 */
    public String body() {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.toString();
    }

    // ---------- 只签到 ----------

    public static RunReport ofCheckIn(CheckInQueue.Summary s) {
        if (s == null) return failed("签到", "任务异常终止，什么都没做完");
        List<String> lines = new ArrayList<>();
        lines.add(signedLine(s.ok + s.already, s.total));
        addNames(lines, "没签成的", s.failedNames, s.failed);
        if (s.skipped > 0) lines.add("跳过 " + s.skipped + " 个（不是它出问题，是我们决定不跑）");
        boolean good = s.failed == 0 && !s.aborted();
        if (s.aborted()) lines.add("整趟停在这里：" + s.abortReason);
        return new RunReport(good ? "签到完成" : "签到没全成", lines, good);
    }

    // ---------- 每日流程（签到 → 订阅） ----------

    public static RunReport ofDaily(DailyQueue.Summary s) {
        if (s == null) return failed("今天的流程", "任务异常终止，什么都没做完");
        List<String> lines = new ArrayList<>();
        lines.add(signedLine(s.checkedIn + s.alreadySigned, s.total));
        addNames(lines, "没签成的", s.failedNames, s.checkInFailed);
        if (s.skipped > 0) lines.add("跳过 " + s.skipped + " 个（不是它出问题，是我们决定不跑）");
        if (s.subscribing) {
            lines.add(boughtLine(s.bought, s.spent));
            if (s.subscribeFailed > 0) lines.add("订阅没走通 " + s.subscribeFailed + " 个号");
            // 这两句是弹窗里唯一点名到章的地方：整本停在第几章（代券不够也算，不是失败），
            // 以及万一真越过了一章 —— 缺口自己不会浮出来，2026-09-03 那趟就是这么漏掉第83章的。
            if (s.stuckNote != null) lines.add(s.stuckNote);
            if (s.gapNote != null) lines.add(s.gapNote);
        } else if (s.subscribeNote != null) {
            lines.add("这一趟没订阅：" + s.subscribeNote);
        }
        if (s.catalogNote != null && !s.catalogNote.trim().isEmpty()) lines.add(s.catalogNote);
        if (s.nextChapterNote != null && !s.nextChapterNote.trim().isEmpty()) {
            lines.add(s.nextChapterNote);
        }
        boolean good = s.checkInFailed == 0 && s.subscribeFailed == 0
                && s.gapNote == null && !s.aborted();
        if (s.aborted()) lines.add("整趟停在这里：" + s.abortReason);
        return new RunReport(good ? "今天的流程跑完了" : "今天的流程没跑完", lines, good);
    }

    // ---------- 只订阅 ----------

    public static RunReport ofSubscribe(SubscribeQueue.Summary s) {
        if (s == null) return failed("订阅", "任务异常终止，什么都没做完");
        List<String> lines = new ArrayList<>();
        lines.add(boughtLine(s.bought, s.spent));
        if (s.total > 0) lines.add(s.total + " 个号都试过了");
        if (s.failed > 0) lines.add("没走通 " + s.failed + " 个号");
        if (s.stuckNote != null) lines.add(s.stuckNote);
        if (s.gapNote != null) lines.add(s.gapNote);
        if (s.catalogNote != null && !s.catalogNote.trim().isEmpty()) lines.add(s.catalogNote);
        if (s.nextChapterNote != null && !s.nextChapterNote.trim().isEmpty()) {
            lines.add(s.nextChapterNote);
        }
        boolean good = s.failed == 0 && s.gapNote == null && !s.aborted();
        if (s.aborted()) lines.add("整趟停在这里：" + s.abortReason);
        return new RunReport(good ? "订阅完成" : "订阅没做完", lines, good);
    }

    /** 目录同步没有购买环节；结果不能套用「一章都没订到」，把正常扫描误报成订阅失败。 */
    public static RunReport ofCatalog(CatalogQueue.Summary s) {
        if (s == null) return failed("同步目录", "任务异常终止，目录同步未完成");
        List<String> lines = new ArrayList<>();
        if (s.book != null && !s.book.trim().isEmpty()) lines.add("《" + s.book + "》");
        if (s.scanned >= 0) lines.add((s.ok ? "目录 " : "目录读到 ") + s.scanned + " 章");
        if (s.ok || s.added > 0 || s.realigned > 0 || s.backfilled > 0) {
            lines.add("新登记 " + s.added + " 章 · 重排 " + s.realigned + " 章");
            lines.add("免费章 " + s.free + " 章 · 补记 " + s.backfilled + " 条"
                    + (s.accounts > 0 ? "（" + s.accounts + " 个启用账号）" : ""));
        }
        if (s.catalogNote != null && !s.catalogNote.trim().isEmpty()) lines.add(s.catalogNote);
        boolean good = s.ok && !s.aborted();
        if (s.aborted()) lines.add("整趟停在这里：" + s.abortReason);
        else if (!s.ok) lines.add("目录同步未完成");
        lines.add("未购买任何章节");
        return new RunReport(good ? "同步目录完成" : "同步目录没完成", lines, good);
    }

    /** 修账成功与证据不足必须分开说；一条存疑还在，就不能让弹窗宣称清单全对上了。 */
    public static RunReport ofAudit(SubscriptionAuditQueue.Summary s) {
        if (s == null) return failed("核对订阅清单", "任务异常终止，订阅清单核对未完成");
        List<String> lines = new ArrayList<>();
        if (s.book != null && !s.book.trim().isEmpty()) lines.add("《" + s.book + "》");
        lines.add(s.total > 0 ? "已核对 " + s.checked + "/" + s.total + " 个号"
                : "没有启用的账号，什么都没核对");
        lines.add("补记 " + s.backfilled + " 章 · 修正 " + s.deleted + " 条");
        if (s.suspect > 0) lines.add("存疑 " + s.suspect + " 个号，相关差异未继续修正");
        if (s.unverified > 0) lines.add("未核对 " + s.unverified + " 个号");
        if (s.nextChapterNote != null && !s.nextChapterNote.trim().isEmpty()) {
            lines.add(s.nextChapterNote);
        }
        if (s.aborted()) lines.add("整趟停在这里：" + s.abortReason);
        lines.add("未购买任何章节");
        boolean good = s.total > 0 && s.checked == s.total && s.suspect == 0
                && s.unverified == 0 && !s.aborted();
        return new RunReport(good ? "核对订阅清单完成" : "核对订阅清单没全成", lines, good);
    }

    // ---------- 只切号 ----------

    /** 切号那一趟只有一句话（{@link SwitchAccountQueue} 直接返回文案）。 */
    public static RunReport ofSwitch(SwitchAccountQueue.Result result) {
        List<String> lines = new ArrayList<>();
        String message = result == null ? null : result.message;
        lines.add(message == null || message.trim().isEmpty() ? "结果不明" : message.trim());
        boolean good = result != null && result.success;
        return new RunReport(good ? "切号完成" : "切号没完成", lines, good);
    }

    /** 连队列都没跑起来（无障碍没连上、崩了）—— 那种情况只有一句现成的话。 */
    public static RunReport failed(String label, String text) {
        List<String> lines = new ArrayList<>();
        lines.add(text == null || text.trim().isEmpty() ? "结果不明" : text.trim());
        return new RunReport(label + "没跑起来", lines, false);
    }

    // ---------- 几种说法 ----------

    private static String signedLine(int done, int total) {
        if (total <= 0) return "没有启用的账号，什么都没做";
        return "已签到 " + done + "/" + total + " 个号";
    }

    /**
     * 「订了几章、花了多少」。
     *
     * <p>0 章的时候只说「一章都没订到」，<b>绝不</b>替它猜原因：代券不够、目录对不上、
     * 撞验证码看着都一样，猜错会把他引到错的地方去。真原因在下面那几行里。
     */
    private static String boughtLine(int bought, int spent) {
        if (bought <= 0) return "一章都没订到";
        return "订到 " + bought + " 章" + (spent > 0 ? "，花 " + spent + " 代券" : "");
    }

    /** 点名，最多 {@link #MAX_NAMES} 个；名单是空的（只有个数）就退回报个数。 */
    private static void addNames(List<String> lines, String label, List<String> names, int count) {
        if (count <= 0 && (names == null || names.isEmpty())) return;
        if (names == null || names.isEmpty()) {
            lines.add(label + " " + count + " 个");
            return;
        }
        StringBuilder sb = new StringBuilder(label).append("：");
        int shown = Math.min(MAX_NAMES, names.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) sb.append('、');
            sb.append(names.get(i));
        }
        if (names.size() > shown) sb.append(" 等 ").append(names.size()).append(" 个");
        lines.add(sb.toString());
    }
}
