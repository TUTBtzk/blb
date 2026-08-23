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
    private static final String KEY_ADS_PER_ACCOUNT = "ads_per_account";
    private static final String KEY_AD_ASSIST = "ad_assist_tap";
    private static final String KEY_AD_JUMP = "ad_press_jump";

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

    /** 一轮自动订阅最多买几章，防止选择器错位时连续买错。 */
    public static int maxChaptersPerRun(Context c) {
        return clamp(sp(c).getInt(KEY_MAX_CHAPTERS_PER_RUN, 3), 1, 50);
    }

    public static void setMaxChaptersPerRun(Context c, int n) {
        sp(c).edit().putInt(KEY_MAX_CHAPTERS_PER_RUN, clamp(n, 1, 50)).apply();
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

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
