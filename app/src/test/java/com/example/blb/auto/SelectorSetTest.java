package com.example.blb.auto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * selectors.json 的解析规则，以及内置那份文件本身的自检。
 * 内置文件写坏了在编译期是发现不了的，只能靠这里挡住。
 */
public class SelectorSetTest {

    private static final String LOCAL_SOURCE = "/data/user/0/com.example.blb/files/selectors.json";

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

    @Test
    public void oldLocalCopyDoesNotHideNewBundledKeys() {
        SelectorSet merged = SelectorSet.merge(
                "{\"old\":[{\"id\":\"builtin_old\"}],\"new\":[{\"id\":\"builtin_new\"}]}",
                "{\"old\":[{\"id\":\"user_tuned\"}]}", LOCAL_SOURCE);

        assertEquals("user_tuned", merged.get("old").get(0).id);
        assertEquals("builtin_new", merged.get("new").get(0).id);
        assertTrue(merged.missing("old", "new").isEmpty());
        assertFalse(merged.hasWarnings());
        assertTrue(merged.source().contains(LOCAL_SOURCE));
        assertTrue(merged.diagnostics().contains("本地覆盖的 key：old"));
        assertTrue(merged.diagnostics().contains("由内置补齐的 key：new"));
    }

    @Test
    public void localCandidatesReplaceTheWholeListWithoutChangingTheirOrder() {
        SelectorSet merged = SelectorSet.merge(
                "{\"top\":[{\"id\":\"builtin_first\"},{\"id\":\"builtin_second\"}]}",
                "{\"top\":[{\"desc\":\"回顶\",\"visibleOnly\":true},"
                        + "{\"id\":\"com.sfacg:id/goto_top\",\"clickableOnly\":true}]}", LOCAL_SOURCE);

        List<Selector> candidates = merged.get("top");
        assertEquals(2, candidates.size());
        assertEquals("回顶", candidates.get(0).desc);
        assertTrue(candidates.get(0).visibleOnly);
        assertEquals("com.sfacg:id/goto_top", candidates.get(1).id);
        assertTrue(candidates.get(1).clickableOnly);
        assertTrue(merged.source().contains("本地覆盖 1，内置补齐 0，本地无效 0"));
    }

    @Test
    public void validLocalOnlyKeysRemainAvailable() {
        SelectorSet merged = SelectorSet.merge("{}",
                "{\"custom\":{\"id\":\"user_control\",\"topmost\":true,\"index\":1}}", LOCAL_SOURCE);

        assertEquals("user_control", merged.get("custom").get(0).id);
        assertTrue(merged.get("custom").get(0).topmost);
        assertEquals(1, merged.get("custom").get(0).index);
        assertFalse(merged.hasWarnings());
    }

    @Test
    public void noLocalFileAndEmptyLocalFileBothKeepBundledKeys() {
        String bundled = "{\"top\":{\"id\":\"goto_top\"}}";
        SelectorSet absent = SelectorSet.merge(bundled, null, null);
        SelectorSet empty = SelectorSet.merge(bundled, "{}", LOCAL_SOURCE);

        assertEquals("goto_top", absent.get("top").get(0).id);
        assertEquals("goto_top", empty.get("top").get(0).id);
        assertFalse(absent.hasWarnings());
        assertFalse(empty.hasWarnings());
        assertTrue(absent.source().contains("无本地副本"));
        assertTrue(empty.source().contains("内置补齐 1"));
    }

    @Test
    public void explicitEmptyOrMalformedLocalKeysDoNotReactivateBundledSelectors() {
        String bundled = "{\"blocked\":{\"id\":\"builtin_action\"},"
                + "\"new\":{\"id\":\"new_control\"}}";
        for (String invalid : Arrays.asList("[]", "{}", "null", "\"wrong type\"", "42",
                "[null]", "[{}]", "[{\"id\":\"local_action\"},{}]",
                "{\"id\":42}", "{\"id\":\"action\",\"clickableOnly\":\"true\"}",
                "{\"id\":\"action\",\"index\":-1}", "{\"id\":\"action\",\"index\":1.5}")) {
            SelectorSet merged = SelectorSet.merge(bundled, "{\"blocked\":" + invalid + "}", LOCAL_SOURCE);

            assertFalse(invalid, merged.has("blocked"));
            assertTrue(invalid, merged.get("blocked").isEmpty());
            assertEquals(invalid, Arrays.asList("blocked"), merged.missing("blocked", "new"));
            assertTrue(invalid, merged.has("new"));
            assertTrue(invalid, merged.hasWarnings());
            assertTrue(invalid, merged.source().contains("本地无效 1"));
            assertTrue(invalid, merged.diagnostics().contains("本地无效 key（按缺失处理）"));
            assertFalse(invalid, merged.diagnostics().contains("JSON 损坏"));
        }
    }

    @Test
    public void brokenRegexInvalidatesTheLocalKeyWithoutChangingOtherKeys() {
        SelectorSet merged = SelectorSet.merge(
                "{\"blocked\":{\"id\":\"builtin_action\"},\"retained\":{\"text\":\"保留\"}}",
                "{\"blocked\":[{\"id\":\"local_action\"},{\"textRegex\":\"[\"}]}", LOCAL_SOURCE);

        assertFalse(merged.has("blocked"));
        assertEquals("保留", merged.get("retained").get(0).text);
        assertTrue(merged.diagnostics().contains("候选 2：textRegex 无法编译"));
    }

    @Test
    public void damagedLocalJsonFallsBackAsAWholeAndReportsTheOriginalSource() {
        String bundled = "{\"action\":{\"id\":\"builtin_action\"}}";
        for (String broken : Arrays.asList("{\"action\":{\"id\":\"local_action\"},\"broken\":",
                "[]", "not json")) {
            SelectorSet merged = SelectorSet.merge(bundled, broken, LOCAL_SOURCE);

            assertEquals(broken, "builtin_action", merged.get("action").get(0).id);
            assertTrue(broken, merged.hasWarnings());
            assertTrue(broken, merged.source().contains(LOCAL_SOURCE));
            assertTrue(broken, merged.source().contains("本地 JSON 损坏"));
            assertTrue(broken, merged.source().contains("回退内置可用配置"));
            assertTrue(broken, merged.source().contains("原文件未改动"));
        }
    }

    @Test
    public void unreadLocalFileIsNotReportedAsAnAbsentCopy() {
        SelectorSet merged = SelectorSet.merge("{\"top\":{\"id\":\"goto_top\"}}", null, LOCAL_SOURCE);

        assertTrue(merged.has("top"));
        assertTrue(merged.hasWarnings());
        assertTrue(merged.source().contains(LOCAL_SOURCE));
        assertTrue(merged.source().contains("损坏或未读取"));
        assertFalse(merged.source().contains("无本地副本"));
    }

    @Test
    public void brokenBundledJsonDoesNotDiscardValidLocalKeys() {
        SelectorSet merged = SelectorSet.merge("broken", "{\"local\":{\"text\":\"用户配置\"}}", LOCAL_SOURCE);

        assertEquals("用户配置", merged.get("local").get(0).text);
        assertTrue(merged.hasWarnings());
        assertTrue(merged.source().contains("内置 JSON 损坏"));
    }

    @Test
    public void neitherSourceReadableMeansRequiredKeysRemainMissing() {
        SelectorSet merged = SelectorSet.merge("broken", "broken", LOCAL_SOURCE);

        assertTrue(merged.keys().isEmpty());
        assertEquals(Arrays.asList("top", "pay_detail"), merged.missing("top", "pay_detail"));
        assertTrue(merged.hasWarnings());
        assertTrue(merged.source().contains("内置 JSON 损坏"));
        assertTrue(merged.source().contains("本地 JSON 损坏"));
    }

    @Test
    public void metadataCannotBecomeSelectorsOrChangeResourceIdPackages() {
        SelectorSet merged = SelectorSet.merge(
                "{\"_note\":\"内置说明\",\"_package\":\"com.sfacg\","
                        + "\"top\":{\"id\":\"com.sfacg:id/goto_top\"}}",
                "{\"_note\":\"用户说明\",\"_package\":\"other.app\","
                        + "\"_metadata\":{\"id\":\"not_a_selector\"}}", LOCAL_SOURCE);

        assertEquals(1, merged.keys().size());
        assertEquals("com.sfacg:id/goto_top", merged.get("top").get(0).id);
        assertFalse(merged.has("_note"));
        assertFalse(merged.has("_package"));
        assertFalse(merged.has("_metadata"));
        assertFalse(merged.hasWarnings());
        assertFalse(merged.source().contains("other.app"));
    }

    @Test
    public void missingMessageIncludesKeysBothSourcesAndRecoverySteps() {
        SelectorSet merged = SelectorSet.merge("{\"blocked\":{\"id\":\"builtin\"}}",
                "{\"blocked\":[]}", LOCAL_SOURCE);
        String message = merged.missingMessage("blocked", "absent");

        assertTrue(message.contains("缺少可用选择器：blocked、absent"));
        assertTrue(message.contains("assets/selectors.json"));
        assertTrue(message.contains(LOCAL_SOURCE));
        assertTrue(message.contains("没有有效候选"));
        assertTrue(message.contains("导出内置选择器／恢复内置"));
        assertTrue(message.contains("请先备份"));
        assertEquals("", merged.missingMessage());
    }

    @Test
    public void bundledFilePassesStrictLoadingAsWellAsLegacyParsing() throws Exception {
        String bundled = readBundled();
        SelectorSet merged = SelectorSet.merge(bundled, null, null);

        assertFalse(merged.diagnostics(), merged.hasWarnings());
        assertEquals(parse(bundled).keySet(), merged.keys());
    }

    /** 单元测试的工作目录是 app 模块目录，从命令行整仓跑时退一级也能找到。 */
    private static String readBundled() throws Exception {
        File f = new File("src/main/assets/selectors.json");
        if (!f.isFile()) f = new File("app/src/main/assets/selectors.json");
        assertTrue("找不到 assets/selectors.json：" + f.getAbsolutePath(), f.isFile());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
