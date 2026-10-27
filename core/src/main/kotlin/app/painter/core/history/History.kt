package app.painter.core.history

import app.painter.core.doc.Document

/**
 * One undoable change to a [Document]. [apply] and [revert] must be exact inverses and may be
 * called repeatedly in alternation. Implementations keep [Document.modified] and
 * [Document.activeId] valid.
 */
interface Command {
    /** Human-readable label shown next to undo/redo. */
    val name: String

    fun apply(doc: Document)

    fun revert(doc: Document)

    /** Approximate memory retained by this command, used for the history budget. */
    val byteSize: Long
}

/** Runs [commands] in order as one step; reverts them in reverse order. */
class CompoundCommand(
    override val name: String,
    val commands: List<Command>,
) : Command {
    override fun apply(doc: Document) {
        for (c in commands) c.apply(doc)
    }

    override fun revert(doc: Document) {
        for (i in commands.indices.reversed()) commands[i].revert(doc)
    }

    override val byteSize: Long get() = commands.sumOf { it.byteSize }
}

/**
 * Linear undo/redo stack (spec/document-model.md «History (undo)»). Limited to [maxSteps] entries and
 * [maxBytes] of retained data, whichever is hit first; the oldest entries are evicted.
 * The most recent entry is always kept even if it alone exceeds [maxBytes].
 */
class History(
    val maxSteps: Int = 100,
    val maxBytes: Long = 512L shl 20,
) {
    private val undoStack = ArrayDeque<Command>()
    private val redoStack = ArrayDeque<Command>()

    /** Total [Command.byteSize] of all retained entries (undo and redo). */
    var totalBytes: Long = 0
        private set

    /** Invoked after every change of the stacks. */
    var onChanged: (() -> Unit)? = null

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoName: String? get() = undoStack.lastOrNull()?.name
    val redoName: String? get() = redoStack.lastOrNull()?.name

    /** Number of undoable steps currently held. */
    val undoSize: Int get() = undoStack.size

    /** Number of redoable steps currently held. */
    val redoSize: Int get() = redoStack.size

    /** Applies [cmd] to [doc] and records it. */
    fun execute(doc: Document, cmd: Command) {
        cmd.apply(doc)
        push(cmd)
    }

    /** Records an already applied [cmd]. Clears the redo stack. */
    fun push(cmd: Command) {
        for (c in redoStack) totalBytes -= c.byteSize
        redoStack.clear()
        undoStack.addLast(cmd)
        totalBytes += cmd.byteSize
        evict()
        onChanged?.invoke()
    }

    /** Reverts the latest step. Returns false if there is nothing to undo. */
    fun undo(doc: Document): Boolean {
        val cmd = undoStack.removeLastOrNull() ?: return false
        cmd.revert(doc)
        redoStack.addLast(cmd)
        onChanged?.invoke()
        return true
    }

    /** Re-applies the latest undone step. Returns false if there is nothing to redo. */
    fun redo(doc: Document): Boolean {
        val cmd = redoStack.removeLastOrNull() ?: return false
        cmd.apply(doc)
        undoStack.addLast(cmd)
        onChanged?.invoke()
        return true
    }

    /** Drops all entries. */
    fun clear() {
        undoStack.clear()
        redoStack.clear()
        totalBytes = 0
        onChanged?.invoke()
    }

    private fun evict() {
        while (undoStack.size > maxSteps || (totalBytes > maxBytes && undoStack.size > 1)) {
            totalBytes -= undoStack.removeFirst().byteSize
        }
    }
}
