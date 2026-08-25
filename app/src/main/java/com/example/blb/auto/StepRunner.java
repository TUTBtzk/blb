package com.example.blb.auto;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;

import com.example.blb.util.Texts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 流程引擎：把「等控件出现 / 点它 / 填字 / 读文本」这些动作串起来。
 *
 * <p>阻塞式，跑在 {@link AutomationService} 的工作线程上；靠轮询根节点推进，
 * 不依赖无障碍事件（事件噪声大、会丢，且不同机型时序差别很大）。
 */
public class StepRunner {

    private static final String TAG = "BlbAuto";
    private static final long POLL_MS = 400;
    /** {@link #ensureHome} 里最多替你清几下广告残局，免得和广告的确认框来回死磕。 */
    private static final int MAX_AD_ESCAPES = 4;
    /**
     * 点完三方登录图标之后，等那个 App 起身的宽限。这段时间里「前台还是菠萝包」不算
     * 「不需要确认」—— 见 {@link #confirmThirdPartyAuth}。
     */
    private static final long AUTH_LAUNCH_GRACE_MS = 6_000;
    /** 下拉通知栏／系统浮层的包名。它吃不到返回键，只能用 HOME 收掉。 */
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";

    /** 撞到需要人工处理的情况时，用户的选择。 */
    public enum Decision { CONTINUE, SKIP, ABORT }

    public interface Host {
        boolean isCancelled();

        void log(String message);

        /** 阻塞直到用户在通知/界面上做出选择。 */
        Decision awaitUser(String reason);
    }

    /**
     * 失败的种类。{@code MONEY_UNCLEAR} 是「点过『立即下载』但结果不明，券可能已经扣了」——
     * 这种情况必须整趟停下（见 {@link SubscribeTask.Result#abortRun}），不许换个号接着点。
     */
    public enum Kind { TIMEOUT, CANCELLED, SKIPPED, CAPTCHA, NO_SERVICE, NEEDS_LAUNCH, CONFIG,
        LOGIN_FAILED, MONEY_UNCLEAR }

    public static class StepFailure extends Exception {
        public final Kind kind;

        public StepFailure(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }
    }

    /** 命中了哪个 key、命中的是哪个节点。 */
    public static final class Outcome {
        public final String key;
        public final NodeView node;

        Outcome(String key, NodeView node) {
            this.key = key;
            this.node = node;
        }
    }

    private final Context appContext;
    private final SelectorSet selectors;
    private final Host host;
    private final Random random = new Random();

    public StepRunner(Context context, SelectorSet selectors, Host host) {
        this.appContext = context.getApplicationContext();
        this.selectors = selectors;
        this.host = host;
    }

    public SelectorSet selectors() {
        return selectors;
    }

    // ---------- 查找与等待 ----------

    private BlbAccessibilityService service() throws StepFailure {
        BlbAccessibilityService svc = BlbAccessibilityService.peek();
        if (svc == null) throw new StepFailure(Kind.NO_SERVICE, "无障碍服务未开启");
        return svc;
    }

    /** 长时间自己等待的步骤（等广告播完）要定期调用，保证「停止」按得动。 */
    public void checkCancelled() throws StepFailure {
        if (host.isCancelled()) throw new StepFailure(Kind.CANCELLED, "已取消");
    }

    /**
     * 非阻塞：看这批 key 里有没有已经在屏幕上的，返回第一个命中的，没有返回 null。
     *
     * <p>先在活动窗口里找（原来的行为一点没变），找不到才去菠萝包<b>别的</b>窗口里找。
     * 广告播放页实测是好几层窗口叠起来的，活动窗口那层可能是一个连字都没有的 WebView ——
     * 那颗要点的按钮在另一层里。顺序是「活动窗口优先」，所以不会因为背后还挂着签到页
     * 就把播放中的广告误判成「已经回到签到页了」。
     */
    public Outcome findAny(String... keys) throws StepFailure {
        BlbAccessibilityService svc = service();
        Outcome hit = findAnyIn(svc.root(), keys);
        if (hit != null) return hit;
        return findAnyIn(svc.otherWindows(), keys);
    }

    private Outcome findAnyIn(NodeView root, String... keys) {
        if (root == null) return null;
        for (String key : keys) {
            List<Selector> candidates = selectors.get(key);
            if (candidates.isEmpty()) continue;
            NodeMatcher.Hit hit = NodeMatcher.find(root, candidates);
            if (hit != null) return new Outcome(key, hit.node);
        }
        return null;
    }

    /**
     * 只在某个子树里找这个 key。用来判断「这一行有没有锁」——同一屏上别的章节也有锁，
     * 全局找会认错行，必须限定在目标行的子树里。
     */
    public NodeView findIn(NodeView subtree, String key) {
        if (subtree == null) return null;
        List<Selector> candidates = selectors.get(key);
        if (candidates.isEmpty()) return null;
        NodeMatcher.Hit hit = NodeMatcher.find(subtree, candidates);
        return hit == null ? null : hit.node;
    }

    /**
     * 只在某个子树里找这个 key 的<b>所有</b>命中。
     *
     * <p>用来判断「这一层到底是不是『一行』」：订阅明细里的金额和币种都没有 resource-id，
     * 只能按文本认，而整屏上每一条都各有一个 —— 命中<b>正好一个</b>才说明这一层就是那一行，
     * 命中好几个说明已经走到整张列表上去了（见 {@code SubscribedDetail.rowOf}）。
     */
    public List<NodeView> findAllIn(NodeView subtree, String key) {
        if (subtree == null) return new ArrayList<>();
        for (Selector selector : selectors.get(key)) {
            List<NodeView> hits = NodeMatcher.findAll(subtree, selector);
            if (!hits.isEmpty()) return hits;
        }
        return new ArrayList<>();
    }

    /**
     * 把当前活动窗口上这个 key 匹配到的<b>所有</b>节点都拿回来，按候选选择器逐条试，
     * 第一条有命中的就用它的结果。
     *
     * <p>{@link #findAny} 和 {@link #findIn} 都只给第一个命中的节点，扫章节列表要的是
     * 「这一屏上的十几行」，所以单独开一个口子。只看活动窗口：章节列表不会画在别的窗口里，
     * 而把 {@code otherWindows()} 也算进来会把背景里另一层残留的列表混进来。
     *
     * <p>候选之间不做并集：{@code chapter_row_title} 那一组是「先按 id 认，认不到再按别的」的
     * 退化链，并集会把同一行按两种方式各记一遍。
     */
    public List<NodeView> findAllOn(String key) throws StepFailure {
        NodeView root = service().root();
        if (root == null) return new ArrayList<>();
        for (Selector selector : selectors.get(key)) {
            List<NodeView> hits = NodeMatcher.findAll(root, selector);
            if (!hits.isEmpty()) return hits;
        }
        return new ArrayList<>();
    }

    /**
     * 轮询等这批 key 里任意一个出现。
     *
     * <p>用「等多个」而不是「等一个」，是因为流程里到处是分支：点完登录可能出现昵称、
     * 验证码或密码错误提示；进签到页可能是签到按钮也可能是「已签到」。只等一个就得靠
     * 超时来区分分支，既慢又容易误判。
     */
    public Outcome waitForAny(long timeoutMs, String... keys) throws StepFailure {
        ensureConfigured(keys);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            checkCancelled();
            Outcome hit = findAny(keys);
            if (hit != null) return hit;
            if (System.currentTimeMillis() >= deadline) {
                // 带上此刻的前台包名：超时最常见的原因根本不是选择器不对，而是我们已经不在
                // 菠萝包上了（别的 App 顶上来、或者被弹窗盖住）。光一句「等 xxx 超时」看不出
                // 这件事 —— 2026-08-23 那趟 iierr89／好好上课是 就白查了一轮。
                throw new StepFailure(Kind.TIMEOUT,
                        "等 " + TextUtils.join("/", keys) + " 超时 " + timeoutMs + "ms"
                                + foregroundNote());
            }
            sleep(POLL_MS);
        }
    }

    public NodeView waitFor(String key, long timeoutMs) throws StepFailure {
        return waitForAny(timeoutMs, key).node;
    }

    /** 超时文案的后缀：只有在「顶上不是菠萝包」时才加一句，正常情况下不啰嗦。 */
    private String foregroundNote() {
        try {
            BlbAccessibilityService svc = BlbAccessibilityService.peek();
            if (svc == null) return "（无障碍服务这会儿是断开的）";
            String top = svc.activePackage();
            if (top == null) return "（读不到当前前台是谁）";
            if (BlbAccessibilityService.TARGET_PACKAGE.equals(top)) return "";
            return "（当前前台是 " + top + "，不是菠萝包）";
        } catch (Exception e) {
            return "";
        }
    }

    /** 这批 key 一个都没配时直接报配置错误，而不是白等一轮超时。 */
    private void ensureConfigured(String... keys) throws StepFailure {
        List<String> missing = new ArrayList<>();
        for (String k : keys) {
            if (selectors.get(k).isEmpty()) missing.add(k);
        }
        if (missing.size() == keys.length) {
            throw new StepFailure(Kind.CONFIG,
                    "selectors.json 里没有配：" + TextUtils.join("、", missing));
        }
    }

    // ---------- 动作 ----------

    public void click(String key, long timeoutMs) throws StepFailure {
        clickNode(key, waitFor(key, timeoutMs));
    }

    public void clickNode(String label, NodeView node) throws StepFailure {
        BlbAccessibilityService.Press p = service().press(node);
        if (p != BlbAccessibilityService.Press.OK) {
            throw new StepFailure(Kind.TIMEOUT, "点不动：" + label + describe(p, node));
        }
        host.log("点击 " + label);
        sleepHuman();
    }

    /**
     * 点一下，按不动只记日志、返回 false，不抛异常。
     *
     * <p>给广告流程用：一颗按钮按不动只该让这一支广告作废，不该把整个账号（连它剩下的
     * 广告一起）判失败。
     */
    public boolean pressOrLog(String label, NodeView node) throws StepFailure {
        BlbAccessibilityService.Press p = service().press(node);
        if (p != BlbAccessibilityService.Press.OK) {
            host.log("  点不动：" + label + describe(p, node));
            return false;
        }
        host.log("点击 " + label);
        sleepHuman();
        return true;
    }

    /**
     * 点「整张卡」的中心：认不出卡上哪颗是按钮时的退路，见
     * {@link BlbAccessibilityService#pressCard}。按不动只记日志、返回 false。
     */
    public boolean pressCardOrLog(String label, NodeView node) throws StepFailure {
        BlbAccessibilityService.Press p = service().pressCard(node);
        if (p != BlbAccessibilityService.Press.OK) {
            host.log("  点不动：" + label + describe(p, node));
            return false;
        }
        host.log("点击 " + label);
        sleepHuman();
        return true;
    }

    /** 菠萝包现在挂着几个窗口。广告播放页是多层窗口叠起来的，写进日志好对照。 */
    public int windowCount() throws StepFailure {
        return service().windowCount();
    }

    /**
     * 认不出按钮、连整张卡的矩形都读不出来时，按屏幕几何位置点正中偏下。
     * 见 {@link BlbAccessibilityService#pressScreenCenter}；只许在确认促销卡在屏幕上之后用。
     */
    public boolean pressScreenCenterOrLog(String label) throws StepFailure {
        BlbAccessibilityService.Press p = service().pressScreenCenter();
        if (p != BlbAccessibilityService.Press.OK) {
            host.log("  点不动：" + label + describe(p, null));
            return false;
        }
        host.log("点击 " + label);
        sleepHuman();
        return true;
    }

    /**
     * 点右上角那颗关闭 X，见 {@link BlbAccessibilityService#pressCloseCorner}。
     * 只许在奖励已经到手（ad_earned 在屏幕上）之后调用 —— 同一位置在别家 SDK 上是「跳过」。
     */
    public boolean pressCloseCornerOrLog(String label) throws StepFailure {
        BlbAccessibilityService.Press p = service().pressCloseCorner();
        if (p != BlbAccessibilityService.Press.OK) {
            host.log("  点不动：" + label + describe(p, null));
            return false;
        }
        host.log("点击 " + label);
        sleepHuman();
        return true;
    }

    /**
     * 上「把你从落地页带回来」的闹钟。落地页上系统可能把我们冻住，见 {@link ReturnWatchdog}。
     */
    public void armReturnWatchdog(long dwellMs) {
        ReturnWatchdog.arm(appContext, dwellMs);
    }

    /** 自己回来了就撤掉闹钟。 */
    public void disarmReturnWatchdog() {
        ReturnWatchdog.disarm(appContext);
    }

    /** 这一段时间里闹钟响过吗（响过说明我们在外面被冻住／被杀过，要如实写进日志）。 */
    public boolean returnWatchdogFired(long sinceElapsedRealtime) {
        return ReturnWatchdog.firedSince(sinceElapsedRealtime);
    }

    /** 把「点不动」的原因写成人话，附上节点位置——不然日志里没法判断该改哪儿。 */    private static String describe(BlbAccessibilityService.Press p, NodeView node) {
        String where = "";
        if (node != null) {
            int[] b = node.boundsInScreen();
            if (b != null && b.length == 4) {
                where = " bounds=[" + b[0] + "," + b[1] + "][" + b[2] + "," + b[3] + "]";
            }
        }
        switch (p) {
            case NO_NODE:
                return "（这一刻界面被换掉了，拿不到节点）";
            case NO_BOUNDS:
                return "（按钮位置读不出来，" + where.trim() + "）";
            case REFUSED:
                return "（系统拒绝了模拟点击，无障碍连接可能刚断过）" + where;
            case CANCELLED:
                return "（手势被系统取消，已重试一次）" + where;
            default:
                return where;
        }
    }

    public void setText(String key, String value, long timeoutMs) throws StepFailure {
        NodeView node = waitFor(key, timeoutMs);
        if (!service().setText(node, value)) {
            throw new StepFailure(Kind.TIMEOUT, "填不进去：" + key);
        }
        sleepHuman();
    }

    /** 读文本；读不到返回 null（例如昵称还没加载出来），不当成失败。 */
    public String readText(String key, long timeoutMs) {
        try {
            NodeView node = waitFor(key, timeoutMs);
            return node == null ? null : node.text();
        } catch (StepFailure e) {
            return null;
        }
    }

    /**
     * 只看当前屏，不等待。用在「有就读、没有就算了」的地方（广告剩余次数），
     * 免得每次都白等一轮超时。文本为空时退而取 contentDescription。
     */
    public String peekText(String key) throws StepFailure {
        Outcome hit = findAny(key);
        if (hit == null) return null;
        String text = hit.node.text();
        return Texts.isBlank(text) ? hit.node.desc() : text;
    }

    /** 把一行进度写到日志和通知上。 */
    public void log(String message) {
        host.log(message);
    }

    /**
     * 读当前屏上的余额，不等待、不翻页。
     *
     * <p>三种页面都认（见 selectors.json 的 {@code _note_coupons}）：批量购买页那一行
     * 「账户余额：0火券/0代券」两种券在同一段文字里；我的钱包页两颗数字各有自己的 id；
     * 「我的」页那一行数字<b>连 id 都没有</b>，只能靠标签定位、取标签正上方那个数字。
     */
    public Texts.Balance peekBalance() throws StepFailure {
        BlbAccessibilityService svc = service();
        Texts.Balance hit = BalanceReader.read(svc.root(), selectors::get);
        if (hit.known()) return hit;
        return BalanceReader.read(svc.otherWindows(), selectors::get);
    }

    /**
     * 读当前页面上的余额，最多等 {@code timeoutMs}（数字常常比页面骨架晚一点画出来）。
     * 读不到就是 -1/-1，表示「不知道」，调用方不要拿它覆盖库里已有的值。
     */
    public Texts.Balance readBalance(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        while (true) {
            Texts.Balance hit;
            try {
                hit = peekBalance();
            } catch (StepFailure e) {
                return Texts.balance(-1, -1);
            }
            if (hit.known()) return hit;
            if (System.currentTimeMillis() >= deadline) return Texts.balance(-1, -1);
            sleep(POLL_MS);
        }
    }

    /**
     * 到「我的」页上把余额读回来。
     *
     * <p>为什么非得专门跑一趟：签到面板是<b>独立窗口</b>（实测 dump 只有 78 个节点，底部那排
     * tab 根本不在树里），而且面板上一个余额数字都没有 —— 签完到就地读余额永远是「读不到」，
     * 这就是 2026-08-23 用户报的「所有账号都无法识别有多少代券」。所以先按返回把面板收掉、
     * 切到「我的」，再读那一行。
     *
     * <p>读不到只返回未知，绝不让「读余额」这件小事把整个号判失败；只有「已取消」照旧往上抛。
     */
    public Texts.Balance readBalanceFromMine() throws StepFailure {
        try {
            ensureHome(2);
            click(Keys.MINE_TAB, 10_000);
            // 等这一页真的画出来（昵称和余额是一起下来的），再读余额。
            readText(Keys.NICKNAME, 6_000);
            return readBalance(2_500);
        } catch (StepFailure e) {
            if (e.kind == Kind.CANCELLED) throw e;
            host.log("  没读到余额：" + e.getMessage());
            return Texts.balance(-1, -1);
        }
    }

    /**
     * 到「我的」页看一眼<b>现在登着谁</b>。读不到返回 null。
     *
     * <p>给「今天已经签完、被跳过的号」用：只有昵称对上了才顺手把它的余额刷新一下，
     * 绝不为了读一个数字去切号 —— 切号意味着退登重登，是最招验证码的动作。
     */
    public String readNicknameFromMine() throws StepFailure {
        try {
            ensureHome(2);
            click(Keys.MINE_TAB, 10_000);
            String name = readText(Keys.NICKNAME, 6_000);
            return Texts.isBlank(name) ? null : name.trim();
        } catch (StepFailure e) {
            if (e.kind == Kind.CANCELLED) throw e;
            return null;
        }
    }

    /** 挂起队列等你在通知或界面上做选择。 */
    public Decision awaitUser(String reason) {
        return host.awaitUser(reason);
    }

    public void back() throws StepFailure {
        service().back();
        sleepHuman();
    }

    public boolean isTargetForeground() throws StepFailure {
        return service().isTargetForeground();
    }

    /** 当前前台包名，只用来写日志和判断「是不是被带到别的 App 去了」。 */
    public String activePackage() throws StepFailure {
        return service().activePackage();
    }

    /**
     * 在会触发风控的动作之后调用（登录、连续订阅）。命中验证码就停下等人工，
     * 不做任何自动过验证的尝试。
     */
    public void guardCaptcha() throws StepFailure {
        Outcome hit = findAny(Keys.CAPTCHA_HINT);
        if (hit == null) return;
        host.log("检测到安全验证，已暂停");
        Decision d = host.awaitUser("菠萝包要求安全验证，请手动完成后点「继续」");
        if (d == Decision.SKIP) throw new StepFailure(Kind.CAPTCHA, "撞验证码，跳过该账号");
        if (d == Decision.ABORT) throw new StepFailure(Kind.CANCELLED, "撞验证码，已中止");
    }

    // ---------- 按屏幕上的文字找（书名、章节名这类运行时才知道的目标） ----------

    /** 用运行时才知道的文字临时拼一条选择器：先试完全相等，再试包含。 */
    public Outcome findByText(String label, String text) throws StepFailure {
        if (Texts.isBlank(text)) return null;
        Selector exact = new Selector();
        exact.text = text.trim();
        exact.clickableAncestor = true;
        Selector loose = new Selector();
        loose.textContains = text.trim();
        loose.clickableAncestor = true;

        NodeMatcher.Hit hit = findEverywhere(Arrays.asList(exact, loose));
        return hit == null ? null : new Outcome(label, hit.node);
    }

    /** 只认完全相等的文本。「第12章」用包含匹配会先撞上「第120章」。 */
    public Outcome findExactText(String label, String text) throws StepFailure {
        if (Texts.isBlank(text)) return null;
        Selector exact = new Selector();
        exact.text = text.trim();
        exact.clickableAncestor = true;
        NodeMatcher.Hit hit = findEverywhere(Collections.singletonList(exact));
        return hit == null ? null : new Outcome(label, hit.node);
    }

    /**
     * 在某个 key 的候选里挑「文本正好等于 text」的那一个。
     *
     * <p>专门给搜书用：书名只有跑起来才知道，写不进 selectors.json；而「书名画在哪个 id 上」
     * 又必须留在 json 里。这个方法把两半拼起来（{@link Selector#withText}）。
     *
     * <p>真机实测的教训：搜索输入框 {@code inputSearch} 的文本也正好等于刚输进去的书名，
     * 纯按文本找会先命中输入框 —— 点它什么都不会发生，然后一直等不到「目录」。
     * 限定 id 就同时把输入框和「以“…”为关键字进行搜索」那条排掉了。
     */
    public Outcome findRowWithText(String key, String label, String text) throws StepFailure {
        if (Texts.isBlank(text)) return null;
        for (Selector candidate : selectors.get(key)) {
            Selector s = candidate.withText(text);
            if (s.isEmpty()) continue;
            NodeMatcher.Hit hit = findEverywhere(Collections.singletonList(s));
            if (hit != null) return new Outcome(label, hit.node);
        }
        return null;
    }

    /**
     * 用运行时拼出来的正则找。目录行是「第十一章 久违的笑」这种「标号+标题」合在一个
     * 文本节点里的写法，相等匹配不行、包含匹配又会误伤，只能靠正则锚住开头。
     */
    public Outcome findByRegex(String label, String regex) throws StepFailure {
        if (Texts.isBlank(regex)) return null;
        Selector s = new Selector();
        s.textRegex = regex;
        s.clickableAncestor = true;
        NodeMatcher.Hit hit = findEverywhere(Collections.singletonList(s));
        return hit == null ? null : new Outcome(label, hit.node);
    }

    /** 活动窗口优先，其次才是菠萝包别的窗口（弹窗是独立窗口，见 {@link #findAny}）。 */
    private NodeMatcher.Hit findEverywhere(List<Selector> candidates) throws StepFailure {
        BlbAccessibilityService svc = service();
        NodeMatcher.Hit hit = NodeMatcher.find(svc.root(), candidates);
        return hit != null ? hit : NodeMatcher.find(svc.otherWindows(), candidates);
    }

    /** 往下翻页找某个 key。菠萝包的「设置」在「我的」页最底下，不翻根本看不见。 */
    public Outcome scrollToKey(String key, int maxScrolls) throws StepFailure {
        for (int i = 0; i <= maxScrolls; i++) {
            checkCancelled();
            Outcome hit = findAny(key);
            if (hit != null) return hit;
            if (i == maxScrolls || !scrollForward()) return null;
            sleepHuman();
        }
        return null;
    }

    /** 往下翻页找某段文字，找到就返回它；翻够次数还没有就返回 null。 */
    public Outcome scrollToText(String label, String text, int maxScrolls) throws StepFailure {
        for (int i = 0; i <= maxScrolls; i++) {
            checkCancelled();
            Outcome hit = findByText(label, text);
            if (hit != null) return hit;
            if (i == maxScrolls || !scrollForward()) return null;
            sleepHuman();
        }
        return null;
    }

    /**
     * 同上，但只认<b>完全相等</b>的文本。
     *
     * <p>搜书必须用这一个：搜索结果页第一行是「以"发小竟然是后悔文男主"为关键字进行搜索」，
     * 包含匹配会抢先命中它，点下去进不了详情页。
     */
    public Outcome scrollToExactText(String label, String text, int maxScrolls) throws StepFailure {
        for (int i = 0; i <= maxScrolls; i++) {
            checkCancelled();
            Outcome hit = findExactText(label, text);
            if (hit != null) return hit;
            if (i == maxScrolls || !scrollForward()) return null;
            sleepHuman();
        }
        return null;
    }

    /** 往下翻页找「某个 key 的候选里文本正好等于 text」的那一行（搜书结果页要翻）。 */
    public Outcome scrollToRowWithText(String key, String label, String text, int maxScrolls)
            throws StepFailure {
        for (int i = 0; i <= maxScrolls; i++) {
            checkCancelled();
            Outcome hit = findRowWithText(key, label, text);
            if (hit != null) return hit;
            if (i == maxScrolls || !scrollForward()) return null;
            sleepHuman();
        }
        return null;
    }

    /** 优先让最靠里的可滚动容器自己滚，没有就退化成手势滑动。 */
    public boolean scrollForward() throws StepFailure {
        BlbAccessibilityService svc = service();
        NodeView scrollable = NodeMatcher.findScrollable(svc.root());
        if (scrollable != null && svc.scrollForward(scrollable)) return true;
        return svc.swipeUp();
    }

    /** 同上，反方向。 */
    public boolean scrollBackward() throws StepFailure {
        BlbAccessibilityService svc = service();
        NodeView scrollable = NodeMatcher.findScrollable(svc.root());
        if (scrollable != null && svc.scrollBackward(scrollable)) return true;
        return svc.swipeDown();
    }

    /**
     * 把当前页面滚回顶部。
     *
     * <p>实测按下「退出登录」之后直接回到「我的」页，而那个 ScrollView <b>还停在底部</b>，
     * 「立即登录」不在可见树里 —— 不滚回去就永远点不到它。所以这一步是切号链路的一环，
     * 不是保险。滚够次数也不报错：调用方紧接着就要找一个具体的 key，让那一步去报更具体的话。
     */
    public void scrollToTop(int times) throws StepFailure {
        for (int i = 0; i < times; i++) {
            checkCancelled();
            if (!scrollBackward()) return;
            sleepHuman();
        }
    }

    /**
     * 三方登录点完图标之后，在那个 App 里按一下「同意」。
     *
     * <p>这是本 App 唯一会去读 com.sfacg 之外界面的地方，界限写死在三处：
     * <ul>
     *   <li>只在这个方法执行期间开界外硬闸（{@link BlbAccessibilityService#armAuthGate()}），
     *       {@code finally} 里一定关掉；</li>
     *   <li>只用 {@link Keys#LOGIN_AUTH_CONFIRM} 这一组选择器去匹配，不查别的 key、
     *       不导出节点树、不把那个 App 里的任何文字写进日志；</li>
     *   <li>树只从 {@link BlbAccessibilityService#authRoot()} 拿，它自己再验一次包名。</li>
     * </ul>
     *
     * <p>实测只有 QQ 有这一步（跳 com.tencent.mobileqq 的 PublicFragmentActivityForOpenSDK，
     * 底部一颗 Button「同意」）；微信和微博点完图标就直接登回菠萝包了，那时前台一直是
     * com.sfacg，这里一个节点都不会读，直接返回 false。
     *
     * <p><b>2026-08-23 19:50 真机日志暴露的那个坑</b>：点完 QQ 图标的那一瞬间，QQ 还没起来，
     * 前台仍然是 com.sfacg —— 旧写法第一圈就撞上「回到菠萝包了」直接 return，于是授权页顶上来
     * 之后没人按那颗「同意」，人被留在 QQ 里，接着只看到一句「等 mine_tab 超时」。所以前
     * {@link #AUTH_LAUNCH_GRACE_MS} 毫秒里「前台还是菠萝包」不算数，要给三方 App 起身的时间；
     * 只有真的跳出去过（或者过了这段宽限还没动静）才认。
     *
     * @return 真的按下过那颗「同意」才返回 true
     */
    public boolean confirmThirdPartyAuth(String appLabel, long timeoutMs) throws StepFailure {
        BlbAccessibilityService svc = service();
        List<Selector> candidates = selectors.get(Keys.LOGIN_AUTH_CONFIRM);
        if (candidates.isEmpty()) {
            throw new StepFailure(Kind.CONFIG,
                    "selectors.json 里没有配：" + Keys.LOGIN_AUTH_CONFIRM);
        }
        long now = System.currentTimeMillis();
        long deadline = now + timeoutMs;
        long graceUntil = now + Math.min(AUTH_LAUNCH_GRACE_MS, timeoutMs);
        boolean pressed = false;
        boolean left = false;
        svc.armAuthGate();
        try {
            while (System.currentTimeMillis() < deadline) {
                checkCancelled();
                if (svc.isTargetForeground()) {
                    // 跳出去过又回来了＝这一步结束；从没跳出去、宽限也过了＝这一家不需要确认。
                    if (pressed || left || System.currentTimeMillis() >= graceUntil) return pressed;
                    sleep(POLL_MS);
                    continue;
                }
                left = true;
                if (!pressed) {
                    NodeView authRoot = svc.authRoot();
                    NodeMatcher.Hit hit = authRoot == null
                            ? null : NodeMatcher.find(authRoot, candidates);
                    if (hit != null) {
                        host.log("在" + appLabel + "的授权页上按「同意」");
                        if (svc.press(hit.node) == BlbAccessibilityService.Press.OK) {
                            pressed = true;
                        } else {
                            host.log("  那颗「同意」按不动，下一轮再试");
                        }
                        sleepHuman();
                        continue;
                    }
                }
                sleep(POLL_MS);
            }
        } finally {
            svc.disarmAuthGate();
        }
        return pressed;
    }

    /**
     * 把菠萝包切到前台。
     *
     * <p>两个实测教训写在这里：
     *
     * <p>1）广告可能把别的 App 拉起来，而有些落地页是<b>透明</b>的（实测残留过
     * {@code com.jingdong.app.mall/.personel.FloatViewActivity}）—— 截图上看着还是菠萝包，
     * 但活动窗口是它，于是「拉不起菠萝包」白等一轮超时。所以顶上是第三方 App 时先按一次返回把
     * 它关掉。只按全局返回，不读也不点那个 App 里的任何东西；顶上是我们自己的界面时不按，
     * 免得把用户的操作界面退掉。
     *
     * <p>2）MIUI 会拦掉后台启动 Activity，一次 startActivity 常常不算数，所以在等待期间每
     * 5 秒再发一次，而不是只在开头发一次。
     */
    public void launchTarget(long timeoutMs) throws StepFailure {
        BlbAccessibilityService svc = service();
        if (svc.isTargetForeground()) return;
        Intent intent = appContext.getPackageManager()
                .getLaunchIntentForPackage(BlbAccessibilityService.TARGET_PACKAGE);
        if (intent == null) {
            throw new StepFailure(Kind.CONFIG, "这台手机上没装菠萝包（com.sfacg）");
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

        long deadline = System.currentTimeMillis() + timeoutMs;
        long nextStart = 0;
        int backs = 0;
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            checkCancelled();
            if (svc.isTargetForeground()) {
                sleepHuman();
                return;
            }
            String top = svc.activePackage();
            if (top != null && !top.equals(last)) {
                host.log("当前前台是 " + top + "，正在把菠萝包拉回来");
                last = top;
            }
            // 透明的第三方落地页盖在上面：按返回关掉它，比反复 startActivity 有效。
            if (backs < 3 && isForeignTop(top)) {
                backs++;
                // 系统界面（下拉的通知栏、MIUI 的浮层）吃不到返回键 —— 实测 2026-08-23 19:50
                // 那一趟就是卡在 com.android.systemui 上，连按三次返回一点用都没有，
                // 后面 4 个号全报「拉不起菠萝包」。这种顶层用 HOME 才收得掉。
                if (SYSTEM_UI_PACKAGE.equals(top)) svc.home();
                else back();
                continue;
            }
            if (System.currentTimeMillis() >= nextStart) {
                nextStart = System.currentTimeMillis() + 5_000;
                try {
                    svc.startActivity(intent);
                } catch (Exception e) {
                    Log.w(TAG, "拉起菠萝包被系统拦下", e);
                }
            }
            sleep(POLL_MS);
        }
        // Android 10+ 限制后台启动 Activity，MIUI 还要单独给「后台弹出界面」权限。
        throw new StepFailure(Kind.NEEDS_LAUNCH,
                "拉不起菠萝包（当前前台是 " + svc.activePackage()
                        + "）。请在系统里给本 App 授予「后台弹出界面」权限，或手动打开菠萝包后重试");
    }

    /** 顶上是别的 App 吗（不算菠萝包，也不算我们自己的界面）。 */
    private boolean isForeignTop(String pkg) {
        return pkg != null
                && !BlbAccessibilityService.TARGET_PACKAGE.equals(pkg)
                && !appContext.getPackageName().equals(pkg);
    }

    /**
     * 把菠萝包切到前台，并保证首页（底部那排 tab）确实在当前窗口里。
     *
     * <p>为什么不能只 {@link #launchTarget}：菠萝包一起来常有弹窗盖在上面（签到面板、
     * 活动弹窗），弹窗是独立窗口，{@code getRootInActiveWindow()} 拿到的就只有弹窗那一层，
     * 底部 tab 根本不在树里 —— 这时候去点「我的」只会白等一轮超时。实测第一次跑就栽在这儿。
     *
     * <p>关弹窗一律用全局返回键，不去点弹窗里的叉：叉的位置各弹窗都不一样，认错了就是乱点。
     * 翻够次数还没看到首页也不报错 —— 可能只是停在书籍详情这类没有底部 tab 的页面，
     * 让后面那一步去报「等不到什么」更具体。
     */
    public void ensureHome(int maxBacks) throws StepFailure {
        launchTarget(25_000);
        int backs = 0;
        int escapes = 0;
        while (true) {
            checkCancelled();
            if (findAny(Keys.HOME_READY) != null) return;
            if (!isTargetForeground()) {
                launchTarget(15_000);
                continue;
            }
            // 清广告残局要按好几下（跳过 → 二次确认 → 坚持退出），这些不算进 maxBacks，
            // 否则返回键的额度还没走到首页就用完了。
            if (escapes < MAX_AD_ESCAPES && escapeStuckAd()) {
                escapes++;
                continue;
            }
            if (backs++ >= maxBacks) {
                // 额度用完还没看到首页。不在这里报错（可能只是停在没有底部 tab 的页面，
                // 让后面那一步报「等不到什么」更具体），但一定要留下这一行 ——
                // 2026-08-24 15:26 那趟有 3 个号只留下一句「等 mine_tab 超时」，
                // 事后完全看不出「返回键额度不够」才是真凶。
                host.log("按了 " + maxBacks + " 次返回还没回到首页（活动窗口 " + activePackage()
                        + "，菠萝包挂着 " + windowCount() + " 个窗口），后面那一步大概会等超时");
                return;
            }
            // 带上活动窗口的包名和窗口数。2026-08-24 09:47 那趟这一行连打了 21 遍，而真相是
            // 我们还站在广告播放页上 —— 它和菠萝包同属 com.sfacg，光看包名分不出来，窗口数
            // 和后面那句「等 mine_tab 超时」合起来才能事后判出「根本不在首页上」。
            host.log("首页被弹窗盖住了，按返回关掉它（活动窗口 " + activePackage()
                    + "，菠萝包挂着 " + windowCount() + " 个窗口）");
            back();
        }
    }

    /**
     * 上一趟没退干净、播放页还挂在最上面时的退路。
     *
     * <p>穿山甲那种「去浏览N秒免看此广告」的卡会把返回键整个吃掉（实测连按 4 次一动不动），
     * 于是整趟任务从第一步「点我的」就超时。这时候按它自己那颗「跳过」把它了结掉 ——
     * 判断条件和 {@link AdWatchTask} 放弃广告时一样（卡还在＝奖励本来就拿不到），
     * 所以不存在「本来能领却被跳掉」。正常播着的广告不会命中这里。
     *
     * <p>「跳过」按下去只会弹一个二次确认（「继续看28秒或下载安装即可领奖，确定要退出吗？」），
     * 而那个确认框是<b>独立窗口</b> —— 弹出来之后 {@code getRootInActiveWindow()} 里只剩确认框
     * 这一层，广告卡上那些字全都看不见了。所以这里先认确认框、再认广告卡：不然第二下就会
     * 认不出自己刚按出来的东西，白按一轮返回。确认框上那颗「去领取奖励」一律不碰 ——
     * 它走的是「下载安装」那条路。
     *
     * <p>还有第三种残局：奖励已经到手、卡在关不掉的领奖弹窗上（实测优量汇那张「恭喜获得奖励」，
     * X 没文字没 id、返回键在它上面一动不动）。这种情形排在最前面单独处理，按位置点那颗 X。
     *
     * <p>第四种、也是排在最前面的一种：菠萝包自己弹的代券发放卡（{@link Keys#REWARD_GRANT}）。
     * 它按返回关不掉，而且盖着它的时候整棵树里只有它 —— 2026-08-24 那趟就是它让后面 7 个号
     * 全部卡在「等 mine_tab 超时」。按「开心收下」是收下代券，不是丢掉。
     *
     * <p>第五种（2026-08-24 09:47 实测）：优量汇的播放页还挂在最上面，而这一家的关闭键
     * <b>既没有 id 也没有文字</b>，{@link Keys#AD_SKIP} 认不出来。要命的是广告 Activity
     * （{@code com.qq.e.ads.PortraitADActivity}）就在 {@code com.sfacg} 包里，
     * {@code isTargetForeground()} 因此一直是 true，{@link #ensureHome} 会把它误诊成
     * 「首页被弹窗盖住了」并连按返回（播放页把返回吃掉）—— 那一趟 8 个号里有 7 个就是这么
     * 每个白等 12 秒、全部签到失败的。所以「卡在／顶栏还在数秒」而认不出「跳过」时，
     * 按位置点右上角那颗关闭。
     */
    private boolean escapeStuckAd() throws StepFailure {
        // 排在最前面：菠萝包自己弹的那张代券发放卡（「+3 开心收下 已发放到"我的-我的钱包"中」）。
        // 它是模态的、按返回关不掉，盖着它的时候底部 tab 一个都不在树里 —— 2026-08-24 09:28 实测
        // 就是它让后面 7 个号全部卡在「等 mine_tab 超时」。按「开心收下」既把代券真正收进账号
        // （不按不算收完），也是唯一能清掉它的动作，所以在任何地方撞见它都直接按。
        Outcome grant = findAny(Keys.REWARD_GRANT);
        if (grant != null) {
            host.log("菠萝包的代券发放卡还挂着，按「开心收下」把代券收进账号（顺便把路让开）");
            return pressOrLog(Keys.REWARD_GRANT, grant.node);
        }
        // 奖励已经到手、只是那张领奖弹窗关不掉（实测优量汇：X 没文字没 id、返回键在它上面
        // 一动不动）。这时候先按位置点掉那颗 X —— 奖励已经到账，点它不会损失任何东西，
        // 而下面那条「按跳过」的路是为「奖励拿不到」准备的，不该拿来处理这种情形。
        if (findAny(Keys.AD_EARNED) != null) {
            host.log("上一趟的领奖弹窗还挂着（奖励已到手），按右上角那颗关闭 X 清掉");
            return pressCloseCornerOrLog("领奖弹窗的关闭 X");
        }
        if (findAny(Keys.AD_LEAVE_CONFIRM) != null) {
            Outcome abandon = findAny(Keys.AD_ABANDON);
            if (abandon == null) return false;
            host.log("广告问要不要退出，按「坚持退出」（这一支的奖励本来也拿不到）");
            return pressOrLog(Keys.AD_ABANDON, abandon.node);
        }
        boolean promo = findAny(Keys.AD_PROMO) != null;
        // 「奖励将于 N 秒后发放」＝我们还站在别人的播放页上、奖励也还没到手。在 ensureHome
        // 这个语境里它只有一个意思：上一支广告没退干净。ensureHome 只在一趟任务的开头和切号
        // 之间调，正在播的广告永远走不到这里，所以不存在「把正在播的广告关掉」。
        boolean pending = !promo && findAny(Keys.AD_PENDING) != null;
        if (!promo && !pending) return false;
        Outcome skip = findAny(Keys.AD_SKIP);
        if (skip != null) {
            host.log("上一趟的广告播放页还挂着且按不动返回键，用它的「跳过」清掉（那一支本来也拿不到奖励）");
            return pressOrLog(Keys.AD_SKIP, skip.node);
        }
        // 2026-08-24 09:47 那趟的教训：优量汇（com.qq.e.ads.PortraitADActivity）的播放页
        // 右上角那颗关闭键既没有 id 也没有文字，「跳过」这一组认不出它，于是这里旧代码直接
        // return false —— 而广告页跟菠萝包<b>同属 com.sfacg 包</b>，isTargetForeground() 照样
        // 是 true，ensureHome 于是把它误诊成「首页被弹窗盖住了」，连按 3 次返回（被播放页吃掉）
        // 再等 mine_tab 超时。结果第 1 个号之后的 7 个号每一个都白等 12 秒、全部签到失败。
        // 认不出「跳过」时就按位置点右上角那颗关闭：这时奖励本来就拿不到，点它只是把它了结掉，
        // 弹出的「放弃奖励离开」下一轮由上面 AD_LEAVE_CONFIRM 那一段收尾。
        host.log(promo
                ? "上一趟那张「点进落地页才给奖」的卡还挂着，这一家连「跳过」都认不出来，"
                + "按右上角那颗关闭把它了结掉（那一支本来也拿不到奖励）"
                : "上一趟的广告播放页还挂着（顶栏还在数「奖励将于几秒后发放」），"
                + "按右上角那颗关闭把它了结掉 —— 广告页和菠萝包是同一个包，不这么做会被"
                + "当成「首页有弹窗」白按一轮返回");
        return pressCloseCornerOrLog("广告播放页的关闭 X");
    }

    /** 步骤之间留一点随机间隔：给界面渲染时间，也不制造异常整齐的高频操作。 */
    public void sleepHuman() {
        sleep(400 + random.nextInt(500));
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 明确要等一段时间时用（等广告按真实时长播完）。 */
    public void waitMillis(long ms) {
        sleep(ms);
    }
}
