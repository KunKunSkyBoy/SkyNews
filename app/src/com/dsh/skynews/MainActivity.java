package com.dsh.skynews;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.text.InputType;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.text.Editable;
import android.text.TextWatcher;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

/**
 * 光遇国服公告 · 监控与阅读器
 * 数据源（公开 HTTP JSON，无需登录、无需抓包）：
 *   announcement_live.json 正文 / age_alert_content.json 8+提示 / server_time 服务器时钟
 */
public class MainActivity extends Activity {

    static final String U_ANN = "https://ma75.update.netease.com/game_notice/announcement_live.json";
    static final String U_AGE = "https://ma75.update.netease.com/game_notice/age_alert_content.json";
    static final String U_TIME = "https://mgbsdk.matrix.netease.com/ma75/sdk/server_time";
    /** 官网新闻列表（公告/更新/活动/攻略），JS 页面背后的 JSON 接口 */
    static final String U_NEWS = "https://sixhorse.game.163.com/news/outer/newslist_search.do?start=1&limit=30&contentkind=26689,26690,26691,29298";
    /** B 站公开接口（WBI 签名 + buvid3 访客 cookie，无需登录） */
    /** TapTap 官方帖（社区「Sky光·遇」官方号，免登录，只需 X-UA） */
    static final String U_TAP = "https://www.taptap.cn/webapiv2/feed/v7/by-group?group_id=47615&type=official&sort=created&X-UA=V%3D1%26PN%3DWebApp%26VN%3D0.1.0%26LANG%3Dzh_CN%26PLT%3DPC";
    static final String U_BILI_SPI = "https://api.bilibili.com/x/frontend/finger/spi";
    static final String U_BILI_NAV = "https://api.bilibili.com/x/web-interface/nav";
    static final String U_BILI_DYN = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/space";
    static final long BILI_MID = 211700578L; // 光遇手游（B 站官方，认证 2.6M 粉）
    static final String UA_WEB = "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    /** 已部署的云中继（GitHub Pages）：未手动设置时默认拉这里 */
    static final String U_RELAY_DEFAULT = "https://kunkunskyboy.github.io/SkyNews/feed.json";
    static final String CH_FEED = "sky_feed_channel";
    static final String CH_DAILY = "sky_daily_channel";
    static final int NID_FEED = 1003;
    static final int NID_DAILY = 1004;
    static final int NID_SHARD = 1005;
    static final String CH_SHARD = "sky_shard_channel";
    static final String ACTION_SHARD = "com.dsh.skynews.SHARD_ALARM";
    static final String CH_ANN = "sky_ann_channel";
    static final String CH_MON = "sky_mon_channel";
    static final int NID_ANN = 1001;
    static final int NID_MON = 1002;
    static final int JOB_ID = 0x5c01;

    // 主题色（静态可切换：跟随/浅色/深色；改动后 recreate 生效）
    static int C_BG = 0xFF0B1020, C_CARD = 0xFF151B2E, C_CARD2 = 0xFF1D2439,
            C_ACCENT = 0xFF6FD3FF, C_GOLD = 0xFFF3C77B, C_TEXT = 0xFFE6ECF7, C_DIM = 0xFF8C97B3,
            C_BODY = 0xFFCBD5E8;
    static final int THEME_AUTO = 0, THEME_LIGHT = 1, THEME_DARK = 2;

    /** 动态卡片默认预览字数；超过则显示「展开全文」（不再硬截断存储的正文）。 */
    static final int CARD_PREVIEW_CHARS = 600;
    /** 自动展开的上限：超过这个字数仍先折叠，避免一屏塞几千字。 */
    static final int AUTO_EXPAND_MAX = 4000;

    static int themeMode(Context c) {
        return pref(c).getInt("theme_mode", THEME_AUTO);
    }

    /** 应用主题色：跟随系统用 uiMode 判断；浅色/深色手动覆盖。 */
    static void applyTheme(Context c) {
        int mode = themeMode(c);
        boolean dark;
        if (mode == THEME_DARK) dark = true;
        else if (mode == THEME_LIGHT) dark = false;
        else {
            int ui = c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            dark = ui == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        }
        if (dark) {
            C_BG = 0xFF0B1020; C_CARD = 0xFF151B2E; C_CARD2 = 0xFF1D2439;
            C_ACCENT = 0xFF6FD3FF; C_GOLD = 0xFFF3C77B; C_TEXT = 0xFFE6ECF7; C_DIM = 0xFF8C97B3;
            C_BODY = 0xFFCBD5E8;
        } else {
            C_BG = 0xFFF4F6FB; C_CARD = 0xFFFFFFFF; C_CARD2 = 0xFFE9EEF7;
            C_ACCENT = 0xFF1E88E5; C_GOLD = 0xFFB07A10; C_TEXT = 0xFF1A2233; C_DIM = 0xFF6B7688;
            C_BODY = 0xFF33405A;
        }
    }

    private LinearLayout tabRow, content;
    private TextView tvStatus, tvRefresh;
    private ObjectAnimator spin;
    private TextView copyBtn;
    /** 碎片页每分钟自刷新（进行中/剩余分钟） */
    private boolean shardTimer = false;
    private final java.util.ArrayList<TextView> TOP_TEXTS = new java.util.ArrayList<>();
    static volatile String NET_LASTMOD = "";
    private ScrollView scroller;
    private int tab = 0;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean busy = false;
    private EditText searchBar;
    private String searchQuery = "";
    private boolean favOnly = false;
    /** 动态页里被用户手动切换过展开状态的条目 id（自动展开模式=已收起，折叠模式=已展开） */
    private final java.util.Set<String> toggledFeed = new java.util.HashSet<>();

    // ---- 资料页（assets/sky 静态数据）----
    private int skyCat = 0;
    private final java.util.HashMap<String, JSONObject> skyCache = new java.util.HashMap<>();
    private static final String[] SKY_CATS = {"物品", "先祖", "每日任务", "世界任务", "赛季", "活动"};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        applyTheme(this);
        getWindow().setStatusBarColor(C_BG);
        getWindow().setNavigationBarColor(C_BG);
        ensureChannel(this);
        requestNotifPermission(this);
        buildUi();
        render();
        refresh(false);
        schedule(this);
        scheduleShardAlarms(this);
        handleOpenIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleOpenIntent(intent);
    }

    private void handleOpenIntent(Intent i) {
        try {
            int wantTab = i == null ? -1 : i.getIntExtra("tab", -1);
            if (wantTab >= 0 && tabRow != null && wantTab < tabRow.getChildCount()) {
                tab = wantTab;
                render();
            }
            String u = i == null ? "" : i.getStringExtra("open_url");
            if (u != null && u.startsWith("http")) open(u);
        } catch (Throwable ignore) {
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }

    // ------------------------------------------------------------ UI

    private int dp(float v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private GradientDrawable round(int fill, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView tv(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        t.setIncludeFontPadding(false);
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(18), dp(14), dp(18), dp(10));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView title = tv("光遇公告", 26, C_TEXT, true);
        TOP_TEXTS.add(title);
        tvStatus = tv("加载中…", 11, C_DIM, false);
        tvStatus.setPadding(0, dp(4), 0, 0);
        TOP_TEXTS.add(tvStatus);
        titleBox.addView(title);
        titleBox.addView(tvStatus);
        head.addView(titleBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tvRefresh = tv("↻", 22, C_ACCENT, true);
        tvRefresh.setGravity(Gravity.CENTER);
        tvRefresh.setBackground(round(C_CARD, 14));
        tvRefresh.setOnClickListener(v -> {
            if (tab == 6) render(); else refresh(true);
        });
        LinearLayout.LayoutParams rlp = lp(dp(46), dp(46));
        rlp.leftMargin = dp(8);
        head.addView(tvRefresh, rlp);
        root.addView(head);

        tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tlp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(14);
        root.addView(tabRow, tlp);
        String[] names = {"公告", "动态", "变更历史", "设置", "预载", "日历", "碎片", "资料"};
        String[] icons = {"ann", "feed", "hist", "set", "pre", "cal", "shard", "age"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = tv(names[i], 12, C_DIM, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(4), dp(9), dp(4), dp(9));
            int ic = getResources().getIdentifier("ic_tab_" + icons[i], "drawable", getPackageName());
            if (ic != 0) {
                t.setCompoundDrawablesWithIntrinsicBounds(0, ic, 0, 0);
                t.setCompoundDrawablePadding(dp(4));
            }
            t.setOnClickListener(v -> {
                tab = idx;
                pulse(t);
                render();
            });
            tabRow.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        searchBar = new EditText(this);
        searchBar.setHint("搜索标题/正文/来源…");
        searchBar.setTextSize(13);
        searchBar.setTextColor(C_TEXT);
        searchBar.setHintTextColor(C_DIM);
        searchBar.setSingleLine(true);
        searchBar.setBackground(round(C_CARD2, 10));
        searchBar.setPadding(dp(10), dp(6), dp(10), dp(6));
        searchBar.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                searchQuery = s.toString().trim();
                render();
            }
        });
        LinearLayout.LayoutParams sblp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sblp.topMargin = dp(10);
        root.addView(searchBar, sblp);

        scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setBackground(round(C_CARD, 18));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), dp(16));
        scroller.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = lp(ViewGroup.LayoutParams.MATCH_PARENT, 0);
        slp.weight = 1f;
        slp.topMargin = dp(12);
        root.addView(scroller, slp);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(12);
        root.addView(bar, blp);

        TextView copy = pill("复制全文", C_ACCENT);
        copyBtn = copy;
        putIcon(copy, "ic_ui_copy", C_ACCENT);
        copy.setOnClickListener(v -> {
            if (tab == 5) {
                String txt = calendarText();
                if (txt.trim().length() < 5) { toast("还没有内容可复制"); return; }
                clip(txt);
                toast("已复制活动日历（" + txt.length() + " 字）");
                return;
            }
            if (tab == 4) {
                String txt = preloadText();
                if (txt.trim().length() < 5) { toast("还没有内容可复制"); return; }
                clip(txt);
                toast("已复制预载清单（" + txt.length() + " 字）");
                return;
            }
            if (tab == 1) {
                String txt = feedText();
                if (txt.trim().length() < 5) { toast("还没有动态可复制"); return; }
                clip(txt);
                toast("已复制动态（" + txt.length() + " 字）");
                return;
            }
            if (tab == 6) {
                String txt = shardText();
                clip(txt);
                toast("已复制碎片日程（" + txt.length() + " 字）");
                return;
            }
            String raw = pref(this).getString("ann_json", "");
            try {
                JSONObject o = parseLoose(raw);
                String text = o.optString("Title", "") + "\n\n"
                        + cleanText(o.optString("NeteaseMessage", ""));
                if (text.trim().length() < 5) throw new Exception();
                clip(text);
                toast("已复制（" + text.length() + " 字）");
            } catch (Exception e) {
                toast("还没有内容可复制");
            }
        });
        bar.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView open = pill("原文", C_GOLD);
        putIcon(open, "ic_ui_textnote", C_GOLD);
        open.setOnClickListener(v -> open(U_ANN));
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        olp.leftMargin = dp(10);
        bar.addView(open, olp);

        TextView age = pill("刷新", C_TEXT);
        putIcon(age, "ic_ui_refresh", C_TEXT);
        age.setOnClickListener(v -> {
            if (tab == 6) render(); else refresh(true);
        });
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        al.leftMargin = dp(10);
        bar.addView(age, al);

        setContentView(root);
    }

    private TextView pill(String s, int color) {
        TextView t = tv(s, 14, color, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(12), dp(10), dp(12));
        GradientDrawable g = round(C_CARD2, 14);
        g.setStroke(dp(1), 0x332C5A78);
        t.setBackground(g);
        return t;
    }

    // ------------------------------------------------------------ 渲染

    private void render() {
        if (copyBtn != null) {
            String label = tab == 6 ? "复制石头" : tab == 5 ? "复制日历" : tab == 4 ? "复制预载" : tab == 1 ? "复制动态" : tab == 0 ? "复制全文" : "复制";
            copyBtn.setText(label);
        }
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            TextView t = (TextView) tabRow.getChildAt(i);
            boolean on = i == tab;
            t.setTextColor(on ? C_ACCENT : C_DIM);
            try {
                t.setCompoundDrawableTintList(ColorStateList.valueOf(on ? C_ACCENT : C_DIM));
            } catch (Throwable ignore) { }
            GradientDrawable g = round(on ? C_CARD2 : 0x00000000, 12);
            if (on) g.setStroke(dp(1), 0xFF2C5A78);
            t.setBackground(g);
        }
        if (searchBar != null) {
            searchBar.setVisibility(tab == 0 || tab == 1 || tab == 2 || tab == 7 ? View.VISIBLE : View.GONE);
        }
        content.removeAllViews();
        if (tab == 0) renderAnn();
        else if (tab == 1) renderFeed();
        else if (tab == 2) renderHist();
        else if (tab == 4) renderPreload();
        else if (tab == 5) renderCalendar();
        else if (tab == 6) renderShard();
        else if (tab == 7) renderSky();
        else renderSettings();
        updateStatus();
        animateIn();
    }

    // ------------------------------------------------------------ 资料页（assets/sky 静态数据）

    private JSONObject skyObj(String name) throws Exception {
        JSONObject cached = skyCache.get(name);
        if (cached != null) return cached;
        java.io.InputStream in = getAssets().open("sky/" + name + ".json");
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        JSONObject o = new JSONObject(new String(bo.toByteArray(), "UTF-8"));
        skyCache.put(name, o);
        return o;
    }

    private boolean skyMatch(String... parts) {
        if (searchQuery == null || searchQuery.isEmpty()) return true;
        String q = searchQuery.toLowerCase();
        for (String p : parts) {
            if (p != null && p.toLowerCase().contains(q)) return true;
        }
        return false;
    }

    private TextView skyNote(String s) {
        TextView t = tv(s, 11, C_DIM, false);
        t.setPadding(0, dp(4), 0, dp(2));
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    private void skyLine(String main, String sub) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(C_CARD2, 10));
        box.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams bp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(8);
        TextView t1 = tv(main, 13, C_TEXT, false);
        t1.setTextIsSelectable(true);
        box.addView(t1);
        if (sub != null && !sub.isEmpty()) {
            TextView t2 = tv(sub, 11, C_DIM, false);
            t2.setPadding(0, dp(2), 0, 0);
            box.addView(t2);
        }
        content.addView(box, bp);
    }

    private void renderSky() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < SKY_CATS.length; i++) {
            final int idx = i;
            TextView b = tv(SKY_CATS[i], 12, i == skyCat ? C_ACCENT : C_DIM, true);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(4), dp(7), dp(4), dp(7));
            GradientDrawable g = round(i == skyCat ? C_CARD2 : 0x00000000, 10);
            if (i == skyCat) g.setStroke(dp(1), 0xFF2C5A78);
            b.setBackground(g);
            b.setOnClickListener(v -> {
                skyCat = idx;
                render();
            });
            LinearLayout.LayoutParams p = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.weight = 1f;
            row.addView(b, p);
        }
        content.addView(row);
        try {
            if (skyCat == 0) renderSkyItems();
            else if (skyCat == 1) renderSkySpirits();
            else if (skyCat == 2) renderSkyDaily();
            else if (skyCat == 3) renderSkyWorld();
            else if (skyCat == 4) renderSkySeasons();
            else renderSkyActivities();
        } catch (Exception e) {
            content.addView(hint("资料加载失败：" + e));
        }
    }

    private void renderSkyItems() throws Exception {
        JSONObject doc = skyObj("items");
        JSONArray arr = doc.optJSONArray("items");
        int total = arr == null ? 0 : arr.length();
        content.addView(section("物品/装扮（" + total + "）"));
        content.addView(skyNote("静态数据 v" + doc.optString("v") + " · 搜索可过滤名称/类型"));
        int shown = 0;
        for (int i = 0; i < total && shown < 80; i++) {
            JSONObject o = arr.getJSONObject(i);
            String n = o.optString("n");
            String tc = o.optString("tc", o.optString("t"));
            String id = o.optString("i");
            if (!skyMatch(n, tc, id)) continue;
            skyLine(n, tc + " · " + ("store".equals(o.optString("r")) ? "官方名" : "推导名") + " · " + id);
            shown++;
        }
        if (shown == 0) content.addView(hint("没有匹配的物品"));
        else if (shown >= 80) content.addView(hint("仅显示前 80 条，可输入关键词继续筛选"));
    }

    private void renderSkySpirits() throws Exception {
        JSONObject doc = skyObj("spirits");
        JSONArray arr = doc.optJSONArray("items");
        int total = arr == null ? 0 : arr.length();
        content.addView(section("先祖（" + total + "）"));
        int shown = 0;
        for (int i = 0; i < total && shown < 80; i++) {
            JSONObject o = arr.getJSONObject(i);
            String n = o.optString("n");
            String sc = o.optString("sc");
            String map = o.optString("r");
            String en = o.optString("en");
            if (!skyMatch(n, en, sc, map, o.optString("s"))) continue;
            StringBuilder sub = new StringBuilder();
            if (!sc.isEmpty()) sub.append(sc);
            if (!map.isEmpty()) sub.append(sub.length() > 0 ? " · " : "").append(map);
            if (!en.isEmpty()) sub.append(sub.length() > 0 ? " · " : "").append(en);
            skyLine(n, sub.toString());
            shown++;
        }
        if (shown == 0) content.addView(hint("没有匹配的先祖"));
    }

    private void renderSkyDaily() throws Exception {
        JSONObject doc = skyObj("quests");
        JSONObject d = doc.optJSONObject("daily");
        JSONArray arr = d == null ? null : d.optJSONArray("items");
        int total = arr == null ? 0 : arr.length();
        content.addView(section("每日任务（" + total + "）"));
        int shown = 0;
        for (int i = 0; i < total && shown < 120; i++) {
            JSONObject o = arr.getJSONObject(i);
            String n = o.optString("n");
            String m = o.optString("m");
            String np = o.optString("np");
            String g = o.optString("g");
            if (!skyMatch(n, m, np, g)) continue;
            StringBuilder sub = new StringBuilder();
            if (!m.isEmpty()) sub.append(m);
            if (!np.isEmpty()) sub.append(sub.length() > 0 ? " · " : "").append("由「").append(np).append("」发布");
            skyLine(n, sub.toString());
            shown++;
        }
        if (shown == 0) content.addView(hint("没有匹配的每日任务"));
    }

    private void renderSkyWorld() throws Exception {
        JSONObject doc = skyObj("quests");
        JSONObject d = doc.optJSONObject("world");
        JSONArray arr = d == null ? null : d.optJSONArray("items");
        int total = arr == null ? 0 : arr.length();
        content.addView(section("世界任务（" + total + "）"));
        content.addView(skyNote("世界任务只有内部编号与地图，中文标题由游戏内显示"));
        int shown = 0;
        for (int i = 0; i < total && shown < 120; i++) {
            JSONObject o = arr.getJSONObject(i);
            String id = o.optString("i");
            String m = o.optString("m");
            if (!skyMatch(id, m)) continue;
            skyLine(id, m);
            shown++;
        }
        if (shown == 0) content.addView(hint("没有匹配的世界任务"));
    }

    private void renderSkySeasons() throws Exception {
        JSONObject doc = skyObj("seasons");
        JSONArray arr = doc.optJSONArray("items");
        int total = arr == null ? 0 : arr.length();
        content.addView(section("赛季（" + total + "）"));
        content.addView(skyNote("第 2–32 季中文名；起止时间为排期反推（est=1）"));
        for (int i = 0; i < total; i++) {
            JSONObject o = arr.getJSONObject(i);
            String n = o.optString("zh");
            String k = o.optString("k");
            if (!skyMatch(n, k)) continue;
            skyLine(n, "第 " + o.optInt("n") + " 季 · " + k);
        }
    }

    private void renderSkyActivities() throws Exception {
        JSONObject doc = skyObj("activities");
        content.addView(section("活动排期（静态快照）"));
        content.addView(skyNote("快照 " + doc.optString("generated_at") + " · " + doc.optString("note", "数据可能过期")));
        JSONArray active = doc.optJSONArray("active");
        if (active != null && active.length() > 0) {
            content.addView(section("进行中（" + active.length() + "）"));
            int shown = 0;
            for (int i = 0; i < active.length() && shown < 40; i++) {
                JSONObject o = active.getJSONObject(i);
                String n = o.optString("n");
                String cn = o.optString("cn");
                if (!skyMatch(n, cn)) continue;
                skyLine(cn, n + " · " + o.optString("st") + " ~ " + o.optString("et"));
                shown++;
            }
        }
        JSONArray up = doc.optJSONArray("upcoming");
        if (up != null && up.length() > 0) {
            content.addView(section("即将开始（" + up.length() + "）"));
            int shown = 0;
            for (int i = 0; i < up.length() && shown < 40; i++) {
                JSONObject o = up.getJSONObject(i);
                String n = o.optString("n");
                String cn = o.optString("cn");
                if (!skyMatch(n, cn)) continue;
                skyLine(cn, n + " · " + o.optString("st") + " ~ " + o.optString("et"));
                shown++;
            }
        }
    }

    // ------------------------------------------------------------ 动效

    private void pulse(View v) {
        v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(70)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f)
                        .setDuration(160).setInterpolator(new DecelerateInterpolator(1.8f)).start())
                .start();
    }

    private void startSpin() {
        stopSpin();
        spin = ObjectAnimator.ofFloat(tvRefresh, "rotation", 0f, 360f);
        spin.setDuration(900);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        spin.start();
    }

    private void stopSpin() {
        if (spin != null) { spin.cancel(); spin = null; }
        tvRefresh.setRotation(0f);
    }

    /** 文字动效：递归遍历，所有 TextView 都要淡入上滑（卡片额外带缩放） */
    private void animateIn() {
        animateTree(content, new int[]{0});
        if (tabRow != null) for (int i = 0; i < tabRow.getChildCount(); i++) animateTree(tabRow.getChildAt(i), new int[]{0});
        fadeTopBar();
    }

    private void animateTree(View v, int[] idx) {
        if (v == null) return;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int k = 0; k < g.getChildCount(); k++) animateTree(g.getChildAt(k), idx);
            return;
        }
        if (!(v instanceof android.widget.TextView)) return;
        int i = idx[0]++;
        if (i > 70) return;
        v.setAlpha(0f);
        v.setTranslationY(dp(9));
        v.animate().alpha(1f).translationY(0f)
                .setStartDelay(Math.min(i * 16L, 620L)).setDuration(230)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    /** 顶栏标题/状态行也跟着淡入 */
    private void fadeTopBar() {
        for (TextView t : TOP_TEXTS) {
            if (t == null) continue;
            t.setAlpha(0f);
            t.animate().alpha(1f).setDuration(280).setInterpolator(new DecelerateInterpolator(1.4f)).start();
        }
    }

    private void renderAnn() {
        String raw = pref(this).getString("ann_json", "");
        if (raw.isEmpty()) {
            content.addView(hint("还没有抓到公告\n点右上角 ↻ 立即抓取"));
            return;
        }
        try {
            JSONObject o = parseLoose(raw);
            TextView t = tv(o.optString("Title", "公告"), 19, C_TEXT, true);
            t.setLineSpacing(dp(3), 1f);
            content.addView(t);

            String sha = pref(this).getString("ann_sha", "00000000");
            TextView m = tv("抓取 " + pref(this).getString("last_check", "—") + " · " + sha.substring(0, 8)
                    + " · " + o.optString("NeteaseMessage", "").length() + " 字", 10, C_DIM, false);
            m.setPadding(0, dp(6), 0, dp(10));
            content.addView(m);

            View div = new View(this);
            div.setBackgroundColor(C_CARD2);
            content.addView(div, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

            if (!searchQuery.isEmpty() && !matchQuery(searchQuery,
                    o.optString("Title", ""), o.optString("NeteaseMessage", ""))) {
                content.addView(hint("没有匹配「" + searchQuery + "」的内容"));
            } else {
                addBody(cleanText(o.optString("NeteaseMessage", "")));
            }
        } catch (Exception e) {
            content.addView(hint("解析失败：" + e.getMessage()));
        }
    }

    private void addBody(String body) {
        String[] lines = body.replace("\r", "").split("\n");
        boolean firstLine = true;
        for (String ln : lines) {
            String s = ln.trim();
            if (s.isEmpty()) continue;
            if (!matchQuery(searchQuery, s)) continue;
            TextView t;
            if (s.startsWith("【") && s.contains("】")) {
                t = tv(s, 16, C_GOLD, true);
                t.setPadding(0, dp(14), 0, dp(6));
            } else if (firstLine) {
                t = tv(s, 15, C_TEXT, true);
                t.setPadding(0, dp(12), 0, dp(4));
            } else {
                t = tv(s, 14, C_BODY, false);
                t.setPadding(0, dp(3), 0, dp(3));
            }
            t.setLineSpacing(dp(4), 1f);
            t.setTextIsSelectable(true);
            content.addView(t);
            firstLine = false;
        }
    }

    private void renderFeed() {
        JSONArray arr;
        try {
            arr = new JSONArray(pref(this).getString("feed", "[]"));
        } catch (Exception e) {
            arr = new JSONArray();
        }
        java.util.LinkedHashMap<String, JSONObject> favs = favMap(this);
        java.util.List<JSONObject> show = new java.util.ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "");
            if (favOnly && !favs.containsKey(id)) continue;
            if (!searchQuery.isEmpty() && !matchQuery(searchQuery,
                    o.optString("src", ""), o.optString("t", ""), o.optString("text", ""))) continue;
            show.add(o);
        }
        if (arr.length() == 0) {
            content.addView(hint("还没有抓到官方动态\n点右上角 ↻ 抓取（微博 / B 站 / 官网）"));
            return;
        }

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.addView(tv("官方动态 · " + (favOnly ? "收藏 " : "") + show.size() + " 条", 12, C_DIM, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView favChip = tv(favOnly ? "★ 只看收藏" : "☆ 收藏", 12, favOnly ? C_GOLD : C_DIM, true);
        favChip.setPadding(dp(10), dp(4), dp(10), dp(4));
        favChip.setBackground(round(C_CARD2, 10));
        favChip.setOnClickListener(v -> { favOnly = !favOnly; render(); });
        tools.addView(favChip);
        content.addView(tools);

        if (show.isEmpty()) {
            content.addView(hint(searchQuery.isEmpty() ? "还没有收藏\n点卡片右上角 ☆ 收藏" : "没有匹配「" + searchQuery + "」的动态"));
            return;
        }
        for (JSONObject o : show) {
            final String id = o.optString("id", "");
            boolean stale = o.optBoolean("stale", false);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(10), dp(12), dp(12));
            card.setBackground(round(C_CARD2, 12));
            card.setAlpha(stale ? 0.55f : 1f);
            LinearLayout.LayoutParams clp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = dp(8);
            card.setLayoutParams(clp);

            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(chip(o.optString("src", "官方")));
            LinearLayout.LayoutParams tlp2 = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp2.leftMargin = dp(8);
            head.addView(tv(o.optString("t", ""), 11, C_DIM, false), tlp2);
            if (stale) {
                LinearLayout.LayoutParams slp2 = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                slp2.leftMargin = dp(8);
                TextView st = tv("旧", 9, C_DIM, true);
                st.setBackground(round(0x33FFFFFF, 5));
                st.setPadding(dp(5), dp(1), dp(5), dp(1));
                head.addView(st, slp2);
            }
            TextView star = tv(favs.containsKey(id) ? "★" : "☆", 20, favs.containsKey(id) ? C_GOLD : C_DIM, false);
            star.setPadding(dp(8), 0, 0, 0);
            star.setOnClickListener(v -> {
                toggleFav(MainActivity.this, o);
                render();
            });
            head.addView(star, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.addView(head);

            String fullText = o.optString("text", "");
            boolean autoExpand = pref(this).getBoolean("auto_expand", true);
            final boolean longText = fullText.length() > CARD_PREVIEW_CHARS;
            // 自动展开模式下默认整篇显示（超长文除外），用户点「收起」才折叠
            final boolean auto = autoExpand && fullText.length() <= AUTO_EXPAND_MAX;
            final boolean expanded = longText && (auto ? !toggledFeed.contains(id) : toggledFeed.contains(id));
            String shown = (!longText || expanded) ? fullText
                    : fullText.substring(0, CARD_PREVIEW_CHARS).trim() + "\n…";
            TextView body = tv(shown, 14, stale ? C_DIM : C_TEXT, false);
            body.setLineSpacing(dp(4), 1f);
            body.setPadding(0, dp(8), 0, 0);
            card.addView(body);
            if (longText) {
                TextView toggle = tv(expanded ? "▲ 收起" : "▼ 展开全文（共 " + fullText.length() + " 字）",
                        12, C_ACCENT, true);
                toggle.setPadding(0, dp(6), 0, 0);
                toggle.setOnClickListener(v -> {
                    if (expanded) toggledFeed.add(id); else toggledFeed.remove(id);
                    render();
                });
                card.addView(toggle);
            }

            final String url = o.optString("url", "");
            final String itemTitle = o.optString("t", "");
            card.setOnClickListener(v -> openItem(url, itemTitle, fullText));
            content.addView(card);
        }
    }

    /** 搜索：大小写不敏感，任一字段包含即命中。 */
    static boolean matchQuery(String q, String... fields) {
        if (q == null || q.trim().isEmpty()) return true;
        String needle = q.trim().toLowerCase(Locale.CHINA);
        for (String f : fields) {
            if (f != null && f.toLowerCase(Locale.CHINA).contains(needle)) return true;
        }
        return false;
    }

    /** 收藏存储：id -> {id,src,t,text,url,ts} */
    static java.util.LinkedHashMap<String, JSONObject> favMap(Context c) {
        java.util.LinkedHashMap<String, JSONObject> map = new java.util.LinkedHashMap<>();
        try {
            JSONObject o = new JSONObject(pref(c).getString("favs", "{}"));
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject v = o.optJSONObject(k);
                if (v != null) map.put(k, v);
            }
        } catch (Exception ignore) {
        }
        return map;
    }

    static void toggleFav(Context c, JSONObject item) {
        java.util.LinkedHashMap<String, JSONObject> map = favMap(c);
        String id = item.optString("id", "");
        if (id.isEmpty()) return;
        JSONObject obj = new JSONObject();
        try {
            if (map.containsKey(id)) {
                map.remove(id);
            } else {
                obj.put("id", id);
                obj.put("src", item.optString("src", ""));
                obj.put("t", item.optString("t", ""));
                obj.put("text", item.optString("text", ""));
                obj.put("url", item.optString("url", ""));
                obj.put("ts", item.optLong("ts", 0));
                map.put(id, obj);
            }
            JSONObject out = new JSONObject();
            for (java.util.Map.Entry<String, JSONObject> e : map.entrySet()) out.put(e.getKey(), e.getValue());
            pref(c).edit().putString("favs", out.toString()).apply();
        } catch (Exception ignore) {
        }
    }

    private TextView chip(String s) {
        TextView t = tv(s, 10, C_BG, true);
        t.setPadding(dp(6), dp(2), dp(6), dp(2));
        t.setBackground(round(C_ACCENT, 6));
        return t;
    }

    private String feedText() {
        JSONArray arr;
        try {
            arr = new JSONArray(pref(this).getString("feed", "[]"));
        } catch (Exception e) {
            arr = new JSONArray();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            sb.append("【").append(o.optString("src", "官方")).append("】 ").append(o.optString("t", "")).append("\n")
              .append(o.optString("text", "")).append("\n")
              .append(o.optString("url", "")).append("\n\n");
        }
        return sb.toString().trim();
    }

    /** 合并各源条目：按 id 去重、按 ts 倒序、最多保留 150 条。返回新增条数。 */
    /** 官网正文是 HTML 片段：<br> 转换行、去标签、反转义实体，得到纯文本。 */
    static String cleanText(String s) {
        if (s == null) return "";
        String t = s.replace("\r\n", "\n").replace("\r", "\n");
        t = t.replaceAll("(?i)<br\\s*/?>", "\n");
        t = t.replaceAll("(?i)</(p|div|li|tr|h[1-6])>", "\n");
        t = t.replaceAll("<[^>]+>", "");
        t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&#x27;", "'").replace("&apos;", "'");
        t = t.replace('\u3000', ' ');
        t = t.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n");
        return t.trim();
    }

    /** 公告正文规整：保留段落换行、压缩连续空行，**不截断**。 */
    static String tidyBody(String s) {
        return cleanText(s);
    }

    /** 清掉同一标题、旧版 id（正文被截断）的公告条目，避免重抓后同一条出现两份。 */
    static void dropLegacyAnn(Context ctx, String title, String keepId) {
        if (title == null || title.isEmpty()) return;
        SharedPreferences sp = pref(ctx);
        JSONArray old;
        try {
            old = new JSONArray(sp.getString("feed", "[]"));
        } catch (Exception e) {
            return;
        }
        JSONArray out = new JSONArray();
        boolean changed = false;
        for (int i = 0; i < old.length(); i++) {
            JSONObject o = old.optJSONObject(i);
            if (o == null) continue;
            boolean legacy = o.optString("id", "").startsWith("ann-")
                    && !keepId.equals(o.optString("id", ""))
                    && "公告".equals(o.optString("src", ""))
                    && o.optString("text", "").startsWith(title);
            if (legacy) {
                changed = true;
                continue;
            }
            out.put(o);
        }
        if (changed) sp.edit().putString("feed", out.toString()).apply();
    }

    static int mergeFeed(Context ctx, java.util.List<JSONObject> incoming) {
        SharedPreferences sp = pref(ctx);
        JSONArray old;
        try {
            old = new JSONArray(sp.getString("feed", "[]"));
        } catch (Exception e) {
            old = new JSONArray();
        }
        java.util.LinkedHashMap<String, JSONObject> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < old.length(); i++) {
            JSONObject o = old.optJSONObject(i);
            if (o != null) map.put(o.optString("id", "old-" + i), o);
        }
        int added = 0;
        for (JSONObject o : incoming) {
            String id = o.optString("id", "");
            if (id.isEmpty()) continue;
            if (!map.containsKey(id)) added++;
            map.put(id, o);
        }
        java.util.List<JSONObject> list = new java.util.ArrayList<>(map.values());
        list.sort((a, b) -> Long.compare(b.optLong("ts", 0), a.optLong("ts", 0)));
        JSONArray out = new JSONArray();
        for (int i = 0; i < Math.min(list.size(), 150); i++) out.put(list.get(i));
        sp.edit().putString("feed", out.toString()).apply();
        return added;
    }

    // ------------------------------------------------------------ 官方动态抓取

    /** 带 Referer/Cookie 的 GET：B 站与官网 JSON 接口都只认浏览器 UA + 来源页。 */
    static String http(String url, String referer, String cookie) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return httpOnce(url, referer, cookie);
            } catch (Exception e) {
                last = e;
                if (attempt < 2) {
                    try { Thread.sleep(400L * (attempt + 1)); } catch (InterruptedException ie) { break; }
                }
            }
        }
        throw last == null ? new RuntimeException("网络失败") : last;
    }

    static String httpOnce(String url, String referer, String cookie) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setConnectTimeout(12000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", UA_WEB);
        c.setRequestProperty("Accept", "application/json, text/plain, */*");
        // 注意：B 站动态接口对带 Accept-Encoding: gzip 的请求会返回空列表（2026-11 实测：
        // 同一 URL 带 gzip 返回 0 条，改 identity 返回 26 条）。HttpURLConnection 默认可能
        // 自动协商 gzip，这里显式 identity 钉死；服务端若仍压缩，下面照常按 gzip 解。
        if (referer != null) c.setRequestProperty("Referer", referer);
        if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new RuntimeException("HTTP " + code);
            InputStream in = c.getInputStream();
            if ("gzip".equalsIgnoreCase(c.getContentEncoding())) in = new GZIPInputStream(in);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    static String md5hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] d = md.digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** WBI 签名（B 站 2023 起空间接口必需）：mixinKey = 重排后的 img_key+sub_key 前 32 位。 */
    static final int[] WBI_MIXIN = {46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,22,25,54,21,56,59,6,63,57,62,11,36,20,34,44,52};

    static String wbiQuery(TreeMap<String, String> params, String imgKey, String subKey) throws Exception {
        String raw = imgKey + subKey;
        StringBuilder mix = new StringBuilder();
        for (int i : WBI_MIXIN) if (i < raw.length()) mix.append(raw.charAt(i));
        String mixin = mix.substring(0, 32);
        long wts = System.currentTimeMillis() / 1000L;
        StringBuilder q = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : params.entrySet()) {
            if (q.length() > 0) q.append('&');
            q.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        q.append("&wts=").append(wts);
        return q + "&w_rid=" + md5hex(q + mixin);
    }

    static String baseName(String url) {
        int slash = url.lastIndexOf('/');
        String s = slash >= 0 ? url.substring(slash + 1) : url;
        int dot = s.indexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }

    /** 抓全部动态源 → 合并入库，返回新增条数。单源失败不影响其它源。 */
    static int pullFeed(Context ctx, String ann) {
        List<JSONObject> items = new ArrayList<>();
        StringBuilder err = new StringBuilder();
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        long t0 = System.currentTimeMillis();

        // 1) 官网新闻列表（公告 / 更新 / 活动 / 攻略）
        try {
            JSONObject r = new JSONObject(http(U_NEWS, "https://sky.163.com/news/", null));
            JSONArray arr = r.optJSONArray("result");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String url = o.optString("url", "");
                if (url.startsWith("//")) url = "https:" + url;
                long ts = o.optLong("showtime", 0);
                JSONObject it = new JSONObject();
                it.put("id", "gf-" + o.optString("code", ""));
                it.put("src", "官网");
                it.put("t", ts > 0 ? fmt.format(new Date(ts)) : "");
                it.put("ts", ts);
                it.put("text", o.optString("title", ""));
                it.put("url", url);
                items.add(it);
            }
        } catch (Exception e) {
            err.append("官网:").append(e.getMessage()).append('\n');
            markHealth(ctx, "官网", false, 0, System.currentTimeMillis() - t0, e.getMessage());
        }

        // 2) TapTap 官方帖（社区官方号，免登录；缺 X-UA 会 400）
        try {
            JSONObject r = new JSONObject(http(U_TAP, "https://www.taptap.cn/", null));
            JSONArray arr = r.optJSONObject("data").optJSONArray("list");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                JSONObject m = o.optJSONObject("moment");
                if (m == null) continue;
                JSONObject topic = m.optJSONObject("topic");
                String title = topic == null ? "" : topic.optString("title", "");
                String summary = topic == null ? "" : topic.optString("summary", "");
                long ts = m.optLong("created_time", 0);
                String id = m.optString("id_str", "");
                if (id.isEmpty()) continue;
                JSONObject it = new JSONObject();
                it.put("id", "tt-" + id);
                it.put("src", "TapTap");
                it.put("t", ts > 0 ? fmt.format(new Date(ts * 1000L)) : "");
                it.put("ts", ts * 1000L);
                it.put("text", ((title.isEmpty() ? "" : title + "\n") + summary).trim());
                it.put("url", "https://www.taptap.cn/moment/" + id);
                items.add(it);
            }
        } catch (Exception e) {
            err.append("TapTap:").append(e.getMessage()).append('\n');
            markHealth(ctx, "TapTap", false, 0, System.currentTimeMillis() - t0, e.getMessage());
        }

        // 2) B 站官方动态（访客 cookie + WBI 签名，无需登录）
        try {
            JSONObject sd = new JSONObject(http(U_BILI_SPI, "https://www.bilibili.com/", null)).optJSONObject("data");
            String b3 = sd == null ? "" : sd.optString("b_3", "");
            String b4 = sd == null ? "" : sd.optString("b_4", "");
            String cookie = "buvid3=" + b3 + "; buvid4=" + b4 + "; b_nut=" + (System.currentTimeMillis() / 1000L);
            JSONObject nav = new JSONObject(http(U_BILI_NAV, "https://www.bilibili.com/", cookie));
            JSONObject wi = nav.optJSONObject("data").optJSONObject("wbi_img");
            String img = baseName(wi.optString("img_url", ""));
            String sub = baseName(wi.optString("sub_url", ""));
            TreeMap<String, String> q = new TreeMap<>();
            q.put("host_mid", String.valueOf(BILI_MID));
            q.put("timezone_offset", "-480");
            q.put("features", "itemOpusStyle,listOnlyfans,opusBigCover,onlyfansVote");
            String dyn = http(U_BILI_DYN + "?" + wbiQuery(q, img, sub),
                    "https://space.bilibili.com/" + BILI_MID + "/dynamic", cookie);
            JSONArray arr = new JSONObject(dyn).optJSONObject("data").optJSONArray("items");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                JSONObject mod = o.optJSONObject("modules");
                JSONObject au = mod == null ? null : mod.optJSONObject("module_author");
                JSONObject dy = mod == null ? null : mod.optJSONObject("module_dynamic");
                String text = "";
                if (dy != null) {
                    JSONObject desc = dy.optJSONObject("desc");
                    if (desc != null) text = desc.optString("text", "");
                    if (text.isEmpty()) {
                        JSONObject major = dy.optJSONObject("major");
                        JSONObject opus = major == null ? null : major.optJSONObject("opus");
                        if (opus != null && opus.optJSONObject("summary") != null)
                            text = opus.optJSONObject("summary").optString("text", "");
                        if (text.isEmpty() && major != null && major.optJSONObject("archive") != null)
                            text = major.optJSONObject("archive").optString("title", "");
                    }
                }
                long pubTs = au == null ? 0 : au.optLong("pub_ts", 0);
                String id = o.optString("id_str", "");
                JSONObject it = new JSONObject();
                it.put("id", "bl-" + id);
                it.put("src", "B站");
                it.put("t", pubTs > 0 ? fmt.format(new Date(pubTs * 1000L)) : (au == null ? "" : au.optString("pub_time", "")));
                it.put("ts", pubTs * 1000L);
                it.put("text", text.trim());
                it.put("url", "https://t.bilibili.com/" + id);
                items.add(it);
            }
        } catch (Exception e) {
            err.append("B站:").append(e.getMessage()).append('\n');
            markHealth(ctx, "B站", false, 0, System.currentTimeMillis() - t0, e.getMessage());
        }

        // 4) 官网公告正文（与「公告」页同源，作为动态时间线里的一条）
        try {
            JSONObject o = parseLoose(ann);
            String annTitle = o.optString("Title", "").trim();
            String body = tidyBody(o.optString("NeteaseMessage", ""));
            String newId = "ann-" + sha256(annTitle);
            dropLegacyAnn(ctx, annTitle, newId);   // 旧版把正文截到 140 字，重抓时清掉旧条目
            JSONObject it = new JSONObject();
            it.put("id", newId);
            it.put("src", "公告");
            it.put("t", fmt.format(new Date()));
            it.put("ts", System.currentTimeMillis());
            it.put("text", annTitle + (body.isEmpty() ? "" : "\n\n" + body));
            it.put("url", "https://sky.163.com/news/");
            items.add(it);
        } catch (Exception e) {
            err.append("公告:").append(e.getMessage()).append('\n');
            markHealth(ctx, "公告", false, 0, System.currentTimeMillis() - t0, e.getMessage());
        }

        // 5) 云中继：同时兼容旧 schema（updated/count/problems/items）与 schema:2（sources+items）
        String relay = pref(ctx).getString("relay_url", U_RELAY_DEFAULT);
        java.util.Set<String> relayFailed = new java.util.HashSet<>();
        if (!relay.isEmpty()) {
            try {
                long rt = System.currentTimeMillis();
                String body = http(relay, null, null);
                JSONArray arr;
                JSONObject relayDoc = null;
                if (body.trim().startsWith("[")) {
                    arr = new JSONArray(body);
                } else {
                    relayDoc = new JSONObject(body);
                    JSONArray it = relayDoc.optJSONArray("items");
                    arr = it == null ? new JSONArray() : it;
                }
                // schema:2：逐源健康 + 失败源（失败源保留旧条目并灰显）
                JSONObject srcs = relayDoc == null ? null : relayDoc.optJSONObject("sources");
                java.util.Set<String> relayHealth = new java.util.HashSet<>();
                if (srcs != null) {
                    java.util.Iterator<String> sk = srcs.keys();
                    while (sk.hasNext()) {
                        String sn = sk.next();
                        JSONObject so = srcs.optJSONObject(sn);
                        if (so == null) continue;
                        boolean sok = so.optBoolean("ok", true);
                        String disp = relaySrcLabel(sn);
                        relayHealth.add("中继·" + disp);
                        markHealth(ctx, "中继·" + disp, sok, so.optInt("count", 0),
                                so.optLong("elapsed_ms", 0), so.optString("error", ""));
                        if (!sok) relayFailed.add(disp);
                    }
                    pruneHealth(ctx, "中继·", relayHealth);
                }
                // 旧 schema：problems 形如 "B站: HTTPError 412"（有 sources 时忽略，避免重复显示）
                if (relayDoc != null && srcs == null) {
                    JSONArray probs = relayDoc.optJSONArray("problems");
                    for (int i = 0; probs != null && i < probs.length(); i++) {
                        String p = probs.optString(i, "");
                        int c = p.indexOf(':');
                        String name = (c > 0 ? p.substring(0, c) : p).trim();
                        if (!name.isEmpty()) relayFailed.add(name);
                        markHealth(ctx, "中继·" + name, false, 0, 0, p);
                    }
                }
                java.util.Set<String> have = new java.util.HashSet<>();
                for (JSONObject x : items) have.add(x.optString("id", ""));
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String baseId = o.optString("id", "i" + i);
                    if (have.contains(baseId)) continue;   // 本地已抓到（官网/TapTap/B站），不重复
                    have.add(baseId);
                    boolean schema2 = o.has("source") || o.has("title") || o.has("summary");
                    JSONObject it = new JSONObject();
                    it.put("id", "rl-" + baseId);
                    it.put("src", o.optString(schema2 ? "source" : "src", "中继"));
                    it.put("t", o.optString(schema2 ? "title" : "t", o.optString("time", "")));
                    long rts = o.optLong("ts", 0);
                    if (rts > 0 && rts < 100000000000L) rts *= 1000L; // 中继 ts 是秒，统一成毫秒
                    it.put("ts", rts);
                    it.put("text", o.optString(schema2 ? "summary" : "text", o.optString("text", "")));
                    it.put("url", o.optString("url", ""));
                    it.put("stale", o.optBoolean("stale", false));
                    items.add(it);
                }
                markHealth(ctx, "中继", true, arr == null ? 0 : arr.length(),
                        System.currentTimeMillis() - rt, "");
            } catch (Exception e) {
                err.append("中继:").append(e.getMessage()).append('\n');
                markHealth(ctx, "中继", false, 0, System.currentTimeMillis() - t0, e.getMessage());
                relayFailed.add("中继");
            }
        }

        int added = mergeFeed(ctx, items);
        applyStale(ctx, relayFailed);
        SharedPreferences sp = pref(ctx);
        boolean init = sp.getBoolean("feed_init", false);
        sp.edit().putString("feed_err", err.toString())
                .putLong("feed_check", System.currentTimeMillis())
                .putBoolean("feed_init", true).apply();
        if (added > 0 && init) notifyFeed(ctx, added, items.isEmpty() ? "" : items.get(0).optString("text", ""),
                items.isEmpty() ? "" : items.get(0).optString("url", ""));
        return added;
    }

    static void notifyFeed(Context c, int added, String sample, String url) {
        try {
            if (!notifyAllowed(c, sample)) return;
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            String brief = sample.replace("\n", " ").trim();
            if (brief.length() > 100) brief = brief.substring(0, 100) + "…";
            Notification n = new Notification.Builder(c, CH_FEED)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("官方动态 +" + added + " 条")
                    .setContentText(brief)
                    .setStyle(new Notification.BigTextStyle().bigText(brief))
                    .setAutoCancel(true)
                    .setContentIntent(pi(c, url))
                    .build();
            nm.notify(NID_FEED, n);
        } catch (Throwable ignore) {
        }
    }

    private void renderHist() {
        JSONArray arr;
        try {
            arr = new JSONArray(pref(this).getString("hist", "[]"));
        } catch (Exception e) {
            arr = new JSONArray();
        }
        if (arr.length() == 0) {
            content.addView(hint("还没有检测到变更\n公告更新后这里会记录时间 + 标题"));
            return;
        }
        content.addView(tv("最近 " + arr.length() + " 次变更", 12, C_DIM, false));
        for (int i = arr.length() - 1; i >= 0; i--) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (!searchQuery.isEmpty() && !matchQuery(searchQuery, o.optString("title", ""), o.optString("t", ""))) continue;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackground(round(C_CARD2, 12));
            LinearLayout.LayoutParams rlp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(8);
            row.setLayoutParams(rlp);
            row.addView(tv(o.optString("t", ""), 11, C_ACCENT, false));
            TextView ti = tv(o.optString("title", "(无标题)"), 15, C_TEXT, true);
            ti.setPadding(0, dp(4), 0, 0);
            row.addView(ti);
            String sha = o.optString("sha", "");
            row.addView(tv("sha256 " + (sha.length() > 12 ? sha.substring(0, 12) : sha), 10, C_DIM, false));
            content.addView(row);
        }
    }

    // ------------------------------------------------------------ 预载页

    static class Win {
        LocalDate start, end;
        String title;
        Win(LocalDate s, LocalDate e, String t) { start = s; end = e; title = t; }
    }

    /** 从公告正文里抽出全部时间窗（含节标题），按今天分成未来/进行中/已结束 */
    private List<Win> scanWindows(String body, LocalDate today, List<Win> future,
                                  List<Win> current, int[] pastCount) {
        return scanWindows(body, today, future, current, pastCount, null);
    }

    private List<Win> scanWindows(String body, LocalDate today, List<Win> future,
                                  List<Win> current, int[] pastCount, List<Win> pastList) {
        List<Win> all = new ArrayList<>();
        List<String[]> sections = new ArrayList<>();   // [标题, 正文]
        Matcher sm = Pattern.compile("【([^】]{1,30})】").matcher(body);
        int last = 0;
        String pendingTitle = null;
        int pendingStart = -1;
        while (sm.find()) {
            if (pendingTitle != null) sections.add(new String[]{pendingTitle, body.substring(pendingStart, sm.start())});
            pendingTitle = sm.group(1);
            pendingStart = sm.end();
            last = sm.end();
        }
        if (pendingTitle != null) sections.add(new String[]{pendingTitle, body.substring(pendingStart)});
        if (sections.isEmpty()) sections.add(new String[]{"全文", body});

        Pattern win = Pattern.compile("(\\d{1,2})月(\\d{1,2})日(?:[^\\n。；]{0,24}?至\\s*(\\d{1,2})月(\\d{1,2})日)?");
        for (String[] sec : sections) {
            Matcher m = win.matcher(sec[1]);
            while (m.find()) {
                LocalDate s = mkDate(today, m.group(1), m.group(2));
                LocalDate e = (m.group(3) != null) ? mkDate(today, m.group(3), m.group(4)) : s;
                if (s == null) continue;
                if (e == null) e = s;
                if (e.isBefore(s)) e = s;
                all.add(new Win(s, e, sec[0]));
            }
        }
        // 去重
        List<Win> uniq = new ArrayList<>();
        for (Win w : all) {
            boolean dup = false;
            for (Win u : uniq) {
                if (u.start.equals(w.start) && u.end.equals(w.end) && u.title.equals(w.title)) { dup = true; break; }
            }
            if (!dup) uniq.add(w);
        }
        for (Win w : uniq) {
            if (w.end.isBefore(today)) { pastCount[0]++; if (pastList != null) pastList.add(w); }
            else if (w.start.isAfter(today)) future.add(w);
            else current.add(w);
        }
        return uniq;
    }

    private LocalDate mkDate(LocalDate today, String mo, String da) {
        try {
            int m = Integer.parseInt(mo), d = Integer.parseInt(da);
            LocalDate dt = LocalDate.of(today.getYear(), m, d);
            if (dt.isBefore(today.minusDays(200))) dt = LocalDate.of(today.getYear() + 1, m, d);
            return dt;
        } catch (Exception e) {
            return null;
        }
    }

    private String preloadText() {
        try {
            JSONObject o = parseLoose(pref(this).getString("ann_json", ""));
            LocalDate today = LocalDate.now();
            List<Win> future = new ArrayList<>(), current = new ArrayList<>();
            int[] past = {0};
            scanWindows(o.optString("NeteaseMessage", ""), today, future, current, past);
            StringBuilder sb = new StringBuilder();
            sb.append("【" ).append(o.optString("Title", "")).append("】预载清单 ").append(today).append("\n");
            for (Win w : future) {
                long d = ChronoUnit.DAYS.between(today, w.start);
                sb.append("+").append(d).append("天  ").append(w.start).append(" → ").append(w.end)
                  .append("  ").append(w.title).append("\n");
            }
            for (Win w : current) {
                sb.append("进行中  ").append(w.start).append(" → ").append(w.end).append("  ").append(w.title).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void renderPreload() {
        String raw = pref(this).getString("ann_json", "");
        if (raw.isEmpty()) {
            content.addView(hint("还没有抓到公告，无法判断预载\n点右上角 ↻ 立即抓取"));
            return;
        }
        JSONObject o;
        LocalDate today = LocalDate.now();
        List<Win> future = new ArrayList<>(), current = new ArrayList<>();
        int[] past = {0};
        try {
            o = parseLoose(raw);
            scanWindows(o.optString("NeteaseMessage", ""), today, future, current, past);
        } catch (Exception e) {
            content.addView(hint("解析失败：" + e.getMessage()));
            return;
        }
        content.addView(tv("预载判断", 19, C_TEXT, true));
        TextView m = tv("按正文时间窗自动分类 · 今天 " + today + " · 未来 " + future.size()
                + " / 进行中 " + current.size() + " / 已结束 " + past[0], 10, C_DIM, false);
        m.setPadding(0, dp(6), 0, dp(12));
        content.addView(m);

        if (future.isEmpty()) {
            content.addView(hint("当前没有未来时间窗\n说明公告没有提前预载的活动，或已经全部开始"));
        } else {
            content.addView(tv("已预载、还没开始（提前量越大 = 官方提前得越早）", 12, C_GOLD, true));
            for (Win w : future) {
                long days = ChronoUnit.DAYS.between(today, w.start);
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(12), dp(10), dp(12), dp(10));
                card.setBackground(round(C_CARD2, 12));
                LinearLayout.LayoutParams clp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.topMargin = dp(8);
                card.setLayoutParams(clp);
                card.setTag("card");
                card.addView(tv("+" + days + " 天", 15, C_ACCENT, true));
                card.addView(tv(w.title, 14, C_TEXT, true));
                card.addView(tv(w.start + " → " + w.end, 11, C_DIM, false));
                content.addView(card);
            }
        }

        if (!current.isEmpty()) {
            TextView h2 = tv("进行中", 12, C_TEXT, true);
            h2.setPadding(0, dp(16), 0, dp(4));
            content.addView(h2);
            for (Win w : current) {
                content.addView(tv("· " + w.start + " → " + w.end + "  " + w.title, 13, C_BODY, false));
            }
        }
    }

    private void collectWindows(List<Win> future, List<Win> current, int[] past) {
        try {
            JSONObject o = parseLoose(pref(this).getString("ann_json", ""));
            scanWindows(o.optString("NeteaseMessage", ""), LocalDate.now(), future, current, past);
        } catch (Exception ignored) { }
    }

    private String calendarText() {
        LocalDate today = LocalDate.now();
        List<Win> future = new ArrayList<>(), current = new ArrayList<>();
        int[] past = {0};
        collectWindows(future, current, past);
        StringBuilder sb = new StringBuilder("【活动日历】" + today + "\n");
        sb.append("进行中 ").append(current.size()).append(" · 即将开始 ").append(future.size())
          .append(" · 已结束 ").append(past[0]).append("\n");
        if (!current.isEmpty()) {
            sb.append("\n== 进行中 ==\n");
            for (Win w : current) {
                long left = ChronoUnit.DAYS.between(today, w.end);
                sb.append("· ").append(w.title).append("  ").append(w.start).append(" → ").append(w.end)
                  .append(left <= 0 ? "（今天结束）" : "（还剩 " + left + " 天）").append("\n");
            }
        }
        if (!future.isEmpty()) {
            sb.append("\n== 即将开始 ==\n");
            for (Win w : future) {
                long d = ChronoUnit.DAYS.between(today, w.start);
                sb.append("· ").append(w.title).append("  ").append(w.start).append(" → ").append(w.end)
                  .append("（").append(d).append(" 天后）").append("\n");
            }
        }
        return sb.toString();
    }

    private void renderCalendar() {
        String raw = pref(this).getString("ann_json", "");
        if (raw.isEmpty()) {
            content.addView(hint("还没有抓到公告，无法生成活动日历\n点右上角 ↻ 立即抓取"));
            return;
        }
        LocalDate today = LocalDate.now();
        List<Win> future = new ArrayList<>(), current = new ArrayList<>();
        int[] past = {0};
        List<Win> pastItems = new ArrayList<>();
        try {
            JSONObject o = parseLoose(raw);
            scanWindows(o.optString("NeteaseMessage", ""), today, future, current, past, pastItems);
        } catch (Exception e) { }

        content.addView(tv("活动日历", 19, C_TEXT, true));
        TextView m = tv("数据源=公告正文 · 今天 " + today + " · 进行中 " + current.size()
                + " / 即将开始 " + future.size() + " / 已结束 " + past[0], 10, C_DIM, false);
        m.setPadding(0, dp(6), 0, dp(12));
        content.addView(m);

        if (current.isEmpty() && future.isEmpty()) {
            content.addView(hint("公告里没有解析到活动时间窗\n可能这期公告只写了版本说明"));
            return;
        }

        if (!current.isEmpty()) {
            TextView h1 = tv("进行中（还剩多少天结束）", 12, C_GOLD, true);
            h1.setPadding(0, 0, 0, dp(2));
            content.addView(h1);
            for (Win w : current) {
                long left = ChronoUnit.DAYS.between(today, w.end);
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(12), dp(10), dp(12), dp(10));
                card.setBackground(round(C_CARD2, 12));
                LinearLayout.LayoutParams clp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.topMargin = dp(8);
                card.setLayoutParams(clp);
                card.setTag("card");
                card.addView(tv(left <= 0 ? "今天结束" : "还剩 " + left + " 天", 13, 0xFF7BE3A5, true));
                card.addView(tv(w.title, 14, C_TEXT, true));
                card.addView(tv(w.start + " → " + w.end, 11, C_DIM, false));
                content.addView(card);
            }
        }

        if (!future.isEmpty()) {
            TextView h2 = tv("即将开始", 12, C_GOLD, true);
            h2.setPadding(0, dp(16), 0, dp(2));
            content.addView(h2);
            for (Win w : future) {
                long d = ChronoUnit.DAYS.between(today, w.start);
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(12), dp(10), dp(12), dp(10));
                card.setBackground(round(C_CARD2, 12));
                LinearLayout.LayoutParams clp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.topMargin = dp(8);
                card.setLayoutParams(clp);
                card.setTag("card");
                card.addView(tv(d + " 天后开始", 13, C_ACCENT, true));
                card.addView(tv(w.title, 14, C_TEXT, true));
                card.addView(tv(w.start + " → " + w.end, 11, C_DIM, false));
                content.addView(card);
            }
        }

        if (!pastItems.isEmpty()) {
            TextView h3 = tv("已结束（" + pastItems.size() + "）", 12, C_DIM, true);
            h3.setPadding(0, dp(16), 0, dp(2));
            content.addView(h3);
            java.util.Collections.sort(pastItems, (a, b) -> b.start.compareTo(a.start));
            int n = 0;
            for (Win w : pastItems) {
                if (n++ >= 8) break;
                content.addView(tv("· " + w.start + " → " + w.end + "  " + w.title, 11, 0xFF7A8699, false));
            }
            if (pastItems.size() > 8) content.addView(tv("…还有 " + (pastItems.size() - 8) + " 条", 10, C_DIM, false));
        }
    }

    static void requestNotifPermission(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && c instanceof Activity) {
                Activity a = (Activity) c;
                if (a.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    a.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001);
                }
            }
        } catch (Throwable ignore) {
        }
    }

    // ------------------------------------------------------------ 碎片页（红石 / 黑石，离线算法）

    /** 一天的碎片信息（国服社区算法；非官方）。 */
    static final class ShardDay {
        boolean hasShard;
        boolean isRed;
        String reason = "";
        String place = "";
        double reward;
        int[][] times;
        int daysAhead;
        int isoWeekday;
    }

    static final String[] SHARD_MAPS_TUE = {"云野·蝴蝶平原", "雨林·雨林小溪", "霞谷·溜冰场", "暮土·破败神庙", "禁阁·星光沙漠"};
    static final String[] SHARD_MAPS_WED = {"云野·云中仙乡", "雨林·遗迹", "霞谷·溜冰场", "暮土·战场", "禁阁·星光沙漠"};
    static final String[] SHARD_MAPS_FRI = {"云野·鸟巢", "雨林·大树屋", "霞谷·圆梦村", "暮土·螃蟹场", "禁阁·水母湾"};
    static final String[] SHARD_MAPS_SAT = {"云野·洞穴", "雨林·雨林花园", "霞谷·圆梦村", "暮土·墓地", "禁阁·水母湾"};
    static final String[] SHARD_MAPS_SUN = {"云野·圣岛", "雨林·高处空地", "霞谷·隐士山谷", "暮土·遗忘方舟", "禁阁·水母湾"};
    static final double[] SHARD_REW_FRI = {2.5, 3.5, 2.5, 2.5, 3.5};
    static final double[] SHARD_REW_SAT = {2, 2.5, 2.5, 2, 3.5};
    static final String[] SHARD_WD = {"一", "二", "三", "四", "五", "六", "日"};

    static int shardColor(boolean red) {
        return red ? 0xFFFF6B6B : 0xFF9AA4B2;   // 红石=红，黑石=冷灰
    }

    static String trimNum(double v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.valueOf(v);
    }

    static String shardWin(int[] w) {
        return String.format("%02d:%02d–%s", w[0], w[1],
                w[2] == 24 ? "次日00:00" : String.format("%02d:%02d", w[2], w[3]));
    }

    static ShardDay shardDay(Calendar base) {
        Calendar c = (Calendar) base.clone();
        int day = c.get(Calendar.DAY_OF_MONTH);
        int dow = c.get(Calendar.DAY_OF_WEEK);
        int iso = dow == Calendar.SUNDAY ? 7 : dow - 1;
        ShardDay s = new ShardDay();
        s.isoWeekday = iso;
        boolean noWeek = iso == 1 || iso == 4;
        boolean noHalf = day <= 15 ? (iso == 3 || iso == 5) : (iso == 2 || iso == 6);
        s.hasShard = !noWeek && !noHalf;
        if (!s.hasShard) {
            s.reason = noWeek ? "周一 / 周四固定无碎片"
                    : (day <= 15 ? "1–15 号规则：周三 / 周五无碎片"
                    : "16 号后规则：周二 / 周六无碎片");
            return s;
        }
        int ri = (day + 2) % 5;
        if (iso == 2) {
            s.isRed = false;
            s.place = SHARD_MAPS_TUE[ri];
            s.times = new int[][]{{9, 8, 10, 0}, {14, 8, 15, 0}, {19, 8, 20, 0}};
        } else if (iso == 3) {
            s.isRed = false;
            s.place = SHARD_MAPS_WED[ri];
            s.times = new int[][]{{9, 8, 10, 0}, {15, 8, 16, 0}, {19, 8, 20, 0}};
        } else if (iso == 5) {
            s.isRed = true;
            s.place = SHARD_MAPS_FRI[ri];
            s.times = new int[][]{{11, 8, 12, 0}, {17, 8, 18, 0}, {23, 8, 24, 0}};
            s.reward = SHARD_REW_FRI[ri];
        } else if (iso == 6) {
            s.isRed = true;
            s.place = SHARD_MAPS_SAT[ri];
            s.times = new int[][]{{10, 8, 11, 0}, {14, 8, 15, 0}, {22, 8, 23, 0}};
            s.reward = SHARD_REW_SAT[ri];
        } else {
            s.isRed = true;
            s.place = SHARD_MAPS_SUN[ri];
            s.times = new int[][]{{7, 8, 8, 0}, {13, 8, 14, 0}, {19, 8, 20, 0}};
            s.reward = 3.5;
        }
        return s;
    }

    static ShardDay nextShard(Calendar base, int horizon) {
        for (int i = 1; i <= horizon; i++) {
            Calendar c = (Calendar) base.clone();
            c.add(Calendar.DAY_OF_MONTH, i);
            ShardDay s = shardDay(c);
            if (s.hasShard) {
                s.daysAhead = i;
                return s;
            }
        }
        return null;
    }

    private void scheduleShardTick() {
        if (shardTimer) return;
        shardTimer = true;
        ui.postDelayed(() -> {
            shardTimer = false;
            if (tab == 6) render();
        }, 60000L);
    }

    private void renderShard() {
        scheduleShardTick();
        try { scheduleShardAlarms(this); } catch (Throwable ignore) {}
        final Calendar now = Calendar.getInstance();
        ShardDay t = shardDay(now);
        int accent = t.hasShard ? shardColor(t.isRed) : C_DIM;

        content.addView(section("碎片 · 今天"));

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(16), dp(14), dp(16), dp(16));
        GradientDrawable hg = round(C_CARD2, 16);
        hg.setStroke(dp(1), 0x332C5A78);
        hero.setBackground(hg);
        content.addView(hero);

        int badgeBg = t.hasShard ? accent : 0x338C97B3;
        int badgeFg = t.hasShard ? 0xFF12161F : C_TEXT;
        TextView badge = tv(t.hasShard ? (t.isRed ? "红石" : "黑石") : "今天无碎片", 26, badgeFg, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(10), dp(12), dp(10), dp(12));
        badge.setBackground(round(badgeBg, 14));
        hero.addView(badge, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String dateLine = String.format("%02d-%02d 周%s",
                now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH),
                SHARD_WD[t.isoWeekday - 1]);
        TextView sub = tv(dateLine + (t.hasShard ? " · " + t.place : " · " + t.reason), 14, C_TEXT, false);
        sub.setPadding(0, dp(12), 0, 0);
        hero.addView(sub);

        if (t.hasShard) {
            int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
            for (int[] w : t.times) {
                int s1 = w[0] * 60 + w[1];
                int e1 = w[2] == 24 ? 24 * 60 : w[2] * 60 + w[3];
                String stat;
                int col;
                if (nowMin < s1) {
                    stat = "未开始 · " + (s1 - nowMin) + " 分钟后";
                    col = C_DIM;
                } else if (nowMin >= e1) {
                    stat = "已结束";
                    col = C_DIM;
                } else {
                    stat = "进行中 · 剩 " + (e1 - nowMin) + " 分钟";
                    col = accent;
                }
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, dp(10), 0, 0);
                row.addView(tv(shardWin(w), 15, col == accent ? accent : C_TEXT, true),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView st = tv(stat, 12, col, false);
                st.setGravity(Gravity.END);
                row.addView(st);
                hero.addView(row);
            }
            if (t.isRed) {
                TextView rw = tv("升华蜡烛 ×" + trimNum(t.reward) + "（烧红石）", 12, C_GOLD, false);
                rw.setPadding(0, dp(10), 0, 0);
                hero.addView(rw);
            }
        } else {
            ShardDay nx = nextShard(now, 21);
            if (nx != null) {
                String line = "最近一次：" + nx.daysAhead + " 天后（" + (nx.isRed ? "红石" : "黑石")
                        + " · " + nx.place + "）" + String.format("%02d:%02d", nx.times[0][0], nx.times[0][1]) + " 开始";
                TextView nv = tv(line, 13, shardColor(nx.isRed), false);
                nv.setPadding(0, dp(12), 0, 0);
                hero.addView(nv);
            }
        }

        content.addView(section("未来 7 天"));
        for (int i = 0; i < 7; i++) {
            Calendar c = (Calendar) now.clone();
            c.add(Calendar.DAY_OF_MONTH, i);
            ShardDay d = shardDay(c);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackground(round(C_CARD2, 12));
            LinearLayout.LayoutParams rlp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(6);
            row.setLayoutParams(rlp);

            row.addView(tv(d.hasShard ? "●" : "○", 14,
                    d.hasShard ? shardColor(d.isRed) : C_DIM, false));
            String label = String.format("%02d-%02d 周%s",
                    c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                    SHARD_WD[d.isoWeekday - 1]);
            String body = label + "  " + (d.hasShard
                    ? ((d.isRed ? "红石" : "黑石") + " · " + d.place) : "无碎片");
            TextView info = tv(body, 13, d.hasShard ? C_TEXT : C_DIM, false);
            info.setPadding(dp(8), 0, 0, 0);
            row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (d.hasShard) {
                row.addView(tv(String.format("%02d:%02d", d.times[0][0], d.times[0][1]), 12, C_DIM, false));
            }
            content.addView(row);
        }
        boolean remind = pref(this).getBoolean("shard_remind", true);
        content.addView(tv(remind
                        ? "⏰ 已开启：红石 / 黑石开始前 10 分钟提醒" + (canExact(this) ? "" : "（未授予精确闹钟权限）")
                        : "⏰ 碎片提醒已关闭（设置 → 碎片提醒）",
                12, remind ? C_ACCENT : C_DIM, false));
        content.addView(hint("社区算法（非官方）：周一/周四固定无碎片；1–15 号另停周三/周五，16 号后另停周二/周六。"
                + "红石给升华蜡烛（2 / 2.5 / 3.5），黑石只给白蜡烛。最终以游戏内公告为准。"));
    }

    private String shardText() {
        Calendar now = Calendar.getInstance();
        StringBuilder sb = new StringBuilder("【光·遇】碎片日程（国服）\n");
        for (int i = 0; i < 7; i++) {
            Calendar c = (Calendar) now.clone();
            c.add(Calendar.DAY_OF_MONTH, i);
            ShardDay d = shardDay(c);
            String label = String.format("%02d-%02d 周%s",
                    c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                    SHARD_WD[d.isoWeekday - 1]);
            if (!d.hasShard) {
                sb.append(label).append(" 无碎片（").append(d.reason).append("）\n");
                continue;
            }
            sb.append(label).append(' ').append(d.isRed ? "红石" : "黑石").append(" · ").append(d.place);
            if (d.isRed) sb.append(" ｜ 升华×").append(trimNum(d.reward));
            sb.append('\n');
            for (int[] w : d.times) sb.append("  ").append(shardWin(w)).append('\n');
        }
        sb.append("说明 社区算法（非官方），红石有升华蜡烛、黑石没有；以游戏内公告为准");
        return sb.toString();
    }

    private void renderSettings() {        final SharedPreferences sp = pref(this);

        content.addView(section("官方动态源"));
        String[] srcNames = {"TapTap 官方帖（App 内可抓）", "B 站官方动态（App 内可抓）", "官网新闻 / 公告（App 内可抓）", "微博官方（当前网络被风控，点此手动打开）"};
        String[] srcUrls = {"https://www.taptap.cn/app/62448", "https://space.bilibili.com/211700578/dynamic", "https://sky.163.com/news/", "https://weibo.com/skygy"};
        int[] srcColors = {0xFF7BE3A5, 0xFF7BE3A5, 0xFF7BE3A5, C_DIM};
        for (int i = 0; i < srcNames.length; i++) {
            final String u = srcUrls[i];
            TextView b = bigBtn(srcNames[i], srcColors[i]);
            b.setOnClickListener(v -> open(u));
            content.addView(b);
        }
        content.addView(section("云中继（可选，补微博 / 抖音 / 小红书）"));
        EditText et = new EditText(this);
        et.setHint("https://<user>.github.io/<repo>/feed.json");
        et.setText(sp.getString("relay_url", U_RELAY_DEFAULT));
        et.setTextSize(13);
        et.setTextColor(C_TEXT);
        et.setHintTextColor(C_DIM);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        et.setBackground(round(C_CARD2, 10));
        et.setPadding(dp(10), dp(8), dp(10), dp(8));
        content.addView(et);
        TextView saveRelay = bigBtn("保存中继地址", C_ACCENT);
        saveRelay.setOnClickListener(v -> {
            pref(MainActivity.this).edit().putString("relay_url", et.getText().toString().trim()).apply();
            toast(et.getText().toString().trim().isEmpty() ? "已清空中继地址" : "已保存，下次抓取生效");
            render();
        });
        content.addView(saveRelay);
        String ferr = sp.getString("feed_err", "");
        if (!ferr.isEmpty()) {
            TextView fe = tv("上次抓取失败源：" + ferr.trim(), 11, C_DIM, false);
            fe.setPadding(0, dp(6), 0, 0);
            content.addView(fe);
        }

                content.addView(section("每日任务提醒"));
        boolean dOn = sp.getBoolean("daily_remind", false);
        TextView dbtn = bigBtn(dOn ? "每日任务提醒：已开启" : "每日任务提醒：已关闭", dOn ? C_ACCENT : C_DIM);
        dbtn.setOnClickListener(v -> {
            boolean now = !pref(MainActivity.this).getBoolean("daily_remind", false);
            pref(MainActivity.this).edit().putBoolean("daily_remind", now).apply();
            toast(now ? "每天到点会提醒你做每日任务" : "已关闭每日任务提醒");
            render();
        });
        content.addView(dbtn);
        EditText dt = new EditText(this);
        dt.setHint("提醒时间，例如 08:00");
        dt.setText(sp.getString("daily_time", "08:00"));
        dt.setTextSize(13);
        dt.setTextColor(C_TEXT);
        dt.setHintTextColor(C_DIM);
        dt.setSingleLine(true);
        dt.setInputType(InputType.TYPE_CLASS_DATETIME);
        dt.setBackground(round(C_CARD2, 10));
        dt.setPadding(dp(10), dp(6), dp(10), dp(6));
        content.addView(dt);
        TextView dsave = bigBtn("保存提醒时间", C_ACCENT);
        dsave.setOnClickListener(v -> {
            pref(MainActivity.this).edit().putString("daily_time", dt.getText().toString().trim()).apply();
            toast("已保存：" + dt.getText().toString().trim() + " 提醒");
            render();
        });
        content.addView(dsave);
        TextView dnote = tv("到点后通知栏提醒，当天只提醒一次；受系统省电策略影响，建议同时开启「常驻监控」。", 11, C_DIM, false);
        dnote.setPadding(0, dp(6), 0, dp(6));
        content.addView(dnote);

content.addView(section("检测频率"));
        String[] names = {"15 分钟", "30 分钟", "60 分钟", "120 分钟"};
        int[] vals = {15, 30, 60, 120};
        int cur = sp.getInt("interval", 15);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rw = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rw.topMargin = dp(8);
        row.setLayoutParams(rw);
        for (int i = 0; i < vals.length; i++) {
            final int v = vals[i];
            boolean on = v == cur;
            TextView b = tv(names[i], 12, on ? C_ACCENT : C_DIM, true);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, dp(9), 0, dp(9));
            GradientDrawable g = round(on ? C_CARD2 : C_CARD2, 10);
            if (on) g.setStroke(dp(1), 0xFF2C5A78);
            b.setBackground(g);
            b.setOnClickListener(x -> {
                sp.edit().putInt("interval", v).apply();
                schedule(MainActivity.this);
                render();
                toast("已设为 " + v + " 分钟");
            });
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) bl.leftMargin = dp(8);
            row.addView(b, bl);
        }
        content.addView(row);

        content.addView(section("常驻监控（24h 更准时）"));
        boolean fgs = sp.getBoolean("fgs", false);
        TextView f = bigBtn(fgs ? "● 常驻监控已开启（每 5 分钟）" : "○ 开启常驻监控", fgs ? 0xFF7BE3A5 : C_TEXT);
        f.setOnClickListener(x -> {
            boolean now = pref(MainActivity.this).getBoolean("fgs", false);
            if (now) {
                pref(MainActivity.this).edit().putBoolean("fgs", false).apply();
                stopService(new Intent(MainActivity.this, MonService.class));
                toast("已关闭常驻监控");
            } else {
                pref(MainActivity.this).edit().putBoolean("fgs", true).apply();
                Intent it = new Intent(MainActivity.this, MonService.class);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(it);
                else startService(it);
                toast("已开启常驻监控");
            }
            render();
        });
        content.addView(f);

        boolean fast = sp.getBoolean("fast", false);
        TextView ff = bigBtn(fast ? "⚡ 极速模式已开启（60 秒，需常驻监控）" : "○ 开启极速模式（60 秒）", fast ? 0xFF7BE3A5 : C_TEXT);
        ff.setOnClickListener(x -> {
            boolean now = pref(MainActivity.this).getBoolean("fast", false);
            pref(MainActivity.this).edit().putBoolean("fast", now ? false : true).apply();
            if (pref(MainActivity.this).getBoolean("fgs", false)) {
                stopService(new Intent(MainActivity.this, MonService.class));
                Intent it = new Intent(MainActivity.this, MonService.class);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(it);
                else startService(it);
            }
            toast(now ? "极速模式已关闭" : "极速模式已开启：每 60 秒检测");
            render();
        });
        content.addView(ff);

        content.addView(section("动态页"));
        boolean autoExp = sp.getBoolean("auto_expand", true);
        TextView ae = bigBtn(autoExp ? "● 长文自动展开：开（默认整篇显示）" : "○ 长文自动展开：关（先显示摘要）",
                autoExp ? 0xFF7BE3A5 : C_TEXT);
        ae.setOnClickListener(x -> {
            boolean now = pref(MainActivity.this).getBoolean("auto_expand", true);
            pref(MainActivity.this).edit().putBoolean("auto_expand", !now).apply();
            toast(now ? "已关闭：长动态先折叠，点「展开全文」再看" : "已开启：长动态默认整篇展开");
            render();
        });
        content.addView(ae);

        content.addView(section("碎片提醒"));
        boolean sr = sp.getBoolean("shard_remind", true);
        TextView srb = bigBtn(sr ? "● 碎片开始前 10 分钟提醒：开（红石 / 黑石）"
                        : "○ 碎片开始前 10 分钟提醒：关",
                sr ? 0xFF7BE3A5 : C_TEXT);
        srb.setOnClickListener(x -> {
            boolean now = pref(MainActivity.this).getBoolean("shard_remind", true);
            pref(MainActivity.this).edit().putBoolean("shard_remind", !now).apply();
            if (!now && !canExact(MainActivity.this)) {
                toast("还需允许「闹钟和提醒」权限才能准点提醒");
                requestExact(MainActivity.this);
            }
            scheduleShardAlarms(MainActivity.this);
            toast(now ? "已关闭碎片提醒" : "已开启：红石 / 黑石开始前 10 分钟提醒");
            render();
        });
        content.addView(srb);

        content.addView(section("变更取证（自动存档）"));
        boolean cap = sp.getBoolean("capture", true);
        TextView cp = bigBtn(cap ? "● 官方接口快照：开（零依赖，自带证据）" : "○ 官方接口快照：关", cap ? 0xFF7BE3A5 : C_TEXT);
        cp.setOnClickListener(x -> {
            boolean now = pref(MainActivity.this).getBoolean("capture", true);
            pref(MainActivity.this).edit().putBoolean("capture", !now).apply();
            toast(now ? "已关闭：变更时不再抓官方接口快照" : "已开启：公告一变就抓完整响应头+正文");
            render();
        });
        content.addView(cp);
        TextView tb = bigBtn("立即取证一次", C_TEXT);
        tb.setOnClickListener(x -> {
            final String tag = "manual-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(new Date());
            toast("正在取证…");
            final Context ac = getApplicationContext();
            new Thread(() -> {
                captureLocal(ac, tag);
                final boolean ok = pref(ac).getString("evidence_err", "").isEmpty();
                runOnUiThread(() -> { render(); showEvidenceDialog(ok); });
            }).start();
        });
        content.addView(tb);
        String lastEv = sp.getString("last_evidence", "");
        String evErr = sp.getString("evidence_err", "");
        if (!lastEv.isEmpty()) {
            content.addView(tv("最近取证：" + lastEv + (evErr.isEmpty() ? " ✓" : "（失败：" + evErr + "）"), 10, C_DIM, false));
        }

        // 深色模式（三态）
        content.addView(section("深色模式"));
        int tmode = themeMode(this);
        String[] tnames = {"跟随系统", "浅色", "深色"};
        int[] tvals = {THEME_AUTO, THEME_LIGHT, THEME_DARK};
        LinearLayout trow = new LinearLayout(this);
        trow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams trlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trlp.topMargin = dp(8);
        trow.setLayoutParams(trlp);
        for (int i = 0; i < tnames.length; i++) {
            final int v = tvals[i];
            boolean on = v == tmode;
            TextView b = tv(tnames[i], 12, on ? C_ACCENT : C_DIM, true);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, dp(9), 0, dp(9));
            GradientDrawable g = round(on ? C_CARD2 : C_CARD, 10);
            if (on) g.setStroke(dp(1), C_ACCENT);
            b.setBackground(g);
            b.setOnClickListener(x -> {
                pref(MainActivity.this).edit().putInt("theme_mode", v).apply();
                applyTheme(MainActivity.this);
                recreate();
            });
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) bl.leftMargin = dp(8);
            trow.addView(b, bl);
        }
        content.addView(trow);

        // 通知过滤（白/黑名单 + 免打扰）
        content.addView(section("通知过滤（关键词白/黑名单 + 免打扰）"));
        EditText kwWhite = editBox(sp.getString("nf_kw_white", ""), "白名单关键词（逗号分隔，空=全部）");
        EditText kwBlack = editBox(sp.getString("nf_kw_black", ""), "黑名单关键词（逗号分隔）");
        content.addView(kwWhite);
        content.addView(kwBlack);
        TextView saveKw = bigBtn("保存通知关键词", C_ACCENT);
        saveKw.setOnClickListener(x -> {
            sp.edit().putString("nf_kw_white", kwWhite.getText().toString().trim())
                    .putString("nf_kw_black", kwBlack.getText().toString().trim()).apply();
            toast("已保存通知过滤关键词");
            render();
        });
        content.addView(saveKw);
        boolean dnd = sp.getBoolean("nf_dnd", true);
        TextView dndBtn = bigBtn(dnd ? "● 免打扰已开启" : "○ 免打扰已关闭", dnd ? 0xFF7BE3A5 : C_TEXT);
        dndBtn.setOnClickListener(x -> {
            boolean now = pref(MainActivity.this).getBoolean("nf_dnd", true);
            pref(MainActivity.this).edit().putBoolean("nf_dnd", !now).apply();
            toast(now ? "已关闭免打扰" : "已开启免打扰");
            render();
        });
        content.addView(dndBtn);
        LinearLayout dndRow = new LinearLayout(this);
        dndRow.setOrientation(LinearLayout.HORIZONTAL);
        dndRow.addView(editBox(String.valueOf(sp.getInt("nf_dnd_start", 23)), "开始小时(0-23)"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        dlp.leftMargin = dp(8);
        EditText eEnd = editBox(String.valueOf(sp.getInt("nf_dnd_end", 7)), "结束小时(0-23)");
        dndRow.addView(eEnd, dlp);
        content.addView(dndRow);
        TextView saveDnd = bigBtn("保存免打扰时段", C_TEXT);
        saveDnd.setOnClickListener(x -> {
            try {
                int s = Integer.parseInt(((EditText) dndRow.getChildAt(0)).getText().toString().trim());
                int e = Integer.parseInt(eEnd.getText().toString().trim());
                sp.edit().putInt("nf_dnd_start", Math.max(0, Math.min(23, s)))
                        .putInt("nf_dnd_end", Math.max(0, Math.min(23, e))).apply();
                toast("已保存免打扰时段");
            } catch (Exception ex) {
                toast("请输入 0-23 的整数小时");
            }
            render();
        });
        content.addView(saveDnd);

        // 源健康
        content.addView(section("源健康（最近一次抓取）"));
        String health = sp.getString("src_health", "");
        if (health.isEmpty()) {
            content.addView(tv("还没有抓取记录", 11, C_DIM, false));
        } else {
            try {
                JSONObject h = new JSONObject(health);
                java.util.Iterator<String> it = h.keys();
                while (it.hasNext()) {
                    String name = it.next();
                    JSONObject hrow = h.optJSONObject(name);
                    if (hrow == null) continue;
                    boolean ok = hrow.optBoolean("ok", false);
                    String line = (ok ? "✅ " : "⚠️ ") + name + "  " + (ok ? "正常" : "失败")
                            + (ok ? " · " + hrow.optInt("count", 0) + " 条" : "")
                            + (hrow.optLong("elapsed_ms", 0) > 0 ? " · " + hrow.optLong("elapsed_ms") + "ms" : "")
                            + (hrow.optString("last_success", "").isEmpty() ? "" : " · 上次成功 " + hrow.optString("last_success"))
                            + (ok ? "" : " · " + hrow.optString("error", ""));
                    TextView hl = tv(line, 11, ok ? C_BODY : 0xFFFF9E9E, false);
                    hl.setPadding(0, dp(3), 0, dp(3));
                    content.addView(hl);
                }
            } catch (Exception ignore) {
            }
        }

        content.addView(section("关于"));
        content.addView(tv("SkyNews（光遇公告阅读器）v3.1 (13)", 13, C_TEXT, true));
        content.addView(tv("数据源：官方公告/新闻、TapTap、B站、云中继；抓取失败会保留旧条目（灰显）", 11, C_DIM, false));
        content.addView(tv("构建：无 Gradle（javac + aapt2 + d8 + apksigner）", 11, C_DIM, false));

        content.addView(section("其它"));
        TextView clear = bigBtn("清空变更历史", 0xFFFF9E9E);
        clear.setOnClickListener(x -> {
            sp.edit().putString("hist", "[]").apply();
            toast("历史已清空");
            render();
        });
        content.addView(clear);

        TextView test = bigBtn("立即检测一次", C_ACCENT);
        test.setOnClickListener(x -> refresh(true));
        content.addView(test);
    }

    private EditText editBox(String text, String hint) {
        EditText e = new EditText(this);
        e.setText(text);
        e.setHint(hint);
        e.setTextSize(13);
        e.setTextColor(C_TEXT);
        e.setHintTextColor(C_DIM);
        e.setSingleLine(true);
        e.setBackground(round(C_CARD2, 10));
        e.setPadding(dp(10), dp(7), dp(10), dp(7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        e.setLayoutParams(lp);
        return e;
    }

    private TextView section(String s) {
        TextView t = tv(s, 12, C_DIM, true);
        t.setPadding(0, dp(16), 0, 0);
        return t;
    }

    private TextView bigBtn(String s, int color) {
        TextView t = tv(s, 14, color, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(13), 0, dp(13));
        t.setBackground(round(C_CARD2, 12));
        LinearLayout.LayoutParams lp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView hint(String s) {
        TextView t = tv(s, 13, C_DIM, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(40), 0, dp(40));
        t.setLineSpacing(dp(4), 1f);
        return t;
    }

    private void updateStatus() {
        SharedPreferences sp = pref(this);
        String srv = sp.getString("server_date", "");
        long lastOk = sp.getLong("feed_check", 0);
        String okStr = lastOk > 0 ? new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(lastOk)) : "—";
        tvStatus.setText("上次成功 " + okStr + " · 检测 " + sp.getString("last_check", "—")
                + (srv.isEmpty() ? "" : " · 服务器 " + srv)
                + " · 每 " + (sp.getBoolean("fast", false) && sp.getBoolean("fgs", false) ? "1 分钟" : sp.getInt("interval", 15) + " 分钟")
                + (sp.getString("official_lm", "").isEmpty() ? "" : " · 官方 " + shortTime(sp.getString("official_lm", ""))));
    }

    // ------------------------------------------------------------ 抓取

    private void refresh(final boolean manual) {
        maybeDailyReminder(this);
        if (busy) {
            if (manual) toast("正在抓取…");
            return;
        }
        if (!online(this)) {
            if (manual) toast("无网络，已跳过抓取");
            updateStatus();
            return;
        }
        busy = true;
        startSpin();
        if (manual) tvRefresh.setText("…");
        pool.execute(() -> {
            String err = null;
            try {
                String ann = http(U_ANN);
                rememberOfficial(this);
                String age = http(U_AGE);
                String srv = "";
                try {
                    JSONObject t = new JSONObject(http(U_TIME));
                    srv = new SimpleDateFormat("MM/dd HH:mm", Locale.CHINA)
                            .format(new Date(t.optLong("server_time", 0) * 1000L));
                } catch (Exception ignore) {
                }
                applyStatic(this, ann, age, srv);
                try {
                    pullFeed(this, ann);
                } catch (Throwable fe) {
                    // 动态源失败不影响公告主流程
                }
            } catch (Exception e) {
                err = e.toString();
            }
            final String ferr = err;
            ui.post(() -> {
                busy = false;
                stopSpin();
                tvRefresh.setText("↻");
                if (ferr != null) {
                    toast("抓取失败：" + ferr);
                    render();
                } else {
                    render();
                    if (manual) toast("已更新");
                }
            });
        });
    }

    /** 抓取结果入库：内容变化才记历史 + 发通知 */
    static void applyStatic(Context ctx, String ann, String age, String srv) throws Exception {
        SharedPreferences sp = pref(ctx);
        JSONObject o = parseLoose(ann);
        String title = o.optString("Title", "");
        String body = o.optString("NeteaseMessage", "");
        String sha = sha256(title + "\u0001" + body);
        String old = sp.getString("ann_sha", "");
        String nowStr = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date());
        SharedPreferences.Editor e = sp.edit();
        e.putString("ann_json", ann);
        e.putString("age_json", age);
        e.putString("last_check", nowStr);
        if (!srv.isEmpty()) e.putString("server_date", srv);
        if (old.isEmpty()) {
            e.putString("ann_sha", sha);
        } else if (!old.equals(sha)) {
            e.putString("ann_sha", sha);
            e.putString("last_change", nowStr);
            JSONArray arr = new JSONArray(sp.getString("hist", "[]"));
            JSONObject h = new JSONObject();
            h.put("t", nowStr);
            h.put("title", title);
            h.put("sha", sha);
            arr.put(h);
            while (arr.length() > 30) arr.remove(0);
            e.putString("hist", arr.toString());
            e.apply();
            final String capTag = "change-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(new Date());
            final Context capCtx = ctx.getApplicationContext();
            new Thread(() -> captureLocal(capCtx, capTag)).start();
            notifyAnn(ctx, title, body);
            return;
        }
        e.apply();
    }

    static JSONObject parseLoose(String raw) throws Exception {
        String s = raw == null ? "" : raw.trim();
        try {
            return new JSONObject(s);
        } catch (Exception ex) {
            return new JSONObject(s.replaceAll(",\\s*([}\\]])", "$1"));
        }
    }

    /** 本地快照：不依赖任何抓包软件，直接抓官方接口的完整响应头+正文存证 */
    static void captureLocal(Context ctx, String tag) {
        try {
            java.io.File dir = new java.io.File(new java.io.File(ctx.getFilesDir(), "evidence"), tag);
            if (!dir.exists()) dir.mkdirs();
            StringBuilder meta = new StringBuilder();
            meta.append("tag=").append(tag).append("\n");
            meta.append("at=").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date())).append("\n");
            String annBody = fetchTo(ctx, U_ANN, dir, "announcement", meta);
            fetchTo(ctx, U_AGE, dir, "age", meta);
            fetchTo(ctx, "https://mgbsdk.matrix.netease.com/ma75/sdk/server_time", dir, "server_time", meta);
            String sha = annBody == null ? "" : sha256(annBody);
            meta.append("ann_sha256=").append(sha).append("\n");
            meta.append("ann_bytes=").append(annBody == null ? 0 : annBody.length()).append("\n");
            // 与上一次存档比对，给出「新增/删除行」
            SharedPreferences sp = pref(ctx);
            String prev = sp.getString("ev_prev_body", "");
            if (!prev.isEmpty() && annBody != null && !prev.equals(annBody)) {
                java.util.HashSet<String> a = new java.util.HashSet<>(java.util.Arrays.asList(prev.split("\n")));
                java.util.HashSet<String> b = new java.util.HashSet<>(java.util.Arrays.asList(annBody.split("\n")));
                int add = 0, del = 0;
                StringBuilder diff = new StringBuilder();
                for (String line : b) if (!a.contains(line)) { add++; if (diff.length() < 600) diff.append("+ ").append(line, 0, Math.min(line.length(), 120)).append("\n"); }
                for (String line : a) if (!b.contains(line)) { del++; if (diff.length() < 1200) diff.append("- ").append(line, 0, Math.min(line.length(), 120)).append("\n"); }
                meta.append("diff_added=").append(add).append("\n").append("diff_removed=").append(del).append("\n");
                writeText(new java.io.File(dir, "diff.txt"), diff.toString());
            }
            if (annBody != null) sp.edit().putString("ev_prev_body", annBody).apply();
            writeText(new java.io.File(dir, "meta.txt"), meta.toString());
            sp.edit().putString("last_evidence", tag).putString("evidence_err", "").apply();
        } catch (Throwable t) {
            pref(ctx).edit().putString("evidence_err", String.valueOf(t)).apply();
        }
    }

    /** GET 一个 URL，把完整响应头写成 <name>.hdr、正文写成 <name>.body，返回正文 */
    static String fetchTo(Context ctx, String url, java.io.File dir, String name, StringBuilder meta) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(9000);
            c.setRequestProperty("Cache-Control", "no-cache");
            c.setRequestProperty("User-Agent", "skynews/2.0");
            int code = c.getResponseCode();
            StringBuilder hdr = new StringBuilder("HTTP " + code + " " + url + "\n");
            for (java.util.Map.Entry<String, java.util.List<String>> en : c.getHeaderFields().entrySet()) {
                if (en.getKey() == null) continue;
                hdr.append(en.getKey()).append(": ").append(en.getValue()).append("\n");
            }
            writeText(new java.io.File(dir, name + ".hdr"), hdr.toString());
            meta.append(name).append("_code=").append(code).append("\n");
            meta.append(name).append("_last_modified=").append(String.valueOf(c.getHeaderField("Last-Modified"))).append("\n");
            meta.append(name).append("_etag=").append(String.valueOf(c.getHeaderField("ETag"))).append("\n");
            java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (in != null && (n = in.read(buf)) > 0) bo.write(buf, 0, n);
            String body = new String(bo.toByteArray(), "UTF-8");
            writeText(new java.io.File(dir, name + ".body"), body);
            return body;
        } catch (Throwable t) {
            meta.append(name).append("_error=").append(String.valueOf(t)).append("\n");
            return null;
        }
    }

    static void writeText(java.io.File f, String s) {
        try {
            java.io.FileWriter w = new java.io.FileWriter(f);
            w.write(s == null ? "" : s);
            w.close();
        } catch (Throwable ignored) {
        }
    }

    /** 记录官方 Last-Modified，用于展示"官方什么时候推的新公告" */
    static void rememberOfficial(Context c) {
        try {
            String lm = NET_LASTMOD;
            if (lm == null || lm.isEmpty()) return;
            SharedPreferences sp = pref(c);
            if (!lm.equals(sp.getString("official_lm", ""))) {
                sp.edit().putString("official_lm", lm).apply();
            }
        } catch (Throwable ignore) { }
    }

    static String http(String url) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return httpOnce(url);
            } catch (Exception e) {
                last = e;
                if (attempt < 2) {
                    try { Thread.sleep(400L * (attempt + 1)); } catch (InterruptedException ie) { break; }
                }
            }
        }
        throw last == null ? new RuntimeException("网络失败") : last;
    }

    static String httpOnce(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "SkyAnnounce/2.0 (Android)");
        c.setRequestProperty("Accept-Encoding", "gzip");
        try {
            int code = c.getResponseCode();
            NET_LASTMOD = c.getHeaderField("Last-Modified");
            if (code != 200) throw new RuntimeException("HTTP " + code);
            InputStream in = c.getInputStream();
            if ("gzip".equalsIgnoreCase(c.getContentEncoding())) in = new GZIPInputStream(in);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** "Thu, 29 Oct 2026 18:40:37 GMT" -> "10/30 02:40"（+8） */
    static String shortTime(String lm) {
        try {
            java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            java.util.Date d = in.parse(lm);
            return new java.text.SimpleDateFormat("MM/dd HH:mm", Locale.CHINA).format(d);
        } catch (Throwable e) {
            return lm;
        }
    }


    /** 把 Reicon 矢量图标以 ImageSpan 方式嵌进按钮文字（compoundDrawable 在居中文本里会贴左边，改用 span 才跟文字一起居中） */
    private void putIcon(TextView t, String name, int color) {
        int id = getResources().getIdentifier(name, "drawable", getPackageName());
        if (id == 0) return;
        android.graphics.drawable.Drawable d = getDrawable(id);
        if (d == null) return;
        d = d.mutate();
        d.setTint(color);
        d.setBounds(0, 0, dp(17), dp(17));
        String label = t.getText() == null ? "" : t.getText().toString();
        android.text.SpannableString sp = new android.text.SpannableString("\u2009 " + label);
        sp.setSpan(new android.text.style.ImageSpan(d, android.text.style.ImageSpan.ALIGN_CENTER), 0, 1,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        t.setText(sp);
        t.setGravity(Gravity.CENTER);
    }

    static String sha256(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ------------------------------------------------------------ 通知

    static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel a = new NotificationChannel(CH_ANN, "公告更新", NotificationManager.IMPORTANCE_HIGH);
        a.setDescription("检测到光遇公告内容变化时提醒");
        nm.createNotificationChannel(a);
        NotificationChannel m = new NotificationChannel(CH_MON, "常驻监控", NotificationManager.IMPORTANCE_MIN);
        m.setDescription("常驻后台检测的运行状态");
        nm.createNotificationChannel(m);
        NotificationChannel f = new NotificationChannel(CH_FEED, "官方动态", NotificationManager.IMPORTANCE_DEFAULT);
        f.setDescription("官方账号（B 站 / 官网）有新动态时提醒");
        nm.createNotificationChannel(f);
        NotificationChannel dch = new NotificationChannel(CH_DAILY, "每日任务", NotificationManager.IMPORTANCE_DEFAULT);
        dch.setDescription("每天到点提醒做光遇每日任务");
        nm.createNotificationChannel(dch);
        NotificationChannel sch = new NotificationChannel(CH_SHARD, "碎片提醒", NotificationManager.IMPORTANCE_HIGH);
        sch.setDescription("红石 / 黑石开始前 10 分钟提醒");
        nm.createNotificationChannel(sch);
    }

    static PendingIntent pi(Context c) {
        return pi(c, "");
    }

    static PendingIntent pi(Context c, String url) {
        Intent i = new Intent(c, MainActivity.class);
        if (url != null && !url.isEmpty()) i.putExtra("open_url", url);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) f |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(c, 0, i, f);
    }

    static void notifyAnn(Context c, String title, String body) {
        if (!notifyAllowed(c, title + " " + body)) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        String brief = body.replace("\n", " ").trim();
        if (brief.length() > 120) brief = brief.substring(0, 120) + "…";
        Notification n = new Notification.Builder(c, CH_ANN)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("公告更新：" + title)
                .setContentText(brief)
                .setStyle(new Notification.BigTextStyle().bigText(brief))
                .setAutoCancel(true)
                .setContentIntent(pi(c, U_ANN))
                .build();
        nm.notify(NID_ANN, n);
    }

    /** 每日任务提醒：到设定时间后当天只提醒一次（随 App 抓取/刷新时检查）。 */
    static void maybeDailyReminder(Context c) {
        try {
            SharedPreferences sp = pref(c);
            if (!sp.getBoolean("daily_remind", false)) return;
            String hm = sp.getString("daily_time", "08:00");
            int target = 8 * 60;
            try {
                String[] a = hm.split(":");
                target = Integer.parseInt(a[0].trim()) * 60 + Integer.parseInt(a[1].trim());
                if (target < 0 || target > 1439) target = 8 * 60;
            } catch (Throwable ignore) {
            }
            java.util.Calendar cal = java.util.Calendar.getInstance();
            int now = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE);
            if (now < target) return;
            String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                    .format(new java.util.Date());
            if (today.equals(sp.getString("daily_last", ""))) return;
            // 尊重免打扰/关键词过滤：被拦时不标记，等下次再提醒
            if (!notifyAllowed(c, "每日任务")) return;
            sp.edit().putString("daily_last", today).apply();
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            android.content.Intent it = new android.content.Intent(c, MainActivity.class);
            it.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
            android.app.PendingIntent pi2 = android.app.PendingIntent.getActivity(c, 2004, it,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            String dailyBody = "";
            try {
                org.json.JSONArray arr = new org.json.JSONArray(sp.getString("feed", "[]"));
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String src = o.optString("src", "");
                    if ("每日任务".equals(src) || "daily".equals(src)) {
                        String t = o.optString("text", "");
                        if (!t.trim().isEmpty()) {
                            dailyBody = t.trim();
                            if (dailyBody.length() > 260) dailyBody = dailyBody.substring(0, 260) + "…";
                            break;
                        }
                    }
                }
            } catch (Throwable ignore) {
            }
            if (dailyBody.isEmpty()) dailyBody = "今天的每日任务刷新啦，记得上线做～（点开 App 查看资料/活动）";
            Notification n = new Notification.Builder(c, CH_DAILY)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("光遇 · 每日任务")
                    .setContentText(dailyBody.replace("\n", " "))
                    .setStyle(new Notification.BigTextStyle().bigText(dailyBody))
                    .setAutoCancel(true)
                    .setContentIntent(pi2)
                    .build();
            nm.notify(NID_DAILY, n);
        } catch (Throwable ignore) {
        }
    }

    // ------------------------------------------------------------ 碎片提醒（开始前 10 分钟）

    static PendingIntent shardAlarmPi(Context c, int slot, Intent it) {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) f |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, 4100 + slot, it, f);
    }

    static PendingIntent shardOpenPi(Context c) {
        Intent i = new Intent(c, MainActivity.class).putExtra("tab", 6);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) f |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(c, 4200, i, f);
    }

    /** 精确闹钟权限（Android 12+ 的「闹钟和提醒」特殊权限）。 */
    static boolean canExact(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            return am != null && am.canScheduleExactAlarms();
        } catch (Throwable t) {
            return false;
        }
    }

    static void requestExact(Context c) {
        if (Build.VERSION.SDK_INT < 31) return;
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable ignore) {
        }
    }

    /** 把接下来最多 6 个碎片窗口的「开始前 10 分钟」排成精确闹钟。 */
    static void scheduleShardAlarms(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        for (int slot = 0; slot < 6; slot++) {
            Intent it = new Intent(c, ShardReceiver.class).setAction(ACTION_SHARD).putExtra("slot", slot);
            try {
                am.cancel(shardAlarmPi(c, slot, it));
            } catch (Throwable ignore) {
            }
        }
        if (!pref(c).getBoolean("shard_remind", true)) return;
        long now = System.currentTimeMillis();
        Calendar base = Calendar.getInstance();
        int slot = 0;
        for (int dayOff = 0; dayOff < 10 && slot < 6; dayOff++) {
            Calendar day = (Calendar) base.clone();
            day.add(Calendar.DAY_OF_MONTH, dayOff);
            ShardDay d = shardDay(day);
            if (!d.hasShard || d.times == null) continue;
            for (int wi = 0; wi < d.times.length && slot < 6; wi++) {
                Calendar st = (Calendar) day.clone();
                st.set(Calendar.HOUR_OF_DAY, d.times[wi][0]);
                st.set(Calendar.MINUTE, d.times[wi][1]);
                st.set(Calendar.SECOND, 0);
                st.set(Calendar.MILLISECOND, 0);
                long trigger = st.getTimeInMillis() - 600000L;
                if (trigger <= now + 2000L) continue;
                Intent it = new Intent(c, ShardReceiver.class).setAction(ACTION_SHARD).putExtra("slot", slot);
                PendingIntent pi = shardAlarmPi(c, slot, it);
                try {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
                } catch (Throwable t) {
                    try {
                        am.setWindow(AlarmManager.RTC_WAKEUP, trigger, 300000L, pi);
                    } catch (Throwable ignore) {
                    }
                }
                slot++;
            }
        }
    }

    /** 现在是否落在某个窗口「开始前 10 分钟」内；是则发一次（当天该窗口只发一次）。 */
    static void maybeNotifyShard(Context c) {
        maybeNotifyShard(c, false);
    }

    /** force=true 时忽略时间判断，直接对最近一个窗口发提醒（调试用）。 */
    static void maybeNotifyShard(Context c, boolean force) {
        try {
            SharedPreferences sp = pref(c);
            if (!sp.getBoolean("shard_remind", true)) return;
            Calendar now = Calendar.getInstance();
            ShardDay d = shardDay(now);
            Calendar day = now;
            int idx = -1;
            if (force) {
                if (!d.hasShard || d.times == null) {
                    ShardDay nx = nextShard(now, 10);
                    if (nx == null) return;
                    day = (Calendar) now.clone();
                    day.add(Calendar.DAY_OF_MONTH, nx.daysAhead);
                    d = nx;
                }
                idx = 0;
            } else {
                if (!d.hasShard || d.times == null) return;
                long nowMs = now.getTimeInMillis();
                for (int i = 0; i < d.times.length; i++) {
                    Calendar st = (Calendar) now.clone();
                    st.set(Calendar.HOUR_OF_DAY, d.times[i][0]);
                    st.set(Calendar.MINUTE, d.times[i][1]);
                    st.set(Calendar.SECOND, 0);
                    st.set(Calendar.MILLISECOND, 0);
                    long lead = st.getTimeInMillis() - nowMs;
                    if (lead >= 0 && lead <= 660000L) {   // 提前 ≤11 分钟（含闹钟稍早触发）
                        idx = i;
                        break;
                    }
                }
                if (idx < 0) return;
            }
            String dayKey = new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(day.getTime());
            // 调试触发带分钟戳，避免被去重挡住；正常提醒仍按「日期+窗口」去重
            String key = "shard_sent_" + dayKey + "_" + idx
                    + (force ? "_dbg" + (System.currentTimeMillis() / 60000L) : "");
            if (sp.getBoolean(key, false)) return;
            String kind = d.isRed ? "红石" : "黑石";
            String title = kind + " 10 分钟后开始";
            StringBuilder body = new StringBuilder(d.place)
                    .append(" · ").append(shardWin(d.times[idx]));
            if (d.isRed) body.append(" · 升华×").append(trimNum(d.reward));
            if (!notifyAllowed(c, title + " " + body, true)) {
                android.util.Log.i("SkyShard", "blocked by filter");
                return;
            }
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            Notification n = new Notification.Builder(c, CH_SHARD)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setAutoCancel(true)
                    .setContentIntent(shardOpenPi(c))
                    .build();
            nm.notify(NID_SHARD, n);
            int active = -1;
            try {
                android.service.notification.StatusBarNotification[] cur = nm.getActiveNotifications();
                active = cur == null ? -1 : cur.length;
            } catch (Throwable ignore) {
            }
            android.util.Log.i("SkyShard", "posted title=" + title + " body=" + body + " active=" + active);
            sp.edit().putBoolean(key, true).apply();
        } catch (Throwable t) {
            android.util.Log.w("SkyShard", "notify failed: " + t, t);
        }
    }

    public static class ShardReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            boolean force = i != null && i.getBooleanExtra("force", false);
            android.util.Log.i("SkyShard", "recv force=" + force);
            maybeNotifyShard(c, force);
            scheduleShardAlarms(c);
        }
    }

    // ------------------------------------------------------------ 定时

    static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        int mins = Math.max(15, pref(c).getInt("interval", 15));
        JobInfo.Builder b = new JobInfo.Builder(JOB_ID, new ComponentName(c, PollJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(mins * 60_000L);
        js.schedule(b.build());
    }

    public static class PollJob extends JobService {
        @Override
        public boolean onStartJob(final JobParameters p) {
            new Thread(() -> {
                    maybeDailyReminder(getApplicationContext());
                try { maybeNotifyShard(getApplicationContext()); } catch (Throwable ignore) {}
                try {
                    if (!online(getApplicationContext())) { jobFinished(p, false); return; }
                    String ann = http(U_ANN);
                    rememberOfficial(getApplicationContext());
                    String age = http(U_AGE);
                    String srv = "";
                    try {
                        JSONObject t = new JSONObject(http(U_TIME));
                        srv = new SimpleDateFormat("MM/dd HH:mm", Locale.CHINA)
                                .format(new Date(t.optLong("server_time", 0) * 1000L));
                    } catch (Exception ignore) {
                    }
                    applyStatic(getApplicationContext(), ann, age, srv);
                } catch (Exception ignore) {
                }
                jobFinished(p, false);
            }).start();
            return true;
        }

        @Override
        public boolean onStopJob(JobParameters p) {
            return true;
        }
    }

    public static class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            schedule(c);
            try { scheduleShardAlarms(c); } catch (Throwable ignore) {}
        }
    }

    // ------------------------------------------------------------ 常驻服务

    public static class MonService extends Service {
        private Thread worker;
        private volatile boolean run = true;

        @Override
        public void onCreate() {
            super.onCreate();
            ensureChannel(this);
        requestNotifPermission(this);
            Notification n = new Notification.Builder(this, CH_MON)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle("光遇公告监控运行中")
                    .setContentText((pref(this).getBoolean("fast", false) ? "每 60 秒" : "每 5 分钟") + "检测一次，有更新会单独提醒")
                    .setOngoing(true)
                    .setContentIntent(pi(this))
                    .build();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NID_MON, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NID_MON, n);
            }
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            if (worker == null) {
                worker = new Thread(() -> {
                    while (run) {
                        try { maybeDailyReminder(getApplicationContext()); } catch (Throwable ignore) {}
                        try { maybeNotifyShard(getApplicationContext()); } catch (Throwable ignore) {}
                        try {
                            if (!online(getApplicationContext())) {
                                try { Thread.sleep(60_000L); } catch (InterruptedException e) { break; }
                                continue;
                            }
                            String ann = http(U_ANN);
                            rememberOfficial(getApplicationContext());
                            String age = http(U_AGE);
                            String srv = "";
                            try {
                                JSONObject t = new JSONObject(http(U_TIME));
                                srv = new SimpleDateFormat("MM/dd HH:mm", Locale.CHINA)
                                        .format(new Date(t.optLong("server_time", 0) * 1000L));
                            } catch (Exception ignore) {
                            }
                            applyStatic(getApplicationContext(), ann, age, srv);
                        } catch (Exception ignore) {
                        }
                        try {
                            long iv = pref(getApplicationContext()).getBoolean("fast", false) ? 60_000L : 300_000L;
                            Thread.sleep(iv);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                });
                worker.start();
            }
            return START_STICKY;
        }

        @Override
        public void onDestroy() {
            run = false;
            if (worker != null) worker.interrupt();
            super.onDestroy();
        }

        @Override
        public IBinder onBind(Intent i) {
            return null;
        }
    }

    // ------------------------------------------------------------ 内置 WebView 阅读页

    public static class ReaderActivity extends Activity {
        private WebView web;

        @Override
        protected void onCreate(Bundle b) {
            super.onCreate(b);
            String u0 = getIntent().getStringExtra("url");
            if (u0 != null && u0.startsWith("http://")) u0 = "https://" + u0.substring(7); // 明文链接升级，避免被系统禁明文
            final String url = u0;
            final String content = getIntent().getStringExtra("content");
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(C_BG);

            LinearLayout bar = new LinearLayout(this);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setPadding(dp(10), dp(8), dp(10), dp(8));
            bar.setGravity(Gravity.CENTER_VERTICAL);
            TextView close = barBtn("关闭");
            TextView copy = barBtn("复制链接");
            TextView browser = barBtn("浏览器打开");
            TextView copyAll = barBtn("复制全文");
            if (content == null || content.trim().isEmpty()) copyAll.setVisibility(android.view.View.GONE);
            bar.addView(close, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            bar.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            bar.addView(copyAll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            bar.addView(browser, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            root.addView(bar);

            web = new WebView(this);
            web.getSettings().setJavaScriptEnabled(true);
            web.getSettings().setDomStorageEnabled(true);
            web.getSettings().setLoadWithOverviewMode(true);
            web.getSettings().setUseWideViewPort(true);
            web.setBackgroundColor(C_BG);
            web.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, String u) {
                    v.loadUrl(u);
                    return true;
                }

                @Override
                public void onReceivedError(WebView v, int code, String desc, String failingUrl) {
                    // 加载失败 → 回退系统浏览器，并关闭阅读页
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Throwable ignore) {
                    }
                    finish();
                }
            });
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0);
            wlp.weight = 1f;
            root.addView(web, wlp);
            setContentView(root);

            close.setOnClickListener(v -> finish());
            copy.setOnClickListener(v -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url == null ? "" : url));
                Toast.makeText(this, "已复制链接", Toast.LENGTH_SHORT).show();
            });
            browser.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Throwable e) {
                    Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
                }
            });
            copyAll.setOnClickListener(v -> {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("content", content == null ? "" : content));
                Toast.makeText(this, "已复制全文（" + (content == null ? 0 : content.length()) + " 字）",
                        Toast.LENGTH_SHORT).show();
            });
            if (content != null && !content.trim().isEmpty()) {
                String html = android.text.TextUtils.htmlEncode(content).replace("\n", "<br/>");
                web.loadDataWithBaseURL(null, "<html><head><meta name='viewport' content='width=device-width'/></head>"
                        + "<body style='background:" + String.format("#%06X", 0xFFFFFF & C_BG)
                        + ";color:" + String.format("#%06X", 0xFFFFFF & C_TEXT)
                        + ";font-size:16px;line-height:1.6;padding:14px'>" + html + "</body></html>",
                        "text/html", "UTF-8", null);
            } else if (url != null && url.startsWith("http")) {
                web.loadUrl(url);
            } else {
                Toast.makeText(this, "没有可显示的内容", Toast.LENGTH_SHORT).show();
            }
        }

        private int dp(float v) {
            return Math.round(getResources().getDisplayMetrics().density * v);
        }

        private TextView barBtn(String s) {
            TextView tv = new TextView(this);
            tv.setText(s);
            tv.setTextSize(13);
            tv.setTextColor(C_ACCENT);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(dp(6), dp(9), dp(6), dp(9));
            GradientDrawable g = new GradientDrawable();
            g.setColor(C_CARD2);
            g.setCornerRadius(dp(10));
            tv.setBackground(g);
            return tv;
        }
    }

    // ------------------------------------------------------------ 工具

    /** 是否有可用网络（无网直接跳过抓取，不重试轰炸） */
    static boolean online(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Throwable e) {
            return true; // 拿不到状态时不阻塞抓取
        }
    }

    /** 中继源 key → 中文显示名 */
    static String relaySrcLabel(String key) {
        if (key == null) return "源";
        switch (key) {
            case "official": return "官网";
            case "tap":
            case "taptap": return "TapTap";
            case "bili": return "B站";
            case "weibo": return "微博";
            case "douyin": return "抖音";
            case "xhs": return "小红书";
            case "daily": return "每日任务";
            default: return key;
        }
    }

    /** 清理已不存在的健康项（例如旧版本/旧 schema 的源名） */
    static synchronized void pruneHealth(Context c, String prefix, java.util.Set<String> keep) {
        try {
            SharedPreferences sp = pref(c);
            JSONObject all = new JSONObject(sp.getString("src_health", "{}"));
            java.util.Iterator<String> it = all.keys();
            java.util.List<String> del = new java.util.ArrayList<>();
            while (it.hasNext()) {
                String k = it.next();
                if (k.startsWith(prefix) && !keep.contains(k)) del.add(k);
            }
            if (del.isEmpty()) return;
            for (String k : del) all.remove(k);
            sp.edit().putString("src_health", all.toString()).apply();
        } catch (Throwable ignore) {
        }
    }

    /** 记录每源健康：ok/count/elapsed/last_success/error（设置页展示） */
    static synchronized void markHealth(Context c, String name, boolean ok, int count, long elapsedMs, String error) {
        try {
            SharedPreferences sp = pref(c);
            JSONObject all;
            try {
                all = new JSONObject(sp.getString("src_health", "{}"));
            } catch (Exception e) {
                all = new JSONObject();
            }
            JSONObject row = all.optJSONObject(name);
            if (row == null) row = new JSONObject();
            row.put("ok", ok);
            row.put("count", count);
            row.put("elapsed_ms", elapsedMs);
            row.put("error", error == null ? "" : error);
            if (ok) row.put("last_success", new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date()));
            row.put("at", System.currentTimeMillis());
            all.put(name, row);
            sp.edit().putString("src_health", all.toString()).apply();
        } catch (Throwable ignore) {
        }
    }

    /** 失败源保留旧条目并标 stale（灰显）；成功源清除 stale。 */
    static synchronized void applyStale(Context c, java.util.Set<String> failed) {
        try {
            JSONArray arr = new JSONArray(pref(c).getString("feed", "[]"));
            java.util.Set<String> local = new java.util.HashSet<>();
            local.add("官网"); local.add("TapTap"); local.add("B站"); local.add("公告");
            boolean relayDown = failed.contains("中继");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String src = o.optString("src", "");
                boolean st = false;
                for (String f : failed) {
                    if (f.equals("中继")) continue;
                    if (src.startsWith(f)) { st = true; break; }
                }
                if (relayDown && !local.contains(src)) st = true;
                if (st) o.put("stale", true);
                else o.remove("stale");
            }
            pref(c).edit().putString("feed", arr.toString()).apply();
        } catch (Throwable ignore) {
        }
    }

    /** 通知是否允许：白名单非空需命中、黑名单命中即静音、免打扰时段静音 */
    static boolean notifyAllowed(Context c, String text) {
        return notifyAllowed(c, text, false);
    }

    /** ignoreDnd=true 时跳过免打扰时段（碎片这类到点事件用）。 */
    static boolean notifyAllowed(Context c, String text, boolean ignoreDnd) {
        try {
            SharedPreferences sp = pref(c);
            String t = text == null ? "" : text.toLowerCase(Locale.CHINA);
            String white = sp.getString("nf_kw_white", "").trim();
            String black = sp.getString("nf_kw_black", "").trim();
            if (!black.isEmpty()) {
                for (String kw : black.split("[,，]")) {
                    if (!kw.trim().isEmpty() && t.contains(kw.trim().toLowerCase(Locale.CHINA))) return false;
                }
            }
            if (!white.isEmpty()) {
                boolean hit = false;
                for (String kw : white.split("[,，]")) {
                    if (!kw.trim().isEmpty() && t.contains(kw.trim().toLowerCase(Locale.CHINA))) { hit = true; break; }
                }
                if (!hit) return false;
            }
            if (!ignoreDnd && sp.getBoolean("nf_dnd", true)) {
                int start = sp.getInt("nf_dnd_start", 23);
                int end = sp.getInt("nf_dnd_end", 7);
                int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
                boolean in;
                if (start == end) in = false;
                else if (start < end) in = hour >= start && hour < end;
                else in = hour >= start || hour < end;
                if (in) return false;
            }
        } catch (Throwable ignore) {
        }
        return true;
    }

    static SharedPreferences pref(Context c) {
        return c.getSharedPreferences("skynews", Context.MODE_PRIVATE);
    }

    private void clip(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("sky", s));
    }

    /** 默认用内置阅读页（可回退浏览器）；失败时退回系统浏览器。 */
    private void open(String url) {
        try {
            Intent i = new Intent(this, ReaderActivity.class);
            i.putExtra("url", url);
            startActivity(i);
        } catch (Throwable t) {
            openBrowser(url);
        }
    }

    /** 点卡片：正文是可读文本 → 阅读页显示全文；正文是原始 JSON/无正文 → 用内置网页阅读 */
    private void openItem(String url, String title, String text) {
        String t = text == null ? "" : text.trim();
        boolean jsonLike = t.startsWith("{") || t.startsWith("[")
                || t.contains("\"announcementMessage\"") || t.contains("\"oppoMessage\"")
                || t.contains("\"xiaomi_appMessage\"");
        if (!t.isEmpty() && !jsonLike) {
            Intent i = new Intent(this, ReaderActivity.class);
            i.putExtra("content", (title == null ? "" : title + "\n\n") + t);
            i.putExtra("url", url == null ? "" : url);
            startActivity(i);
        } else if (url != null && url.startsWith("http")) {
            Intent i = new Intent(this, ReaderActivity.class);
            i.putExtra("content", "");
            i.putExtra("url", url);
            startActivity(i);
        } else {
            toast("没有可打开的内容");
        }
    }

    private void openBrowser(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("没有可用的浏览器");
        }
    }

    /** 取证结果弹窗：只报「取证成功」四个字，不外露 tag/路径等细节 */
    private void showEvidenceDialog(boolean ok) {
        try {
            final android.app.Dialog d = new android.app.Dialog(this);
            d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(dp(28), dp(30), dp(28), dp(22));
            GradientDrawable cbg = round(C_CARD2, 20);
            cbg.setStroke(dp(1), 0x332C5A78);
            card.setBackground(cbg);
            int accent = ok ? 0xFF7BE3A5 : 0xFFFF9E9E;

            android.widget.ImageView ic = new android.widget.ImageView(this);
            int rid = getResources().getIdentifier(ok ? "ic_ui_check" : "ic_ui_info", "drawable", getPackageName());
            if (rid != 0) {
                ic.setImageDrawable(getDrawable(rid));
                ic.setColorFilter(accent);
            }
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(44), dp(44));
            ilp.bottomMargin = dp(14);
            card.addView(ic, ilp);

            TextView title = tv(ok ? "取证成功" : "取证失败", 20, C_TEXT, true);
            title.setGravity(Gravity.CENTER);
            card.addView(title);

            TextView btn = pill("好", C_ACCENT);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
            blp.topMargin = dp(22);
            btn.setLayoutParams(blp);
            btn.setOnClickListener(v -> d.dismiss());
            card.addView(btn);

            d.setContentView(card);
            android.view.Window w = d.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
                w.setLayout(dp(300), android.view.WindowManager.LayoutParams.WRAP_CONTENT);
            }
            d.setCanceledOnTouchOutside(true);
            d.show();
        } catch (Throwable t) {
            toast(ok ? "取证成功" : "取证失败");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
