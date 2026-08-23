package com.example.blb.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 够用的 CSV 读写：只处理逗号分隔、双引号转义、字段内换行这三件事。
 * 单独拆出来是为了能被普通 JVM 单元测试直接跑往返测试。
 */
public final class Csv {

    private Csv() {
    }

    public static String row(String... cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(Texts.csvCell(cells[i]));
        }
        sb.append('\n');
        return sb.toString();
    }

    /** 解析整个文件；空行忽略。 */
    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        if (text == null) return rows;

        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    // "" 表示一个字面双引号，单个 " 表示引号段结束。
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
                continue;
            }
            switch (c) {
                case '"':
                    quoted = true;
                    break;
                case ',':
                    row.add(cell.toString());
                    cell.setLength(0);
                    break;
                case '\r':
                    break;
                case '\n':
                    row.add(cell.toString());
                    cell.setLength(0);
                    if (!isEmptyRow(row)) rows.add(row);
                    row = new ArrayList<>();
                    break;
                default:
                    cell.append(c);
                    break;
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            if (!isEmptyRow(row)) rows.add(row);
        }
        return rows;
    }

    /** 越界或空白都返回空串，省掉调用处一堆判断。 */
    public static String at(List<String> row, int index) {
        if (row == null || index < 0 || index >= row.size()) return "";
        String v = row.get(index);
        return v == null ? "" : v.trim();
    }

    public static int intAt(List<String> row, int index) {
        return Texts.parseCount(at(row, index));
    }

    private static boolean isEmptyRow(List<String> row) {
        for (String c : row) {
            if (c != null && !c.trim().isEmpty()) return false;
        }
        return true;
    }
}
