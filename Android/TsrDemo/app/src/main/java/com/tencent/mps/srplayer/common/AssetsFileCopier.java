package com.tencent.mps.srplayer.common;

import android.content.Context;
import android.os.Environment;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class AssetsFileCopier {

    private static final String TAG = "AssetsFileCopier";

    /**
     * 将 Assets 中的文件原样拷贝到应用外部存储，保留 assets 内的子目录结构。
     *
     * @param context 上下文对象
     * @param assetFileName Assets 中的相对路径（如 "video/test.h264"）
     * @return 拷贝后的文件对象，如果失败返回 null
     */
    public static File copyAssetToExternalFilesDir(Context context, String assetFileName) {
        // 1. 检查外部存储是否可用
        if (!isExternalStorageWritable()) {
            Log.e(TAG, "External storage not available");
            return null;
        }

        // 2. 获取目标目录
        File targetDir = context.getExternalFilesDir(null);
        if (targetDir == null) {
            Log.e(TAG, "Failed to get external files directory");
            return null;
        }

        // 3. 创建目标文件，保留子目录结构（如 assets/video/xxx.mp4 → externalDir/video/xxx.mp4）
        File targetFile = new File(targetDir, assetFileName);

        // 4. 确保父目录存在
        File parentDir = targetFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        // 5. Demo 资源固定不变，目标文件已存在时直接复用，避免重复拷贝。
        if (targetFile.exists()) {
            Log.d(TAG, "File already exists: " + targetFile.getAbsolutePath());
            return targetFile;
        }

        // 6. 执行拷贝
        try (InputStream is = context.getAssets().open(assetFileName);
                OutputStream os = new FileOutputStream(targetFile)) {

            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) > 0) {
                os.write(buffer, 0, length);
            }

            Log.i(TAG, "File copied successfully: " + targetFile.getAbsolutePath());
            return targetFile;

        } catch (IOException e) {
            Log.e(TAG, "Failed to copy asset file: " + assetFileName, e);
            // 删除可能已创建的部分文件
            if (targetFile.exists()) {
                targetFile.delete();
            }
            return null;
        }
    }

    /**
     * 检查外部存储是否可写
     */
    private static boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state);
    }

    /**
     * 检查文件是否存在于外部存储
     */
    public static boolean isFileExistsInExternalFilesDir(Context context, String fileName) {
        File targetDir = context.getExternalFilesDir(null);
        if (targetDir == null) {
            return false;
        }

        File targetFile = new File(targetDir, fileName);
        return targetFile.exists();
    }
}