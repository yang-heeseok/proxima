package net.gseek.proxima.seed

import java.nio.file.Path

/**
 * The generator's command line.
 *
 * The steps are separate commands rather than one `seed` command, because the gap between
 * them is itself a measurement. A bulk `COPY` leaves the planner's statistics stale, and
 * `T4` measures a query in exactly that state before running `ANALYZE` — so `load` must be
 * able to finish without quietly fixing the condition the report is about.
 *
 * Usage:
 *
 * ```
 *   generate [--out DIR] [--scale full|tiny]
 *   load     --url JDBC --user U --password P [--out DIR] [--truncate]
 *   analyze  --url JDBC --user U --password P
 *   counts   --url JDBC --user U --password P
 * ```
 *
 * Credentials come from arguments or the environment, never from a file in this
 * repository — see `PUB-1`.
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println(USAGE)
        return
    }

    val command = args[0]
    val opts = parseOptions(args.drop(1))
    val outDir = Path.of(opts["out"] ?: "seed-out")

    when (command) {
        "generate" -> {
            val scale = when (opts["scale"] ?: "full") {
                "tiny" -> Scale.TINY
                "full" -> Scale.FULL
                else -> error("unknown scale: ${opts["scale"]}")
            }
            println("generate  seed=$SEED_VALUE  scale=${opts["scale"] ?: "full"}  out=$outDir")
            val started = System.nanoTime()
            val files = Generator(scale).generateAll(outDir)
            val ms = (System.nanoTime() - started) / 1_000_000
            files.forEach { (table, path) ->
                println("  %-13s %,12d bytes".format(table, java.nio.file.Files.size(path)))
            }
            println("  generated in %,d ms".format(ms))
        }

        "load" -> {
            val loader = loaderFrom(opts)
            if (opts.containsKey("truncate")) loader.truncateAll()
            println("load  from=$outDir")
            val started = System.nanoTime()
            loader.loadAll(outDir)
            loader.realignSequences()
            println("  loaded in %,d ms".format((System.nanoTime() - started) / 1_000_000))
        }

        "analyze" -> loaderFrom(opts).analyze()

        "counts" -> loaderFrom(opts).rowCounts().forEach { (t, c) ->
            println("  %-13s %,12d".format(t, c))
        }

        else -> {
            println("unknown command: $command")
            println(USAGE)
        }
    }
}

private fun loaderFrom(opts: Map<String, String>) = Loader(
    jdbcUrl = opts["url"] ?: System.getenv("PROXIMA_DB_URL") ?: error("--url or PROXIMA_DB_URL"),
    user = opts["user"] ?: System.getenv("PROXIMA_DB_USER") ?: error("--user or PROXIMA_DB_USER"),
    password = opts["password"] ?: System.getenv("PROXIMA_DB_PASSWORD")
        ?: error("--password or PROXIMA_DB_PASSWORD"),
)

/** `--key value` and bare `--flag`. Deliberately not a CLI framework. */
private fun parseOptions(args: List<String>): Map<String, String> {
    val opts = LinkedHashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        require(a.startsWith("--")) { "expected an option, got: $a" }
        val key = a.removePrefix("--")
        val next = args.getOrNull(i + 1)
        if (next == null || next.startsWith("--")) {
            opts[key] = "true"
            i += 1
        } else {
            opts[key] = next
            i += 2
        }
    }
    return opts
}

private val USAGE = """
    proxima seed generator -- the dataset is code, never a committed file (PUB-7)

      generate [--out DIR] [--scale full|tiny]
      load     --url JDBC --user U --password P [--out DIR] [--truncate]
      analyze  --url JDBC --user U --password P
      counts   --url JDBC --user U --password P

    Seed value $SEED_VALUE. Same value in, same bytes out.
""".trimIndent()
