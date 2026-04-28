package com.baidu.paddle.lite.demo.ppocr_demo;

import org.json.JSONArray;
import org.json.JSONObject;

public final class OcrResultParser {

    /** Parses the JSON string returned by {@code Native.processBitmap}.
     *  Never throws; returns an empty array on null, empty, or malformed input. */
    public static OcrResult[] parse(String json) {
        if (json == null || json.isEmpty()) return new OcrResult[0];
        try {
            JSONArray arr = new JSONArray(json);
            OcrResult[] results = new OcrResult[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                JSONArray boxArr = obj.getJSONArray("box");
                int[][] box = new int[4][2];
                for (int p = 0; p < 4; p++) {
                    JSONArray pt = boxArr.getJSONArray(p);
                    box[p][0] = pt.getInt(0);
                    box[p][1] = pt.getInt(1);
                }
                results[i] = new OcrResult(box, obj.getString("text"), (float) obj.getDouble("score"));
            }
            return results;
        } catch (Exception e) {
            return new OcrResult[0];
        }
    }
}
