package jp.local.datameter;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private LinearLayout root;
    private int fg, sub, accent;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        TypedArray ta = obtainStyledAttributes(new int[]{
                android.R.attr.textColorPrimary, android.R.attr.textColorSecondary});
        fg = ta.getColor(0, Color.BLACK);
        sub = ta.getColor(1, Color.GRAY);
        ta.recycle();
        accent = Color.parseColor("#009688");

        ScrollView sv = new ScrollView(this);
        sv.setFitsSystemWindows(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, p);
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        root.removeAllViews();
        if (!DataUsage.hasUsageAccess(this)) {
            showPermission();
            return;
        }
        TextView loading = text("読み込み中…", 14, sub);
        root.addView(loading);
        final Context ctx = getApplicationContext();
        new Thread(() -> {
            final Result r = load(ctx);
            runOnUiThread(() -> render(r));
        }).start();
        UsageWidget.requestUpdate(this);
    }

    // ---------------- データ ----------------
    static final class Result {
        long[] period;
        long rx, tx, today, wifi;
        List<long[]> daily = new ArrayList<>();
        List<DataUsage.AppUsage> apps = new ArrayList<>();
        String error;
    }

    private static Result load(Context c) {
        Result r = new Result();
        try {
            long now = System.currentTimeMillis() + 60000;
            r.period = DataUsage.currentPeriod(DataUsage.startDay(c));
            long[] m = DataUsage.device(c, ConnectivityManager.TYPE_MOBILE, r.period[0], now);
            r.rx = m[0];
            r.tx = m[1];
            r.today = DataUsage.mobileTotal(c, DataUsage.todayStart(), now);
            long[] w = DataUsage.device(c, ConnectivityManager.TYPE_WIFI, r.period[0], now);
            r.wifi = w[0] + w[1];
            r.daily = DataUsage.daily(c, r.period[0], r.period[1]);
            r.apps = DataUsage.perApp(c, r.period[0], now);
        } catch (Exception e) {
            r.error = e.toString();
        }
        return r;
    }

    // ---------------- 描画 ----------------
    private void render(Result r) {
        root.removeAllViews();
        boolean b1024 = DataUsage.base1024(this);
        if (r.error != null) {
            root.addView(text("取得エラー: " + r.error, 14, Color.RED));
        }
        if (r.period == null) return;
        SimpleDateFormat f = new SimpleDateFormat("yyyy/M/d", Locale.JAPAN);
        long total = r.rx + r.tx;

        LinearLayout card = card();
        card.addView(text("モバイルデータ通信量（今月の集計期間）", 13, sub));
        card.addView(text(f.format(new Date(r.period[0])) + " 〜 "
                + f.format(new Date(r.period[1] - 1)), 13, sub));
        TextView big = text(DataUsage.fmt(total, b1024), 40, fg);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(big);
        card.addView(text("受信 " + DataUsage.fmt(r.rx, b1024) + "　送信 " + DataUsage.fmt(r.tx, b1024)
                + "\n（" + String.format(Locale.JAPAN, "%,d", total) + " バイト）", 13, sub));

        float lim = DataUsage.limitGb(this);
        if (lim > 0) {
            long limB = DataUsage.gbToBytes(lim, b1024);
            ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            pb.setMax(1000);
            pb.setProgress((int) Math.min(1000, total * 1000 / Math.max(1, limB)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(10));
            lp.topMargin = dp(8);
            card.addView(pb, lp);
            long remain = limB - total;
            Calendar now = Calendar.getInstance();
            long daysLeft = Math.max(1, (r.period[1] - now.getTimeInMillis() + 86399999L) / 86400000L);
            String s = String.format(Locale.JAPAN, "上限 %s 中 %.1f%%　", DataUsage.fmt(limB, b1024),
                    total * 100.0 / limB);
            s += remain >= 0
                    ? "残り " + DataUsage.fmt(remain, b1024) + "\n残り" + daysLeft + "日 → 1日あたり "
                      + DataUsage.fmt(remain / daysLeft, b1024)
                    : "超過 " + DataUsage.fmt(-remain, b1024);
            card.addView(text(s, 14, fg));
        }
        card.addView(text("今日: " + DataUsage.fmt(r.today, b1024)
                + "　　Wi-Fi(参考): " + DataUsage.fmt(r.wifi, b1024), 14, fg));
        root.addView(card);

        // 日別グラフ
        LinearLayout c2 = card();
        c2.addView(text("日別モバイル通信量", 15, fg));
        BarChart chart = new BarChart(this, r.daily, b1024, accent, sub);
        c2.addView(chart, new LinearLayout.LayoutParams(-1, dp(170)));
        root.addView(c2);

        // アプリ別
        LinearLayout c3 = card();
        c3.addView(text("アプリ別（今月・モバイル）", 15, fg));
        int n = 0;
        long sumApps = 0;
        for (DataUsage.AppUsage a : r.apps) sumApps += a.bytes;
        for (DataUsage.AppUsage a : r.apps) {
            if (n++ >= 25) break;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(4), 0, dp(4));
            TextView name = text(a.label, 14, fg);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView val = text(DataUsage.fmt(a.bytes, b1024), 14, fg);
            val.setGravity(Gravity.END);
            row.addView(val);
            c3.addView(row);
        }
        c3.addView(text("アプリ合計: " + DataUsage.fmt(sumApps, b1024), 12, sub));
        root.addView(c3);

        // 設定
        root.addView(settingsCard());

        Button reload = new Button(this);
        reload.setText("再読み込み");
        reload.setOnClickListener(v -> refresh());
        root.addView(reload);

        root.addView(text(
                "※ この値は Android の内部通信量カウンタ（NetworkStatsManager）から直接取得しています。"
                + "テザリング・システム通信・削除済みアプリの通信も含みます。\n"
                + "※ 通信事業者側のカウント（課金対象）とは、計測タイミングやヘッダ分の扱いで多少の差が出ることがあります。\n"
                + "※ 日別・今日の値は OS の記録単位（最大2時間程度）に丸められる場合があります。",
                12, sub));
    }

    private LinearLayout settingsCard() {
        LinearLayout c = card();
        c.addView(text("設定", 15, fg));

        c.addView(text("集計開始日（締め日の翌日／毎月この日にリセット）", 13, sub));
        final EditText day = new EditText(this);
        day.setInputType(InputType.TYPE_CLASS_NUMBER);
        day.setText(String.valueOf(DataUsage.startDay(this)));
        c.addView(day);

        c.addView(text("月間上限（GB、0で非表示）", 13, sub));
        final EditText lim = new EditText(this);
        lim.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        float l = DataUsage.limitGb(this);
        lim.setText(l == (int) l ? String.valueOf((int) l) : String.valueOf(l));
        c.addView(lim);

        final CheckBox cb = new CheckBox(this);
        cb.setText("1GB = 1024MB で計算する（オフ: 1000MB）");
        cb.setChecked(DataUsage.base1024(this));
        c.addView(cb);

        Button save = new Button(this);
        save.setText("保存");
        save.setOnClickListener(v -> {
            int d;
            float g;
            try { d = Integer.parseInt(day.getText().toString().trim()); } catch (Exception e) { d = 1; }
            try { g = Float.parseFloat(lim.getText().toString().trim()); } catch (Exception e) { g = 0; }
            d = Math.max(1, Math.min(31, d));
            DataUsage.prefs(this).edit()
                    .putInt(DataUsage.KEY_START_DAY, d)
                    .putFloat(DataUsage.KEY_LIMIT_GB, Math.max(0, g))
                    .putBoolean(DataUsage.KEY_BASE1024, cb.isChecked())
                    .apply();
            Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show();
            refresh();
        });
        c.addView(save);
        return c;
    }

    private void showPermission() {
        LinearLayout c = card();
        TextView t = text("使用状況へのアクセス許可が必要です", 18, fg);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(t);
        c.addView(text("Android の通信量カウンタを読み取るため、次の画面で「データ通信量メーター」を選び、"
                + "「使用状況へのアクセスを許可」をオンにしてください。\n"
                + "（HyperOS: 設定 → パスワードとセキュリティ → プライバシー → 特別な権限 → 使用状況へのアクセス）",
                14, fg));
        Button b = new Button(this);
        b.setText("設定を開く");
        b.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            try {
                startActivity(i);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            }
        });
        c.addView(b);
        root.addView(c);
    }

    // ---------------- 部品 ----------------
    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        c.setPadding(p, p, p, p);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor((fg & 0x00FFFFFF) | 0x10000000);
        c.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

}
