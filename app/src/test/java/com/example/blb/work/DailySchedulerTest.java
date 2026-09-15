package com.example.blb.work;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

/** 每日触发时刻的计算：算错就会变成一天不签或者一小时签一次。 */
public class DailySchedulerTest {

    @Test
    public void changingHourUsesAbsoluteNextTimeInsteadOfOriginalEnqueueTime() {
        Calendar now = at("Asia/Shanghai", 2026, Calendar.SEPTEMBER, 13, 11, 30);
        assertEquals(at("Asia/Shanghai", 2026, Calendar.SEPTEMBER, 13, 20, 5).getTimeInMillis(),
                DailyScheduler.nextRunAtMillis(now, 20));
        assertEquals(at("Asia/Shanghai", 2026, Calendar.SEPTEMBER, 14, 9, 5).getTimeInMillis(),
                DailyScheduler.nextRunAtMillis(now, 9));
    }

    @Test
    public void finishingAtScheduledMinuteSchedulesTheFollowingDay() {
        Calendar now = at("Asia/Shanghai", 2026, Calendar.SEPTEMBER, 13, 9, 5);
        assertEquals(at("Asia/Shanghai", 2026, Calendar.SEPTEMBER, 14, 9, 5).getTimeInMillis(),
                DailyScheduler.nextRunAtMillis(now, 9));
    }

    @Test
    public void followingRunKeepsLocalClockHourAcrossDaylightSavingChange() {
        Calendar now = at("America/New_York", 2026, Calendar.OCTOBER, 31, 10, 0);
        assertEquals(at("America/New_York", 2026, Calendar.NOVEMBER, 1, 9, 5).getTimeInMillis(),
                DailyScheduler.nextRunAtMillis(now, 9));
    }

    private static Calendar at(String zone, int year, int month, int day, int hour, int minute) {
        Calendar time = Calendar.getInstance(TimeZone.getTimeZone(zone));
        time.clear();
        time.set(year, month, day, hour, minute, 0);
        return time;
    }

    @Test
    public void delayIsAlwaysWithinTheNextDayAndNeverZero() {
        for (int hour = 0; hour < 24; hour++) {
            long minutes = DailyScheduler.delayToNextRunMinutes(hour);
            assertTrue("hour=" + hour + " 算出 " + minutes, minutes >= 1 && minutes <= 24 * 60);
        }
    }

    @Test
    public void delayLandsOnTheRequestedHourAtMinuteFive() {
        for (int hour = 0; hour < 24; hour++) {
            long minutes = DailyScheduler.delayToNextRunMinutes(hour);
            if (minutes == 1) continue; // 正好卡在 hour:05 前后一分钟，被 max(1,…) 夹住了

            Calendar fired = Calendar.getInstance();
            fired.add(Calendar.MINUTE, (int) minutes);
            assertEquals("hour=" + hour, hour, fired.get(Calendar.HOUR_OF_DAY));
            // 秒数被整除截掉，可能落在 hour:04:xx。
            int minute = fired.get(Calendar.MINUTE);
            assertTrue("hour=" + hour + " 落在 " + minute + " 分", minute == 4 || minute == 5);
        }
    }

    @Test
    public void theTwentyFourHoursCoverDistinctTargets() {
        // 24 个小时算出来的延时应当两两不同，间隔约 60 分钟，能证明没把 hour 参数吞掉。
        long base = DailyScheduler.delayToNextRunMinutes(0);
        for (int hour = 1; hour < 24; hour++) {
            long minutes = DailyScheduler.delayToNextRunMinutes(hour);
            long diff = Math.floorMod(minutes - base, 24L * 60L);
            assertTrue("hour=" + hour + " 与 0 点相差 " + diff + " 分",
                    Math.abs(diff - hour * 60L) <= 1);
        }
    }
}
