package com.example.blb.auto;

import java.util.ArrayList;
import java.util.List;

/**
 * 把多个窗口的根节点拼成一棵假树，让 {@link NodeMatcher} 一次搜完。
 *
 * <p>{@code getRootInActiveWindow()} 只给活动窗口那一层；需要查询其它窗口时，
 * 用同一个节点匹配器逐层搜索，避免复制一套匹配规则。
 *
 * <p>子节点顺序 = 传进来的顺序，调用方按 z 序<b>从下往上</b>给，这样 DFS 里靠后的仍然是
 * 画在上层的那个，{@link Selector#topmost} 的语义不变。
 *
 * <p>它自己什么条件都不满足（text/desc/id/className 全是 null），所以永远不会被当成命中目标。
 */
final class MultiRoot implements NodeView {

    private final List<NodeView> roots;

    MultiRoot(List<NodeView> roots) {
        this.roots = new ArrayList<>(roots);
    }

    @Override
    public String text() {
        return null;
    }

    @Override
    public String desc() {
        return null;
    }

    @Override
    public String viewId() {
        return null;
    }

    @Override
    public String className() {
        return null;
    }

    @Override
    public boolean clickable() {
        return false;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public boolean checked() {
        return false;
    }

    @Override
    public boolean scrollable() {
        return false;
    }

    @Override
    public boolean visible() {
        return true;
    }

    /** 没有实体，返回空矩形；有 {@code requireArea} 的选择器因此也不会点到它。 */
    @Override
    public int[] boundsInScreen() {
        return new int[]{0, 0, 0, 0};
    }

    @Override
    public int childCount() {
        return roots.size();
    }

    @Override
    public NodeView child(int index) {
        return index >= 0 && index < roots.size() ? roots.get(index) : null;
    }

    @Override
    public NodeView parent() {
        return null;
    }
}
