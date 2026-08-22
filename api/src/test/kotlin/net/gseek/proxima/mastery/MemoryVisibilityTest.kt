package net.gseek.proxima.mastery

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `E3` — a flag one thread writes and another may never read.
 *
 * ⚠ **This may not reproduce, and the brief says that is a result rather than a failure.**
 * The Java memory model *permits* the reader never to observe the write; it does not require
 * it. On x86 the hardware is strongly ordered, so if the defect shows at all it is the
 * compiler hoisting the read, which depends on whether C2 had compiled the loop before the
 * write landed.
 *
 * So the instrument is a **pair**, and the pair is what makes the verdict readable on a busy
 * machine. Both arms run in the same JVM, in the same test, warmed identically. Scheduling
 * noise moves both; a hoisted read moves only one. **If the `@Volatile` arm terminates and
 * the plain arm does not, that difference is not scheduling.**
 *
 * ⭐ The verdict is a boolean — *was the write observed within the bound* — and the bound is a
 * chosen parameter, not a measurement. No duration and no iteration rate is published from
 * this file.
 */
class MemoryVisibilityTest {

    /**
     * How far the reader is allowed to spin before giving up.
     *
     * Large enough that the writer, which does one field assignment, has certainly run long
     * before the loop could exhaust it — and finite so that no thread from this test is still
     * burning a core when the next test, or another slice, starts measuring something.
     */
    private val bound = 2_000_000_000L

    /** Enough repetitions to say whether an outcome was consistent, not enough to be a sweep. */
    private val trials = 3

    private data class Verdict(val stoppedAt: Long, val bound: Long) {
        val observed: Boolean get() = stoppedAt < bound
    }

    /**
     * Warms the loop so that C2 has compiled it before the trial that matters.
     *
     * Without this the reader spends the interesting window in the interpreter, which reads
     * the field every time — so the defect could not appear, and a *not observed* verdict
     * would be a statement about the warm-up rather than about the memory model.
     */
    private fun warmUp(flag: VisibilityFlag) {
        repeat(3) {
            flag.resetPlain()
            flag.resetVolatile()
            flag.spinOnPlain(50_000_000L)
            flag.spinOnVolatile(50_000_000L)
        }
    }

    private fun trial(volatileArm: Boolean): Verdict {
        val flag = VisibilityFlag()
        warmUp(flag)
        flag.resetPlain()
        flag.resetVolatile()

        val entered = CountDownLatch(1)
        var stoppedAt = -1L
        val reader = Thread {
            entered.countDown()
            stoppedAt = if (volatileArm) flag.spinOnVolatile(bound) else flag.spinOnPlain(bound)
        }
        reader.isDaemon = true
        reader.start()

        // The latch orders the reader BEFORE the write, not after it, so it publishes nothing
        // the loop could go on to read. It only establishes that the loop has been entered.
        entered.await(30, TimeUnit.SECONDS)
        Thread.yield()
        if (volatileArm) flag.stopVolatile() else flag.stopPlain()

        reader.join(TimeUnit.MINUTES.toMillis(5))
        check(!reader.isAlive) { "the bounded reader outlived its bound; the harness is wrong, not the JMM" }
        return Verdict(stoppedAt, bound)
    }

    @Test
    fun `a plain flag written by one thread is seen by another`() {
        val control = (1..trials).map { trial(volatileArm = true) }
        val plain = (1..trials).map { trial(volatileArm = false) }

        println("E3 >>> bound=$bound trials=$trials")
        println("E3 >>> @Volatile control   observed=${control.count { it.observed }}/$trials  stoppedAt=${control.map { it.stoppedAt }}")
        println("E3 >>> plain field         observed=${plain.count { it.observed }}/$trials  stoppedAt=${plain.map { it.stoppedAt }}")

        assertEquals(
            trials, control.count { it.observed },
            "THE CONTROL FAILED, so nothing below it means anything: a @Volatile write must " +
                "be observed. If this line is what is red, the harness is broken and the " +
                "plain arm's verdict must not be read at all",
        )
        assertEquals(
            trials, plain.count { it.observed },
            "a write to a plain field was not observed within $bound iterations. NOT OBSERVED " +
                "IS NOT THE SAME AS DOES NOT HAPPEN, and the reverse is also true: this " +
                "assertion failing means the JMM permitted something and this machine took it",
        )
    }
}
