#!/usr/bin/env python3
"""Opt-in real-command write-cost measurement on an owned disposable MySQL server."""
import argparse
import json
from pathlib import Path
import context_benchmark as context


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--mysql-image', default='mysql:8.4')
    parser.add_argument('--compare-historical', action='store_true',
                        help='Also install never-deployed V113/V114 in a disposable probe schema')
    parser.add_argument('--read-cost', action='store_true',
                        help='Measure form/single-response rows, bytes and transaction overhead for two families')
    args = parser.parse_args()
    if args.read_cost and args.compare_historical:
        parser.error('--read-cost measures the current baseline, not historical projection writes')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    baseline = '703bfbe3fab5f50eb1ce1ce04f5beabf484b85f6'
    history = output / 'historical'
    if args.compare_historical:
        history.mkdir()
        for name in ('V113__index_accepted_response_identities.sql',
                     'V114__project_current_accepted_response_references.sql'):
            sql = context.command(['git', 'show', f'{baseline}:src/main/resources/db/migration/{name}'],
                                  cwd=context.ROOT)
            (history / name).write_text(sql + '\n')
    before = context.fingerprint()
    with context.databases(1, output / 'db', args.mysql_image) as database:
        context.write_json(output / 'baseline.json', {'source_fingerprint': before,
                           'historical_commit': baseline if args.compare_historical else None,
                           'servers': database['servers']})
        for mode in (['reads'] if args.read_cost else ['authoritative', 'historical'] if args.compare_historical else ['authoritative']):
            arguments = (['clean'] if mode in ('authoritative', 'reads') else []) + [
                'test', '-Dtest=ResponseWriteCostProbe#' + ('measureReadCost' if args.read_cost else 'measureWriteCost')]
            if mode == 'historical':
                arguments.append('-Dresponse.cost.historicalMigrations=' + str(history))
            log = output / (mode + '.log')
            context.maven(arguments, log, context.safe_environment())
            marker = 'RESPONSE_READ_COST ' if args.read_cost else 'RESPONSE_WRITE_COST '
            samples = [json.loads(line.split(marker, 1)[1])
                       for line in log.read_text().splitlines() if line.startswith(marker)]
            if args.read_cost:
                expected = {(kind, family, size, sample) for kind in ('single', 'form')
                            for family in ('TRUE_FALSE', 'MCQ_SINGLE') for size in (5, 50, 500) for sample in (1, 2, 3)}
                actual = {(r['mode'], r['family'], r['items'], r['sample']) for r in samples}
            else:
                expected = {(kind, size, sample) for kind in ('single', 'batch')
                            for size in (100, 250, 500) for sample in (1, 2, 3)}
                actual = {(r['mode'], r['items'], r['sample']) for r in samples}
            if len(samples) != len(expected) or actual != expected:
                raise RuntimeError('Incomplete measurement samples')
            context.write_json(output / (mode + '.json'), samples)
            print(f'{mode}: all {len(expected)} samples complete', flush=True)
    if before != context.fingerprint():
        raise RuntimeError('Sources changed during measurement; discard comparison')


if __name__ == '__main__':
    main()
