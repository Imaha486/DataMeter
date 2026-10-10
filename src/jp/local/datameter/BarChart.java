package jp.local.datameter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import java.util.Calendar;
import java.util.List;

/** シンプルな日別棒グラフ */

public final class BarChart extends View {
    private final List<long[]> data;
    private final boolean b1024;
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint();

    private final float dens;
    BarChart(Context c, List<long[]> d, boolean b1024, int accent, int sub) {
        super(c);
        dens = c.getResources().getDisplayMetrics().density;
        this.data = d;
        this.b1024 = b1024;
        bar.setColor(accent);
        txt.setColor(sub);
        txt.setTextSize(dp(10));
        line.setColor((sub & 0x00FFFFFF) | 0x40000000);
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        int top = dp(16), bottom = h - dp(16);
        long max = 1;
        for (long[] e : data) max = Math.max(max, e[1]);
        cv.drawText("最大 " + DataUsage.fmt(max, b1024), 0, dp(11), txt);
        cv.drawLine(0, bottom, w, bottom, line);
        int days = Math.max(data.size(), 28);
        float slot = (float) w / days;
        Calendar cal = Calendar.getInstance();
        for (int i = 0; i < data.size(); i++) {
            long v = data.get(i)[1];
            float bh = (float) (bottom - top) * v / max;
            float x = i * slot;
            cv.drawRect(x + slot * 0.15f, bottom - bh, x + slot * 0.85f, bottom, bar);
            cal.setTimeInMillis(data.get(i)[0]);
            int dom = cal.get(Calendar.DAY_OF_MONTH);
            if (i == 0 || dom % 5 == 0) {
                cv.drawText(String.valueOf(dom), x, h - dp(3), txt);
            }
        }
    }

    private int dp(int v) { return (int) (v * dens + 0.5f); }
}
