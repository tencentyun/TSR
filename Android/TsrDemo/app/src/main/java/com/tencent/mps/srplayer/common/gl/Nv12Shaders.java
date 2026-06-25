package com.tencent.mps.srplayer.common.gl;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * NV12 渲染相关的公共 shader 与 GL 工具方法集中地。
 *
 * <p>本类是 {@code com.tencent.mps.srplayer.common.gl} 下的 GL 工具，仅依赖 Android GLES，
 * 不依赖任何 app 业务代码，被本包内多个渲染器共享。</p>
 */
public final class Nv12Shaders {
    private Nv12Shaders() {}

    /** 全屏四边形顶点 vertex shader：仅做坐标透传，无任何变换。 */
    public static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTexCoord = aTexCoord;\n" +
            "}\n";

    /**
     * NV12&rarr;RGB fragment shader（通用版，色彩空间通过 uniform 注入）。
     *
     * <ul>
     *     <li>{@code uYuv2Rgb}：3x3 反向矩阵，列主序（OpenGL ES 约定）；</li>
     *     <li>{@code uYuvOffset}：YUV 减偏移向量，limited 是 (16/255, 128/255, 128/255)，full 是 (0, 128/255, 128/255)。</li>
     * </ul>
     *
     * <p>等价于通用公式：{@code yuv = sample - uYuvOffset; rgb = uYuv2Rgb * yuv;}</p>
     */
    public static final String FRAGMENT_SHADER_NV12_TO_RGB =
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform sampler2D yTexture;\n" +
            "uniform sampler2D uvTexture;\n" +
            "uniform mat3 uYuv2Rgb;\n" +
            "uniform vec3 uYuvOffset;\n" +
            "void main() {\n" +
            "    float y = texture2D(yTexture, vTexCoord).r;\n" +
            "    vec2 uv = texture2D(uvTexture, vTexCoord).rg;\n" + // NV12: GL_RG8 → U在R通道, V在G通道
            "    vec3 yuv = vec3(y, uv.r, uv.g) - uYuvOffset;\n" +
            "    vec3 rgb = uYuv2Rgb * yuv;\n" +
            "    gl_FragColor = vec4(rgb, 1.0);\n" +
            "}\n";

    /**
     * NV12&rarr;RGB 反向公式所用的色彩空间枚举。
     *
     * <p>系数与 SDK 正向 RGB&rarr;YUV 配对。</p>
     */
    public enum YuvColorSpace {
        /** 标清/兼容兜底 */
        BT601_LIMITED,
        /** 部分应用场景使用 full range */
        BT601_FULL,
        /** 主流视频 */
        BT709_LIMITED,
        /** 极少出现，仅做完整性兜底 */
        BT709_FULL,
    }

    // YUV 偏移：limited = (16/255, 128/255, 128/255)，full = (0, 128/255, 128/255)
    private static final float[] OFFSET_LIMITED = {16f / 255f, 128f / 255f, 128f / 255f};
    private static final float[] OFFSET_FULL    = {0f,         128f / 255f, 128f / 255f};

    // 反向 3x3 矩阵（行主序，行内顺序：[Y 系数, U 系数, V 系数]）。
    private static final float[] M_BT601_LIMITED = {
            1.164f,  0.000f,  1.596f,
            1.164f, -0.392f, -0.813f,
            1.164f,  2.017f,  0.000f,
    };
    private static final float[] M_BT601_FULL = {
            1.000f,  0.000f,  1.402f,
            1.000f, -0.344f, -0.714f,
            1.000f,  1.772f,  0.000f,
    };
    private static final float[] M_BT709_LIMITED = {
            1.164f,  0.000f,  1.793f,
            1.164f, -0.213f, -0.533f,
            1.164f,  2.112f,  0.000f,
    };
    private static final float[] M_BT709_FULL = {
            1.000f,  0.000f,  1.5748f,
            1.000f, -0.1873f,-0.4681f,
            1.000f,  1.8556f, 0.000f,
    };

    /** 取指定色彩空间对应的 3x3 YUV&rarr;RGB 反向矩阵，已转成 OpenGL ES 期望的列主序。 */
    public static float[] getYuv2RgbMatrixColumnMajor(YuvColorSpace cs) {
        return toColumnMajor(getYuv2RgbMatrixRowMajor(cs));
    }

    /** 取偏移向量（limited 或 full），可直接交给 glUniform3fv。 */
    public static float[] getYuvOffset(YuvColorSpace cs) {
        switch (cs) {
            case BT601_FULL:
            case BT709_FULL:
                return OFFSET_FULL.clone();
            case BT601_LIMITED:
            case BT709_LIMITED:
            default:
                return OFFSET_LIMITED.clone();
        }
    }

    private static float[] getYuv2RgbMatrixRowMajor(YuvColorSpace cs) {
        switch (cs) {
            case BT601_FULL:    return M_BT601_FULL;
            case BT709_LIMITED: return M_BT709_LIMITED;
            case BT709_FULL:    return M_BT709_FULL;
            case BT601_LIMITED:
            default:            return M_BT601_LIMITED;
        }
    }

    /** 行主序 3x3 → 列主序 3x3（OpenGL 矩阵 uniform 默认按列主序解释）。 */
    private static float[] toColumnMajor(float[] rowMajor) {
        return new float[]{
                rowMajor[0], rowMajor[3], rowMajor[6],
                rowMajor[1], rowMajor[4], rowMajor[7],
                rowMajor[2], rowMajor[5], rowMajor[8],
        };
    }

    /** 编译 shader，失败抛 RuntimeException。 */
    public static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("compile shader failed (type=" + type + "): " + log);
        }
        return shader;
    }

    /** 链接 vertex/fragment shader 成一个 program。 */
    public static int linkProgram(String vertexSource, String fragmentSource) {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            GLES20.glDeleteShader(vs);
            GLES20.glDeleteShader(fs);
            throw new RuntimeException("link program failed: " + log);
        }
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return program;
    }

    /**
     * 创建一张 GL_TEXTURE_2D 纹理并按指定内部格式分配存储；min/mag 滤波 GL_LINEAR、
     * wrap GL_CLAMP_TO_EDGE。常用于 NV12 Y（{@code GL_R8/GL_RED}）和 UV（{@code GL_RG8/GL_RG}）平面。
     */
    public static int createNv12Texture(int internalFormat, int format, int width, int height) {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        int textureId = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, internalFormat,
                width, height, 0, format, GLES20.GL_UNSIGNED_BYTE, null);
        return textureId;
    }

    /** 把 float[] 拷成 native order 的 direct FloatBuffer，position 已重置到 0。 */
    public static FloatBuffer createFloatBuffer(float[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(data);
        fb.position(0);
        return fb;
    }

    // ---- GLES30 版本 shader/program 工具（供 TiePass 管线等 GLES 3.0+ 组件复用） ----

    /** 编译 shader（GLES30），失败抛 RuntimeException。 */
    public static int compileShader(int type, String source, String label) {
        int sh = GLES30.glCreateShader(type);
        GLES30.glShaderSource(sh, source);
        GLES30.glCompileShader(sh);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES30.glGetShaderInfoLog(sh);
            GLES30.glDeleteShader(sh);
            throw new RuntimeException("compile " + label + " failed: " + log);
        }
        return sh;
    }

    /** 编译并链接 vertex/fragment shader 为一个 program（GLES30）。 */
    public static int buildProgram(String vsSrc, String fsSrc, String label) {
        int vs = compileShader(GLES30.GL_VERTEX_SHADER, vsSrc, label + ".vs");
        int fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fsSrc, label + ".fs");
        try {
            int program = GLES30.glCreateProgram();
            GLES30.glAttachShader(program, vs);
            GLES30.glAttachShader(program, fs);
            GLES30.glLinkProgram(program);
            int[] linked = new int[1];
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0);
            if (linked[0] == 0) {
                String log = GLES30.glGetProgramInfoLog(program);
                GLES30.glDeleteProgram(program);
                throw new RuntimeException("link " + label + " failed: " + log);
            }
            return program;
        } finally {
            GLES30.glDeleteShader(vs);
            GLES30.glDeleteShader(fs);
        }
    }

    /**
     * 创建一张 OES 外部纹理（{@code GL_TEXTURE_EXTERNAL_OES}），
     * min/mag 滤波 GL_LINEAR、wrap GL_CLAMP_TO_EDGE。
     * OES Demo / Surface 路径共用此方法。
     */
    public static int createOesTexture() {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        return tex[0];
    }
}
