package com.example.blb.auto;

import org.json.JSONObject;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 一条控件匹配条件。所有非空字段是「与」的关系；一个 key 下的多条 Selector 是「或」的关系，
 * 按顺序尝试。菠萝包改版时改 selectors.json 即可，不必重编译。
 */
public final class Selector {

    /** text 完全相等（两端空白已忽略）。 */
    public String text;
    /** text 包含该子串，忽略大小写。 */
    public String textContains;
    /** text 匹配该正则（find 语义，不要求整段匹配）。 */
    public String textRegex;
    /** contentDescription 完全相等。 */
    public String desc;
    /** contentDescription 包含该子串。 */
    public String descContains;
    /** viewIdResourceName，可写全名或只写 ":id/" 后面那段。 */
    public String id;
    /** className 包含该子串，例如写 "EditText" 就能匹配 android.widget.EditText。 */
    public String className;
    /** 只认本身可点击的节点。 */
    public boolean clickableOnly;
    /** 命中后向上找最近的可点击祖先——列表里的文字本身通常不可点。 */
    public boolean clickableAncestor;
    /**
     * 只认用户真的看得见、而且有面积的节点。
     *
     * <p>被弹窗覆盖的节点可能还在树里，匹配到它不代表可以对它下手。
     */
    public boolean visibleOnly;
    /**
     * 只认有面积的节点，但<b>不</b>问 isVisibleToUser。
     *
     * <p>余额标签、目录行等几何判据需要排除零面积的占位节点；是否可见仍由调用方单独选择。
     */
    public boolean requireArea;
    /**
     * 有多个命中时取<b>最后</b>一个（DFS 顺序里靠后 ≈ 画在最上层），而不是第一个。
     *
     */
    public boolean topmost;
    /** 有多个命中时取第几个，从 0 起（开了 topmost 就是从最后一个往前数）。 */
    public int index;

    private Pattern compiled;
    private boolean compileFailed;

    public static Selector fromJson(JSONObject o) {
        Selector s = new Selector();
        s.text = trimToNull(o.optString("text", null));
        s.textContains = trimToNull(o.optString("textContains", null));
        s.textRegex = trimToNull(o.optString("textRegex", null));
        s.desc = trimToNull(o.optString("desc", null));
        s.descContains = trimToNull(o.optString("descContains", null));
        s.id = trimToNull(o.optString("id", null));
        s.className = trimToNull(o.optString("className", null));
        s.clickableOnly = o.optBoolean("clickableOnly", false);
        s.clickableAncestor = o.optBoolean("clickableAncestor", false);
        s.visibleOnly = o.optBoolean("visibleOnly", false);
        s.requireArea = o.optBoolean("requireArea", false);
        s.topmost = o.optBoolean("topmost", false);
        s.index = o.optInt("index", 0);
        return s;
    }

    /** 一条什么条件都没写的 Selector 会匹配整棵树，必须当成配置错误挡掉。 */
    public boolean isEmpty() {
        return text == null && textContains == null && textRegex == null
                && desc == null && descContains == null && id == null && className == null;
    }

    /**
     * 复制一条，并把 text 换成运行时才知道的那串（书名、章节名）。
     *
     * <p>为什么需要：书名只有跑起来才知道，写不进 selectors.json；而「哪个 id 是书名行」
     * 必须留在 json 里（菠萝包改版时不该重编译）。这个方法把两半拼起来 ——
     * 「id 是 tv_think_text 且文本正好等于这本书的书名」。
     *
     * <p>实测这一步就是搜书点错行的根因：搜索框自己的文本也正好等于刚输进去的书名，
     * 只按文本找会先命中输入框，点它什么都不会发生。
     */
    public Selector withText(String value) {
        Selector s = new Selector();
        s.text = trimToNull(value);
        s.textContains = textContains;
        s.textRegex = textRegex;
        s.desc = desc;
        s.descContains = descContains;
        s.id = id;
        s.className = className;
        s.clickableOnly = clickableOnly;
        s.clickableAncestor = clickableAncestor;
        s.visibleOnly = visibleOnly;
        s.requireArea = requireArea;
        s.topmost = topmost;
        s.index = index;
        return s;
    }

    Pattern regex() {
        if (textRegex == null || compileFailed) return null;
        if (compiled == null) {
            try {
                compiled = Pattern.compile(textRegex);
            } catch (PatternSyntaxException e) {
                compileFailed = true;
                return null;
            }
        }
        return compiled;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Selector{");
        append(sb, "text", text);
        append(sb, "textContains", textContains);
        append(sb, "textRegex", textRegex);
        append(sb, "desc", desc);
        append(sb, "descContains", descContains);
        append(sb, "id", id);
        append(sb, "className", className);
        if (clickableOnly) sb.append("clickableOnly ");
        if (clickableAncestor) sb.append("clickableAncestor ");
        if (visibleOnly) sb.append("visibleOnly ");
        if (requireArea) sb.append("requireArea ");
        if (topmost) sb.append("topmost ");
        if (index != 0) sb.append("index=").append(index).append(' ');
        return sb.append('}').toString();
    }

    private static void append(StringBuilder sb, String name, String value) {
        if (value != null) sb.append(name).append('=').append(value).append(' ');
    }

    private static String trimToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() || "null".equals(t) ? null : t;
    }
}
