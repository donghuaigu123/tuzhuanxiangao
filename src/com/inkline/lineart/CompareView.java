package com.inkline.lineart;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** 图片对比视图：拖动分割线左右对比原图/线稿，也支持并排对比与"按住看原图"。 */
public class CompareView extends View {

    public static final int MODE_SLIDE = 0;
    public static final int MODE_SIDE = 1;

    private Bitmap original, lineArt;
    private float ratio = 0.5f;
    private int mode = MODE_SLIDE;
    private boolean showOriginal = false;

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint whitePaint = new Paint();
    private final Paint splitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();

    public CompareView(Context c) { super(c); init(c); }
    public CompareView(Context c, AttributeSet a) { super(c, a); init(c); }

    private void init(Context c) {
        float d = c.getResources().getDisplayMetrics().density;
        whitePaint.setColor(Color.WHITE);
        whitePaint.setStyle(Paint.Style.FILL);
        splitPaint.setColor(0xFFFF9E4A);
        splitPaint.setStrokeWidth(3 * d);
        handleFill.setColor(0xFFFFFFFF);
        handleFill.setStyle(Paint.Style.FILL);
        handleStroke.setColor(0xFFFF9E4A);
        handleStroke.setStyle(Paint.Style.STROKE);
        handleStroke.setStrokeWidth(3 * d);
        badgeBg.setColor(0xCC1B1B1F);
        badgeBg.setStyle(Paint.Style.FILL);
        badgeText.setColor(Color.WHITE);
        badgeText.setTextSize(12 * d);
        badgeText.setFakeBoldText(true);
        tipText.setColor(0x9A222222);
        tipText.setTextSize(13 * d);
    }

    public void setBitmaps(Bitmap original, Bitmap lineArt) {
        this.original = original;
        this.lineArt = lineArt;
        invalidate();
    }

    public void setMode(int m) { this.mode = m; invalidate(); }
    public int getMode() { return mode; }
    public void setShowOriginal(boolean v) { this.showOriginal = v; invalidate(); }
    public boolean hasImage() { return original != null; }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        c.drawColor(0xFFF0F1F4);
        float d = getResources().getDisplayMetrics().density;
        if (original == null) {
            c.drawText("尚未选择图片", w / 2f - 62 * d, h / 2f, tipText);
            return;
        }
        if (showOriginal || lineArt == null) {
            drawFit(c, original, 0, 0, w, h);
            badge(c, "原图", 14 * d, 14 * d, d);
            return;
        }
        if (mode == MODE_SIDE) {
            int half = w / 2;
            drawFit(c, original, 0, 0, half, h);
            drawFit(c, lineArt, half, 0, w, h);
            c.drawLine(half, 0, half, h, splitPaint);
            badge(c, "原图", 14 * d, 14 * d, d);
            badge(c, "线稿", half + 14 * d, 14 * d, d);
            return;
        }
        float split = w * ratio;
        c.save();
        c.clipRect(0, 0, split, h);
        drawFit(c, original, 0, 0, w, h);
        c.restore();
        c.save();
        c.clipRect(split, 0, w, h);
        drawFit(c, lineArt, 0, 0, w, h);
        c.restore();
        c.drawLine(split, 0, split, h, splitPaint);
        float r = 19 * d;
        c.drawCircle(split, h / 2f, r, handleFill);
        c.drawCircle(split, h / 2f, r, handleStroke);
        c.drawLine(split - 6 * d, h / 2f, split - 2 * d, h / 2f, handleStroke);
        c.drawLine(split + 2 * d, h / 2f, split + 6 * d, h / 2f, handleStroke);
        badge(c, "原图", 14 * d, 14 * d, d);
        badge(c, "线稿", w - 66 * d, 14 * d, d);
        c.drawText("← 拖动分割线对比 →", w / 2f - 76 * d, h - 16 * d, tipText);
    }

    private void drawFit(Canvas c, Bitmap bm, float l, float t, float r, float b) {
        float iw = bm.getWidth(), ih = bm.getHeight();
        float s = Math.min((r - l) / iw, (b - t) / ih);
        float dw = iw * s, dh = ih * s;
        tmp.set(l + ((r - l) - dw) / 2f, t + ((b - t) - dh) / 2f,
                l + ((r - l) + dw) / 2f, t + ((b - t) + dh) / 2f);
        c.drawRect(tmp, whitePaint);
        c.drawBitmap(bm, null, tmp, bitmapPaint);
    }

    private void badge(Canvas c, String text, float x, float y, float d) {
        float w = badgeText.measureText(text) + 18 * d;
        float h = 24 * d;
        tmp.set(x, y, x + w, y + h);
        c.drawRoundRect(tmp, 6 * d, 6 * d, badgeBg);
        c.drawText(text, x + 9 * d, y + 16.5f * d, badgeText);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (mode == MODE_SLIDE && !showOriginal && original != null && lineArt != null && getWidth() > 0) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE || a == MotionEvent.ACTION_UP) {
                ratio = Math.max(0.02f, Math.min(0.98f, e.getX() / getWidth()));
                invalidate();
                if (a == MotionEvent.ACTION_UP) performClick();
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    @Override public boolean performClick() { return super.performClick(); }
}
