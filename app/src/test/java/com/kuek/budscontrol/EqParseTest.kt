package com.kuek.budscontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EqParseTest {
    private fun show(g: FloatArray?) = g?.joinToString(" ") ?: "null"

    @Test fun fixedBand() {
        val txt = """
            Preamp: -6.0 dB
            Filter 1: ON PK Fc 31 Hz Gain 6.0 dB Q 1.41
            Filter 2: ON PK Fc 62 Hz Gain 0.0 dB Q 1.41
            Filter 6: ON PK Fc 1000 Hz Gain -4.0 dB Q 1.41
        """.trimIndent()
        val g = Eq.parse(txt)!!
        println("fixed: ${show(g)}")
        assertEquals(6f, g[0], 0.01f)
        assertEquals(-4f, g[5], 0.01f)
        assertEquals(0f, g[9], 0.5f)
    }

    @Test fun parametricWithShelves() {
        val txt = """
            Preamp: -5.2 dB
            Filter 1: ON LSC Fc 105 Hz Gain 5.0 dB Q 0.70
            Filter 2: ON PK Fc 3000 Hz Gain -3.1 dB Q 2.0
            Filter 3: ON HSC Fc 10000 Hz Gain -2.0 dB Q 0.70
        """.trimIndent()
        val g = Eq.parse(txt)!!
        println("param: ${show(g)}")
        assert(g[0] > 4f && g[1] > 3f) { show(g) }
        assert(g[9] < -1.5f) { show(g) }
    }

    @Test fun graphic() {
        val g = Eq.parse("GraphicEQ: 20 4.0; 100 2.0; 1000 0.0; 10000 -3.0; 20000 -3.0")!!
        println("graphic: ${show(g)}")
        assertEquals(0f, g[5], 0.5f)
        assertEquals(-3f, g[9], 0.5f)
    }

    @Test fun plainPairs() {
        val g = Eq.parse("frequency,gain\n31,3\n16000,-2\n")
        assertNotNull(g); println("pairs: ${show(g)}")
    }

    @Test fun garbage() { assertNull(Eq.parse("hello world")) }

    /** Optional: set EQ_SAMPLES to a folder of Poweramp .json exports to parse real files. */
    @Test fun powerampFiles() {
        val dir = java.io.File(System.getenv("EQ_SAMPLES") ?: return)
        val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: return
        for (f in files) {
            val all = Eq.parseAll(f.readText())
            println("${f.name}: ${all.size} preset(s) name=${all.firstOrNull()?.name} pre=${all.firstOrNull()?.preamp} " + show(all.firstOrNull()?.gains))
            assertEquals(1, all.size)
        }
    }

    @Test fun powerampInline() {
        val json = """[{"name": "A", "preamp": -2.5, "parametric": false, "bands": [
            {"type": 0, "frequency": 90, "q": 0.8, "gain": 0.0},
            {"type": 1, "frequency": 10000, "q": 0.6, "gain": 0.0},
            {"type": 2, "frequency": 31, "q": 0.0, "gain": 1.85},
            {"type": 2, "frequency": 62, "q": 0.0, "gain": 3.7},
            {"type": 2, "frequency": 124, "q": 0.0, "gain": 3.3},
            {"type": 2, "frequency": 249, "q": 0.0, "gain": 3.1},
            {"type": 2, "frequency": 498, "q": 0.0, "gain": 3.8},
            {"type": 2, "frequency": 996, "q": 0.0, "gain": 4.2},
            {"type": 2, "frequency": 1995, "q": 0.0, "gain": 4.3},
            {"type": 2, "frequency": 3993, "q": 0.0, "gain": 2.7},
            {"type": 2, "frequency": 7993, "q": 0.0, "gain": 5.5},
            {"type": 2, "frequency": 16000, "q": 0.0, "gain": -0.1}]},
          {"name": "B", "preamp": 0.0, "parametric": true, "bands": [
            {"type": 2, "frequency": 1000, "q": 1.0, "gain": 6.0}]}]"""
        val all = Eq.parseAll(json)
        assertEquals(2, all.size)
        assertEquals("A", all[0].name); assertEquals(-2.5f, all[0].preamp!!, 0.01f)
        assertEquals(1.9f, all[0].gains[0], 0.01f); assertEquals(5.5f, all[0].gains[8], 0.01f)
        assertEquals("B", all[1].name); assertNull(all[1].preamp)
        assert(all[1].gains[5] > 4f) { show(all[1].gains) }
    }
}
