package com.tencent.mps.srplayer.utils;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;

public class FileUtils {
    private static final String TAG = "FileUtils";

    public static String copyAssetsFileToSDCard(Context context) {
        AssetManager assetManager = context.getAssets();
        String licenseName = null;
        String[] files = null;
        String filePath = "";
        try {
            files = assetManager.list("");
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (files != null) {
            for (String file : files) {
                if (file.endsWith(".crt")) {
                    licenseName = file;
                    break;
                }
            }
        }

        if (licenseName == null) {
            return filePath;
        }

        InputStream in = null;
        OutputStream out = null;

        try {
            in = assetManager.open(licenseName);
            filePath = context.getExternalFilesDir("license/").getAbsolutePath() + "/" + licenseName;
            File outFile = new File(filePath);
            out = new FileOutputStream(outFile);
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return filePath;
    }


    /**
     * 获取视频的contentValue
     */
    public static ContentValues getVideoContentValues(Context context, File paramFile, long timestamp) {
        ContentValues localContentValues = new ContentValues();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            localContentValues.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM
                    + File.separator + context.getPackageName());
        }
        localContentValues.put(MediaStore.Video.Media.TITLE, paramFile.getName());
        localContentValues.put(MediaStore.Video.Media.DISPLAY_NAME, paramFile.getName());
        localContentValues.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        localContentValues.put(MediaStore.Video.Media.DATE_TAKEN, timestamp);
        localContentValues.put(MediaStore.Video.Media.DATE_MODIFIED, timestamp);
        localContentValues.put(MediaStore.Video.Media.DATE_ADDED, timestamp);
        localContentValues.put(MediaStore.Video.Media.SIZE, paramFile.length());
        return localContentValues;
    }

    /**
     * 将视频保存到系统相册
     */
    public static boolean saveVideoToAlbum(Context context, String videoFile) {
        Log.d(TAG, "saveVideoToAlbum() videoFile = [" + videoFile + "]");
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return saveVideoToAlbumBeforeQ(context, videoFile);
        } else {
            return saveVideoToAlbumAfterQ(context, videoFile);
        }
    }

    private static boolean saveVideoToAlbumAfterQ(Context context, String videoFile) {
        try {
            ContentResolver contentResolver = context.getContentResolver();
            File tempFile = new File(videoFile);
            ContentValues contentValues = getVideoContentValues(context, tempFile, System.currentTimeMillis());
            Uri uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues);
            copyFileAfterQ(context, contentResolver, tempFile, uri);
            contentValues.clear();
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0);
            context.getContentResolver().update(uri, contentValues, null, null);
            context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri));
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static boolean saveVideoToAlbumBeforeQ(Context context, String videoFile) {
        File picDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        File tempFile = new File(videoFile);
        File destFile = new File(picDir, context.getPackageName() + File.separator + tempFile.getName());
        FileInputStream ins = null;
        BufferedOutputStream ous = null;
        try {
            ins = new FileInputStream(tempFile);
            ous = new BufferedOutputStream(new FileOutputStream(destFile));
            long nread = 0L;
            byte[] buf = new byte[1024];
            int n;
            while ((n = ins.read(buf)) > 0) {
                ous.write(buf, 0, n);
                nread += n;
            }
            MediaScannerConnection.scanFile(
                    context,
                    new String[]{destFile.getAbsolutePath()},
                    new String[]{"video/*"},
                    (path, uri) -> {
                        Log.d(TAG, "saveVideoToAlbum: " + path + " " + uri);
                        // Scan Completed
                    });
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            try {
                if (ins != null) {
                    ins.close();
                }
                if (ous != null) {
                    ous.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static void copyFileAfterQ(Context context, ContentResolver localContentResolver, File tempFile, Uri localUri) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                context.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.Q) {
            //拷贝文件到相册的uri,android10及以上得这么干，否则不会显示。可以参考ScreenMediaRecorder的save方法
            OutputStream os = localContentResolver.openOutputStream(localUri);
            Files.copy(tempFile.toPath(), os);
            os.close();
            tempFile.delete();
        }
    }
}
