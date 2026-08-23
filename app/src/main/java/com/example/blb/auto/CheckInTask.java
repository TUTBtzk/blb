package com.example.blb.auto;

import com.example.blb.data.CheckInLog;
import com.example.blb.util.Texts;

/**
 * 一个账号的签到流程。
 *
 * <p>广告不自动看：走到签到页时只判断有没有「看广告领奖励」入口，把这件事记进日志、
 * 交给你手动领。激励视频是按真实观看计费的，脚本刷完等于让广告主为没人看的曝光付钱。
 */
public final class CheckInTask {

    private static final long NAV_TIMEOUT = 12_000;
    /** 面板已经出来了，再给「点击签到」这点时间画出来；等不到才认定今天已经签过。 */
    private static final long BUTTON_GRACE = 3_000;

    public static final class Result {
        public String status = CheckInLog.FAILED;
        public boolean adAvailable;
        /** 读到的火券余额，-1 表示没读到，不要覆盖已有值。 */
        public int coupons = -1;
        /** 读到的代券余额，-1 表示没读到。签到发的就是代券。 */
        public int vouchers = -1;
        public String message;
    }

    private CheckInTask() {
    }

    public static Result run(StepRunner r) throws StepRunner.StepFailure {
        Result result = new Result();

        // 签到入口在书架页右上角。上一轮任务可能把菠萝包留在别的页面，先回书架。
        if (r.findAny(Keys.CHECKIN_ENTRY, Keys.CHECKIN_DONE) == null) {
            StepRunner.Outcome shelf = r.findAny(Keys.SHELF_TAB);
            if (shelf != null) r.clickNode(Keys.SHELF_TAB, shelf.node);
        }

        StepRunner.Outcome entry =
                r.waitForAny(NAV_TIMEOUT, Keys.CHECKIN_ENTRY, Keys.CHECKIN_DONE);
        if (Keys.CHECKIN_ENTRY.equals(entry.key)) {
            r.clickNode(Keys.CHECKIN_ENTRY, entry.node);
        }

        StepRunner.Outcome page = r.waitForAny(NAV_TIMEOUT,
                Keys.CHECKIN_BUTTON, Keys.CHECKIN_DONE, Keys.CHECKIN_DIALOG);
        if (Keys.CHECKIN_DIALOG.equals(page.key)) page = settle(r, page);

        if (Keys.CHECKIN_BUTTON.equals(page.key)) {
            sign(r, page, result);
        } else if (Keys.CHECKIN_DONE.equals(page.key)) {
            result.status = CheckInLog.ALREADY;
            result.message = trim(page.node.text());
        } else {
            // 菠萝包签完不留任何「已签到」字样：今天那一格直接变回星期名。
            // 所以「面板在、但没有『点击签到』」就是今天已经签过了。
            result.status = CheckInLog.ALREADY;
            result.message = "签到面板里没有「点击签到」，今天这个号已经签过";
        }

        result.adAvailable = r.findAny(Keys.AD_REWARD) != null;
        Texts.Balance balance = r.readBalance(2_500);
        result.coupons = balance.fire;
        result.vouchers = balance.voucher;
        return result;
    }

    private static void sign(StepRunner r, StepRunner.Outcome button, Result result)
            throws StepRunner.StepFailure {
        r.clickNode(Keys.CHECKIN_BUTTON, button.node);
        r.guardCaptcha();
        try {
            NodeView confirmed = r.waitFor(Keys.CHECKIN_DONE, 10_000);
            result.status = CheckInLog.OK;
            result.message = join(trim(confirmed.text()), trim(r.peekText(Keys.CHECKIN_REWARD)));
        } catch (StepRunner.StepFailure e) {
            if (e.kind != StepRunner.Kind.TIMEOUT) throw e;
            // 点下去了但界面没给出成功提示，不敢当成成功。
            result.status = CheckInLog.FAILED;
            result.message = "点了签到但没看到「签到成功」，请手动核对";
        }
    }

    /** 面板已经出来了，但今天那一格可能还没渲染完。等一小会儿，等不到就当已签。 */
    private static StepRunner.Outcome settle(StepRunner r, StepRunner.Outcome dialog)
            throws StepRunner.StepFailure {
        try {
            return r.waitForAny(BUTTON_GRACE, Keys.CHECKIN_BUTTON, Keys.CHECKIN_DONE);
        } catch (StepRunner.StepFailure e) {
            if (e.kind != StepRunner.Kind.TIMEOUT) throw e;
            return dialog;
        }
    }

    private static String join(String a, String b) {
        if (Texts.isBlank(a)) return b;
        if (Texts.isBlank(b)) return a;
        return a + " " + b;
    }

    private static String trim(String s) {
        return Texts.isBlank(s) ? null : s.trim();
    }
}
