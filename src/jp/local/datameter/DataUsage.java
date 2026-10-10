package jp.local.datameter;

import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Process;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** NetworkStatsManager（OS内部の通信量カウンタ）から通信量を取得するヘルパ */
public final class DataUsage {

    public static final String PREFS = "settings";
    public static final String KEY_START_DAY = "start_day";
    public static final String KEY_LIMIT_GB = "limit_gb";
    public static final String KEY_BASE1024 = "base1024";

    private DataUsage() {}

    // ---------- 設定 ----------
    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    public static int startDay(Context c) { return prefs(c).getInt(KEY_START_DAY, 1); }
    public static float limitGb(Context c) { return prefs(c).getFloat(KEY_LIMIT_GB, 0f); }
    public static boolean base1024(Context c) { return prefs(c).getBoolean(KEY_BASE1024, false); }

    // ---------- 権限 ----------
    @SuppressWarnings("deprecation")
    public static boolean hasUsageAccess(Context c) {
        AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
        int mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), c.getPackageName());
        if (mode == AppOpsManager.MODE_DEFAULT) {
            return c.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    // ---------- 期間 ----------
    /** 締め日（開始日）から現在の集計期間 [start, end) を求める */
    public static long[] currentPeriod(int startDay) {
        Calendar now = Calendar.getInstance();
        Calendar s = (Calendar) now.clone();
        setDayClamped(s, startDay);
        zeroTime(s);
        if (s.after(now)) {
            s.add(Calendar.MONTH, -1);
            setDayClamped(s, startDay);
        }
        Calendar e = (Calendar) s.clone();
        e.set(Calendar.DAY_OF_MONTH, 1);
        e.add(Calendar.MONTH, 1);
        setDayClamped(e, startDay);
        return new long[]{s.getTimeInMillis(), e.getTimeInMillis()};
    }

    private static void setDayClamped(Calendar c, int day) {
        c.set(Calendar.DAY_OF_MONTH, 1);
        int max = c.getActualMaximum(Calendar.DAY_OF_MONTH);
        c.set(Calendar.DAY_OF_MONTH, Math.min(Math.max(day, 1), max));
    }

    public static void zeroTime(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    public static long todayStart() {
        Calendar c = Calendar.getInstance();
        zeroTime(c);
        return c.getTimeInMillis();
    }

    // ---------- 取得 ----------
    /** 端末全体の通信量 {rx, tx}。networkType = ConnectivityManager.TYPE_MOBILE / TYPE_WIFI */
    public static long[] device(Context c, int networkType, long start, long end) throws Exception {
        NetworkStatsManager nsm = (NetworkStatsManager) c.getSystemService(Context.NETWORK_STATS_SERVICE);
        // Android 10 以降、subscriberId=null で全SIM（モバイル全体）を対象にできる
        NetworkStats.Bucket b = nsm.querySummaryForDevice(networkType, null, start, end);
        if (b == null) return new long[]{0, 0};
        return new long[]{b.getRxBytes(), b.getTxBytes()};
    }

    public static long mobileTotal(Context c, long start, long end) throws Exception {
        long[] v = device(c, ConnectivityManager.TYPE_MOBILE, start, end);
        return v[0] + v[1];
    }

    /** 日別のモバイル通信量（期間開始から今日まで） */
    public static List<long[]> daily(Context c, long start, long end) throws Exception {
        List<long[]> out = new ArrayList<>();
        Calendar d = Calendar.getInstance();
        d.setTimeInMillis(start);
        long now = System.currentTimeMillis();
        while (d.getTimeInMillis() < end && d.getTimeInMillis() <= now) {
            long ds = d.getTimeInMillis();
            d.add(Calendar.DAY_OF_MONTH, 1);
            long de = d.getTimeInMillis();
            out.add(new long[]{ds, mobileTotal(c, ds, de)});
        }
        return out;
    }

    public static final class AppUsage {
        public int uid;
        public String label;
        public long bytes;
    }

    /** アプリ(UID)別のモバイル通信量（多い順） */
    public static List<AppUsage> perApp(Context c, long start, long end) throws Exception {
        NetworkStatsManager nsm = (NetworkStatsManager) c.getSystemService(Context.NETWORK_STATS_SERVICE);
        NetworkStats st = nsm.querySummary(ConnectivityManager.TYPE_MOBILE, null, start, end);
        Map<Integer, Long> map = new HashMap<>();
        NetworkStats.Bucket b = new NetworkStats.Bucket();
        while (st.hasNextBucket()) {
            st.getNextBucket(b);
            int uid = b.getUid();
            long v = b.getRxBytes() + b.getTxBytes();
            Long old = map.get(uid);
            map.put(uid, (old == null ? 0 : old) + v);
        }
        st.close();
        PackageManager pm = c.getPackageManager();
        List<AppUsage> list = new ArrayList<>();
        for (Map.Entry<Integer, Long> e : map.entrySet()) {
            if (e.getValue() <= 0) continue;
            AppUsage a = new AppUsage();
            a.uid = e.getKey();
            a.bytes = e.getValue();
            a.label = labelForUid(pm, a.uid);
            list.add(a);
        }
        Collections.sort(list, (x, y) -> Long.compare(y.bytes, x.bytes));
        return list;
    }

    private static String labelForUid(PackageManager pm, int uid) {
        if (uid == NetworkStats.Bucket.UID_TETHERING) return "テザリング";
        if (uid == NetworkStats.Bucket.UID_REMOVED) return "削除済みアプリ";
        if (uid == 0) return "システム (root)";
        if (uid == Process.SYSTEM_UID) return "Androidシステム";
        String[] pkgs = pm.getPackagesForUid(uid);
        if (pkgs != null && pkgs.length > 0) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkgs[0], 0);
                String l = pm.getApplicationLabel(ai).toString();
                if (pkgs.length > 1) l += " ほか" + (pkgs.length - 1) + "件";
                return l;
            } catch (Exception ignored) {
                return pkgs[0];
            }
        }
        return "UID " + uid;
    }

    // ---------- 表示 ----------
    public static String fmt(long bytes, boolean base1024) {
        double k = base1024 ? 1024.0 : 1000.0;
        double v = bytes;
        if (v < k) return String.format(Locale.JAPAN, "%d B", bytes);
        v /= k;
        if (v < k) return String.format(Locale.JAPAN, "%.0f KB", v);
        v /= k;
        if (v < k) return String.format(Locale.JAPAN, v < 100 ? "%.1f MB" : "%.0f MB", v);
        v /= k;
        return String.format(Locale.JAPAN, v < 100 ? "%.2f GB" : "%.1f GB", v);
    }

    public static long gbToBytes(float gb, boolean base1024) {
        double k = base1024 ? 1024.0 : 1000.0;
        return (long) (gb * k * k * k);
    }
}
