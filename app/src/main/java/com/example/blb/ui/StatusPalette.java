package com.example.blb.ui;

import androidx.annotation.ColorRes;
import androidx.annotation.Nullable;

import com.example.blb.R;
import com.example.blb.data.CheckInLog;

/**
 * 把「这一行是什么状况」翻译成一对颜色：深色给文字和左侧色条，浅色给徽章底。
 *
 * <p>为什么单独抽出来：列表里一眼能不能看出「哪个号没成」全靠这层映射，
 * 而它是纯函数，可以在 JVM 单测里断死 —— 界面上的颜色不该靠肉眼回归。
 */
public enum StatusPalette {

    /** 成功了，不用管。 */
    OK(R.color.blb_ok, R.color.blb_ok_container),
    /** 没失败，但需要人知道（被验证码挡住）。 */
    WARN(R.color.blb_warn, R.color.blb_warn_container),
    /** 失败了。 */
    FAIL(R.color.blb_fail, R.color.blb_fail_container),
    /** 主动跳过／已停用，不算问题。 */
    SKIP(R.color.blb_skip, R.color.blb_skip_container),
    /** 今天还没跑到它。 */
    IDLE(R.color.blb_idle, R.color.blb_idle_container);

    /** 文字和左侧色条用的深色。 */
    @ColorRes
    public final int foreground;

    /** 徽章底用的浅色。 */
    @ColorRes
    public final int container;

    StatusPalette(@ColorRes int foreground, @ColorRes int container) {
        this.foreground = foreground;
        this.container = container;
    }

    /**
     * 今日签到状态 → 颜色。
     *
     * @param status CheckInLog 里的状态常量；null＝今天还没跑这个号
     */
    public static StatusPalette forCheckIn(@Nullable String status) {
        if (status == null) return IDLE;
        switch (status) {
            case CheckInLog.OK:
            case CheckInLog.ALREADY:
                return OK;
            case CheckInLog.BLOCKED_CAPTCHA:
                // 验证码要人来过，不是脚本失败 —— 用琥珀色跟真失败区分开。
                return WARN;
            case CheckInLog.SKIPPED:
                return SKIP;
            case CheckInLog.FAILED:
                return FAIL;
            default:
                // 库里存了我们不认识的状态：当失败看，宁可醒目也不要悄悄划过去。
                return FAIL;
        }
    }

    /**
     * 账号页那一行 → 颜色。
     *
     * @param enabled  这个号还参不参加跑队列
     * @param loginable 切号所需的东西齐不齐（需要密码的号存了密码没）
     */
    public static StatusPalette forAccount(boolean enabled, boolean loginable) {
        if (!enabled) return SKIP;
        return loginable ? OK : WARN;
    }
}
