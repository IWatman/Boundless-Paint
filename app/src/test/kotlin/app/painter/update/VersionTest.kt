package app.painter.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {

    @Test
    fun parsesCommonTagFormats() {
        assertEquals(Version(0, 1, 0), Version.parse("0.1.0"))
        assertEquals(Version(0, 1, 0), Version.parse("v0.1.0"))
        assertEquals(Version(1, 2, 3), Version.parse("  V1.2.3 "))
        assertEquals(Version(2, 0, 0, "beta.1"), Version.parse("v2.0.0-beta.1"))
        assertEquals(Version(1, 0, 0), Version.parse("1.0"))
        assertEquals(Version(3, 0, 0), Version.parse("3"))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(Version.parse(""))
        assertNull(Version.parse("latest"))
        assertNull(Version.parse("v"))
        assertNull(Version.parse("1.0.0.0"))
    }

    @Test
    fun comparesNumbers() {
        assertTrue(Version.parse("0.2.0")!! > Version.parse("0.1.9")!!)
        assertTrue(Version.parse("1.0.0")!! > Version.parse("0.99.99")!!)
        assertTrue(Version.parse("0.1.10")!! > Version.parse("0.1.9")!!)
        assertEquals(0, Version.parse("v0.1.0")!!.compareTo(Version.parse("0.1.0")!!))
    }

    @Test
    fun preReleaseSortsBelowRelease() {
        assertTrue(Version.parse("0.2.0-beta.1")!! < Version.parse("0.2.0")!!)
        assertTrue(Version.parse("0.2.0-beta.2")!! > Version.parse("0.2.0-beta.1")!!)
        assertTrue(Version.parse("0.2.0-beta.10")!! > Version.parse("0.2.0-beta.9")!!)
        assertTrue(Version.parse("0.2.0-rc.1")!! > Version.parse("0.2.0-beta.9")!!)
    }

    @Test
    fun picksFirstNonDraftReleaseWithApk() {
        val info = latestUpdate(RELEASES_JSON, "0.1.0")
        requireNotNull(info)
        assertEquals("v0.2.0", info.tagName)
        assertEquals(Version(0, 2, 0), info.version)
        assertEquals("Notes for 0.2.0", info.notes)
        assertEquals("https://example.com/painter-0.2.0.apk", info.apkUrl)
        assertEquals(1234L, info.apkSize)
    }

    @Test
    fun reportsUpToDateWhenNewestReleaseIsNotNewer() {
        assertNull(latestUpdate(RELEASES_JSON, "0.2.0"))
        assertNull(latestUpdate(RELEASES_JSON, "v0.3.0"))
    }

    @Test
    fun reportsUpToDateWhenReleaseHasNoApk() {
        val json = """
            [
              {"tag_name": "v0.9.0", "draft": false, "prerelease": false, "body": "",
               "assets": [{"name": "notes.txt", "browser_download_url": "https://example.com/n.txt", "size": 3}]}
            ]
        """.trimIndent()
        assertNull(latestUpdate(json, "0.1.0"))
    }

    @Test
    fun ignoresDrafts() {
        val json = """
            [
              {"tag_name": "v0.9.0", "draft": true, "prerelease": true, "body": "draft",
               "assets": [{"name": "app.apk", "browser_download_url": "https://example.com/d.apk", "size": 10}]}
            ]
        """.trimIndent()
        assertNull(latestUpdate(json, "0.1.0"))
    }

    @Test
    fun survivesMalformedJson() {
        assertNull(latestUpdate("not json", "0.1.0"))
        assertNull(latestUpdate("[]", "0.1.0"))
    }

    private companion object {
        val RELEASES_JSON = """
            [
              {
                "tag_name": "v0.3.0-beta.1",
                "name": "Painter 0.3.0 beta",
                "draft": true,
                "prerelease": true,
                "body": "Draft notes",
                "assets": [
                  {"name": "painter-0.3.0-beta.1.apk", "browser_download_url": "https://example.com/draft.apk", "size": 99}
                ]
              },
              {
                "tag_name": "v0.2.0",
                "name": "Painter 0.2.0",
                "draft": false,
                "prerelease": false,
                "body": "Notes for 0.2.0",
                "assets": [
                  {"name": "checksums.txt", "browser_download_url": "https://example.com/sums.txt", "size": 42},
                  {"name": "painter-0.2.0.apk", "browser_download_url": "https://example.com/painter-0.2.0.apk", "size": 1234}
                ]
              },
              {
                "tag_name": "v0.1.0",
                "name": "Painter 0.1.0",
                "draft": false,
                "prerelease": false,
                "body": "First release",
                "assets": [
                  {"name": "painter-0.1.0.apk", "browser_download_url": "https://example.com/painter-0.1.0.apk", "size": 100}
                ]
              }
            ]
        """.trimIndent()
    }
}
