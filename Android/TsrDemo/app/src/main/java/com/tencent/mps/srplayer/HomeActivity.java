package com.tencent.mps.srplayer;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.tencent.mps.tie.api.TieBufferEnhancer.EnhancerType;
import java.io.IOException;
import java.io.InputStream;

import com.tencent.mps.srplayer.demo.bytebuffer.ByteBufferActivity;
import com.tencent.mps.srplayer.demo.bytebuffer.ByteBufferBaseActivity;
import com.tencent.mps.srplayer.demo.bytebuffer.ByteBufferSrActivity;
import com.tencent.mps.srplayer.demo.surface.TieSurfaceActivity;
import com.tencent.mps.srplayer.demo.texture.TextureOesActivity;
import com.tencent.mps.srplayer.demo.texture.TextureOutputActivity;
import com.tencent.mps.srplayer.demo.texture.Texture2dActivity;
import com.tencent.mps.srplayer.common.TieSdkHelper;
import com.tencent.mps.srplayer.common.AssetsFileCopier;

/**
 * 应用首页：集中展示客户集成 TsrSdk 时最常参考的几条链路。
 */
public class HomeActivity extends AppCompatActivity {

    private static final String TAG = "HomeActivity";

    /**
     * 启动子 Activity 时通过 Intent 携带的视频文件名（仅文件名，不含路径）。
     * 子 Activity 据此在 {@code getExternalFilesDir(null)/video/} 下拼出完整路径。
     * <p>仅在选择 APK 内置视频时携带；选择本地相册视频时使用 {@link #EXTRA_VIDEO_URI}。</p>
     */
    public static final String EXTRA_VIDEO_FILE_NAME = "extra_video_file_name";

    /**
     * 启动子 Activity 时通过 Intent 携带的本地视频 URI（{@link Uri#toString()}）。
     * <p>该 extra 存在时优先生效，子 Activity 使用 {@code MediaExtractor.setDataSource(Context, Uri, null)}
     * 或 {@code MediaPlayer.setDataSource(Context, Uri)} 打开。</p>
     */
    public static final String EXTRA_VIDEO_URI = "extra_video_uri";

    /**
     * 启动子 Activity 时携带的可读名称，仅用于日志与性能记录展示。
     * <p>本地视频会传 SAF 解析出的 displayName；内置视频会传文件名。</p>
     */
    public static final String EXTRA_VIDEO_DISPLAY_NAME = "extra_video_display_name";

    /**
     * Demo 需要的 assets 视频文件，由 {@link #scanVideoAssets()} 在 {@link #onCreate} 中扫描
     * assets/video 目录下的所有 .mp4 文件自动填充，无需手动维护。
     * <p>各子 Activity 只需根据文件名从 {@code getExternalFilesDir(null)/video/} 拼路径读取。</p>
     */
    private String[] mVideoAssets = new String[0];

    /** 当前选中的内置视频文件名（会话内有效）。仅当 {@link #mLocalVideoUri} 为 null 时生效。 */
    private String mSelectedVideoFileName = "";

    /** 当前选中的本地相册视频 URI。为 null 时表示使用内置视频。 */
    private Uri mLocalVideoUri = null;

    /** 本地视频的可读名称，解析不出时为“本地视频”。 */
    private String mLocalVideoDisplayName = "";

    private TextView mTvCurrentVideo;

    /** SAF 视频选择器，返回的 Uri 赋给 {@link #mLocalVideoUri}。 */
    private final ActivityResultLauncher<String[]> mPickVideoLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::onLocalVideoPicked);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        // 扫描 assets 目录下所有 .mp4 文件，填充视频列表
        scanVideoAssets();

        // 默认选择第一个视频文件
        if (mVideoAssets.length > 0) {
            mSelectedVideoFileName = mVideoAssets[0];
        }

        // 统一拷贝所有 demo mp4 到应用外部存储（拷贝器内部幂等：已存在则跳过）
        for (String assetName : mVideoAssets) {
            AssetsFileCopier.copyAssetToExternalFilesDir(this, "video/" + assetName);
        }
        setupVideoSpinner();
        setupLocalVideoPicker();

        findViewById(R.id.base_activity).setOnClickListener(
                v -> launchIfSdkReady(ByteBufferBaseActivity.class));
        findViewById(R.id.profile_activity).setOnClickListener(
                v -> launchIfSdkReady(ByteBufferActivity.class));
        findViewById(R.id.oes_activity).setOnClickListener(
                v -> launchIfSdkReady(TextureOesActivity.class));
        findViewById(R.id.oes_output_activity).setOnClickListener(
                v -> launchIfSdkReady(TextureOutputActivity.class));
        findViewById(R.id.texture2d_activity).setOnClickListener(
                v -> launchIfSdkReady(Texture2dActivity.class));
        findViewById(R.id.sr_activity).setOnClickListener(
                v -> launchIfSdkReady(ByteBufferSrActivity.class));
        // 两个入口共用 TieSurfaceActivity，仅通过 engine type 区分同尺寸增强 / 超分意图。
        findViewById(R.id.tie_surface_activity).setOnClickListener(
                v -> launchEnhanceSurface(EnhancerType.IE_Y));
        findViewById(R.id.tsr_surface_activity).setOnClickListener(
                v -> launchEnhanceSurface(EnhancerType.SR_Y));

        // Release 构建仅保留核心客户示例入口；调试/实验入口只在 Debug 构建展示。
        if (!BuildConfig.DEBUG) {
            findViewById(R.id.section_oes_container).setVisibility(View.GONE);
            findViewById(R.id.section_surface_container).setVisibility(View.GONE);
        }
    }

    /** 扫描 assets/video 目录下所有 .mp4 文件，填充 {@link #mVideoAssets}。 */
    private void scanVideoAssets() {
        try {
            String[] assets = getAssets().list("video");
            if (assets == null) {
                mVideoAssets = new String[0];
                return;
            }
            java.util.List<String> mp4List = new java.util.ArrayList<>();
            for (String name : assets) {
                if (name != null && name.toLowerCase().endsWith(".mp4")) {
                    mp4List.add(name);
                }
            }
            mVideoAssets = mp4List.toArray(new String[0]);
            Log.i(TAG, "scanVideoAssets: 找到 " + mVideoAssets.length + " 个 mp4 文件");
        } catch (IOException e) {
            Log.e(TAG, "scanVideoAssets: 扫描 assets 目录失败", e);
            mVideoAssets = new String[0];
        }
    }

    /** 初始化视频选择 Spinner，默认选中第一个视频文件。 */
    private void setupVideoSpinner() {
        Spinner spinner = findViewById(R.id.video_spinner);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, mVideoAssets);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        // 默认选中第一个视频
        spinner.setSelection(0);

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                mSelectedVideoFileName = mVideoAssets[position];
                // 选择内置视频 → 会话里清除本地视频来源，后续 Demo 启动将走内置路径。
                mLocalVideoUri = null;
                mLocalVideoDisplayName = "";
                refreshCurrentVideoText();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 保持当前选择不变
            }
        });
    }

    /** 初始化“选择本地视频”按钮与当前视频名称 TextView。 */
    private void setupLocalVideoPicker() {
        mTvCurrentVideo = findViewById(R.id.tv_current_video);
        Button btnPick = findViewById(R.id.btn_pick_local_video);
        btnPick.setOnClickListener(v -> {
            // SAF 视频选择器：仅限定 video/*，不需要额外 READ_EXTERNAL_STORAGE 权限
            mPickVideoLauncher.launch(new String[]{"video/*"});
        });
        refreshCurrentVideoText();
    }

    /**
     * SAF 返回的 Uri 回调。uri 为 null 表示用户取消选择，这种情况下保持当前选择不变。
     */
    private void onLocalVideoPicked(Uri uri) {
        if (uri == null) {
            Log.i(TAG, "local video pick canceled, keep current source");
            return;
        }
        // 1) 可读性校验：打开一次输入流立即关闭，及时发现不可用的 uri
        if (!isUriReadable(uri)) {
            Toast.makeText(this, "本地视频读取失败，请重新选择", Toast.LENGTH_LONG).show();
            return;
        }
        // 2) 尝试持久化读取权限（避免 Activity 重建后立即失效）。SAF 选择器返回的 Uri 本身
        // 在当前进程生命周期内已可读；持久化仅在部分提供者上生效，失败不影响当前会话。
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            Log.w(TAG, "takePersistableUriPermission failed (当前会话仍可用): " + e.getMessage());
        }
        mLocalVideoUri = uri;
        mLocalVideoDisplayName = queryDisplayName(uri);
        Log.i(TAG, "local video picked: " + uri + ", displayName=" + mLocalVideoDisplayName);
        refreshCurrentVideoText();
    }

    /** 检查 uri 是否可读：打开一次 InputStream 立即关闭。 */
    private boolean isUriReadable(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            return is != null;
        } catch (Throwable t) {
            Log.w(TAG, "isUriReadable() failed for " + uri + ": " + t.getMessage());
            return false;
        }
    }

    /** 从 ContentResolver 查询 displayName；拿不到时返回“本地视频”。 */
    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = c.getString(idx);
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "queryDisplayName failed: " + t.getMessage());
        }
        return "本地视频";
    }

    /** 同步刷新“当前视频”文本。 */
    private void refreshCurrentVideoText() {
        if (mTvCurrentVideo == null) {
            return;
        }
        String desc;
        if (mLocalVideoUri != null) {
            desc = "当前选择：本地 · " + mLocalVideoDisplayName;
        } else {
            desc = "当前选择：内置 · " + mSelectedVideoFileName;
        }
        mTvCurrentVideo.setText(desc);
    }

    /**
     * 校验当前视频来源。本地 URI 不可读时提示并返回 false，阻止 Demo 启动。
     * 内置视频不在本函数里检查文件是否存在（子 Activity 会从 getExternalFilesDir(null)/video/ 拼路径后自检）。
     */
    private boolean ensureVideoSourceReady() {
        if (mLocalVideoUri != null && !isUriReadable(mLocalVideoUri)) {
            Toast.makeText(this, "视频不可用或权限已失效，请重新选择", Toast.LENGTH_LONG).show();
            // 重置到内置视频，避免后续 Demo 继续使用已失效的 Uri。
            mLocalVideoUri = null;
            mLocalVideoDisplayName = "";
            refreshCurrentVideoText();
            return false;
        }
        return true;
    }

    /**
     * 将当前视频来源填到 Intent 中：
     * <ul>
     *     <li>本地视频：{@link #EXTRA_VIDEO_URI} + {@link #EXTRA_VIDEO_DISPLAY_NAME}，同时 grant URI 读权限；</li>
     *     <li>内置视频：{@link #EXTRA_VIDEO_FILE_NAME} + {@link #EXTRA_VIDEO_DISPLAY_NAME}（名称与文件名一致）。</li>
     * </ul>
     */
    private void applyVideoSourceExtras(Intent intent) {
        if (mLocalVideoUri != null) {
            intent.putExtra(EXTRA_VIDEO_URI, mLocalVideoUri.toString());
            intent.putExtra(EXTRA_VIDEO_DISPLAY_NAME,
                    mLocalVideoDisplayName.isEmpty() ? "本地视频" : mLocalVideoDisplayName);
            // 为目标 Activity 授予这次会话内的读取权限（同进程本身已可读，此举对跨进程、
            // 以及部分 Provider 是必须的）。
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            intent.putExtra(EXTRA_VIDEO_FILE_NAME, mSelectedVideoFileName);
            intent.putExtra(EXTRA_VIDEO_DISPLAY_NAME, mSelectedVideoFileName);
        }
    }

    /**
     * 校验 TieSdk 鉴权状态，仅在鉴权成功后才进入依赖 SDK 的 Demo。
     *
     * @param target 目标 Activity Class
     */
    private void launchIfSdkReady(Class<?> target) {
        launchIfSdkReady(target, null);
    }

    /** 启动 EnhanceSurface demo，按 type 传入引擎类型 extra。 */
    private void launchEnhanceSurface(EnhancerType type) {
        launchIfSdkReady(TieSurfaceActivity.class, type);
    }

    /**
     * 通用 Demo 启动方法：校验 SDK 状态 + 视频源 + 填充 Intent。
     *
     * @param target    目标 Activity Class
     * @param enhancerType 处理意图（仅 TieSurfaceActivity 需要，其余传 null）
     */
    private void launchIfSdkReady(Class<?> target, EnhancerType enhancerType) {
        Boolean state = TieSdkHelper.getInstance().isInit();
        if (state == null) {
            Toast.makeText(this, "TsrSdk 正在初始化，请稍候...", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!state) {
            Toast.makeText(this, "TsrSdk 初始化失败，请检查 APP_ID / AUTH_ID 配置", Toast.LENGTH_LONG).show();
            return;
        }
        if (!ensureVideoSourceReady()) {
            return;
        }
        Intent intent = new Intent(HomeActivity.this, target);
        applyVideoSourceExtras(intent);
        if (enhancerType != null) {
            intent.putExtra(TieSurfaceActivity.EXTRA_ENGINE_TYPE, enhancerType.name());
        }
        startActivity(intent);
    }
}