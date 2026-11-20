package uk.gegc.quizmaker.shared.testing;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Best-effort, bounded diagnostic work; never forces GC or inspects test inputs, commands or environment. */
final class TestResourceRecorder {
    static final String PREFIX = "[test-resources] ";
    static final int SAMPLE_SECONDS = 1;
    static final int EMIT_SECONDS = 5;
    private static final Set<String> PEAK_METRICS = Set.of("heap_used_bytes", "nonheap_used_bytes",
            "metaspace_used_bytes", "direct_used_bytes", "mapped_used_bytes", "threads_live", "rss_bytes");
    private final Supplier<Map<String, Long>> metrics;
    private final LongSupplier clock;
    private final Consumer<Map<String, Object>> output;
    private final Map<Long, Window> active = new LinkedHashMap<>();
    private final Map<String, Long> peaks = new LinkedHashMap<>();
    private final Map<String, Object> identity;
    private long sequence;
    private long tokens;
    private int samples;

    TestResourceRecorder(Supplier<Map<String, Long>> metrics, LongSupplier clock,
                         Consumer<Map<String, Object>> output, Map<String, Object> identity) {
        this.metrics = metrics;
        this.clock = clock;
        this.output = output;
        this.identity = Map.copyOf(identity);
    }

    static TestResourceRecorder create() {
        long pid = ProcessHandle.current().pid();
        String run = UUID.randomUUID().toString();
        AsyncOutput output = new AsyncOutput(pid, run, System.out);
        String lane = System.getProperty("quizmaker.tests.resources.lane", "unspecified");
        // Only known lane/fork values may enter evidence, never arbitrary property values.
        if (!Set.of("unit", "db-serial", "context", "context-exclusive").contains(lane)) lane = "unspecified";
        String fork = System.getProperty("quizmaker.tests.context-fork", "");
        if (!fork.matches("[1-4]")) fork = "";
        TestResourceRecorder recorder = new TestResourceRecorder(new JvmMetrics(), System::nanoTime, output,
                Map.of("pid", pid, "jvm_id", run, "lane", lane, "fork", fork));
        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "test-resource-sampler");
            thread.setDaemon(true);
            return thread;
        });
        recorder.event("jvm_start", Map.of("sample_interval_ms", SAMPLE_SECONDS * 1000,
                "emit_interval_ms", EMIT_SECONDS * 1000));
        sampler.scheduleWithFixedDelay(recorder::sample, SAMPLE_SECONDS, SAMPLE_SECONDS, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            sampler.shutdownNow();
            recorder.event("jvm_end", Map.of());
            output.close();
        }, "test-resource-shutdown"));
        return recorder;
    }

    synchronized long begin(Class<?> type) {
        long token = ++tokens;
        Map<String, Long> current = read();
        Window window = new Window(type.getName(), type.getEnclosingClass() == null ? ""
                : type.getEnclosingClass().getName(), clock.getAsLong(), current);
        active.put(token, window);
        observe(current);
        for (Window item : active.values()) item.maxActive = Math.max(item.maxActive, active.size());
        emit("class_start", current, Map.of("class_id", token, "class_name", window.name,
                "enclosing_class", window.enclosing));
        return token;
    }

    synchronized void end(long token) {
        Window window = active.get(token);
        if (window == null) return;
        Map<String, Long> current = read();
        observe(current);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("class_id", token);
        fields.put("class_name", window.name);
        fields.put("enclosing_class", window.enclosing);
        fields.put("wall_ns", clock.getAsLong() - window.started);
        fields.put("start_metrics", window.before);
        fields.put("sampled_peak", Map.copyOf(window.peaks));
        fields.put("max_active_classes", window.maxActive);
        Map<String, Long> deltas = new LinkedHashMap<>();
        for (String key : List.of("process_cpu_ns", "gc_count", "gc_ms", "heap_used_bytes",
                "nonheap_used_bytes", "metaspace_used_bytes", "rss_bytes", "classes_loaded_total", "classes_unloaded")) {
            long before = window.before.getOrDefault(key, -1L), after = current.getOrDefault(key, -1L);
            if (before >= 0 && after >= 0) deltas.put(key, after - before);
        }
        fields.put("delta", deltas);
        emit("class_end", current, fields);
        active.remove(token);
    }

    synchronized void marker(Class<?> type, String phase) {
        event(phase, Map.of("class_name", type.getName()));
    }

    synchronized void sample() {
        Map<String, Long> current = read();
        observe(current);
        if (++samples % (EMIT_SECONDS / SAMPLE_SECONDS) == 0) emit("jvm_sample", current, Map.of());
    }

    synchronized void event(String name, Map<String, Object> fields) {
        Map<String, Long> current = read();
        observe(current);
        emit(name, current, fields);
    }

    private Map<String, Long> read() {
        long started = clock.getAsLong();
        Map<String, Long> result = new LinkedHashMap<>();
        try {
            result.putAll(metrics.get());
        } catch (RuntimeException ignored) {
            result.put("metrics_unavailable", 1L);
        }
        result.put("collection_ns", clock.getAsLong() - started);
        return result;
    }

    private void observe(Map<String, Long> current) {
        updatePeaks(peaks, current);
        for (Window window : active.values()) updatePeaks(window.peaks, current);
    }

    private static void updatePeaks(Map<String, Long> destination, Map<String, Long> current) {
        for (String key : PEAK_METRICS) {
            Long value = current.get(key);
            if (value != null && value >= 0) destination.merge(key, value, Math::max);
        }
    }

    private void emit(String event, Map<String, Long> current, Map<String, Object> fields) {
        Map<String, Object> record = new LinkedHashMap<>(identity);
        record.putAll(fields);
        record.put("schema", 1);
        record.put("event", event);
        record.put("sequence", sequence++);
        record.put("utc", Instant.now().toString());
        record.put("monotonic_ns", clock.getAsLong());
        record.put("metrics", current);
        record.put("jvm_sampled_peak", Map.copyOf(peaks));
        record.put("active_count", active.size());
        record.put("active_classes", active.entrySet().stream().limit(16)
                .map(entry -> Map.of("class_id", entry.getKey(), "class_name", entry.getValue().name,
                        "sampled_peak", Map.copyOf(entry.getValue().peaks))).toList());
        try {
            output.accept(record);
        } catch (RuntimeException ignored) {
            // A failed diagnostic sink must never fail a class or stop future measurements.
        }
    }

    private static final class Window {
        private final String name;
        private final String enclosing;
        private final long started;
        private final Map<String, Long> before;
        private final Map<String, Long> peaks = new LinkedHashMap<>();
        private int maxActive;

        private Window(String name, String enclosing, long started, Map<String, Long> before) {
            this.name = name;
            this.enclosing = enclosing;
            this.started = started;
            this.before = before;
        }
    }

    static final class JvmMetrics implements Supplier<Map<String, Long>> {
        @Override
        public Map<String, Long> get() {
            Map<String, Long> values = new LinkedHashMap<>();
            var memory = ManagementFactory.getMemoryMXBean();
            usage(values, "heap", memory.getHeapMemoryUsage());
            usage(values, "nonheap", memory.getNonHeapMemoryUsage());
            for (var pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getName().equals("Metaspace")) usage(values, "metaspace", pool.getUsage());
            }
            for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
                if (Set.of("direct", "mapped").contains(pool.getName())) {
                    values.put(pool.getName() + "_used_bytes", pool.getMemoryUsed());
                    values.put(pool.getName() + "_capacity_bytes", pool.getTotalCapacity());
                }
            }
            var threads = ManagementFactory.getThreadMXBean();
            values.put("threads_live", (long) threads.getThreadCount());
            values.put("threads_daemon", (long) threads.getDaemonThreadCount());
            values.put("threads_peak", (long) threads.getPeakThreadCount());
            var classes = ManagementFactory.getClassLoadingMXBean();
            values.put("classes_loaded", (long) classes.getLoadedClassCount());
            values.put("classes_loaded_total", classes.getTotalLoadedClassCount());
            values.put("classes_unloaded", classes.getUnloadedClassCount());
            long count = 0, millis = 0;
            for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (gc.getCollectionCount() >= 0) count += gc.getCollectionCount();
                if (gc.getCollectionTime() >= 0) millis += gc.getCollectionTime();
            }
            values.put("gc_count", count);
            values.put("gc_ms", millis);
            var os = ManagementFactory.getOperatingSystemMXBean();
            values.put("process_cpu_ns", os instanceof com.sun.management.OperatingSystemMXBean extended
                    ? extended.getProcessCpuTime() : -1L);
            values.put("uptime_ms", ManagementFactory.getRuntimeMXBean().getUptime());
            values.putAll(linuxMemory(Path.of("/proc/self/status")));
            return values;
        }

        private static void usage(Map<String, Long> values, String name, MemoryUsage usage) {
            if (usage == null) return;
            values.put(name + "_used_bytes", usage.getUsed());
            values.put(name + "_committed_bytes", usage.getCommitted());
            values.put(name + "_max_bytes", usage.getMax());
        }

        static Map<String, Long> linuxMemory(Path status) {
            Map<String, Long> values = new LinkedHashMap<>(Map.of("rss_bytes", -1L, "rss_hwm_bytes", -1L));
            try (BufferedReader reader = Files.newBufferedReader(status, StandardCharsets.US_ASCII)) {
                // Bounded input, read only two numeric fields; never retain arbitrary status text.
                for (int i = 0; i < 128; i++) {
                    String line = reader.readLine();
                    if (line == null) break;
                    if (line.startsWith("VmRSS:") || line.startsWith("VmHWM:")) {
                        String[] fields = line.trim().split("\\s+");
                        if (fields.length == 3 && fields[2].equals("kB")) {
                            long kib = Long.parseLong(fields[1]);
                            if (kib >= 0) values.put(fields[0].equals("VmRSS:") ? "rss_bytes" : "rss_hwm_bytes",
                                    Math.multiplyExact(kib, 1024));
                        }
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // For example macOS has no /proc. Unknown remains -1, never zero.
            }
            return values;
        }
    }

    static final class AsyncOutput implements Consumer<Map<String, Object>>, AutoCloseable {
        private final ArrayBlockingQueue<Map<String, Object>> queue = new ArrayBlockingQueue<>(256);
        private final AtomicLong dropped = new AtomicLong();
        private final Thread writer;
        private volatile boolean closed;

        AsyncOutput(long pid, String run, PrintStream live) {
            this(fileSink(pid, run, live));
        }

        private static Sink fileSink(long pid, String run, PrintStream live) {
            ObjectMapper mapper = new ObjectMapper();
            return record -> {
                String json = mapper.writeValueAsString(record);
                live.println(PREFIX + json);
                live.flush();
                Path directory = Path.of(System.getProperty("quizmaker.tests.resources.directory",
                        "target/test-resource-evidence"));
                Files.createDirectories(directory);
                Files.writeString(directory.resolve("jvm-" + pid + "-" + run + ".jsonl"), json + "\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            };
        }

        @FunctionalInterface
        interface Sink {
            void write(Map<String, Object> record) throws Exception;
        }

        AsyncOutput(Sink sink) {
            writer = new Thread(() -> {
                long errors = 0;
                while (!closed || !queue.isEmpty()) {
                    try {
                        Map<String, Object> record = queue.poll(100, TimeUnit.MILLISECONDS);
                        if (record == null) continue;
                        record.put("dropped_events", dropped.get());
                        record.put("sink_errors", errors);
                        sink.write(record);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Exception ignored) {
                        errors++; // No paths or exception messages in diagnostics.
                    }
                }
            }, "test-resource-writer");
            writer.setDaemon(true);
            writer.start();
        }

        @Override
        public void accept(Map<String, Object> record) {
            if (closed || !queue.offer(new LinkedHashMap<>(record))) dropped.incrementAndGet();
        }

        @Override
        public void close() {
            closed = true;
            try {
                writer.join(500); // A hung console/filesystem must not delay test JVM shutdown indefinitely.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
