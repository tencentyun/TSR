package com.tencent.mps.srplayer.helper;

import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;

public class TapHelper implements OnTouchListener {
    private static final String TAG = "TapHelper";

    /**
     * 首次按下时的x坐标
     */
    private volatile float mDownX = -1;
    /**
     * 首次按下时的y坐标
     */
    private volatile float mDownY = -1;

    private TapHelper() {
    }

    @Override
    public boolean onTouch(View view, MotionEvent motionEvent) {
        view.performClick();
        int action = motionEvent.getAction();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                mDownX = motionEvent.getX();
                mDownY = motionEvent.getY();
                break;
        }
        return true;
    }

    public float getDownX() {
        return mDownX;
    }

    public float getDownY() {
        return mDownY;
    }

    public static TapHelper getInstance() {
        return Holder.instance;
    }

    private static final class Holder {
        private static final TapHelper instance = new TapHelper();
    }
}