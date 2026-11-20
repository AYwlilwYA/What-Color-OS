package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.HandPaintFormat
import com.coloros16.liquidglass.engine.HandPaintMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandPaintFormatContractTest {

    private fun frame(colors: String, counts: String, steps: String) =
        "{\"Colors\":[$colors],\"PointsCounts\":[$counts],\"Steps\":[$steps]}"

    private fun silk(frames: String, spf: Int = 2) =
        "{\"HandPaintedKaleidoscope\":\"1.0.0\",\"SilkNum\":1,\"Silks\":[{" +
            "\"SilkID\":0,\"Enable\":true,\"Mirror\":true,\"Rotates\":6,\"Spirals\":2," +
            "\"StepsPerFrame\":$spf,\"BrushLineWidth\":2,\"TotalFrames\":1," +
            "\"CentralPointX\":510.0,\"CentralPointY\":510.0,\"Frames\":[$frames]}]}"

    private val twoLaneFrame = frame(
        "{\"R\":0.9,\"G\":0.4,\"B\":0.44,\"A\":0.09},{\"R\":0.1,\"G\":0.2,\"B\":0.3,\"A\":0.05}",
        "{\"PointsCount\":2},{\"PointsCount\":2}",
        "{\"Points\":[{\"X\":10.0,\"Y\":20.0},{\"X\":30.0,\"Y\":40.0}]}," +
            "{\"Points\":[{\"X\":10.5,\"Y\":20.5},{\"X\":30.5,\"Y\":40.5}]}",
    )

    @Test
    fun parsesVersionAndSilkParams() {
        val doc = HandPaintFormat.parse(silk(twoLaneFrame))
        assertEquals("1.0.0", doc.version)
        assertEquals(1, doc.silks.size)
        val s = doc.silks[0]
        assertEquals(0, s.id)
        assertTrue(s.enabled)
        assertTrue(s.mirror)
        assertEquals(6, s.rotates)
        assertEquals(2, s.spirals)
        assertEquals(2f, s.brushLineWidth, 0.0001f)
        assertEquals(510f, s.centerX, 0.0001f)
    }

    @Test
    fun interleavesByPointIndexNotByLane() {
        val s = HandPaintFormat.parse(silk(twoLaneFrame)).silks[0]
        assertEquals(4, s.points.size)
        assertEquals(10.0f, s.points[0].x, 0.0001f)
        assertEquals(10.5f, s.points[1].x, 0.0001f)
        assertEquals(30.0f, s.points[2].x, 0.0001f)
        assertEquals(30.5f, s.points[3].x, 0.0001f)
    }

    @Test
    fun laneMajorOrderWouldJumpAndIsNotUsed() {
        val s = HandPaintFormat.parse(silk(twoLaneFrame)).silks[0]
        val laneMajor = listOf(10.0f, 30.0f, 10.5f, 30.5f)
        assertFalse(laneMajor == s.points.map { it.x })
    }

    @Test
    fun colorFollowsItsOwnLane() {
        val s = HandPaintFormat.parse(silk(twoLaneFrame)).silks[0]
        assertEquals(0.9f, s.points[0].r, 0.0001f)
        assertEquals(0.1f, s.points[1].r, 0.0001f)
        assertEquals(0.09f, s.points[0].a, 0.0001f)
        assertEquals(0.05f, s.points[1].a, 0.0001f)
    }

    @Test
    fun declaredCountShorterThanArrayIsHonored() {
        val f = frame(
            "{\"R\":1,\"G\":0,\"B\":0,\"A\":1}",
            "{\"PointsCount\":1}",
            "{\"Points\":[{\"X\":1.0,\"Y\":2.0},{\"X\":9.0,\"Y\":9.0}]}",
        )
        val s = HandPaintFormat.parse(silk(f, spf = 1)).silks[0]
        assertEquals(1, s.points.size)
        assertEquals(1f, s.points[0].x, 0.0001f)
    }

    @Test
    fun raggedLanesUseShortest() {
        val f = frame(
            "{\"R\":1,\"G\":0,\"B\":0,\"A\":1},{\"R\":0,\"G\":1,\"B\":0,\"A\":1}",
            "{\"PointsCount\":2},{\"PointsCount\":1}",
            "{\"Points\":[{\"X\":1.0,\"Y\":1.0},{\"X\":2.0,\"Y\":2.0}]}," +
                "{\"Points\":[{\"X\":1.5,\"Y\":1.5}]}",
        )
        val s = HandPaintFormat.parse(silk(f)).silks[0]
        assertEquals(2, s.points.size)
    }

    @Test
    fun adjacentStillPointsCollapsedAcrossLanes() {
        val f = frame(
            "{\"R\":1,\"G\":0,\"B\":0,\"A\":1},{\"R\":1,\"G\":0,\"B\":0,\"A\":1}",
            "{\"PointsCount\":1},{\"PointsCount\":1}",
            "{\"Points\":[{\"X\":5.0,\"Y\":5.0}]},{\"Points\":[{\"X\":5.0,\"Y\":5.0}]}",
        )
        val s = HandPaintFormat.parse(silk(f)).silks[0]
        assertEquals(1, s.points.size)
    }

    @Test
    fun nonAdjacentRepeatIsKept() {
        val f = frame(
            "{\"R\":1,\"G\":0,\"B\":0,\"A\":1},{\"R\":1,\"G\":0,\"B\":0,\"A\":1}," +
                "{\"R\":1,\"G\":0,\"B\":0,\"A\":1}",
            "{\"PointsCount\":1},{\"PointsCount\":1},{\"PointsCount\":1}",
            "{\"Points\":[{\"X\":5.0,\"Y\":5.0}]},{\"Points\":[{\"X\":9.0,\"Y\":9.0}]}," +
                "{\"Points\":[{\"X\":5.0,\"Y\":5.0}]}",
        )
        val s = HandPaintFormat.parse(silk(f, spf = 3)).silks[0]
        assertEquals(3, s.points.size)
    }

    @Test
    fun missingOptionalFieldsUseDefaults() {
        val text = "{\"Silks\":[{\"StepsPerFrame\":1,\"Frames\":[" +
            frame(
                "{\"R\":1,\"G\":0,\"B\":0,\"A\":1}",
                "{\"PointsCount\":1}",
                "{\"Points\":[{\"X\":1.0,\"Y\":2.0}]}",
            ) + "]}]}"
        val s = HandPaintFormat.parse(text).silks[0]
        assertEquals(HandPaintMotion.DEFAULT_BRUSH_WIDTH, s.brushLineWidth, 0.0001f)
        assertEquals(1, s.rotates)
        assertEquals(1, s.spirals)
        assertFalse(s.mirror)
        assertTrue(s.enabled)
    }

    @Test
    fun zeroStepsPerFrameDropsSilk() {
        assertTrue(HandPaintFormat.parse(silk(twoLaneFrame, spf = 0)).silks.isEmpty())
    }

    @Test
    fun emptyFramesDropsSilk() {
        assertTrue(HandPaintFormat.parse(silk("")).silks.isEmpty())
    }

    @Test
    fun malformedJsonDoesNotThrow() {
        assertTrue(HandPaintFormat.parse("{").silks.isEmpty())
        assertTrue(HandPaintFormat.parse("").silks.isEmpty())
        assertTrue(HandPaintFormat.parse("{\"Silks\":[{\"StepsPerFrame\":1}]}").silks.isEmpty())
    }

    @Test
    fun bracesInsideStringsDoNotBreakSplit() {
        val text = "{\"Note\":\"a { b } c\",\"Silks\":[{\"StepsPerFrame\":1,\"Frames\":[" +
            frame(
                "{\"R\":1,\"G\":0,\"B\":0,\"A\":1}",
                "{\"PointsCount\":1}",
                "{\"Points\":[{\"X\":1.0,\"Y\":2.0}]}",
            ) + "]}]}"
        assertEquals(1, HandPaintFormat.parse(text).silks.size)
    }

    @Test
    fun multipleSilksAllParsed() {
        val one = "{\"StepsPerFrame\":1,\"Frames\":[" +
            frame(
                "{\"R\":1,\"G\":0,\"B\":0,\"A\":1}",
                "{\"PointsCount\":1}",
                "{\"Points\":[{\"X\":1.0,\"Y\":2.0}]}",
            ) + "]}"
        val doc = HandPaintFormat.parse("{\"Silks\":[$one,$one]}")
        assertEquals(2, doc.silks.size)
    }
}