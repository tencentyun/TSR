package com.tencent.mps.srplayer.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.tencent.mps.srplayer.R;
import com.tencent.mps.srplayer.helper.TsrSdkHelper;
import com.tencent.mps.srplayer.utils.AssetsFileCopier;
import java.io.File;

public class HomeActivity extends AppCompatActivity {

    private static final String TAG = "HomeActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        findViewById(R.id.effect_activity).setOnClickListener(
                v -> {
                    if (TsrSdkHelper.getInstance().isInit() == null || !TsrSdkHelper.getInstance().isInit()) {
                        Toast.makeText(this, "SDK未成功初始化", Toast.LENGTH_SHORT).show();
                    } else {
                        startActivity(new Intent(HomeActivity.this, CmpSettingActivity.class));
                    }
                });
        findViewById(R.id.profile_activity).setOnClickListener(
                v -> {
                    if (TsrSdkHelper.getInstance().isInit() == null || !TsrSdkHelper.getInstance().isInit()) {
                        Toast.makeText(this, "SDK未成功初始化", Toast.LENGTH_SHORT).show();
                    } else {
                        startActivity(new Intent(HomeActivity.this, ProfileActivity.class));
                    }
                });
        findViewById(R.id.yuv_activity).setOnClickListener(v -> testYuv());
    }

    private void testYuv() {
        findViewById(R.id.yuv_activity).setEnabled(false);
        new Thread(() -> {
            try {
                // 输入文件路径
                File mp4File = AssetsFileCopier.copyAssetToExternalFilesDir(this, "540x960.mp4");
                File yuvDir = getExternalFilesDir(null);
                File yuvFile = new File(yuvDir, "video_frames.yuv");
                Exporter exporter = new Exporter();
                boolean success = exporter.decodeToYuv(mp4File.getAbsolutePath(), yuvFile.getAbsolutePath());

                runOnUiThread(() -> {
                    if (success) {
                        Toast.makeText(this, "输出成功", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "输出失败", Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "输出错误", e);
                Toast.makeText(this, "输出异常", Toast.LENGTH_SHORT).show();
            } finally {
                runOnUiThread(() -> findViewById(R.id.yuv_activity).setEnabled(true));
            }
        }).start();
    }
}