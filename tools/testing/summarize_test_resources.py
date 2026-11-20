"""Summarize process-wide class windows; overlapping windows are not additive attribution."""
import argparse
import json
from pathlib import Path


def summarize(directory):
    jvms, classes = {}, {}
    invalid = 0
    for path in sorted(directory.glob('jvm-*.jsonl')):
        with path.open(encoding='utf-8') as stream:
            for line in stream:
                try:
                    record = json.loads(line)
                    if record.get('schema') != 1:
                        raise ValueError('schema')
                    key = record['jvm_id']
                    jvm = jvms.setdefault(key, dict(jvm_id=key, pid=record['pid'], lane=record['lane'],
                                                   fork=record['fork'], ended=False, sampled_peak={},
                                                   dropped_events=0, sink_errors=0))
                    jvm['ended'] |= record['event'] == 'jvm_end'
                    for metric, value in record.get('jvm_sampled_peak', {}).items():
                        jvm['sampled_peak'][metric] = max(jvm['sampled_peak'].get(metric, -1), value)
                    for metric in ('dropped_events', 'sink_errors'):
                        jvm[metric] = max(jvm[metric], record.get(metric, 0))
                    if record['event'] == 'class_start':
                        classes[(key, record['class_id'])] = dict(
                            jvm_id=key, pid=record['pid'], lane=record['lane'], fork=record['fork'],
                            class_name=record['class_name'], enclosing_class=record['enclosing_class'],
                            started_ns=record['monotonic_ns'], wall_ns=0, completed=False,
                            sampled_peak={}, max_active_classes=1, delta={})
                    for active in record.get('active_classes', []):
                        window = classes.get((key, active['class_id']))
                        if window is not None:
                            window['wall_ns'] = max(window['wall_ns'], record['monotonic_ns'] - window['started_ns'])
                            window['max_active_classes'] = max(window['max_active_classes'], record['active_count'])
                            for metric, value in active.get('sampled_peak', {}).items():
                                window['sampled_peak'][metric] = max(window['sampled_peak'].get(metric, -1), value)
                    if record['event'] == 'class_end':
                        window = classes.get((key, record['class_id']))
                        if window is not None:
                            for field in ('wall_ns', 'sampled_peak', 'max_active_classes', 'delta'):
                                window[field] = record[field]
                            window['completed'] = True
                except (ValueError, KeyError, TypeError):
                    invalid += 1  # Partial final JSON is possible after SIGKILL. Keep preceding evidence.
    return dict(schema=1, invalid_records=invalid, jvms=list(jvms.values()), classes=list(classes.values()))


def render(result, limit=10):
    rows = result['classes']
    lines = ['Process-wide class windows: sampled peaks, no forced GC; overlapping/nested rows are not additive.',
             f"JVMs: {len(result['jvms'])}; class windows: {len(rows)}; "
             f"incomplete: {sum(not row['completed'] for row in rows)}; invalid records: {result['invalid_records']}"]
    for title, key in (
            ('Wall time', lambda row: row['wall_ns']),
            ('Process CPU during window', lambda row: row['delta'].get('process_cpu_ns', -1)),
            ('Sampled RSS', lambda row: row['sampled_peak'].get('rss_bytes', -1)),
            ('Sampled heap', lambda row: row['sampled_peak'].get('heap_used_bytes', -1)),
            ('Heap growth at class boundary (not retained/live heap)', lambda row: row['delta'].get('heap_used_bytes', -1))):
        lines.append('\n' + title)
        for row in sorted(rows, key=key, reverse=True)[:limit]:
            rss = row['sampled_peak'].get('rss_bytes', -1)
            rss_text = f'{rss / 1048576:.1f}MiB' if rss >= 0 else 'unavailable'
            cpu = row['delta'].get('process_cpu_ns', -1)
            cpu_text = f'{cpu / 1e9:.2f}s' if cpu >= 0 else 'unavailable'
            heap = row['sampled_peak'].get('heap_used_bytes', -1)
            heap_text = f'{heap / 1048576:.1f}MiB' if heap >= 0 else 'unavailable'
            growth = row['delta'].get('heap_used_bytes')
            growth_text = f'{growth / 1048576:+.1f}MiB' if growth is not None else 'unavailable'
            lines.append(f"{row['wall_ns'] / 1e9:.2f}s cpu={cpu_text} rss={rss_text} "
                         f"heap_peak={heap_text} heap_delta={growth_text} "
                         f"overlap={row['max_active_classes']} complete={row['completed']} "
                         f"pid={row['pid']} {row['lane']}/{row['fork']} {row['class_name']}")
    return '\n'.join(lines) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = summarize(args.directory)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(render(result), end='')


if __name__ == '__main__':
    main()
