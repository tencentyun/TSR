package com.tencent.mps.srplayer.common;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tencent.mps.srplayer.HomeActivity;

import java.io.File;
import java.io.InputStream;

/**
 * 各 Demo Activity 共享的视频来源解析结果。
 *
 * <p>解析优先级（与 {@link HomeActivity} 的 launch 约定保持一致）：</p>
 * <ol>
 *     <li>{@link HomeActivity#EXTRA_VIDEO_URI} 存在 → 视频源为本地相册 URI；</li>
     *     <li>{@link HomeActivity#EXTRA_VIDEO_FILE_NAME} 存在 → 视频源为 APK 内置视频，路径在
     *         {@code getExternalFilesDir(null)/video/<fileName>}；</li>
 *     <li>都没有 → 回退默认内置视频。</li>
 * </ol>
 *
 * <p>{@link #displayName} 可能与 fileName 重复（内置视频时），用于日志、状态栏、性能记录。</p>
 *
 * <p>调用方典型用法：</p>
 * <pre>
 *     VideoSource src = VideoSource.from(this);
 *     if (!src.exists(this)) { finish(); return; }
 *     // ByteBuffer 解码：
 *     if (src.uri != null) {
 *         decoder.init(this, src.uri);
 *     } else {
 *         decoder.init(src.filePath);
 *     }
 * </pre>
 */
public final class VideoSource {

    private static final String TAG = "VideoSource";

    /** 本地相册 URI。当且仅当来源为本地视频时非 null。 */
    @Nullable
    public final Uri uri;

    /** 内置视频文件路径。当且仅当来源为内置视频时非 null。 */
    @Nullable
    public final String filePath;

    /** 内置视频文件名（不含路径）。本地视频时为空。 */
    @NonNull
    public final String fileName;

    /** 用于日志/状态栏/性能记录的可读名称。 */
    @NonNull
    public final String displayName;

    private VideoSource(@Nullable Uri uri, @Nullable String filePath,
                        @NonNull String fileName, @NonNull String displayName) {
        this.uri = uri;
        this.filePath = filePath;
        this.fileName = fileName;
        this.displayName = displayName;
    }

    /** 从 Activity 的启动 Intent 解析视频来源。 */
    @NonNull
    public static VideoSource from(@NonNull Activity activity) {
        Intent intent = activity.getIntent();
        String uriStr = intent != null ? intent.getStringExtra(HomeActivity.EXTRA_VIDEO_URI) : null;
        String displayName = intent != null ? intent.getStringExtra(HomeActivity.EXTRA_VIDEO_DISPLAY_NAME) : null;

        if (!TextUtils.isEmpty(uriStr)) {
            Uri uri = Uri.parse(uriStr);
            String name = TextUtils.isEmpty(displayName) ? "本地视频" : displayName;
            Log.i(TAG, "use local uri: " + uri + ", displayName=" + name);
            return new VideoSource(uri, null, "", name);
        }

        // 内置视频
        String fileName = intent != null ? intent.getStringExtra(HomeActivity.EXTRA_VIDEO_FILE_NAME) : null;
        if (TextUtils.isEmpty(fileName)) {
            Log.w(TAG, "no video file name in intent, fallback to empty");
            fileName = "";
        }
        File file = new File(activity.getExternalFilesDir(null), "video/" + fileName);
        String path = file.getAbsolutePath();
        String name = TextUtils.isEmpty(displayName) ? fileName : displayName;
        Log.i(TAG, "use builtin file: " + fileName);
        return new VideoSource(null, path, fileName, name);
    }

    /** 是否为本地相册 URI 来源。 */
    public boolean isLocalUri() {
        return uri != null;
    }

    /**
     * 启动前轻量校验视频源是否可读。
     * <ul>
     *     <li>本地 URI：尝试 openInputStream；</li>
     *     <li>内置文件：检查文件是否存在。</li>
     * </ul>
     */
    public boolean exists(@NonNull Context ctx) {
        if (uri != null) {
            try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
                return is != null;
            } catch (Throwable t) {
                Log.w(TAG, "uri not readable: " + uri + ", " + t.getMessage());
                return false;
            }
        }
        if (filePath != null) {
            return new File(filePath).exists();
        }
        return false;
    }
}
