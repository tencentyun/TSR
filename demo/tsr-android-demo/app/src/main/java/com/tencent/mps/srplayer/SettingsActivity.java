package com.tencent.mps.srplayer;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import com.tencent.mps.tsr.api.TsrLogger;
import com.tencent.mps.tsr.api.TsrSdk;
import java.util.Objects;

public class SettingsActivity extends AppCompatActivity {
    private Uri mVideoUri;
    private String mFileName;
    private boolean mVideoChooseLocal = true;
    private static PreferenceCategory mLocalVideoChoosePreferenceCategory;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings, new SettingsFragment())
                    .commit();
        }

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        LinearLayout localVideoLayout = findViewById(R.id.choose_local_video_layout);
        localVideoLayout.setVisibility(View.VISIBLE);

        // 配置视频选择类型
        RadioGroup videoChooseRadioGroup = findViewById(R.id.choose_type);
        videoChooseRadioGroup.setOnCheckedChangeListener((group, checkedID) -> {
            RadioButton btn = findViewById(checkedID);
            if (btn.getId() == R.id.local_video) {
                mVideoChooseLocal = true;
                localVideoLayout.setVisibility(View.VISIBLE);
                mLocalVideoChoosePreferenceCategory.setVisible(false);
            } else {
                mVideoChooseLocal = false;
                localVideoLayout.setVisibility(View.GONE);
                mLocalVideoChoosePreferenceCategory.setVisible(true);
            }
        });

        // 配置开始按钮
        Button startPlayButton = findViewById(R.id.start_play_button);
        startPlayButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(SettingsActivity.this, MainActivity.class);

                if (mVideoChooseLocal) {
                    if (mVideoUri == null) {
                        Toast.makeText(getApplicationContext(), R.string.video_choose_hint, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (mFileName != null) {
                        String[] fileNameSplit = mFileName.split("\\.");
                        if (!Objects.equals(fileNameSplit[fileNameSplit.length - 1], "mp4") &&
                                !Objects.equals(fileNameSplit[fileNameSplit.length - 1], "3gp") &&
                                !Objects.equals(fileNameSplit[fileNameSplit.length - 1], "webm")) {
                            Toast.makeText(getApplicationContext(), R.string.video_format_not_support, Toast.LENGTH_SHORT).show();
                            return;
                        }
                    } else {
                        Toast.makeText(getApplicationContext(), R.string.video_choose_hint, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    intent.putExtra("videoUri", mVideoUri.toString());
                }
                startActivity(intent);
            }
        });

        TextView chooseVideoButton = findViewById(R.id.choose_button);
        chooseVideoButton.setText(R.string.video_choose);
        chooseVideoButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("video/*"); //选择视频 （mp4 3gp 是android支持的视频格式）
                intent.addCategory(Intent.CATEGORY_OPENABLE);

                /* 使用Intent.ACTION_GET_CONTENT这个Action */
                startActivityForResult(Intent.createChooser(intent, "选择文件"), 1);
            }
        });
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey);
            mLocalVideoChoosePreferenceCategory = findPreference("choose_app_video");
            mLocalVideoChoosePreferenceCategory.setVisible(false);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // 选取图片的返回值
        if (requestCode == 1) {
            //
            if (resultCode == RESULT_OK) {
                mVideoUri = data.getData();
                TextView chooseVideoButton = findViewById(R.id.choose_button);
                mFileName = getFileName(mVideoUri);
                chooseVideoButton.setText(mFileName);
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @SuppressLint("Range")
    private String getFileName(Uri uri) {
        String fileName = null;
        if (uri.getScheme().equals("content")) {
            try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null,
                    null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    fileName = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                }
            }
        } else if (uri.getScheme().equals("file")) {
            fileName = uri.getLastPathSegment();
        }
        return fileName;
    }
}