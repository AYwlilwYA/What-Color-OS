package com.coloros16.liquidglass

import com.coloros16.liquidglass.hook.MethodSignaturePicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.lang.reflect.Method
import java.util.UUID

class MethodSignaturePickerTest {

    private val name = "updateTransition"

    private fun realMethod(): Method =
        String::class.java.getDeclaredMethod("length")

    private fun candidates(): List<Array<Class<*>>> = listOf(
        arrayOf(UUID::class.java, java.lang.Float.TYPE, String::class.java, Runnable::class.java),
        arrayOf(UUID::class.java, java.lang.Float.TYPE, String::class.java),
    )

    @Test
    fun firstCandidateWinsWhenPresent() {
        val m = realMethod()
        val picked = MethodSignaturePicker.pick(name, candidates()) { _, params ->
            if (params.size == 4) m else null
        }
        assertSame(m, picked)
        assertEquals(0, MethodSignaturePicker.pickIndex(name, candidates()) { _, params ->
            if (params.size == 4) m else null
        })
    }

    @Test
    fun fallsBackToSecondWhenFirstMissing() {
        val m = realMethod()
        val picked = MethodSignaturePicker.pick(name, candidates()) { _, params ->
            if (params.size == 3) m else null
        }
        assertSame(m, picked)
        assertEquals(1, MethodSignaturePicker.pickIndex(name, candidates()) { _, params ->
            if (params.size == 3) m else null
        })
    }

    @Test
    fun throwingLookupIsTreatedAsMiss() {
        val m = realMethod()
        val picked = MethodSignaturePicker.pick(name, candidates()) { _, params ->
            if (params.size == 4) throw NoSuchMethodException("no 4-arg") else m
        }
        assertSame(m, picked)
    }

    @Test
    fun allMissingReturnsNull() {
        assertNull(MethodSignaturePicker.pick(name, candidates()) { _, _ -> null })
        assertEquals(-1, MethodSignaturePicker.pickIndex(name, candidates()) { _, _ -> null })
    }

    @Test
    fun allThrowingReturnsNull() {
        assertNull(MethodSignaturePicker.pick(name, candidates()) { _, _ ->
            throw NoSuchMethodException("none")
        })
        assertEquals(-1, MethodSignaturePicker.pickIndex(name, candidates()) { _, _ ->
            throw NoSuchMethodException("none")
        })
    }

    @Test
    fun emptyCandidatesReturnsNull() {
        assertNull(MethodSignaturePicker.pick(name, emptyList()) { _, _ -> realMethod() })
        assertEquals(-1, MethodSignaturePicker.pickIndex(name, emptyList()) { _, _ -> realMethod() })
    }

    @Test
    fun lookupReceivesRequestedNameAndParams() {
        var seenName: String? = null
        var seenSize = -1
        MethodSignaturePicker.pick(name, candidates()) { n, params ->
            seenName = n
            seenSize = params.size
            null
        }
        assertEquals(name, seenName)
        assertEquals(3, seenSize)
    }

    @Test
    fun firstThreeParamsAreIdenticalAcrossCandidates() {
        val c = candidates()
        for (i in 0..2) {
            assertEquals(c[0][i], c[1][i])
        }
    }
}