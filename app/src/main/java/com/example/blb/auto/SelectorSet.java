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
 * <p>优先读 filesDir/selectors.json（用节点探测器导出后可直接热改，不用重装），
 * 没有就退回 assets/selectors.json。
 */
public final class SelectorSet {

    public static final String FILE_NAME = "selectors.json";
    private static final String TAG = "BlbAuto";

    private final Map<String, List<Selector>> map;
    private final String source;

    private SelectorSet(Map<String, List<Selector>> map, String source) {
        this.map = map;
        this.source = source;
    }

    public static SelectorSet load(Context context) {
        File override = overrideFile(context);
        if (override.isFile()) {
            try (InputStream in = new FileInputStream(override)) {
                return new SelectorSet(parse(readAll(in)), override.getAbsolutePath());
            } catch (Exception e) {
                Log.w(TAG, "读 filesDir/selectors.json 失败，回退到内置版本", e);
            }
        }
        try (InputStream in = context.getAssets().open(FILE_NAME)) {
            return new SelectorSet(parse(readAll(in)), "assets/" + FILE_NAME);
        } catch (Exception e) {
            Log.e(TAG, "内置 selectors.json 也读不出来", e);
            return new SelectorSet(new LinkedHashMap<>(), "(空)");
        }
    }

    public static File overrideFile(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    static Map<String, List<Selector>> parse(String json) throws Exception {
        Map<String, List<Selector>> out = new LinkedHashMap<>();
        JSONObject root = new JSONObject(json);
        for (java.util.Iterator<String> it = root.keys(); it.hasNext(); ) {
            String key = it.next();
            if (key.startsWith("_")) continue; // _note 之类的说明字段
            Object value = root.get(key);
            List<Selector> list = new ArrayList<>();
            if (value instanceof JSONArray) {
                JSONArray arr = (JSONArray) value;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    Selector s = Selector.fromJson(o);
                    if (!s.isEmpty()) list.add(s);
                }
            } else if (value instanceof JSONObject) {
                Selector s = Selector.fromJson((JSONObject) value);
                if (!s.isEmpty()) list.add(s);
            }
            if (!list.isEmpty()) out.put(key, list);
        }
        return out;
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

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
