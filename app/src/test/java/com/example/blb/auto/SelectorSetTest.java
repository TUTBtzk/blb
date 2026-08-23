package com.example.blb.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * selectors.json 的解析规则，以及内置那份文件本身的自检。
 * 内置文件写坏了在编译期是发现不了的，只能靠这里挡住。
 */
public class SelectorSetTest {

    private static Map<String, List<Selector>> parse(String json) throws Exception {
        return SelectorSet.parse(json);
    }

    @Test
    public void arrayValueBecomesOrderedCandidateList() throws Exception {
        Map<String, List<Selector>> m = parse("{\"mine_tab\":[{\"text\":\"我的\"},{\"desc\":\"我的\"}]}");
        assertEquals(1, m.size());
        List<Selector> list = m.get("mine_tab");
        assertEquals(2, list.size());
        assertEquals("我的", list.get(0).text);
        assertEquals("我的", list.get(1).desc);
    }

    @Test
    public void singleObjectValueIsAcceptedToo() throws Exception {
        Map<String, List<Selector>> m = parse("{\"checkin_done\":{\"textRegex\":\"已签到\"}}");
        assertEquals(1, m.get("checkin_done").size());
        assertEquals("已签到", m.get("checkin_done").get(0).textRegex);
    }

    @Test
    public void underscoreKeysAreCommentsAndEmptySelectorsAreDropped() throws Exception {
        Map<String, List<Selector>> m = parse(
                "{\"_note\":\"说明\",\"a\":[{}],\"b\":[{\"text\":\"  \"},{\"text\":\"x\"}]}");
        assertFalse(m.containsKey("_note"));
        assertFalse("全空条件会匹配整棵树，必须丢掉", m.containsKey("a"));
        assertEquals(1, m.get("b").size());
        assertEquals("x", m.get("b").get(0).text);
    }

    @Test
    public void flagsAndIndexAreRead() throws Exception {
        Map<String, List<Selector>> m = parse("{\"k\":[{\"text\":\"x\",\"clickableOnly\":true,"
                + "\"clickableAncestor\":true,\"index\":2}]}");
        Selector s = m.get("k").get(0);
        assertTrue(s.clickableOnly);
        assertTrue(s.clickableAncestor);
        assertEquals(2, s.index);
    }

    @Test
    public void bundledFileParsesAndCoversEveryKeyWeReference() throws Exception {
        Map<String, List<Selector>> m = parse(readBundled());
        for (java.lang.reflect.Field f : Keys.class.getDeclaredFields()) {
            if (f.getType() != String.class) continue; // 跳过 REQUIRED_FOR_* 这些数组
            f.setAccessible(true);
            String key = (String) f.get(null);
            assertTrue("assets/selectors.json 缺少 key: " + key, m.containsKey(key));
        }
    }

    @Test
    public void bundledFileHasNoBrokenRegexOrEmptyCandidate() throws Exception {
        Map<String, List<Selector>> m = parse(readBundled());
        for (Map.Entry<String, List<Selector>> e : m.entrySet()) {
            assertFalse("空候选列表: " + e.getKey(), e.getValue().isEmpty());
            for (Selector s : e.getValue()) {
                assertFalse("空条件: " + e.getKey(), s.isEmpty());
                if (s.textRegex != null) {
                    assertNotNull("正则编译不过: " + e.getKey() + " -> " + s.textRegex, s.regex());
                }
            }
        }
    }

    /** 单元测试的工作目录是 app 模块目录，从命令行整仓跑时退一级也能找到。 */
    private static String readBundled() throws Exception {
        File f = new File("src/main/assets/selectors.json");
        if (!f.isFile()) f = new File("app/src/main/assets/selectors.json");
        assertTrue("找不到 assets/selectors.json：" + f.getAbsolutePath(), f.isFile());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
