package jp.local.datameter;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class UsageWidget extends AppWidgetProvider {

    public static final String ACTION_REFRESH = "jp.local.datameter.REFRESH";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_REFRESH.equals(intent.getAction())) {
            final PendingResult r = goAsync();
            final Context app = context.getApplicationContext();
            new Thread(() -> {
                try { updateAll(app); } finally { r.finish(); }
            }).start();
            return;
        }
        super.onReceive(context, intent);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        schedule(context);
        final PendingResult r = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try { updateAll(app); } finally { r.finish(); }
        }).start();
    }

    @Override
    public void onEnabled(Context context) { schedule(context); }

    @Override
    public void onDisabled(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        am.cancel(refreshIntent(context, 1));
    }

    private static PendingIntent refreshIntent(Context c, int req) {
        Intent i = new Intent(c, UsageWidget.class).setAction(ACTION_REFRESH);
        return PendingIntent.getBroadcast(c, req, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** 15分ごとの更新（省電力のためOS側でまとめて実行される場合あり） */
    private static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES, refreshIntent(c, 1));
    }

    /** アプリ側から呼ぶ：ウィジェットを即時更新 */
    public static void requestUpdate(Context c) {
        c.sendBroadcast(new Intent(c, UsageWidget.class).setAction(ACTION_REFRESH));
    }

    static void updateAll(Context c) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(c);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(c, UsageWidget.class));
        if (ids == null || ids.length == 0) return;
        RemoteViews v = build(c);
        mgr.updateAppWidget(ids, v);
    }

    private static RemoteViews build(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        boolean b1024 = DataUsage.base1024(c);
        int startDay = DataUsage.startDay(c);
        long[] p = DataUsage.currentPeriod(startDay);
        SimpleDateFormat md = new SimpleDateFormat("M/d", Locale.JAPAN);

        if (!DataUsage.hasUsageAccess(c)) {
            v.setTextViewText(R.id.w_total, "要設定");
            v.setTextViewText(R.id.w_sub, "タップして\n権限を許可");
            v.setProgressBar(R.id.w_progress, 1000, 0, false);
        } else {
            try {
                long now = System.currentTimeMillis();
                long total = DataUsage.mobileTotal(c, p[0], now + 60000);
                long today = DataUsage.mobileTotal(c, DataUsage.todayStart(), now + 60000);
                v.setTextViewText(R.id.w_total, DataUsage.fmt(total, b1024));
                float lim = DataUsage.limitGb(c);
                String sub = "今日 " + DataUsage.fmt(today, b1024).replace(" ", "");
                if (lim > 0) {
                    long limB = DataUsage.gbToBytes(lim, b1024);
                    int prog = (int) Math.min(1000, total * 1000 / Math.max(1, limB));
                    v.setProgressBar(R.id.w_progress, 1000, prog, false);
                    long remain = limB - total;
                    sub += remain >= 0 ? "\n残り " + DataUsage.fmt(remain, b1024).replace(" ", "")
                                       : "\n超過 " + DataUsage.fmt(-remain, b1024).replace(" ", "");
                } else {
                    v.setProgressBar(R.id.w_progress, 1000, 0, false);
                }
                v.setTextViewText(R.id.w_sub, sub);
            } catch (Exception e) {
                v.setTextViewText(R.id.w_total, "取得失敗");
                v.setTextViewText(R.id.w_sub, "");
            }
        }

        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        v.setOnClickPendingIntent(R.id.w_refresh, refreshIntent(c, 2));
        return v;
    }
}
