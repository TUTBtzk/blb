package com.example.blb.auto;

import static com.example.blb.auto.FakeNode.node;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/**
 * {@link NodeMatcher#findScrollable} 的边界。目录页翻页全靠它选对容器，
 * 选错了（比如滚到外层 ViewPager）就会一直翻不动，最后误报「目录里没这一章」。
 */
public class FindScrollableTest {

    @Test
    public void noScrollableAnywhereReturnsNull() {
        FakeNode root = node().add(node(), node().add(node()));
        assertNull(NodeMatcher.findScrollable(root));
    }

    @Test
    public void nullRootIsTolerated() {
        assertNull(NodeMatcher.findScrollable(null));
    }

    @Test
    public void findsTheOnlyScrollable() {
        FakeNode list = node().withClass("androidx.recyclerview.widget.RecyclerView").scrollable(true);
        FakeNode root = node().add(node(), node().add(list));
        assertSame(list, NodeMatcher.findScrollable(root));
    }

    @Test
    public void picksTheDeepestScrollableNotTheOuterOne() {
        FakeNode inner = node().withClass("RecyclerView").scrollable(true);
        FakeNode outer = node().withClass("ViewPager").scrollable(true).add(node().add(inner));
        FakeNode root = node().add(outer);
        assertSame(inner, NodeMatcher.findScrollable(root));
    }

    @Test
    public void rootItselfCanBeTheScrollable() {
        FakeNode root = node().scrollable(true).add(node(), node());
        assertSame(root, NodeMatcher.findScrollable(root));
    }

    /** 同深度有两个可滚动容器时取先遇到的，保证结果稳定而不是随遍历顺序抖。 */
    @Test
    public void sameDepthKeepsTheFirstOne() {
        FakeNode first = node().withId("a").scrollable(true);
        FakeNode second = node().withId("b").scrollable(true);
        FakeNode root = node().add(node().add(first), node().add(second));
        assertSame(first, NodeMatcher.findScrollable(root));
    }

    /** 超过深度上限的畸形树不会把遍历拖死，也不会把够不着的节点当结果。 */
    @Test
    public void tooDeepScrollableIsIgnored() {
        FakeNode deep = node().scrollable(true);
        FakeNode cur = deep;
        for (int i = 0; i < 80; i++) {
            cur = node().add(cur);
        }
        assertNull(NodeMatcher.findScrollable(cur));
    }
}
