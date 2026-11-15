import com.inkline.lineart.ColorCard;
import com.inkline.lineart.LineArtCore;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

/**
 * 纯 Java SE 算法自测：不需要 Android 设备即可验证线稿算法、生成预览图与色卡统计。
 * 用法: java -cp <classes> CoreTest <输入图片> <输出目录>
 */
public class CoreTest {

    public static void main(String[] args) throws Exception {
        File in = new File(args.length > 0 ? args[0] : "tools/sample.png");
        File dir = new File(args.length > 1 ? args[1] : "docs");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建输出目录");

        BufferedImage src = ImageIO.read(in);
        if (src == null) throw new Exception("无法读取 " + in);
        int w = src.getWidth(), h = src.getHeight();
        int[] px = src.getRGB(0, 0, w, h, null, 0, w);
        System.out.println("输入: " + in.getName() + "  " + w + "x" + h);

        // 1) 智能线稿（默认，适合纹身转印）
        LineArtCore.Options smart = new LineArtCore.Options();
        int[] smartOut = LineArtCore.process(px, w, h, smart);
        write(smartOut, w, h, new File(dir, "line_smart.png"));
        stat("智能线稿", smartOut);

        // 2) 经典勾线（复刻 color-card 的 line()）
        LineArtCore.Options classic = new LineArtCore.Options();
        classic.mode = LineArtCore.MODE_CLASSIC;
        int[] classicOut = LineArtCore.process(px, w, h, classic);
        write(classicOut, w, h, new File(dir, "line_classic.png"));
        stat("经典勾线", classicOut);

        // 3) 纹身转印底稿：加粗线条 + 水平镜像
        LineArtCore.Options tattoo = new LineArtCore.Options();
        tattoo.lineWidth = 2;
        tattoo.mirror = true;
        int[] tattooOut = LineArtCore.process(px, w, h, tattoo);
        write(tattooOut, w, h, new File(dir, "line_tattoo_mirror.png"));
        stat("镜像转印稿", tattooOut);

        // 4) 并排对比预览图（原图 | 智能线稿 | 经典勾线），纯像素拼接，适配无显示环境
        BufferedImage combo = new BufferedImage(w * 3, h, BufferedImage.TYPE_INT_ARGB);
        combo.setRGB(0, 0, w, h, px, 0, w);
        combo.setRGB(w, 0, w, h, smartOut, 0, w);
        combo.setRGB(w * 2, 0, w, h, classicOut, 0, w);
        ImageIO.write(combo, "png", new File(dir, "preview.png"));
        System.out.println("预览图: " + new File(dir, "preview.png"));

        // 5) 色卡
        List<ColorCard.Swatch> crowd = ColorCard.crowd(px, w, h, 0.01, 8);
        List<ColorCard.Swatch> naive = ColorCard.naive(px, w, h, 4, 2);
        StringBuilder sb = new StringBuilder("色卡 crowd: ");
        for (ColorCard.Swatch s : crowd) sb.append(s.hex()).append('(').append(String.format("%.1f%%", s.ratio * 100)).append(") ");
        System.out.println(sb);
        sb = new StringBuilder("色卡 naive 4x2: ");
        for (ColorCard.Swatch s : naive) sb.append(s.hex()).append(' ');
        System.out.println(sb);
    }

    private static BufferedImage write(int[] out, int w, int h, File f) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, out, 0, w);
        if (f != null) ImageIO.write(img, "png", f);
        return img;
    }

    private static void stat(String name, int[] out) {
        int black = 0, white = 0, other = 0;
        for (int c : out) {
            if (c == 0xFF000000) black++;
            else if (c == 0xFFFFFFFF) white++;
            else other++;
        }
        double pct = 100.0 * (black + white) / out.length;
        System.out.printf("%s: 黑线 %.2f%%  纯白底 %.2f%%  其它 %.2f%%  二值纯度 %.2f%%%n",
                name, 100.0 * black / out.length, 100.0 * white / out.length,
                100.0 * other / out.length, pct);
    }
}
