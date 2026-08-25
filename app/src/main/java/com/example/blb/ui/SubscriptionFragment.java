package com.example.blb.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.blb.R;
import com.example.blb.auto.AutomationBus;
import com.example.blb.auto.AutomationService;
import com.example.blb.auto.BlbAccessibilityService;
import com.example.blb.auto.Keys;
import com.example.blb.auto.SelectorSet;
import com.example.blb.data.Account;
import com.example.blb.data.AccountDao;
import com.example.blb.data.AccountStat;
import com.example.blb.data.Chapter;
import com.example.blb.data.Db;
import com.example.blb.data.Novel;
import com.example.blb.data.Purchase;
import com.example.blb.data.PurchaseRow;
import com.example.blb.data.SubscriptionDao;
import com.example.blb.util.Csv;
import com.example.blb.util.Prefs;
import com.example.blb.util.Texts;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 订阅账本页：谁订了哪本书的哪一章，全在这里。
 *
 * <p>账本是「这一章归谁」的唯一权威：自动订阅按它挑下一章，也只有它答得出
 * 「这一章我用哪个号买过了没有」。界面上那个「已下载」是本机状态、8 个号共用，答不出买家。
 */
public class SubscriptionFragment extends Fragment {

    private SubscriptionDao dao;
    private AccountDao accountDao;
    private SimpleAdapter<Chapter> adapter;

    private TextView target;
    private TextView suggestion;
    private TextView stats;
    private TextView empty;
    private Button runSubscribe;

    private List<Novel> novels = new ArrayList<>();
    private List<Account> accounts = new ArrayList<>();
    private List<Chapter> chapters = new ArrayList<>();
    private List<Purchase> purchases = new ArrayList<>();
    private Novel current;

    private LiveData<List<Chapter>> chaptersLd;
    private LiveData<List<Purchase>> purchasesLd;

    private ActivityResultLauncher<String> exportLauncher;
    private ActivityResultLauncher<String[]> importLauncher;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_subscription, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        dao = Db.get(requireContext()).subscriptionDao();
        accountDao = Db.get(requireContext()).accountDao();

        target = v.findViewById(R.id.target);
        suggestion = v.findViewById(R.id.suggestion);
        stats = v.findViewById(R.id.stats);
        empty = v.findViewById(R.id.empty);
        runSubscribe = v.findViewById(R.id.run_subscribe);

        adapter = new SimpleAdapter<Chapter>(R.layout.item_two_line, this::bindChapter)
                .onClick((item, pos) -> openChapterSheet(item))
                .onLongClick((item, pos) -> confirmDeleteChapter(item));
        RecyclerView list = v.findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        // 行是卡片，卡片之间已经有间距，不再画分割线。
        list.setAdapter(adapter);

        exportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("text/csv"), this::writeCsv);
        importLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::readCsv);

        v.<Button>findViewById(R.id.pick_novel).setOnClickListener(b -> pickNovel());
        v.<Button>findViewById(R.id.add_novel).setOnClickListener(b -> addNovel());
        v.<Button>findViewById(R.id.add_chapter).setOnClickListener(b -> addChapters());
        v.<Button>findViewById(R.id.export_csv).setOnClickListener(b ->
                exportLauncher.launch("blb-subscriptions-" + Texts.todayYmd() + ".csv"));
        v.<Button>findViewById(R.id.import_csv).setOnClickListener(b ->
                importLauncher.launch(new String[]{"text/*", "text/csv", "text/comma-separated-values"}));
        runSubscribe.setOnClickListener(b -> preflightSubscribe());
        target.setOnClickListener(b -> askStartChapter());

        // 签到和订阅共用同一个队列，跑着的时候不让再点。
        AutomationBus.running().observe(getViewLifecycleOwner(), running ->
                runSubscribe.setEnabled(!Boolean.TRUE.equals(running)));

        dao.observeNovels().observe(getViewLifecycleOwner(), this::onNovels);
        accountDao.observeAll().observe(getViewLifecycleOwner(), list2 -> {
            accounts = list2 == null ? new ArrayList<>() : list2;
            render();
        });
        dao.observeAccountStats().observe(getViewLifecycleOwner(), this::renderStats);
    }

    // ---------- 数据绑定 ----------

    private void onNovels(List<Novel> list) {
        novels = list == null ? new ArrayList<>() : list;
        Novel newTarget = null;
        for (Novel n : novels) {
            if (n.isTarget) {
                newTarget = n;
                break;
            }
        }
        // 一本书都没标目标时，就先显示第一本，免得页面空着没法操作。
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
        render();
    }

    private void detachNovelObservers() {
        if (chaptersLd != null) chaptersLd.removeObservers(getViewLifecycleOwner());
        if (purchasesLd != null) purchasesLd.removeObservers(getViewLifecycleOwner());
        chaptersLd = null;
        purchasesLd = null;
    }

    private void attachNovelObservers(long novelId) {
        detachNovelObservers();
        chaptersLd = dao.observeChapters(novelId);
        chaptersLd.observe(getViewLifecycleOwner(), list -> {
            chapters = list == null ? new ArrayList<>() : list;
            render();
        });
        purchasesLd = dao.observePurchasesOfNovel(novelId);
        purchasesLd.observe(getViewLifecycleOwner(), list -> {
            purchases = list == null ? new ArrayList<>() : list;
            render();
        });
    }

    private void render() {
        if (current == null) {
            target.setText(R.string.sub_no_target);
        } else {
            StringBuilder sb = new StringBuilder(current.title);
            if (!Texts.isBlank(current.author)) sb.append('（').append(current.author).append('）');
            if (!current.isTarget) sb.append("　※还没设为目标");
            sb.append("\n从第").append(current.startFrom()).append("章开始订阅（点这里改）");
            target.setText(sb);
        }
        // 空态要分两种：一本小说都没有，和有小说但这本还没登记章节。
        // 原来只管前一种，于是「已登记的章节与订阅情况」下面会是一片什么都不说的空白。
        if (novels.isEmpty()) {
            empty.setText(R.string.sub_empty);
            empty.setVisibility(View.VISIBLE);
        } else if (chapters.isEmpty()) {
            empty.setText(R.string.sub_no_chapters);
            empty.setVisibility(View.VISIBLE);
        } else {
            empty.setVisibility(View.GONE);
        }
        adapter.submit(chapters);
        refreshSuggestion();
    }

    /** 起始章：自动订阅只碰它及之后的章，前面的旧章不会被顺手买掉。 */
    private void askStartChapter() {
        if (current == null) {
            toast(getString(R.string.sub_no_target));
            return;
        }
        Novel novel = current;
        View form = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_single_input, null, false);
        TextView label = form.findViewById(R.id.label);
        EditText input = form.findViewById(R.id.input);
        label.setText("从第几章开始订阅？（之前的章不会自动买）");
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(novel.startFrom()));

        new AlertDialog.Builder(requireContext())
                .setTitle("《" + novel.title + "》的起始章")
                .setView(form)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> {
                    int no = Math.max(1, Texts.parseCount(input.getText().toString()));
                    novel.startChapterNo = no;
                    Db.io(() -> dao.setStartChapter(novel.id, no));
                    render();
                })
                .show();
    }

    private void renderStats(List<AccountStat> list) {
        if (list == null || list.isEmpty()) {
            stats.setText("");
            return;
        }
        List<String> parts = new ArrayList<>();
        for (AccountStat s : list) {
            // 花费和余额都以代券为主：新流程只买「实付 0 火券」的章，火券只会是历史遗留。
            parts.add(s.displayName() + " " + s.chapterCount + " 章／" + s.totalVouchers
                    + " 代券" + (s.totalCost > 0 ? "+" + s.totalCost + " 火券" : "")
                    + "（余代券 " + (s.vouchers >= 0 ? String.valueOf(s.vouchers) : "?") + "）");
        }
        stats.setText("累计：" + TextUtils.join("　", parts));
    }

    /** 「下一章该用哪个号买」。走 DAO 而不是在内存里另算一遍，保证跟自动订阅的判断一致。 */
    private void refreshSuggestion() {
        if (current == null) {
            suggestion.setText(R.string.sub_no_target);
            return;
        }
        long novelId = current.id;
        int from = current.startFrom();
        Db.io(() -> {
            Chapter next = dao.findNextUnownedChapterFrom(novelId, from);
            Account buyer = next == null ? null : dao.suggestBuyer(next.id);
            String text;
            if (next == null) {
                text = getStringSafe(R.string.sub_suggestion_none) + "（第" + from + "章起）";
            } else if (buyer == null) {
                text = "下一章：第" + next.chapterNo + "章 —— 但没有可用账号了"
                        + "（都买过了，或都被停用了）";
            } else {
                text = "下一章该买：第" + next.chapterNo + "章"
                        + (Texts.isBlank(next.title) ? "" : " " + next.title)
                        + "\n建议用：" + buyer.displayName()
                        + "（" + buyer.balanceText() + "）";
            }
            post(() -> suggestion.setText(text));
        });
    }

    // ---------- 章节行 ----------

    private void bindChapter(View row, Chapter c, int position) {
        StringBuilder line1 = new StringBuilder("第").append(c.chapterNo).append('章');
        if (!Texts.isBlank(c.title)) line1.append(' ').append(c.title);
        if (c.priceCoupons > 0) line1.append("　").append(c.priceCoupons).append(" 券");
        row.<TextView>findViewById(R.id.line1).setText(line1);

        List<String> owners = new ArrayList<>();
        for (Purchase p : purchases) {
            if (p.chapterId != c.id) continue;
            owners.add(accountName(p.accountId));
        }
        StringBuilder line2 = new StringBuilder();
        line2.append(owners.isEmpty() ? "还没人订阅" : "已订阅：" + TextUtils.join("、", owners));
        row.<TextView>findViewById(R.id.line2).setText(line2);

        // 色条＝这一章有没有归属：绿＝有号拥有它，灰＝还没有。
        StatusPalette tone = owners.isEmpty() ? StatusPalette.SKIP : StatusPalette.OK;
        row.findViewById(R.id.accent).setBackgroundColor(
                ContextCompat.getColor(row.getContext(), tone.foreground));
        row.findViewById(R.id.badge).setVisibility(View.GONE);
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

    /** 点一章：列出所有账号，选谁就补录／撤销谁的订阅。 */
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
                    : Purchase.SRC_OWNED.equals(owned.source) ? "界面显示已拥有"
                    : "已订阅 " + describeCost(owned);
            labels[i] = a.displayName() + " — " + mark;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("第" + chapter.chapterNo + "章")
                .setItems(labels, (d, which) -> {
                    Account a = accounts.get(which);
                    Purchase owned = purchaseOf(a.id, chapter.id);
                    if (owned == null) {
                        askCostThenRecord(a, chapter);
                    } else {
                        confirmDeletePurchase(a, owned);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private Purchase purchaseOf(long accountId, long chapterId) {
        for (Purchase p : purchases) {
            if (p.accountId == accountId && p.chapterId == chapterId) return p;
        }
        return null;
    }

    private void askCostThenRecord(Account account, Chapter chapter) {
        View form = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_single_input, null, false);
        TextView label = form.findViewById(R.id.label);
        EditText input = form.findViewById(R.id.input);
        label.setText(getString(R.string.sub_cost_prompt));
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(chapter.priceCoupons > 0 ? String.valueOf(chapter.priceCoupons) : "");

        new AlertDialog.Builder(requireContext())
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
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.sub_unmark)
                .setMessage(account.displayName() + " 这一章的记录会被删掉。"
                        + "这只改本地账本，不会退代券。")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) ->
                        Db.io(() -> dao.deletePurchase(purchase)))
                .show();
    }

    private void confirmDeleteChapter(Chapter chapter) {
        new AlertDialog.Builder(requireContext())
                .setTitle("删除第" + chapter.chapterNo + "章？")
                .setMessage("这一章下面所有账号的订阅记录会一起删掉。")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) ->
                        Db.io(() -> dao.deleteChapter(chapter)))
                .show();
    }

    // ---------- 小说与章节的增删 ----------

    private void pickNovel() {
        if (novels.isEmpty()) {
            addNovel();
            return;
        }
        String[] labels = new String[novels.size()];
        for (int i = 0; i < novels.size(); i++) {
            Novel n = novels.get(i);
            labels[i] = (n.isTarget ? "★ " : "") + n.title;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.sub_pick_novel)
                .setItems(labels, (d, which) -> {
                    long id = novels.get(which).id;
                    Db.io(() -> dao.setTargetNovel(id));
                })
                .setNeutralButton(R.string.delete, (d, w) -> pickNovelToDelete())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void pickNovelToDelete() {
        String[] labels = new String[novels.size()];
        for (int i = 0; i < novels.size(); i++) labels[i] = novels.get(i).title;
        new AlertDialog.Builder(requireContext())
                .setTitle("删除哪本？（连章节和订阅记录一起删）")
                .setItems(labels, (d, which) -> {
                    Novel n = novels.get(which);
                    Db.io(() -> dao.deleteNovel(n));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void addNovel() {
        View form = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_novel_edit, null, false);
        EditText title = form.findViewById(R.id.title);
        EditText author = form.findViewById(R.id.author);
        EditText sfId = form.findViewById(R.id.sf_id);

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.sub_add_novel)
                .setView(form)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String t = title.getText().toString().trim();
                    if (Texts.isBlank(t)) {
                        toast("书名不能为空");
                        return;
                    }
                    Novel n = new Novel();
                    n.title = t;
                    n.author = emptyToNull(author.getText().toString().trim());
                    n.sfNovelId = emptyToNull(sfId.getText().toString().trim());
                    Db.io(() -> {
                        try {
                            long id = dao.insertNovel(n);
                            // 第一本书自动设为集中订阅目标，省一次操作。
                            if (novels.isEmpty() && id > 0) dao.setTargetNovel(id);
                        } catch (Exception e) {
                            post(() -> toast("添加失败（书号可能重复）：" + e.getMessage()));
                        }
                    });
                })
                .show();
    }

    private void addChapters() {
        if (current == null) {
            toast("先添加或选择一本小说");
            return;
        }
        View form = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_chapter_add, null, false);
        EditText from = form.findViewById(R.id.chapter_no);
        EditText to = form.findViewById(R.id.chapter_no_end);
        EditText title = form.findViewById(R.id.title);
        EditText price = form.findViewById(R.id.price);

        long novelId = current.id;
        Db.io(() -> {
            int next = dao.maxChapterNo(novelId) + 1;
            post(() -> from.setText(String.valueOf(next)));
        });

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.sub_add_chapter)
                .setView(form)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> {
                    int start = Texts.parseCount(from.getText().toString());
                    if (start < 0) {
                        toast("章节号得填个数字");
                        return;
                    }
                    int end = Texts.parseCount(to.getText().toString());
                    if (end < start) end = start;
                    int p = Math.max(0, Texts.parseCount(price.getText().toString()));
                    String t = emptyToNull(title.getText().toString().trim());
                    // 批量登记时统一标题没意义，只给单章用。
                    String chapterTitle = end == start ? t : null;
                    final int s = start;
                    final int e = Math.min(end, start + 499);
                    Db.io(() -> {
                        for (int no = s; no <= e; no++) {
                            dao.ensureChapter(novelId, no, no == s ? chapterTitle : null, p);
                        }
                    });
                })
                .show();
    }

    // ---------- 自动订阅 ----------

    /**
     * 起队列之前把能提前发现的问题都摊开：无障碍、选择器、目标小说、待订阅章节、模式。
     * 队列里有同样一套护栏 —— 这里只是让你在点下去之前就看见，而不是跑起来才失败。
     */
    private void preflightSubscribe() {
        Context ctx = requireContext();
        if (!BlbAccessibilityService.isReady()) {
            new AlertDialog.Builder(ctx)
                    .setMessage(R.string.checkin_need_a11y)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.settings_open_accessibility, (d, w) ->
                            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                    .show();
            return;
        }
        SelectorSet selectors = SelectorSet.load(ctx);
        List<String> missing = selectors.missing(Keys.REQUIRED_FOR_SUBSCRIBE);
        missing.addAll(selectors.missing(Keys.REQUIRED_FOR_SWITCH));
        if (!missing.isEmpty()) {
            new AlertDialog.Builder(ctx)
                    .setMessage("selectors.json 还缺订阅必需的 key：" + TextUtils.join("、", missing)
                            + "\n\n先用设置页的节点探测器抓一次菠萝包的目录页和章节页，把这些值填好。")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        // 队列只认 is_target = 1 的那本；这一页「没有目标就先显示第一本」的兜底不算。
        if (current == null || !current.isTarget) {
            new AlertDialog.Builder(ctx)
                    .setMessage(R.string.sub_need_target)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        Novel novel = current;
        int cap = Prefs.dailySpendCap(ctx);
        Db.io(() -> {
            // 整条队列一次取完，不设条数上限：停下来的理由只能是「这个号的代券不够下一章」。
            List<Chapter> pending = dao.findUnownedChaptersFrom(novel.id, novel.startFrom());
            post(() -> confirmSubscribe(novel, pending, cap));
        });
    }

    /** 把这一轮到底会动哪几章、花多少券摊在眼前，再问一次。 */
    private void confirmSubscribe(Novel novel, List<Chapter> pending, int cap) {
        Context ctx = getContext();
        if (ctx == null) return;
        if (pending.isEmpty()) {
            new AlertDialog.Builder(ctx)
                    .setMessage(R.string.sub_nothing_to_do)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        // 队列可能有几百章，标题栏里只摊前几章＋总数，全部列出来会把对话框撑爆。
        List<String> nos = new ArrayList<>();
        for (Chapter c : pending.subList(0, Math.min(8, pending.size()))) {
            nos.add("第" + c.chapterNo + "章");
        }
        StringBuilder sb = new StringBuilder("《").append(novel.title).append("》\n")
                .append("还没有任何号买过的章共 ").append(pending.size()).append(" 章，")
                .append("从第").append(pending.get(0).chapterNo).append("章起按顺序往下订：")
                .append(TextUtils.join("、", nos))
                .append(pending.size() > nos.size() ? "…" : "");
        sb.append("\n\n不限章数：一个号订到代券不够下一章就换下一个号，所有号都不够才收工。");
        if (cap > 0) sb.append("\n每号每日花费上限 ").append(cap).append(" 代券。");
        sb.append("\n\n").append(getString(R.string.sub_real_buy_note));
        sb.append("\n\n跑的时候别动屏幕；撞到安全验证会停下来等你手动过。");

        new AlertDialog.Builder(ctx)
                .setTitle(R.string.sub_run_title)
                .setMessage(sb)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton("确认，开始订阅", (d, w) -> {
                    AutomationService.startSubscribe(ctx);
                    toast("订阅队列已启动，去签到页看实时日志");
                })
                .show();
    }

    // ---------- CSV ----------

    /**
     * 新列 {@code cost_vouchers} 加在<b>末尾</b>而不是 cost_coupons 旁边：这样你以前导出的
     * CSV 还能照原样导回来（前 7 列的位置一个都没动），缺这一列就按 0 算。
     */
    private static final String CSV_HEADER =
            "novel_title,chapter_no,chapter_title,account_login,cost_coupons,source,purchased_at,"
                    + "cost_vouchers";

    private void writeCsv(@Nullable Uri uri) {
        if (uri == null) return;
        Db.io(() -> {
            int count = 0;
            try (OutputStream out = requireContext().getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("openOutputStream 返回 null");
                out.write((CSV_HEADER + "\n").getBytes(StandardCharsets.UTF_8));
                List<PurchaseRow> rows = dao.loadAllRows();
                for (PurchaseRow r : rows) {
                    out.write(Csv.row(
                            r.novelTitle,
                            String.valueOf(r.chapterNo),
                            r.chapterTitle,
                            r.accountLoginName,
                            String.valueOf(r.costCoupons),
                            r.source,
                            Texts.ymd(r.purchasedAt),
                            String.valueOf(r.costVouchers)).getBytes(StandardCharsets.UTF_8));
                    count++;
                }
            } catch (Exception e) {
                post(() -> toast("导出失败：" + e.getMessage()));
                return;
            }
            int n = count;
            post(() -> toast("导出了 " + n + " 条订阅记录"));
        });
    }

    private void readCsv(@Nullable Uri uri) {
        if (uri == null) return;
        Db.io(() -> {
            int added = 0;
            int skipped = 0;
            try (InputStream in = requireContext().getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IllegalStateException("openInputStream 返回 null");
                List<List<String>> rows = Csv.parse(readAll(in));
                for (int i = 0; i < rows.size(); i++) {
                    List<String> row = rows.get(i);
                    if (i == 0 && "novel_title".equalsIgnoreCase(Csv.at(row, 0))) continue;

                    String novelTitle = Csv.at(row, 0);
                    int chapterNo = Csv.intAt(row, 1);
                    String login = Csv.at(row, 3);
                    if (Texts.isBlank(novelTitle) || chapterNo < 0 || Texts.isBlank(login)) {
                        skipped++;
                        continue;
                    }
                    // 账号必须已经存在：凭据不在 CSV 里，凭空造个没密码的号只会让切号失败。
                    Account account = accountDao.byLoginName(login);
                    if (account == null) {
                        skipped++;
                        continue;
                    }
                    Novel novel = dao.novelByTitle(novelTitle);
                    if (novel == null) {
                        Novel fresh = new Novel();
                        fresh.title = novelTitle;
                        long id = dao.insertNovel(fresh);
                        novel = dao.novelById(id);
                    }
                    Chapter chapter = novel == null ? null : dao.ensureChapter(
                            novel.id, chapterNo, emptyToNull(Csv.at(row, 2)), 0);
                    if (chapter == null) {
                        skipped++;
                        continue;
                    }
                    String source = Csv.at(row, 5);
                    if (Texts.isBlank(source)) source = Purchase.SRC_MANUAL;
                    dao.upsertPurchase(Purchase.of(account.id, chapter.id,
                            Math.max(0, Csv.intAt(row, 4)),
                            Math.max(0, Csv.intAt(row, 7)), // 旧 CSV 没这一列，按 0 算
                            source));
                    added++;
                }
            } catch (Exception e) {
                post(() -> toast("导入失败：" + e.getMessage()));
                return;
            }
            int a = added;
            int s = skipped;
            post(() -> toast("导入 " + a + " 条，跳过 " + s + " 条（跳过的多半是本机没有这个账号）"));
        });
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    // ---------- 杂项 ----------

    private static String emptyToNull(String s) {
        return Texts.isBlank(s) ? null : s;
    }

    /** 后台线程回主线程用；界面已销毁时静默丢弃。 */
    private void post(Runnable action) {
        View v = getView();
        if (v != null) v.post(action);
    }

    private String getStringSafe(int resId) {
        android.content.Context ctx = getContext();
        return ctx == null ? "" : ctx.getString(resId);
    }

    private void toast(String message) {
        android.content.Context ctx = getContext();
        if (ctx != null) Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show();
    }
}
