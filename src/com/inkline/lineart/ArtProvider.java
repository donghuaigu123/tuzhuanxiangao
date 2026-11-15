package com.inkline.lineart;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 极简只读 ContentProvider，用于把应用内部导出的线稿以 content:// 形式分享出去。
 * 不依赖 AndroidX / support 库，避免 file:// URI 在 Android 7.0+ 触发 FileUriExposedException。
 */
public class ArtProvider extends ContentProvider {

    public static final String AUTHORITY = "com.inkline.lineart.files";
    public static final String FILE_NAME = "lineart.png";
    public static final Uri SHARE_URI = Uri.parse("content://" + AUTHORITY + "/" + FILE_NAME);

    public static File exportFile(Context c) {
        File dir = new File(c.getFilesDir(), "export");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, FILE_NAME);
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String seg = uri.getLastPathSegment();
        if (seg == null || !FILE_NAME.equals(seg)) throw new FileNotFoundException("unknown uri: " + uri);
        File f = exportFile(getContext());
        if (!f.exists()) throw new FileNotFoundException(f.getAbsolutePath());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return "image/png"; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
