package com.baidu.paddle.lite.demo.ppocr_demo;

import org.json.JSONObject;

public final class OcrResultJsonAdapter {

    private OcrResultJsonAdapter() {
    }

    public static String toJson(OcrResult[] results) {
        if (results == null || results.length == 0) return "[]";

        StringBuilder json = new StringBuilder();
        json.append('[');
        boolean needsComma = false;
        for (OcrResult result : results) {
            if (!isValid(result)) continue;
            if (needsComma) json.append(',');
            appendResult(json, result);
            needsComma = true;
        }
        json.append(']');
        return json.toString();
    }

    private static void appendResult(StringBuilder json, OcrResult result) {
        json.append('{');
        json.append("\"box\":");
        appendBox(json, result.box);
        json.append(',');
        json.append("\"text\":").append(JSONObject.quote(result.text == null ? "" : result.text));
        json.append(',');
        json.append("\"score\":").append(Float.toString(normalizeScore(result.score)));
        json.append('}');
    }

    private static void appendBox(StringBuilder json, int[][] box) {
        json.append('[');
        for (int i = 0; i < 4; i++) {
            if (i > 0) json.append(',');
            json.append('[')
                    .append(box[i][0])
                    .append(',')
                    .append(box[i][1])
                    .append(']');
        }
        json.append(']');
    }

    private static boolean isValid(OcrResult result) {
        if (result == null || result.box == null || result.box.length < 4) return false;
        for (int i = 0; i < 4; i++) {
            if (result.box[i] == null || result.box[i].length < 2) return false;
        }
        return true;
    }

    private static float normalizeScore(float score) {
        if (Float.isNaN(score)) return 0.0f;
        if (score < 0.0f) return 0.0f;
        if (score > 1.0f) return 1.0f;
        return score;
    }
}
