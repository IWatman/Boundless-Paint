package app.painter.core.guide

import app.painter.core.stroke.InputPoint
import app.painter.core.stroke.PointSink
import app.painter.core.stroke.PointStage

/** Records output as sub-strokes split by lift(). */
class RecordingSink : PointSink {
    val strokes = mutableListOf(mutableListOf<InputPoint>())
    val all: List<InputPoint> get() = strokes.flatten()

    override fun point(p: InputPoint) {
        strokes.last().add(p)
    }

    override fun lift() {
        if (strokes.last().isNotEmpty()) strokes.add(mutableListOf())
    }

    val nonEmpty: List<List<InputPoint>> get() = strokes.filter { it.isNotEmpty() }
}

/** Chains stages like the stroke engine: the first point a stage emits begins the next stage. */
class Chain(private val stages: List<PointStage>, private val sink: PointSink) {
    private val sinks: List<PointSink>

    init {
        val list = ArrayList<PointSink>()
        var next: PointSink = sink
        for (i in stages.indices.reversed()) {
            val stage = stages.getOrNull(i + 1)
            val downstream = next
            val s = if (stage == null) downstream else object : PointSink {
                var started = false
                override fun point(p: InputPoint) {
                    if (!started) { started = true; stage.begin(p, downstream) } else stage.add(p, downstream)
                }
                override fun lift() = downstream.lift()
            }
            list.add(0, s)
            next = s
        }
        sinks = list
    }

    fun run(points: List<InputPoint>) {
        stages[0].begin(points[0], sinks[0])
        for (p in points.drop(1)) stages[0].add(p, sinks[0])
        for (i in stages.indices) stages[i].end(sinks[i])
    }
}

fun runStage(stage: PointStage, points: List<InputPoint>): RecordingSink {
    val sink = RecordingSink()
    stage.begin(points[0], sink)
    for (p in points.drop(1)) stage.add(p, sink)
    stage.end(sink)
    return sink
}
