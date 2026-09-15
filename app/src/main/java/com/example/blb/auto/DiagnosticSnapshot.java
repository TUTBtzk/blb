package com.example.blb.auto;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 诊断专用的只读副本，不交给业务扫描或点击。
 * 2026-09-15：复用 AccessibilityNodeInfo 根仍会在 getChild 时读取变化后的子节点；
 * 因此只顺序遍历源树一次，再让行证据、树和匹配报告共用副本。
 * 遍历过程中 Android 仍可能更新页面；这保证输出共用已采到的事实，不保证 Android 原子帧。
 */
final class DiagnosticSnapshot {
    private static final int MAX_NODES = 2500;
    private static final int MAX_DEPTH = 60;
    private static final int MAX_PROBLEM_EXAMPLES = 20;

    final NodeView root;
    final String diagnostics;

    private DiagnosticSnapshot(NodeView root, Capture capture) {
        this.root = root;
        StringBuilder text = new StringBuilder("=== 诊断采样副本 ===\n")
                .append("源树只顺序遍历一次；下方行证据、TreeDump 与匹配报告共用只读副本。\n")
                .append("该遍历不是 Android 原子帧；只代表这一次遍历实际读到的属性与父子关系。\n")
                .append("已读取节点=").append(capture.readNodes)
                .append("；节点访问上限=").append(capture.maxNodes)
                .append("；深度上限=").append(capture.maxDepth)
                .append("；采样问题数=").append(capture.problemCount).append('\n');
        for (String problem : capture.problems) text.append("  ").append(problem).append('\n');
        if (capture.problemCount > capture.problems.size()) {
            text.append("  ... 采样问题示例已截断：显示 ").append(capture.problems.size())
                    .append("/共 ").append(capture.problemCount)
                    .append("；各节点可读范围内另附采样备注。\n");
        }
        diagnostics = text.toString();
    }

    static DiagnosticSnapshot capture(NodeView source) {
        return capture(source, MAX_NODES, MAX_DEPTH);
    }

    static DiagnosticSnapshot capture(NodeView source, int maxNodes, int maxDepth) {
        Capture capture = new Capture(Math.max(0, maxNodes), Math.max(0, maxDepth));
        if (capture.maxNodes == 0) {
            capture.problem(null, "root", "节点数量截断，上限=0");
            return new DiagnosticSnapshot(null, capture);
        }
        return new DiagnosticSnapshot(copy(source, null, "root", 0, capture), capture);
    }

    /** 遍历只暴露已保存的子项；原始 childCount 单独保留，防止畸形数量令后续匹配无限循环。 */
    static int reportedChildCount(NodeView node) {
        return node instanceof FrozenNode ? ((FrozenNode) node).reportedChildren : node.childCount();
    }

    static String note(NodeView node) {
        if (!(node instanceof FrozenNode)) return null;
        FrozenNode frozen = (FrozenNode) node;
        return frozen.notes.isEmpty() ? null : String.join("；", frozen.notes);
    }

    private static FrozenNode copy(NodeView source, FrozenNode parent, String path,
                                   int depth, Capture capture) {
        capture.remaining--;
        if (source == null) {
            capture.problem(parent, path, parent == null ? "根节点未读取" : "子节点未读取");
            return null;
        }
        capture.readNodes++;
        FrozenNode node = new FrozenNode(source, parent, path, capture);
        if (node.reportedChildren < 0) {
            capture.problem(node, path, "childCount 未知，未展开子项");
            return node;
        }
        for (int index = 0; index < node.reportedChildren; index++) {
            if (depth >= capture.maxDepth || capture.remaining <= 0) {
                String limit = depth >= capture.maxDepth
                        ? "树深度截断，上限=" + capture.maxDepth
                        : "节点数量截断，上限=" + capture.maxNodes;
                capture.problem(node, path, limit + "；原始childCount=" + node.reportedChildren
                        + "；已保存子项=" + node.children.size());
                break;
            }
            NodeView child;
            try {
                child = source.child(index);
            } catch (RuntimeException failure) {
                child = null;
                capture.problem(node, path + "/" + index,
                        "child 读取异常：" + failure.getClass().getSimpleName());
            }
            node.children.add(copy(child, node, path + "/" + index, depth + 1, capture));
        }
        return node;
    }

    private static final class Capture {
        final int maxNodes, maxDepth;
        int remaining, readNodes, problemCount;
        final List<String> problems = new ArrayList<>();
        Capture(int maxNodes, int maxDepth) {
            this.maxNodes = maxNodes;
            this.maxDepth = maxDepth;
            remaining = maxNodes;
        }
        void problem(FrozenNode node, String path, String issue) {
            problemCount++;
            if (node != null) node.notes.add(issue);
            if (problems.size() < MAX_PROBLEM_EXAMPLES) problems.add(path + "：" + issue);
        }
    }

    private static final class FrozenNode implements NodeView {
        final String text, desc, viewId, className;
        final boolean clickable, enabled, checked, scrollable, heading, visible;
        final boolean scrollForward, scrollBackward;
        final int collectionRows, collectionColumns, rowIndex, rowSpan, columnIndex, columnSpan;
        final int reportedChildren;
        final int[] bounds;
        final FrozenNode parent;
        final List<NodeView> children = new ArrayList<>();
        final List<String> notes = new ArrayList<>();

        FrozenNode(NodeView source, FrozenNode parent, String path, Capture capture) {
            this.parent = parent;
            text = read(source::text, null, "text", path, capture);
            desc = read(source::desc, null, "desc", path, capture);
            viewId = read(source::viewId, null, "viewId", path, capture);
            className = read(source::className, null, "className", path, capture);
            clickable = read(source::clickable, false, "clickable", path, capture);
            enabled = read(source::enabled, false, "enabled", path, capture);
            checked = read(source::checked, false, "checked", path, capture);
            scrollable = read(source::scrollable, false, "scrollable", path, capture);
            heading = read(source::heading, false, "heading", path, capture);
            visible = read(source::visible, false, "visible", path, capture);
            scrollForward = read(source::supportsScrollForward, false, "scrollForwardAction", path, capture);
            scrollBackward = read(source::supportsScrollBackward, false, "scrollBackwardAction", path, capture);
            collectionRows = read(source::collectionRowCount, -1, "collectionRows", path, capture);
            collectionColumns = read(source::collectionColumnCount, -1, "collectionColumns", path, capture);
            rowIndex = read(source::collectionRowIndex, -1, "itemRowIndex", path, capture);
            rowSpan = read(source::collectionRowSpan, -1, "itemRowSpan", path, capture);
            columnIndex = read(source::collectionColumnIndex, -1, "itemColumnIndex", path, capture);
            columnSpan = read(source::collectionColumnSpan, -1, "itemColumnSpan", path, capture);
            reportedChildren = read(source::childCount, -1, "childCount", path, capture);
            int[] value = read(source::boundsInScreen, null, "bounds", path, capture);
            if (value == null || value.length != 4) {
                capture.problem(this, path, "bounds 未读取；零坐标只作未知占位");
                bounds = new int[]{0, 0, 0, 0};
            } else bounds = value.clone();
        }

        private <T> T read(Supplier<T> source, T unknown, String field, String path, Capture capture) {
            try {
                return source.get();
            } catch (RuntimeException failure) {
                capture.problem(this, path, field + " 未读取：" + failure.getClass().getSimpleName()
                        + "（所示默认值只作未知占位）");
                return unknown;
            }
        }

        @Override public String text() { return text; }
        @Override public String desc() { return desc; }
        @Override public String viewId() { return viewId; }
        @Override public String className() { return className; }
        @Override public boolean clickable() { return clickable; }
        @Override public boolean enabled() { return enabled; }
        @Override public boolean checked() { return checked; }
        @Override public boolean scrollable() { return scrollable; }
        @Override public boolean heading() { return heading; }
        @Override public boolean visible() { return visible; }
        @Override public boolean supportsScrollForward() { return scrollForward; }
        @Override public boolean supportsScrollBackward() { return scrollBackward; }
        @Override public int collectionRowCount() { return collectionRows; }
        @Override public int collectionColumnCount() { return collectionColumns; }
        @Override public int collectionRowIndex() { return rowIndex; }
        @Override public int collectionRowSpan() { return rowSpan; }
        @Override public int collectionColumnIndex() { return columnIndex; }
        @Override public int collectionColumnSpan() { return columnSpan; }
        @Override public int[] boundsInScreen() { return bounds.clone(); }
        @Override public int childCount() { return children.size(); }
        @Override public NodeView child(int index) {
            return index < 0 || index >= children.size() ? null : children.get(index);
        }
        @Override public NodeView parent() { return parent; }
    }
}
