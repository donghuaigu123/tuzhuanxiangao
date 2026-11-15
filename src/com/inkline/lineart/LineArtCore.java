package com.inkline.lineart;

/**
 * 纯 Java 图像处理核心，不依赖任何 Android API，便于桌面端自测与复用。
 *
 * <p>功能融合自两个开源项目：</p>
 * <ul>
 *   <li>{@code userElaina/color-card} 的 {@code line()}（比较相邻像素 RGB 距离来勾线）、
 *       {@code onlyline}、{@code color} 调色与 {@code linear()} 色阶线性变换；</li>
 *   <li>{@code luckycucu/image-to-line-drawing} 的 "先提取线稿、再用 line-relifer 增强"
 *       两级思路 —— 这里用去孤立点、小连通域过滤、断线连接、毛刺修正来实现同等的
 *       "去噪 + 修正线条、让它更像笔画" 效果。</li>
 * </ul>
 *
 * <p>与参考项目的差别：参考项目一依赖两个 ONNX 模型、参考项目二依赖 ffmpeg + C++，
 * 两者都不适合直接跑在手机上。本实现用等价的传统 CV 流水线完成同样的目标，
 * 完全离线、零第三方依赖、零模型下载，适合做"傻瓜式"一键 App。</p>
 *
 * <p>输出默认纯白底 + 纯黑线（{@link Options#pureBW}），并支持水平镜像
 * （纹身转印需要镜像稿）、反色、调色、线条粗细，专为纹身转印打印机底稿优化。</p>
 */
public final class LineArtCore {

    /** 智能线稿：DoG 边缘 + 颜色距离 + 自适应二值化 + 线条增强（默认，适合纹身转印） */
    public static final int MODE_SMART = 0;
    /** 经典勾线：严格复刻 color-card 的 line() 相邻像素颜色距离算法 */
    public static final int MODE_CLASSIC = 1;

    public static final class Options {
        public int mode = MODE_SMART;
        /** 0..100 灵敏度：边缘检出的敏感程度，数值越大越容易检出弱边缘 */
        public int sensitivity = 50;
        /** 0..100 浓度（墨量）：数值越大越多弱边缘被画成黑线，线条更密更实；50 为默认 */
        public int density = 50;
        /** 纯黑白二值输出，底色绝对干净 */
        public boolean pureBW = true;
        /** 水平镜像，纹身转印稿需要 */
        public boolean mirror = false;
        /** 黑底白线 */
        public boolean invert = false;
        /** line-relifer 风格增强：去噪、连接断线、修毛刺 */
        public boolean enhance = true;
        /** 1~5 档线条粗细，每加一档做一次 3x3 形态学膨胀 */
        public int lineWidth = 1;
        /** 对应 color-card line() 的 color 参数：把底色替换成指定颜色 */
        public boolean recolor = false;
        public int backgroundColor = 0xFFFFFFFF;
    }

    private LineArtCore() {}

    /**
     * @param argb ARGB 像素数组，长度至少 w*h
     * @return 同尺寸的线稿 ARGB 像素数组
     */
    public static int[] process(int[] argb, int w, int h, Options opt) {
        if (argb == null || w < 3 || h < 3 || argb.length < w * h) {
            throw new IllegalArgumentException("image too small or buffer too short");
        }
        final Options o = (opt == null) ? new Options() : opt;
        final int n = w * h;

        // ---------- 1) 灰度 ----------
        int[] gray = new int[n];
        for (int i = 0; i < n; i++) {
            int c = argb[i];
            int r = (c >>> 16) & 255, g = (c >>> 8) & 255, b = c & 255;
            gray[i] = (r * 77 + g * 150 + b * 29) >> 8;   // Rec.601 亮度
        }

        // ---------- 2) linear()：自动色阶拉伸，把近白噪声压成纯白、近黑压成纯黑 ----------
        int[] lut = autoLevelsLut(gray, n);
        int[] leveled = new int[n];
        for (int i = 0; i < n; i++) leveled[i] = lut[gray[i]];

        // ---------- 3) 降噪 ----------
        int[] base = boxBlur(leveled, w, h, 1);

        // ---------- 4) 线强度图 ----------
        int[] strength = (o.mode == MODE_CLASSIC)
                ? classicStrength(argb, w, h, o.sensitivity)
                : smartStrength(base, argb, w, h);

        // ---------- 5) 线强度 -> 线稿灰度（线黑、底白） ----------
        int gain = 190 + o.sensitivity * 2;
        int[] lineGray = new int[n];
        for (int i = 0; i < n; i++) {
            int v = 255 - (strength[i] * gain / 255);
            lineGray[i] = v < 0 ? 0 : (v > 255 ? 255 : v);
        }

        // ---------- 6) 自适应二值化（Bradley 局部均值 + 浓度偏移 + 纯白底保护） ----------
        int[] mask = binarize(lineGray, w, h, o.sensitivity, o.density);

        // ---------- 7) line-relifer 风格线条增强 ----------
        if (o.enhance) mask = enhanceLines(mask, w, h, o.lineWidth);

        // ---------- 8) 输出 ----------
        int bg = o.recolor ? o.backgroundColor : 0xFFFFFFFF;
        int fg = 0xFF000000;
        if (o.invert) { bg = 0xFF000000; fg = 0xFFFFFFFF; }
        int[] out = new int[n];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int si = o.mirror ? row + (w - 1 - x) : row + x;
                int di = row + x;
                if (mask[si] == 1) out[di] = fg;
                else if (o.pureBW) out[di] = bg;
                else out[di] = blendGray(lineGray[si], bg);
            }
        }
        return out;
    }

    // ======================================================================
    // color-card 风格：相邻像素颜色距离勾线
    // ======================================================================

    /**
     * 复刻 {@code line(pth, dis, onlyline=1, color)}：
     * 像素与右邻 / 下邻的 RGB 曼哈顿距离超过 dis 就判定为轮廓。
     * dis 越大勾线越少（原项目 dis>=765 时无线条），这里由 sensitivity 反向映射。
     */
    private static int[] classicStrength(int[] argb, int w, int h, int sensitivity) {
        int s = clamp(sensitivity, 0, 100);
        int dis = Math.max(6, 200 - s * 18 / 10);   // sens=50 -> 110，与原项目默认 96 接近
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int i = row + x, d = 0;
                if (x + 1 < w) d = Math.max(d, rgbDist(argb[i], argb[i + 1]));
                if (y + 1 < h) d = Math.max(d, rgbDist(argb[i], argb[i + w]));
                out[i] = d > dis ? Math.min(255, 90 + (d - dis) * 2) : 0;
            }
        }
        return out;
    }

    private static int rgbDist(int c1, int c2) {
        return Math.abs(((c1 >>> 16) & 255) - ((c2 >>> 16) & 255))
             + Math.abs(((c1 >>> 8) & 255) - ((c2 >>> 8) & 255))
             + Math.abs((c1 & 255) - (c2 & 255));
    }

    /**
     * 智能模式：DoG（高斯差分）+ Sobel 梯度 + color-card 颜色距离 三路融合。
     * DoG 对细窄亮环存在零交叉盲区（浅色太阳、浅色花瓣容易整圈丢失），
     * Sobel 能补上阶跃边缘；颜色距离则对「亮度相近但色相不同」的边界敏感，三者互补。
     */
    private static int[] smartStrength(int[] base, int[] argb, int w, int h) {
        final int n = w * h;
        int[] near = boxBlur(base, w, h, 1);
        int[] far = boxBlur(boxBlur(base, w, h, 2), w, h, 2);
        int[] grad = sobel(base, w, h);
        int[] colorEdge = colorDistance(argb, w, h);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int dog = Math.abs(far[i] - near[i]) * 3;
            int v = (dog * 3 + grad[i] * 4 + colorEdge[i] * 4) / 11;
            // 灰度相同、色相不同的边界（如红花压在蓝水上）灰度边缘几乎为零，
            // 这里给色差边缘一个保底强度，避免整块轮廓被自适应阈值吞掉。
            int floor = colorEdge[i] * 3 / 5;
            if (v < floor) v = floor;
            out[i] = v > 255 ? 255 : v;
        }
        return out;
    }

    /** 3x3 Sobel 梯度幅值（|Gx|+|Gy| 近似），对阶跃边缘和细窄亮环敏感 */
    private static int[] sobel(int[] g, int w, int h) {
        int[] out = new int[w * h];
        for (int y = 1; y < h - 1; y++) {
            int row = y * w;
            for (int x = 1; x < w - 1; x++) {
                int i = row + x;
                int gx = -g[i - w - 1] - 2 * g[i - 1] - g[i + w - 1]
                       + g[i - w + 1] + 2 * g[i + 1] + g[i + w + 1];
                int gy = -g[i - w - 1] - 2 * g[i - w] - g[i - w + 1]
                       + g[i + w - 1] + 2 * g[i + w] + g[i + w + 1];
                int v = (Math.abs(gx) + Math.abs(gy)) / 2;
                out[i] = v > 255 ? 255 : v;
            }
        }
        return out;
    }

    private static int[] colorDistance(int[] argb, int w, int h) {
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int i = row + x, d = 0;
                if (x + 1 < w) d = Math.max(d, rgbDist(argb[i], argb[i + 1]));
                if (y + 1 < h) d = Math.max(d, rgbDist(argb[i], argb[i + w]));
                out[i] = Math.min(255, d / 2);
            }
        }
        return out;
    }

    // ======================================================================
    // linear()：自动色阶 / 线性颜色变换
    // ======================================================================

    /** 取 0.5% / 99.5% 分位作为 black0 / white0，线性映射到 [0,255] */
    private static int[] autoLevelsLut(int[] gray, int n) {
        int[] hist = new int[256];
        for (int i = 0; i < n; i++) hist[gray[i]]++;
        int cut = Math.max(1, n / 200);
        int lo = 0, hi = 255, cum = 0;
        for (int i = 0; i < 256; i++) { cum += hist[i]; if (cum >= cut) { lo = i; break; } }
        cum = 0;
        for (int i = 255; i >= 0; i--) { cum += hist[i]; if (cum >= cut) { hi = i; break; } }
        if (hi - lo < 24) { lo = 0; hi = 255; }
        int span = Math.max(1, hi - lo);
        int[] lut = new int[256];
        for (int v = 0; v < 256; v++) {
            int t = (v - lo) * 255 / span;
            lut[v] = t < 0 ? 0 : (t > 255 ? 255 : t);
        }
        return lut;
    }

    private static int blendGray(int v, int bg) {
        int r = ((bg >>> 16) & 255) * v / 255;
        int g = ((bg >>> 8) & 255) * v / 255;
        int b = (bg & 255) * v / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // ======================================================================
    // 基础滤波
    // ======================================================================

    /** 可分离 box blur（三次近似高斯），O(n)，边界按 clamp 复制 */
    private static int[] boxBlur(int[] src, int w, int h, int radius) {
        int[] tmp = new int[w * h];
        int[] out = new int[w * h];
        int win = radius * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w, sum = 0;
            for (int k = -radius; k <= radius; k++) sum += src[row + cx(k, w)];
            for (int x = 0; x < w; x++) {
                tmp[row + x] = sum / win;
                sum += src[row + cx(x + radius + 1, w)] - src[row + cx(x - radius, w)];
            }
        }
        for (int x = 0; x < w; x++) {
            int sum = 0;
            for (int k = -radius; k <= radius; k++) sum += tmp[cy(k, h) * w + x];
            for (int y = 0; y < h; y++) {
                out[y * w + x] = sum / win;
                sum += tmp[cy(y + radius + 1, h) * w + x] - tmp[cy(y - radius, h) * w + x];
            }
        }
        return out;
    }

    private static int cx(int v, int w) { return v < 0 ? 0 : (v >= w ? w - 1 : v); }
    private static int cy(int v, int h) { return v < 0 ? 0 : (v >= h ? h - 1 : v); }

    // ======================================================================
    // 自适应二值化
    // ======================================================================

    /**
     * Bradley 自适应阈值：像素低于局部均值 * (1-k) 判为黑线。
     * 附加 "lineGray < 250" 的硬条件，保证大面积底色绝对纯白、不带灰噪点。
     * 积分图用 int 即可（最大和 = 像素数 * 255 < 2^31）。
     */
    private static int[] binarize(int[] lg, int w, int h, int sensitivity, int density) {
        int n = w * h, stride = w + 1;
        int[] sumImg = new int[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            int row = y * w, cur = (y + 1) * stride, prev = y * stride, rowSum = 0;
            for (int x = 0; x < w; x++) {
                rowSum += lg[row + x];
                sumImg[cur + x + 1] = sumImg[prev + x + 1] + rowSum;
            }
        }
        int radius = Math.max(6, Math.min(w, h) / 24);
        double k = 0.02 + clamp(sensitivity, 0, 100) * 0.0013;
        // 浓度：整体平移线稿灰度。浓度高 -> 有效值更小 -> 更多弱边缘越过阈值变成黑线。
        // 白底保护始终基于原始 lineGray，所以无论浓度多高，纯白底色都不会变脏。
        int bias = (int) ((clamp(density, 0, 100) - 50) * 0.7);   // -35 .. +35
        int[] mask = new int[n];
        for (int y = 0; y < h; y++) {
            int y1 = Math.max(0, y - radius), y2 = Math.min(h - 1, y + radius);
            int top = y1 * stride, bottom = (y2 + 1) * stride;
            for (int x = 0; x < w; x++) {
                int x1 = Math.max(0, x - radius), x2 = Math.min(w - 1, x + radius);
                long sum = (long) sumImg[bottom + x2 + 1] - sumImg[top + x2 + 1]
                         - sumImg[bottom + x1] + sumImg[top + x1];
                int count = (x2 - x1 + 1) * (y2 - y1 + 1);
                double mean = (double) sum / count;
                int raw = lg[y * w + x];
                int v = raw - bias;
                mask[y * w + x] = (raw < 250 && v < mean * (1.0 - k)) ? 1 : 0;
            }
        }
        return mask;
    }

    // ======================================================================
    // line-relifer 风格增强
    // ======================================================================

    private static int[] enhanceLines(int[] mask, int w, int h, int lineWidth) {
        int width = clamp(lineWidth, 1, 5);
        int[] a = removeIsolated(mask, w, h);
        a = removeSmallComponents(a, w, h);
        // 粗细：每加一档做一次 3x3 膨胀（线宽约 +2px），最高 5 档
        for (int t = 1; t < width; t++) a = dilate(a, w, h);
        a = fillHoles(a, w, h);
        return removeSmallComponents(a, w, h);
    }

    /** 8 邻域黑点少于 2 个的孤立点直接抹掉 */
    private static int[] removeIsolated(int[] m, int w, int h) {
        int[] o = m.clone();
        for (int y = 1; y < h - 1; y++) {
            int row = y * w;
            for (int x = 1; x < w - 1; x++) {
                int i = row + x;
                if (m[i] == 0) continue;
                int c = m[i - w - 1] + m[i - w] + m[i - w + 1]
                      + m[i - 1] + m[i + 1]
                      + m[i + w - 1] + m[i + w] + m[i + w + 1];
                if (c < 2) o[i] = 0;
            }
        }
        return o;
    }

    /** 白点被 >=7 个黑点包围时填成黑，连接断裂的笔画 */
    private static int[] fillHoles(int[] m, int w, int h) {
        int[] o = m.clone();
        for (int y = 1; y < h - 1; y++) {
            int row = y * w;
            for (int x = 1; x < w - 1; x++) {
                int i = row + x;
                if (m[i] == 1) continue;
                int c = m[i - w - 1] + m[i - w] + m[i - w + 1]
                      + m[i - 1] + m[i + 1]
                      + m[i + w - 1] + m[i + w] + m[i + w + 1];
                if (c >= 7) o[i] = 1;
            }
        }
        return o;
    }

    /** 3x3 膨胀，用于加粗线条 */
    private static int[] dilate(int[] m, int w, int h) {
        int[] o = m.clone();
        for (int y = 1; y < h - 1; y++) {
            int row = y * w;
            for (int x = 1; x < w - 1; x++) {
                int i = row + x;
                if (m[i] == 1) continue;
                if (m[i - w - 1] + m[i - w] + m[i - w + 1] + m[i - 1] + m[i + 1]
                    + m[i + w - 1] + m[i + w] + m[i + w + 1] > 0) o[i] = 1;
            }
        }
        return o;
    }

    /**
     * 8 连通域面积过滤，删掉零散噪点，让底色彻底干净。
     * 用一个 int 数组同时充当 "已访问列表"（从头部增长）与 "DFS 栈"（从尾部增长），
     * 省掉两份 n 长数组，降低手机端内存占用。
     */
    private static int[] removeSmallComponents(int[] m, int w, int h) {
        int n = w * h;
        boolean[] seen = new boolean[n];
        int[] buf = new int[n];
        int[] out = m.clone();
        int minArea = Math.max(8, n / 40000);
        for (int seed = 0; seed < n; seed++) {
            if (m[seed] == 0 || seen[seed]) continue;
            int cc = 0, sp = n;
            buf[--sp] = seed;
            seen[seed] = true;
            while (sp < n) {
                int p = buf[sp++];
                buf[cc++] = p;
                int x = p % w, y = p / w;
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = y + dy;
                    if (ny < 0 || ny >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = x + dx;
                        if (nx < 0 || nx >= w) continue;
                        int q = ny * w + nx;
                        if (m[q] == 1 && !seen[q]) { seen[q] = true; buf[--sp] = q; }
                    }
                }
            }
            if (cc < minArea) {
                for (int t = 0; t < cc; t++) out[buf[t]] = 0;
            }
        }
        return out;
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
