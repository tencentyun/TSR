package com.tencent.mps.srplayer.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import java.io.ByteArrayOutputStream;

public class ImageUtil {

    // 新增辅助方法：Bitmap转YUV数据
    public static byte[] bitmapToYuv(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] argb = new int[width * height];
        bitmap.getPixels(argb, 0, width, 0, 0, width, height);

        byte[] yuv = new byte[width * height * 3 / 2]; // I420格式
        encodeYUV420SP(yuv, argb, width, height);
        return yuv;
    }

    // 新增辅助方法：将Y平面与原始UV数据组合
    public static byte[] combineYWithUV(byte[] originalYuv, byte[] newYPlane,
            int originalWidth, int originalHeight,
            int newWidth, int newHeight) {
        int scale = newWidth / originalWidth;
        byte[] resultYuv = new byte[newWidth * newHeight * 3 / 2];

        // 复制新的Y平面数据
        System.arraycopy(newYPlane, 0, resultYuv, 0, newYPlane.length);

        // 处理UV数据：根据缩放因子调整UV平面
        int uvOriginalWidth = originalWidth / 2;
        int uvOriginalHeight = originalHeight / 2;
        int uvNewWidth = newWidth / 2;
        int uvNewHeight = newHeight / 2;

        // 复制原始UV数据（如果需要缩放，这里简单复制，实际可能需要插值）
        int uvStart = originalWidth * originalHeight;
        int uvNewStart = newWidth * newHeight;

        if (scale == 1) {
            // 相同尺寸，直接复制UV
            System.arraycopy(originalYuv, uvStart, resultYuv, uvNewStart,
                    originalYuv.length - uvStart);
        } else {
            // 缩放情况：这里需要实现UV平面的缩放，简单版本可以直接复制（效果可能不佳）
            // 实际应用中可能需要使用双线性插值等算法
            for (int i = 0; i < uvNewHeight; i++) {
                int srcRow = Math.min(i / scale, uvOriginalHeight - 1);
                for (int j = 0; j < uvNewWidth; j++) {
                    int srcCol = Math.min(j / scale, uvOriginalWidth - 1);
                    int srcIndex = uvStart + srcRow * uvOriginalWidth * 2 + srcCol * 2;
                    int dstIndex = uvNewStart + i * uvNewWidth * 2 + j * 2;

                    if (srcIndex + 1 < originalYuv.length && dstIndex + 1 < resultYuv.length) {
                        resultYuv[dstIndex] = originalYuv[srcIndex];     // U
                        resultYuv[dstIndex + 1] = originalYuv[srcIndex + 1]; // V
                    }
                }
            }
        }

        return resultYuv;
    }

    // 新增辅助方法：YUV转Bitmap
    public static Bitmap yuvToBitmap(byte[] yuvData, int width, int height) {
        YuvImage yuvImage = new YuvImage(yuvData, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, width, height), 100, os);
        byte[] jpegData = os.toByteArray();
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
        return bitmap;
    }

    // RGB转YUV的辅助方法（需要实现）
    public static void encodeYUV420SP(byte[] yuv420sp, int[] argb, int width, int height) {
        final int frameSize = width * height;

        int yIndex = 0;
        int uvIndex = frameSize;

        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int R = (argb[yIndex] & 0xff0000) >> 16;
                int G = (argb[yIndex] & 0xff00) >> 8;
                int B = (argb[yIndex] & 0xff);

                // RGB to YUV
                int Y = ((66 * R + 129 * G + 25 * B + 128) >> 8) + 16;
                int U = ((-38 * R - 74 * G + 112 * B + 128) >> 8) + 128;
                int V = ((112 * R - 94 * G - 18 * B + 128) >> 8) + 128;

                yuv420sp[yIndex] = (byte) ((Y < 0) ? 0 : ((Y > 255) ? 255 : Y));

                if (j % 2 == 0 && i % 2 == 0) {
                    yuv420sp[uvIndex++] = (byte) ((V < 0) ? 0 : ((V > 255) ? 255 : V));
                    yuv420sp[uvIndex++] = (byte) ((U < 0) ? 0 : ((U > 255) ? 255 : U));
                }

                yIndex++;
            }
        }
    }
}
