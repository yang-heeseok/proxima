package net.gseek.proxima.mastery

/**
 * A flag one thread writes and another may never read.
 *
 * The shape is a shutdown or refresh signal — `while (running) { … }` in a background thread,
 * and something else setting `running = false`. It has no lock because there is nothing to
 * serialise: one writer, one reader, one boolean, and a torn `boolean` is not a thing.
 *
 * What is missing is not atomicity, it is **visibility**. Without `@Volatile` nothing requires
 * the reader's thread to ever observe the write, and the standard permits the JIT to hoist the
 * read out of the loop entirely — turning `while (running)` into `while (true)`, which is a
 * correct compilation of a program that established no happens-before edge between the two
 * threads.
 *
 * ⚠ **Whether it happens on any given machine is a different question from whether it is
 * permitted**, and this class exists to keep the two apart. On x86 the hardware is strongly
 * ordered, so a store becomes visible almost immediately and the defect, when it appears at
 * all, is the compiler's doing rather than the memory system's — which makes it depend on
 * whether C2 compiled the loop before the write landed, and therefore on how the surrounding
 * test was scheduled.
 *
 * So the instrument is built as a **pair**: the same loop over a plain field and over a
 * `@Volatile` one, in the same run, on the same machine, warmed the same way. One arm is the
 * control. A verdict of *not observed* then means what it says and nothing more.
 */
class VisibilityFlag {

    /** No `@Volatile`. The reader is not required to ever see a write to this. */
    private var plainRunning = true

    /**
     * The same field with the one keyword.
     *
     * A `@Volatile` write happens-before every subsequent read of it, so the read cannot be
     * hoisted and the loop must terminate. This arm is here to prove the harness can observe a
     * termination at all — without it, *the loop did not exit* and *the writer never ran* are
     * the same output, which is `ADR-015`'s finding arriving in a different package.
     */
    @Volatile
    private var volatileRunning = true

    fun stopPlain() {
        plainRunning = false
    }

    fun stopVolatile() {
        volatileRunning = false
    }

    fun resetPlain() {
        plainRunning = true
    }

    fun resetVolatile() {
        volatileRunning = true
    }

    /**
     * Spin on the plain field, up to [bound] iterations.
     *
     * **The bound is what makes this safe to run beside other work, and it is a parameter, not
     * a measurement.** An unbounded spin on a hoisted read never returns, and a test that
     * leaves a thread burning a core for the rest of the JVM's life would corrupt every number
     * taken after it — including numbers belonging to somebody else.
     *
     * Returns the iteration the loop stopped at. [bound] means the write was never observed;
     * anything less means it was.
     */
    fun spinOnPlain(bound: Long): Long {
        var i = 0L
        while (i < bound && plainRunning) {
            i++
        }
        return i
    }

    /** The control, identical but for the one keyword on the field it reads. */
    fun spinOnVolatile(bound: Long): Long {
        var i = 0L
        while (i < bound && volatileRunning) {
            i++
        }
        return i
    }
}
