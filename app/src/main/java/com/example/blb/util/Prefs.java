package com.example.blb.util;

import android.content.Context;
import android.content.SharedPreferences;

/** 少量开关设置。凭据不放这里，走 Room + KeyStoreBox。 */
public final class Prefs {

    private static final String NAME = "blb_prefs";

    private static final String KEY_DRY_RUN = "subscribe_dry_run";
    private static final String KEY_DAILY_ENABLED = "daily_enabled";
    private static final String KEY_DAILY_HOUR = "daily_hour";
    private static final String KEY_REAL_BUY_CONFIRMED = "real_buy_confirmed";
    private static final String KEY_DAILY_SPEND_CAP = "daily_spend_cap";
    private static final String KEY_MAX_CHAPTERS_PER_RUN = "max_chapters_per_run";
    private static final String KEY_REAL_BUY_LIMIT = "real_buy_limit";
    private static final String KEY_ADS_PER_ACCOUNT = "ads_per_account";
    private static final String KEY_AD_ASSIST = "ad_assist_tap";
    private static final String KEY_AD_JUMP = "ad_press_jump";
    /** 「被系统杀掉之后自动接着跑」的当天计数，见 AutomationService。 */
    private static final String KEY_RESUME_YMD = "auto_resume_ymd";
    private static final String KEY_RESUME_COUNT = "auto_resume_count";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /** 默认开：自动订阅会花真火券，先干跑验证过流程再放开。 */
    public static boolean isDryRun(Context c) {
        return sp(c).getBoolean(KEY_DRY_RUN, true);
    }

    public static void setDryRun(Context c, boolean value) {
        sp(c).edit().putBoolean(KEY_DRY_RUN, value).apply();
    }

    /** 关掉干跑前必须先明确确认一次。 */
    public static boolean isRealBuyConfirmed(Context c) {
        return sp(c).getBoolean(KEY_REAL_BUY_CONFIRMED, false);
    }

    public static void setRealBuyConfirmed(Context c, boolean value) {
        sp(c).edit().putBoolean(KEY_REAL_BUY_CONFIRMED, value).apply();
    }

    public static boolean isDailyEnabled(Context c) {
        return sp(c).getBoolean(KEY_DAILY_ENABLED, false);
    }

    public static void setDailyEnabled(Context c, boolean value) {
        sp(c).edit().putBoolean(KEY_DAILY_ENABLED, value).apply();
    }

    public static int dailyHour(Context c) {
        return clamp(sp(c).getInt(KEY_DAILY_HOUR, 9), 0, 23);
    }

    public static void setDailyHour(Context c, int hour) {
        sp(c).edit().putInt(KEY_DAILY_HOUR, clamp(hour, 0, 23)).apply();
    }

    /** 每个账号每天最多花多少火券，0 表示不限。 */
    public static int dailySpendCap(Context c) {
        return Math.max(0, sp(c).getInt(KEY_DAILY_SPEND_CAP, 0));
    }

    public static void setDailySpendCap(Context c, int cap) {
        sp(c).edit().putInt(KEY_DAILY_SPEND_CAP, Math.max(0, cap)).apply();
    }

    /**
     * 每个账号一轮最多买几章。这是<b>安全阀</b>，不是目标：正常的停止条件是
     * 「这个号的代券不够 → 换下一个号」，一路买到所有号都买不动为止。
     * 只有选择器错位时它才起作用 —— 那时候最多错这么多章就会停下。
     */
    public static int maxChaptersPerRun(Context c) {
        return clamp(sp(c).getInt(KEY_MAX_CHAPTERS_PER_RUN, 50), 1, 50);
    }

    public static void setMaxChaptersPerRun(Context c, int n) {
        sp(c).edit().putInt(KEY_MAX_CHAPTERS_PER_RUN, clamp(n, 1, 50)).apply();
    }

    /**
     * 一整趟里最多<b>真买</b>几章，0＝不限。默认 1。
     *
     * <p>这是<b>保险丝</b>，跟 {@link #maxChaptersPerRun} 那个「每号安全阀」不是一回事：
     * 安全阀防的是选择器错位买错章，这一条防的是「第一次开真买就一口气把所有号的券花掉」。
     * 真买这条路在真机上一次都没走通过 —— 第一趟必须只买一章，让人看清账本记对了、券扣对了。
     *
     * <p>配套的是 {@link #setDryRun} 的自动回档（见 {@code SubscribeRun.restoreDryRunAfterRealBuy}）：
     * 真买那一趟跑完就把干跑重新打开，不管买成几章、也不管是从哪条路退出去的。
     * 这个 App 的使用者手指动不了，忘了关＝下一趟继续花钱，
     * 而他自己关不掉 —— 所以真买是一次性授权，用完自己回到安全档。
     */
    public static int realBuyLimit(Context c) {
        return Math.max(0, sp(c).getInt(KEY_REAL_BUY_LIMIT, 1));
    }

    public static void setRealBuyLimit(Context c, int n) {
        sp(c).edit().putInt(KEY_REAL_BUY_LIMIT, Math.max(0, n)).apply();
    }

    /**
     * 一个账号每天有几个广告可领。只在界面上那句「今日还剩 N 次」读不到时当兜底用。
     */
    public static int adsPerAccount(Context c) {
        return clamp(sp(c).getInt(KEY_ADS_PER_ACCOUNT, 5), 0, 20);
    }

    public static void setAdsPerAccount(Context c, int n) {
        sp(c).edit().putInt(KEY_ADS_PER_ACCOUNT, clamp(n, 0, 20)).apply();
    }

    /**
     * 默认开：由脚本替你按下「看广告」和播完之后的关闭键。
     *
     * <p>替按键不等于替观看 —— 视频照真实时长完整播放，不快进、不跳过、播不满不许关，
     * 看的人始终是你。这是给按不动屏幕的人做的无障碍适配。关掉之后回到「只提醒」：
     * 脚本只数还剩几个、每个都停下等你自己点。
     */
    public static boolean isAdAssist(Context c) {
        return sp(c).getBoolean(KEY_AD_ASSIST, true);
    }

    public static void setAdAssist(Context c, boolean value) {
        sp(c).edit().putBoolean(KEY_AD_ASSIST, value).apply();
    }

    /**
     * 默认关：广告里那颗跳到别的 App 的按钮，要不要也替你按。
     *
     * <p>和「替你按播放」不是一回事 —— 那一下是广告主另外按点击/安装付费的动作，所以要你
     * 单独开一次，而且只在你人在屏幕前的那趟里生效（定时任务一律不按）。按下去之后不会把你
     * 丢在别的 App 里：落地页停留一会儿让你看清，再用全局返回把你带回广告页接着播。
     */
    public static boolean isAdJump(Context c) {
        return sp(c).getBoolean(KEY_AD_JUMP, false);
    }

    public static void setAdJump(Context c, boolean value) {
        sp(c).edit().putBoolean(KEY_AD_JUMP, value).apply();
    }

    /**
     * 今天已经「被系统杀掉之后自动接着跑」几次了。
     *
     * <p>MIUI 会连进程一起把我们杀掉（SwipeUpClean），系统随后会把原来那条启动请求重发一遍，
     * 于是队列自己又跑一趟。接着跑本身是好事（用户按不动屏幕，没人能替他重按一次），但必须
     * 有上限：万一某个号每次都在同一步失败，无限重启就变成反复重登，那是最招验证码的动作。
     */
    public static int autoResumes(Context c, String ymd) {
        SharedPreferences p = sp(c);
        return ymd.equals(p.getString(KEY_RESUME_YMD, "")) ? p.getInt(KEY_RESUME_COUNT, 0) : 0;
    }

    public static void noteAutoResume(Context c, String ymd) {
        int next = autoResumes(c, ymd) + 1;
        sp(c).edit().putString(KEY_RESUME_YMD, ymd).putInt(KEY_RESUME_COUNT, next).apply();
    }

    /** 用户自己按了「跑今天的流程」就把计数清零：这是一趟新的、有人看着的运行。 */
    public static void clearAutoResumes(Context c, String ymd) {
        sp(c).edit().putString(KEY_RESUME_YMD, ymd).putInt(KEY_RESUME_COUNT, 0).apply();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
