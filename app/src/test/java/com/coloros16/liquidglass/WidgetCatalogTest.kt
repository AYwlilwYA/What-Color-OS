package com.coloros16.liquidglass

import com.coloros16.liquidglass.engine.edit.WidgetCatalog
import com.coloros16.liquidglass.engine.edit.WidgetSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetCatalogTest {

    @Test
    fun idsAreUniqueAndNameKeysPresent() {
        val ids = WidgetCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(WidgetCatalog.all.all { it.nameKey.isNotBlank() })
    }

    @Test
    fun everyWidgetAllowsThreeSizes() {
        for (def in WidgetCatalog.all) {
            assertTrue("${def.id} missing 1x1", def.sizes.contains(WidgetSize.SPAN_1X1))
            assertTrue("${def.id} missing 2x2", def.sizes.contains(WidgetSize.SPAN_2X2))
            assertTrue("${def.id} missing 1x4", def.sizes.contains(WidgetSize.SPAN_1X4))
            assertTrue(def.sizes.contains(def.defaultSize))
        }
    }

    @Test
    fun createUsesCatalogDefaults() {
        val widget = WidgetCatalog.create(WidgetCatalog.MUSIC)
        assertNotNull(widget)
        assertEquals(WidgetCatalog.MUSIC, widget!!.typeId)
        assertTrue(widget.enabled)
        assertNull(WidgetCatalog.create("no.such.widget"))
    }

    @Test
    fun sizeTokensRoundTrip() {
        for (size in WidgetSize.values()) {
            assertEquals(size, WidgetSize.fromToken(WidgetSize.token(size)))
        }
        assertNull(WidgetSize.fromToken("3x3"))
        assertNull(WidgetSize.fromToken(null))
    }

    @Test
    fun catalogKnowsRegisteredTypes() {
        for (def in WidgetCatalog.all) {
            assertTrue(WidgetCatalog.isKnown(def.id))
        }
        assertFalse(WidgetCatalog.isKnown("unknown"))
    }
}
