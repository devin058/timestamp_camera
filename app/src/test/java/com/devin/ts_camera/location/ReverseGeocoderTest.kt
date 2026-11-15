package com.devin.ts_camera.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Address parsing decides what the user sees stamped on their media, so the
 * ordering and the blank-level handling are pinned down here.
 */
class ReverseGeocoderTest {

    @Test
    fun `builds street level address most specific first`() {
        val json = """
            {"display_name":"北京路, 拓东街道, 盘龙区, 昆明市, 云南省, 中国",
             "address":{"road":"北京路","suburb":"拓东街道","district":"盘龙区",
                        "city":"昆明市","state":"云南省"}}
        """.trimIndent()

        assertEquals(
            "北京路, 拓东街道, 盘龙区, 昆明市, 云南省",
            ReverseGeocoder.parse(json)
        )
    }

    @Test
    fun `includes house number when present`() {
        val json = """
            {"address":{"house_number":"12","road":"南池子大街","city":"东城区"}}
        """.trimIndent()

        assertEquals("12, 南池子大街, 东城区", ReverseGeocoder.parse(json))
    }

    @Test
    fun `skips empty levels instead of leaving gaps`() {
        // "suburb" and "district" are empty strings, as Nominatim often does.
        val json = """
            {"address":{"road":"北京路","suburb":"","district":"","city":"昆明市"}}
        """.trimIndent()

        assertEquals("北京路, 昆明市", ReverseGeocoder.parse(json))
    }

    @Test
    fun `collapses repeated names across levels`() {
        // Municipality names often repeat between city and state.
        val json = """
            {"address":{"city":"北京市","state":"北京市"}}
        """.trimIndent()

        assertEquals("北京市", ReverseGeocoder.parse(json))
    }

    @Test
    fun `falls back to display_name when address object is missing`() {
        val json = """{"display_name":"某某路, 某某市"}"""

        assertEquals("某某路, 某某市", ReverseGeocoder.parse(json))
    }

    @Test
    fun `returns null for an empty address`() {
        assertNull(ReverseGeocoder.parse("""{"address":{}}"""))
    }

    @Test
    fun `returns null for malformed json without throwing`() {
        assertNull(ReverseGeocoder.parse("not json at all"))
    }

    @Test
    fun `does not surface a literal null string`() {
        // JSONObject.optString turns JSON null into the string "null".
        val json = """{"address":{"road":null,"city":"昆明市"}}"""

        assertTrue(ReverseGeocoder.parse(json) == "昆明市")
    }
}
