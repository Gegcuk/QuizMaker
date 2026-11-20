import json
from pathlib import Path
import tempfile
import unittest

from summarize_test_resources import render, summarize


class ResourceSummaryTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)

    def record(self, event, **values):
        return dict(schema=1, jvm_id='jvm-a', pid=21, lane='context', fork='2', event=event,
                    monotonic_ns=100, active_count=0, active_classes=[], **values)

    def write(self, *records):
        (self.root / 'jvm-21-a.jsonl').write_text(''.join(json.dumps(record) + '\n' for record in records))

    def test_completed_and_killed_windows_keep_process_identity_overlap_and_peak(self):
        start = self.record('class_start', class_id=1, class_name='example.A', enclosing_class='')
        sample = self.record('jvm_sample', jvm_sampled_peak={'rss_bytes': 900})
        sample.update(monotonic_ns=1000, active_count=2,
                      active_classes=[dict(class_id=1, class_name='example.A', sampled_peak={'rss_bytes': 800})])
        self.write(start, sample)
        partial = summarize(self.root)
        self.assertFalse(partial['jvms'][0]['ended'])
        row = partial['classes'][0]
        self.assertEqual((21, 'context', '2'), (row['pid'], row['lane'], row['fork']))
        self.assertFalse(row['completed'])
        self.assertEqual(900, row['wall_ns'])
        self.assertEqual(800, row['sampled_peak']['rss_bytes'])  # Never substitute a whole-JVM lifetime peak.
        self.assertEqual(2, row['max_active_classes'])
        end = self.record('class_end', class_id=1, wall_ns=1100, sampled_peak={'rss_bytes': 850},
                          max_active_classes=2, delta={'heap_used_bytes': -20, 'process_cpu_ns': 123})
        self.write(start, sample, end, self.record('jvm_end', dropped_events=4, sink_errors=1))
        complete = summarize(self.root)
        self.assertTrue(complete['classes'][0]['completed'])
        self.assertEqual(-20, complete['classes'][0]['delta']['heap_used_bytes'])
        self.assertEqual(4, complete['jvms'][0]['dropped_events'])
        self.assertTrue(complete['jvms'][0]['ended'])
        self.assertIn('overlap=2 complete=True pid=21 context/2 example.A', render(complete))

    def test_truncated_final_record_does_not_discard_preceding_evidence(self):
        self.write(self.record('jvm_start'))
        with (self.root / 'jvm-21-a.jsonl').open('a') as stream:
            stream.write('{"private-truncated-value')
        result = summarize(self.root)
        self.assertEqual(1, len(result['jvms']))
        self.assertEqual(1, result['invalid_records'])
        self.assertNotIn('private-', json.dumps(result))

    def test_empty_directory_is_not_a_successful_or_failed_test_claim(self):
        result = summarize(self.root)
        self.assertEqual([], result['classes'])
        self.assertEqual([], result['jvms'])
        self.assertIn('JVMs: 0', render(result))


if __name__ == '__main__':
    unittest.main()
