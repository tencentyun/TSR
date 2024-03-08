package com.tencent.mps.srplayer.opengl;

import android.opengl.GLES30;
import android.opengl.GLES31;
import android.util.Log;

import java.nio.FloatBuffer;


// Helper class for handling OpenGL shaders and shader programs.
public class GlShader {
    private static final String TAG = "GlShader";

    private static int compileShader(int shaderType, String source) {
        final int shader = GLES30.glCreateShader(shaderType);
        if (shader == 0) {
            throw new RuntimeException("glCreateShader() failed. GLES30 error: " + GLES30.glGetError());
        }
        GLES30.glShaderSource(shader, source);
        GLES30.glCompileShader(shader);
        int[] compileStatus = new int[] {GLES30.GL_FALSE};
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0);
        if (compileStatus[0] != GLES30.GL_TRUE) {
            Log.e(
                    TAG, "Compile error " + GLES30.glGetShaderInfoLog(shader) + " in shader:\n" + source);
            throw new RuntimeException(GLES30.glGetShaderInfoLog(shader));
        }
        return shader;
    }

    private int program;

    public GlShader(String vertexSource, String fragmentSource) {
        final int vertexShader = compileShader(GLES31.GL_VERTEX_SHADER, vertexSource);
        final int fragmentShader = compileShader(GLES31.GL_FRAGMENT_SHADER, fragmentSource);
        program = GLES31.glCreateProgram();
        if (program == 0) {
            throw new RuntimeException("glCreateProgram() failed. GLES30 error: " + GLES30.glGetError());
        }
        GLES31.glAttachShader(program, vertexShader);
        GLES31.glAttachShader(program, fragmentShader);
        GLES31.glLinkProgram(program);
        int[] linkStatus = new int[] {GLES31.GL_FALSE};
        GLES31.glGetProgramiv(program, GLES31.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES31.GL_TRUE) {
            Log.e(TAG, "Could not link program: " + GLES31.glGetProgramInfoLog(program));
            throw new RuntimeException(GLES31.glGetProgramInfoLog(program));
        }
        // According to the documentation of glLinkProgram():
        // "After the link operation, applications are free to modify attached shader objects, compile
        // attached shader objects, detach shader objects, delete shader objects, and attach additional
        // shader objects. None of these operations affects the information log or the program that is
        // part of the program object."
        // But in practice, detaching shaders from the program seems to break some devices. Deleting the
        // shaders are fine however - it will delete them when they are no longer attached to a program.
        GLES31.glDeleteShader(vertexShader);
        GLES31.glDeleteShader(fragmentShader);
    }

    public int getAttribLocation(String label) {
        if (program == -1) {
            throw new RuntimeException("The program has been released");
        }
        int location = GLES30.glGetAttribLocation(program, label);
        if (location < 0) {
            throw new RuntimeException("Could not locate '" + label + "' in program");
        }
        return location;
    }

    /**
     * Enable and upload a vertex array for attribute `label`. The vertex data is specified in
     * `buffer` with `dimension` number of components per vertex.
     */
    public void setVertexAttribArray(String label, int dimension, FloatBuffer buffer) {
        setVertexAttribArray(label, dimension, 0 /* stride */, buffer);
    }

    /**
     * Enable and upload a vertex array for attribute `label`. The vertex data is specified in
     * `buffer` with `dimension` number of components per vertex and specified `stride`.
     */
    public void setVertexAttribArray(String label, int dimension, int stride, FloatBuffer buffer) {
        if (program == -1) {
            throw new RuntimeException("The program has been released");
        }
        int location = getAttribLocation(label);
        GLES30.glEnableVertexAttribArray(location);
        GLES30.glVertexAttribPointer(location, dimension, GLES30.GL_FLOAT, false, stride, buffer);
    }

    public int getUniformLocation(String label) {
        if (program == -1) {
            throw new RuntimeException("The program has been released");
        }
        return GLES30.glGetUniformLocation(program, label);
    }

    public void useProgram() {
        if (program == -1) {
            throw new RuntimeException("The program has been released");
        }
        GLES30.glUseProgram(program);
    }

    public void release() {
        Log.d(TAG, "Deleting shader.");
        // Delete program, automatically detaching any shaders from it.
        if (program != -1) {
            GLES30.glDeleteProgram(program);
            program = -1;
        }
    }
}
