package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.MethodSignaturePicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.lang.reflect.Method
import java.util.UUID

class MethodSignaturePickerNamedTest {

    private fun realMethod(): Method = String::class.java.getDeclaredMethod("length")

    private fun candidates(): List<MethodSignaturePicker.Candidate> = listOf(
        MethodSignaturePicker.Candidate("handleShowInner", arrayOf(UUID::class.java)),
        MethodSignaturePicker.Candidate("-\$\$Nest\$mhandleShow", arrayOf(String::class.java, UUID::class.java)),
    )

    @Test
    fun instanceMethodWinsWhenPresent() {
        val m = realMethod()
        val hit = MethodSignaturePicker.pickNamed(candidates()) { name, _ ->
            if (name == "handleShowInner") m else null
        }
        assertEquals("handleShowInner", hit?.name)
        assertEquals(1, hit?.params?.size)
    }

    @Test
    fun fallsBackToNestBridge() {
        val m = realMethod()
        val hit = MethodSignaturePicker.pickNamed(candidates()) { name, _ ->
            if (name.startsWith("-")) m else null
        }
        assertEquals("-\$\$Nest\$mhandleShow", hit?.name)
        assertEquals(2, hit?.params?.size)
    }

    @Test
    fun nestBridgeFirstParamIsHostClass() {
        val c = candidates()[1]
        assertSame(String::class.java, c.params[0])
        assertSame(UUID::class.java, c.params[1])
    }

    @Test
    fun throwingLookupFallsThrough() {
        val m = realMethod()
        val hit = MethodSignaturePicker.pickNamed(candidates()) { name, _ ->
            if (name == "handleShowInner") throw NoSuchMethodException("inlined") else m
        }
        assertEquals("-\$\$Nest\$mhandleShow", hit?.name)
    }

    @Test
    fun allMissingReturnsNull() {
        assertNull(MethodSignaturePicker.pickNamed(candidates()) { _, _ -> null })
    }

    @Test
    fun emptyListReturnsNull() {
        assertNull(MethodSignaturePicker.pickNamed(emptyList()) { _, _ -> realMethod() })
    }

    @Test
    fun lookupSeesEachCandidateInOrder() {
        val seen = mutableListOf<String>()
        MethodSignaturePicker.pickNamed(candidates()) { name, _ ->
            seen.add(name)
            null
        }
        assertEquals(listOf("handleShowInner", "-\$\$Nest\$mhandleShow"), seen)
    }
}