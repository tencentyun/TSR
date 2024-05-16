package com.tencent.mps.srplayer;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
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
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import java.util.Objects;

public class SettingsActivity extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private Uri mVideoUri;
    private String mFileName;
    private boolean mVideoChooseLocal;
    private SettingsFragment mSettingsFragment;
    private Button mStartPlayTsrButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        mSettingsFragment = new SettingsFragment();

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings, mSettingsFragment)
                    .commit();
        }

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        LinearLayout localVideoLayout = findViewById(R.id.choose_local_video_layout);
        localVideoLayout.setVisibility(View.GONE);

        // 配置开始按钮
        mStartPlayTsrButton = findViewById(R.id.start_play_button_tsr);
        mStartPlayTsrButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(SettingsActivity.this, TsrActivity.class);

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
                    intent.putExtra("fileName", mFileName);
                }
                startActivity(intent);
            }
        });

        TextView chooseVideoButton = findViewById(R.id.choose_button);
        chooseVideoButton.setText(R.string.video_choose);
        chooseVideoButton.setOnClickListener(view -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("video/*"); //选择视频 （mp4 3gp 是android支持的视频格式）
            intent.addCategory(Intent.CATEGORY_OPENABLE);

            /* 使用Intent.ACTION_GET_CONTENT这个Action */
            startActivityForResult(Intent.createChooser(intent, "选择文件"), 1);
        });

        // 配置视频选择类型
        RadioGroup videoChooseRadioGroup = findViewById(R.id.choose_type);
        videoChooseRadioGroup.setOnCheckedChangeListener((group, checkedID) -> {
            RadioButton btn = findViewById(checkedID);
            if (btn.getId() == R.id.local_video) {
                mVideoChooseLocal = true;
                mSettingsFragment.onLocalVideoChoosePreferenceCategoryVisibilityChanged(false);
                localVideoLayout.setVisibility(View.VISIBLE);
            } else {
                mVideoChooseLocal = false;
                mSettingsFragment.onLocalVideoChoosePreferenceCategoryVisibilityChanged(true);
                localVideoLayout.setVisibility(View.GONE);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        mSettingsFragment.getSettingsPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mSettingsFragment.getSettingsPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {
        if ("export_video".equals(s)) {
            if (!sharedPreferences.getBoolean("export_video", false)) {
                mSettingsFragment.onExportVideoPreferenceCategoryVisibilityChanged(false);
                mStartPlayTsrButton.setText(R.string.start_play_tsr);
            } else {
                mSettingsFragment.onExportVideoPreferenceCategoryVisibilityChanged(true);
                mStartPlayTsrButton.setText(R.string.start_export_tsr);
            }
        }
        if ("video_algorithm".equals(s)) {
            if ("专业版增强".equals(sharedPreferences.getString("video_algorithm", ""))) {
                mSettingsFragment.onPlaySrVideoPreferenceCategoryVisibilityChanged(false);
                mSettingsFragment.onPlayIeVideoPreferenceCategoryVisibilityChanged(true);
            } else if ("直接渲染".equals(sharedPreferences.getString("video_algorithm", ""))) {
                mSettingsFragment.onPlaySrVideoPreferenceCategoryVisibilityChanged(false);
                mSettingsFragment.onPlayIeVideoPreferenceCategoryVisibilityChanged(false);
            }else {
                mSettingsFragment.onPlaySrVideoPreferenceCategoryVisibilityChanged(true);
                mSettingsFragment.onPlayIeVideoPreferenceCategoryVisibilityChanged(false);
            }
        }
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        private PreferenceCategory mExportVideoPreferenceCategory;
        private PreferenceCategory mPlaySrVideoPreferenceCategory;
        private PreferenceCategory mLocalVideoChoosePreferenceCategory;
        private PreferenceCategory mPlayIeVideoPreferenceCategory;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey);

            SwitchPreferenceCompat switchPreference = findPreference("export_video");
            if (switchPreference != null) {
                mExportVideoPreferenceCategory = findPreference("export_config");
                onExportVideoPreferenceCategoryVisibilityChanged(switchPreference.isChecked());
            }

            mLocalVideoChoosePreferenceCategory = findPreference("choose_app_video");
            onLocalVideoChoosePreferenceCategoryVisibilityChanged(true);

            mPlaySrVideoPreferenceCategory = findPreference("play_sr_config");
            mPlayIeVideoPreferenceCategory = findPreference("play_ie_config");

            SharedPreferences sharedPreferences = getPreferenceManager().getSharedPreferences();
            String algorithm = sharedPreferences.getString("video_algorithm", "");
            if ("专业版增强".equals(algorithm)) {
                onPlaySrVideoPreferenceCategoryVisibilityChanged(false);
                onPlayIeVideoPreferenceCategoryVisibilityChanged(true);
            } else if ("直接渲染".equals(algorithm)) {
                onPlaySrVideoPreferenceCategoryVisibilityChanged(false);
                onPlayIeVideoPreferenceCategoryVisibilityChanged(false);
            } else {
                onPlaySrVideoPreferenceCategoryVisibilityChanged(true);
                onPlayIeVideoPreferenceCategoryVisibilityChanged(false);
            }
        }

        public void onPlaySrVideoPreferenceCategoryVisibilityChanged(boolean isVisible) {
            if (mPlaySrVideoPreferenceCategory != null) {
                mPlaySrVideoPreferenceCategory.setVisible(isVisible);
            }
        }

        public void onPlayIeVideoPreferenceCategoryVisibilityChanged(boolean isVisible) {
            if (mPlayIeVideoPreferenceCategory != null) {
                mPlayIeVideoPreferenceCategory.setVisible(isVisible);
            }
        }

        public void onExportVideoPreferenceCategoryVisibilityChanged(boolean isVisible) {
            if (mExportVideoPreferenceCategory != null) {
                mExportVideoPreferenceCategory.setVisible(isVisible);
            }
        }

        public void onLocalVideoChoosePreferenceCategoryVisibilityChanged(boolean isVisible) {
            if (mLocalVideoChoosePreferenceCategory != null) {
                mLocalVideoChoosePreferenceCategory.setVisible(isVisible);
            }
        }

        public PreferenceScreen getSettingsPreferenceScreen() {
            return getPreferenceScreen();
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
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null,
                    null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    fileName = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                }
            }
        } else if ("file".equals(uri.getScheme())) {
            fileName = uri.getLastPathSegment();
        }
        return fileName;
    }
}