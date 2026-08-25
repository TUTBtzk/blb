package com.example.blb.ui;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
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
import com.example.blb.auto.AutomationBus;
import com.example.blb.auto.AutomationService;
import com.example.blb.auto.BlbAccessibilityService;
import com.example.blb.data.Account;
import com.example.blb.data.AccountDao;
import com.example.blb.data.AccountStat;
import com.example.blb.data.Chapter;
import com.example.blb.data.CheckInRow;
import com.example.blb.data.Db;
import com.example.blb.data.Novel;
import com.example.blb.data.Purchase;
import com.example.blb.data.SubscriptionDao;
import com.example.blb.util.Texts;

import java.util.ArrayList;
import java.util.List;

/**
 * 二级页面：把签到页和订阅页上五处「信息很多、窗口很小」的东西各给一整屏。
 *
 * <p>原来的挤法和它们各自被挤掉的东西：辅助点击说明折成两行（后面还有大半段看不见）、
 * 今日状态 8 个号挤在半屏列表里、运行日志锁死在 120dp 约八行、8 个号的累计压成一行小字
 * 横向截断、已登记章节几百行塞在内嵌滚动区里。一级页面现在只留
 * {@link EntryRowView} 一行：标题 + <b>一句摘要</b> + 箭头。
 *
 * <p>摘要那一句是硬要求：使用者手指不能动，只有点开才看得到的信息对他等于不存在。
 * 二级页面同时留了 adb 入口（debug 构建的 {@code SHOW_PAGE}），这样不碰屏幕也能把某一页
 * 拉到前台看。
 */
public class DetailActivity extends AppCompatActivity {

    /** 要看哪一页。取值见下面五个 {@code PAGE_*}。 */
    public static final String EXTRA_PAGE = "page";

    /** 「这一趟里我会替你按什么」全文。 */
    public static final String PAGE_NOTICE = "notice";
    /** 今日各账号签到状态，一屏一个号一行。 */
    public static final String PAGE_TODAY = "today";
    /** 运行日志全文，自动滚到最后一行。 */
    public static final String PAGE_LOG = "log";
    /** 8 个号在账本里的累计，一行一个号。 */
    public static final String PAGE_STATS = "stats";
    /** 已登记的章节与订阅情况，点一章可补录／撤销，长按删章。 */
    public static final String PAGE_CHAPTERS = "chapters";

    private static final String[] PAGES = {
            PAGE_NOTICE, PAGE_TODAY, PAGE_LOG, PAGE_STATS, PAGE_CHAPTERS};

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
    private TextView bodyText;
    private TextView logText;
    private TextView empty;
    private ScrollView textScroll;
    private ScrollView logScroll;
    private RecyclerView list;

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
        bodyText = findViewById(R.id.detail_text);
        logText = findViewById(R.id.detail_log);
        empty = findViewById(R.id.detail_empty);
        textScroll = findViewById(R.id.detail_scroll);
        logScroll = findViewById(R.id.detail_log_scroll);
        list = findViewById(R.id.detail_list);
        findViewById(R.id.detail_back).setOnClickListener(v -> finish());
        ((ImageView) findViewById(R.id.detail_back)).setContentDescription(
                getString(R.string.detail_back));

        String page = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_PAGE);
        if (PAGE_TODAY.equals(page)) showToday();
        else if (PAGE_LOG.equals(page)) showLog();
        else if (PAGE_STATS.equals(page)) showStats();
        else if (PAGE_CHAPTERS.equals(page)) showChapters();
        else showNotice();
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
    }

    // ---------- 这一趟里我会替你按什么 ----------

    private void showNotice() {
        title.setText(R.string.checkin_notice_header);
        setSubtitle(getString(R.string.detail_notice_sub));
        bodyText.setText(R.string.checkin_ad_notice);
        textScroll.setVisibility(View.VISIBLE);
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
        AutomationBus.status().observe(this, s ->
                setSubtitle(Texts.isBlank(s) ? getString(R.string.checkin_idle) : s));
        AutomationBus.log().observe(this, lines -> {
            logText.setText(lines == null || lines.isEmpty()
                    ? getString(R.string.detail_log_empty) : TextUtils.join("\n", lines));
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    // ---------- 今日状态 ----------

    private void showToday() {
        String ymd = Texts.todayYmd();
        title.setText(getString(R.string.checkin_today_header) + "（" + ymd + "）");
        SimpleAdapter<CheckInRow> adapter =
                new SimpleAdapter<>(R.layout.item_two_line, (row, item, pos) ->
                        CheckInRows.bind(row, item));
        useList(adapter);
        Db.get(this).checkInDao().observeTodayStatus(ymd).observe(this, rows -> {
            adapter.submit(rows);
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

    // ---------- 已登记的章节与订阅情况 ----------

    /**
     * 章节列表整屏显示。交互：<b>点一章＝切到买过它的那个号</b>（见 {@link #onChapterClick}），
     * 长按＝补录／撤销／删章（{@link #openChapterSheet}）。
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
     * 就是多一次他按不动的门。补录／撤销挪到长按（{@link #openChapterSheet}）。
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
        // 一章两个号买过是要修的错（钱白花了），但这一下不许替他猜切哪个。
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
        if (!BlbAccessibilityService.isReady()) {
            toast("无障碍服务没连上，切不了号（重装 App 会把它踢掉，要重新打开）");
            return;
        }
        if (AutomationBus.isRunning()) {
            toast("有任务正在跑，等它跑完再切号（免得两边抢界面）");
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
     * 长按一章（没人订阅的章点一下也到这里）：列出所有账号，选谁就补录／撤销谁的订阅。
     *
     * <p>删这一章也放在这里。原来它是长按，而长按现在被这个窗口占了 —— 撤销误记录这件事
     * （{@code FORGET_OWNED} 那次）说明这个入口不能丢。
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
                    else confirmDeletePurchase(a, owned);
                })
                .setNeutralButton("删除这一章", (d, w) -> confirmDeleteChapter(chapter))
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
                    int cost = Math.max(0, Texts.parseCount(input.getText().toString()));
                    Db.io(() -> dao.upsertPurchase(Purchase.of(
                            account.id, chapter.id, 0, cost, Purchase.SRC_MANUAL)));
                })
                .show();
    }
    private void confirmDeletePurchase(Account account, Purchase purchase) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.sub_unmark)
                .setMessage(account.displayName() + " 这一章的记录会被删掉。"
                        + "这只改本地账本，不会退代券。")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) ->
                        Db.io(() -> dao.deletePurchase(purchase)))
                .show();
    }

    private void confirmDeleteChapter(Chapter chapter) {
        new AlertDialog.Builder(this)
                .setTitle("删除第" + chapter.chapterNo + "章？")
                .setMessage("这一章下面所有账号的订阅记录会一起删掉。")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) ->
                        Db.io(() -> dao.deleteChapter(chapter)))
                .show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
