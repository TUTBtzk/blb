package com.example.blb.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

public class DayRolloverTest {

    private static final class Scheduled {
        final Runnable action;
        final long delay;

        Scheduled(Runnable action, long delay) {
            this.action = action;
            this.delay = delay;
        }
    }

    private static final class Fixture implements DayRollover.Scheduler {
        long now;
        TimeZone zone = TimeZone.getTimeZone("Asia/Shanghai");
        final List<Scheduled> pending = new ArrayList<>();
        final List<String> boundDays = new ArrayList<>();
        final DayRollover rollover = new DayRollover(
                () -> now, () -> zone, this, boundDays::add);

        void at(int day, int hour, int minute, int second, int millis) {
            Calendar time = Calendar.getInstance(zone);
            time.clear();
            time.set(2026, Calendar.SEPTEMBER, day, hour, minute, second);
            time.set(Calendar.MILLISECOND, millis);
            now = time.getTimeInMillis();
        }

        @Override
        public void postDelayed(Runnable action, long delayMillis) {
            pending.add(new Scheduled(action, delayMillis));
        }

        @Override
        public void removeCallbacks(Runnable action) {
            pending.removeIf(scheduled -> scheduled.action == action);
        }

        void fireNext() {
            Scheduled scheduled = pending.remove(0);
            now += scheduled.delay;
            scheduled.action.run();
        }
    }

    @Test
    public void visiblePageSwitchesItsDayAtMidnightWithoutDuplicateSubscriptions() {
        Fixture f = new Fixture();
        f.at(13, 23, 59, 59, 500);
        f.rollover.start();
        assertEquals(Arrays.asList("2026-09-13"), f.boundDays);
        assertEquals(500L, f.pending.get(0).delay);

        f.fireNext();
        assertEquals(Arrays.asList("2026-09-13", "2026-09-14"), f.boundDays);
        f.fireNext();
        f.rollover.start();
        assertEquals(Arrays.asList("2026-09-13", "2026-09-14"), f.boundDays);
        assertEquals(1, f.pending.size());
    }

    @Test
    public void returningFromBackgroundOrAnotherTabBindsTheNewDayImmediately() {
        Fixture f = new Fixture();
        f.at(13, 21, 0, 0, 0);
        f.rollover.start();
        f.rollover.stop();
        assertEquals(0, f.pending.size());

        f.at(15, 9, 0, 0, 0);
        assertEquals(Arrays.asList("2026-09-13"), f.boundDays);
        f.rollover.start();
        assertEquals(Arrays.asList("2026-09-13", "2026-09-15"), f.boundDays);
        assertEquals(1, f.pending.size());
    }

    @Test
    public void returningOnTheSameDayReusesTheExistingQuery() {
        Fixture f = new Fixture();
        f.at(13, 9, 0, 0, 0);
        f.rollover.start();
        f.rollover.stop();
        f.at(13, 22, 0, 0, 0);
        f.rollover.start();
        f.rollover.start();
        assertEquals(Arrays.asList("2026-09-13"), f.boundDays);
        assertEquals(1, f.pending.size());
    }

    @Test
    public void timezoneChangeAndClockCorrectionRefreshTheDayWhileVisible() {
        Fixture f = new Fixture();
        f.zone = TimeZone.getTimeZone("UTC");
        f.at(13, 18, 0, 0, 0);
        f.rollover.start();
        f.zone = TimeZone.getTimeZone("Asia/Shanghai");
        f.fireNext();
        assertEquals(Arrays.asList("2026-09-13", "2026-09-14"), f.boundDays);

        f.at(12, 9, 0, 0, 0);
        f.fireNext();
        assertEquals(Arrays.asList("2026-09-13", "2026-09-14", "2026-09-12"), f.boundDays);
        assertEquals(1, f.pending.size());
    }

    @Test
    public void stoppingAlsoInvalidatesAnAlreadyDequeuedCallback() {
        Fixture f = new Fixture();
        f.at(13, 23, 59, 59, 500);
        f.rollover.start();
        Runnable oldCallback = f.pending.get(0).action;
        f.rollover.stop();
        f.at(14, 0, 0, 0, 0);
        oldCallback.run();
        assertEquals(Arrays.asList("2026-09-13"), f.boundDays);
        assertEquals(0, f.pending.size());
    }
}
