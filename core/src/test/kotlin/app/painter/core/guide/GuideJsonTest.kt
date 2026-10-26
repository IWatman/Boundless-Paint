package app.painter.core.guide

import app.painter.core.geom.Vec2
import app.painter.core.perspective.PerspectiveConfig
import app.painter.core.perspective.PerspectiveType
import app.painter.core.shape.NodeType
import app.painter.core.shape.PathNode
import app.painter.core.symmetry.MirrorAxes
import app.painter.core.symmetry.SymmetryConfig
import app.painter.core.symmetry.SymmetryType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GuideJsonTest {
    private val full = GuideState(
        lines = LinesConfig(true, Vec2(1f, 2f), Vec2(30f, 40f), perpendicular = false, magnet = true, edge = true, locked = true),
        ellipse = EllipseConfig(true, Vec2(5f, 6f), 120f, 0.3f, 15f, magnet = true, edge = false, locked = true),
        pen = PenConfig(
            true,
            listOf(
                PathNode(Vec2(0f, 0f)),
                PathNode(Vec2(10f, 5f), NodeType.ALIGNED, Vec2(-2f, -1f), Vec2(2f, 1f)),
                PathNode(Vec2(20f, 0f), NodeType.SMOOTH),
            ),
            closed = true, locked = true,
        ),
        lazy = LazyConfig(true, 80f, 40f),
        hatching = HatchingConfig(true),
        symmetry = SymmetryConfig(true, SymmetryType.KALEIDO, Vec2(500f, 400f), 75f, MirrorAxes.VH, 8, 180f, true, true),
        perspective = PerspectiveConfig(
            true, PerspectiveType.THREE_POINT, listOf(Vec2(-10f, 4f), Vec2(900f, 4f), Vec2(400f, 2000f)),
            3f, 48f, 0.5f, Vec2(7f, 8f), snap = false, visible = false, locked = true,
        ),
        grid = GridConfig(true, 16f, 5, Vec2(3f, 3f), "#11223344", "#55667788"),
    )

    @Test
    fun roundTrip() {
        assertEquals(full, GuideJson.decode(GuideJson.encode(full)))
        assertEquals(full, GuideJson.fromElement(GuideJson.toElement(full)))
        assertEquals(GuideState(), GuideJson.decode(GuideJson.encode(GuideState())))
    }

    @Test
    fun missingBlockGivesDefaults() {
        assertEquals(GuideState(), GuideJson.decode(null))
        assertEquals(GuideState(), GuideJson.decode(""))
        assertEquals(GuideState(), GuideJson.decode("{}"))
        assertEquals(GuideState(), GuideJson.fromElement(null))
        assertEquals(GuideState(), GuideJson.decode("not json"))
    }

    @Test
    fun unknownFieldsAndEnumsAreIgnored() {
        val text = """{"v":1,"future":{"x":1},"lines":{"on":true,"a":[1,2],"b":[3,4],"wobble":5},
            |"symmetry":{"on":true,"type":"spiral","planes":12},"perspective":{"type":"curvilinear"}}""".trimMargin()
        val st = GuideJson.decode(text)
        assertTrue(st.lines.on)
        assertEquals(Vec2(3f, 4f), st.lines.b)
        assertEquals(SymmetryType.MIRROR, st.symmetry.type)
        assertEquals(12, st.symmetry.planes)
        assertEquals(PerspectiveType.ONE_POINT, st.perspective.type)
    }

    @Test
    fun usesSpecKeys() {
        val text = GuideJson.encode(full)
        for (k in listOf("\"kaleido\"", "\"3pt\"", "\"vh\"", "\"aligned\"", "\"in\"", "\"majorEvery\"", "\"fan\"", "\"horizon\"")) {
            assertTrue(k in text, "missing $k")
        }
    }
}
