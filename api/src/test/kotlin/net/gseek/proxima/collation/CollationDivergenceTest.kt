package net.gseek.proxima.collation

import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * `R25` — what the container tag decides, measured against the image `R9` could not reach.
 *
 * ## The question this closes
 *
 * `R9` §3.3 predicted that mixed-case ordering would diverge between H2 and PostgreSQL. It
 * did not, and naming a collation explicitly showed why: **this repository's PostgreSQL sorts
 * byte-wise.** `postgres:16-alpine` is built against musl, whose `strcoll` is byte
 * comparison, so a database declaring `en_US.utf8` gets `C` behaviour and nothing says so.
 *
 * `R9` then wrote the half it could not finish, verbatim:
 *
 * > **미측정**: whether a glibc PostgreSQL orders these four strings differently. The
 * > Debian-based image is not present locally and WSL had no network to pull it.
 *
 * That is `ADR-014` entry `D.8`. WSL has network now and the image is pinned below, so the
 * comparison is available and this class is it.
 *
 * ## Two images, both pinned by digest and not by tag
 *
 * `R27` is the reason the digests are here rather than the tags. `postgres:16-alpine` no
 * longer resolves to the image every number in this repository was taken on, so a comparison
 * written against the tag would be comparing two images neither of which is the one under
 * discussion.
 *
 * | arm | image | libc |
 * | --- | --- | --- |
 * | **A** | `postgres@sha256:57c72fd2…` — the digest in `measurement-discipline.md` | musl |
 * | **B** | `postgres@sha256:e17e8606…` — `postgres:16`, Debian | glibc |
 *
 * ## Nothing here asserts which database is right
 *
 * Both are correct; they were asked different questions. Every probe prints its outcome and
 * the report reads the numbers out. The only assertions are the two controls, and they are
 * the reason a null result below can be believed.
 *
 * ## The two controls, and why one was not enough
 *
 * `R0` §9 is explicit about this, after `R10`'s canary passed while the conclusion was wrong:
 *
 * > 대조군은 계측기가 살아 있음을 증명할 뿐, **내가 옳은 것을 겨누고 있음을 증명하지 않는다.**
 *
 * So there are two, and they fail for different reasons:
 *
 * - **ALIVE** — arm B must order `apple,Apple,Banana,cherry` and arm A must not. If both
 *   agree, this class is not reading a locale-aware collation at all and every *"no
 *   divergence"* result below is a statement about the harness rather than about the data.
 * - **AIMED** — the displacement counter must report a non-zero count on a value set in this
 *   repository's own identifier format whose divergence is **already established**. Without
 *   it, a null result on the real shapes cannot be told apart from a routine that never
 *   reports anything.
 *
 * **The AIMED control failed on its first run and the failure was the author's.** It planted
 * a misaligned hyphen — `learner-1` beside `learner-000001` — on a guess about which shapes
 * are collation-sensitive, and that guess was wrong. See the comment at the control itself,
 * and `R25` §9. A hypothesis written where a control belongs reads as a control until it runs.
 */
class CollationDivergenceTest {

    companion object {

        /**
         * The image `measurement-discipline.md` records, by digest.
         *
         * The tag is deliberately absent. `R27` measures what `postgres:16-alpine` resolves
         * to today and it is not this.
         */
        const val MUSL_DIGEST = "postgres@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777"

        /** `postgres:16`, Debian bookworm, glibc. The image `R9` §3.3 could not pull. */
        const val GLIBC_DIGEST = "postgres@sha256:e17e86066e5ef83e0952a9347f5c792b7ece00972e2aa787a6986f471b3dd3d5"

        private lateinit var musl: PostgreSQLContainer
        private lateinit var glibc: PostgreSQLContainer

        @BeforeAll
        @JvmStatic
        fun start() {
            musl = container(MUSL_DIGEST)
            glibc = container(GLIBC_DIGEST)
            musl.start()
            glibc.start()
        }

        @AfterAll
        @JvmStatic
        fun stop() {
            if (::musl.isInitialized) musl.stop()
            if (::glibc.isInitialized) glibc.stop()
        }

        private fun container(ref: String) =
            PostgreSQLContainer(DockerImageName.parse(ref).asCompatibleSubstituteFor("postgres"))
    }

    private enum class Arm(val label: String) { MUSL("A musl "), GLIBC("B glibc") }

    private fun connection(arm: Arm): Connection {
        val c = when (arm) { Arm.MUSL -> musl; Arm.GLIBC -> glibc }
        return DriverManager.getConnection(c.jdbcUrl, c.username, c.password)
    }

    /**
     * Sort [values] in the database and return the order it chose.
     *
     * The values go into a table rather than into a `values` list so that the sort is a real
     * sort over a real column of `varchar`, which is what every column in `V1__baseline.sql`
     * that holds text is. `collation` names one explicitly when given.
     */
    private fun orderedBy(arm: Arm, values: List<String>, collation: String? = null): List<String> =
        connection(arm).use { c ->
            c.createStatement().use { st ->
                st.execute("drop table if exists r25_probe")
                st.execute("create table r25_probe (v varchar(64) not null)")
            }
            c.prepareStatement("insert into r25_probe (v) values (?)").use { ps ->
                for (v in values) { ps.setString(1, v); ps.addBatch() }
                ps.executeBatch()
            }
            val order = if (collation == null) "order by v" else "order by v collate \"$collation\""
            val out = mutableListOf<String>()
            c.createStatement().use { st ->
                st.executeQuery("select v from r25_probe $order").use { rs ->
                    while (rs.next()) out.add(rs.getString(1))
                }
            }
            out
        }

    // -------------------------------------------------------------------------------------
    // 3.1  Which two servers these actually are
    // -------------------------------------------------------------------------------------

    @Test
    fun `what each image reports about itself`() {
        println("\n=== R25 §3.1  the two servers ===")
        for (arm in Arm.entries) {
            connection(arm).use { c ->
                val row = { sql: String ->
                    c.createStatement().use { st ->
                        st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else "?" }
                    }
                }
                println("${arm.label} | version        : ${row("select version()")}")
                println("${arm.label} | datcollate     : ${row("select datcollate from pg_database where datname = current_database()")}")
                println("${arm.label} | datctype       : ${row("select datctype from pg_database where datname = current_database()")}")
                println("${arm.label} | datlocprovider : ${row("select datlocprovider from pg_database where datname = current_database()")}")
                println("${arm.label} | icu collations : ${row("select count(*)::text from pg_collation where collprovider = 'i'")}")
                println("${arm.label} | libc collations: ${row("select count(*)::text from pg_collation where collprovider = 'c'")}")
                println("${arm.label} | name type coll : ${row("select coalesce((select collname from pg_collation where oid = typcollation), 'none') from pg_type where typname = 'name'")}")
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // 3.2  R9's four strings -- the 미측정 this class exists to close
    // -------------------------------------------------------------------------------------

    /** The exact four values of `R9` §3.3, so the two reports are comparable at all. */
    private val r9Strings = listOf("cherry", "Apple", "apple", "Banana")

    @Test
    fun `R9 section 3_3's four strings, on both images, and the ALIVE control`() {
        val muslOrder = orderedBy(Arm.MUSL, r9Strings)
        val glibcOrder = orderedBy(Arm.GLIBC, r9Strings)
        val glibcAsC = orderedBy(Arm.GLIBC, r9Strings, collation = "C")

        println("\n=== R25 §3.2  the four strings R9 could only sort on one image ===")
        println("A musl   order by v                 : ${muslOrder.joinToString(",")}")
        println("B glibc  order by v                 : ${glibcOrder.joinToString(",")}")
        println("B glibc  order by v collate \"C\"     : ${glibcAsC.joinToString(",")}")

        // CONTROL — ALIVE. If these agree, nothing below can be believed: it would mean the
        // glibc arm is not applying a locale-aware collation either, and every "no
        // divergence" result in this class would be a fact about the harness.
        assertTrue(
            muslOrder != glibcOrder,
            "ALIVE control failed: both images ordered R9's four strings identically as " +
                "$muslOrder. Either arm B is not glibc, or its database was not created with " +
                "a locale-aware collation. No null result in this class means anything until " +
                "this passes.",
        )
        // The musl arm must be byte order, which is what R9 §3.3 established and what
        // everything in this repository was measured on.
        assertEquals(
            listOf("Apple", "Banana", "apple", "cherry"), muslOrder,
            "arm A did not sort byte-wise. R9 §3.3 measured that it does.",
        )
        assertEquals(
            glibcAsC, muslOrder,
            "arm B told to use collate \"C\" did not reproduce arm A's order. If these differ, " +
                "the divergence is not collation and this whole report is measuring the wrong thing.",
        )
    }

    // -------------------------------------------------------------------------------------
    // 3.3  How far apart the two collations are, over the characters this schema can hold
    // -------------------------------------------------------------------------------------

    /**
     * Every character that appears in a text column of `V1__baseline.sql` under the shipped
     * generator, plus the rest of printable ASCII so the answer is about the character set
     * rather than about the sample.
     */
    private val alphabet: List<String> =
        (32..126).map { it.toChar().toString() }

    @Test
    fun `the divergence surface over printable ASCII`() {
        // Two-character strings over the alphabet would be 9,025 rows; the ordering question
        // is decided pairwise, so pairs are what get compared.
        val pairs = mutableListOf<String>()
        for (a in alphabet) for (b in alphabet) if (a < b) pairs.add(a + b)

        val muslOrder = orderedBy(Arm.MUSL, pairs)
        val glibcOrder = orderedBy(Arm.GLIBC, pairs)

        var firstDisplaced = -1
        var displaced = 0
        for (i in muslOrder.indices) {
            if (muslOrder[i] != glibcOrder[i]) {
                displaced++
                if (firstDisplaced < 0) firstDisplaced = i
            }
        }
        println("\n=== R25 §3.3  the divergence surface ===")
        println("two-character strings compared      : ${pairs.size}")
        println("positions where the orders differ   : $displaced")
        println("first differing position            : $firstDisplaced")
        println("A musl  at that position            : ${muslOrder.getOrNull(firstDisplaced)}")
        println("B glibc at that position            : ${glibcOrder.getOrNull(firstDisplaced)}")

        // Which single characters are re-ranked relative to byte order. This is the answer a
        // reader needs to decide whether their own data is exposed, and it does not depend on
        // this repository's data at all.
        val muslSingles = orderedBy(Arm.MUSL, alphabet)
        val glibcSingles = orderedBy(Arm.GLIBC, alphabet)
        println("printable ASCII, A musl             : ${muslSingles.joinToString("")}")
        println("printable ASCII, B glibc            : ${glibcSingles.joinToString("")}")
        println("single characters re-ranked         : ${muslSingles.indices.count { muslSingles[it] != glibcSingles[it] }}")
    }

    // -------------------------------------------------------------------------------------
    // 3.4  This repository's own text columns
    // -------------------------------------------------------------------------------------

    /**
     * The value shapes the shipped generator emits, transcribed from
     * `seed/src/main/kotlin/net/gseek/proxima/seed/Generator.kt`.
     *
     * **Transcribed, not generated.** `:api` does not depend on `:seed` — the module split is
     * `PUB-7`'s, so that a generator cannot be run against a real database from inside the
     * application's tests — and adding the dependency to compare five string formats would be
     * a build change in service of a measurement. The formats are one line each and the
     * citation is beside every one of them. `GRADE_BANDS` is the complete set; the others are
     * a slice chosen at the width boundaries, because the shapes are fixed-width and it is a
     * width boundary that would break the alignment.
     */
    private fun repositoryTextValues(): Map<String, List<String>> {
        // Generator.kt:309 -- fun ref(kind: String, n: Int): String = "$kind-${pad(n)}"
        // Generator.kt:306 -- fun pad(n: Int): String = n.toString().padStart(6, '0')
        val ns = listOf(1, 2, 9, 10, 11, 99, 100, 101, 999, 1_000, 99_999, 100_000, 999_999)
        fun ref(kind: String) = ns.map { "$kind-${it.toString().padStart(6, '0')}" }
        return linkedMapOf(
            // Generator.kt:93  -- learner.external_ref, 1..1000 at FULL scale
            "learner.external_ref" to ref("learner"),
            // Generator.kt:108 -- concept.code, 1..3000
            "concept.code" to ref("concept"),
            // Generator.kt:109 -- concept.name = "Concept " + pad(id)
            "concept.name" to ns.map { "Concept ${it.toString().padStart(6, '0')}" },
            // Generator.kt:308 -- GRADE_BANDS, the complete set of five
            "concept.grade_band" to listOf("G1-2", "G3-4", "G5-6", "G7-9", "G10-12"),
            // Generator.kt:181 -- item.code, 1..100000
            "item.code" to ref("item"),
        )
    }

    @Test
    fun `the five text columns this schema holds, and the AIMED control`() {
        println("\n=== R25 §3.4  this repository's own text values ===")
        var columnsDiverging = 0
        for ((column, values) in repositoryTextValues()) {
            val a = orderedBy(Arm.MUSL, values)
            val b = orderedBy(Arm.GLIBC, values)
            val displaced = a.indices.count { a[it] != b[it] }
            if (displaced > 0) columnsDiverging++
            println("%-22s n=%-3d displaced=%d".format(column, values.size, displaced))
            if (displaced > 0) {
                println("    A musl : ${a.joinToString(",")}")
                println("    B glibc: ${b.joinToString(",")}")
            }
        }
        println("columns whose order differs         : $columnsDiverging of 5")

        // CONTROL — AIMED. What this has to establish is that **the counter above reports a
        // non-zero displacement when the underlying orders differ.** That is a property of
        // the counter, and the planted set therefore has to be one whose divergence is
        // already established rather than one whose divergence is being guessed at.
        //
        // THE FIRST VERSION OF THIS CONTROL WAS A GUESS AND IT FAILED. It planted
        // `learner-1` beside `learner-000001`, on the claim that a misaligned hyphen is what
        // makes a fixed-width identifier collation-sensitive. Both images ordered that set
        // identically: the hyphen is ignored at the primary level on the glibc side and the
        // digits decide either way. Commit `0819a47` is that state. **A hypothesis written
        // where a control belongs passes for a control until it is run**, and the thing it
        // was protecting -- "0 of 5" on the real columns -- was unbacked in the meantime.
        //
        // The mechanism this plants instead is the one the ALIVE control has already measured
        // on this very pair of images: case. `Apple` before `apple` byte-wise, `apple` before
        // `Apple` under a locale-aware collation. It is carried into the generator's own
        // identifier format so that the set is still of the shape being counted over.
        val planted = listOf("Item-000001", "item-000001", "Item-000002", "item-000002")
        val plantedA = orderedBy(Arm.MUSL, planted)
        val plantedB = orderedBy(Arm.GLIBC, planted)
        val plantedDisplaced = plantedA.indices.count { plantedA[it] != plantedB[it] }
        println("AIMED control, planted case flip    : A=${plantedA.joinToString(",")}")
        println("                                      B=${plantedB.joinToString(",")}")
        println("                                      displaced=$plantedDisplaced")
        assertTrue(
            plantedDisplaced > 0,
            "AIMED control failed: a value set that differs only by case, in this " +
                "repository's own identifier format, was counted as displaced=0. The counter " +
                "cannot distinguish 'these values do not diverge' from 'this routine does not " +
                "look', so the result above is not a measurement.",
        )
    }

    // -------------------------------------------------------------------------------------
    // 3.5  The two text orderings that exist in this repository's own tests
    // -------------------------------------------------------------------------------------

    /**
     * `BaselineMigrationTest` reads `order by table_name` and `order by i.indexname`, and
     * those are the only `order by` on text anywhere in the tree outside `R9`'s own probe.
     *
     * Both columns are of PostgreSQL's `name` type, whose collation is fixed rather than
     * inherited from the database. This asks the server rather than asserting it.
     */
    @Test
    fun `the catalog orderings BaselineMigrationTest depends on`() {
        println("\n=== R25 §3.5  what BaselineMigrationTest orders ===")
        val tablesBy = mutableMapOf<Arm, List<String>>()
        val indexesBy = mutableMapOf<Arm, List<String>>()
        for (arm in Arm.entries) {
            val c = when (arm) { Arm.MUSL -> musl; Arm.GLIBC -> glibc }
            // The probe table of the other tests in this class lives in the same database,
            // and Flyway refuses to migrate a non-empty schema that has no history table.
            // Test order is not defined, so this cannot be arranged by ordering.
            connection(arm).use { conn ->
                conn.createStatement().use { st -> st.execute("drop table if exists r25_probe") }
            }
            Flyway.configure()
                .dataSource(c.jdbcUrl, c.username, c.password)
                .locations("classpath:db/migration")
                .load()
                .migrate()
            connection(arm).use { conn ->
                conn.createStatement().use { st ->
                    val tables = mutableListOf<String>()
                    st.executeQuery(
                        """
                        select table_name from information_schema.tables
                         where table_schema = 'public' and table_type = 'BASE TABLE'
                         order by table_name
                        """.trimIndent(),
                    ).use { rs -> while (rs.next()) tables.add(rs.getString(1)) }
                    tablesBy[arm] = tables
                    val indexes = mutableListOf<String>()
                    st.executeQuery(
                        "select i.indexname from pg_indexes i where i.schemaname = 'public' order by i.indexname",
                    ).use { rs -> while (rs.next()) indexes.add(rs.getString(1)) }
                    indexesBy[arm] = indexes
                }
            }
            println("${arm.label} | tables : ${tablesBy[arm]}")
            println("${arm.label} | indexes: ${indexesBy[arm]}")
        }
        println("table order identical               : ${tablesBy[Arm.MUSL] == tablesBy[Arm.GLIBC]}")
        println("index order identical               : ${indexesBy[Arm.MUSL] == indexesBy[Arm.GLIBC]}")

        // And the mechanism, asked rather than assumed: is `name` collated by the database's
        // collation, or by its own?
        connection(Arm.GLIBC).use { c ->
            c.createStatement().use { st ->
                st.executeQuery(
                    """
                    select a.attname,
                           coalesce((select collname from pg_collation where oid = a.attcollation), 'none')
                      from pg_attribute a
                      join pg_class cl on cl.oid = a.attrelid
                     where cl.relname = 'pg_indexes' and a.attname = 'indexname'
                    """.trimIndent(),
                ).use { rs ->
                    while (rs.next()) println("B glibc | pg_indexes.indexname collation: ${rs.getString(2)}")
                }
            }
        }
    }
}
