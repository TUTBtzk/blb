package com.example.blb.auto;

import android.content.Context;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/**
 * 节点探测器的取样器。
 *
 * <p>难点在于：我们的界面一到前台，{@code getRootInActiveWindow()} 拿到的就是我们自己的窗口，
 * 抓不到菠萝包。所以改成「布防—回收」：探测器先 {@link #arm(Context)} 再把菠萝包拉到前台，
 * 无障碍服务在菠萝包窗口变化时顺手抓一份快照存这里；你切回本 App 时直接读最后一份。
 *
 * <p>顺带把「现有 selectors.json 在这个页面能命中哪些 key」也一起算好 —— 节点信息离开抓取时刻
 * 就会失效，事后拿文本没法再匹配，只能在抓的当下算。
 */
public final class InspectorCapture {

    /** 两次抓取的最小间隔：contentChanged 事件非常密，不限流会白烧 CPU。 */
    private static final long MIN_INTERVAL_MS = 700;

    private static volatile boolean armed;
    private static volatile String tree;
    private static volatile String report;
    private static volatile long capturedAt;
    private static volatile long lastAttemptAt;
    private static volatile SelectorSet selectors;

    private InspectorCapture() {
    }

    /** 开始布防；之后菠萝包每次界面变化都会刷新快照。 */
    public static void arm(Context context) {
        selectors = SelectorSet.load(context);
        tree = null;
        report = null;
        capturedAt = 0;
        lastAttemptAt = 0;
        armed = true;
    }

    public static void disarm() {
        armed = false;
    }

    public static boolean isArmed() {
        return armed;
    }

    public static String tree() {
        return tree;
    }

    public static String report() {
        return report;
    }

    /** 快照时间戳，0 表示还没抓到过。 */
    public static long capturedAt() {
        return capturedAt;
    }

    /** 由无障碍服务在收到菠萝包窗口事件时调用。 */
    static void onTargetEvent(BlbAccessibilityService service) {
        if (!armed || service == null) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastAttemptAt < MIN_INTERVAL_MS) return;
        lastAttemptAt = now;
        if (!service.isTargetForeground()) return;

        NodeView root = service.root();
        if (root == null) return;
        String dumped = TreeDump.dump(root);
        if (dumped == null || dumped.isEmpty()) return;
        tree = dumped;
        report = probe(root, selectors);
        capturedAt = System.currentTimeMillis();
    }

    /** 当前界面上每个 key 命中了什么，用来校准 selectors.json。 */
    static String probe(NodeView root, SelectorSet set) {
        if (set == null) return "没有加载到 selectors.json";
        List<String> hitLines = new ArrayList<>();
        List<String> missKeys = new ArrayList<>();
        for (String key : set.keys()) {
            NodeMatcher.Hit hit = NodeMatcher.find(root, set.get(key));
            if (hit == null) {
                missKeys.add(key);
            } else {
                hitLines.add("  ✔ " + key + "  → " + describe(hit.node));
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("命中 ").append(hitLines.size())
                .append(" / ").append(hitLines.size() + missKeys.size()).append('\n');
        for (String line : hitLines) sb.append(line).append('\n');
        if (!missKeys.isEmpty()) {
            sb.append("  ✘ 未命中：").append(android.text.TextUtils.join("、", missKeys)).append('\n');
        }
        sb.append("（未命中不一定是配错了 —— 当前页面本来就没有那个控件时也会未命中）\n");
        return sb.toString();
    }

    private static String describe(NodeView node) {
        if (node == null) return "(空节点)";
        StringBuilder sb = new StringBuilder();
        String cn = node.className();
        sb.append(cn == null ? "?" : cn.substring(cn.lastIndexOf('.') + 1));
        if (node.text() != null && !node.text().trim().isEmpty()) {
            sb.append(" text=\"").append(node.text().trim()).append('"');
        }
        if (node.viewId() != null) {
            sb.append(" id=").append(node.viewId());
        }
        if (node.clickable()) sb.append(" CLICKABLE");
        return sb.toString();
    }
}
