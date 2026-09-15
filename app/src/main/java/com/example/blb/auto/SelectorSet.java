package com.example.blb.auto;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * selectors.json 的加载与查询。
 *
 * <p>内置配置打底，本地副本按 key 覆盖。加载只读，两份配置的来源与错误一并保留。
 */
public final class SelectorSet {

    public static final String FILE_NAME = "selectors.json";
    private static final String TAG = "BlbAuto";

    private final Map<String, List<Selector>> map;
    private final String source;
    private final String diagnostics;
    private final boolean localPresent;
    private final boolean warnings;

    SelectorSet(Map<String, List<Selector>> map, String source) {
        this(map, source, source, false, false);
    }

    private SelectorSet(Map<String, List<Selector>> map, String source, String diagnostics,
                        boolean localPresent, boolean warnings) {
        Map<String, List<Selector>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<Selector>> entry : map.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        this.map = Collections.unmodifiableMap(copy);
        this.source = source;
        this.diagnostics = diagnostics;
        this.localPresent = localPresent;
        this.warnings = warnings;
    }

    public static SelectorSet load(Context context) {
        String bundledJson = null;
        String bundledError = null;
        try (InputStream in = context.getAssets().open(FILE_NAME)) {
            bundledJson = readAll(in);
        } catch (Exception e) {
            bundledError = "内置文件读取失败（" + e.getClass().getSimpleName() + "）";
            Log.e(TAG, bundledError, e);
        }

        File override = overrideFile(context);
        String localJson = null;
        String localSource = null;
        String localError = null;
        if (override.isFile()) {
            localSource = override.getAbsolutePath();
            try (InputStream in = new FileInputStream(override)) {
                localJson = readAll(in);
            } catch (Exception e) {
                localError = "本地文件读取失败（" + e.getClass().getSimpleName() + "），已回退内置可用配置";
                Log.w(TAG, localError, e);
            }
        }
        return merge(bundledJson, localJson, localSource, bundledError, localError);
    }

    public static File overrideFile(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    static Map<String, List<Selector>> parse(String json) throws Exception {
        // 旧解析入口供候选匹配测试使用，仍允许丢弃空候选；实际文件合并采用整 key 校验。
        return parseFile(json, false).valid;
    }

    /** 纯函数入口：null 本地文本和来源表示没有副本，绝不改写输入文件。 */
    static SelectorSet merge(String bundledJson, String localJson, String localSource) {
        return merge(bundledJson, localJson, localSource, null, null);
    }

    private static SelectorSet merge(String bundledJson, String localJson, String localSource,
                                     String bundledReadError, String localReadError) {
        List<String> notices = new ArrayList<>();
        ParsedFile bundled = readSource(bundledJson, "内置", bundledReadError, notices);
        boolean hasLocal = localSource != null || localJson != null || localReadError != null;
        String localPath = localSource == null ? "filesDir/" + FILE_NAME : localSource;
        ParsedFile local = hasLocal ? readSource(localJson, "本地", localReadError, notices)
                : new ParsedFile();

        Map<String, List<Selector>> effective = new LinkedHashMap<>(bundled.valid);
        // 2026-09-15 v1.1 取证显示正在用本地副本；整份优先会遮住新增 key，因此只补缺项并保留候选顺序。
        effective.putAll(local.valid);
        // 显式空值/坏候选可能是用户主动停用某个入口；不能把它当成缺省并恢复同名内置点击条件。
        for (String key : local.invalid.keySet()) effective.remove(key);

        Set<String> inherited = new TreeSet<>(effective.keySet());
        inherited.removeAll(local.valid.keySet());
        StringBuilder origin = new StringBuilder("assets/").append(FILE_NAME);
        if (hasLocal) {
            origin.append(" + 本地 ").append(localPath)
                    .append("（本地覆盖 ").append(local.valid.size())
                    .append("，内置补齐 ").append(inherited.size())
                    .append("，本地无效 ").append(local.invalid.size()).append("）");
        } else {
            origin.append("（内置生效 ").append(inherited.size()).append("，无本地副本）");
        }
        if (!bundled.invalid.isEmpty()) origin.append("；内置无效 ").append(bundled.invalid.size());
        if (!notices.isEmpty()) origin.append("；告警：").append(String.join("；", notices));

        StringBuilder details = new StringBuilder(origin);
        appendKeys(details, "本地覆盖的 key", local.valid.keySet());
        appendKeys(details, hasLocal ? "由内置补齐的 key" : "内置生效的 key", inherited);
        appendInvalid(details, "本地无效 key（按缺失处理）", local.invalid);
        appendInvalid(details, "内置无效 key", bundled.invalid);
        return new SelectorSet(effective, origin.toString(), details.toString(), hasLocal,
                !notices.isEmpty() || !local.invalid.isEmpty() || !bundled.invalid.isEmpty());
    }

    private static ParsedFile readSource(String json, String label, String readError,
                                         List<String> notices) {
        if (readError != null) {
            notices.add(readError);
            return new ParsedFile();
        }
        try {
            if (json == null) throw new IOException("未读取配置文本");
            return parseFile(json, true);
        } catch (Exception e) {
            String note = label + " JSON 损坏或未读取（" + e.getClass().getSimpleName() + "）";
            if ("本地".equals(label)) note += "，已回退内置可用配置，原文件未改动";
            notices.add(note);
            return new ParsedFile();
        }
    }

    private static final class ParsedFile {
        final Map<String, List<Selector>> valid = new LinkedHashMap<>();
        final Map<String, String> invalid = new LinkedHashMap<>();
    }

    private static ParsedFile parseFile(String json, boolean strict) throws Exception {
        ParsedFile out = new ParsedFile();
        JSONObject root = new JSONObject(json);
        Set<String> keys = new TreeSet<>();
        for (java.util.Iterator<String> it = root.keys(); it.hasNext(); ) {
            keys.add(it.next());
        }
        for (String key : keys) {
            if (key.startsWith("_")) continue; // _note 之类的说明字段
            Object value = root.get(key);
            List<Selector> list = new ArrayList<>();
            String error = null;
            if (value instanceof JSONArray) {
                JSONArray arr = (JSONArray) value;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (strict) {
                        error = candidateError(o);
                        if (error != null) {
                            error = "候选 " + (i + 1) + "：" + error;
                            break;
                        }
                    }
                    if (o == null) continue;
                    Selector s = Selector.fromJson(o);
                    if (!s.isEmpty()) list.add(s);
                }
            } else if (value instanceof JSONObject) {
                if (strict) error = candidateError((JSONObject) value);
                Selector s = Selector.fromJson((JSONObject) value);
                if (!s.isEmpty()) list.add(s);
            } else {
                error = "必须是条件对象或候选数组";
            }
            if (error == null && list.isEmpty()) error = "没有有效候选（空数组或空条件）";
            if (error == null) {
                out.valid.put(key, list);
            } else {
                out.invalid.put(key, error);
            }
        }
        return out;
    }

    private static String candidateError(JSONObject candidate) {
        if (candidate == null) return "不是条件对象";
        for (String field : new String[]{"text", "textContains", "textRegex", "desc", "descContains",
                "id", "className"}) {
            Object value = candidate.opt(field);
            if (value != null && value != JSONObject.NULL && !(value instanceof String)) {
                return field + " 必须是文本";
            }
        }
        for (String field : new String[]{"clickableOnly", "clickableAncestor", "visibleOnly",
                "requireArea", "topmost"}) {
            Object value = candidate.opt(field);
            if (value != null && value != JSONObject.NULL && !(value instanceof Boolean)) {
                return field + " 必须为 true 或 false";
            }
        }
        Object index = candidate.opt("index");
        if (index != null && index != JSONObject.NULL) {
            if (!(index instanceof Number)) return "index 必须是非负整数";
            double number = ((Number) index).doubleValue();
            if (!Double.isFinite(number) || number < 0 || number > Integer.MAX_VALUE
                    || number != Math.floor(number)) return "index 必须是非负整数";
        }
        Selector selector = Selector.fromJson(candidate);
        if (selector.isEmpty()) return "没有匹配条件";
        if (selector.textRegex != null && selector.regex() == null) return "textRegex 无法编译";
        return null;
    }

    private static void appendKeys(StringBuilder out, String label, Set<String> keys) {
        out.append('\n').append(label).append("：")
                .append(keys.isEmpty() ? "无" : String.join("、", new TreeSet<>(keys)));
    }

    private static void appendInvalid(StringBuilder out, String label, Map<String, String> invalid) {
        if (invalid.isEmpty()) return;
        out.append('\n').append(label).append("：");
        for (Map.Entry<String, String> entry : invalid.entrySet()) {
            out.append('\n').append("  ").append(entry.getKey()).append("：").append(entry.getValue());
        }
    }

    /** 没配这个 key 时返回空表，调用方会当成「找不到」按超时处理。 */
    public List<Selector> get(String key) {
        List<Selector> list = map.get(key);
        return list == null ? Collections.emptyList() : list;
    }

    public boolean has(String key) {
        return map.containsKey(key);
    }

    public Set<String> keys() {
        return new TreeSet<>(map.keySet());
    }

    /** 设置页自检用：列出这批 key 里哪些还没配。 */
    public List<String> missing(String... required) {
        List<String> out = new ArrayList<>();
        for (String k : required) {
            if (!map.containsKey(k)) out.add(k);
        }
        return out;
    }

    public String source() {
        return source;
    }

    /** 设置自检与任务日志复用；列出每个有效 key 的来源及无效项原因。 */
    public String diagnostics() {
        return diagnostics;
    }

    public boolean hasWarnings() {
        return warnings;
    }

    public String recoveryHint() {
        if (localPresent) {
            return "可在设置中点「导出内置选择器／恢复内置」，确认覆盖后再自检。"
                    + "覆盖会替换本地选择器修改，请先备份；账号和账本不会被清空。";
        }
        return "当前没有本地副本；内置缺项或损坏时，请安装包含完整内置配置的版本后再自检。";
    }

    /** 缺项提示必须能自救，也不能把配置错误报成当前页面没找到控件。 */
    public String missingMessage(String... required) {
        List<String> missing = missing(required);
        if (missing.isEmpty()) return "";
        return "缺少可用选择器：" + String.join("、", missing) + "\n" + diagnostics
                + "\n" + recoveryHint();
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
