package com.example.blb.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
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
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;

import com.example.blb.R;
import com.example.blb.auto.AutomationBus;
import com.example.blb.auto.AutomationService;
import com.example.blb.auto.AccessibilityAccess;
import com.example.blb.auto.BlbAccessibilityService;
import com.example.blb.auto.CatalogStatus;
import com.example.blb.auto.Keys;
import com.example.blb.auto.SelectorSet;
import com.example.blb.data.Account;
import com.example.blb.data.AccountDao;
import com.example.blb.data.AccountStat;
import com.example.blb.data.Chapter;
import com.example.blb.data.Db;
import com.example.blb.data.LedgerAudit;
import com.example.blb.data.LedgerAuditPayload;
import com.example.blb.data.Novel;
import com.example.blb.data.Purchase;
import com.example.blb.data.PurchaseRow;
import com.example.blb.data.SubscriptionDao;
import com.example.blb.util.Csv;
import com.example.blb.util.Prefs;
import com.example.blb.util.PurchaseCsv;
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
 *
 * <p>逐章明细和 8 个号的累计原来都挤在这一页上（六百多章塞在下半屏的内嵌滚动区、累计被拼成
 * 一条 13sp 小字横向截断）。现在各是一行 {@link EntryRowView}，完整内容在
 * {@link DetailActivity} 里占一整屏；这一页只留一句摘要，好让不能动手的人不点也看得到。
 */
public class SubscriptionFragment extends Fragment {

    private SubscriptionDao dao;
    private AccountDao accountDao;

    private TextView target;
    private TextView suggestion;
    private TextView catalogSummary;
    private TextView auditSummary;
    private EntryRowView entryChapters;
    private EntryRowView entryStats;
    private EntryRowView entrySuspect;
    private Button runSubscribe;
    private Button runCatalog;
    private Button runAudit;
    private boolean readOnlyPreflight;
    private boolean subscribePreflight;

    private List<Novel> novels = new ArrayList<>();
    private List<Chapter> chapters = new ArrayList<>();
    private List<Purchase> purchases = new ArrayList<>();
    private List<LedgerAudit> ledgerAudits = new ArrayList<>();
    private Novel current;

    private LiveData<List<Chapter>> chaptersLd;
    private LiveData<List<Purchase>> purchasesLd;
    private LiveData<List<LedgerAudit>> ledgerAuditsLd;

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
        catalogSummary = v.findViewById(R.id.catalog_summary);
        auditSummary = v.findViewById(R.id.audit_summary);
        runSubscribe = v.findViewById(R.id.run_subscribe);
        runCatalog = v.findViewById(R.id.run_catalog);
        runAudit = v.findViewById(R.id.run_audit);
        readOnlyPreflight = false;
        subscribePreflight = false;
        renderRunCosts(null);

        entryChapters = v.findViewById(R.id.entry_chapters);
        entryChapters.setTitle(getString(R.string.sub_records_header));
        entryChapters.setOnClickListener(b -> DetailActivity.open(
                requireContext(), DetailActivity.PAGE_CHAPTERS));
        entryStats = v.findViewById(R.id.entry_stats);
        entryStats.setTitle(getString(R.string.detail_stats_title));
        entryStats.setSummary(getString(R.string.detail_stats_summary_empty));
        entryStats.setOnClickListener(b -> DetailActivity.open(
                requireContext(), DetailActivity.PAGE_STATS));
        entrySuspect = v.findViewById(R.id.entry_suspect);
        entrySuspect.setTitle(getString(R.string.detail_suspect_title));
        entrySuspect.setSummary(getString(R.string.detail_suspect_empty));
        entrySuspect.setOnClickListener(b -> DetailActivity.open(
                requireContext(), DetailActivity.PAGE_SUSPECT));

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
        runCatalog.setOnClickListener(b -> preflightCatalog());
        runAudit.setOnClickListener(b -> preflightAudit());
        target.setOnClickListener(b -> askStartChapter());

        // 核对要逐号登录；预检读库期间也不能让另一个按钮排入第二次屏幕操作。
        AutomationBus.busy().observe(getViewLifecycleOwner(), busy -> {
            updateRunButtons();
            if (!Boolean.TRUE.equals(busy)) refreshActionSummaries();
        });

        dao.observeNovels().observe(getViewLifecycleOwner(), this::onNovels);
        dao.observeAccountStats().observe(getViewLifecycleOwner(), this::renderStats);
        accountDao.observeAll().observe(getViewLifecycleOwner(), accounts -> {
            renderRunCosts(accounts);
            refreshSuggestion();
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshActionSummaries();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) refreshActionSummaries();
    }

    private void refreshActionSummaries() {
        if (getView() == null || catalogSummary == null) return;
        catalogSummary.setText(CatalogStatus.describe(current, System.currentTimeMillis()));
        refreshAuditSummary();
        refreshSuggestion();
    }

    private void updateRunButtons() {
        boolean enabled = !AutomationBus.isBusy() && !readOnlyPreflight && !subscribePreflight;
        runSubscribe.setEnabled(enabled);
        runCatalog.setEnabled(enabled);
        runAudit.setEnabled(enabled);
        View v = getView();
        if (v == null) return;
        // 队列和预检都依赖眼前这本书；运行中弹出编辑或文件选择器会挡住正在读的屏幕。
        for (int id : new int[]{R.id.target, R.id.pick_novel, R.id.add_novel,
                R.id.add_chapter, R.id.export_csv, R.id.import_csv}) {
            v.findViewById(id).setEnabled(enabled);
        }
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
            ledgerAudits = new ArrayList<>();
        } else if (current.id != oldId) {
            attachNovelObservers(current.id);
        }
        render();
    }

    private void detachNovelObservers() {
        if (chaptersLd != null) chaptersLd.removeObservers(getViewLifecycleOwner());
        if (purchasesLd != null) purchasesLd.removeObservers(getViewLifecycleOwner());
        if (ledgerAuditsLd != null) ledgerAuditsLd.removeObservers(getViewLifecycleOwner());
        chaptersLd = null;
        purchasesLd = null;
        ledgerAuditsLd = null;
    }

    private void attachNovelObservers(long novelId) {
        detachNovelObservers();
        chapters = new ArrayList<>();
        purchases = new ArrayList<>();
        ledgerAudits = new ArrayList<>();
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
        ledgerAuditsLd = Db.get(requireContext()).auditDao()
                .observeRecentLedgerAudits(novelId, 100);
        ledgerAuditsLd.observe(getViewLifecycleOwner(), list -> {
            ledgerAudits = list == null ? new ArrayList<>() : list;
            renderAuditEntry();
        });
    }

    private void render() {
        catalogSummary.setText(CatalogStatus.describe(current, System.currentTimeMillis()));
        refreshAuditSummary();
        renderAuditEntry();
        if (current == null) {
            target.setText(R.string.sub_no_target);
        } else {
            StringBuilder sb = new StringBuilder(current.title);
            if (!Texts.isBlank(current.author)) sb.append('（').append(current.author).append('）');
            if (!current.isTarget) sb.append("　※还没设为目标");
            sb.append("\n从第").append(current.startFrom()).append("章开始订阅（点这里改）");
            target.setText(sb);
        }
        // 摘要要分三种：一本小说都没有、有小说但这本还没登记章节、有章节。
        // 原来这里只管前一种，于是列表下面会是一片什么都不说的空白。
        if (novels.isEmpty()) {
            entryChapters.setSummary(getString(R.string.sub_empty));
        } else if (chapters.isEmpty()) {
            entryChapters.setSummary(getString(R.string.sub_no_chapters));
        } else {
            entryChapters.setSummary(ChapterLedgerText.summary(chapters, purchases));
        }
        refreshSuggestion();
    }

    private void renderRunCosts(List<Account> accounts) {
        String auditText;
        if (accounts == null) {
            auditText = getString(R.string.sub_audit_accounts_loading);
        } else {
            int enabled = 0;
            for (Account account : accounts) if (account.enabled) enabled++;
            auditText = enabled == 0 ? getString(R.string.sub_audit_no_accounts)
                    : getString(R.string.sub_audit_cost, enabled);
        }
        runAudit.setText(getString(R.string.sub_audit) + "\n" + auditText);
    }

    private void refreshAuditSummary() {
        Context context = getContext();
        if (auditSummary == null || context == null) return;
        String saved = current == null ? null : Prefs.lastAuditSummary(context, current.id);
        auditSummary.setText(Texts.isBlank(saved) ? getString(R.string.sub_audit_unchecked) : saved);
    }

    /** 存疑不能只藏在二级页；人动不了手，最近一次保留账本的依据必须在入口就能读到。 */
    private void renderAuditEntry() {
        if (ledgerAudits.isEmpty()) {
            entrySuspect.setSummary(getString(R.string.detail_suspect_empty));
            return;
        }
        int suspects = 0;
        int deletes = 0;
        for (LedgerAudit audit : ledgerAudits) {
            if (LedgerAudit.KIND_SUSPECT.equals(audit.kind)) suspects++;
            if (LedgerAudit.KIND_DELETE.equals(audit.kind)) deletes++;
        }
        LedgerAudit latest = ledgerAudits.get(0);
        String when = latest.at > 0 ? DateFormat.format("MM-dd HH:mm", latest.at).toString()
                : "时间未记录";
        String evidence = LedgerAuditPayload.message(latest.detail);
        entrySuspect.setSummary("最近 " + ledgerAudits.size() + " 条：存疑 " + suspects
                + " · 修正 " + deletes + "\n最近 " + when + "："
                + (Texts.isBlank(evidence) ? "依据未记录" : evidence));
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
                    int no = Texts.parseCount(input.getText().toString());
                    if (no < 1) {
                        toast("起始章必须填清楚，至少是第 1 章");
                        return;
                    }
                    Context app = requireContext().getApplicationContext();
                    LedgerEdits.submit(app, () -> {
                        dao.setStartChapter(novel.id, no);
                        Db.get(app).auditDao().invalidateNovel(novel.id);
                    });
                })
                .show();
    }

    /** 8 个号的累计压成一句总账；一行一个号的完整那份在 {@link DetailActivity} 里。 */
    private void renderStats(List<AccountStat> list) {
        String summary = AccountStatText.summary(list);
        entryStats.setSummary(Texts.isBlank(summary)
                ? getString(R.string.detail_stats_summary_empty) : summary);
    }

    /** 「下一章该用哪个号买」。走 DAO 而不是在内存里另算一遍，保证跟自动订阅的判断一致。 */
    private void refreshSuggestion() {
        View sourceView = getView();
        Context ctx = getContext();
        if (sourceView == null || ctx == null) return;
        if (current == null) {
            suggestion.setText(R.string.sub_no_target);
            return;
        }
        long novelId = current.id;
        int from = current.startFrom();
        SubscriptionDao subscriptions = dao;
        Db.io(() -> {
            String text;
            try {
                // 页面观察者还没回调不等于目录为空，下一章摘要也必须凭这次读库的结果。
                List<Chapter> localChapters = subscriptions.loadChapters(novelId);
                if (localChapters == null) {
                    text = "下一章：目录读不到，暂时无法确认";
                } else if (localChapters.isEmpty()) {
                    text = ctx.getString(R.string.sub_no_chapters);
                } else {
                    Chapter next = subscriptions.findNextUnownedChapterFrom(novelId, from);
                    Account buyer = next == null ? null : subscriptions.suggestBuyer(next.id);
                    if (next == null) {
                        text = ctx.getString(R.string.sub_suggestion_none, from);
                    } else {
                        text = "下一章：第 " + next.chapterNo + " 章"
                                + (Texts.isBlank(next.title) ? "" : "「" + next.title + "」");
                        text += buyer == null ? "\n没有可用账号，请在账号页检查启用状态"
                                : "\n建议用：" + buyer.displayName() + "（" + buyer.balanceText() + "）";
                    }
                }
            } catch (RuntimeException failure) {
                text = "下一章：账本读取失败，暂时无法确认";
                AutomationBus.append(text + "：" + failure.getMessage());
            }
            String latest = text;
            sourceView.post(() -> {
                // 切书或重建页面后，旧查询不能把上一本书的下一章贴到新按钮下面。
                if (getView() != sourceView || current == null
                        || current.id != novelId || current.startFrom() != from) return;
                suggestion.setText(latest);
            });
        });
    }

    // 章节行的绑定、补录与核对入口搬去了 DetailActivity：
    // 那一页才有完整列表，留在这里只会是第二份实现，两边迟早对不上。

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
                    Context app = requireContext().getApplicationContext();
                    LedgerEdits.submit(app, () -> {
                        dao.setTargetNovel(id);
                        Db.get(app).auditDao().invalidateAll();
                    });
                })
                .setNeutralButton(R.string.delete, (d, w) -> pickNovelToDelete())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void pickNovelToDelete() {
        String[] labels = new String[novels.size()];
        for (int i = 0; i < novels.size(); i++) labels[i] = novels.get(i).title;
        new AlertDialog.Builder(requireContext())
                .setTitle("仅可删除没有订阅记录的小说")
                .setItems(labels, (d, which) -> {
                    Novel n = novels.get(which);
                    Context app = requireContext().getApplicationContext();
                    LedgerEdits.submit(app, () -> {
                        dao.deleteNovel(n);
                        Db.get(app).auditDao().invalidateNovel(n.id);
                    });
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
                    String a = emptyToNull(author.getText().toString().trim());
                    String bookId = emptyToNull(sfId.getText().toString().trim());
                    Context app = requireContext().getApplicationContext();
                    LedgerEdits.submit(app, () -> {
                        boolean firstNovel = dao.loadNovels().isEmpty();
                        Novel n = new Novel();
                        n.title = t;
                        n.author = a;
                        n.sfNovelId = bookId;
                        long id = dao.insertNovel(n);
                        // 第一本书自动设为集中订阅目标，省一次操作。
                        if (firstNovel && id > 0) dao.setTargetNovel(id);
                        Db.get(app).auditDao().invalidateNovel(id);
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
                    if (start < 1) {
                        toast("章节号至少是第 1 章，请填清楚");
                        return;
                    }
                    int end = Texts.parseCount(to.getText().toString());
                    if (end < start) end = start;
                    int p = Texts.parseCount(price.getText().toString());
                    String t = emptyToNull(title.getText().toString().trim());
                    // 批量登记时统一标题没意义，只给单章用。
                    String chapterTitle = end == start ? t : null;
                    final int s = start;
                    final int e = Math.min(end, start + 499);
                    Context app = requireContext().getApplicationContext();
                    LedgerEdits.submit(app, () -> {
                        for (int no = s; no <= e; no++) {
                            dao.ensureChapter(novelId, no, no == s ? chapterTitle : null, p);
                        }
                        Db.get(app).auditDao().invalidateNovel(novelId);
                    });
                })
                .show();
    }

    // ---------- 运行入口 ----------

    /** 唯一一本书可以认出，但补回目标标志必须等队列拿到独占权，预检只读不写。 */
    private void preflightCatalog() {
        preflightReadOnly(false);
    }

    private void preflightAudit() {
        preflightReadOnly(true);
    }

    private void preflightReadOnly(boolean audit) {
        View sourceView = getView();
        if (sourceView == null || readOnlyPreflight || subscribePreflight) return;
        String operation = audit ? "核对订阅清单" : "同步目录";
        if (AutomationBus.isBusy()) {
            toast("有任务正在运行或账本正在更新，请等结束后再" + operation);
            return;
        }
        readOnlyPreflight = true;
        updateRunButtons();
        SubscriptionDao subscriptions = dao;
        Db.io(() -> {
            String problem = null;
            try {
                Novel novel = subscriptions.targetNovel();
                if (novel == null) {
                    List<Novel> all = subscriptions.loadNovels();
                    if (all.size() == 1) novel = all.get(0);
                }
                if (novel == null) problem = "先在订阅页选一本目标小说";
            } catch (RuntimeException failure) {
                problem = "读取目标小说失败：" + failure.getMessage();
            }
            String error = problem;
            sourceView.post(() -> {
                // 离开后又重建的页面不是这次点击的发起者，旧预检不能突然扫描或逐号登录。
                if (getView() != sourceView) return;
                readOnlyPreflight = false;
                updateRunButtons();
                Context ctx = getContext();
                if (ctx == null) return;
                if (AutomationBus.isBusy()) {
                    toast("有任务正在运行或账本正在更新，请等结束后再" + operation);
                    return;
                }
                if (error != null) {
                    new AlertDialog.Builder(ctx)
                            .setMessage(error)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    return;
                }
                if (!ensureAccessibilityReady(ctx)) return;
                // 缺清单选择器时各账号要留下「未核对」；在页面提前拦住会让整趟没有任何结论。
                if (audit) {
                    AutomationService.startAudit(ctx);
                    toast("核对订阅清单已启动，将逐个登录启用账号");
                    return;
                }
                SelectorSet catalogSelectors = SelectorSet.load(ctx);
                List<String> missing = catalogSelectors.missing(Keys.REQUIRED_FOR_CATALOG);
                if (!missing.isEmpty()) {
                    new AlertDialog.Builder(ctx)
                            .setMessage(catalogSelectors.missingMessage(Keys.REQUIRED_FOR_CATALOG))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    return;
                }
                AutomationService.startCatalog(ctx);
                toast("目录同步已启动，使用当前登录账号扫描");
            });
        });
    }

    /** 托管设备可能只被系统清了开关，两种入口都应先尝试已有授权恢复，不能平白要求手动操作。 */
    private boolean ensureAccessibilityReady(Context ctx) {
        if (AccessibilityAccess.state(ctx) != AccessibilityAccess.State.DISABLED) return true;
        AccessibilityAccess.RestoreResult restore = AccessibilityAccess.restoreIfAuthorized(ctx);
        if (restore != AccessibilityAccess.RestoreResult.NOT_AUTHORIZED
                && restore != AccessibilityAccess.RestoreResult.FAILED) return true;
        new AlertDialog.Builder(ctx)
                .setMessage(R.string.checkin_need_a11y)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.settings_open_accessibility, (d, w) ->
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .show();
        return false;
    }

    /**
     * 起队列之前把能提前发现的问题都摊开：无障碍、选择器、目标小说、待订阅章节、模式。
     * 队列里有同样一套护栏 —— 这里只是让你在点下去之前就看见，而不是跑起来才失败。
     */
    private void preflightSubscribe() {
        View sourceView = getView();
        if (sourceView == null || readOnlyPreflight || subscribePreflight) return;
        Context ctx = requireContext();
        if (AutomationBus.isBusy()) {
            toast("有任务正在运行或账本正在更新，请等结束后再开始订阅");
            return;
        }
        if (!ensureAccessibilityReady(ctx)) return;
        SelectorSet selectors = SelectorSet.load(ctx);
        List<String> missing = selectors.missing(Keys.REQUIRED_FOR_SUBSCRIBE);
        missing.addAll(selectors.missing(Keys.REQUIRED_FOR_SWITCH));
        if (!missing.isEmpty()) {
            new AlertDialog.Builder(ctx)
                    .setMessage("订阅必需选择器未配置或无效：" + TextUtils.join("、", missing)
                            + "\n\n" + selectors.diagnostics() + "\n" + selectors.recoveryHint())
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
        subscribePreflight = true;
        updateRunButtons();
        SubscriptionDao subscriptions = dao;
        Db.io(() -> {
            List<Chapter> pending = new ArrayList<>();
            String problem = null;
            try {
                Novel selected = subscriptions.targetNovel();
                if (selected == null || selected.id != novel.id) {
                    problem = "目标小说已变化，请重新开始订阅";
                } else {
                    // 整条队列一次取完，不设条数上限：余额不足才由购买环节停下。
                    pending = subscriptions.findUnownedChaptersFrom(novel.id, novel.startFrom());
                }
            } catch (RuntimeException failure) {
                problem = "读取订阅账本失败：" + failure.getMessage();
            }
            List<Chapter> candidates = pending;
            String error = problem;
            sourceView.post(() -> {
                if (getView() != sourceView) return;
                subscribePreflight = false;
                updateRunButtons();
                if (AutomationBus.isBusy()) {
                    toast("有任务正在运行或账本正在更新，请等结束后再开始订阅");
                    return;
                }
                if (error != null) {
                    toast(error);
                    return;
                }
                confirmSubscribe(novel, candidates, cap);
            });
        });
    }

    /** 把这一轮到底会动哪几章、花多少券摊在眼前，再问一次。 */
    private void confirmSubscribe(Novel novel, List<Chapter> pending, int cap) {
        Context ctx = getContext();
        if (ctx == null) return;
        // 队列可能有几百章，标题栏里只摊前几章＋总数，全部列出来会把对话框撑爆。
        List<String> nos = new ArrayList<>();
        for (Chapter c : pending.subList(0, Math.min(8, pending.size()))) {
            nos.add("第" + c.chapterNo + "章");
        }
        StringBuilder sb = new StringBuilder("《").append(novel.title).append("》\n");
        if (pending.isEmpty()) {
            sb.append("本地账本暂无待订阅章节，请先确认目录已同步。");
        } else {
            sb.append("本地账本还有 ").append(pending.size()).append(" 章没有任何号买过，")
                    .append("从第").append(pending.get(0).chapterNo).append("章起：")
                    .append(TextUtils.join("、", nos))
                    .append(pending.size() > nos.size() ? "…" : "");
        }
        sb.append("\n").append(CatalogStatus.runNote(novel, System.currentTimeMillis(),
                Prefs.catalogMaxAgeHours(ctx)));
        sb.append("\n").append(getString(Prefs.isCatalogAutoSync(ctx)
                ? R.string.sub_catalog_auto_enabled : R.string.sub_catalog_manual));
        sb.append("\n只订阅第").append(novel.startFrom()).append("章及之后尚未购买的章节。");
        sb.append("\n\n不限章数：一个号订到代券不够下一章就换下一个号，所有号都不够才收工。");
        if (cap > 0) sb.append("\n每号每日花费上限 ").append(cap).append(" 代券。");
        sb.append("\n\n").append(getString(R.string.sub_real_buy_note));
        sb.append("\n\n跑的时候别动屏幕；撞到安全验证会停下来等你手动过。");

        new AlertDialog.Builder(ctx)
                .setTitle(R.string.sub_run_title)
                .setMessage(sb)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton("确认，开始订阅", (d, w) -> {
                    if (AutomationBus.isBusy()) {
                        toast("有任务正在运行或账本正在更新，请等结束后再开始订阅");
                        return;
                    }
                    // 确认框里的书才是这次授权；后台更新或切书之后不能拿旧框启动另一本。
                    if (current == null || !current.isTarget || current.id != novel.id) {
                        toast("目标小说已变化，请重新开始订阅");
                        return;
                    }
                    AutomationService.startSubscribe(ctx);
                    toast("订阅队列已启动，去签到页看实时日志");
                })
                .show();
    }

    // ---------- CSV ----------

    private static final class ImportCounts {
        int added;
        int skipped;
        int invalidDates;
    }

    private void writeCsv(@Nullable Uri uri) {
        if (uri == null) return;
        Context app = requireContext().getApplicationContext();
        Db.io(() -> {
            int count = 0;
            try (OutputStream out = app.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("openOutputStream 返回 null");
                out.write((PurchaseCsv.HEADER + "\n").getBytes(StandardCharsets.UTF_8));
                List<PurchaseRow> rows = dao.loadAllRows();
                for (PurchaseRow r : rows) {
                    out.write(PurchaseCsv.row(r).getBytes(StandardCharsets.UTF_8));
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
        Context app = requireContext().getApplicationContext();
        if (!LedgerEdits.requireIdle(app)) return;
        Db.io(() -> {
            List<List<String>> rows;
            try (InputStream in = app.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IllegalStateException("openInputStream 返回 null");
                rows = Csv.parse(readAll(in));
            } catch (Exception e) {
                post(() -> toast("导入失败：" + e.getMessage()));
                return;
            }
            // 读文件不占用自动化；涉及数据库的整段导入必须与队列互斥。
            Object editOwner = new Object();
            if (!LedgerEdits.tryStart(app, editOwner)) return;
            ImportCounts counts = new ImportCounts();
            try {
                // 后面一行若撞上重复归属，前面几行也要回滚，不能留下只导了一半的账本。
                Db.get(app).runInTransaction(() -> {
                    for (int i = 0; i < rows.size(); i++) {
                        List<String> row = rows.get(i);
                        if (i == 0 && "novel_title".equalsIgnoreCase(Csv.at(row, 0))) continue;

                        String novelTitle = Csv.at(row, 0);
                        int chapterNo = Csv.intAt(row, 1);
                        String login = Csv.at(row, 3);
                        if (Texts.isBlank(novelTitle) || chapterNo < 1 || Texts.isBlank(login)) {
                            counts.skipped++;
                            continue;
                        }
                        Purchase purchase = PurchaseCsv.parsePurchase(row);
                        if (purchase == null) {
                            counts.skipped++;
                            counts.invalidDates++;
                            continue;
                        }
                        // 账号必须已经存在：凭据不在 CSV 里，凭空造个没密码的号只会让切号失败。
                        Account account = accountDao.byLoginName(login);
                        if (account == null) {
                            counts.skipped++;
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
                                novel.id, chapterNo, emptyToNull(Csv.at(row, 2)), -1);
                        if (chapter == null) {
                            counts.skipped++;
                            continue;
                        }
                        purchase.accountId = account.id;
                        purchase.chapterId = chapter.id;
                        dao.upsertImportedPurchase(purchase);
                        counts.added++;
                    }
                    if (counts.added > 0) Db.get(app).auditDao().invalidateAll();
                });
            } catch (RuntimeException e) {
                String message = "导入失败，整批未写入：" + e.getMessage();
                AutomationBus.append(message);
                post(() -> toast(message));
                return;
            } finally {
                AutomationBus.finishEdit(editOwner);
            }
            int a = counts.added;
            int s = counts.skipped;
            int badDates = counts.invalidDates;
            post(() -> toast("导入 " + a + " 条，跳过 " + s + " 条"
                    + (badDates > 0 ? "（其中 " + badDates + " 条金额或购买日期缺失、无效）"
                    : s > 0 ? "（账号不存在或必填字段无效）" : "")));
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
