package com.example.blb.util;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** 前台页面的日期订阅：恢复显示时立即复核，跨午夜时只通知一次。所有调用在同一线程执行。 */
public final class DayRollover {

    public interface Scheduler {
        void postDelayed(Runnable action, long delayMillis);
        void removeCallbacks(Runnable action);
    }

    // 同时处理页面显示期间的系统时钟/时区修改，不把旧午夜的延时一直留到第二天。
    private static final long MAX_CHECK_DELAY = 60_000L;

    private final LongSupplier clock;
    private final Supplier<TimeZone> timeZone;
    private final Scheduler scheduler;
    private final Consumer<String> onDayChanged;
    private final Runnable tick = this::refresh;
    private boolean active;
    private String observedDay;

    public DayRollover(Scheduler scheduler, Consumer<String> onDayChanged) {
        this(System::currentTimeMillis, TimeZone::getDefault, scheduler, onDayChanged);
    }

    DayRollover(LongSupplier clock, Supplier<TimeZone> timeZone,
                Scheduler scheduler, Consumer<String> onDayChanged) {
        this.clock = clock;
        this.timeZone = timeZone;
        this.scheduler = scheduler;
        this.onDayChanged = onDayChanged;
    }

    public void start() {
        active = true;
        refresh();
    }

    public void stop() {
        active = false;
        scheduler.removeCallbacks(tick);
    }

    private void refresh() {
        scheduler.removeCallbacks(tick);
        if (!active) return;

        long now = clock.getAsLong();
        Calendar day = Calendar.getInstance(timeZone.get(), Locale.US);
        day.setTimeInMillis(now);
        String ymd = String.format(Locale.US, "%04d-%02d-%02d",
                day.get(Calendar.YEAR), day.get(Calendar.MONTH) + 1,
                day.get(Calendar.DAY_OF_MONTH));
        if (!ymd.equals(observedDay)) {
            observedDay = ymd;
            onDayChanged.accept(ymd);
        }

        day.add(Calendar.DAY_OF_MONTH, 1);
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        long delay = Math.max(1L, Math.min(MAX_CHECK_DELAY, day.getTimeInMillis() - now));
        if (active) scheduler.postDelayed(tick, delay);
    }
}
