package com.tencent.mps.srplayer.utils;

import android.app.ProgressDialog;
import android.content.Context;

public class ProgressDialogUtils {
    private static ProgressDialog progressDialog;//ProgressDialog这个对象你从上面的导入也可以看到，这是Android库自带的

    public static void showProgressDialog(Context context, String message) {
        progressDialog = new ProgressDialog(context);
        progressDialog.setMessage(message);
        progressDialog.setCancelable(false);
        progressDialog.show();
    }

    public static void hideProgressDialog() {
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }
}