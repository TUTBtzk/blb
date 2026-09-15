package com.example.blb.util;

import android.content.Context;
import android.content.SharedPreferences;

/** 少量开关设置。凭据不放这里，走 Room + KeyStoreBox。 */
public final class Prefs {

    private static final String NAME = "blb_prefs";

    private static final String KEY_DAILY_ENABLED = "daily_enabled";
    private static final String KEY_DAILY_HOUR = "daily_hour";
    private static final String KEY_DAILY_SPEND_CAP = "daily_spend_cap";
    private static final String KEY_CATALOG_MAX_AGE_HOURS = "catalog_max_age_hours";
    private static final String KEY_CATALOG_AUTO_SYNC = "catalog_auto_sync";
    private static final String KEY_ALWAYS_DETAIL_AUDIT = "always_detail_audit";
    /** 「被系统杀掉之后自动接着跑」的当天计数，见 AutomationService。 */
    private static final String KEY_RESUME_YMD = "auto_resume_ymd";
    private static final String KEY_RESUME_COUNT = "auto_resume_count";
    private static final String KEY_AUDIT_SUMMARY = "last_audit_summary_";
    private static final String KEY_AUDIT_DAY = "last_audit_day_";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
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

    /** 八个号过去每号都扫整本目录；过期阈值只决定提醒，不能暗中重新启用逐号扫描。 */
    public static int catalogMaxAgeHours(Context c) {
        return catalogHoursOrDefault(sp(c).getInt(KEY_CATALOG_MAX_AGE_HOURS, 24));
    }

    public static void setCatalogMaxAgeHours(Context c, int hours) {
        sp(c).edit().putInt(KEY_CATALOG_MAX_AGE_HOURS, catalogHoursOrDefault(hours)).apply();
    }

    static int catalogHoursOrDefault(int hours) {
        return hours > 0 ? hours : 24;
    }

    /** 同步目录已独立成按钮；只有用户明确打开后，过期目录才可占用整套流程的时间。 */
    public static boolean isCatalogAutoSync(Context c) {
        return sp(c).getBoolean(KEY_CATALOG_AUTO_SYNC, false);
    }

    public static void setCatalogAutoSync(Context c, boolean enabled) {
        sp(c).edit().putBoolean(KEY_CATALOG_AUTO_SYNC, enabled).apply();
    }

    /** 默认复用仍有效的逐章证据；聚合异常或准备花券时，购买护栏仍须强制重读。 */
    public static boolean isAlwaysDetailAudit(Context c) {
        return sp(c).getBoolean(KEY_ALWAYS_DETAIL_AUDIT, false);
    }

    public static void setAlwaysDetailAudit(Context c, boolean enabled) {
        sp(c).edit().putBoolean(KEY_ALWAYS_DETAIL_AUDIT, enabled).apply();
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

    /** 摘要必须跟书走，切换目标书不能借用另一本书的「八个号都核过」。 */
    public static String lastAuditSummary(Context c, long novelId) {
        if (novelId <= 0) return "";
        SharedPreferences prefs = sp(c);
        return datedAuditSummary(prefs.getString(KEY_AUDIT_SUMMARY + novelId, ""),
                prefs.getString(KEY_AUDIT_DAY + novelId, ""), Texts.todayYmd());
    }

    /** 调用者持有 LedgerEdits 的运行占用并在 Db.io 内保存，页面收工刷新时立即读到本轮结论。 */
    public static void setLastAuditSummary(Context c, long novelId, String summary) {
        if (novelId <= 0) throw new IllegalArgumentException("核对摘要缺少目标小说");
        sp(c).edit().putString(KEY_AUDIT_SUMMARY + novelId, summary == null ? "" : summary)
                .putString(KEY_AUDIT_DAY + novelId, Texts.todayYmd()).apply();
    }

    /** 原样存下「今天」会在隔天仍冒充新证据；跨日只改日期，保留真实的账号数和修账结果。 */
    static String datedAuditSummary(String summary, String savedDay, String today) {
        if (summary == null) return "";
        String prefix = "最后核对 今天 ";
        if (summary.startsWith(prefix) && !Texts.isBlank(savedDay) && !savedDay.equals(today)) {
            return "最后核对 " + savedDay + " " + summary.substring(prefix.length());
        }
        return summary;
    }

    // ---------- 常驻真买授权 ----------
    //
    // 已整套删除。自动订阅现在没有干跑、没有额度、没有到期：按队列顺序真的订阅，
    // 一个号买到代券不够就换下一个号。「能不能花钱」的唯一判据是账号手里的代券够不够。

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
