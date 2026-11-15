package com.inkline.lineart;

import android.graphics.Bitmap;

/** Android 层包装：Bitmap <-> int[] 转换、分辨率限制。算法本体在 {@link LineArtCore}。 */
public final class LineArtEngine {

    /** 处理边长上限。1600px 在 203dpi 转印机下约 20cm，足够 A5/A4 纹身底稿，且避免手机 OOM。 */
    public static final int MAX_SIDE = 1600;

    private LineArtEngine() {}

    public static Bitmap process(Bitmap src, LineArtCore.Options opt) {
        if (src == null) throw new IllegalArgumentException("source bitmap is null");
        Bitmap work = fit(src, MAX_SIDE);
        int w = work.getWidth(), h = work.getHeight();
        int[] px = new int[w * h];
        work.getPixels(px, 0, w, 0, 0, w, h);
        int[] out = LineArtCore.process(px, w, h, opt);
        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        result.setPixels(out, 0, w, 0, 0, w, h);
        if (work != src) work.recycle();
        return result;
    }

    /** 等比缩放到最大边不超过 maxSide，不放大 */
    public static Bitmap fit(Bitmap src, int maxSide) {
        int w = src.getWidth(), h = src.getHeight();
        int m = Math.max(w, h);
        if (m <= maxSide) return src;
        float s = (float) maxSide / m;
        return Bitmap.createScaledBitmap(src,
                Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), true);
    }

    /** 取出 ARGB 像素数组，供色卡提取使用 */
    public static int[] pixels(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        return px;
    }
}
