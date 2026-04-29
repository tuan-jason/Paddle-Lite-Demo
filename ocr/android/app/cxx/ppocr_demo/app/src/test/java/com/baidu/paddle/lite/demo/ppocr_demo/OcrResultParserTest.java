package com.baidu.paddle.lite.demo.ppocr_demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class OcrResultParserTest {

    @Test
    public void parse_validJapaneseAndMixedText_mapsAllFields() {
        String json = "["
                + "{\"box\":[[10,20],[110,20],[110,52],[10,52]],\"text\":\"問題1 次の式を解け。\",\"score\":0.98},"
                + "{\"box\":[[14,70],[300,70],[300,102],[14,102]],\"text\":\"Q2: Find x + y (合計)\",\"score\":0.875}"
                + "]";

        OcrResult[] results = OcrResultParser.parse(json);

        assertNotNull(results);
        assertEquals(2, results.length);

        assertBoxEquals(results[0].box, 10, 20, 110, 20, 110, 52, 10, 52);
        assertEquals("問題1 次の式を解け。", results[0].text);
        assertEquals(0.98f, results[0].score, 0.0001f);

        assertBoxEquals(results[1].box, 14, 70, 300, 70, 300, 102, 14, 102);
        assertEquals("Q2: Find x + y (合計)", results[1].text);
        assertEquals(0.875f, results[1].score, 0.0001f);
    }

    @Test
    public void parse_nullOrEmpty_returnsEmptyArray() {
        assertEquals(0, OcrResultParser.parse(null).length);
        assertEquals(0, OcrResultParser.parse("").length);
    }

    @Test
    public void parse_malformedJson_returnsEmptyArray() {
        String malformed = "[{\"box\":[[1,2],[3,4],[5,6],[7,8]],\"text\":\"問題\",\"score\":0.9}";
        assertEquals(0, OcrResultParser.parse(malformed).length);
    }

    @Test
    public void parse_missingRequiredField_returnsEmptyArray() {
        String missingScore = "[{\"box\":[[1,2],[3,4],[5,6],[7,8]],\"text\":\"問題1\"}]";
        assertEquals(0, OcrResultParser.parse(missingScore).length);
    }

    @Test
    public void parse_invalidBoxShape_returnsEmptyArray() {
        String invalidBox = "[{\"box\":[[1,2],[3,4],[5,6]],\"text\":\"問題1\",\"score\":0.5}]";
        assertEquals(0, OcrResultParser.parse(invalidBox).length);
    }

    private static void assertBoxEquals(
            int[][] box,
            int tlX, int tlY,
            int trX, int trY,
            int brX, int brY,
            int blX, int blY
    ) {
        assertNotNull(box);
        assertEquals(4, box.length);
        for (int[] point : box) {
            assertNotNull(point);
            assertTrue(point.length >= 2);
        }
        assertEquals(tlX, box[0][0]);
        assertEquals(tlY, box[0][1]);
        assertEquals(trX, box[1][0]);
        assertEquals(trY, box[1][1]);
        assertEquals(brX, box[2][0]);
        assertEquals(brY, box[2][1]);
        assertEquals(blX, box[3][0]);
        assertEquals(blY, box[3][1]);
    }
}
