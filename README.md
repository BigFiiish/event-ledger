# Event Ledger

Java 21 event-processing laboratory: a deterministic order book, a readable reference implementation, a bounded array implementation, and reproducible latency experiments. **Synthetic single-instrument data; no broker, exchange connectivity, market-making strategy, or trading-performance claim.**

## Recorded evidence — October 4, 2026

17 JUnit test cases pass. Three independent JVM forks, four measured rounds each, on an Intel Core i9-13900HK / Windows / Microsoft OpenJDK 21.0.12, G1, 512 MB fixed heap. Raw CSVs and environment/source hashes are retained under `reports/raw/20261004-180530`; open `reports/index.html` for the complete report.

Service median of per-round P99: reference 1.75 µs, array 0.40 µs. Timed-loop throughput medians: 1.28M and 4.18M events/sec respectively. The array's worst observed service sample was 5.17 ms, worse than the reference's 3.31 ms; the median improvement is not a worst-case guarantee. At 500k scheduled events/sec, median round scheduled-to-done P99 was 487.75 µs reference / 467.90 µs array, with worst samples 37.31 ms / 22.85 ms. Engine improvements did not translate into a comparable end-to-end speedup; scheduling and queuing dominate that experiment.

Use these as one machine's bounded experiment, not latency promises. The first suitable resume claim is the engineering method and correctness evidence; numerical claims must retain the workload and timing boundary.

## Run

```powershell
mvn test
./scripts/benchmark.ps1
# Use the printed result directory:
python scripts/report.py reports/raw/YYYYMMDD-HHMMSS
java -cp target/classes io.github.bigfiiish.eventledger.Replay generate reports/example.csv
java -cp target/classes io.github.bigfiiish.eventledger.Replay replay reports/example.csv
```

On this machine Maven's default cache path may require `mvn "-Dmaven.repo.local=C:/Users/yanxi/.m2/repository" test`. Java runtime code has no external dependencies; JUnit is test-only. Benchmarks are deliberately separate from CI correctness checks. Python report generation uses only its standard library.

## What is implemented

- Normalized order-level ADD, REDUCE and DELETE events. Quantity reduction may represent a partial cancel or execution; it does not distinguish these or infer trades. Modification is represented by explicit delete/add events, not an atomic replace. No price-time matching engine or execution simulation.
- Strict consecutive sequence checking: duplicates, gaps and out-of-order input are rejected. The caller must repair the feed/recover before proceeding. Invalid updates leave state and sequence unchanged; there is no silent skip policy.
- Integer price ticks, integer order units and long aggregate level quantities. IDs can be reused only after removal. BID and ASK levels are independent; a crossed feed is representable, not automatically matched.
- HashMap/TreeMap baseline versus dense primitive arrays with BitSet best-price discovery. Both versions enforce the same configured bounds. Array design spends memory on empty IDs and tick levels and is unsuitable for arbitrary sparse exchange IDs without an explicit mapping layer.
- Full offline journal replay with SHA-256 chain validation. Corrupt records fail. Files are created exclusively rather than overwritten. A cleanly removed trailing record cannot be detected without an externally stored expected sequence/hash. This is an offline replay artifact, **not** a crash-consistent write-ahead log, fsync durability guarantee, or cryptographic notarization. Failed replay state must be discarded.
- Single-writer book ownership; bounded ArrayBlockingQueue producer/consumer experiment, timeout handling and backpressure counts. No custom lock-free claims.
- Randomized differential tests plus explicit semantic cases, invalid-event atomicity, level aggregation, sequence errors, journal corruption and concurrent delivery checks.

## Measurement contract

Each fresh JVM warms both implementations for five service replays and one queued replay before four reported rounds. Implementation order alternates per round. Run at least three forks; keep every result, including regressions. Fixed seed and bounds are recorded with source hashes, JVM flags, OS and CPU. A book is reset outside each timing interval; events are pre-generated immutable records. Parsing, disk I/O and generation are excluded from service timings.

**Service:** `nanoTime` immediately around apply plus both best-price reads and a volatile result sink. Report P50/P95/P99/max and timed-loop throughput. Clock calls, per-event sample writes, and the sink perturb this microbenchmark. Nanosecond units do not establish nanosecond clock precision. Allocation is current-thread allocated bytes, not retained heap; report generation, prebuilt events, book construction, producer allocations and other threads are excluded.

**Fixed scheduled arrivals:** one producer, one book-owning consumer, queue capacity 1024; offered rates 100k and 500k events/sec. Arrival deadlines advance on the original schedule even under backlog. `scheduled_to_done` includes producer lag, enqueue blocking, queue wait and service; `enqueue_to_start` starts before enqueue and therefore includes enqueue blocking. `producer_lag` exposes how far the producer missed its intended deadline. Do not call this network wire-to-wire latency. Actual throughput can fall below the offered rate; no dropped samples are hidden. Wall timing includes consumer completion/join overhead. GC counts are process-wide, not attributable solely to the book.

Percentiles use nearest rank over individual samples. Report medians and ranges of **per-round** P99 values, never present an average of percentiles as a pooled percentile. Windows desktop scheduling, frequency scaling, thermal effects and other applications are uncontrolled. No affinity, isolated cores, NIC timestamps, packet processing, JMH confidence analysis, hardware counters, or exchange capture exists here. Repeat on a controlled Linux host before making stronger performance claims.

Oracle's [System.nanoTime contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/System.html#nanoTime()) describes elapsed-time use and separates timer precision from resolution.

## Interview evidence

Explain the before/after data structures, their bounds and memory tradeoff; correctness before speed; why tail latency differs from throughput; why queue saturation matters; and what was excluded from measurement. A truthful claim is “built and measured a Java event-processing prototype with differential correctness tests and reproducible latency experiments.” Only attach performance numbers with the exact workload, hardware, measurement boundary and observed variation.

Next extensions: licensed exchange-event normalization with provenance, checkpoint plus journal suffix recovery, multi-symbol partitioning, Linux profiling/JFR analysis, and JMH service benchmarks. None are claimed implemented.
