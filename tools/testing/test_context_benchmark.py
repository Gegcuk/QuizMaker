"""Failure/selection/resource contracts for the real benchmark launcher."""
from collections import Counter
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import context_benchmark as bench


class RunnerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def report(self, name='example.A', cases=('works',), **counts):
        values = dict(tests=len(cases), failures=0, errors=0, skipped=0)
        values.update(counts)
        xml = '<testsuite name="' + name + '" ' + ' '.join(f'{k}="{v}"' for k, v in values.items()) + '>'
        xml += ''.join(f'<testcase classname="{name}" name="{case}"/>' for case in cases) + '</testsuite>'
        (self.root / ('TEST-' + name + '.xml')).write_text(xml)

    def test_reports_preserve_nested_and_parameterized_identifiers(self):
        self.report('example.Outer$Nested', ('scenario[1]', 'scenario[2]'))
        result = bench.reports(self.root)
        self.assertEqual(['example.Outer'], result['suites'])
        self.assertEqual([['example.Outer$Nested', 'scenario[1]'], ['example.Outer$Nested', 'scenario[2]']], result['cases'])

    def test_missing_failed_skipped_and_duplicate_results_never_succeed(self):
        with self.assertRaises(ValueError):
            bench.reports(self.root)
        for changes in ({'failures': 1}, {'errors': 1}, {'skipped': 1}, {'tests': 0}):
            self.report(**changes)
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                bench.reports(self.root)
        self.report(cases=('same', 'same'))
        self.assertEqual(2, len(bench.reports(self.root)['cases']))
        (self.root / 'another-execution').mkdir()
        (self.root / 'another-execution/TEST-duplicate.xml').write_text((self.root / 'TEST-example.A.xml').read_text())
        with self.assertRaises(ValueError):
            bench.reports(self.root)

    def test_exact_comparison_rejects_same_count_different_tests_or_bytecode(self):
        original = dict(source='source', bytecode='bytecode', cases=[['A', 'one']], covered_lines={'A:1': [1, 1]}, test_seconds=2)
        for change in ({'cases': [['A', 'two']]}, {'bytecode': 'changed'}, {'source': 'changed'}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                bench.compare_results(original, dict(original, **change))
        result = bench.compare_results(original, dict(original, covered_lines={}, test_seconds=1))
        self.assertEqual(['A:1'], result['missing_covered_lines'])
        self.assertEqual(2, result['speedup'])

    def test_incompatible_coverage_is_not_a_successful_gate(self):
        log = self.root / 'maven.log'
        for message in ('[WARNING] Execution data for class example/A does not match.',
                        '[WARNING] Classes in bundle QuizMaker do not match with execution data.'):
            log.write_text(message)
            with self.assertRaisesRegex(ValueError, 'bytecode mismatch'):
                bench.check_coverage_log(log)
        log.write_text('Validation test: password does not match')
        bench.check_coverage_log(log)

    def state(self, identifier='a' * 32):
        state = self.root / 'resources.json'
        name = 'qm-context-' + identifier + '-1'
        bench.write_json(state, {'run_id': identifier, 'containers': [name]})
        return state, name

    def test_cleanup_verifies_owner_label_and_removes_owned_volumes(self):
        state, name = self.state()
        inspection = subprocess.CompletedProcess([], 0, json.dumps([{'Config': {'Labels': {bench.LABEL: 'a' * 32}}}]))
        with patch.object(bench.subprocess, 'run', return_value=inspection), patch.object(bench, 'command') as command:
            bench.cleanup(state)
        command.assert_called_once_with(['docker', 'rm', '-f', '-v', name])
        self.assertTrue(json.loads((self.root / 'cleanup.json').read_text())['complete'])

    def test_cleanup_refuses_foreign_resources_and_daemon_failure(self):
        state, _ = self.state()
        inspection = subprocess.CompletedProcess([], 0, json.dumps([{'Config': {'Labels': {bench.LABEL: 'b' * 32}}}]))
        with patch.object(bench.subprocess, 'run', return_value=inspection), patch.object(bench, 'command') as command:
            with self.assertRaises(ValueError):
                bench.cleanup(state)
            command.assert_not_called()
        with patch.object(bench.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, '')), \
             patch.object(bench, 'command', side_effect=RuntimeError('Docker unavailable')):
            with self.assertRaises(RuntimeError):
                bench.cleanup(state)

    def test_existing_state_is_never_cleaned_by_a_new_launch(self):
        with patch.object(bench, 'cleanup') as cleanup:
            with self.assertRaises(ValueError), bench.databases(1, self.root, 'mysql:8.4'):
                self.fail('existing output accepted')
            cleanup.assert_not_called()

    def test_partial_startup_is_cleaned_without_masking_original_failure(self):
        output = self.root / 'new'
        def failed(*_):
            output.mkdir()
            (output / 'resources.json').write_text('{}')
            raise RuntimeError('startup failed')
        with patch.object(bench, 'prepare', side_effect=failed), patch.object(bench, 'cleanup', side_effect=RuntimeError('cleanup failed')), \
             patch.object(bench.sys, 'stderr'):
            with self.assertRaisesRegex(RuntimeError, 'startup failed'), bench.databases(1, output, 'mysql:8.4'):
                self.fail('startup accepted')

    def test_safe_environment_does_not_forward_caller_provider_credentials(self):
        with patch.dict(os.environ, {'JAVA_HOME': '/test/jdk17', 'OPENAI_API_KEY': 'private-value', 'AWS_SECRET_ACCESS_KEY': 'private-value'}):
            environment = bench.safe_environment()
        self.assertNotIn('private-value', environment.values())
        self.assertEqual('33901', environment['TEST_MYSQL_PORT'])

    def test_new_and_duplicate_manifest_classes_fail_validation(self):
        self.assertTrue(bench.inventory()['contexts'])
        original = bench.MANIFEST.read_text()
        manifest = self.root / 'manifest.json'
        data = json.loads(original)
        data['contexts'].append(data['contexts'][0])
        manifest.write_text(json.dumps(data))
        with patch.object(bench, 'MANIFEST', manifest), self.assertRaises(ValueError):
            bench.inventory()
        data['contexts'][-1] = 'uk.gegc.quizmaker.MissingTest'
        manifest.write_text(json.dumps(data))
        with patch.object(bench, 'MANIFEST', manifest), self.assertRaises(ValueError):
            bench.inventory()

    def test_missing_coverage_and_shared_server_identity_fail_the_gate(self):
        target = self.root / 'target'
        workers = target / 'context-workers'
        workers.mkdir(parents=True)
        (target / 'jacoco-context').mkdir()
        (target / 'site/jacoco').mkdir(parents=True)
        (target / 'site/jacoco/jacoco.xml').write_text('<report/>')
        for index, name in enumerate(('example.A', 'example.B'), 1):
            bench.write_json(workers / f'worker-{index}.json', {'server_uuid': 'shared', 'isolation': 'REPEATABLE-READ'})
            (workers / f'worker-{index}.classes').write_text(name + '\n')
            (target / f'jacoco-context/{index}.exec').write_bytes(b'fixture-data')
        data = {'contexts': ['example.A', 'example.B'], 'database_companions': [], 'excluded': [], 'not_discovered': []}
        result = {'suites': ['example.A', 'example.B']}
        with patch.object(bench, 'inventory', return_value=data), patch.object(bench, 'reports', return_value=result):
            with self.assertRaisesRegex(ValueError, 'share a database'):
                bench.check_evidence(2, 'contexts', target=target)
            bench.write_json(workers / 'worker-2.json', {'server_uuid': 'independent', 'isolation': 'REPEATABLE-READ'})
            (target / 'jacoco-context/2.exec').unlink()
            with self.assertRaises(OSError):
                bench.check_evidence(2, 'contexts', target=target)


if __name__ == '__main__':
    unittest.main()
