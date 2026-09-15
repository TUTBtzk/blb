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
    private static volatile Snapshot latest;
    private static volatile long lastAttemptAt;
    private static volatile SelectorSet selectors;

    private InspectorCapture() {
    }

    /** 一次抓取的证据、树与选择器报告一起发布，避免界面导出到两次采样的混合结果。 */
    public static final class Snapshot {
        public final long capturedAt;
        public final String content;
        public final String report;

        private Snapshot(long capturedAt, String content, String report) {
            this.capturedAt = capturedAt;
            this.content = content;
            this.report = report;
        }
    }

    /** 开始布防；之后菠萝包每次界面变化都会刷新快照。 */
    public static void arm(Context context) {
        selectors = SelectorSet.load(context);
        latest = null;
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
        Snapshot snapshot = latest;
        return snapshot == null ? null : snapshot.content;
    }

    public static String report() {
        Snapshot snapshot = latest;
        return snapshot == null ? null : snapshot.report;
    }

    /** 快照时间戳，0 表示还没抓到过。 */
    public static long capturedAt() {
        Snapshot snapshot = latest;
        return snapshot == null ? 0 : snapshot.capturedAt;
    }

    public static Snapshot snapshot() {
        return latest;
    }

    /**
     * 2026-09-14 目录漏番外需要原始行证据；直接抓取与布防采样走同一段只读逻辑。
     * 不切前台、不恢复权限、不滚屏；窗口不属于菠萝包时返回 null，绝不借旧快照冒充现场。
     */
    public static Snapshot captureNow(Context context) {
        BlbAccessibilityService service = BlbAccessibilityService.peek();
        if (service == null || !service.isTargetForeground()) return null;
        NodeView root = service.root();
        if (root == null) return null;
        return publish(root, SelectorSet.load(context));
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
        publish(root, selectors);
    }

    private static Snapshot publish(NodeView root, SelectorSet set) {
        Snapshot snapshot = sample(root, set, System.currentTimeMillis());
        latest = snapshot;
        return snapshot;
    }

    /** 各段输出只读一次源树；顺序采样并不保证 Android 在遍历期间停止更新页面。 */
    static Snapshot sample(NodeView root, SelectorSet set, long at) {
        DiagnosticSnapshot frozen = DiagnosticSnapshot.capture(root);
        String content = "采集时间（Unix 毫秒）=" + at + "\n" + evidenceContent(frozen, set);
        return new Snapshot(at, content,
                "=== 当前快照选择器匹配 ===\n" + probe(frozen.root, set) + "\n" + content);
    }

    /** 运行存档与手动采样都先顺序冻结；此入口不抓新窗口，也不更新手动缓存。 */
    public static String evidenceContent(NodeView root, SelectorSet set) {
        return evidenceContent(DiagnosticSnapshot.capture(root), set);
    }

    private static String evidenceContent(DiagnosticSnapshot frozen, SelectorSet set) {
        NodeView root = frozen.root;
        String rows;
        try {
            rows = CatalogRowEvidence.dump(root, set);
        } catch (RuntimeException unavailable) {
            rows = "目录行结构证据未读取完整：" + unavailable.getClass().getSimpleName() + "\n";
        }
        String details;
        try {
            details = SubscribedDetail.evidenceSnapshot(root, set);
        } catch (RuntimeException unavailable) {
            details = "订阅明细结构证据未读取完整：" + unavailable.getClass().getSimpleName() + "\n";
        }
        return frozen.diagnostics + "\n=== 当前选择器加载诊断 ===\n"
                + (set == null ? "没有加载到 selectors.json" : set.diagnostics()) + "\n\n"
                + rows + "\n" + details
                + "\n=== 当前窗口 TreeDump 原文 ===\n" + CatalogRowEvidence.treeDump(root);
    }

    /** 当前界面上每个 key 命中了什么，用来校准 selectors.json。 */
    static String probe(NodeView root, SelectorSet set) {
        if (set == null) return "没有加载到 selectors.json";
        List<String> hitLines = new ArrayList<>();
        List<String> missKeys = new ArrayList<>();
        List<String> unreadKeys = new ArrayList<>();
        for (String key : set.keys()) {
            try {
                NodeMatcher.Hit hit = NodeMatcher.find(root, set.get(key));
                if (hit == null) {
                    missKeys.add(key);
                } else {
                    hitLines.add("  ✔ " + key + "  → " + describe(hit.node));
                }
            } catch (RuntimeException failure) {
                unreadKeys.add(key + "（" + failure.getClass().getSimpleName() + "）");
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("命中 ").append(hitLines.size())
                .append(" / ").append(hitLines.size() + missKeys.size() + unreadKeys.size()).append('\n');
        for (String line : hitLines) sb.append(line).append('\n');
        if (!missKeys.isEmpty()) {
            sb.append("  ✘ 未命中：").append(String.join("、", missKeys)).append('\n');
        }
        if (!unreadKeys.isEmpty()) {
            sb.append("  ? 未读取完整：").append(String.join("、", unreadKeys)).append('\n');
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
