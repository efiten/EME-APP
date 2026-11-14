package be.on5gs.eme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** De User-Agent naar HB9Q en W3SZ staat op één plek en volgt de app-versie uit build.gradle.kts. */
class UserAgentTest {

    private val prefix = "EME-ON5GS-Android/"

    @Test
    fun userAgentIsDefinedOnceWithTheAppVersion() {
        val hits = File("src/main/java").walk()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f -> f.readLines().filter { prefix in it }.map { f.name to it.trim() } }
            .toList()
        assertEquals("plaatsen met $prefix: $hits", 1, hits.size)
        assertTrue("versie komt uit BuildConfig: ${hits.single()}", "BuildConfig.VERSION_NAME" in hits.single().second)
    }
}
