package com.example.blb.auto;

import com.example.blb.util.Texts;

/**
 * 「看广告领代券」这一步。
 *
 * <p>两种模式，区别只在**谁按键**，不在**谁看**：
 *
 * <ul>
 *   <li><b>辅助点击</b>（默认，设置页可关）：脚本替你点开广告、等视频按真实时长播完、
 *       再替你点掉播完后的领取和关闭键。这是给手上按不动屏幕的人做的无障碍适配 ——
 *       AccessibilityService 本来就是为这件事存在的。
 *   <li><b>只提醒</b>：脚本只数出还剩几个、每个都停下等你自己点，一个键都不替你按。
 * </ul>
 *
 * <p>两种模式守同一条线：不快进、不跳过、播不满最短时长不许碰关闭键。奖励到没到账不靠
 * 「点了就算」，靠界面上那句「今日还剩 N 次」有没有减少 —— 没减少就停下，不闷着头连点。
 * 激励视频是广告主按真实观看结算的，所以脚本可以替你按键，但不会替你看。
 *
 * <p><b>会替你按的键</b>：点开广告、暂停时的「继续观看」、播完的「领取奖励」、播完的关闭键。
 * 这些都只是让你正在看的这段视频继续或收尾。
 *
 * <p><b>跳到别的 App 的那颗（下载／打开／去看看）</b>：默认不按，设置页单独开一次才按
 * （{@link com.example.blb.util.Prefs#isAdJump}）。分开放一个开关，是因为它和「替你按播放」
 * 不是一回事 —— 那一下是广告主另外按点击和安装付费的动作，所以得由你本人决定、而且只在你
 * 人就在屏幕前看着的那趟里按；定时任务里永远不按（没人看的时候按它只是替广告主刷点击）。
 * 就算开着，也<b>只在广告把视频停住、不点就拿不到奖励</b>的时候按（下面那种卡）；正常播着的
 * 广告底部常驻的那颗「立即打开」一律不按 —— 视频自己会播完，没必要多制造一次付费点击。
 * 按下去之后不会把你丢在外面：在落地页停留一会儿让你看清，再用<b>全局返回</b>把你带回广告页
 * 接着播（只按返回键，不读也不点那个 App 里的任何内容）—— 手按不动的人最怕的就是被留在
 * 别的 App 里出不来。开关关着的时候，撞上这种弹窗同样是按返回退回来。
 *
 * <p><b>实测撞上的一种广告（优量汇「10秒更快拿奖」）</b>：播到一半弹一张卡把视频停住，卡上
 * 只有一颗「我要更快拿奖」（跳到广告主 App），没有「关掉接着看」的键；右上角关闭键弹的是
 * 「放弃奖励离开／抓住奖励机会」，按「抓住奖励机会」只关掉那个确认框，卡还在、视频还是停着。
 * 换句话说这种广告不点进落地页就拿不到奖励。跳转开关关着时，脚本对它的处理是<b>放弃这一个
 * 广告并如实写进日志</b> —— 不假装看完，也不让你对着停住的视频白等。
 */
public final class AdWatchTask {

    /** 一个激励视频最长等这么久（含加载和结算页）。到点还没回来就当这一个没成。 */
    private static final long MAX_PLAYBACK_MS = 150_000;
    /** 播放不足这个时长就不许去点关闭键 —— 提前关掉等于白看，奖励不到账。 */
    private static final long MIN_PLAYBACK_MS = 18_000;
    /** 点完入口，等视频真的顶上来的时间。等不到就说明这一下没点开。 */
    private static final long AD_START_TIMEOUT_MS = 20_000;
    /** 广告最多把我们带走这么多次；再多就是这个广告一直在弹跳转，放弃它。 */
    private static final int MAX_RETURNS = 4;
    /** 落地页停留的默认时长（卡上没写要浏览几秒时用），够你看清是什么东西。 */
    private static final long JUMP_DWELL_MS = 12_000;
    /** 停留时长的上限：广告主写得再长也不把你晾在别人 App 里超过这个数。 */
    private static final long MAX_DWELL_MS = 40_000;
    /** 一个广告最多替你按几次跳转键。 */
    private static final int MAX_JUMPS = 2;
    /** 按下跳转键之后，等页面真的变化的时间。到点还一动不动就是这一下没生效。 */
    private static final long JUMP_EFFECT_MS = 4_000;
    /** 连着这么多支广告都「必须点进落地页才给奖励」，就别再往下耗了。 */
    private static final int MAX_PROMO_BLOCKED = 2;
    private static final long POLL_MS = 1_000;

    /** 这一趟怎么按键。 */
    public static final class Mode {
        /** true = 脚本替你按键，false = 只提醒你自己按。 */
        public final boolean assist;
        /** true = 连跳到别的 App 那颗也替你按（默认关，且只在你人在屏幕前时才为真）。 */
        public final boolean pressJump;

        public Mode(boolean assist, boolean pressJump) {
            this.assist = assist;
            this.pressJump = assist && pressJump;
        }
    }

    public static final class Result {
        /** 确认领到奖励的个数（含今天之前几轮已经领过的）。 */
        public int watched;
        /** 界面上读到的剩余次数，-1 表示读不到。 */
        public int remaining = -1;
        /** 中途按了「跳过此账号」，或者奖励没到账主动停下。 */
        public boolean skipped;
        /** 撞上「必须点进落地页才给奖励」的广告，而你没开跳转开关，所以放弃了。 */
        public boolean promoBlocked;
        public String message;
    }

    /** 一个广告播到最后是什么结局。 */
    private enum Play {
        /** 播放页上出现了「恭喜获得奖励」。 */
        EARNED,
        /** 已经自己回到签到页了（别家 SDK 播完直接关掉的情形）。 */
        ON_SIGN_PAGE,
        /** 撞上「必须点进落地页」的卡，而跳转开关没开 —— 放弃这一个。 */
        PROMO_BLOCKED,
        /** 等到最长时长还没个结果。 */
        TIMEOUT,
        /** 被广告反复带出去，怎么都回不到菠萝包。 */
        LOST
    }

    /** 一支广告处理完之后，这一轮该怎么走。 */
    private enum Loop {
        /** 这一支的奖励到账了，接着下一支。 */
        NEXT,
        /** 这一支放弃了，但下一支可能是正常广告，还可以试。 */
        SKIP_AD,
        /** 别再往下点了。 */
        STOP
    }

    private AdWatchTask() {
    }

    /**
     * @param quota          设置页里配的「每号每天几个广告」，读不到界面计数时按它走
     * @param alreadyWatched 今天这个号已经领过几个（重跑不会从头再来一遍）
     * @param mode           谁按键、跳转键按不按
     */
    public static Result run(StepRunner r, int quota, int alreadyWatched, String accountName,
                             Mode mode) throws StepRunner.StepFailure {
        Result result = new Result();
        result.watched = Math.max(0, alreadyWatched);

        if (r.findAny(Keys.AD_REWARD) == null) {
            result.message = "签到页没有广告入口，跳过";
            return result;
        }

        result.remaining = readRemaining(r);
        int todo = result.remaining >= 0
                ? result.remaining
                : Math.max(0, quota - result.watched);
        if (todo <= 0) {
            result.message = result.remaining == 0
                    ? "今天的广告已经领完了"
                    : "按配额算今天已经领够 " + result.watched + " 个";
            return result;
        }

        r.log(accountName + " 今天还有 " + todo + " 个广告可领"
                + (result.remaining < 0 ? "（界面计数读不到，按配额 " + quota + " 算）" : "")
                + (mode.assist ? "，由我替你点开" : "，需要你自己点")
                + (mode.pressJump ? "；跳转键也替你按（会带你回来）" : ""));

        int blocked = 0;
        for (int i = 1; i <= todo; i++) {
            r.checkCancelled();
            Loop next = mode.assist
                    ? playOne(r, accountName, i, todo, result, mode)
                    : (promptOne(r, accountName, i, todo, result) ? Loop.NEXT : Loop.STOP);
            if (next == Loop.STOP) break;
            if (next == Loop.SKIP_AD) {
                // 「必须点进落地页」是某一支广告素材的做法，不是每一支都这样，所以放弃这一支
                // 之后还接着试下一支；连着两支都这样就别耗下去了。
                if (++blocked >= MAX_PROMO_BLOCKED) {
                    result.message = "连着 " + blocked + " 支广告都要求点进广告主的落地页才给奖励"
                            + "（视频被它们停住了）。"
                            + (mode.pressJump
                            ? "跳转这两支都没走通（认不出按钮或按不动），"
                            : "你没开「替我按广告里的跳转按钮」，")
                            + "所以这个号的广告先停在这儿，领到的是 " + result.watched + " 个";
                    break;
                }
                continue;
            }
            if (result.remaining == 0) {
                result.message = "广告已领完";
                break;
            }
        }

        if (result.message == null) {
            result.message = "领了 " + result.watched + " 个广告奖励"
                    + (blocked > 0 ? "；另有 " + blocked + " 支广告要求点进落地页才给奖励，已放弃" : "");
        }
        return result;
    }

    /**
     * 辅助点击模式的一个广告：点开 → 等它按真实时长播完 → 点掉领取/关闭 → 用剩余次数验收。
     *
     * @return NEXT = 领到了；SKIP_AD = 这一支要求点落地页，放弃它但可以试下一支；
     *         STOP = 这一轮别再往下点了（入口没了、奖励没到账）
     */
    private static Loop playOne(StepRunner r, String accountName, int i, int todo,
                                Result result, Mode mode) throws StepRunner.StepFailure {
        StepRunner.Outcome entry = r.findAny(Keys.AD_REWARD);
        if (entry == null) {
            result.message = "广告入口不见了（可能已领完），停在第 " + i + " 个";
            return Loop.STOP;
        }
        int before = result.remaining;
        r.log(accountName + "：点开第 " + i + "/" + todo + " 个广告，接下来是真实时长的视频");
        if (!r.pressOrLog(Keys.AD_REWARD, entry.node)) {
            result.message = "点不动签到页上的广告入口，停在第 " + i + " 个";
            return Loop.STOP;
        }

        // 先确认视频真的顶上来了。少了这一步，签到页还没被盖住就会被当成「已经看完回来了」。
        if (!awaitAdStart(r)) {
            result.message = "点了广告入口，但视频没起来（等了 "
                    + AD_START_TIMEOUT_MS / 1000 + " 秒），停在第 " + i + " 个";
            return Loop.STOP;
        }

        long start = System.currentTimeMillis();
        Play play = awaitPlayback(r, start, mode);
        if (play == Play.PROMO_BLOCKED) {
            result.promoBlocked = true;
            leaveAd(r);
            r.log("  第 " + i + " 个广告要求点进广告主的落地页才给奖励（视频被它停住了）。"
                    + (mode.pressJump
                    ? "跳转这一下没走通（认不出按钮或按不动），"
                    : "你没开「替我按广告里的跳转按钮」，")
                    + "所以我放弃了这一个，没有假装看完");
            int left = readRemaining(r);
            if (left >= 0) result.remaining = left;
            return Loop.SKIP_AD;
        }
        boolean back = play == Play.ON_SIGN_PAGE || leaveAd(r);
        // 退不回签到页时自己走回去（书架 → 签到入口）。奖励可能已经到账了（实测 14:14 那一支
        // 就是：代券到了账，脚本却因为卡在领奖弹窗上读不到「今日还剩 N 次」），而验收只能在
        // 签到面板上做 —— 读不到就得记成「没确认到」，连这个号剩下的广告一起丢掉。
        if (!back) back = reopenSignPage(r);

        int now = readRemaining(r);
        if (now >= 0) result.remaining = now;

        // 验收：剩余次数减了才算领到。读不到次数时退一步，用「回到了签到页」当佐证。
        boolean rewarded = before >= 0 && now >= 0 ? now < before : back;
        if (!rewarded) {
            result.skipped = true;
            result.message = "第 " + i + " 个广告没确认到奖励（"
                    + (before >= 0 && now >= 0 ? "剩余次数还是 " + now : "没回到签到页")
                    + "），先停下，免得白点";
            return Loop.STOP;
        }
        result.watched++;
        r.log("  第 " + i + " 个广告的奖励已到账"
                + (result.remaining >= 0 ? "，还剩 " + result.remaining + " 次" : ""));
        return Loop.NEXT;
    }

    /**
     * 等广告页盖住签到页。视频加载要几秒，这几秒里签到页还在，
     * 「回到签到页」这个判据在那之前都不能用，也不能去碰任何关闭键。
     *
     * @return true = 视频页已经起来了
     */
    private static boolean awaitAdStart(StepRunner r) throws StepRunner.StepFailure {
        long deadline = System.currentTimeMillis() + AD_START_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            r.checkCancelled();
            // 「奖励将于 N 秒后发放」这类字样是最硬的证据；读不到就退回「签到页被盖住了」。
            if (r.findAny(Keys.AD_PENDING, Keys.AD_EARNED) != null) return true;
            if (!onSignPage(r)) return true;
            r.waitMillis(POLL_MS);
        }
        return false;
    }

    /**
     * 等视频自己播完。这里做这几件事：等；暂停了就按「继续观看／抓住奖励机会」；播完了把
     * 「领取奖励」和关闭键点掉；开了跳转开关就按那颗跳转键、并在落地页停一会儿之后把你带回来；
     * 没开开关而广告自己把我们带走了，就直接按返回退回来。播不满最短时长绝不碰关闭键，
     * 全程不点「跳过」。
     *
     * @param start 视频页起来的时刻，最短时长从这里算
     */
    private static Play awaitPlayback(StepRunner r, long start, Mode mode)
            throws StepRunner.StepFailure {
        long budget = MAX_PLAYBACK_MS;
        int returns = 0;
        int jumps = 0;
        boolean rescued = false;

        while (System.currentTimeMillis() - start < budget) {
            r.checkCancelled();

            if (!r.isTargetForeground()) {
                // 我们没按跳转键，是广告自己把我们带出去了：按返回退回来接着播。
                if (returns++ >= MAX_RETURNS) {
                    // 返回键退不出来（小程序落地页常见）。最后一招是直接把菠萝包拉回前台
                    // ——只按系统的启动入口，不读也不点那个 App 里的任何东西。
                    if (rescued) {
                        r.log("  这个广告一直往外跳，怎么都回不来，不跟它纠缠了");
                        return Play.LOST;
                    }
                    rescued = true;
                    r.log("  返回键退不出这个落地页，直接把菠萝包拉回前台");
                    try {
                        r.launchTarget(10_000);
                    } catch (StepRunner.StepFailure e) {
                        r.log("  " + e.getMessage());
                        return Play.LOST;
                    }
                    returns = 0;
                    budget += 12_000;
                    continue;
                }
                r.log("  广告跳到别的 App 了，按返回退回来（不点它里面任何东西）");
                r.back();
                continue;
            }
            // 奖励到手是最强的收尾信号，比「回到签到页」更早也更准。
            if (r.findAny(Keys.AD_EARNED) != null) return Play.EARNED;
            if (onSignPage(r)) return Play.ON_SIGN_PAGE;

            // 「必须点进落地页才给奖励」的卡：它把视频停住了，只有这时候才谈得上按跳转键。
            // 正常播着的广告底部也常驻一颗「立即打开」，那颗一律不按 —— 视频自己会播完，
            // 没必要替广告主多制造一次要付费的点击。
            //
            // 这一组刻意排在 ad_resume / ad_claim 之前：第二张卡上写着「6s后自动放弃」，
            // 晚一轮（1 秒）都可能错过窗口，而卡在时那两组本来也不会命中。
            if (r.findAny(Keys.AD_PROMO) != null) {
                if (!mode.pressJump || jumps >= MAX_JUMPS) return Play.PROMO_BLOCKED;
                jumps++;
                // 停留时长照广告主自己写的秒数来（「去浏览15秒」「浏览广告详情10秒」），
                // 停不满它要求的时长，回来照样算「任务失败」，白跳一趟还多花了一次点击。
                // 必须在按下去之前读：按完这张卡就没了。
                long dwell = dwellMs(r);
                // 先上闹钟再按跳转：落地页占着前台的时候系统可能把我们冻住（实测停在
                // 「点击 ad_jump」这一行五分钟，然后进程被 MIUI 结束掉），那时候只有闹钟
                // 还能把人带回来。顺序不能反 —— 按下去之后才上闹钟就已经晚了。
                r.armReturnWatchdog(dwell);
                long armedAt = android.os.SystemClock.elapsedRealtime();
                StepRunner.Outcome jump = r.findAny(Keys.AD_JUMP);
                boolean pressed;
                if (jump != null) {
                    pressed = r.pressOrLog(Keys.AD_JUMP, jump.node);
                } else {
                    // 认不出哪颗是按钮时不放弃、更不许去按「跳过」（那是把奖励扔掉）：卡上自己
                    // 写着「上滑或点击跳转到详情页或第三方应用」，整张卡就是跳转热区，点它中心。
                    StepRunner.Outcome promo = r.findAny(Keys.AD_PROMO);
                    r.log("  这张卡上认不出哪颗是跳转键（菠萝包现在挂着 " + r.windowCount()
                            + " 个窗口），改点整张卡的中心 —— 卡上写的就是「点击卡片即跳转」");
                    pressed = promo != null && r.pressCardOrLog("跳转卡片中心", promo.node);
                    if (!pressed) {
                        // 卡上的节点整棵都是零面积的占位节点（实测 bounds=[0,111][0,111]），
                        // 读不出任何矩形。最后按屏幕几何位置点正中偏下那一带 —— 那是卡片本体，
                        // 「跳过」在最上面、常驻的「立即下载」在最下面，都碰不到。
                        pressed = r.pressScreenCenterOrLog("跳转（屏幕正中，卡上读不出矩形）");
                    }
                }
                if (!pressed) {
                    r.disarmReturnWatchdog();
                    return Play.PROMO_BLOCKED;
                }
                r.log("  这一支要点进落地页才给奖励，已替你按下跳转（" + dwell / 1000
                        + " 秒后我按返回把你带回来）");
                boolean landed = dwellOnLanding(r, dwell);
                r.disarmReturnWatchdog();
                if (r.returnWatchdogFired(armedAt)) {
                    r.log("  （在落地页上我们被系统冻住过，是闹钟按的返回 —— 这一支的浏览时长可能不够）");
                }
                if (!landed) {
                    r.log("  跳转按下去了但页面没动，放弃这一支 ——"
                            + "不再补按，补按只是替广告主多刷一次要付费的点击");
                    return Play.PROMO_BLOCKED;
                }
                budget += dwell + 10_000;
                continue;
            }

            // 暂停了就替你按「继续观看／抓住奖励机会」。这一组只认让视频接着播的键。
            StepRunner.Outcome resume = r.findAny(Keys.AD_RESUME);
            if (resume != null) {
                if (!r.pressOrLog(Keys.AD_RESUME, resume.node)) r.waitMillis(POLL_MS);
                continue;
            }
            StepRunner.Outcome claim = r.findAny(Keys.AD_CLAIM);
            if (claim != null) {
                if (!r.pressOrLog(Keys.AD_CLAIM, claim.node)) r.waitMillis(POLL_MS);
                continue;
            }
            if (System.currentTimeMillis() - start >= MIN_PLAYBACK_MS) {
                StepRunner.Outcome close = r.findAny(Keys.AD_CLOSE);
                if (close != null) {
                    if (!r.pressOrLog(Keys.AD_CLOSE, close.node)) r.waitMillis(POLL_MS);
                    continue;
                }
            }
            r.waitMillis(POLL_MS);
        }
        return Play.TIMEOUT;
    }

    /**
     * 按下跳转键之后：确认真的进了落地页 → 按广告主要求的秒数停住 → 按返回回到广告页。
     *
     * <p>为什么不能只看前台包名：卡上写的是「跳转到详情页<b>或</b>第三方应用」，实测详情页有可能
     * 就开在菠萝包自己的 WebView 里 —— 那时前台包名一直是 com.sfacg。旧代码的停留逻辑只在
     * 「已经不在菠萝包里」时才生效，落在内置详情页上就完全不算停留，还会被主循环当成播放页去找
     * 关闭键，结果停不满秒数、白跳一趟（广告主那边算任务失败，奖励照样不给）。
     *
     * <p>停留期间只等待、只按返回，<b>不读也不点</b>非 com.sfacg 的任何节点，落地页里的
     * 「立即下载」之类一颗都不碰。
     *
     * @return false = 按下去之后 4 秒内页面毫无变化（这一下没生效），交给调用方放弃这一支
     */
    private static boolean dwellOnLanding(StepRunner r, long dwellMs)
            throws StepRunner.StepFailure {
        if (!awaitLanding(r)) return false;
        String where = r.activePackage();
        r.log("  已经在落地页上了" + (where == null ? "" : "（" + where + "）")
                + "，停 " + dwellMs / 1000 + " 秒让你看清");
        long until = System.currentTimeMillis() + dwellMs;
        long nextTick = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < until) {
            r.checkCancelled();
            r.waitMillis(POLL_MS);
            // 每 5 秒报一行。这几行不是给人看的进度条，是唯一能事后区分「我们在正常等」和
            // 「我们在落地页上被系统冻住了」的证据 —— 冻住的时候日志会从中间断掉。
            if (System.currentTimeMillis() >= nextTick) {
                nextTick += 5_000;
                long left = Math.max(0, until - System.currentTimeMillis());
                r.log("  落地页上还要停 " + (left / 1000 + 1) + " 秒");
            }
        }
        r.log("  浏览时间够了，按返回回到广告页接着播");
        for (int i = 0; i < MAX_RETURNS; i++) {
            r.checkCancelled();
            if (backInAd(r)) return true;
            r.back();
        }
        // 回不去也别在这儿判死刑：主循环还有「拉回前台」那一层兜底。
        return true;
    }

    /** 等页面真的变了（离开菠萝包，或者促销卡不见了＝已经进了内置详情页）。 */
    private static boolean awaitLanding(StepRunner r) throws StepRunner.StepFailure {
        long deadline = System.currentTimeMillis() + JUMP_EFFECT_MS;
        while (System.currentTimeMillis() < deadline) {
            r.checkCancelled();
            if (!r.isTargetForeground()) return true;
            if (r.findAny(Keys.AD_PROMO) == null) return true;
            r.waitMillis(POLL_MS);
        }
        return false;
    }

    /** 回到广告页（或签到页）了吗。 */
    private static boolean backInAd(StepRunner r) throws StepRunner.StepFailure {
        return r.isTargetForeground()
                && r.findAny(Keys.AD_PENDING, Keys.AD_EARNED, Keys.AD_PROMO, Keys.AD_DWELL_HINT,
                Keys.AD_REMAINING, Keys.AD_REWARD) != null;
    }

    /**
     * 该在落地页停多久。取卡上／顶栏上广告主自己写的秒数（「去浏览15秒」「去体验15秒可立即
     * 领奖」「浏览广告详情10秒」），再加 4 秒缓冲：这类卡还会写一句「请勿中断浏览以免任务
     * 失败」，差一秒都算没完成，那就等于白跳一趟。读不到秒数时退回默认值。
     */
    private static long dwellMs(StepRunner r) throws StepRunner.StepFailure {
        int seconds = Texts.firstInt(r.peekText(Keys.AD_DWELL_HINT), -1);
        if (seconds <= 0) return JUMP_DWELL_MS;
        long wanted = (seconds + 4) * 1000L;
        return Math.min(Math.max(wanted, JUMP_DWELL_MS), MAX_DWELL_MS);
    }

    /**
     * 离开播放页回到签到页。奖励到手之后按返回就能出来，菠萝包自己会弹「开心收下」，
     * 顺手点掉；奖励没到手时返回会弹「确认要离开吗」，那说明我们是主动放弃，按「放弃奖励离开」。
     *
     * <p>不在菠萝包里的时候只按返回 —— 别在别的 App 的界面上找键，那不是我们该碰的地方。
     *
     * <p>穿山甲那种播放页<b>把返回键整个吃掉</b>（实测连按 4 次一动不动），所以还留了两条退路：
     * 先按它右上角那颗「跳过」—— 只在我们已经决定放弃这一个广告时才按，而且此时奖励本来就
     * 拿不到，不存在「本来能领却被跳掉」；它也不行就直接把菠萝包拉回前台。
     */
    private static boolean leaveAd(StepRunner r) throws StepRunner.StepFailure {
        boolean skipped = false;
        int backs = 0;
        int corners = 0;
        for (int i = 0; i < 8; i++) {
            r.checkCancelled();
            if (onSignPage(r)) return true;
            if (!r.isTargetForeground()) {
                r.back();
                continue;
            }
            StepRunner.Outcome claim = r.findAny(Keys.AD_CLAIM);
            if (claim != null) {
                if (!r.pressOrLog(Keys.AD_CLAIM, claim.node)) r.waitMillis(POLL_MS);
                continue;
            }
            StepRunner.Outcome abandon = r.findAny(Keys.AD_ABANDON);
            if (abandon != null) {
                r.log("  这一个不看了，按「放弃奖励离开」退出去");
                if (!r.pressOrLog(Keys.AD_ABANDON, abandon.node)) r.waitMillis(POLL_MS);
                continue;
            }
            StepRunner.Outcome close = r.findAny(Keys.AD_CLOSE);
            if (close != null) {
                if (!r.pressOrLog(Keys.AD_CLOSE, close.node)) r.waitMillis(POLL_MS);
                continue;
            }
            // 奖励已经到手，但这张领奖弹窗的 X 没文字、没 id、也不 clickable，选择器认不到，
            // 而全局返回在它上面实测连按 8 次一动不动（2026-08-23 14:14 那一支：代券已经到账，
            // 脚本却因为退不回签到页把它记成「没确认到奖励」并停下）。所以按位置点那颗 X。
            // 严格排在 ad_close 之后、返回键之前，而且只在 ad_earned 在屏幕上时才敢下手 ——
            // 同一个位置在穿山甲播放页上是「跳过」，奖励没到手时点那儿等于把奖励扔掉。
            if (corners < 2 && r.findAny(Keys.AD_EARNED) != null) {
                corners++;
                r.log("  奖励已到手，按右上角那颗关闭 X 收尾（这个 SDK 的 X 没文字没 id，只能按位置认）");
                if (!r.pressCloseCornerOrLog("领奖弹窗的关闭 X")) r.waitMillis(POLL_MS);
                continue;
            }
            // 返回键按不动的播放页：用它自己的「跳过」把这一个了结掉。放在最后、而且要先真的
            // 连按过 3 次返回都没反应才许用 —— 「跳过」永远只能用来放弃，不能用来领奖，所以
            // 「一时没认出卡上的按钮」绝不是按它的理由（那是把本来能领的奖励直接扔掉）。
            if (!skipped && backs >= 3 && r.findAny(Keys.AD_PROMO) != null) {
                StepRunner.Outcome skip = r.findAny(Keys.AD_SKIP);
                if (skip != null) {
                    skipped = true;
                    r.log("  连按 " + backs + " 次返回都退不出这个播放页，"
                            + "用它自己的「跳过」把这一支了结掉（本来也拿不到奖励）");
                    if (!r.pressOrLog(Keys.AD_SKIP, skip.node)) r.waitMillis(POLL_MS);
                    continue;
                }
            }
            backs++;
            r.back();
        }
        if (onSignPage(r)) return true;
        // 连「跳过」都没有：直接把菠萝包拉回前台，别把手按不动的人留在广告里。
        r.log("  退不出这个播放页，直接把菠萝包拉回前台");
        try {
            r.ensureHome(1);
        } catch (StepRunner.StepFailure e) {
            r.log("  " + e.getMessage());
        }
        return onSignPage(r);
    }

    /** 回到签到页了吗。先确认还在菠萝包里 —— 别的 App 里也可能有「看视频」这种字样。 */
    private static boolean onSignPage(StepRunner r) throws StepRunner.StepFailure {
        return r.isTargetForeground()
                && r.findAny(Keys.AD_REMAINING, Keys.CHECKIN_DIALOG, Keys.AD_REWARD) != null;
    }

    /**
     * 从别处自己走回签到面板（书架 → 签到入口），只为了读「今日还剩 N 次」。
     *
     * <p>只走我们自己认得的那两颗入口键，不碰广告里的任何东西；一路上按不动就如实记日志、
     * 返回 false 交给调用方按「没确认到奖励」处理，绝不假装领到了。
     */
    private static boolean reopenSignPage(StepRunner r) throws StepRunner.StepFailure {
        r.log("  退不回签到页，自己走回去（书架 → 签到入口）看奖励到没到账");
        try {
            r.ensureHome(2);
            StepRunner.Outcome shelf = r.findAny(Keys.SHELF_TAB);
            if (shelf != null) r.pressOrLog(Keys.SHELF_TAB, shelf.node);
            StepRunner.Outcome entry = r.findAny(Keys.CHECKIN_ENTRY, Keys.CHECKIN_DONE);
            if (entry != null) r.pressOrLog(entry.key, entry.node);
            r.waitForAny(8_000, Keys.AD_REMAINING, Keys.AD_REWARD, Keys.CHECKIN_DIALOG);
        } catch (StepRunner.StepFailure e) {
            if (e.kind == StepRunner.Kind.CANCELLED) throw e;
            r.log("  " + e.getMessage());
        }
        return onSignPage(r);
    }

    /** 只提醒模式：停下来等你自己看完点「继续」。 */
    private static boolean promptOne(StepRunner r, String accountName, int i, int todo,
                                    Result result) throws StepRunner.StepFailure {
        if (r.findAny(Keys.AD_REWARD) == null) {
            result.message = "广告入口不见了（可能已领完），停在第 " + i + " 个";
            return false;
        }
        StepRunner.Decision d = r.awaitUser(accountName + "：请手动看完第 " + i + "/" + todo
                + " 个广告并领取奖励，领完点「继续」；不想看就点「跳过此账号」");
        if (d == StepRunner.Decision.ABORT) {
            throw new StepRunner.StepFailure(StepRunner.Kind.CANCELLED, "看广告时中止");
        }
        if (d == StepRunner.Decision.SKIP) {
            result.skipped = true;
            result.message = "剩下的广告没看（已跳过，领了 " + result.watched + " 个）";
            return false;
        }
        result.watched++;
        int now = readRemaining(r);
        if (now >= 0) result.remaining = now;
        return true;
    }

    /** 先试专门的计数控件，再退到广告入口自己那行文字（有的版本写在一起）。 */
    private static int readRemaining(StepRunner r) throws StepRunner.StepFailure {
        int n = Texts.parseRemaining(r.peekText(Keys.AD_REMAINING));
        if (n >= 0) return n;
        return Texts.parseRemaining(r.peekText(Keys.AD_REWARD));
    }
}
