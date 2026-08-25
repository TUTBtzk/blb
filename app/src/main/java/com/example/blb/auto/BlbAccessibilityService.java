package com.example.blb.auto;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 唯一能读写菠萝包界面的组件。它只提供「原子操作」（找根节点、点、输入、返回、导出节点树），
 * 具体流程编排在 {@link StepRunner} 里。
 *
 * <p><b>界外硬闸</b>：无障碍配置里放行了微信／QQ／微博（三方登录的授权确认页长在那些 App 里），
 * 但这里只开一道很窄的门 —— {@link #root()} 一律只交出 {@code com.sfacg} 的树，别的 App 的界面
 * 从这条路一个节点都读不到；要读那颗授权键必须走 {@link #authRoot()}，而它只在
 * {@link #armAuthGate()} 之后、且前台正是那三个 App 之一时才交出树。所以「读别人的界面」这件事
 * 在结构上被限制在「正在等一颗授权键」这一小段时间里。
 */
public class BlbAccessibilityService extends AccessibilityService {

    public static final String TARGET_PACKAGE = "com.sfacg";
    private static final String TAG = "BlbAuto";
    /** 按下多久。零长度 + 60 ms 的点击实测按不动 Lynx／Canvas 画出来的按钮。 */
    private static final int TAP_MS = 110;

    /**
     * 允许在「等授权键」这一小段时间里读的三个 App。
     *
     * <p>实测只有 QQ 真的需要（微信和微博点完图标就直接登回菠萝包了），另两个留着是因为
     * 它们随时可能改成也要确认一次 —— 到那时脚本停在授权页上，用户是按不动那颗键的。
     */
    private static final Set<String> AUTH_PACKAGES = new HashSet<>(Arrays.asList(
            "com.tencent.mobileqq", "com.tencent.mm", "com.sina.weibo"));

    private static volatile BlbAccessibilityService instance;

    private volatile String lastEventPackage;

    /** 界外硬闸的开关，只有 {@link StepRunner#confirmThirdPartyAuth} 那一小段时间里是开的。 */
    private volatile boolean authGateOpen;

    /** 服务没开启时返回 null，调用方要给出「去系统设置开启无障碍」的提示。 */
    public static BlbAccessibilityService peek() {
        return instance;
    }

    public static boolean isReady() {
        return instance != null;
    }

    /**
     * 等无障碍服务连上来，最多等 {@code timeoutMs}。
     *
     * <p>为什么要等：MIUI 的 SwipeUpClean 会把 {@code com.example.blb} 整个进程杀掉（实测
     * 2026-08-23 20:26 和 20:35 各一次，{@code Killing …(adj 50): SwipeUpClean}），连带把这个
     * 无障碍服务也带走；系统随后把两个服务分别排队重启，而 {@code AutomationService} 排在
     * 10 s（内存压力下甚至 0 ms）、无障碍服务排在 30 s —— 也就是队列一定比无障碍服务先醒。
     * 那一趟就是这么废掉的：[2/8] 报「无障碍服务未开启」直接把整队掐死，其实再等十几秒它就回来了。
     */
    public static boolean awaitReady(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (instance == null && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return instance != null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "无障碍服务已连接");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        Log.i(TAG, "无障碍服务已断开");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 流程本身靠轮询推进（事件噪声大且会丢），这里只记录前台包名。
        if (event != null && event.getPackageName() != null) {
            lastEventPackage = event.getPackageName().toString();
        }
        // 节点探测器布防时顺手抓一份快照：我们自己的界面一到前台就抓不到菠萝包了。
        InspectorCapture.onTargetEvent(this);
    }

    @Override
    public void onInterrupt() {
    }

    // ---------- 原子操作 ----------

    /**
     * 当前活动窗口的树，<b>只在它属于菠萝包时</b>才交出来；别的 App 在前台时返回 null。
     *
     * <p>这是界外硬闸的主闸。无障碍配置里放行了微信／QQ／微博之后，
     * {@code getRootInActiveWindow()} 是真的能拿到那些 App 的整棵界面树的 —— 而流程里所有
     * 「找控件」的路都汇到这里，所以只要这一处按包名挡住，那些 App 的内容就不会流进选择器、
     * 日志、节点探测器和 CSV 里的任何地方。要点那颗授权键走 {@link #authRoot()}。
     */
    public NodeView root() {
        try {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r == null) return null;
            CharSequence pkg = r.getPackageName();
            if (pkg == null || !TARGET_PACKAGE.contentEquals(pkg)) return null;
            return AccessibilityNodeView.of(r);
        } catch (Exception e) {
            return null;
        }
    }

    /** 打开界外硬闸：接下来允许读一个授权页，仅限找那颗「同意」。 */
    public void armAuthGate() {
        authGateOpen = true;
    }

    /** 关上界外硬闸。放在 finally 里，异常路径也不许把它留在开着的状态。 */
    public void disarmAuthGate() {
        authGateOpen = false;
    }

    /**
     * 三方授权页的树。三个条件同时满足才交出来：硬闸开着、活动窗口的包名在
     * {@link #AUTH_PACKAGES} 里。不满足任何一条返回 null。
     *
     * <p>调用方只允许拿它去匹配 {@code login_auth_confirm}（「同意／允许／确认登录」那一组），
     * 见 {@link StepRunner#confirmThirdPartyAuth}。
     */
    public NodeView authRoot() {
        if (!authGateOpen) return null;
        try {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r == null) return null;
            CharSequence pkg = r.getPackageName();
            if (pkg == null || !AUTH_PACKAGES.contains(pkg.toString())) return null;
            return AccessibilityNodeView.of(r);
        } catch (Exception e) {
            return null;
        }
    }

    /** 前台是不是那三个需要按「同意」的 App 之一（只看包名，不读内容）。 */
    public boolean isAuthPackageForeground() {
        return AUTH_PACKAGES.contains(String.valueOf(activePackage()));
    }

    /**
     * 活动窗口<b>之外</b>那些属于菠萝包的窗口，拼成一棵树；没有就返回 null。
     *
     * <p>为什么要有它：广告播放页不是一个窗口。实测淘宝那支试玩广告，截图上卡片写满了字，
     * 而活动窗口抓下来只有 21 个节点（FrameLayout / LinearLayout / {@code android.webkit.WebView}）、
     * <b>一个字都没有</b> —— 那颗「我要直接拿奖励」根本不在活动窗口里，只查
     * {@code getRootInActiveWindow()} 的话任何选择器都不可能命中它，于是流程就只剩「按跳过」这条
     * 死路（那等于把奖励扔了）。
     *
     * <p>三条界限：<b>只</b>收 {@code com.sfacg} 的窗口（别的 App 的界面一个节点都不读）；
     * 按 {@code getLayer()} 从下往上排，好让 {@link Selector#topmost} 继续等于「画在最上层的那个」；
     * 调用方先查活动窗口、查不到才查这里，所以原来的判断顺序一点没变。
     */
    public NodeView otherWindows() {
        try {
            int activeId = -1;
            AccessibilityNodeInfo active = getRootInActiveWindow();
            if (active != null) activeId = active.getWindowId();
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null || windows.isEmpty()) return null;
            List<AccessibilityWindowInfo> mine = new ArrayList<>();
            for (AccessibilityWindowInfo w : windows) {
                if (w == null || w.getId() == activeId) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                CharSequence pkg = r.getPackageName();
                if (pkg == null || !TARGET_PACKAGE.contentEquals(pkg)) continue;
                mine.add(w);
            }
            if (mine.size() > 1) {
                Collections.sort(mine, new Comparator<AccessibilityWindowInfo>() {
                    @Override
                    public int compare(AccessibilityWindowInfo a, AccessibilityWindowInfo b) {
                        return Integer.compare(a.getLayer(), b.getLayer());
                    }
                });
            }
            List<NodeView> roots = new ArrayList<>();
            for (AccessibilityWindowInfo w : mine) {
                NodeView v = AccessibilityNodeView.of(w.getRoot());
                if (v != null) roots.add(v);
            }
            if (roots.isEmpty()) return null;
            return roots.size() == 1 ? roots.get(0) : new MultiRoot(roots);
        } catch (Exception e) {
            Log.w(TAG, "读别的窗口失败", e);
            return null;
        }
    }

    /** 现在菠萝包一共挂着几个窗口，只用来写日志。 */
    public int windowCount() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null) return 0;
            int n = 0;
            for (AccessibilityWindowInfo w : windows) {
                if (w == null) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && r.getPackageName() != null
                        && TARGET_PACKAGE.contentEquals(r.getPackageName())) {
                    n++;
                }
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 当前活动窗口的包名，取不到时退回最近一次事件的包名。 */
    public String activePackage() {
        try {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null && r.getPackageName() != null) return r.getPackageName().toString();
        } catch (Exception ignored) {
            // 落到 lastEventPackage
        }
        return lastEventPackage;
    }

    public boolean isTargetForeground() {
        return TARGET_PACKAGE.equals(activePackage());
    }

    /** 先试节点自身的 ACTION_CLICK，不行再按屏幕坐标点一下中心。 */
    public boolean click(NodeView view) {
        return press(view) == Press.OK;
    }

    /**
     * 一次点击的结局。分这么细是因为广告播放页上「点不动」有好几种完全不同的原因，
     * 合成一个 boolean 之后日志里只剩一句「点不动」，没法判断该改选择器还是该重连服务。
     */
    public enum Press {
        /** 点出去了。 */
        OK,
        /** 拿不到底层节点（树在这一刻被换掉了）。 */
        NO_NODE,
        /** 节点和它的祖先都没有面积，没有坐标可点。 */
        NO_BOUNDS,
        /** 系统没收下这个手势（dispatchGesture 返回 false 或抛异常，通常是无障碍连接断了）。 */
        REFUSED,
        /** 系统收下了又取消了（被别的手势或触摸打断），已经重试过一次。 */
        CANCELLED
    }

    /**
     * 点一个节点，并说清楚失败在哪一步。
     *
     * <p>梯队：节点自身 ACTION_CLICK → 最近的可点击祖先 → 按 bounds 中心做手势。广告播放页
     * （穿山甲那套 Lynx 渲染树实测 209 个节点里一个 clickable 都没有）只有最后一条路能走。
     *
     * <p><b>会阻塞</b>最多约 2 秒等手势回调，只许在工作线程上调用（手势回调发在主线程）。
     */
    public Press press(NodeView view) {
        AccessibilityNodeInfo node = AccessibilityNodeView.rawOf(view);
        if (node == null) return Press.NO_NODE;
        if (node.isClickable() && node.isEnabled()
                && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return Press.OK;
        }
        AccessibilityNodeInfo ancestor = clickableAncestor(node);
        if (ancestor != null && ancestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return Press.OK;
        }
        Rect r = tappableBounds(node);
        if (r == null) return Press.NO_BOUNDS;
        return tapAt(r.exactCenterX(), r.exactCenterY());
    }

    /**
     * 点「这张卡」的中心，而不是某颗按钮 —— 认不出按钮时的退路。
     *
     * <p>穿山甲那张促销卡自己写着「上滑或<b>点击</b>跳转到详情页或第三方应用」：整张卡就是跳转
     * 热区。所以在已经确认「卡在屏幕上、视频被停住了」之后，还是找不到那颗按钮时，点卡片中心
     * 比按右上角那颗「跳过」正确得多 —— 跳过是把奖励扔掉，点卡片是广告主写明的那条路。
     *
     * <p>做法：从传进来的节点（通常是卡上某段文字）往上走，取<b>最大但还没铺满全屏</b>的那个
     * 祖先当作卡片，点它中心。两条保险：铺满全屏（&gt;85%）的祖先不算卡片，免得退化成「点屏幕
     * 正中」；落点在屏幕最上面 12% 之内时拒绝下手 —— 那一带住着「跳过」和倒计时。
     */
    public Press pressCard(NodeView view) {
        AccessibilityNodeInfo node = AccessibilityNodeView.rawOf(view);
        if (node == null) return Press.NO_NODE;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        long screen = (long) dm.widthPixels * dm.heightPixels;
        Rect best = null;
        AccessibilityNodeInfo cur = node;
        for (int i = 0; i < 8 && cur != null; i++) {
            Rect box = new Rect();
            try {
                cur.getBoundsInScreen(box);
            } catch (Exception e) {
                break;
            }
            long area = (long) box.width() * box.height();
            if (!box.isEmpty() && area <= screen * 85 / 100
                    && (best == null || area > (long) best.width() * best.height())) {
                best = new Rect(box);
            }
            try {
                cur = cur.getParent();
            } catch (Exception e) {
                break;
            }
        }
        if (best == null) return Press.NO_BOUNDS;
        float y = best.exactCenterY();
        if (y < dm.heightPixels * 0.12f) return Press.NO_BOUNDS;
        Log.i(TAG, "点卡片中心 " + best.toShortString());
        return tapAt(best.exactCenterX(), y);
    }

    /**
     * 最后一招：点屏幕正中偏下一点。
     *
     * <p>2026-08-23 实测有一张促销卡，从按钮那段文字往上八层<b>全都是零面积</b>的 Lynx 占位节点
     * （日志里是 {@code bounds=[0,111][0,111]}），{@link #pressCard} 因此拿不到任何矩形，那一支
     * 广告就变成「没有可点的目标」。这时候按屏幕几何位置下手：卡片本体就在屏幕中间那一带，
     * 而「跳过」和倒计时住在最上面 12%、常驻的「立即下载」在最下面，都碰不到。
     *
     * <p>只在<b>已经确认促销卡在屏幕上、视频被它停住</b>之后才允许调用 —— 它是盲点，
     * 唯一的正当理由是「不点就拿不到奖励，而按跳过等于把奖励扔掉」。
     */
    public Press pressScreenCenter() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float x = dm.widthPixels / 2f;
        float y = dm.heightPixels * 0.55f;
        Log.i(TAG, "点屏幕正中 (" + x + "," + y + ")");
        return tapAt(x, y);
    }

    /**
     * 点右上角那颗关闭 X。<b>只许在奖励已经到手之后调用</b>。
     *
     * <p>2026-08-23 14:14 实测：优量汇那支广告跳转、浏览、返回之后，播放页上弹出「恭喜获得奖励／
     * 已完成浏览10秒，提前获得奖励」，代券确实到账了（签到面板上「今日还剩」从 3 变成 2）。但这张
     * 弹窗的出口只有右上角那颗 X，而它是个 44×44 的 ImageView，<b>没文字、没 desc、没 id、
     * 也不 clickable</b>，选择器一条都认不到；同时全局返回在这张弹窗上连按 8 次一动不动。结果脚本
     * 退不回签到页、读不到「今日还剩 N 次」，把已经到账的奖励记成「没确认到」并停下，
     * 白丢了这个号剩下的广告。
     *
     * <p>做法：先按位置在节点树里找那颗图标（{@link NodeMatcher#findCornerClose}），找到就点它
     * 中心；整棵树里都没有（画在 Canvas 上）就按几何位置点右上角。两层都不问 clickable。
     *
     * <p><b>为什么必须先确认奖励到手</b>：同一个位置在穿山甲的播放页上是「跳过」。奖励还没到手时
     * 点那儿等于把奖励扔掉 —— 那是这个项目里最不能犯的错。到手之后再点它，最坏也只是关掉一张
     * 已经领完奖的弹窗。
     */
    public Press pressCloseCorner() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels;
        int h = dm.heightPixels;
        NodeView icon = NodeMatcher.findCornerClose(root(), w, h);
        if (icon == null) icon = NodeMatcher.findCornerClose(otherWindows(), w, h);
        if (icon != null) {
            int[] b = icon.boundsInScreen();
            if (b != null && b.length == 4) {
                float x = (b[0] + b[2]) / 2f;
                float y = (b[1] + b[3]) / 2f;
                Log.i(TAG, "点右上角关闭图标 (" + x + "," + y + ")");
                return tapAt(x, y);
            }
        }
        float x = w * 0.913f;
        float y = h * 0.086f;
        Log.i(TAG, "点右上角关闭位置（树里找不到图标）(" + x + "," + y + ")");
        return tapAt(x, y);
    }

    /** 向上找最近的可点击且可用的祖先，找不到返回 null。 */    private static AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int i = 0; i < 8; i++) {
            try {
                cur = cur.getParent();
            } catch (Exception e) {
                return null;
            }
            if (cur == null) return null;
            if (cur.isClickable() && cur.isEnabled()) return cur;
        }
        return null;
    }

    /**
     * 拿一个能下手的矩形：节点自己没面积（Lynx 树里一堆零高度的占位节点）就向上借祖先的。
     */
    private static Rect tappableBounds(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int i = 0; i < 8 && cur != null; i++) {
            Rect r = new Rect();
            try {
                cur.getBoundsInScreen(r);
            } catch (Exception e) {
                return null;
            }
            if (!r.isEmpty() && r.centerX() >= 0 && r.centerY() >= 0) return r;
            try {
                cur = cur.getParent();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 手势点一下坐标。被取消（{@link Press#CANCELLED}）时隔 250 ms 再试一次 ——
     * 实测广告页上偶发一次取消，重来就好。
     */
    public Press tapAt(float x, float y) {
        Press first = tapOnce(x, y);
        if (first != Press.CANCELLED) return first;
        sleep(250);
        return tapOnce(x, y) == Press.OK ? Press.OK : Press.CANCELLED;
    }

    /**
     * 一次手势点击。
     *
     * <p>两处刻意的选择：路径带 2px 位移、按下 {@value #TAP_MS} ms，因为 Lynx／Canvas 画出来的
     * 按钮不一定认零长度、60 ms 的点击；以及传真的 {@link GestureResultCallback} 并等结果 ——
     * 之前回调传 null，系统把手势丢掉我们也当成点成功了。
     */
    private Press tapOnce(float x, float y) {
        try {
            Path path = new Path();
            path.moveTo(x, y);
            path.lineTo(x + 2, y + 2);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, TAP_MS))
                    .build();
            final CountDownLatch done = new CountDownLatch(1);
            final boolean[] completed = {false};
            boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription description) {
                    completed[0] = true;
                    done.countDown();
                }

                @Override
                public void onCancelled(GestureDescription description) {
                    done.countDown();
                }
            }, null);
            if (!accepted) return Press.REFUSED;
            // 回调迟迟不来时按「已经点出去了」算：手势系统已经收下，重复点更危险。
            if (!done.await(2, TimeUnit.SECONDS)) return Press.OK;
            return completed[0] ? Press.OK : Press.CANCELLED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Press.OK;
        } catch (Exception e) {
            Log.w(TAG, "手势点击失败 (" + x + "," + y + ")", e);
            return Press.REFUSED;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean setText(NodeView view, String value) {
        AccessibilityNodeInfo node = AccessibilityNodeView.rawOf(view);
        if (node == null) return false;
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle args = new Bundle();
        args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value == null ? "" : value);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    public boolean scrollForward(NodeView view) {
        AccessibilityNodeInfo node = AccessibilityNodeView.rawOf(view);
        return node != null
                && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
    }

    /**
     * 往回滚（回到列表顶部那一头）。
     *
     * <p>为什么需要它：实测在设置页按下「退出登录」之后直接回到「我的」页，而那个 ScrollView
     * <b>还停在底部</b>（顶部那块 top_layout 高度塌成 2px），「立即登录」根本不在可见树里 ——
     * 不往回滚就永远点不到它，整条切号链路断在这一步。
     */
    public boolean scrollBackward(NodeView view) {
        AccessibilityNodeInfo node = AccessibilityNodeView.rawOf(view);
        return node != null
                && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
    }

    /** 没有可滚动节点时的退路：在屏幕中间从下往上划一段。 */
    public boolean swipeUp() {
        return swipe(0.72f, 0.32f);
    }

    /** 同上，反方向（回到顶部那一头）。 */
    public boolean swipeDown() {
        return swipe(0.32f, 0.72f);
    }

    private boolean swipe(float fromRatio, float toRatio) {
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            float x = dm.widthPixels / 2f;
            Path path = new Path();
            path.moveTo(x, dm.heightPixels * fromRatio);
            path.lineTo(x, dm.heightPixels * toRatio);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 320))
                    .build();
            return dispatchGesture(gesture, null, null);
        } catch (Exception e) {
            Log.w(TAG, "滑动失败", e);
            return false;
        }
    }

    public boolean back() {
        return performGlobalAction(GLOBAL_ACTION_BACK);
    }

    public boolean home() {
        return performGlobalAction(GLOBAL_ACTION_HOME);
    }
}
