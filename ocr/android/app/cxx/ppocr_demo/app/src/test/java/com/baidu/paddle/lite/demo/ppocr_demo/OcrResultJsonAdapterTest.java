package com.baidu.paddle.lite.demo.ppocr_demo;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class OcrResultJsonAdapterTest {

    @Test
    public void toJson_emitsLegacySchemaWithStableFieldOrder() {
        OcrResult[] results = new OcrResult[]{
                line(10, 20, 110, 20, 110, 52, 10, 52, "問題1", 0.98f),
                line(14, 70, 300, 70, 300, 102, 14, 102, "Q2: Find x + y", 0.875f)
        };

        String json = OcrResultJsonAdapter.toJson(results);

        assertEquals(
                "["
                        + "{\"box\":[[10,20],[110,20],[110,52],[10,52]],\"text\":\"問題1\",\"score\":0.98},"
                        + "{\"box\":[[14,70],[300,70],[300,102],[14,102]],\"text\":\"Q2: Find x + y\",\"score\":0.875}"
                        + "]",
                json
        );
    }

    @Test
    public void toJson_escapesTextForValidJson() {
        OcrResult[] results = new OcrResult[]{
                line(0, 0, 10, 0, 10, 10, 0, 10, "quote \" slash \\ line\nnext", 0.5f)
        };

        String json = OcrResultJsonAdapter.toJson(results);

        assertEquals(
                "[{\"box\":[[0,0],[10,0],[10,10],[0,10]],\"text\":\"quote \\\" slash \\\\ line\\nnext\",\"score\":0.5}]",
                json
        );
    }

    @Test
    public void toJson_roundTripsThroughExistingParser() {
        OcrResult[] original = new OcrResult[]{
                line(1, 2, 11, 2, 11, 12, 1, 12, "設問1", 0.75f)
        };

        OcrResult[] parsed = OcrResultParser.parse(OcrResultJsonAdapter.toJson(original));

        assertEquals(1, parsed.length);
        assertEquals("設問1", parsed[0].text);
        assertEquals(0.75f, parsed[0].score, 0.0001f);
        assertEquals(1, parsed[0].box[0][0]);
        assertEquals(2, parsed[0].box[0][1]);
        assertEquals(11, parsed[0].box[2][0]);
        assertEquals(12, parsed[0].box[2][1]);
    }

    @Test
    public void toJson_normalizesInvalidInputs() {
        OcrResult[] results = new OcrResult[]{
                null,
                new OcrResult(null, "missing box", 0.5f),
                line(0, 0, 10, 0, 10, 10, 0, 10, null, Float.NaN),
                line(20, 20, 30, 20, 30, 30, 20, 30, "too high", 1.7f),
                line(40, 40, 50, 40, 50, 50, 40, 50, "too low", -0.2f)
        };

        String json = OcrResultJsonAdapter.toJson(results);

        assertEquals(
                "["
                        + "{\"box\":[[0,0],[10,0],[10,10],[0,10]],\"text\":\"\",\"score\":0.0},"
                        + "{\"box\":[[20,20],[30,20],[30,30],[20,30]],\"text\":\"too high\",\"score\":1.0},"
                        + "{\"box\":[[40,40],[50,40],[50,50],[40,50]],\"text\":\"too low\",\"score\":0.0}"
                        + "]",
                json
        );
    }

    @Test
    public void toJson_nullOrEmptyArray_returnsEmptyJsonArray() {
        assertEquals("[]", OcrResultJsonAdapter.toJson(null));
        assertEquals("[]", OcrResultJsonAdapter.toJson(new OcrResult[0]));
    }

    private static OcrResult line(
            int tlX, int tlY,
            int trX, int trY,
            int brX, int brY,
            int blX, int blY,
            String text,
            float score
    ) {
        return new OcrResult(
                new int[][]{
                        new int[]{tlX, tlY},
                        new int[]{trX, trY},
                        new int[]{brX, brY},
                        new int[]{blX, blY}
                },
                text,
                score
        );
    }
}
