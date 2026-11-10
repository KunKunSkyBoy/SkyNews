package com.dsh.skynews;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
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
    static final int NID_FEED = 1003;
    static final String CH_ANN = "sky_ann_channel";
    static final String CH_MON = "sky_mon_channel";
    static final int NID_ANN = 1001;
    static final int NID_MON = 1002;
    static final int JOB_ID = 0x5c01;

    static final int C_BG = 0xFF0B1020, C_CARD = 0xFF151B2E, C_CARD2 = 0xFF1D2439,
            C_ACCENT = 0xFF6FD3FF, C_GOLD = 0xFFF3C77B, C_TEXT = 0xFFE6ECF7, C_DIM = 0xFF8C97B3;

    private LinearLayout tabRow, content;
    private TextView tvStatus, tvRefresh;
    private ObjectAnimator spin;
    private TextView copyBtn;
    private final java.util.ArrayList<TextView> TOP_TEXTS = new java.util.ArrayList<>();
    static volatile String NET_LASTMOD = "";
    private ScrollView scroller;
    private int tab = 0;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean busy = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(C_BG);
        getWindow().setNavigationBarColor(C_BG);
        ensureChannel(this);
        buildUi();
        render();
        refresh(false);
        schedule(this);
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
        tvRefresh.setOnClickListener(v -> refresh(true));
        LinearLayout.LayoutParams rlp = lp(dp(46), dp(46));
        rlp.leftMargin = dp(8);
        head.addView(tvRefresh, rlp);
        root.addView(head);

        tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tlp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(14);
        root.addView(tabRow, tlp);
        String[] names = {"公告", "动态", "变更历史", "设置", "预载", "日历"};
        String[] icons = {"ann", "feed", "hist", "set", "pre", "cal"};
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
            String raw = pref(this).getString("ann_json", "");
            try {
                JSONObject o = parseLoose(raw);
                String text = o.optString("Title", "") + "\n\n" + o.optString("NeteaseMessage", "");
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
        age.setOnClickListener(v -> refresh(true));
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
            String label = tab == 5 ? "复制日历" : tab == 4 ? "复制预载" : tab == 1 ? "复制动态" : tab == 0 ? "复制全文" : "复制";
            copyBtn.setText(label);
        }
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            TextView t = (TextView) tabRow.getChildAt(i);
            boolean on = i == tab;
            t.setTextColor(on ? C_ACCENT : C_DIM);
            try {
                t.setCompoundDrawableTintList(ColorStateList.valueOf(on ? C_ACCENT : C_DIM));
            } catch (Throwable ignore) { }
            GradientDrawable g = round(on ? 0xFF16283D : 0x00000000, 12);
            if (on) g.setStroke(dp(1), 0xFF2C5A78);
            t.setBackground(g);
        }
        content.removeAllViews();
        if (tab == 0) renderAnn();
        else if (tab == 1) renderFeed();
        else if (tab == 2) renderHist();
        else if (tab == 4) renderPreload();
        else if (tab == 5) renderCalendar();
        else renderSettings();
        updateStatus();
        animateIn();
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
            div.setBackgroundColor(0xFF243049);
            content.addView(div, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

            addBody(o.optString("NeteaseMessage", ""));
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
            TextView t;
            if (s.startsWith("【") && s.contains("】")) {
                t = tv(s, 16, C_GOLD, true);
                t.setPadding(0, dp(14), 0, dp(6));
            } else if (firstLine) {
                t = tv(s, 15, C_TEXT, true);
                t.setPadding(0, dp(12), 0, dp(4));
            } else {
                t = tv(s, 14, 0xFFCBD5E8, false);
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
        if (arr.length() == 0) {
            content.addView(hint("还没有抓到官方动态\n点右上角 ↻ 抓取（微博 / B 站 / 官网）"));
            return;
        }
        content.addView(tv("官方动态 · 共 " + arr.length() + " 条（按时间倒序）", 12, C_DIM, false));
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(10), dp(12), dp(12));
            card.setBackground(round(C_CARD2, 12));
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
            card.addView(head);

            TextView body = tv(o.optString("text", ""), 14, C_TEXT, false);
            body.setLineSpacing(dp(4), 1f);
            body.setPadding(0, dp(8), 0, 0);
            card.addView(body);

            final String url = o.optString("url", "");
            if (!url.isEmpty()) card.setOnClickListener(v -> open(url));
            content.addView(card);
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
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
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
        }

        // 4) 官网公告正文（与「公告」页同源，作为动态时间线里的一条）
        try {
            JSONObject o = parseLoose(ann);
            String body = o.optString("NeteaseMessage", "").replace("\n", " ").trim();
            if (body.length() > 140) body = body.substring(0, 140) + "…";
            JSONObject it = new JSONObject();
            it.put("id", "ann-" + sha256(o.optString("Title", "") + body));
            it.put("src", "公告");
            it.put("t", fmt.format(new Date()));
            it.put("ts", System.currentTimeMillis());
            it.put("text", o.optString("Title", "") + "\n" + body);
            it.put("url", "https://sky.163.com/news/");
            items.add(it);
        } catch (Exception e) {
            err.append("公告:").append(e.getMessage()).append('\n');
        }

        // 5) 云中继（可选）：微博/抖音/小红书由云端抓，App 只拉统一 JSON
        String relay = pref(ctx).getString("relay_url", U_RELAY_DEFAULT);
        if (!relay.isEmpty()) {
            try {
                String body = http(relay, null, null);
                JSONArray arr;
                if (body.trim().startsWith("[")) arr = new JSONArray(body);
                else arr = new JSONObject(body).optJSONArray("items");
                java.util.Set<String> have = new java.util.HashSet<>();
                for (JSONObject x : items) have.add(x.optString("id", ""));
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String baseId = o.optString("id", "i" + i);
                    if (have.contains(baseId)) continue;   // 本地已抓到（官网/TapTap/B站），不重复
                    have.add(baseId);
                    JSONObject it = new JSONObject();
                    it.put("id", "rl-" + baseId);
                    it.put("src", o.optString("src", "中继"));
                    it.put("t", o.optString("t", ""));
                    it.put("ts", o.optLong("ts", 0));
                    it.put("text", o.optString("text", ""));
                    it.put("url", o.optString("url", ""));
                    items.add(it);
                }
            } catch (Exception e) {
                err.append("中继:").append(e.getMessage()).append('\n');
            }
        }

        int added = mergeFeed(ctx, items);
        SharedPreferences sp = pref(ctx);
        boolean init = sp.getBoolean("feed_init", false);
        sp.edit().putString("feed_err", err.toString())
                .putLong("feed_check", System.currentTimeMillis())
                .putBoolean("feed_init", true).apply();
        if (added > 0 && init) notifyFeed(ctx, added, items.isEmpty() ? "" : items.get(0).optString("text", ""));
        return added;
    }

    static void notifyFeed(Context c, int added, String sample) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            String brief = sample.replace("\n", " ").trim();
            if (brief.length() > 100) brief = brief.substring(0, 100) + "…";
            Notification n = new Notification.Builder(c, CH_FEED)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("官方动态 +" + added + " 条")
                    .setContentText(brief)
                    .setStyle(new Notification.BigTextStyle().bigText(brief))
                    .setAutoCancel(true)
                    .setContentIntent(pi(c))
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
                content.addView(tv("· " + w.start + " → " + w.end + "  " + w.title, 13, 0xFFCBD5E8, false));
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
            GradientDrawable g = round(on ? 0xFF16283D : C_CARD2, 10);
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
        tvStatus.setText("上次检测 " + sp.getString("last_check", "—")
                + (srv.isEmpty() ? "" : " · 服务器 " + srv)
                + " · 每 " + (sp.getBoolean("fast", false) && sp.getBoolean("fgs", false) ? "1 分钟" : sp.getInt("interval", 15) + " 分钟")
                + (sp.getString("official_lm", "").isEmpty() ? "" : " · 官方 " + shortTime(sp.getString("official_lm", ""))));
    }

    // ------------------------------------------------------------ 抓取

    private void refresh(final boolean manual) {
        if (busy) {
            if (manual) toast("正在抓取…");
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
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
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
    }

    static PendingIntent pi(Context c) {
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) f |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(c, 0, i, f);
    }

    static void notifyAnn(Context c, String title, String body) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        String brief = body.replace("\n", " ").trim();
        if (brief.length() > 120) brief = brief.substring(0, 120) + "…";
        Notification n = new Notification.Builder(c, CH_ANN)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("公告更新：" + title)
                .setContentText(brief)
                .setStyle(new Notification.BigTextStyle().bigText(brief))
                .setAutoCancel(true)
                .setContentIntent(pi(c))
                .build();
        nm.notify(NID_ANN, n);
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
                try {
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
                        try {
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

    // ------------------------------------------------------------ 工具

    static SharedPreferences pref(Context c) {
        return c.getSharedPreferences("skynews", Context.MODE_PRIVATE);
    }

    private void clip(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("sky", s));
    }

    private void open(String url) {
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
