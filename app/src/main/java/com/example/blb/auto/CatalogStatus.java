package com.example.blb.auto;

import com.example.blb.data.Novel;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 扫描和购买分开后，目录的新旧必须在按钮旁和运行结论里说同一句话。 */
public final class CatalogStatus {
    private CatalogStatus() { }

    public static String describe(Novel novel, long now) {
        if (novel == null || novel.catalogScannedAt <= 0) return "目录：还没扫过";
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        Date scanned = new Date(novel.catalogScannedAt);
        String when = day.format(scanned).equals(day.format(new Date(now)))
                ? "今天 " + new SimpleDateFormat("HH:mm", Locale.ROOT).format(scanned)
                : new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(scanned);
        return "目录：" + when + " 扫过 · " + novel.catalogChapterCount + " 章";
    }

    public static boolean stale(long scannedAt, long now, int maxAgeHours) {
        if (scannedAt <= 0 || scannedAt > now) return true;
        long limit = (maxAgeHours > 0 ? maxAgeHours : 24) * 3_600_000L;
        return now - scannedAt > limit;
    }

    public static boolean shouldAutoSync(boolean enabled, int accountIndex, long scannedAt,
                                         long now, int maxAgeHours) {
        return enabled && accountIndex == 0 && stale(scannedAt, now, maxAgeHours);
    }

    public static String ageWarning(long scannedAt, long now, int maxAgeHours) {
        if (!stale(scannedAt, now, maxAgeHours)) return null;
        if (scannedAt <= 0) return "目录扫描时间尚未记录，作者可能有新章没进账本；请点『同步目录』";
        if (scannedAt > now) return "目录扫描时间晚于当前时间，无法判断新旧；请点『同步目录』";
        return "目录是 " + ((now - scannedAt) / 3_600_000L)
                + " 小时前扫的，作者可能有新章没进账本";
    }

    public static String runNote(Novel novel, long now, int maxAgeHours) {
        String note = describe(novel, now);
        String warning = ageWarning(novel == null ? 0 : novel.catalogScannedAt, now, maxAgeHours);
        return warning == null ? note : note + "；" + warning;
    }
}
