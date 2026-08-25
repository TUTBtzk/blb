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
 * <p><b>那几次是所有账号共用的</b>（2026-08-23 用户实测告知）：设备上一天总共 5 支，第一个号
 * 看完之后，后面每个号打开签到面板看到的都是「已领完」。所以这一步对后 7 个号的正确行为就是
 * 立刻收工 —— 判据是 {@link Keys#AD_EXHAUSTED}，在读次数和点入口之前先查。设置页那个
 * 「每号每天几个」只是<b>界面计数读不到时</b>的上限，不是每个号真的能再看那么多。
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
    /**
     * 等菠萝包那张「开心收下」发放卡冒出来的时间。
     *
     * <p>2026-08-24 09:28 实测：第 3 支广告回到菠萝包时它还没出现，几秒后才弹，然后一直盖着
     * 首页 —— 后面 7 个号全部卡死。只在「次数读不到或没变」时才等，所以正常路径不会白等。
     */
    private static final long GRANT_WAIT_MS = 8_000;
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

        // 先问「今天还有没有次数」，再问「入口在不在」：领完之后入口那颗 sign_in_ad_goto 整个
        // 从树里消失，ad_reward 却还会命中那条不可点的标题（「看小视频再领代券」），于是旧代码
        // 以为入口还在、按配额去点它，每个号白等 20 秒 awaitAdStart（用户 2026-08-23 报的
        // 「广告已经看完了，程序却一直尝试观看广告」就是这条）。而那 5 次是所有账号共用的，
        // 所以第一个号看完之后，后面每个号都会走到这里。
        if (r.findAny(Keys.AD_EXHAUSTED) != null) {
            result.remaining = 0;
            result.message = "今天的广告次数已经领完了（面板上写着「已领完」）";
            return result;
        }

        if (r.findAny(Keys.AD_REWARD) == null) {
            // 也可能只是上一趟留下的发放卡盖着（模态、按返回关不掉）。收下它再看一眼。
            if (!pressGrantIfAny(r) || r.findAny(Keys.AD_REWARD) == null) {
                result.message = "签到页没有广告入口，跳过";
                return result;
            }
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
                + (result.remaining < 0
                ? "（界面计数读不到，按配额 " + quota + " 算；那几次是所有账号共用的）" : "")
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
        // 每一支开始前都再问一次「已领完」：todo 有可能是按配额算出来的（界面计数读不到时），
        // 那个数字比真实次数大，多出来的那几轮就会去点一个已经没有的入口。
        if (r.findAny(Keys.AD_EXHAUSTED) != null) {
            result.remaining = 0;
            result.message = result.watched > 0
                    ? "领了 " + result.watched + " 个广告奖励，面板已经写「已领完」"
                    : "今天的广告次数已经领完了（面板上写着「已领完」）";
            return Loop.STOP;
        }
        StepRunner.Outcome entry = r.findAny(Keys.AD_REWARD);
        if (entry == null) {
            // 「入口不见了」还有一个更常见的原因：上一支的发放卡盖在面板上（它一盖上来，
            // 整棵树里只剩它那几个节点）。先把它收下，再回头找一次入口。
            if (pressGrantIfAny(r)) entry = r.findAny(Keys.AD_REWARD);
        }
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
            // 点了没反应最常见的原因就是「入口其实已经没了」：领完之后 sign_in_ad_goto 从树里
            // 消失，能命中的只剩那条不可点的标题。把这一句写进日志，免得下次又从零查一遍。
            boolean gone = r.findAny(Keys.AD_EXHAUSTED) != null
                    || r.findAny(Keys.AD_REWARD) == null;
            if (gone) result.remaining = 0;
            result.message = "点了广告入口，但视频没起来（等了 "
                    + AD_START_TIMEOUT_MS / 1000 + " 秒）"
                    + (gone ? "——今天的次数其实已经领完了，点到的是那行不可点的标题"
                            : "，停在第 " + i + " 个");
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

        // 回到菠萝包之后，代券是它自己弹一张「领取成功 ＋3 开心收下／已发放到"我的-我的钱包"中」
        // 来发的（2026-08-23 14:42 实测，用户截图确认）。这张弹窗盖在签到面板上，面板因此怎么
        // 刷都刷不出来；而按下那颗「开心收下」既是把奖励收进账号，也是<b>最硬的到账证据</b> ——
        // 比面板上那句不会就地刷新的次数可靠得多。所以先收下，再去读次数。
        //
        // 2026-08-24 09:28 的教训：这张卡是<b>延迟</b>弹的（那次第 3 支广告回来时它还没出现），
        // 只查一次就走会漏掉它，而漏掉的后果不是少一条佐证 —— 它是模态卡，会一直盖着首页，
        // 让后面每一个号的 ensureHome 都在「等 mine_tab」超时。所以这里给它一个等待窗口。
        boolean granted = pressGrantIfAny(r);

        int now = readRemaining(r);
        // 面板上那句「今日还剩 N 次」是打开面板的那一刻拉的，从广告页回来它一个字都不会变：
        // 2026-08-23 14:31 那一支实测，代券真的到了账（force-stop 菠萝包重开再看是 1），
        // 面板上却还写着 2，于是脚本拿一张过期的界面判「没确认到奖励」并停下。所以次数没变时
        // 先把面板关掉重开一次，让它重新拉一遍再读。
        //
        // 读不到次数（-1）最常见的原因也是那张发放卡：它一盖上来，整棵树里只有它那几个节点。
        // 所以在刷面板之前再等它一次 —— 这一次值得多等几秒，因为不按掉它后面全都跑不动。
        if (now < 0 || (before >= 0 && now == before)) {
            if (!granted) granted = awaitGrant(r, GRANT_WAIT_MS) && pressGrantIfAny(r);
            refreshSignPanel(r);
            now = readRemaining(r);
        }
        if (now >= 0) result.remaining = now;

        // 验收：剩余次数减了才算领到。读不到次数时退一步，用「回到了签到页」当佐证。
        boolean rewarded = before >= 0 && now >= 0 ? now < before : back;
        if (!rewarded && granted) {
            // 「领取成功」那张弹窗自己写明了代券已发放到钱包，比面板上的旧数字可信。
            rewarded = true;
            r.log("  面板上的次数还写着 " + now + "（它不会就地刷新），但菠萝包已经弹过"
                    + "「领取成功」并且我按下了「开心收下」，按到账算");
        }
        if (!rewarded && play == Play.EARNED) {
            // 第三证据：播放页自己写了「恭喜获得奖励／已完成浏览N秒，提前获得奖励」。
            // 今天三支实测都是这句出现之后代券确实到账（面板 3→2→1→已领完），所以按到账算 ——
            // 但要在日志里说清依据是哪一条，别让「已看 N 个」看着像凭空来的。
            rewarded = true;
            r.log("  界面上的次数还是 " + now + "（面板没刷出来），但播放页明确写了「恭喜获得奖励」，"
                    + "按到账算；下一支开始前会再核对一次次数");
        }
        if (!rewarded) {
            result.skipped = true;
            result.message = "第 " + i + " 个广告没确认到奖励（"
                    + (before >= 0 && now >= 0 ? "剩余次数还是 " + now : "没回到签到页")
                    + "），先停下，免得白点";
            return Loop.STOP;
        }
        result.watched++;
        // 这里刻意用 now、不用 result.remaining：读不到次数时 result.remaining 还是上一支留下的
        // 旧数字，打出来就成了「已到账，还剩 4 次」这种看着确定、其实过期的话（2026-08-24 那趟
        // 第 3 支就是这么打的，实际那会儿面板被发放卡盖着，一个数字都读不到）。
        r.log("  第 " + i + " 个广告的奖励已到账"
                + (now >= 0 ? "，还剩 " + now + " 次"
                : "（这会儿读不出面板上的次数，下一支开始前会再核对一次）"));
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
     * 有那张代券发放卡就按下「开心收下」。
     *
     * <p>先认 {@link Keys#REWARD_GRANT}（只认这张卡自己的节点），认不到再退回
     * {@link Keys#AD_CLAIM}（广告 SDK 自己那颗「领取奖励」）。顺序不能反：宽的那一组
     * 在别的页面上也可能命中。
     *
     * @return true = 真的按下去了（＝奖励收进账号了，这是最硬的到账证据）
     */
    private static boolean pressGrantIfAny(StepRunner r) throws StepRunner.StepFailure {
        StepRunner.Outcome grant = r.findAny(Keys.REWARD_GRANT);
        if (grant == null) grant = r.findAny(Keys.AD_CLAIM);
        if (grant == null) return false;
        r.log("  菠萝包弹出了「领取成功」，替你按下「开心收下」把代券收进账号");
        return r.pressOrLog(grant.key, grant.node);
    }

    /**
     * 等那张发放卡冒出来（菠萝包是延迟弹的，实测能晚好几秒）。
     *
     * <p>只在「次数读不到或没变」的时候才等，所以正常那条路不会白等：面板一读就出数字的那几支
     * 广告（2026-08-24 的第 1 支就是）根本走不到这里。
     */
    private static boolean awaitGrant(StepRunner r, long timeoutMs) throws StepRunner.StepFailure {
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean logged = false;
        while (System.currentTimeMillis() < deadline) {
            r.checkCancelled();
            if (r.findAny(Keys.REWARD_GRANT) != null) return true;
            if (!logged) {
                r.log("  面板读不出来，等一下菠萝包那张「开心收下」（它常常晚几秒才弹）");
                logged = true;
            }
            r.waitMillis(POLL_MS);
        }
        return false;
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

    /**
     * 把签到面板关掉重开一次，好让「今日还剩 N 次」重新拉一遍。
     *
     * <p>2026-08-23 14:31 实测：那一支广告的代券确实到了账（force-stop 菠萝包重开之后面板写着
     * 「今日还剩1次」），但从广告页回来时面板上那句<b>一个字都没变</b>，还是打开面板那一刻的 2。
     * 脚本于是把已经到手的奖励记成「没确认到」并停下，白丢了这个号剩下的广告。
     *
     * <p>只按返回 + 点我们自己认得的那颗签到入口，读不到就交回调用方；失败不抛出去 ——
     * 刷不出来顶多是少一条佐证，不该因此把这个号判失败。
     */
    private static void refreshSignPanel(StepRunner r) throws StepRunner.StepFailure {
        r.log("  面板上的次数是打开时拉的，从广告页回来不会自己变 —— 关掉重开一次再读");
        try {
            r.back();
            StepRunner.Outcome entry = r.findAny(Keys.CHECKIN_ENTRY, Keys.CHECKIN_DONE);
            if (entry == null) {
                StepRunner.Outcome shelf = r.findAny(Keys.SHELF_TAB);
                if (shelf != null) r.pressOrLog(Keys.SHELF_TAB, shelf.node);
                entry = r.findAny(Keys.CHECKIN_ENTRY, Keys.CHECKIN_DONE);
            }
            if (entry != null) r.pressOrLog(entry.key, entry.node);
            r.waitForAny(8_000, Keys.AD_REMAINING, Keys.AD_REWARD, Keys.CHECKIN_DIALOG);
        } catch (StepRunner.StepFailure e) {
            if (e.kind == StepRunner.Kind.CANCELLED) throw e;
            r.log("  " + e.getMessage());
        }
    }

    /** 只提醒模式：停下来等你自己看完点「继续」。 */
    private static boolean promptOne(StepRunner r, String accountName, int i, int todo,
                                    Result result) throws StepRunner.StepFailure {
        if (r.findAny(Keys.AD_EXHAUSTED) != null) {
            result.remaining = 0;
            result.message = "今天的广告次数已经领完了（面板上写着「已领完」）";
            return false;
        }
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

    /**
     * 今天还能看几个。
     *
     * <p>顺序是「先看有没有『已领完』，再读计数」：领完之后计数那格（{@code sign_in_ad_count}）
     * 的文案是「明日更新次数」，一个数字都没有，只有旁边那颗 {@code sign_in_ad_finished}
     * 写着「已领完」。反过来先读计数就会读出 -1，调用方按配额去点一个已经没有的入口。
     */
    private static int readRemaining(StepRunner r) throws StepRunner.StepFailure {
        if (r.findAny(Keys.AD_EXHAUSTED) != null) return 0;
        int n = Texts.parseRemaining(r.peekText(Keys.AD_REMAINING));
        if (n >= 0) return n;
        return Texts.parseRemaining(r.peekText(Keys.AD_REWARD));
    }
}
