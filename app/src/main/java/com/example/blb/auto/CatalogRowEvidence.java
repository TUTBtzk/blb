package com.example.blb.auto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * 目录行的只读取证文本，不决定哪行可以买，也不点击或写账。
 * 2026-09-14 真机报告只有「跳过 12 行」而缺少逐行结构；勾选框不能证明无标号行是章节，
 * 所以把标题、行容器与状态节点放在同一份窗口快照中，供实际比对后再确定判据。
 */
public final class CatalogRowEvidence {

    private static final String[] STATE_KEYS = {
            Keys.CHAPTER_LOCKED, Keys.CHAPTER_OWNED, Keys.CHAPTER_SELECTABLE};

    private CatalogRowEvidence() {
    }

    /** 诊断入口传入顺序冻结的只读副本；与 TreeDump 共用采到的事实，不声称 Android 原子帧。 */
    public static String dump(NodeView root, SelectorSet selectors) {
        StringBuilder out = new StringBuilder("=== 目录行结构证据（只读） ===\n");
        out.append("选择器来源=").append(selectors == null ? "未读取" : selectors.source()).append('\n');
        if (root == null) {
            return out.append("当前页已读到行数=-1（根节点未读取）\n").toString();
        }
        appendListInventory(out, root, selectors);
        if (selectors == null || selectors.get(Keys.CHAPTER_ROW_TITLE).isEmpty()) {
            return out.append("当前页已读到行数=-1（未配置 ")
                    .append(Keys.CHAPTER_ROW_TITLE).append("）\n").toString();
        }

        List<NodeView> titles = new ArrayList<>();
        int candidate = -1;
        try {
            List<Selector> choices = selectors.get(Keys.CHAPTER_ROW_TITLE);
            for (int i = 0; i < choices.size(); i++) {
                List<NodeView> found = NodeMatcher.findAll(root, choices.get(i));
                if (found.isEmpty()) continue;
                titles = found;
                candidate = i;
                break;
            }
        } catch (RuntimeException failure) {
            return out.append("当前页已读到行数=-1（标题查找失败：")
                    .append(failure.getClass().getSimpleName()).append("）\n").toString();
        }

        out.append("当前页标题命中数=").append(titles.size())
                .append("；titleKey=").append(Keys.CHAPTER_ROW_TITLE)
                .append("；命中候选下标=").append(candidate).append('\n');
        out.append("标题命中数不等于列表全部子项数；无title子项见上方列表盘点。\n")
                .append("下面的行序只属于当前快照，不是账本章号；未列出的末章、卷名或番外未视为已采到。\n")
                .append("全文标题保留空格与括号；结构只按读到的属性列出，不自动判定分节或章节。\n")
                .append("NodeMatcher.clickableAncestorOf 找不到可点祖先时会返回原节点，必须看实际 clickable。\n");
        for (int i = 0; i < titles.size(); i++) {
            NodeView title = titles.get(i);
            out.append("\n--- 当前快照行 ").append(i + 1).append(" ---\n");
            out.append("title.text 原文开始>>>\n").append(readText(title))
                    .append("\n<<<title.text 原文结束\n");
            out.append("title=").append(describe(title)).append('\n');

            try {
                NodeView ancestor = NodeMatcher.clickableAncestorOf(title);
                out.append("clickableAncestorOf=").append(describe(ancestor))
                        .append("；返回原title节点=").append(ancestor == title).append('\n');
            } catch (RuntimeException failure) {
                out.append("clickableAncestorOf=未读取（")
                        .append(failure.getClass().getSimpleName()).append("）\n");
            }

            NodeView row;
            try {
                row = CatalogScanner.rowOf(title);
            } catch (RuntimeException failure) {
                out.append("CatalogScanner.rowOf=未读取（")
                        .append(failure.getClass().getSimpleName()).append("）\n")
                        .append("row.height=-1；row.childCount=-1\n");
                continue;
            }
            out.append("CatalogScanner.rowOf=").append(describe(row)).append('\n');
            for (String key : STATE_KEYS) appendState(out, row, key, selectors);
            out.append("row TreeDump 原文开始>>>\n").append(treeDump(row))
                    .append("<<<row TreeDump 原文结束\n");
        }
        return out.toString();
    }

    /** 2026-09-15 的旧探测器只遍历 title 命中，恰好漏掉了需要定位的无标题或裁剪行。 */
    private static void appendListInventory(StringBuilder out, NodeView root, SelectorSet selectors) {
        out.append("\n=== 纵向目录列表全部直接子项（按树中原始顺序） ===\n");
        if (selectors == null) { out.append("选择器未读取，无法盘点列表\n"); return; }
        for (String listKey : new String[]{Keys.CATALOG_DIRECTORY_LIST, Keys.CATALOG_PICKER_LIST}) {
            try {
                List<NodeView> lists = matches(root, selectors.get(listKey));
                out.append("列表key=").append(listKey).append("；命中列表数=").append(lists.size()).append('\n');
                for (NodeView list : lists) {
                    out.append("列表=").append(describe(list)).append('\n');
                    int count = childCount(list);
                    out.append("直接子项数=").append(count).append('\n');
                    for (int index = 0; index < count && index < 512; index++) {
                        NodeView child = list.child(index);
                        List<NodeView> rowTitles = matches(child, selectors.get(Keys.CHAPTER_ROW_TITLE));
                        out.append("当前快照直接子项 ").append(index + 1).append("：")
                                .append(describe(child)).append("；title数量=").append(rowTitles.size()).append('\n');
                        List<String> problems = new ArrayList<>();
                        if (child == null) problems.add("子节点未读取");
                        else {
                            if (!NodeMatcher.hasArea(child)) problems.add("行坐标没有面积");
                            if (!child.visible()) problems.add("行不可见");
                            if (clips(child, list)) problems.add("行越过列表边界，可能被裁剪");
                        }
                        if (rowTitles.isEmpty()) problems.add("没有title节点");
                        else if (rowTitles.size() != 1) problems.add("行内有多个title节点");
                        for (int titleAt = 0; titleAt < rowTitles.size(); titleAt++) {
                            NodeView title = rowTitles.get(titleAt);
                            out.append("  title[").append(titleAt).append("]=").append(describe(title)).append('\n')
                                    .append("  title.text 原文开始>>>\n").append(readText(title))
                                    .append("\n<<<title.text 原文结束\n");
                            String raw = title.text();
                            if (raw == null || raw.trim().isEmpty()) problems.add("标题为空");
                            if (!NodeMatcher.hasArea(title)) problems.add("标题坐标没有面积");
                            if (clips(title, list)) problems.add("标题越过列表边界，可能被裁剪");
                        }
                        out.append("  结构观察=").append(problems.isEmpty() ? "以上属性已读到，未据此判定可购买"
                                : String.join("；", problems)).append('\n');
                        if (rowTitles.isEmpty() || rowTitles.size() > 1) {
                            out.append("  子项TreeDump>>>\n").append(treeDump(child)).append("<<<子项TreeDump\n");
                        }
                    }
                    if (count > 512) out.append("... 直接子项盘点已截断：512/").append(count).append('\n');
                }
            } catch (RuntimeException failure) {
                out.append("列表盘点未读取完整：").append(failure.getClass().getSimpleName()).append('\n');
            }
        }
    }

    private static List<NodeView> matches(NodeView root, List<Selector> choices) {
        if (root != null) for (Selector choice : choices) {
            List<NodeView> found = NodeMatcher.findAll(root, choice);
            if (!found.isEmpty()) return found;
        }
        return new ArrayList<>();
    }

    private static boolean clips(NodeView child, NodeView viewport) {
        int[] bounds = child.boundsInScreen();
        int[] visible = viewport.boundsInScreen();
        return bounds != null && visible != null && bounds.length == 4 && visible.length == 4
                && (bounds[0] < visible[0] || bounds[1] < visible[1]
                || bounds[2] > visible[2] || bounds[3] > visible[3]);
    }

    private static void appendState(StringBuilder out, NodeView row, String key, SelectorSet selectors) {
        out.append(key).append('=');
        if (row == null) {
            out.append("未读取（行容器为空）\n");
            return;
        }
        List<Selector> choices = selectors.get(key);
        if (choices.isEmpty()) {
            out.append("未配置\n");
            return;
        }
        try {
            NodeMatcher.Hit hit = NodeMatcher.find(row, choices);
            if (hit == null) {
                out.append("未命中\n");
                return;
            }
            out.append("命中；matcher.node=").append(describe(hit.node)).append('\n');
            // 状态选择器也可能配置 clickableAncestor；原始命中 ID 与最后返回的祖先都必须留下。
            List<NodeView> rawHits = NodeMatcher.findAll(row, hit.selector);
            out.append("  原始命中数=").append(rawHits.size()).append('\n');
            for (int i = 0; i < rawHits.size(); i++) {
                out.append("  [").append(i).append("] ").append(describe(rawHits.get(i))).append('\n');
            }
        } catch (RuntimeException failure) {
            out.append("未读取（").append(failure.getClass().getSimpleName()).append("）\n");
        }
    }

    private static String describe(NodeView node) {
        if (node == null) return "null；height=-1；childCount=-1；clickable=未读取";
        return "id=" + quoted(read(node::viewId))
                + "；class=" + quoted(read(node::className))
                + "；text=" + quoted(read(node::text))
                + "；clickable=" + read(node::clickable)
                + "；heading=" + read(node::heading)
                + "；visible=" + read(node::visible)
                + "；scrollable=" + read(node::scrollable)
                + "；collectionRows=" + read(node::collectionRowCount)
                + "；itemRowIndex=" + read(node::collectionRowIndex)
                + "；itemRowSpan=" + read(node::collectionRowSpan)
                + "；" + geometry(node)
                + "；childCount=" + read(() -> DiagnosticSnapshot.reportedChildCount(node))
                + (DiagnosticSnapshot.note(node) == null ? ""
                : "；采样备注=" + quoted(DiagnosticSnapshot.note(node)));
    }

    private static String geometry(NodeView node) {
        try {
            int[] bounds = node.boundsInScreen();
            if (bounds == null || bounds.length != 4) return "bounds=未读取；height=-1";
            long height = (long) bounds[3] - bounds[1];
            boolean valid = bounds[2] > bounds[0] && height > 0 && height <= Integer.MAX_VALUE;
            return "bounds=" + Arrays.toString(bounds) + "；height=" + (valid ? height : -1);
        } catch (RuntimeException failure) {
            return "bounds=未读取；height=-1";
        }
    }

    private static int childCount(NodeView node) {
        try {
            return Math.max(-1, node.childCount());
        } catch (RuntimeException failure) {
            return -1;
        }
    }

    private static String readText(NodeView node) {
        return node == null ? "<节点未读取>" : read(node::text);
    }

    private static String read(Supplier<?> action) {
        try {
            Object value = action.get();
            return value == null ? "<null>" : String.valueOf(value);
        } catch (RuntimeException failure) {
            return "<未读取：" + failure.getClass().getSimpleName() + ">";
        }
    }

    /** 行摘要保持一行但不截短；紧邻的原文块另外保留标题的真实空白。 */
    private static String quoted(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t") + '"';
    }

    static String treeDump(NodeView root) {
        try {
            return TreeDump.dump(root);
        } catch (RuntimeException failure) {
            return "TreeDump 未读取完整：" + failure.getClass().getSimpleName() + "\n";
        }
    }
}
