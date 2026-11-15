package com.inkline.lineart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 复刻 {@code userElaina/color-card} 的色卡提取能力，作为融合进 App 的第二个功能。
 *
 * <ul>
 *   <li>{@link #crowd} 对应原项目 {@code crowd(pth, limit, need)}：把颜色量化分类，
 *       按像素占比提取主色色卡，忽略占比低于 limit 的颜色；</li>
 *   <li>{@link #naive} 对应原项目 {@code naive(pth, needx, needy)}：把图片切成矩形网格，
 *       每格取一种代表色。</li>
 * </ul>
 * 纯 Java 实现，无 Android 依赖。
 */
public final class ColorCard {

    public static final class Swatch {
        public final int color;      // 0xFFRRGGBB
        public final int pixels;
        public final double ratio;
        Swatch(int color, int pixels, int total) {
            this.color = color;
            this.pixels = pixels;
            this.ratio = total > 0 ? (double) pixels / total : 0.0;
        }
        public String hex() { return String.format("#%06X", color & 0xFFFFFF); }
    }

    private ColorCard() {}

    /** 全局主色色卡：RGB 各量化为 3 bit（共 512 类），按占比排序取前 need 个 */
    public static List<Swatch> crowd(int[] argb, int w, int h, double limit, int need) {
        final int n = w * h;
        int[] count = new int[512];
        long[] sr = new long[512], sg = new long[512], sb = new long[512];
        for (int i = 0; i < n; i++) {
            int c = argb[i];
            int r = (c >>> 16) & 255, g = (c >>> 8) & 255, b = c & 255;
            int idx = ((r >> 5) << 6) | ((g >> 5) << 3) | (b >> 5);
            count[idx]++;
            sr[idx] += r; sg[idx] += g; sb[idx] += b;
        }
        List<Integer> bins = new ArrayList<>();
        for (int i = 0; i < 512; i++) if (count[i] > 0 && count[i] >= n * limit) bins.add(i);
        Collections.sort(bins, new Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) { return count[b] - count[a]; }
        });
        List<Swatch> out = new ArrayList<>();
        int take = Math.min(need, bins.size());
        for (int i = 0; i < take; i++) {
            int idx = bins.get(i), c = count[idx];
            int r = (int) (sr[idx] / c), g = (int) (sg[idx] / c), b = (int) (sb[idx] / c);
            out.add(new Swatch(0xFF000000 | (r << 16) | (g << 8) | b, c, n));
        }
        return out;
    }

    /** 网格区域色卡：nx * ny 个区块，每块取平均色（近似原项目 naive 的分区取色） */
    public static List<Swatch> naive(int[] argb, int w, int h, int nx, int ny) {
        nx = Math.max(1, nx); ny = Math.max(1, ny);
        List<Swatch> out = new ArrayList<>();
        for (int by = 0; by < ny; by++) {
            int y0 = h * by / ny, y1 = h * (by + 1) / ny;
            for (int bx = 0; bx < nx; bx++) {
                int x0 = w * bx / nx, x1 = w * (bx + 1) / nx;
                long sr = 0, sg = 0, sb = 0;
                int cnt = 0;
                for (int y = y0; y < y1; y++) {
                    int row = y * w;
                    for (int x = x0; x < x1; x++) {
                        int c = argb[row + x];
                        sr += (c >>> 16) & 255;
                        sg += (c >>> 8) & 255;
                        sb += c & 255;
                        cnt++;
                    }
                }
                if (cnt == 0) continue;
                int r = (int) (sr / cnt), g = (int) (sg / cnt), b = (int) (sb / cnt);
                out.add(new Swatch(0xFF000000 | (r << 16) | (g << 8) | b, cnt, w * h));
            }
        }
        return out;
    }
}
