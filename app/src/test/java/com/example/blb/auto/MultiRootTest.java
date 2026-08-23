package com.example.blb.auto;

import static com.example.blb.auto.FakeNode.node;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 多窗口搜索：广告播放页不是一个窗口。
 *
 * <p>实测淘宝那支试玩广告，截图上卡片写满了字，而<b>活动</b>窗口抓下来只有 21 个节点
 * （FrameLayout / LinearLayout / WebView）、一个字都没有 —— 要点的按钮在另一层窗口里。
 * {@link MultiRoot} 就是把这些窗口拼起来一次搜完的那层。
 */
public class MultiRootTest {

    private static Selector text(String value) {
        Selector s = new Selector();
        s.text = value;
        return s;
    }

    /** 活动窗口里一个字都没有时，按钮要能在另一层窗口里找到。 */
    @Test
    public void findsNodeThatLivesInAnotherWindow() {
        FakeNode textlessWebView = node().withClass("android.webkit.WebView");
        FakeNode promoCard = node().add(text("我要直接拿奖励", 214, 1367, 866, 1535));
        NodeView all = new MultiRoot(Arrays.<NodeView>asList(textlessWebView, promoCard));

        assertNull("单看活动窗口那层是找不到的",
                NodeMatcher.find(textlessWebView, Collections.singletonList(text("我要直接拿奖励"))));
        NodeMatcher.Hit hit = NodeMatcher.find(all, Collections.singletonList(text("我要直接拿奖励")));
        assertNotNull(hit);
        assertEquals("我要直接拿奖励", hit.node.text());
    }

    /** 窗口按 z 序从下往上给，所以 topmost 还是「画在最上层的那个」。 */
    @Test
    public void topmostStillMeansTheWindowOnTop() {
        FakeNode below = node().add(text("立即下载", 803, 2011, 935, 2055).withId("id/under"));
        FakeNode above = node().add(text("立即下载", 214, 1367, 866, 1535).withId("id/over"));
        NodeView all = new MultiRoot(Arrays.<NodeView>asList(below, above));

        Selector s = text("立即下载");
        s.topmost = true;
        NodeMatcher.Hit hit = NodeMatcher.find(all, Collections.singletonList(s));
        assertNotNull(hit);
        assertEquals("id/over", hit.node.viewId());
    }

    /** 它自己不该被任何选择器当成命中目标（没文字、没 id、没面积）。 */
    @Test
    public void theSyntheticRootIsNeverATarget() {
        NodeView all = new MultiRoot(Collections.<NodeView>singletonList(node()));
        Selector any = new Selector();
        any.className = "View";
        List<NodeView> hits = NodeMatcher.findAll(all, any);
        assertEquals("只该命中真窗口里的节点", 1, hits.size());
        assertNull(all.text());
        assertFalse(NodeMatcher.hasArea(all));
    }

    private static FakeNode text(String value, int l, int t, int r, int b) {
        return FakeNode.text(value).withBounds(l, t, r, b);
    }
}
