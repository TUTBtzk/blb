package com.example.blb.auto;

import static com.example.blb.auto.FakeNode.node;
import static com.example.blb.auto.FakeNode.text;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 选择器匹配的边界覆盖：这是菠萝包改版后唯一能提前发现配置写错的地方。 */
public class NodeMatcherTest {

    private static Selector sel(String field, String value) {
        Selector s = new Selector();
        switch (field) {
            case "text": s.text = value; break;
            case "textContains": s.textContains = value; break;
            case "textRegex": s.textRegex = value; break;
            case "desc": s.desc = value; break;
            case "descContains": s.descContains = value; break;
            case "id": s.id = value; break;
            case "className": s.className = value; break;
            default: throw new IllegalArgumentException(field);
        }
        return s;
    }

    @Test
    public void exactTextIgnoresSurroundingWhitespace() {
        assertTrue(NodeMatcher.matches(sel("text", " 签到 "), text("签到  ")));
    }

    @Test
    public void exactTextIsNotPrefixMatch() {
        assertFalse(NodeMatcher.matches(sel("text", "签到"), text("签到日历")));
    }

    @Test
    public void textContainsFoldsCase() {
        assertTrue(NodeMatcher.matches(sel("textContains", "sign"), text("Daily SIGN in")));
        assertFalse(NodeMatcher.matches(sel("textContains", "sign"), text("每日签到")));
    }

    @Test
    public void regexUsesFindSemantics() {
        Selector s = sel("textRegex", "已签到|今日已签");
        assertTrue(NodeMatcher.matches(s, text("您今日已签到啦")));
        assertFalse(NodeMatcher.matches(s, text("立即签到")));
    }

    @Test
    public void brokenRegexMatchesNothingInsteadOfEverything() {
        Selector s = sel("textRegex", "签到(");
        assertNull(s.regex());
        assertFalse(NodeMatcher.matches(s, text("签到")));
        assertFalse(NodeMatcher.matches(s, text("随便什么")));
    }

    @Test
    public void regexDoesNotMatchNodeWithoutText() {
        assertFalse(NodeMatcher.matches(sel("textRegex", "签到"), node().withDesc("签到")));
    }

    @Test
    public void descMatchesExactlyAndByContains() {
        FakeNode n = node().withDesc(" 我的 ");
        assertTrue(NodeMatcher.matches(sel("desc", "我的"), n));
        assertTrue(NodeMatcher.matches(sel("descContains", "我"), n));
        assertFalse(NodeMatcher.matches(sel("desc", "我"), n));
    }

    @Test
    public void idAcceptsShortNameOrFullName() {
        FakeNode n = node().withId("com.sfacg:id/tv_sign");
        assertTrue(NodeMatcher.matches(sel("id", "tv_sign"), n));
        assertTrue(NodeMatcher.matches(sel("id", "com.sfacg:id/tv_sign"), n));
        assertFalse(NodeMatcher.matches(sel("id", "tv_sign2"), n));
        assertFalse(NodeMatcher.matches(sel("id", "tv_sign"), node()));
    }

    @Test
    public void classNameIsSubstringMatch() {
        FakeNode n = node().withClass("android.widget.EditText");
        assertTrue(NodeMatcher.matches(sel("className", "EditText"), n));
        assertFalse(NodeMatcher.matches(sel("className", "TextView"), n));
    }

    @Test
    public void clickableOnlySkipsUnclickableNodes() {
        Selector s = sel("text", "签到");
        s.clickableOnly = true;
        assertFalse(NodeMatcher.matches(s, text("签到")));
        assertTrue(NodeMatcher.matches(s, text("签到").clickable(true)));
    }

    @Test
    public void fieldsWithinOneSelectorAreAnded() {
        Selector s = sel("text", "签到");
        s.id = "tv_sign";
        assertTrue(NodeMatcher.matches(s, text("签到").withId("com.sfacg:id/tv_sign")));
        assertFalse(NodeMatcher.matches(s, text("签到").withId("com.sfacg:id/other")));
    }

    @Test
    public void emptySelectorNeverMatchesAndIsSkippedByFind() {
        Selector empty = new Selector();
        assertTrue(empty.isEmpty());
        assertFalse(NodeMatcher.matches(empty, text("随便")));
        assertTrue(NodeMatcher.findAll(text("随便"), empty).isEmpty());

        FakeNode root = node().add(text("签到"));
        NodeMatcher.Hit hit = NodeMatcher.find(root, Arrays.asList(empty, sel("text", "签到")));
        assertNotNull(hit);
        assertEquals("签到", hit.node.text());
    }

    @Test
    public void candidatesAreTriedInOrderAndFirstHitWins() {
        FakeNode root = node().add(text("每日签到"));
        List<Selector> candidates = Arrays.asList(
                sel("id", "tv_sign"),            // 不中
                sel("textRegex", "签到"),         // 中
                sel("text", "每日签到"));         // 不该走到
        NodeMatcher.Hit hit = NodeMatcher.find(root, candidates);
        assertNotNull(hit);
        assertSame(candidates.get(1), hit.selector);
    }

    @Test
    public void findReturnsNullWhenNothingMatches() {
        assertNull(NodeMatcher.find(node().add(text("首页")), Collections.singletonList(sel("text", "签到"))));
        assertNull(NodeMatcher.find(null, Collections.singletonList(sel("text", "签到"))));
        assertNull(NodeMatcher.find(node(), null));
    }

    @Test
    public void findAllWalksDepthFirstInScreenOrder() {
        FakeNode root = node().add(
                node().add(text("A").withId("id/a"), text("B").withId("id/b")),
                text("C").withId("id/c"));
        List<NodeView> hits = NodeMatcher.findAll(root, sel("className", "View"));
        assertEquals(5, hits.size());
        assertSame(root, hits.get(0));
        assertEquals("A", hits.get(2).text());
        assertEquals("B", hits.get(3).text());
        assertEquals("C", hits.get(4).text());
    }

    @Test
    public void indexPicksTheNthHitAndFallsThroughWhenOutOfRange() {
        FakeNode root = node().add(text("签到"), text("签到").withId("id/second"));

        Selector second = sel("text", "签到");
        second.index = 1;
        NodeMatcher.Hit hit = NodeMatcher.find(root, Collections.singletonList(second));
        assertNotNull(hit);
        assertEquals("id/second", hit.node.viewId());

        Selector third = sel("text", "签到");
        third.index = 2;
        assertNull(NodeMatcher.find(root, Collections.singletonList(third)));
        // 越界的候选不该吃掉后面的候选。
        NodeMatcher.Hit fallback = NodeMatcher.find(root, Arrays.asList(third, sel("text", "签到")));
        assertNotNull(fallback);
        assertNull(fallback.node.viewId());
    }

    @Test
    public void clickableAncestorClimbsToNearestClickableParent() {
        FakeNode label = text("签到");
        FakeNode row = node().withId("id/row").clickable(true).add(node().add(label));
        FakeNode root = node().add(row);

        Selector s = sel("text", "签到");
        s.clickableAncestor = true;
        NodeMatcher.Hit hit = NodeMatcher.find(root, Collections.singletonList(s));
        assertNotNull(hit);
        assertEquals("id/row", hit.node.viewId());
    }

    @Test
    public void clickableAncestorKeepsTheNodeItselfWhenItIsClickable() {
        FakeNode label = text("签到").withId("id/self").clickable(true);
        FakeNode root = node().clickable(true).withId("id/root").add(label);

        Selector s = sel("text", "签到");
        s.clickableAncestor = true;
        NodeMatcher.Hit hit = NodeMatcher.find(root, Collections.singletonList(s));
        assertNotNull(hit);
        assertEquals("id/self", hit.node.viewId());
    }

    @Test
    public void clickableAncestorGivesBackTheOriginalNodeWhenTooFarUp() {
        // 可点击祖先在 10 层以上，超过 MAX_ANCESTOR_HOPS，退回原节点让调用方走手势点击。
        FakeNode label = text("签到").withId("id/label");
        FakeNode cur = label;
        for (int i = 0; i < 10; i++) {
            cur = node().add(cur);
        }
        FakeNode root = node().clickable(true).withId("id/root").add(cur);

        Selector s = sel("text", "签到");
        s.clickableAncestor = true;
        NodeMatcher.Hit hit = NodeMatcher.find(root, Collections.singletonList(s));
        assertNotNull(hit);
        assertEquals("id/label", hit.node.viewId());
    }

    @Test
    public void traversalStopsAtDepthLimitInsteadOfRecursingForever() {
        FakeNode deep = text("签到");
        FakeNode cur = deep;
        for (int i = 0; i < 80; i++) {
            cur = node().add(cur);
        }
        assertTrue(NodeMatcher.findAll(cur, sel("text", "签到")).isEmpty());

        FakeNode shallow = text("签到");
        FakeNode top = shallow;
        for (int i = 0; i < 20; i++) {
            top = node().add(top);
        }
        assertEquals(1, NodeMatcher.findAll(top, sel("text", "签到")).size());
    }

    /** visibleOnly：用户看不见的那颗不算 —— 点它等于空点，而且不会报错。 */
    @Test
    public void visibleOnlySkipsHiddenNodes() {
        Selector s = sel("text", "立即下载");
        s.visibleOnly = true;
        assertTrue(NodeMatcher.matches(s, text("立即下载")));
        assertFalse(NodeMatcher.matches(s, text("立即下载").visible(false)));
    }

    /** visibleOnly 同时要求有面积：Lynx 树里一堆零高度的占位节点，点中心点等于点在别处。 */
    @Test
    public void visibleOnlySkipsZeroAreaNodes() {
        Selector s = sel("text", "确认");
        s.visibleOnly = true;
        assertFalse("零高度", NodeMatcher.matches(s, text("确认").withBounds(0, 111, 1080, 111)));
        assertFalse("零宽度", NodeMatcher.matches(s, text("确认").withBounds(214, 1367, 214, 1535)));
        assertTrue(NodeMatcher.matches(s, text("确认").withBounds(214, 1367, 866, 1535)));

        assertFalse(NodeMatcher.hasArea(null));
        assertFalse(NodeMatcher.hasArea(text("x").withBounds(0, 0, 0, 0)));
        assertTrue(NodeMatcher.hasArea(text("x").withBounds(0, 0, 1, 1)));
    }

    /** requireArea：只问有没有面积，不问 isVisibleToUser —— 自绘树上后者不可靠。 */
    @Test
    public void requireAreaKeepsInvisibleButSizedNodes() {
        Selector s = sel("text", "确认");
        s.requireArea = true;
        assertTrue("requireArea只负责几何面积，不能暗中替换成visibleOnly",
                NodeMatcher.matches(s, text("确认").visible(false)
                        .withBounds(214, 1367, 866, 1535)));
        assertFalse("零面积的占位节点不算",
                NodeMatcher.matches(s, text("确认").withBounds(0, 111, 1080, 111)));
    }

    /** topmost：多个命中取 DFS 最后一个 ≈ 后画上去、盖在最上层的那个。 */
    @Test
    public void topmostPicksTheLastHitInsteadOfTheFirst() {
        FakeNode root = node().add(
                text("立即下载").withId("id/under"),
                text("立即下载").withId("id/over"));

        Selector first = sel("text", "立即下载");
        NodeMatcher.Hit a = NodeMatcher.find(root, Collections.singletonList(first));
        assertNotNull(a);
        assertEquals("id/under", a.node.viewId());

        Selector topmost = sel("text", "立即下载");
        topmost.topmost = true;
        NodeMatcher.Hit b = NodeMatcher.find(root, Collections.singletonList(topmost));
        assertNotNull(b);
        assertEquals("id/over", b.node.viewId());
    }

    /** topmost + index：从最上层往下数第 index 个，越界时照旧让给后面的候选。 */
    @Test
    public void topmostCountsBackwardsFromTheTopAndStillRespectsIndex() {
        FakeNode root = node().add(
                text("按钮").withId("id/a"),
                text("按钮").withId("id/b"),
                text("按钮").withId("id/c"));

        Selector second = sel("text", "按钮");
        second.topmost = true;
        second.index = 1;
        NodeMatcher.Hit hit = NodeMatcher.find(root, Collections.singletonList(second));
        assertNotNull(hit);
        assertEquals("id/b", hit.node.viewId());

        Selector tooFar = sel("text", "按钮");
        tooFar.topmost = true;
        tooFar.index = 3;
        assertNull(NodeMatcher.find(root, Collections.singletonList(tooFar)));
    }
}
