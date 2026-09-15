package com.example.blb.ui;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.ColorRes;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.LiveData;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.blb.R;
import com.example.blb.auto.AccessibilityAccess;
import com.example.blb.auto.AutomationBus;
import com.example.blb.auto.AutomationService;
import com.example.blb.auto.BlbAccessibilityService;
import com.example.blb.data.Account;
import com.example.blb.data.AccountDao;
import com.example.blb.data.AccountStat;
import com.example.blb.data.Chapter;
import com.example.blb.data.CheckInRow;
import com.example.blb.data.Db;
import com.example.blb.data.LedgerAudit;
import com.example.blb.data.LedgerAuditPayload;
import com.example.blb.data.Novel;
import com.example.blb.data.Purchase;
import com.example.blb.data.SubscriptionDao;
import com.example.blb.util.DayRollover;
import com.example.blb.util.Texts;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 二级页面：把签到页和订阅页上「信息很多、窗口很小」的东西各给一整屏。
 *
 * <p>原来的挤法和它们各自被挤掉的东西：今日状态 8 个号挤在半屏列表里、
 * 运行日志锁死在 120dp 约八行、8 个号的累计压成一行小字
 * 横向截断、已登记章节几百行塞在内嵌滚动区里。一级页面现在只留
 * {@link EntryRowView} 一行：标题 + <b>一句摘要</b> + 箭头。
 *
 * <p>摘要那一句是硬要求：使用者手指不能动，只有点开才看得到的信息对他等于不存在。
 * 二级页面同时留了 adb 入口（debug 构建的 {@code SHOW_PAGE}），这样不碰屏幕也能把某一页
 * 拉到前台看。
 */
public class DetailActivity extends AppCompatActivity {

    /** 要看哪一页。取值见下面的 {@code PAGE_*}。 */
    public static final String EXTRA_PAGE = "page";

    /** 今日各账号签到状态，一屏一个号一行。 */
    public static final String PAGE_TODAY = "today";
    /** 运行日志全文，自动滚到最后一行。 */
    public static final String PAGE_LOG = "log";
    /** 8 个号在账本里的累计，一行一个号。 */
    public static final String PAGE_STATS = "stats";
    /** 已登记的章节与订阅情况；已有记录的修正要走核对证据。 */
    public static final String PAGE_CHAPTERS = "chapters";
    /** 修正依据要留在列表行，免得只能点进详情才能知道为什么账本变了。 */
    public static final String PAGE_SUSPECT = "suspect";

    private static final String[] PAGES = {
            PAGE_TODAY, PAGE_LOG, PAGE_STATS, PAGE_CHAPTERS, PAGE_SUSPECT};

    public static Intent intent(Context context, String page) {
        return new Intent(context, DetailActivity.class).putExtra(EXTRA_PAGE, page);
    }

    public static void open(Context context, String page) {
        context.startActivity(intent(context, page));
    }

    /** adb 入口用：这个页面名认不认。认不出就别启动，免得开出一页空白。 */
    public static boolean isKnownPage(@Nullable String page) {
        for (String p : PAGES) {
            if (p.equals(page)) return true;
        }
        return false;
    }

    /** adb 入口用：把认得的页面名列出来，出错时原样念给人看。 */
    public static String pageNames() {
        return TextUtils.join("｜", PAGES);
    }

    private TextView title;
    private TextView subtitle;
    private TextView logText;
    private TextView empty;
    /** 「运行日志」页头部那个「导出」；其它页一律不出现。 */
    private TextView exportLog;
    /** 最近一次渲染出来的日志行，导出时原样落盘。 */
    private List<String> logLines = new ArrayList<>();
    private ActivityResultLauncher<String> logExportLauncher;
    private ScrollView logScroll;
    private RecyclerView list;
    private FastScrollBar scrollBar;
    private TextView scrollBubble;
    private DayRollover todayRollover;
    private LiveData<List<CheckInRow>> todayRows;
    private SimpleAdapter<CheckInRow> todayAdapter;

    // ---- 只有「已登记的章节」那一页用得上 ----
    private SubscriptionDao dao;
    private AccountDao accountDao;
    private SimpleAdapter<Chapter> chapterAdapter;
    private List<Novel> novels = new ArrayList<>();
    private List<Account> accounts = new ArrayList<>();
    private List<Chapter> chapters = new ArrayList<>();
    private List<Purchase> purchases = new ArrayList<>();
    private Novel current;
    private LiveData<List<Chapter>> chaptersLd;
    private LiveData<List<Purchase>> purchasesLd;
    private SimpleAdapter<LedgerAudit> auditAdapter;
    private List<LedgerAudit> ledgerAudits = new ArrayList<>();
    private LiveData<List<LedgerAudit>> ledgerAuditsLd;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_detail);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.detail_root), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft() + bars.left, bars.top,
                    v.getPaddingRight() + bars.right, bars.bottom);
            return insets;
        });

        title = findViewById(R.id.detail_title);
        subtitle = findViewById(R.id.detail_subtitle);
        logText = findViewById(R.id.detail_log);
        empty = findViewById(R.id.detail_empty);
        exportLog = findViewById(R.id.detail_export);
        logExportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("text/plain"), this::writeLog);
        exportLog.setOnClickListener(v -> logExportLauncher.launch(
                "blb-log-" + Texts.todayYmd() + ".txt"));
        logScroll = findViewById(R.id.detail_log_scroll);
        list = findViewById(R.id.detail_list);
        scrollBar = findViewById(R.id.detail_scrollbar);
        scrollBubble = findViewById(R.id.detail_scroll_bubble);
        findViewById(R.id.detail_back).setOnClickListener(v -> finish());
        ((ImageView) findViewById(R.id.detail_back)).setContentDescription(
                getString(R.string.detail_back));

        String page = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_PAGE);
        if (PAGE_TODAY.equals(page)) showToday();
        else if (PAGE_LOG.equals(page)) showLog();
        else if (PAGE_STATS.equals(page)) showStats();
        else if (PAGE_CHAPTERS.equals(page)) showChapters();
        else if (PAGE_SUSPECT.equals(page)) showSuspect();
        else finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (todayRollover != null) todayRollover.start();
    }

    @Override
    protected void onPause() {
        if (todayRollover != null) todayRollover.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (todayRollover != null) todayRollover.stop();
        if (todayRows != null) todayRows.removeObservers(this);
        super.onDestroy();
    }

    // ---------- 页面外壳 ----------

    private void setSubtitle(CharSequence text) {
        boolean blank = text == null || Texts.isBlank(text.toString());
        subtitle.setText(blank ? "" : text);
        subtitle.setVisibility(blank ? View.GONE : View.VISIBLE);
    }

    private void showEmpty(boolean show, String message) {
        empty.setText(message);
        empty.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private <T> void useList(SimpleAdapter<T> adapter) {
        list.setVisibility(View.VISIBLE);
        list.setLayoutManager(new LinearLayoutManager(this));
        // 行本身是卡片、自带间距，再画分割线只会在卡片之间多一道横杠。
        list.setAdapter(adapter);
        // 常驻滚动条：内容不到一屏它自己收起来，所以三个列表页都能无脑挂上。
        scrollBar.attach(list, scrollBubble);
    }

    // ---------- 运行日志 ----------

    /**
     * 日志整屏显示，并且每次来新行都滚到底。
     *
     * <p>原来它只有 120dp（约八行）：一趟要跑 8 个号，出问题的那几行往往已经被挤出去了。
     */
    private void showLog() {
        title.setText(R.string.checkin_log_header);
        logScroll.setVisibility(View.VISIBLE);
        exportLog.setVisibility(View.VISIBLE);
        AutomationBus.status().observe(this, s ->
                setSubtitle(Texts.isBlank(s) ? getString(R.string.checkin_idle) : s));
        AutomationBus.log().observe(this, lines -> {
            logLines = lines == null ? new ArrayList<>() : new ArrayList<>(lines);
            logText.setText(lines == null || lines.isEmpty()
                    ? getString(R.string.detail_log_empty) : TextUtils.join("\n", lines));
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    /**
     * 把当前日志正文写成用户选定的文件。
     *
     * <p>为什么不用「分享」：日志是几千行纯文本，发出来的现场必须是整份原文 ——
     * 手工划屏选中一定截断，而被截掉的往往正是出问题的那几行。
     */
    private void writeLog(@Nullable Uri uri) {
        if (uri == null) return;
        String body = TextUtils.join("\n", logLines);
        int count = logLines.size();
        Context app = getApplicationContext();
        new Thread(() -> {
            try (OutputStream out = app.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("openOutputStream 返回 null");
                out.write(body.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.detail_log_export_failed, String.valueOf(e.getMessage())),
                        Toast.LENGTH_LONG).show());
                return;
            }
            runOnUiThread(() -> Toast.makeText(this,
                    getString(R.string.detail_log_exported, count), Toast.LENGTH_SHORT).show());
        }).start();
    }

    // ---------- 今日状态 ----------

    private void showToday() {
        title.setText(R.string.checkin_today_header);
        todayAdapter = new SimpleAdapter<>(R.layout.item_two_line, (row, item, pos) ->
                CheckInRows.bind(row, item));
        useList(todayAdapter);
        Handler handler = new Handler(Looper.getMainLooper());
        todayRollover = new DayRollover(new DayRollover.Scheduler() {
            @Override
            public void postDelayed(Runnable action, long delayMillis) {
                handler.postDelayed(action, delayMillis);
            }

            @Override
            public void removeCallbacks(Runnable action) {
                handler.removeCallbacks(action);
            }
        }, this::bindToday);
    }

    private void bindToday(String ymd) {
        if (todayRows != null) todayRows.removeObservers(this);
        title.setText(getString(R.string.checkin_today_header) + "（" + ymd + "）");
        todayAdapter.submit(null);
        setSubtitle("正在读取今日状态…");
        showEmpty(false, "");
        todayRows = Db.get(this).checkInDao().observeTodayStatus(ymd);
        todayRows.observe(this, rows -> {
            todayAdapter.submit(rows);
            setSubtitle(CheckInRows.summary(rows));
            showEmpty(rows == null || rows.isEmpty(), getString(R.string.checkin_no_account));
        });
    }

    // ---------- 各账号累计 ----------

    private void showStats() {
        title.setText(R.string.detail_stats_title);
        SimpleAdapter<AccountStat> adapter =
                new SimpleAdapter<>(R.layout.item_two_line, (row, item, pos) -> {
                    row.<TextView>findViewById(R.id.line1).setText(item.displayName());
                    row.<TextView>findViewById(R.id.line2).setText(AccountStatText.line(item));
                    row.findViewById(R.id.badge).setVisibility(View.GONE);
                    // 色条＝这个号有没有为这本书出过力：买过章＝绿，一章没买＝灰。
                    StatusPalette tone = item.chapterCount > 0
                            ? StatusPalette.OK : StatusPalette.SKIP;
                    row.findViewById(R.id.accent).setBackgroundColor(color(tone.foreground));
                });
        useList(adapter);
        Db.get(this).subscriptionDao().observeAccountStats().observe(this, l -> {
            adapter.submit(l);
            setSubtitle(AccountStatText.summary(l));
            showEmpty(l == null || l.isEmpty(), getString(R.string.detail_stats_empty));
        });
    }

    private int color(@ColorRes int res) {
        return ContextCompat.getColor(this, res);
    }

    // ---------- 核对记录与存疑 ----------

    private void showSuspect() {
        title.setText(R.string.detail_suspect_title);
        dao = Db.get(this).subscriptionDao();
        accountDao = Db.get(this).accountDao();
        auditAdapter = new SimpleAdapter<>(R.layout.item_two_line, this::bindAudit);
        useList(auditAdapter);
        accountDao.observeAll().observe(this, rows -> {
            accounts = rows == null ? new ArrayList<>() : rows;
            auditAdapter.notifyDataSetChanged();
        });
        AutomationBus.busy().observe(this, busy -> auditAdapter.notifyDataSetChanged());
        dao.observeNovels().observe(this, rows -> {
            Novel selected = null;
            if (rows != null) {
                for (Novel novel : rows) {
                    if (novel.isTarget) {
                        selected = novel;
                        break;
                    }
                }
                // 与订阅页展示同一本书，免得从入口进来看到另一份修账依据。
                if (selected == null && !rows.isEmpty()) selected = rows.get(0);
            }
            long oldId = current == null ? 0 : current.id;
            current = selected;
            if (current == null || current.id != oldId) {
                if (ledgerAuditsLd != null) ledgerAuditsLd.removeObservers(this);
                ledgerAuditsLd = null;
                ledgerAudits = new ArrayList<>();
                if (current != null) {
                    ledgerAuditsLd = Db.get(this).auditDao()
                            .observeRecentLedgerAudits(current.id, 100);
                    ledgerAuditsLd.observe(this, audits -> {
                        ledgerAudits = audits == null ? new ArrayList<>() : audits;
                        renderAudits();
                    });
                }
            }
            renderAudits();
        });
    }

    private void renderAudits() {
        setSubtitle(current == null ? getString(R.string.detail_suspect_no_target)
                : "《" + current.title + "》 · 最近 " + ledgerAudits.size()
                + " 条核对记录\n撤销会恢复修正前的本地订阅记录");
        auditAdapter.submit(ledgerAudits);
        showEmpty(ledgerAudits.isEmpty(), getString(current == null
                ? R.string.detail_suspect_no_target : R.string.detail_suspect_empty));
    }

    private void bindAudit(View row, LedgerAudit audit, int position) {
        String action;
        StatusPalette tone;
        if (LedgerAudit.KIND_DELETE.equals(audit.kind)) {
            action = "修正错记";
            tone = StatusPalette.WARN;
        } else if (LedgerAudit.KIND_SUSPECT.equals(audit.kind)) {
            action = "存疑，保留账本";
            tone = StatusPalette.WARN;
        } else if (LedgerAudit.KIND_BACKFILL.equals(audit.kind)) {
            action = "补记漏账";
            tone = StatusPalette.OK;
        } else if (LedgerAudit.KIND_RESTORE.equals(audit.kind)) {
            action = "已撤销修正";
            tone = StatusPalette.OK;
        } else {
            action = "未知核对动作";
            tone = StatusPalette.WARN;
        }
        String chapter = audit.chapterNo > 0 ? " · 第" + audit.chapterNo + "章" : "";
        String heading = action + " · " + accountName(audit.accountId) + chapter
                + (Texts.isBlank(audit.title) ? "" : "「" + audit.title + "」");
        row.<TextView>findViewById(R.id.line1).setText(heading);
        String when = audit.at > 0 ? DateFormat.format("yyyy-MM-dd HH:mm:ss", audit.at).toString()
                : "时间未记录";
        String evidence = LedgerAuditPayload.message(audit.detail);
        row.<TextView>findViewById(R.id.line2).setText(when + "\n依据："
                + (Texts.isBlank(evidence) ? "没有保存可读依据" : evidence));
        row.findViewById(R.id.accent).setBackgroundColor(color(tone.foreground));

        TextView undo = row.findViewById(R.id.badge);
        boolean deletion = LedgerAudit.KIND_DELETE.equals(audit.kind);
        undo.setVisibility(deletion ? View.VISIBLE : View.GONE);
        undo.setText(R.string.detail_audit_undo);
        undo.setTextColor(color(StatusPalette.WARN.foreground));
        undo.setBackgroundTintList(ColorStateList.valueOf(color(StatusPalette.WARN.container)));
        undo.setMinHeight(Math.round(48 * getResources().getDisplayMetrics().density));
        undo.setGravity(android.view.Gravity.CENTER);
        undo.setFocusable(deletion);
        undo.setContentDescription("撤销这条修正：" + heading);
        undo.setEnabled(!AutomationBus.isBusy());
        undo.setOnClickListener(deletion ? v -> restoreAudit(audit.id) : null);
    }

    /** 原记录与冲突判据都由 DAO 核实；页面不能凭一条旧列表行就拼一笔新购买。 */
    private void restoreAudit(long auditId) {
        Context app = getApplicationContext();
        String[] result = new String[1];
        LedgerEdits.submit(app, () -> result[0] = Db.get(app).auditDao().restore(auditId),
                () -> Toast.makeText(app, result[0], Toast.LENGTH_LONG).show());
    }

    // ---------- 已登记的章节与订阅情况 ----------

    /**
     * 章节列表整屏显示。交互：<b>点一章＝切到买过它的那个号</b>（见 {@link #onChapterClick}），
     * 长按＝补录／核对说明／删除空登记（{@link #openChapterSheet}）。
     *
     * <p>原来它挤在订阅页下半屏的内嵌滚动区里：一本书六百多章，外层还能跟着一起滚，
     * 找一章要来回蹭很久。这一页只干一件事，滚起来不会跟别的东西打架。
     */
    private void showChapters() {
        dao = Db.get(this).subscriptionDao();
        accountDao = Db.get(this).accountDao();
        title.setText(R.string.sub_records_header);
        chapterAdapter = new SimpleAdapter<Chapter>(R.layout.item_two_line, this::bindChapter)
                .onClick((item, pos) -> onChapterClick(item))
                .onLongClick((item, pos) -> openChapterSheet(item));
        useList(chapterAdapter);
        // 拖滚动条时气泡上写「第几章」：一屏 8 行、九十来章，光看滑块位置猜不出拖到哪儿了。
        scrollBar.setLabeler(pos -> {
            Chapter c = chapterAdapter.itemAt(pos);
            return c == null ? null : "第" + c.chapterNo + "章";
        });

        dao.observeNovels().observe(this, this::onNovels);
        accountDao.observeAll().observe(this, l -> {
            accounts = l == null ? new ArrayList<>() : l;
            renderChapters();
        });
    }

    private void onNovels(List<Novel> list) {
        novels = list == null ? new ArrayList<>() : list;
        Novel newTarget = null;
        for (Novel n : novels) {
            if (n.isTarget) {
                newTarget = n;
                break;
            }
        }
        // 一本书都没标目标时先显示第一本 —— 跟订阅页一级页面的取法保持一致。
        if (newTarget == null && !novels.isEmpty()) newTarget = novels.get(0);

        long oldId = current == null ? 0 : current.id;
        current = newTarget;
        if (current == null) {
            detachNovelObservers();
            chapters = new ArrayList<>();
            purchases = new ArrayList<>();
        } else if (current.id != oldId) {
            attachNovelObservers(current.id);
        }
        renderChapters();
    }

    private void detachNovelObservers() {
        if (chaptersLd != null) chaptersLd.removeObservers(this);
        if (purchasesLd != null) purchasesLd.removeObservers(this);
        chaptersLd = null;
        purchasesLd = null;
    }

    private void attachNovelObservers(long novelId) {
        detachNovelObservers();
        chaptersLd = dao.observeChapters(novelId);
        chaptersLd.observe(this, l -> {
            chapters = l == null ? new ArrayList<>() : l;
            renderChapters();
        });
        purchasesLd = dao.observePurchasesOfNovel(novelId);
        purchasesLd.observe(this, l -> {
            purchases = l == null ? new ArrayList<>() : l;
            renderChapters();
        });
    }

    private void renderChapters() {
        setSubtitle(current == null ? null
                : "《" + current.title + "》　" + ChapterLedgerText.summary(chapters, purchases));
        // 空态两种：一本小说都没有，和有小说但这本还没登记章节。
        if (novels.isEmpty()) showEmpty(true, getString(R.string.sub_empty));
        else if (chapters.isEmpty()) showEmpty(true, getString(R.string.sub_no_chapters));
        else showEmpty(false, "");
        chapterAdapter.submit(chapters);
    }
    private void bindChapter(View row, Chapter c, int position) {
        StringBuilder line1 = new StringBuilder("第").append(c.chapterNo).append('章');
        if (!Texts.isBlank(c.title)) line1.append(' ').append(c.title);
        if (c.priceCoupons > 0) line1.append("　").append(c.priceCoupons).append(" 券");
        row.<TextView>findViewById(R.id.line1).setText(line1);

        // 「谁买的」和「不用买」必须分开说。source=OWNED 只在<b>免费章</b>上写（没有锁的行，
        // 谁登录都看得到），2026-08-25 拿订阅清单逐章对过账：8 个号在这本书上只付费订阅过
        // 第48／49／50／51 章，第1～47 章一分券都没花过 —— 它们是免费章，不是「谁买的说不清」。
        ChapterOwnership own = ChapterOwnership.of(purchases, c.id);
        List<String> buyers = new ArrayList<>();
        for (Long id : own.buyerIds) buyers.add(accountName(id));
        int deviceOnly = own.deviceOnly;
        StringBuilder line2 = new StringBuilder();
        if (!buyers.isEmpty()) line2.append("已订阅：").append(TextUtils.join("、", buyers));
        if (deviceOnly > 0) {
            if (line2.length() > 0) line2.append(" · ");
            line2.append(buyers.isEmpty() ? "免费章，谁登录都看得到（不用买）" : "也是免费章");
        }
        if (line2.length() == 0) line2.append("还没人订阅");
        row.<TextView>findViewById(R.id.line2).setText(line2);

        // 色条＝这一章要不要花券买。只有真买记录算绿；免费章算灰 —— 它不需要谁去买，
        // 点一下也没有号可切，摘要里也是单独报的，两处得说同一件事。
        StatusPalette tone = buyers.isEmpty() ? StatusPalette.SKIP : StatusPalette.OK;
        row.findViewById(R.id.accent).setBackgroundColor(color(tone.foreground));

        // 有买家的行点一下就会切到那个号，这件事得在行上写明白：切号是退登重登，
        // 误点一下要挨一次登录（还可能撞验证码），不该让人猜。
        TextView badge = row.findViewById(R.id.badge);
        badge.setVisibility(buyers.isEmpty() ? View.GONE : View.VISIBLE);
        if (!buyers.isEmpty()) {
            badge.setText("点→切号");
            badge.setTextColor(color(StatusPalette.OK.foreground));
            badge.setBackgroundTintList(
                    ColorStateList.valueOf(color(StatusPalette.OK.container)));
        }
    }

    /**
     * 点一章：<b>有买家就切到那个号</b>（退出现在登着的号、登入买家），没买家才开补录窗口。
     *
     * <p>用户原话：「第48章显示五杯半雪碧订阅的，点击这一章就自动执行退出当前账号，
     * 登入五杯半雪碧的账号」。所以这一下不再弹确认框 —— 他手指动不了，每多一次确认
     * 就是多一次他按不动的门。补录／核对说明挪到长按（{@link #openChapterSheet}）。
     */
    private void onChapterClick(Chapter chapter) {
        ChapterOwnership own = ChapterOwnership.of(purchases, chapter.id);
        if (!own.hasBuyer()) {
            // 免费章（source=OWNED）也走这里：它没有买家，切号只能是瞎切一个号。
            openChapterSheet(chapter);
            return;
        }
        if (own.buyerIds.size() == 1) {
            switchToBuyer(own.buyerIds.get(0), chapter);
            return;
        }
        // 2026-09-14 真机核对确认同章可能被多个号实际订过；都保留为事实，切号交给用户选择。
        String[] labels = new String[own.buyerIds.size()];
        for (int i = 0; i < own.buyerIds.size(); i++) labels[i] = accountName(own.buyerIds.get(i));
        new AlertDialog.Builder(this)
                .setTitle("第" + chapter.chapterNo + "章有 " + labels.length + " 个号买过，切到哪个？")
                .setItems(labels, (d, which) -> switchToBuyer(own.buyerIds.get(which), chapter))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 真的去切号：交给 {@link AutomationService}，它整趟点着屏幕（无障碍只对亮屏有效）。 */
    private void switchToBuyer(long accountId, Chapter chapter) {
        Account account = accountById(accountId);
        if (account == null) {
            toast("账本里这一章记在 账号#" + accountId + " 名下，可账号页已经没有这个号了");
            return;
        }
        if (AccessibilityAccess.state(this) == AccessibilityAccess.State.DISABLED) {
            AccessibilityAccess.RestoreResult restore =
                    AccessibilityAccess.restoreIfAuthorized(this);
            if (restore == AccessibilityAccess.RestoreResult.NOT_AUTHORIZED
                    || restore == AccessibilityAccess.RestoreResult.FAILED) {
                toast("系统无障碍开关已关闭，无法自动恢复，切不了号");
                return;
            }
        }
        if (AutomationBus.isBusy()) {
            toast("有任务正在运行或账本正在更新，请等结束后再切号");
            return;
        }
        toast("正在切到「" + account.displayName() + "」：退出当前账号再登它。"
                + "进度看签到页的运行日志");
        AutomationService.startSwitchAccount(this, accountId);
    }

    private Account accountById(long accountId) {
        for (Account a : accounts) {
            if (a.id == accountId) return a;
        }
        return null;
    }

    private String accountName(long accountId) {
        for (Account a : accounts) {
            if (a.id == accountId) return a.displayName();
        }
        return "账号#" + accountId;
    }

    /** 一条记录花了多少：现在花的是代券，火券只有历史记录里才有。 */
    private static String describeCost(Purchase p) {
        if (p.costVouchers > 0 && p.costCoupons > 0) {
            return p.costVouchers + " 代券+" + p.costCoupons + " 火券";
        }
        if (p.costVouchers > 0) return p.costVouchers + " 代券";
        if (p.costCoupons > 0) return p.costCoupons + " 火券";
        return "花费未记";
    }

    private Purchase purchaseOf(long accountId, long chapterId) {
        for (Purchase p : purchases) {
            if (p.accountId == accountId && p.chapterId == chapterId) return p;
        }
        return null;
    }
    /**
     * 第49章曾被误挂到两个号，已有记录不能靠点一下账号就抹掉；必须回到订阅清单拿证据。
     * 没有记录时仍可补录，空章节登记才允许在这里删除。
     */
    private void openChapterSheet(Chapter chapter) {
        if (accounts.isEmpty()) {
            toast("先去账号页添加账号");
            return;
        }
        String[] labels = new String[accounts.size()];
        for (int i = 0; i < accounts.size(); i++) {
            Account a = accounts.get(i);
            Purchase owned = purchaseOf(a.id, chapter.id);
            String mark = owned == null ? "未订阅"
                    : Purchase.SRC_OWNED.equals(owned.source) ? "免费章（不用买）"
                    : "已订阅 " + describeCost(owned);
            labels[i] = a.displayName() + " — " + mark;
        }
        new AlertDialog.Builder(this)
                .setTitle("第" + chapter.chapterNo + "章")
                .setItems(labels, (d, which) -> {
                    Account a = accounts.get(which);
                    Purchase owned = purchaseOf(a.id, chapter.id);
                    if (owned == null) askCostThenRecord(a, chapter);
                    else explainPurchaseRepair(a);
                })
                .setNeutralButton("删除无账章节", (d, w) -> confirmDeleteChapter(chapter))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void askCostThenRecord(Account account, Chapter chapter) {
        View form = LayoutInflater.from(this).inflate(R.layout.dialog_single_input, null, false);
        TextView label = form.findViewById(R.id.label);
        EditText input = form.findViewById(R.id.input);
        label.setText(getString(R.string.sub_cost_prompt));
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(chapter.priceCoupons > 0 ? String.valueOf(chapter.priceCoupons) : "");

        new AlertDialog.Builder(this)
                .setTitle(account.displayName() + " 订阅第" + chapter.chapterNo + "章")
                .setView(form)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> {
                    // 手动补录填的是代券：现在能买下来的章一律「实付 0 火券」。
                    int cost = Texts.parseCount(input.getText().toString());
                    if (cost < 0) {
                        toast("代券花费没填清楚，暂时不写账本；请填写实际花费");
                        return;
                    }
                    Context app = getApplicationContext();
                    LedgerEdits.submit(app, () -> {
                        dao.upsertPurchase(Purchase.of(
                                account.id, chapter.id, 0, cost, Purchase.SRC_MANUAL));
                        Db.get(app).auditDao().invalidateNovel(chapter.novelId);
                    });
                })
                .show();
    }
    private void explainPurchaseRepair(Account account) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.sub_unmark)
                .setMessage(account.displayName() + " 的订阅记录不能直接删除。"
                        + "请先在订阅页点『核对订阅清单』；证据齐全的错记会修正并留下可撤销记录，"
                        + "证据不足会保留账本并标为存疑。")
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void confirmDeleteChapter(Chapter chapter) {
        Context app = getApplicationContext();
        new AlertDialog.Builder(this)
                .setTitle("删除第" + chapter.chapterNo + "章的无账登记？")
                .setMessage("仅允许删除没有任何账号订阅记录的章节登记。"
                        + "已有记录时请先在订阅页点『核对订阅清单』，不能通过删章节抹掉账本。")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton("删除无账章节", (d, w) ->
                        LedgerEdits.submit(app, () -> {
                            dao.deleteChapter(chapter);
                            Db.get(app).auditDao().invalidateNovel(chapter.novelId);
                        }))
                .show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
