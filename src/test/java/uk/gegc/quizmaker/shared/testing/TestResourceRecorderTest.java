package uk.gegc.quizmaker.shared.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class TestResourceRecorderTest {
    @TempDir Path temporary;

    @Test
    void observesTransientPeakAndCumulativeCpuGcWithoutForcingCollection() {
        AtomicLong clock = new AtomicLong();
        AtomicReference<Map<String, Long>> metrics = new AtomicReference<>(metrics(100, 50, 0, 0));
        List<Map<String, Object>> records = new ArrayList<>();
        TestResourceRecorder recorder = recorder(metrics, clock, records);
        long token = recorder.begin(TestResourceRecorderTest.class);
        metrics.set(metrics(900, 800, 200, 2));
        clock.set(1_000_000_000L);
        recorder.sample();
        metrics.set(metrics(150, 60, 350, 3));
        clock.set(2_000_000_000L);
        recorder.end(token);

        Map<String, Object> end = records.get(records.size() - 1);
        assertThat(end).containsEntry("event", "class_end").containsEntry("wall_ns", 2_000_000_000L)
                .containsEntry("max_active_classes", 1);
        assertThat(map(end, "sampled_peak")).containsEntry("heap_used_bytes", 800L).containsEntry("rss_bytes", 900L);
        assertThat(map(end, "delta")).containsEntry("process_cpu_ns", 350L).containsEntry("gc_count", 3L)
                .containsEntry("heap_used_bytes", 10L).containsEntry("rss_bytes", 50L);
        assertThat(map(end, "metrics")).containsEntry("heap_used_bytes", 60L);
    }

    @Test
    void marksNestedAndParallelWindowsAndKeepsPeaksOfUnfinishedClassInPeriodicStream() {
        AtomicLong clock = new AtomicLong();
        AtomicReference<Map<String, Long>> metrics = new AtomicReference<>(metrics(100, 50, 0, 0));
        List<Map<String, Object>> records = new ArrayList<>();
        TestResourceRecorder recorder = recorder(metrics, clock, records);
        long outer = recorder.begin(TestResourceRecorderTest.class);
        long nested = recorder.begin(NestedFixture.class);
        metrics.set(metrics(700, 600, 20, 1));
        for (int i = 0; i < 5; i++) recorder.sample();
        Map<String, Object> sample = records.get(records.size() - 1);
        assertThat(sample).containsEntry("event", "jvm_sample").containsEntry("active_count", 2);
        assertThat(sample.get("active_classes").toString()).contains("NestedFixture", "rss_bytes=700");
        recorder.end(nested);
        Map<String, Object> end = records.get(records.size() - 1);
        assertThat(end).containsEntry("enclosing_class", getClass().getName()).containsEntry("max_active_classes", 2);
        recorder.marker(getClass(), "context_close_start");
        recorder.marker(getClass(), "context_close_end");
        recorder.end(outer);
        assertThat(records.get(records.size() - 1)).containsEntry("max_active_classes", 2);
        assertThat(records).extracting(record -> record.get("sequence"))
                .containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L);
        recorder.end(nested); // A repeated or missing end must not invent another result.
        assertThat(records).hasSize(7);
    }

    @Test
    void snapshotAndSinkFailuresRemainPrivateAndDoNotBreakLaterClasses() throws Exception {
        List<Map<String, Object>> records = new ArrayList<>();
        AtomicLong calls = new AtomicLong();
        TestResourceRecorder recorder = new TestResourceRecorder(() -> {
            throw new IllegalStateException("private-provider-key");
        }, System::nanoTime, record -> {
            if (calls.getAndIncrement() == 0) throw new IllegalStateException("private-file-path");
            records.add(record);
        }, Map.of("pid", 1L));
        long token = recorder.begin(getClass());
        assertThatCode(() -> recorder.end(token)).doesNotThrowAnyException();
        assertThat(map(records.get(0), "metrics")).containsEntry("metrics_unavailable", 1L);
        assertThat(map(records.get(0), "delta")).isEmpty();
        assertThat(new ObjectMapper().writeValueAsString(records)).doesNotContain("private-");
    }

    @Test
    void linuxRssReaderHandlesUnavailableAndMalformedDataWithoutRecordingOtherFields() throws Exception {
        Path status = temporary.resolve("status");
        assertThat(TestResourceRecorder.JvmMetrics.linuxMemory(status))
                .containsEntry("rss_bytes", -1L).containsEntry("rss_hwm_bytes", -1L);
        Files.writeString(status, "Name:\tprivate-process-name\nVmRSS:\t123 kB\nVmHWM:\t456 kB\n");
        assertThat(TestResourceRecorder.JvmMetrics.linuxMemory(status))
                .containsExactlyInAnyOrderEntriesOf(Map.of("rss_bytes", 123L * 1024, "rss_hwm_bytes", 456L * 1024));
        Files.writeString(status, "VmRSS: private-argument kB\n");
        assertThat(TestResourceRecorder.JvmMetrics.linuxMemory(status)).containsEntry("rss_bytes", -1L);
    }

    @Test
    void realJdkSnapshotContainsNumericMemoryCpuGcThreadCountersOnly() {
        Map<String, Long> snapshot = new TestResourceRecorder.JvmMetrics().get();
        assertThat(snapshot).containsKeys("heap_used_bytes", "heap_committed_bytes", "heap_max_bytes",
                "nonheap_used_bytes", "metaspace_used_bytes", "direct_used_bytes", "threads_live",
                "classes_loaded", "gc_count", "gc_ms", "process_cpu_ns", "rss_bytes", "uptime_ms");
        assertThat(snapshot.get("heap_used_bytes")).isPositive();
        assertThat(snapshot.get("heap_max_bytes")).isGreaterThanOrEqualTo(snapshot.get("heap_used_bytes"));
        assertThat(snapshot.get("threads_live")).isPositive();
    }

    @Test
    void asyncWriterSurvivesIoFailureAndReportsItOnNextRecord() {
        List<Map<String, Object>> delivered = Collections.synchronizedList(new ArrayList<>());
        AtomicLong calls = new AtomicLong();
        TestResourceRecorder.AsyncOutput output = new TestResourceRecorder.AsyncOutput(record -> {
            if (calls.getAndIncrement() == 0) throw new java.io.IOException("private-path");
            delivered.add(record);
        });
        output.accept(Map.of("event", "first"));
        output.accept(Map.of("event", "second"));
        output.close();
        assertThat(delivered).singleElement().satisfies(record ->
                assertThat(record).containsEntry("event", "second").containsEntry("sink_errors", 1L));
    }

    @Test
    void blockedSinkCannotBlockTestThreadAndBoundedQueueReportsDroppedEvents() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        List<Map<String, Object>> delivered = Collections.synchronizedList(new ArrayList<>());
        TestResourceRecorder.AsyncOutput output = new TestResourceRecorder.AsyncOutput(record -> {
            entered.countDown();
            release.await();
            delivered.add(record);
        });
        try {
            output.accept(Map.of("event", "blocked"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 300; i++) output.accept(Map.of("event", "queued"));
            // close() must return while the sink is still blocked; this is a diagnostic contract.
            long started = System.nanoTime();
            output.close();
            assertThat(System.nanoTime() - started).isLessThan(TimeUnit.SECONDS.toNanos(3));
            assertThat(delivered).isEmpty();
        } finally {
            release.countDown();
            output.close();
        }
        assertThat(delivered).hasSize(257);
        assertThat(delivered).anySatisfy(record -> assertThat((Long) record.get("dropped_events")).isEqualTo(44L));
    }

    private TestResourceRecorder recorder(AtomicReference<Map<String, Long>> metrics, AtomicLong clock,
                                           List<Map<String, Object>> records) {
        return new TestResourceRecorder(metrics::get, clock::get, records::add, Map.of("pid", 10L));
    }

    private Map<String, Long> metrics(long rss, long heap, long cpu, long gc) {
        return Map.of("rss_bytes", rss, "heap_used_bytes", heap, "process_cpu_ns", cpu, "gc_count", gc);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> map(Map<String, Object> record, String field) {
        return (Map<String, Long>) record.get(field);
    }

    private static class NestedFixture {}
}
