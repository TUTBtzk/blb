package com.example.blb.work;

import android.content.Context;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.example.blb.util.Prefs;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

/** 每日签到的调度开关。设置页改了开关或时间就调 {@link #apply(Context)} 重新登记。 */
public final class DailyScheduler {

    private static final String WORK_NAME = "blb_daily_checkin";

    private DailyScheduler() {
    }

    public static void apply(Context context) {
        WorkManager wm = WorkManager.getInstance(context.getApplicationContext());
        if (!Prefs.isDailyEnabled(context)) {
            wm.cancelUniqueWork(WORK_NAME);
            return;
        }

        Constraints constraints = new Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                DailyCheckInWorker.class, 24, TimeUnit.HOURS)
                .setInitialDelay(delayToNextRunMinutes(Prefs.dailyHour(context)), TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build();

        // UPDATE 而不是 KEEP：改了签到时间要能立刻生效。
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request);
    }

    public static void cancel(Context context) {
        WorkManager.getInstance(context.getApplicationContext()).cancelUniqueWork(WORK_NAME);
    }

    /**
     * 距离下一个「今天/明天 hour:05」还有多少分钟。整点错开 5 分钟，避开整点的系统任务高峰。
     *
     * <p>公开是为了让 debug 的总控（{@code ControlReceiver} 的 STATUS）能念出「下一趟大约多久后」——
     * 用户按不动屏幕，看不到设置页，「定时到底还跑不跑」只能靠这一句回答。
     */
    public static long delayToNextRunMinutes(int hour) {
        Calendar now = Calendar.getInstance();
        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, hour);
        next.set(Calendar.MINUTE, 5);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (!next.after(now)) {
            next.add(Calendar.DAY_OF_YEAR, 1);
        }
        long minutes = (next.getTimeInMillis() - now.getTimeInMillis()) / 60_000L;
        return Math.max(1, minutes);
    }
}
