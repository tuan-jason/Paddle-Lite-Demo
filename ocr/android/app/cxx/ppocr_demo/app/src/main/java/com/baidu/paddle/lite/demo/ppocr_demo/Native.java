package com.baidu.paddle.lite.demo.ppocr_demo;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;

import java.nio.ByteBuffer;

import com.baidu.paddle.lite.demo.common.SDKExceptions;
import com.baidu.paddle.lite.demo.common.Utils;

public class Native {
    static {
        System.loadLibrary("Native");
    }

    private long ctx = 0;
    private boolean run_status = false;

    public boolean init(Context mContext,
                        String detModelPath,
                        String clsModelPath,
                        String recModelPath,
                        String configPath,
                        String labelPath,
                        int cputThreadNum,
                        String cpuPowerMode) {
        ctx = nativeInit(
                detModelPath,
                clsModelPath,
                recModelPath,
                configPath,
                labelPath,
                cputThreadNum,
                cpuPowerMode);
        return ctx == 0;
    }

    public boolean release() {
        if (ctx == 0) {
            return false;
        }
        return nativeRelease(ctx);
    }

    public boolean process(int inTextureId, int outTextureId, int textureWidth, int textureHeight, String savedImagePath) {
        if (ctx == 0) {
            return false;
        }
        run_status = nativeProcess(ctx, inTextureId, outTextureId, textureWidth, textureHeight, savedImagePath);
        return run_status;
    }


    public static native long nativeInit(String detModelPath,
                                         String clsModelPath,
                                         String recModelPath,
                                         String configPath,
                                         String labelPath,
                                         int cputThreadNum,
                                         String cpuPowerMode);

    public static native boolean nativeRelease(long ctx);

    public String processBitmap(Bitmap bitmap, String savedImagePath) {
        if (ctx == 0) return null;
        Bitmap rgba = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        ByteBuffer buffer = ByteBuffer.allocate(rgba.getByteCount());
        rgba.copyPixelsToBuffer(buffer);
        long t0 = SystemClock.elapsedRealtime();
        String json = nativeProcessBitmap(ctx, buffer.array(), rgba.getWidth(), rgba.getHeight(), savedImagePath);
        long processBitmapMs = SystemClock.elapsedRealtime() - t0;
        OcrLatencyBaselineLogger.logProcessBitmapSample(
                savedImagePath,
                processBitmapMs,
                rgba.getWidth(),
                rgba.getHeight()
        );
        return json;
    }

    public static native boolean nativeProcess(long ctx, int inTextureId, int outTextureId, int textureWidth, int textureHeight, String savedImagePath);

    public static native String nativeProcessBitmap(long ctx, byte[] rgbaPixels, int width, int height, String savedImagePath);
}
