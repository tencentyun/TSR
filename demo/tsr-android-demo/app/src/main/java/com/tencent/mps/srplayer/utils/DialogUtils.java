package com.tencent.mps.srplayer.utils;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;

public class DialogUtils {
    public static void showSimpleConfirmDialog(Context context, String message,
                                               DialogInterface.OnClickListener positiveListener) {
        new AlertDialog.Builder(context)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, positiveListener)
                .show();
    }
}
