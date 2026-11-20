package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.SeedlingProgress
import org.junit.Assert.assertEquals
import org.junit.Test

/** S3 分段进度模型契约：tracker 定位/分段命中/边界，含阳性对照。 */
class SeedlingProgressContractTest {

    private val delivery = SeedlingProgress(
        fraction = 0.62f,
        segments = listOf(
            SeedlingProgress.Segment(0.2f, 0xFF4CAF50.toInt()),
            SeedlingProgress.Segment(0.3f, 0xFF2196F3.toInt()),
            SeedlingProgress.Segment(0.4f, 0xFFFF9800.toInt()),
            SeedlingProgress.Segment(0.1f, 0xFFE91E63.toInt()),
        ),
        points = listOf(0.2f, 0.5f, 0.9f),
    )

    @Test
    fun trackerClamped() {
        assertEquals(0.62f, delivery.trackerPosition(), 1e-4f)
        assertEquals(0f, delivery.copy(fraction = -1f).trackerPosition(), 1e-4f)
        assertEquals(1f, delivery.copy(fraction = 2f).trackerPosition(), 1e-4f)
    }

    @Test
    fun activeSegmentMidThird() {
        assertEquals("0.62 落在第三段（0.5~0.9）", 2, delivery.activeSegment())
    }

    @Test
    fun activeSegmentBoundaries() {
        assertEquals(0, delivery.copy(fraction = 0f).activeSegment())
        assertEquals(0, delivery.copy(fraction = 0.2f).activeSegment())
        assertEquals(1, delivery.copy(fraction = 0.21f).activeSegment())
        assertEquals(3, delivery.copy(fraction = 1f).activeSegment())
    }

    @Test
    fun emptySegments_positiveControl() {
        assertEquals("阳性对照：无分段返回 -1（证明命中逻辑真的在算）", -1, SeedlingProgress(fraction = 0.5f).activeSegment())
    }

    @Test
    fun pointsReached() {
        val reached = delivery.points.count { delivery.fraction >= it }
        assertEquals("0.62 已过 0.2/0.5 两个里程碑", 2, reached)
    }
}