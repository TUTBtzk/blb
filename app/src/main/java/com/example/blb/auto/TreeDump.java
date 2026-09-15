package com.example.blb.auto;

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
        return dump(root, MAX_NODES, MAX_DEPTH);
    }

    static String dump(NodeView root, int maxNodes, int maxDepth) {
        StringBuilder sb = new StringBuilder();
        if (root == null) {
            return "取不到根节点。请确认无障碍服务已开启，且菠萝包正在前台。\n";
        }
        Budget budget = new Budget(Math.max(0, maxNodes), Math.max(0, maxDepth));
        walk(root, 0, sb, budget);
        if (budget.nodesTruncated) sb.append("... 节点数量截断，上限=").append(maxNodes).append('\n');
        if (budget.depthTruncated) sb.append("... 树深度截断，上限=").append(maxDepth).append('\n');
        if (budget.unreadable > 0) sb.append("... 未读取完整节点=").append(budget.unreadable).append('\n');
        return sb.toString();
    }

    private static final class Budget {
        int remaining;
        final int maxDepth;
        boolean nodesTruncated;
        boolean depthTruncated;
        int unreadable;
        Budget(int remaining, int maxDepth) { this.remaining = remaining; this.maxDepth = maxDepth; }
    }

    private static void walk(NodeView node, int depth, StringBuilder sb, Budget budget) {
        if (depth > budget.maxDepth) { budget.depthTruncated = true; return; }
        if (budget.remaining <= 0) { budget.nodesTruncated = true; return; }
        if (node == null) { budget.unreadable++; sb.append("<子节点未读取>\n"); return; }
        budget.remaining--;

        for (int i = 0; i < depth; i++) sb.append("  ");
        try {
            sb.append(simpleName(node.className()));
            appendIf(sb, " text=", node.text());
            appendIf(sb, " desc=", node.desc());
            appendIf(sb, " id=", node.viewId());
            if (node.clickable()) sb.append(" CLICKABLE");
            if (!node.enabled()) sb.append(" disabled");
            sb.append(" visible=").append(node.visible())
                    .append(" heading=").append(node.heading())
                    .append(" scrollable=").append(node.scrollable())
                    .append(" scrollForwardAction=").append(node.supportsScrollForward())
                    .append(" scrollBackwardAction=").append(node.supportsScrollBackward())
                    .append(" childCount=").append(DiagnosticSnapshot.reportedChildCount(node));
            appendBounds(sb, node);
            appendIf(sb, " captureNotes=", DiagnosticSnapshot.note(node));
            // 2026-09-15 w9899 在集合项数变化时退出；旧 dump 丢掉此元数据，无法核实它到底怎样变化。
            sb.append(" collectionRows=").append(node.collectionRowCount())
                    .append(" collectionColumns=").append(node.collectionColumnCount())
                    .append(" itemRowIndex=").append(node.collectionRowIndex())
                    .append(" itemRowSpan=").append(node.collectionRowSpan())
                    .append(" itemColumnIndex=").append(node.collectionColumnIndex())
                    .append(" itemColumnSpan=").append(node.collectionColumnSpan());
            sb.append('\n');

            int n = node.childCount();
            for (int i = 0; i < n; i++) {
                walk(node.child(i), depth + 1, sb, budget);
                if (budget.remaining <= 0 && i + 1 < n) { budget.nodesTruncated = true; break; }
            }
        } catch (RuntimeException unavailable) {
            budget.unreadable++;
            sb.append(" <节点未读取完整：").append(unavailable.getClass().getSimpleName()).append(">\n");
        }
    }

    private static void appendBounds(StringBuilder sb, NodeView node) {
        // 2026-09-14 目录取证要固定同一份快照；快照没有活的 Android 节点，仍必须保留行高与坐标。
        int[] bounds = node.boundsInScreen();
        if (bounds == null || bounds.length != 4) { sb.append(" bounds=未读取"); return; }
        sb.append(" [").append(bounds[0]).append(',').append(bounds[1])
                .append('-').append(bounds[2]).append(',').append(bounds[3]).append(']');
    }

    private static void appendIf(StringBuilder sb, String label, String value) {
        if (value == null) return;
        // 保留全文和原始空白，用可逆转义让多行文字仍能对应到一个节点；不再默默截到60字。
        String v = value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
        sb.append(label).append('"').append(v).append('"');
    }

    private static String simpleName(String className) {
        if (className == null) return "?";
        int dot = className.lastIndexOf('.');
        return dot >= 0 ? className.substring(dot + 1) : className;
    }
}
