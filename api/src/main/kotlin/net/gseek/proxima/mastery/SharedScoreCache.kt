package net.gseek.proxima.mastery

import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A cache in a bean, which is a cache shared by every request that instance ever serves.
 *
 * Spring beans are singletons by default. A field on one is not per-request state and not
 * per-user state — it is process-wide state reached by every thread in the pool at once, and
 * **the declaration looks identical to a field on an object only one thread touches.** There
 * is no annotation to forget and no configuration to get wrong; a `HashMap` written in the
 * obvious place is already the defect.
 *
 * `ADR-005` decided this repository has **no cache layer**, and that decision is not being
 * reopened here — closed on measurements, and nothing in this class ships on any request
 * path. What it is for is that the defect does not need a cache layer to arrive: it needs one
 * field. This class is the instrument that prices the two repairs everyone reaches for, and
 * the second one is the interesting one because it looks total and is not.
 *
 * **Every arm below passes any single-threaded test that can be written against it.**
 */
class SharedScoreCache {

    /** ① The obvious field. Not thread-safe, and nothing anywhere says so. */
    private val plain = HashMap<Long, BigDecimal>()

    /** ② The obvious repair. Thread-safe *per operation*, which is not the same claim. */
    private val concurrent = ConcurrentHashMap<Long, BigDecimal>()

    /**
     * How many times the expensive thing was actually done.
     *
     * The point of a cache is that this ends at 1 per key. It is an [AtomicInteger] because
     * an instrument that loses counts under the concurrency it is measuring would report the
     * defect as the remedy.
     */
    private val loads = AtomicInteger(0)

    val plainSize: Int get() = plain.size
    val concurrentSize: Int get() = concurrent.size
    val loadCount: Int get() = loads.get()

    fun putPlain(key: Long, value: BigDecimal) {
        plain[key] = value
    }

    fun putConcurrent(key: Long, value: BigDecimal) {
        concurrent[key] = value
    }

    /** Reading the plain map while another thread writes it. Kept separate so the failure is nameable. */
    fun iteratePlain(): Int = plain.entries.count()

    /**
     * **Check, then act — on a thread-safe map.**
     *
     * `containsKey` is atomic. `put` is atomic. The *pair* is not, and the gap between them
     * is where every other thread with the same key lives. So this loads the value once per
     * thread that arrives in the window instead of once per key, and the cache is still
     * *correct* — it just did the work it exists to avoid, N times, with nothing reporting it.
     *
     * Kotlin's `getOrPut` on a mutable map is this shape. It reads as one call.
     */
    fun loadOnceCheckThenAct(key: Long, loader: () -> BigDecimal): BigDecimal {
        val existing = concurrent[key]
        if (existing != null) return existing
        val computed = loader()
        concurrent[key] = computed
        return computed
    }

    /**
     * The same intention, expressed as one operation the map can make atomic.
     *
     * `computeIfAbsent` holds the bin's lock across the mapping function, so the function
     * runs at most once per key however many threads arrive together. That is a promise the
     * map makes and `containsKey`-then-`put` cannot: the difference is not the map, it is
     * whether the compound operation was ever handed to it as one.
     */
    fun loadOnceAtomically(key: Long, loader: () -> BigDecimal): BigDecimal =
        concurrent.computeIfAbsent(key) { loader() }

    /** The instrumented load, so the count means "the expensive thing ran". */
    fun load(value: BigDecimal): BigDecimal {
        loads.incrementAndGet()
        return value
    }

    fun clear() {
        plain.clear()
        concurrent.clear()
        loads.set(0)
    }
}
