package com.example.blb.auto;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 保留自动运行当时的只读现场；不切页面、不重新取根节点，也不删除旧记录。 */
public final class DiagnosticStore {

    public static final int RECENT_LIMIT = 30;
    private static final String DIRECTORY = "diagnostics";
    private static final String EXPORT_PREFIX = "blb-inspector-export-";

    private DiagnosticStore() { }

    /** 调用方须传入目标包硬闸已核验的 root；不能用别的窗口或旧缓存替代本次现场。 */
    public static File save(Context context, String stage, NodeView root, SelectorSet selectors)
            throws IOException {
        return save(context.getFilesDir(), stage, System.currentTimeMillis(), root, selectors);
    }

    static File save(File filesDirectory, String stage, long capturedAt, NodeView root,
                     SelectorSet selectors) throws IOException {
        if (root == null) throw new IOException("目标窗口根节点未读取，本次没有保存现场");
        String label = stage == null || stage.trim().isEmpty() ? "未命名阶段" : stage;
        String content = "运行取证阶段=" + label.replace("\r", "\\r").replace("\n", "\\n")
                + "\n采集时间（Unix 毫秒）=" + capturedAt + "\n"
                + InspectorCapture.evidenceContent(root, selectors)
                + "\n=== 运行取证文件结束 ===\n";
        File directory = new File(filesDirectory, DIRECTORY);
        ensureDirectory(directory);
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT)
                .format(new Date(capturedAt));
        File pending = File.createTempFile(safeStage(label) + "-" + timestamp + "-", ".pending", directory);
        write(pending, content);
        // 2026-09-15 长列表取证必须完整导出；未写完的文件不混进用户看到的已保存记录。
        File finished = new File(directory, pending.getName().replaceFirst("\\.pending$", ".txt"));
        if (finished.exists() || !pending.renameTo(finished)) {
            throw new IOException("现场文本已写入，但未完成存档：" + pending.getAbsolutePath());
        }
        return finished;
    }

    public static final class Listing {
        public final List<File> files;
        public final int totalCount;

        private Listing(List<File> files, int totalCount) {
            this.files = Collections.unmodifiableList(new ArrayList<>(files));
            this.totalCount = totalCount;
        }

        public String summary() {
            return "显示最近 " + files.size() + " 份／共 " + totalCount + " 份";
        }
    }

    public static Listing recent(Context context, int limit) throws IOException {
        return recent(context.getFilesDir(), limit);
    }

    static Listing recent(File filesDirectory, int limit) throws IOException {
        if (limit <= 0) throw new IllegalArgumentException("limit 必须大于零");
        File directory = new File(filesDirectory, DIRECTORY);
        if (!directory.exists()) return new Listing(Collections.emptyList(), 0);
        File[] children = directory.listFiles(file -> file.isFile() && file.getName().endsWith(".txt"));
        if (children == null) throw new IOException("运行取证目录无法读取：" + directory.getAbsolutePath());
        List<File> all = new ArrayList<>();
        Collections.addAll(all, children);
        all.sort((first, second) -> {
            int byTime = Long.compare(second.lastModified(), first.lastModified());
            return byTime != 0 ? byTime : second.getName().compareTo(first.getName());
        });
        return new Listing(all.subList(0, Math.min(limit, all.size())), all.size());
    }

    public static String read(Context context, File file) throws IOException {
        return read(context.getFilesDir(), file);
    }

    static String read(File filesDirectory, File file) throws IOException {
        requireChild(new File(filesDirectory, DIRECTORY), file);
        if (!file.getName().endsWith(".txt")) throw new IOException("这份运行取证尚未保存完成");
        return readText(file);
    }

    /** 保存对话框会让 Activity 暂停甚至重建；在打开它之前把待导出的正文固定到独立缓存文件。 */
    public static File freezeExport(Context context, String content) throws IOException {
        return freezeExport(context.getCacheDir(), content);
    }

    static File freezeExport(File cacheDirectory, String content) throws IOException {
        if (content == null) throw new IOException("没有待保存的正文");
        ensureDirectory(cacheDirectory);
        File frozen = File.createTempFile(EXPORT_PREFIX, ".txt", cacheDirectory);
        write(frozen, content);
        return frozen;
    }

    public static String readFrozenExport(Context context, File frozen) throws IOException {
        return readFrozenExport(context.getCacheDir(), frozen);
    }

    static String readFrozenExport(File cacheDirectory, File frozen) throws IOException {
        requireChild(cacheDirectory, frozen);
        if (!frozen.getName().startsWith(EXPORT_PREFIX) || !frozen.getName().endsWith(".txt")) {
            throw new IOException("待导出的固定正文路径无效");
        }
        return readText(frozen);
    }

    private static String safeStage(String stage) {
        String safe = stage.replaceAll("[^\\p{L}\\p{N}._-]+", "_");
        if (safe.length() > 60) safe = safe.substring(0, 60);
        return safe.isEmpty() ? "现场" : safe;
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && (!directory.mkdirs() || !directory.isDirectory())) {
            throw new IOException("无法创建取证目录：" + directory.getAbsolutePath());
        }
    }

    private static void requireChild(File directory, File file) throws IOException {
        if (file == null || !directory.getCanonicalFile().equals(file.getCanonicalFile().getParentFile())) {
            throw new IOException("文件不在本 App 的对应取证目录中");
        }
    }

    private static void write(File file, String content) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String readText(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
