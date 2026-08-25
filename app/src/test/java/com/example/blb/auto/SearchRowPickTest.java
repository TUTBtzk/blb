package com.example.blb.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 搜书那一屏：点哪个节点才真的能进书籍详情页。
 *
 * <p>这个用例是照 2026-08-24 15:03 真机那棵树 1:1 搭的（《发小竟然是后悔文男主》）。当时的
 * 失败长这样：日志两次都写「点了「书名」但没进详情页（等「目录」超时）」——
 * 因为<b>搜索输入框自己的文本也正好等于刚输进去的书名</b>，纯按文本找会先命中输入框。
 * 同一屏上还有三个同名陷阱：「以“书名”为关键字进行搜索」提示行、被下拉盖住的「历史搜索」
 * 标签、以及滚出屏幕外的热搜榜行。四个陷阱都在这里钉住。
 */
public class SearchRowPickTest {

    private static final String TITLE = "发小竟然是后悔文男主";

    /** 真机那一屏：输入框 + 建议下拉（两行）+ 底下压着的历史搜索/热搜榜。 */
    private static FakeNode searchScreen() {
        FakeNode input = FakeNode.text(TITLE)
                .withId("com.sfacg:id/inputSearch")
                .withClass("android.widget.EditText")
                .clickable(true)
                .withBounds(41, 118, 906, 204);

        FakeNode keywordRow = FakeNode.node().clickable(true).withBounds(43, 240, 1037, 380)
                .add(FakeNode.text("以“" + TITLE + "”为关键字进行搜索")
                        .withId("com.sfacg:id/tv_think_text")
                        .withClass("android.widget.TextView")
                        .withBounds(126, 263, 1009, 325));
        FakeNode titleRow = FakeNode.node().clickable(true).withBounds(43, 380, 1037, 488)
                .add(FakeNode.text(TITLE)
                        .withId("com.sfacg:id/tv_think_text")
                        .withClass("android.widget.TextView")
                        .withBounds(126, 403, 586, 465));
        FakeNode think = FakeNode.node().withId("com.sfacg:id/list_view_think")
                .withBounds(43, 240, 1037, 2328)
                .add(keywordRow, titleRow);

        // 建议下拉底下压着的那层：历史搜索里同名的标签（下拉的行盖住了它）＋滚出屏幕的热搜榜。
        FakeNode history = FakeNode.text(TITLE)
                .withClass("android.widget.TextView")
                .clickable(true)
                .visible(false)
                .withBounds(100, 389, 576, 459);
        FakeNode hot = FakeNode.text(TITLE)
                .withId("com.sfacg:id/hot_novel_name")
                .withClass("android.widget.TextView")
                .clickable(true)
                .visible(false)
                .withBounds(157, 2168, 617, 2230);
        FakeNode under = FakeNode.node().withId("com.sfacg:id/list_view")
                .withBounds(0, 225, 1080, 2400)
                .add(history, hot);

        return FakeNode.node().withBounds(0, 0, 1080, 2400).add(input, think, under);
    }

    private static List<Selector> bundled(String key) throws Exception {
        File f = new File("src/main/assets/selectors.json");
        if (!f.isFile()) f = new File("app/src/main/assets/selectors.json");
        assertTrue("找不到 assets/selectors.json：" + f.getAbsolutePath(), f.isFile());
        Map<String, List<Selector>> m = SelectorSet.parse(
                new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        List<Selector> list = m.get(key);
        assertNotNull("assets/selectors.json 里没有 " + key, list);
        return list;
    }

    /** 照 {@code StepRunner.findRowWithText} 的做法：候选逐条试，每条现场拼上书名。 */
    private static NodeView pick(String key, String title) throws Exception {
        for (Selector candidate : bundled(key)) {
            Selector s = candidate.withText(title);
            NodeMatcher.Hit hit = NodeMatcher.find(searchScreen(), Collections.singletonList(s));
            if (hit != null) return hit.node;
        }
        return null;
    }

    /**
     * 要点的是建议下拉里那一行<b>可点的父容器</b>（[43,380-1037,488]）—— 真机上点它直接进详情页。
     * 书名文字节点自己 clickable=false，点它等于空点。
     */
    @Test
    public void picksTheSuggestionRowNotTheSearchField() throws Exception {
        NodeView hit = pick(Keys.NOVEL_TITLE_ROW, TITLE);
        assertNotNull("一条候选都没命中，搜书就又回到进不了详情页的状态", hit);
        assertTrue("点的必须是可点节点", hit.clickable());
        assertEquals(43, hit.boundsInScreen()[0]);
        assertEquals(380, hit.boundsInScreen()[1]);
    }

    /** 输入框的文本也等于书名 —— 它是这次真机失败的全部原因，绝不许被选中。 */
    @Test
    public void theSearchFieldIsNeverThePick() throws Exception {
        NodeView hit = pick(Keys.NOVEL_TITLE_ROW, TITLE);
        assertNotNull(hit);
        String id = hit.viewId() == null ? "" : hit.viewId();
        String cls = hit.className() == null ? "" : hit.className();
        assertFalse("选到了搜索输入框，点它什么都不会发生", id.contains("inputSearch"));
        assertFalse("选到了 EditText，搜书那一屏只有输入框是 EditText", cls.contains("EditText"));
    }

    /**
     * 纯按文本找会先命中输入框 —— 把这条错误做法也钉下来，免得以后有人又「简化」回去。
     * 这一条正是 2026-08-24 那两行失败日志的机器可读版本。
     */
    @Test
    public void plainTextMatchWouldHaveClickedTheSearchFieldAgain() {
        Selector plain = new Selector();
        plain.text = TITLE;
        plain.clickableAncestor = true;
        NodeMatcher.Hit hit = NodeMatcher.find(searchScreen(), Collections.singletonList(plain));
        assertNotNull(hit);
        assertNotNull(hit.node.viewId());
        assertTrue("纯文本匹配本来就该撞上输入框；哪天不撞了，openNovel 的注释要一起改",
                hit.node.viewId().contains("inputSearch"));
    }

    /** 「以“…”为关键字进行搜索」那条只用来提交搜索，绝不能被当成书名行。 */
    @Test
    public void theKeywordHintRowIsNotMistakenForTheTitleRow() throws Exception {
        NodeView title = pick(Keys.NOVEL_TITLE_ROW, TITLE);
        assertNotNull(title);
        assertEquals("书名行不该是提示行那一行（top=240）", 380, title.boundsInScreen()[1]);

        NodeMatcher.Hit keyword = NodeMatcher.find(searchScreen(), bundled(Keys.SEARCH_KEYWORD_ROW));
        assertNotNull("提交搜索要靠点它（这个 App 没有可见的「搜索」键）", keyword);
        assertTrue(keyword.node.clickable());
        assertEquals(240, keyword.node.boundsInScreen()[1]);
    }

    /** 被下拉盖住的历史搜索标签和滚出屏幕的热搜榜行都看不见，点了是空点。 */
    @Test
    public void hiddenSameTitleNodesAreSkipped() throws Exception {
        NodeView hit = pick(Keys.NOVEL_TITLE_ROW, TITLE);
        assertNotNull(hit);
        int top = hit.boundsInScreen()[1];
        assertTrue("选到了盖住的历史搜索标签或屏幕外的热搜榜", top == 380);
    }

    /** withText 只换文本，别的条件（id/visibleOnly/clickableAncestor）必须原样带过去。 */
    @Test
    public void withTextKeepsEveryOtherCondition() {
        Selector base = new Selector();
        base.id = "tv_think_text";
        base.visibleOnly = true;
        base.clickableAncestor = true;
        base.index = 2;
        base.topmost = true;
        base.textContains = "为关键字";

        Selector copy = base.withText(" " + TITLE + " ");
        assertEquals(TITLE, copy.text);
        assertEquals("tv_think_text", copy.id);
        assertTrue(copy.visibleOnly);
        assertTrue(copy.clickableAncestor);
        assertTrue(copy.topmost);
        assertEquals(2, copy.index);
        assertEquals("为关键字", copy.textContains);
        assertNull("原来那条不许被改", base.text);
    }
}
