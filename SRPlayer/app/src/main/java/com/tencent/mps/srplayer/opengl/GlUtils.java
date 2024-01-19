package com.tencent.mps.srplayer.opengl;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLException;
import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Map;
import java.util.TreeMap;

/** Shader helper functions. */
public class GlUtils {

    private static final String TAG = GlUtils.class.getSimpleName();
    /**
     * Converts a raw text file, saved as a resource, into an OpenGL ES shader.
     *
     * @param type The type of shader we will be creating.
     * @param filename The filename of the asset file about to be turned into a shader.
     * @param defineValuesMap The #define values to add to the top of the shader source code.
     * @return The shader object handler.
     */
    public static int loadGLShader(
            String tag, Context context, int type, String filename, Map<String, Integer> defineValuesMap)
            throws IOException {
        // Load shader source code.
        String code = readShaderFileFromAssets(context, filename);

        // Prepend any #define values specified during this run.
        String defines = "";
        for (Map.Entry<String, Integer> entry : defineValuesMap.entrySet()) {
            defines += "#define " + entry.getKey() + " " + entry.getValue() + "\n";
        }
        code = defines + code;

        // Compiles shader code.
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, code);
        GLES20.glCompileShader(shader);

        // Get the compilation status.
        final int[] compileStatus = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);

        // If the compilation failed, delete the shader.
        if (compileStatus[0] == 0) {
            Log.e(tag, "Error compiling shader: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            shader = 0;
        }

        if (shader == 0) {
            throw new RuntimeException("Error creating shader.");
        }

        return shader;
    }

    /** Overload of loadGLShader that assumes no additional #define values to add. */
    public static int loadGLShader(String tag, Context context, int type, String filename)
            throws IOException {
        Map<String, Integer> emptyDefineValuesMap = new TreeMap<>();
        return loadGLShader(tag, context, type, filename, emptyDefineValuesMap);
    }

    /**
     * Checks if we've had an error inside of OpenGL ES, and if so what that error is.
     *
     * @param label Label to report in case of error.
     * @throws RuntimeException If an OpenGL error is detected.
     */
    public static void checkGLError(String tag, String label) {
        int lastError = GLES20.GL_NO_ERROR;
        // Drain the queue of all errors.
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            Log.e(tag, label + ": glError " + error);
            lastError = error;
        }
        if (lastError != GLES20.GL_NO_ERROR) {
            throw new RuntimeException(label + ": glError " + lastError);
        }
    }

    /**
     * Converts a raw shader file into a string.
     *
     * @param filename The filename of the shader file about to be turned into a shader.
     * @return The context of the text file, or null in case of error.
     */
    public static String readShaderFileFromAssets(Context context, String filename)
            throws IOException {
        try (InputStream inputStream = context.getAssets().open(filename);
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] tokens = line.split(" ", -1);
                if (tokens[0].equals("#include")) {
                    String includeFilename = tokens[1];
                    includeFilename = includeFilename.replace("\"", "");
                    if (includeFilename.equals(filename)) {
                        throw new IOException("Do not include the calling file.");
                    }
                    sb.append(readShaderFileFromAssets(context, includeFilename));
                } else {
                    sb.append(line).append("\n");
                }
            }
            return sb.toString();
        }
    }

    public static void checkShaderError(int shader) {
        int[] compileStatus = new int[1];
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0);
        if (compileStatus[0] == 0) {
            throw new RuntimeException(GLES30.glGetShaderInfoLog(shader));
        }
    }

    public static void checkGlError() {
        int errorCode = GLES30.glGetError();
        if (errorCode != GLES30.GL_NO_ERROR) {
            throw new RuntimeException("GlError=" + errorCode);
        }
    }

    public static class GlOutOfMemoryException extends GLException {
        public GlOutOfMemoryException(int error, String msg) {
            super(error, msg);
        }
    }

    // Assert that no OpenGL ES 2.0 error has been raised.
    public static void checkNoGLES2Error(String msg) {
        int error = GLES30.glGetError();
        if (error != GLES30.GL_NO_ERROR) {
            throw error == GLES30.GL_OUT_OF_MEMORY
                    ? new GlOutOfMemoryException(error, msg)
                    : new GLException(error, msg + ": GLES30 error: " + error);
        }
    }

    public static FloatBuffer createFloatBuffer(float[] coords) {
        // Allocate a direct ByteBuffer, using 4 bytes per float, and copy coords into it.
        ByteBuffer bb = ByteBuffer.allocateDirect(coords.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(coords);
        fb.position(0);
        return fb;
    }

    /**
     * Generate texture with standard parameters.
     */
    public static int generateTexture(int target) {
        final int[] textureArray = new int[1];
        GLES30.glGenTextures(1, textureArray, 0);
        checkNoGLES2Error("genTexture");
        final int textureId = textureArray[0];
        GLES30.glBindTexture(target, textureId);
        GLES30.glTexParameterf(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameterf(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameterf(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameterf(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        checkNoGLES2Error("generateTexture");
        return textureId;
    }
}
