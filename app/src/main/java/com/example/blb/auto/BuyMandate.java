package com.example.blb.auto;

import android.content.Context;

import com.example.blb.util.Prefs;
import com.example.blb.util.Texts;

import java.util.Calendar;
import java.util.Locale;

/**
 * 常驻真买授权：「从现在起 N 天内，每天最多真买 M 章」。
 *
 * <p>为什么非要它 —— 这个 App 的使用者按不动屏幕。原来「允许真实购买」是设置页上一颗开关
 * 加一次确认对话框，他<b>按不开也关不掉</b>；而每买完一趟又会自动回到干跑
 * （{@link SubscribeRun#restoreDryRunAfterRealBuy}，真买是一次性授权），于是每天定时那趟
 * 永远只会干跑 —— 签到和广告挣回来的代券一分也花不出去。这就是「自动订阅完全用不了」
 * 最后剩下的那一环。
 *
 * <p>直接把那颗布尔改成默认 true 是不行的：那等于把「无人值守、每天自动花钱」变成永久状态，
 * 选择器一旦错位就没人叫停。所以这里给授权装上两条自己会到头的边界：
 * <ul>
 *   <li><b>到期</b>：过了 {@code until} 自动失效，回到干跑，不需要任何人去按什么；</li>
 *   <li><b>当日额度</b>：每天最多真买 {@code perDay} 章，买满就停，第二天自己回满。</li>
 * </ul>
 * 额度是<b>买成一章就当场扣</b>的（{@link #noteBought}），不是跑完才结算 —— MIUI 会在半路
 * 连进程一起杀掉（SwipeUpClean）然后由系统把队列重发一遍，跑完才结算的话那一趟买掉的章
 * 就白买了额度，重发的那趟会拿着满额度再买一遍。
 *
 * <p>授权本身也算「明确确认过一次」，所以 {@link #grant} 顺手把
 * {@link Prefs#setRealBuyConfirmed} 打开 —— 它比那颗布尔严：那颗永久有效，这条会自己到期。
 * {@link #revoke} 则把两者一起收回并拨回干跑。
 *
 * <p>只有<b>每日整套流程</b>那条路会被它武装（{@link AutomationService} 的 DAILY 与
 * {@link com.example.blb.work.DailyCheckInWorker}）。订阅队列那条路不武装：{@code RUN_SUBSCRIBE}
 * 探针是强制干跑用的，被自动武装成真买就成了骗人的干跑。
 */
public final class BuyMandate {

    /** 最长授权天数。再长就等于「永久允许自动花钱」，那正是要避免的东西。 */
    public static final int MAX_DAYS = 30;
    /** 每天最多准买几章的上限。20 章＝400 代券，远超任何一个号一天挣得到的量。 */
    public static final int MAX_PER_DAY = 20;

    /** 授权还在有效期内吗。 */
    public final boolean active;
    /** 今天还准真买几章（0＝没授权、已过期，或今天的额度用完了）。 */
    public final int chaptersLeft;
    public final long until;
    public final int perDay;
    public final int doneToday;
    /** 给日志和 STATUS 用的一句话，永远说得出「为什么能买／为什么不能买」。 */
    public final String reason;

    private BuyMandate(boolean active, int chaptersLeft, long until, int perDay, int doneToday,
                       String reason) {
        this.active = active;
        this.chaptersLeft = chaptersLeft;
        this.until = until;
        this.perDay = perDay;
        this.doneToday = doneToday;
        this.reason = reason;
    }

    /** 现在准不准真买、还准买几章。可以真买＝{@code active && chaptersLeft > 0}。 */
    public boolean canBuyNow() {
        return active && chaptersLeft > 0;
    }

    /**
     * 判定本身，纯函数（时间和「今天」都从外面传进来，所以能单测）。
     *
     * @param noteYmd   当日已买章数记在哪一天名下
     * @param doneToday 那一天已经真买了几章
     */
    static BuyMandate decide(long until, int perDay, String noteYmd, int doneToday,
                             long now, String todayYmd) {
        int quota = Math.max(0, perDay);
        int done = todayYmd != null && todayYmd.equals(noteYmd) ? Math.max(0, doneToday) : 0;
        if (until <= 0L || quota <= 0) {
            return new BuyMandate(false, 0, 0L, 0, done,
                    "没有常驻真买授权 —— 每日流程只会干跑（一分券都不花）");
        }
        if (now > until) {
            return new BuyMandate(false, 0, until, quota, done,
                    "常驻真买授权已经过期（到期时间 " + stamp(until) + "）—— 每日流程只会干跑");
        }
        int left = Math.max(0, quota - done);
        if (left <= 0) {
            return new BuyMandate(true, 0, until, quota, done,
                    "今天的真买额度用完了（已买 " + done + " / " + quota + " 章，授权到 "
                            + stamp(until) + "）—— 今天剩下的流程只会干跑");
        }
        return new BuyMandate(true, left, until, quota, done,
                "常驻真买授权有效：今天还准真买 " + left + " 章（额度 " + quota + " 章/天，已买 "
                        + done + "，授权到 " + stamp(until) + "）");
    }

    /** 读当前状态。 */
    public static BuyMandate read(Context c) {
        return decide(Prefs.buyMandateUntil(c), Prefs.buyMandatePerDay(c),
                Prefs.buyMandateYmd(c), Prefs.buyMandateDone(c),
                System.currentTimeMillis(), Texts.todayYmd());
    }

    /**
     * 授权：从现在起 {@code days} 天内，每天最多真买 {@code perDay} 章。
     *
     * <p><b>不</b>清掉今天已经买掉的那几章：重新授权不该把已经花出去的券一笔勾销，
     * 否则「再授权一次」就成了绕过当日额度的办法。
     */
    public static BuyMandate grant(Context c, int days, int perDay) {
        int d = clamp(days, 1, MAX_DAYS);
        int n = clamp(perDay, 1, MAX_PER_DAY);
        Prefs.setBuyMandate(c, endOfDay(System.currentTimeMillis(), d - 1), n);
        // 授权就是「明确确认过一次」，而且比设置页那颗永久布尔严：它会自己到期。
        Prefs.setRealBuyConfirmed(c, true);
        return read(c);
    }

    /** 收回授权，并立刻拨回干跑 —— 撤销要当场生效，不能等下一趟。 */
    public static void revoke(Context c) {
        Prefs.setBuyMandate(c, 0L, 0);
        Prefs.setRealBuyConfirmed(c, false);
        Prefs.setDryRun(c, true);
    }

    /**
     * 买成 {@code n} 章，当场从今天的额度里扣掉。
     *
     * <p>由 {@link SubscribeRun} 在写完账本之后立刻调用：进程随时可能被 MIUI 杀掉，
     * 扣额度这件事晚一步就等于没扣。
     */
    public static void noteBought(Context c, int n) {
        if (n <= 0) return;
        String today = Texts.todayYmd();
        int done = today.equals(Prefs.buyMandateYmd(c)) ? Prefs.buyMandateDone(c) : 0;
        Prefs.setBuyMandateDone(c, today, done + n);
    }

    /**
     * 按授权武装这一趟的真买。返回 true＝这一趟从干跑改成了真买。
     *
     * <p>只有干跑状态才会被武装。已经是真买（一次性授权，比如 {@code BUY_ONE_CHAPTER}）
     * 就不插手：那一趟的章数上限是那条命令自己定的，别被这里改掉。
     *
     * <p>武装出来的上限是<b>今天还剩的额度</b>，交给现成的保险丝
     * （{@link Prefs#realBuyLimit} → {@link SubscribeRun.Plan#reachedBuyLimit}）执行；
     * 跑完由 {@link SubscribeRun#restoreDryRunAfterRealBuy} 自动拨回干跑。
     */
    public static boolean arm(Context c, StepRunner.Host host) {
        if (!Prefs.isDryRun(c)) {
            log(host, "这一趟本来就是真买（一次性授权），常驻授权不插手");
            return false;
        }
        BuyMandate m = read(c);
        if (!m.canBuyNow()) {
            log(host, m.reason);
            return false;
        }
        Prefs.setRealBuyLimit(c, m.chaptersLeft);
        Prefs.setDryRun(c, false);
        log(host, m.reason + " —— 这一趟按授权真买，最多 " + m.chaptersLeft
                + " 章；只花代券，实付里出现火券一律当买不起，买满或跑完自动回到干跑");
        return true;
    }

    /** 一行状态，给 STATUS 那条 adb 命令用。 */
    public String describe() {
        return reason;
    }

    private static void log(StepRunner.Host host, String message) {
        if (host != null) host.log(message);
    }

    /** 授权到「第 plusDays 天」的 23:59:59 —— 按天授权就该在那天结束时到期，而不是差几小时。 */
    static long endOfDay(long now, int plusDays) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.add(Calendar.DAY_OF_YEAR, Math.max(0, plusDays));
        c.set(Calendar.HOUR_OF_DAY, 23);
        c.set(Calendar.MINUTE, 59);
        c.set(Calendar.SECOND, 59);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static String stamp(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return String.format(Locale.US, "%04d-%02d-%02d %02d:%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
