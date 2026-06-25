package com.tencent.mps.srplayer.demo.enhancesurface;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.util.Log;
import android.view.Surface;

/**
 * EGL14 轻量封装：为 {@link com.tencent.mps.srplayer.demo.enhancesurface.EnhanceSurface} 提供"独立 EGL 线程
 * 创建 GLES3 context + 可切换 window surface"的能力，不依赖 GLSurfaceView。
 *
 * <p>典型用法（GL 线程）：</p>
 * <ol>
 *     <li>{@link #EglCore()} 创建 display + context；</li>
 *     <li>{@link #createWindowSurface(Surface)} 绑定一个 output Surface；</li>
 *     <li>{@link #makeCurrent()} 把 context + window surface 设为当前；</li>
 *     <li>渲染到默认 framebuffer 后 {@link #swapBuffers()} 上屏；</li>
 *     <li>切换 output Surface 时 {@link #releaseWindowSurface()} → {@link #createWindowSurface(Surface)}；</li>
 *     <li>不再使用时 {@link #release()}。</li>
 * </ol>
 *
 * <p>所有方法非线程安全，调用方负责保证只在同一 GL 线程使用。</p>
 */
public class EglCore {

    private static final String TAG = "EglCore";

    private EGLDisplay mDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext mContext = EGL14.EGL_NO_CONTEXT;
    private EGLConfig mConfig;
    private EGLSurface mWindowSurface = EGL14.EGL_NO_SURFACE;

    /**
     * 创建 EGL display + context（GLES3）。在 GL 线程构造。
     *
     * @throws RuntimeException 创建失败（设备不支持 GLES3 / 无可用 config 等）
     */
    public EglCore() {
        mDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (mDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("eglGetDisplay failed");
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(mDisplay, version, 0, version, 1)) {
            mDisplay = EGL14.EGL_NO_DISPLAY;
            throw new RuntimeException("eglInitialize failed");
        }

        // 选 RGBA8888 + GLES3 + window surface
        int[] configAttribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(mDisplay, configAttribs, 0,
                configs, 0, configs.length, numConfigs, 0) || numConfigs[0] <= 0) {
            release();
            throw new RuntimeException("eglChooseConfig failed");
        }
        mConfig = configs[0];

        int[] ctxAttribs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL14.EGL_NONE
        };
        mContext = EGL14.eglCreateContext(mDisplay, mConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        if (mContext == EGL14.EGL_NO_CONTEXT) {
            release();
            throw new RuntimeException("eglCreateContext failed, err=0x"
                    + Integer.toHexString(EGL14.eglGetError()));
        }
        Log.i(TAG, "EglCore created, GLES3 ctx=" + mContext);
    }

    /**
     * 为给定 output {@link Surface} 创建 EGL window surface。
     *
     * <p>若已存在 window surface，必须先调用 {@link #releaseWindowSurface()}。</p>
     *
     * @throws RuntimeException 创建失败（Surface 无效等）
     */
    public void createWindowSurface(Surface surface) {
        if (surface == null) {
            throw new IllegalArgumentException("surface == null");
        }
        if (mWindowSurface != EGL14.EGL_NO_SURFACE) {
            throw new IllegalStateException("window surface already exists, call releaseWindowSurface() first");
        }
        int[] surfaceAttribs = {EGL14.EGL_NONE};
        mWindowSurface = EGL14.eglCreateWindowSurface(mDisplay, mConfig, surface, surfaceAttribs, 0);
        if (mWindowSurface == EGL14.EGL_NO_SURFACE) {
            throw new RuntimeException("eglCreateWindowSurface failed, err=0x"
                    + Integer.toHexString(EGL14.eglGetError()));
        }
    }

    /** 销毁当前 window surface。幂等。makeCurrent 状态会被解绑。 */
    public void releaseWindowSurface() {
        if (mWindowSurface != EGL14.EGL_NO_SURFACE) {
            // 解绑当前，防止销毁正在 current 的 surface
            EGL14.eglMakeCurrent(mDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(mDisplay, mWindowSurface);
            mWindowSurface = EGL14.EGL_NO_SURFACE;
        }
    }

    /** 把 context + window surface 设为当前。仅当已经 {@link #createWindowSurface(Surface)} 后可用。 */
    public boolean makeCurrent() {
        if (mWindowSurface == EGL14.EGL_NO_SURFACE || mContext == EGL14.EGL_NO_CONTEXT) {
            return false;
        }
        if (!EGL14.eglMakeCurrent(mDisplay, mWindowSurface, mWindowSurface, mContext)) {
            Log.e(TAG, "eglMakeCurrent failed, err=0x" + Integer.toHexString(EGL14.eglGetError()));
            return false;
        }
        return true;
    }

    /**
     * 仅把 context 设为当前（无 draw/read surface）。用于在没有 output Surface 时也能创建 GL 资源
     * 或仅做 SurfaceTexture.updateTexImage()。需要 EGL_KHR_surfaceless_context 扩展，多数 Android
     * 设备已支持。
     */
    public boolean makeCurrentSurfaceless() {
        if (mContext == EGL14.EGL_NO_CONTEXT) {
            return false;
        }
        if (!EGL14.eglMakeCurrent(mDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, mContext)) {
            Log.w(TAG, "eglMakeCurrent(surfaceless) failed, err=0x"
                    + Integer.toHexString(EGL14.eglGetError()));
            return false;
        }
        return true;
    }

    /** swapBuffers 上屏。返回 false 表示失败（如 window surface 已失效）。 */
    public boolean swapBuffers() {
        if (mWindowSurface == EGL14.EGL_NO_SURFACE) {
            return false;
        }
        boolean ok = EGL14.eglSwapBuffers(mDisplay, mWindowSurface);
        if (!ok) {
            Log.w(TAG, "eglSwapBuffers failed, err=0x" + Integer.toHexString(EGL14.eglGetError()));
        }
        return ok;
    }

    /** 查询当前 window surface 宽。无 window surface 时返回 0。 */
    public int getWindowSurfaceWidth() {
        if (mWindowSurface == EGL14.EGL_NO_SURFACE) {
            return 0;
        }
        int[] value = new int[1];
        EGL14.eglQuerySurface(mDisplay, mWindowSurface, EGL14.EGL_WIDTH, value, 0);
        return value[0];
    }

    /** 查询当前 window surface 高。无 window surface 时返回 0。 */
    public int getWindowSurfaceHeight() {
        if (mWindowSurface == EGL14.EGL_NO_SURFACE) {
            return 0;
        }
        int[] value = new int[1];
        EGL14.eglQuerySurface(mDisplay, mWindowSurface, EGL14.EGL_HEIGHT, value, 0);
        return value[0];
    }

    /** 释放全部 EGL 资源。幂等。释放后实例不可再用。 */
    public void release() {
        if (mDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(mDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (mWindowSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(mDisplay, mWindowSurface);
                mWindowSurface = EGL14.EGL_NO_SURFACE;
            }
            if (mContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(mDisplay, mContext);
                mContext = EGL14.EGL_NO_CONTEXT;
            }
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(mDisplay);
            mDisplay = EGL14.EGL_NO_DISPLAY;
        }
        mConfig = null;
        Log.i(TAG, "EglCore released");
    }
}
