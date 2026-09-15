package com.example.blb.auto;

import com.example.blb.util.Texts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 纯逻辑的节点匹配与查找，不碰任何 Android API，方便单元测试覆盖各种边界。
 */
public final class NodeMatcher {

    /** 树深上限与访问节点数上限，防止畸形树把遍历卡死。 */
    private static final int MAX_DEPTH = 60;
    private static final int MAX_VISITED = 5000;
    /** clickableAncestor 向上找的层数上限。 */
    private static final int MAX_ANCESTOR_HOPS = 8;

    public static final class Hit {
        public final NodeView node;
        public final Selector selector;

        Hit(NodeView node, Selector selector) {
            this.node = node;
            this.selector = selector;
        }
    }

    private NodeMatcher() {
    }

    /** 按顺序尝试每条候选条件，返回第一条命中的结果；都不中返回 null。 */
    public static Hit find(NodeView root, List<Selector> candidates) {
        if (root == null || candidates == null) return null;
        for (Selector s : candidates) {
            if (s == null || s.isEmpty()) continue;
            List<NodeView> found = findAll(root, s);
            if (found.size() > s.index) {
                // 从后往前数可区分同文案的前后两层，避免对被覆盖的控件空点。
                NodeView node = s.topmost
                        ? found.get(found.size() - 1 - s.index)
                        : found.get(s.index);
                if (s.clickableAncestor) node = clickableAncestorOf(node);
                return new Hit(node, s);
            }
        }
        return null;
    }

    /** 深度优先收集所有命中节点，顺序与界面上从上到下大致一致。 */
    public static List<NodeView> findAll(NodeView root, Selector selector) {
        List<NodeView> out = new ArrayList<>();
        if (root == null || selector == null || selector.isEmpty()) return out;
        collect(root, selector, out, 0, new int[]{0});
        return out;
    }

    private static void collect(NodeView node, Selector s, List<NodeView> out,
                               int depth, int[] visited) {
        if (node == null || depth > MAX_DEPTH || visited[0] >= MAX_VISITED) return;
        visited[0]++;
        if (matches(s, node)) out.add(node);
        int n = node.childCount();
        for (int i = 0; i < n; i++) {
            collect(node.child(i), s, out, depth + 1, visited);
        }
    }

    public static boolean matches(Selector s, NodeView node) {
        if (s == null || node == null || s.isEmpty()) return false;

        if (s.clickableOnly && !node.clickable()) return false;
        if (s.visibleOnly && !(node.visible() && hasArea(node))) return false;
        if (s.requireArea && !hasArea(node)) return false;

        String text = norm(node.text());
        if (s.text != null && !norm(s.text).equals(text)) return false;
        if (s.textContains != null && !containsFold(text, s.textContains)) return false;
        if (s.textRegex != null) {
            Pattern p = s.regex();
            // 正则写错时宁可不匹配，也不要退化成「匹配一切」。
            if (p == null || text == null || !p.matcher(text).find()) return false;
        }

        String desc = norm(node.desc());
        if (s.desc != null && !norm(s.desc).equals(desc)) return false;
        if (s.descContains != null && !containsFold(desc, s.descContains)) return false;

        if (s.id != null && !idMatches(node.viewId(), s.id)) return false;

        if (s.className != null) {
            String cn = node.className();
            if (cn == null || !cn.contains(s.className)) return false;
        }
        return true;
    }

    /** 有没有面积。Lynx 那套渲染树里有一堆零高度的占位节点，点它们等于没点。 */
    public static boolean hasArea(NodeView node) {
        if (node == null) return false;
        int[] b = node.boundsInScreen();
        return b != null && b.length == 4 && b[2] > b[0] && b[3] > b[1];
    }

    /** 允许只写 ":id/" 后面那段，例如 "tv_sign" 就能匹配 com.sfacg:id/tv_sign。 */
    static boolean idMatches(String viewId, String wanted) {
        if (viewId == null) return false;
        if (viewId.equals(wanted)) return true;
        int slash = viewId.indexOf("/");
        return slash >= 0 && viewId.substring(slash + 1).equals(wanted);
    }

    /** 向上找最近的可点击祖先；找不到就返回原节点，让调用方退化成手势点击。 */
    static NodeView clickableAncestorOf(NodeView node) {
        if (node == null) return null;
        if (node.clickable()) return node;
        NodeView cur = node.parent();
        for (int i = 0; i < MAX_ANCESTOR_HOPS && cur != null; i++) {
            if (cur.clickable()) return cur;
            cur = cur.parent();
        }
        return node;
    }

    /** {@link #countAbove}：数字离标签最远能有多远（px），再远就是别的东西了。 */
    private static final int LABEL_GAP_MAX = 140;
    /** 允许数字和标签在垂直方向上略微重叠这么多（px），排版误差用。 */
    private static final int LABEL_GAP_SLACK = 12;
    /** 同一列的判定：中心横向偏移不超过标签自身半宽，且至少给这么多余量。 */
    private static final int LABEL_COLUMN_SLACK = 40;

    /**
     * 读「标签正上方那个数字」。
     *
     * <p>专门为菠萝包「我的」页那一行余额准备：实测 2026-08-23，「我的帐户」这一行的
     * <b>数字和标签都没有 resource-id</b>，三列的数字各自画在自己那个标签的正上方、
     * 左右边界完全对齐：
     * <pre>
     *   '0'   [63,687][307,745]     '火券' [63,752][307,798]
     *   '152' [307,687][552,745]    '金币' [307,752][552,798]
     *   '10'  [552,687][797,745]    '代券' [552,752][797,798]
     * </pre>
     * 选择器语言里没有一条能表达「这个数字是属于那个标签的」，只能靠位置配对：取中心和标签
     * 同一列、底边贴在标签上边、整段就是个数字的那个节点，多个候选取最近的。
     *
     * @return 读到的数字；标签没面积、附近没有数字节点，或者数字里掺了别的字都返回 -1
     */
    public static int countAbove(NodeView root, NodeView label) {
        if (root == null || !hasArea(label)) return -1;
        int[] lb = label.boundsInScreen();
        int labelCx = (lb[0] + lb[2]) / 2;
        int slack = Math.max(LABEL_COLUMN_SLACK, (lb[2] - lb[0]) / 2);
        // {已读到的数字, 它和标签的垂直间距}
        int[] best = {-1, Integer.MAX_VALUE};
        collectAbove(root, label, lb[1], labelCx, slack, 0, new int[]{0}, best);
        return best[0];
    }

    private static void collectAbove(NodeView node, NodeView label, int labelTop, int labelCx,
                                     int slack, int depth, int[] visited, int[] best) {
        if (node == null || depth > MAX_DEPTH || visited[0] >= MAX_VISITED) return;
        visited[0]++;
        if (node != label && hasArea(node)) {
            int value = Texts.parseWholeCount(node.text());
            if (value >= 0) {
                int[] b = node.boundsInScreen();
                int gap = labelTop - b[3];
                int cx = (b[0] + b[2]) / 2;
                if (gap >= -LABEL_GAP_SLACK && gap <= LABEL_GAP_MAX
                        && Math.abs(cx - labelCx) <= slack && gap < best[1]) {
                    best[0] = value;
                    best[1] = gap;
                }
            }
        }
        int n = node.childCount();
        for (int i = 0; i < n; i++) {
            collectAbove(node.child(i), label, labelTop, labelCx, slack, depth + 1, visited, best);
        }
    }

    /**
     * 找最靠里的可滚动容器。目录页常见的结构是外层 ViewPager 套内层 RecyclerView，
     * 滚外层没用，所以取深度最大的那个。
     */
    public static NodeView findScrollable(NodeView root) {
        NodeView[] best = new NodeView[1];
        int[] bestDepth = {-1};
        collectScrollable(root, 0, new int[]{0}, best, bestDepth);
        return best[0];
    }

    private static void collectScrollable(NodeView node, int depth, int[] visited,
                                         NodeView[] best, int[] bestDepth) {
        if (node == null || depth > MAX_DEPTH || visited[0] >= MAX_VISITED) return;
        visited[0]++;
        if (node.scrollable() && depth > bestDepth[0]) {
            best[0] = node;
            bestDepth[0] = depth;
        }
        int n = node.childCount();
        for (int i = 0; i < n; i++) {
            collectScrollable(node.child(i), depth + 1, visited, best, bestDepth);
        }
    }

    private static String norm(CharSequence cs) {
        if (cs == null) return null;
        String s = cs.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean containsFold(String haystack, String needle) {
        if (haystack == null || needle == null) return false;
        return haystack.toLowerCase(Locale.ROOT).contains(needle.trim().toLowerCase(Locale.ROOT));
    }
}
