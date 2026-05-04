package com.baidu.paddle.lite.demo.ppocr_demo;

public final class OcrResult {
    /** Four corner points (x, y) in order: TL → TR → BR → BL. */
    public final int[][] box;
    public final String text;
    public final float score;

    public OcrResult(int[][] box, String text, float score) {
        this.box = box;
        this.text = text;
        this.score = score;
    }
}
