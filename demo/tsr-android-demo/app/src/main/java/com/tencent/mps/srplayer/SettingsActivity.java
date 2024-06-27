package com.tencent.mps.srplayer;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Objects;

public class SettingsActivity extends AppCompatActivity {
    private static Uri mVideoUri;
    private static String mFileName;
    private static boolean mVideoChooseAlbum;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        // 配置开始按钮
        Button startPlayTsrButton = findViewById(R.id.start_play_button_tsr);
        startPlayTsrButton.setOnClickListener(view -> onButtonClick(view, false));

        // 配置开始按钮
        Button startExportButton = findViewById(R.id.start_export_button_tsr);
        startExportButton.setOnClickListener(view -> onButtonClick(view, true));

        TextView chooseVideoButton = findViewById(R.id.choose_album_button);
        if (mFileName != null && mVideoUri != null) {
            chooseVideoButton.setText(mFileName);
        }
        chooseVideoButton.setOnClickListener(view -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("video/*"); //选择视频 （mp4 3gp 是android支持的视频格式）
            intent.addCategory(Intent.CATEGORY_OPENABLE);

            /* 使用Intent.ACTION_GET_CONTENT这个Action */
            startActivityForResult(Intent.createChooser(intent, "选择文件"), 1);
        });

        Spinner chooseBuildInVideo = findViewById(R.id.choose_build_in_spinner);
        chooseVideoButton.setVisibility(View.GONE);
        chooseBuildInVideo.setVisibility(View.VISIBLE);

        // 配置视频选择类型
        RadioGroup videoChooseRadioGroup = findViewById(R.id.choose_type);
        videoChooseRadioGroup.setOnCheckedChangeListener((group, checkedID) -> {
            RadioButton btn = findViewById(checkedID);
            if (btn.getId() == R.id.album_video) {
                mVideoChooseAlbum = true;
                chooseVideoButton.setVisibility(View.VISIBLE);
                chooseBuildInVideo.setVisibility(View.GONE);
            } else {
                mVideoChooseAlbum = false;
                chooseVideoButton.setVisibility(View.GONE);
                chooseBuildInVideo.setVisibility(View.VISIBLE);
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // 选取图片的返回值
        if (requestCode == 1) {
            if (resultCode == RESULT_OK) {
                mVideoUri = data.getData();
                TextView chooseVideoButton = findViewById(R.id.choose_album_button);
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

    private void onButtonClick(View view, boolean isExport) {
        Intent intent = new Intent(SettingsActivity.this, TsrActivity.class);
        if (mVideoChooseAlbum) {
            if (mVideoUri == null) {
                Toast.makeText(getApplicationContext(), R.string.video_choose_hint, Toast.LENGTH_SHORT).show();
                return;
            }

            intent.putExtra("video_uri", mVideoUri.toString());
        } else {
            Spinner chooseBuildInVideo = findViewById(R.id.choose_build_in_spinner);
            mFileName = (String) chooseBuildInVideo.getSelectedItem();
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
        intent.putExtra("file_name", mFileName);
        intent.putExtra("algorithm", (String) ((Spinner) findViewById(R.id.video_algorithm)).getSelectedItem());
        intent.putExtra("compare_algorithm", (String) ((Spinner) findViewById(R.id.compare_algorithm)).getSelectedItem());
        intent.putExtra("sr_ratio", (String) ((Spinner) findViewById(R.id.sr_ratio)).getSelectedItem());

        if (isExport) {
            intent.putExtra("export_video", true);
            intent.putExtra("export_codec", (String) ((Spinner) findViewById(R.id.export_codec_type)).getSelectedItem());
            intent.putExtra("export_bitrate", Integer.parseInt(((EditText) findViewById(R.id.export_bitrate_mbps)).getText().toString()));
        }
        startActivity(intent);
    }
}