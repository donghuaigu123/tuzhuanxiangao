package com.inkline.lineart;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 「图转线稿」主界面。
 *
 * 设计目标：傻瓜式 —— 导入图片即自动出线稿，所有参数改动都自动重新生成；
 * 同时提供原图/线稿滑动对比、并排对比、按住看原图、镜像转印、纯黑白输出、
 * 一键保存与分享，以及融合自 color-card 的色卡提取。
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK = 1001;
    private static final int REQ_PERM = 1002;
    private static final int DARK = 0xFF1B1B1F;
    private static final int TEXT = 0xFF33383F;
    private static final int MUTED = 0xFF6A7080;
    private static final int ACCENT = 0xFFFF9E4A;
    private static final int CHIP_OFF = 0xFFEEF0F4;

    private CompareView compare;
    private TextView statusView, emptyHint, sensValue;
    private ProgressBar progress;
    private SeekBar sensBar;
    private Button btnSmart, btnClassic, btnColor, btnMirror, btnBW, btnSide, btnPeek;
    private Button btnPick, btnSave, btnShare;

    private Bitmap original, result;
    private int mode = LineArtCore.MODE_SMART;
    private int sensitivity = 50;
    private boolean mirror = false, pureBW = true, sideBySide = false;

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean busy = false, pending = false;
    private final Runnable reprocessTask = new Runnable() {
        @Override public void run() { runProcess(); }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        refreshToggles();
        handleIncomingIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
        ui.removeCallbacks(reprocessTask);
    }

    // ==================================================================
    // UI
    // ==================================================================

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF4F5F7);

        // ---------- 顶部标题 ----------
        LinearLayout header = hBox();
        header.setBackgroundColor(DARK);
        header.setPadding(dp(16), dp(13), dp(12), dp(13));
        LinearLayout titles = vBox();
        titles.addView(tv("图转线稿", 19, Color.WHITE, true));
        TextView sub = tv("一键生成 · 纹身转印底稿", 11, 0xFFB8BCC6, false);
        sub.setPadding(0, dp(2), 0, 0);
        titles.addView(sub);
        header.addView(titles, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button help = chip("使用说明");
        help.setTextColor(0xFFDDDDE2);
        help.setBackground(bg(0x33FFFFFF, 8, 0, 0));
        help.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showHelp(); }
        });
        header.addView(help);
        root.addView(header, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // ---------- 状态条 ----------
        statusView = tv("准备就绪 · 请选择一张彩色图片", 12, MUTED, false);
        statusView.setPadding(dp(14), dp(8), dp(14), dp(8));
        root.addView(statusView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // ---------- 图像对比区 ----------
        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.WHITE);
        compare = new CompareView(this);
        stage.addView(compare, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        emptyHint = tv("点击下方「选择图片」\n导入彩色照片，自动输出白底黑线线稿", 14, 0xFF8A90A0, false);
        emptyHint.setGravity(Gravity.CENTER);
        emptyHint.setLineSpacing(dp(6), 1f);
        stage.addView(emptyHint, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        stage.addView(progress, new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER));
        root.addView(stage, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---------- 底部面板 ----------
        LinearLayout panel = vBox();
        panel.setBackgroundColor(Color.WHITE);
        panel.setPadding(dp(12), dp(10), dp(12), dp(12));
        panel.setElevation(dp(6));

        // 行 1：模式 + 色卡
        LinearLayout row1 = hBox();
        row1.setPadding(0, 0, 0, dp(6));
        btnSmart = chip("智能线稿");
        btnClassic = chip("经典勾线");
        btnColor = chip("提取色卡");
        row1.addView(btnSmart, weighted(1f, 3));
        row1.addView(btnClassic, weighted(1f, 3));
        row1.addView(btnColor, weighted(1f, 3));
        panel.addView(row1, matchWrap());

        // 行 2：灵敏度
        LinearLayout row2 = hBox();
        row2.addView(tv("灵敏度", 12, TEXT, false));
        sensBar = new SeekBar(this);
        sensBar.setMax(100);
        sensBar.setProgress(sensitivity);
        row2.addView(sensBar, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        sensValue = tv(String.valueOf(sensitivity), 12, TEXT, true);
        sensValue.setPadding(dp(8), 0, 0, 0);
        row2.addView(sensValue);
        panel.addView(row2, matchWrap());

        // 行 3：开关
        LinearLayout row3 = hBox();
        row3.setPadding(0, dp(2), 0, dp(8));
        btnMirror = chip("镜像转印");
        btnBW = chip("纯黑白");
        btnSide = chip("并排对比");
        btnPeek = chip("按住看原图");
        row3.addView(btnMirror, weighted(1f, 2));
        row3.addView(btnBW, weighted(1f, 2));
        row3.addView(btnSide, weighted(1f, 2));
        row3.addView(btnPeek, weighted(1f, 2));
        panel.addView(row3, matchWrap());

        // 行 4：主操作
        LinearLayout row4 = hBox();
        btnPick = bigButton("＋ 选择图片", DARK, Color.WHITE);
        btnSave = bigButton("保存底稿", CHIP_OFF, TEXT);
        btnShare = bigButton("分享", CHIP_OFF, TEXT);
        row4.addView(btnPick, weighted(1.6f, 3));
        row4.addView(btnSave, weighted(1f, 3));
        row4.addView(btnShare, weighted(1f, 3));
        panel.addView(row4, matchWrap());

        root.addView(panel, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        // ---------- 事件 ----------
        btnSmart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { mode = LineArtCore.MODE_SMART; refreshToggles(); scheduleReprocess(); }
        });
        btnClassic.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { mode = LineArtCore.MODE_CLASSIC; refreshToggles(); scheduleReprocess(); }
        });
        btnColor.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showColorCard(); }
        });
        btnMirror.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { mirror = !mirror; refreshToggles(); scheduleReprocess(); }
        });
        btnBW.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pureBW = !pureBW; refreshToggles(); scheduleReprocess(); }
        });
        btnSide.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                sideBySide = !sideBySide;
                compare.setMode(sideBySide ? CompareView.MODE_SIDE : CompareView.MODE_SLIDE);
                refreshToggles();
            }
        });
        btnPeek.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) { compare.setShowOriginal(true); return true; }
                if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    compare.setShowOriginal(false); return true;
                }
                return false;
            }
        });
        btnPick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickImage(); }
        });
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveResult(); }
        });
        btnShare.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { shareResult(); }
        });
        sensBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                sensitivity = p;
                sensValue.setText(String.valueOf(p));
                if (fromUser) scheduleReprocess();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { scheduleReprocess(); }
        });
    }

    private void refreshToggles() {
        styleChip(btnSmart, mode == LineArtCore.MODE_SMART);
        styleChip(btnClassic, mode == LineArtCore.MODE_CLASSIC);
        styleChip(btnMirror, mirror);
        styleChip(btnBW, pureBW);
        styleChip(btnSide, sideBySide);
        btnColor.setBackground(bg(CHIP_OFF, 8, 0, 0));
        btnColor.setTextColor(TEXT);
        btnPeek.setBackground(bg(CHIP_OFF, 8, 0, 0));
        btnPeek.setTextColor(TEXT);
    }

    private void styleChip(Button b, boolean on) {
        b.setBackground(bg(on ? (b == btnMirror ? ACCENT : DARK) : CHIP_OFF, 8, 0, 0));
        b.setTextColor(on ? Color.WHITE : TEXT);
    }

    // ==================================================================
    // 图片导入
    // ==================================================================

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(Intent.createChooser(i, "选择一张彩色图片"), REQ_PICK);
        } catch (Exception e) {
            toast("无法打开系统图片选择器");
        }
    }

    private void handleIncomingIntent(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return;
        Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u != null) loadAndProcess(u);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null) {
            Uri u = data.getData();
            if (u == null) { toast("没有获取到图片"); return; }
            try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception ignored) {}
            loadAndProcess(u);
        }
    }

    private void loadAndProcess(final Uri uri) {
        progress.setVisibility(View.VISIBLE);
        emptyHint.setVisibility(View.GONE);
        statusView.setText("正在读取图片…");
        pool.execute(new Runnable() {
            @Override public void run() {
                try {
                    final Bitmap bm = decodeUri(uri);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            Bitmap old = original;
                            original = bm;
                            result = null;
                            compare.setBitmaps(bm, null);
                            if (old != null) old.recycle();
                            statusView.setText("已导入 " + bm.getWidth() + "×" + bm.getHeight() + " · 正在生成线稿…");
                            runProcess();
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            progress.setVisibility(View.GONE);
                            statusView.setText("读取失败，请换一张图片");
                            toast("读取图片失败：" + t.getMessage());
                        }
                    });
                }
            }
        });
    }

    private Bitmap decodeUri(Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("无法打开图片流");
        BitmapFactory.decodeStream(in, null, bounds);
        in.close();
        int sw = bounds.outWidth, sh = bounds.outHeight;
        if (sw <= 0 || sh <= 0) throw new IOException("无法解析图片尺寸");

        final int target = LineArtEngine.MAX_SIDE;
        int sample = 1;
        while (Math.max(sw, sh) / sample > target * 2) sample *= 2;
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = sample;
        opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
        InputStream in2 = getContentResolver().openInputStream(uri);
        if (in2 == null) throw new IOException("无法打开图片流");
        Bitmap bm = BitmapFactory.decodeStream(in2, null, opt);
        in2.close();
        if (bm == null) throw new IOException("图片解码失败");

        if (Math.max(bm.getWidth(), bm.getHeight()) > target) {
            Bitmap scaled = LineArtEngine.fit(bm, target);
            if (scaled != bm) { bm.recycle(); bm = scaled; }
        }
        int rot = readRotation(uri);
        if (rot != 0) {
            Matrix m = new Matrix();
            m.postRotate(rot);
            Bitmap rotated = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            if (rotated != bm) { bm.recycle(); bm = rotated; }
        }
        return bm;
    }

    private int readRotation(Uri uri) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) return 0;
            ExifInterface ex = new ExifInterface(in);
            int o = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            in.close();
            if (o == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (o == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (o == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (Throwable ignored) {}
        return 0;
    }

    // ==================================================================
    // 线稿生成
    // ==================================================================

    private void scheduleReprocess() {
        ui.removeCallbacks(reprocessTask);
        ui.postDelayed(reprocessTask, 280);
    }

    private void runProcess() {
        if (original == null) return;
        if (busy) { pending = true; return; }
        busy = true;
        progress.setVisibility(View.VISIBLE);
        statusView.setText("正在生成线稿…");

        final LineArtCore.Options o = new LineArtCore.Options();
        o.mode = mode;
        o.sensitivity = sensitivity;
        o.pureBW = pureBW;
        o.mirror = mirror;
        o.enhance = true;
        o.lineWidth = 1;
        final Bitmap src = original;

        pool.execute(new Runnable() {
            @Override public void run() {
                try {
                    final Bitmap r = LineArtEngine.process(src, o);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            Bitmap old = result;
                            result = r;
                            compare.setBitmaps(original, r);
                            if (old != null) old.recycle();
                            progress.setVisibility(View.GONE);
                            statusView.setText("完成 · " + r.getWidth() + "×" + r.getHeight()
                                    + " · 白底黑线" + (mirror ? " · 已镜像" : "") + " · 可直接转印");
                            busy = false;
                            if (pending) { pending = false; runProcess(); }
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            progress.setVisibility(View.GONE);
                            statusView.setText("生成失败，可调低灵敏度后重试");
                            toast("生成失败：" + t.getMessage());
                            busy = false;
                        }
                    });
                }
            }
        });
    }

    // ==================================================================
    // 保存 / 分享
    // ==================================================================

    private void saveResult() {
        if (result == null) { toast("请先生成线稿"); return; }
        String name = "线稿_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                java.util.Locale.CHINA).format(new java.util.Date()) + ".png";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/图转线稿");
                cv.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new IOException("相册插入失败");
                OutputStream os = getContentResolver().openOutputStream(uri);
                if (os == null) throw new IOException("无法打开输出流");
                boolean ok = result.compress(Bitmap.CompressFormat.PNG, 100, os);
                os.flush();
                os.close();
                if (!ok) throw new IOException("PNG 编码失败");
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(uri, done, null, null);
            } else {
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
                    toast("请授予存储权限后再次点击保存");
                    return;
                }
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES), "图转线稿");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建目录");
                File f = new File(dir, name);
                FileOutputStream fo = new FileOutputStream(f);
                boolean ok = result.compress(Bitmap.CompressFormat.PNG, 100, fo);
                fo.flush();
                fo.close();
                if (!ok) throw new IOException("PNG 编码失败");
                MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()},
                        new String[]{"image/png"}, null);
            }
            writeShareCopy();
            statusView.setText("已保存 · " + name + "（相册/Pictures/图转线稿）");
            toast("已保存到相册 Pictures/图转线稿");
        } catch (Throwable t) {
            toast("保存失败：" + t.getMessage());
        }
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_PERM && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            saveResult();
        }
    }

    private void writeShareCopy() throws IOException {
        File f = ArtProvider.exportFile(this);
        FileOutputStream fo = new FileOutputStream(f);
        boolean ok = result.compress(Bitmap.CompressFormat.PNG, 100, fo);
        fo.flush();
        fo.close();
        if (!ok) throw new IOException("PNG 编码失败");
    }

    private void shareResult() {
        if (result == null) { toast("请先生成线稿"); return; }
        try {
            writeShareCopy();
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("image/png");
            i.putExtra(Intent.EXTRA_STREAM, ArtProvider.SHARE_URI);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "分享线稿"));
        } catch (Throwable t) {
            toast("分享失败：" + t.getMessage());
        }
    }

    // ==================================================================
    // 色卡（融合 color-card 的 crowd / naive）
    // ==================================================================

    private void showColorCard() {
        if (original == null) { toast("请先选择图片"); return; }
        int w = original.getWidth(), h = original.getHeight();
        int[] px = LineArtEngine.pixels(original);
        List<ColorCard.Swatch> global = ColorCard.crowd(px, w, h, 0.01, 10);
        List<ColorCard.Swatch> grid = ColorCard.naive(px, w, h, 4, 2);

        LinearLayout box = vBox();
        box.setPadding(dp(20), dp(10), dp(20), dp(10));
        box.addView(tv("融合自 color-card 的取色能力 · 点击色块复制色值", 12, MUTED, false));
        box.addView(swatchSection("全局主色 · crowd(limit=0.01)", global));
        box.addView(swatchSection("区域取色 · naive(4×2)", grid));
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this).setTitle("提取色卡").setView(sv)
                .setPositiveButton("关闭", null).show();
    }

    private View swatchSection(String title, List<ColorCard.Swatch> list) {
        LinearLayout sec = vBox();
        sec.setPadding(0, dp(14), 0, 0);
        sec.addView(tv(title, 13, TEXT, true));
        LinearLayout row = hBox();
        row.setPadding(0, dp(6), 0, 0);
        for (final ColorCard.Swatch s : list) {
            LinearLayout cell = vBox();
            cell.setPadding(dp(3), dp(3), dp(3), dp(3));
            View color = new View(this);
            color.setBackgroundColor(s.color);
            cell.addView(color, new LinearLayout.LayoutParams(dp(54), dp(44)));
            TextView hex = tv(s.hex(), 10, MUTED, false);
            hex.setPadding(0, dp(3), 0, 0);
            cell.addView(hex);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("color", s.hex()));
                        toast("已复制 " + s.hex());
                    }
                }
            });
            row.addView(cell);
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.addView(row);
        sec.addView(hs);
        return sec;
    }

    private void showHelp() {
        String msg = "1. 点「＋ 选择图片」导入彩色照片（也可以从相册直接把图片分享到本应用）。\n\n"
                + "2. 导入后自动生成线稿，无需任何设置：默认纯白底、纯黑线、去噪增强，"
                + "可直接作为纹身转印打印机底稿。\n\n"
                + "3. 左右拖动中间的分割线对比原图与线稿；「并排对比」可同时看两图；"
                + "「按住看原图」可临时查看原图。\n\n"
                + "4. 「镜像转印」会水平翻转图案 —— 纹身转印必须打印镜像稿，转印到皮肤上才是正的。\n\n"
                + "5. 「灵敏度」越高线条越多；「智能线稿」适合照片/插画，「经典勾线」复刻 "
                + "color-card 的相邻像素色差勾线算法。\n\n"
                + "6. 「提取色卡」融合 color-card 的 crowd / naive，从原图提取主色，点击色块复制 HEX。\n\n"
                + "7. 「保存底稿」导出无损 PNG 到相册 Pictures/图转线稿；「分享」可直接发给打印机 App。\n\n"
                + "全程离线处理，不上传任何图片，不申请联网权限。";
        new AlertDialog.Builder(this).setTitle("使用说明").setMessage(msg)
                .setPositiveButton("知道了", null).show();
    }

    // ==================================================================
    // UI 小工具
    // ==================================================================

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView tv(String text, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout hBox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout vBox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted(float weight, int marginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        p.setMargins(dp(marginDp), dp(2), dp(marginDp), dp(2));
        return p;
    }

    private GradientDrawable bg(int color, float radiusDp, int strokeColor, int strokePx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokePx > 0) g.setStroke(strokePx, strokeColor);
        return g;
    }

    private Button chip(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT);
        b.setSingleLine(true);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(6), dp(7), dp(6), dp(7));
        b.setBackground(bg(CHIP_OFF, 8, 0, 0));
        b.setTextColor(TEXT);
        return b;
    }

    private Button bigButton(String text, int bgColor, int textColor) {
        Button b = chip(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(dp(8), dp(11), dp(8), dp(11));
        b.setBackground(bg(bgColor, 9, 0, 0));
        b.setTextColor(textColor);
        return b;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
