package com.example.blb.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.example.blb.data.CheckInLog;

import org.junit.Test;

/**
 * 列表里「哪个号没成」全靠颜色区分，所以这层映射要断死 —— 颜色错了肉眼很难发现，
 * 尤其是「失败」和「被验证码挡住」这两种，它们要求的处置完全不同。
 */
public class StatusPaletteTest {

    @Test
    public void signedInIsGreen() {
        assertEquals(StatusPalette.OK, StatusPalette.forCheckIn(CheckInLog.OK));
        assertEquals(StatusPalette.OK, StatusPalette.forCheckIn(CheckInLog.ALREADY));
    }

    @Test
    public void notRunYetIsGreyNotFailure() {
        // 今天还没跑到它，不是出了问题 —— 队列跑一半时后面的号都是这个状态。
        assertEquals(StatusPalette.IDLE, StatusPalette.forCheckIn(null));
        assertNotEquals(StatusPalette.FAIL, StatusPalette.forCheckIn(null));
    }

    @Test
    public void captchaIsWarnNotFailure() {
        // 验证码要人来过一次，跟「脚本失败了」不是一回事，颜色也不能一样。
        assertEquals(StatusPalette.WARN, StatusPalette.forCheckIn(CheckInLog.BLOCKED_CAPTCHA));
    }

    @Test
    public void skippedIsGrey() {
        assertEquals(StatusPalette.SKIP, StatusPalette.forCheckIn(CheckInLog.SKIPPED));
    }

    @Test
    public void failureAndUnknownStatusBothStandOut() {
        assertEquals(StatusPalette.FAIL, StatusPalette.forCheckIn(CheckInLog.FAILED));
        // 库里存了我们不认识的状态：宁可醒目，也不要悄悄划过去当成没事。
        assertEquals(StatusPalette.FAIL, StatusPalette.forCheckIn("SOMETHING_NEW"));
    }

    @Test
    public void accountMissingItsPasswordIsFlagged() {
        // 缺密码的号切号一定会失败，得在跑之前就在账号页看出来。
        assertEquals(StatusPalette.WARN, StatusPalette.forAccount(true, false));
        assertEquals(StatusPalette.OK, StatusPalette.forAccount(true, true));
    }

    @Test
    public void disabledAccountIsGreyRegardlessOfPassword() {
        assertEquals(StatusPalette.SKIP, StatusPalette.forAccount(false, true));
        assertEquals(StatusPalette.SKIP, StatusPalette.forAccount(false, false));
    }

    @Test
    public void everyToneHasItsOwnPairOfColours() {
        for (StatusPalette a : StatusPalette.values()) {
            assertNotEquals("色条和徽章底必须是深浅两色，不能同色", a.foreground, a.container);
        }
    }
}
