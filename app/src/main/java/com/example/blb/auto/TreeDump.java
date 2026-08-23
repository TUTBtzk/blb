package com.example.blb.auto;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * 把当前窗口的节点树导成可读文本。所有 selectors.json 里的条件都靠它对着真实界面抓出来，
 * 是这个项目能长期维护的前提。
 */
public final class TreeDump {

    private static final int MAX_NODES = 2500;
    private static final int MAX_DEPTH = 60;

    private TreeDump() {
    }

    public static String dump(NodeView root) {
        StringBuilder sb = new StringBuilder();
        if (root == null) {
            return "取不到根节点。请确认无障碍服务已开启，且菠萝包正在前台。\n";
        }
        int[] budget = {MAX_NODES};
        walk(root, 0, sb, budget);
        if (budget[0] <= 0) {
            sb.append("... 节点过多，已截断到 ").append(MAX_NODES).append(" 个\n");
        }
        return sb.toString();
    }

    private static void walk(NodeView node, int depth, StringBuilder sb, int[] budget) {
        if (node == null || depth > MAX_DEPTH || budget[0] <= 0) return;
        budget[0]--;

        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(simpleName(node.className()));
        appendIf(sb, " text=", node.text());
        appendIf(sb, " desc=", node.desc());
        appendIf(sb, " id=", shortId(node.viewId()));
        if (node.clickable()) sb.append(" CLICKABLE");
        if (!node.enabled()) sb.append(" disabled");
        appendBounds(sb, node);
        sb.append('\n');

        int n = node.childCount();
        for (int i = 0; i < n; i++) {
            walk(node.child(i), depth + 1, sb, budget);
        }
    }

    private static void appendBounds(StringBuilder sb, NodeView node) {
        AccessibilityNodeInfo raw = AccessibilityNodeView.rawOf(node);
        if (raw == null) return;
        Rect r = new Rect();
        raw.getBoundsInScreen(r);
        if (r.isEmpty()) return;
        sb.append(" [").append(r.left).append(',').append(r.top)
                .append('-').append(r.right).append(',').append(r.bottom).append(']');
    }

    private static void appendIf(StringBuilder sb, String label, String value) {
        if (value == null) return;
        String v = value.trim();
        if (v.isEmpty()) return;
        if (v.length() > 60) v = v.substring(0, 60) + "…";
        sb.append(label).append('"').append(v).append('"');
    }

    /** com.sfacg:id/tv_sign -> tv_sign，方便直接抄进 selectors.json 的 "id" 字段。 */
    private static String shortId(String viewId) {
        if (viewId == null) return null;
        int slash = viewId.indexOf('/');
        return slash >= 0 ? viewId.substring(slash + 1) : viewId;
    }

    private static String simpleName(String className) {
        if (className == null) return "?";
        int dot = className.lastIndexOf('.');
        return dot >= 0 ? className.substring(dot + 1) : className;
    }
}
