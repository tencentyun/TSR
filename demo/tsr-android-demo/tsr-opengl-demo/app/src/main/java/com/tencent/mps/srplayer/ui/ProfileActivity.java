package com.tencent.mps.srplayer.ui;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;
import com.tencent.mps.srplayer.R;

import com.tencent.mps.tie.api.v2.ErrorCode;
import com.tencent.mps.tie.api.v2.TiePro;
import java.nio.ByteBuffer;
import java.util.concurrent.Executors;

// 测试 TsrSdk 的性能
public class ProfileActivity extends BasePlayActivity {

    private static final String TAG = "ProfileActivity";
    private boolean mUseTsr = false;
    private final TiePro mTiePro = new TiePro();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ToggleButton mToggleTsr = findViewById(R.id.toggleTsr);
        mToggleTsr.setChecked(mUseTsr);
        mToggleTsr.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mUseTsr = isChecked;
        });
    }

    @Override
    protected void onGetVideoSize(int width, int height) {
        super.onGetVideoSize(width, height);
        Log.i(TAG, "onGetVideoSize() " + width + "x" + height);

        // 在后台线程初始化
        Executors.newSingleThreadExecutor().execute(() -> {
            ErrorCode result = mTiePro.init(width, height);
            Log.i(TAG, "init result: " + result);
            if (result != ErrorCode.SUCCESS) {
                runOnUiThread(() -> {
                    Toast.makeText(ProfileActivity.this, "init failed: " + result,
                            Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        });
    }

    @Override
    protected void processYuv(ByteBuffer yuvData, int stride, int height) {
        if (!mUseTsr) {
            return;
        }
        mTiePro.process(yuvData, stride, height);
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mTiePro != null) {
            mTiePro.release();
        }
    }
}
