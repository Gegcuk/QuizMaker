#!/usr/bin/env python3
"""Isolated, offline context benchmarks and complete Maven verification (stdlib only)."""
import argparse
import base64
from collections import Counter
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / 'tools/testing/context-test-manifest.json'
LABEL = 'quizmaker.context-run'


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n')


def command(args, **kwargs):
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs)
    if result.returncode:
        # Never echo command arguments, environment values, or provider diagnostics.
        raise RuntimeError(f'{Path(args[0]).name} failed (exit {result.returncode})')
    return result.stdout.strip()


def inventory():
    data = json.loads(MANIFEST.read_text())
    names = [name for key in ('contexts', 'database_companions', 'excluded', 'not_discovered') for name in data[key]]
    if len(names) != len(set(names)) or not data['contexts']:
        raise ValueError('Manifest has duplicate or missing suites')
    for name in names:
        if not re.fullmatch(r'uk\.gegc\.quizmaker\.[A-Za-z0-9_.]+', name):
            raise ValueError('Invalid manifest class name')
        if not (ROOT / 'src/test/java' / (name.replace('.', '/') + '.java')).is_file():
            raise ValueError(f'Manifest source is missing: {name}')
    selected = sorted(data['contexts'] + data['database_companions'])
    actual = [line for line in (MANIFEST.parent / 'context-suites.txt').read_text().splitlines()
              if line and not line.startswith('#')]
    if actual != [name.replace('.', '/') + '.java' for name in selected]:
        raise ValueError('context-suites.txt does not match the manifest')
    excludes = [line for line in (MANIFEST.parent / 'context-excludes.txt').read_text().splitlines()
                if line and not line.startswith('#')]
    if excludes != [name.replace('.', '/') + suffix for name in selected for suffix in ('.java', '$*.java')]:
        raise ValueError('context-excludes.txt must exclude enclosing and nested discovery roots')
    exclusive = data['exclusive_contexts']
    if not exclusive or len(exclusive) != len(set(exclusive)) or not set(exclusive) <= set(data['contexts']):
        raise ValueError('Exclusive suites must be a nonempty, unique subset of context suites')
    for filename, suffixes in (('context-exclusive-suites.txt', ('.java',)),
                               ('context-exclusive-excludes.txt', ('.java', '$*.java'))):
        actual = [line for line in (MANIFEST.parent / filename).read_text().splitlines()
                  if line and not line.startswith('#')]
        if actual != [name.replace('.', '/') + suffix for name in sorted(exclusive) for suffix in suffixes]:
            raise ValueError(f'{filename} does not match exclusive suites')
    return data


def fingerprint(directory=None):
    if directory:
        paths = sorted(directory.rglob('*.class'))
    else:
        paths = sorted(set(ROOT / name for name in command(
            ['git', 'ls-files', '--cached', '--others', '--exclude-standard'], cwd=ROOT).splitlines()
            if name.startswith(('src/', '.github/', 'tools/', '.mvn/')) or name in ('pom.xml', 'mvnw')))
    digest = hashlib.sha256()
    for path in paths:
        digest.update(str(path.relative_to(ROOT)).encode())
        digest.update(path.read_bytes())
    return digest.hexdigest()


def safe_environment():
    # Read only checked-in defaults, never the caller's provider credentials or .env.
    workflow = (ROOT / '.github/workflows/ci.yml').read_text()
    block = workflow.split('    env:\n', 1)[1].split('    services:\n', 1)[0]
    env = {key: os.environ[key] for key in ('HOME', 'USER', 'LOGNAME', 'TMPDIR', 'LANG', 'PATH', 'JAVA_HOME')
           if key in os.environ}
    if not env.get('JAVA_HOME'):
        raise ValueError('Set JAVA_HOME to JDK 17 before running verification')
    for line in block.splitlines():
        match = re.match(r'^      ([A-Z0-9_]+):\s*(.*?)\s*$', line)
        if not match:
            continue
        key, value = match.groups()
        fallback = re.search(r"\|\|\s*'([^']*)'", value)
        if fallback:
            value = fallback[1]
        elif '${{' in value:
            raise ValueError(f'Unsupported test-environment expression: {key}')
        else:
            value = value.strip("'\"")
        env[key] = value
    env.update(TEST_MYSQL_PORT='33901', TEST_MYSQL_ADMIN_USERNAME='root', TEST_MYSQL_ADMIN_PASSWORD='root',
               SPRING_DATASOURCE_USERNAME='bestuser', SPRING_DATASOURCE_PASSWORD='bestuser',
               JWT_SECRET=base64.b64encode(b'local-test-only-' * 5).decode(),
               TOKEN_PEPPER_SECRET='local-test-only-pepper-not-for-production',
               AWS_EC2_METADATA_DISABLED='true',
               JAVA_TOOL_OPTIONS='-Djava.net.preferIPv4Stack=true -Xms256m -Xmx2g')
    return env


def cleanup(state_path):
    state = json.loads(state_path.read_text())
    run_id = state['run_id']
    if not re.fullmatch(r'[a-f0-9]{32}', run_id):
        raise ValueError('Invalid cleanup run identity')
    failures = []
    for name in state['containers']:
        if not re.fullmatch(r'qm-context-' + run_id + r'-[1-4]', name):
            raise ValueError('Cleanup target does not belong to this run')
        result = subprocess.run(['docker', 'container', 'inspect', name], capture_output=True, text=True)
        if result.returncode:
            # Distinguish an absent container from a dead Docker daemon.
            command(['docker', 'info', '--format', '{{.ServerVersion}}'])
            continue
        actual = json.loads(result.stdout)[0]
        if actual.get('Config', {}).get('Labels', {}).get(LABEL) != run_id:
            raise ValueError('Refusing to delete a container with another owner label')
        try:
            command(['docker', 'rm', '-f', '-v', name])
        except RuntimeError:
            failures.append(name)
    if failures:
        raise RuntimeError('Cleanup incomplete; owned containers: ' + ', '.join(failures))
    write_json(state_path.with_name('cleanup.json'), {'run_id': run_id, 'complete': True})


def prepare(workers, output, image):
    output.mkdir(parents=True, exist_ok=False)
    state_path = output / 'resources.json'
    run_id = uuid.uuid4().hex
    state = {'run_id': run_id, 'containers': [], 'workers': workers}
    write_json(state_path, state)
    started = time.monotonic()
    # Use one resolved local image for every worker; pulling is a separate explicit prerequisite.
    image_id = command(['docker', 'image', 'inspect', image, '--format', '{{.Id}}'])
    for index in range(1, workers + 1):
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', 33900 + index))
        name = f'qm-context-{run_id}-{index}'
        state['containers'].append(name)
        write_json(state_path, state)  # Record ownership before a possibly interrupted create.
        command(['docker', 'run', '-d', '--name', name, '--label', f'{LABEL}={run_id}',
                 '-p', f'127.0.0.1:{33900 + index}:3306',
                 '-e', 'MYSQL_ROOT_PASSWORD=root', '-e', 'MYSQL_DATABASE=quizmaker_test',
                 '-e', 'MYSQL_USER=bestuser', '-e', 'MYSQL_PASSWORD=bestuser', image_id])
    servers = []
    for name in state['containers']:
        sql = ['docker', 'exec', name, 'mysql', '-h127.0.0.1', '-uroot', '-proot', '-Nse']
        deadline = time.monotonic() + 120
        while True:
            try:
                command(sql + ['SELECT 1'])
                break
            except RuntimeError:
                if time.monotonic() >= deadline:
                    raise RuntimeError('Test MySQL readiness timed out') from None
                time.sleep(0.25)
        command(sql + ["CREATE DATABASE quizmaker_test_mysql; GRANT ALL ON quizmaker_test_mysql.* TO 'bestuser'@'%';"])
        servers.append(command(sql + ['SELECT @@server_uuid, VERSION(), @@transaction_isolation, @@innodb_buffer_pool_size']))
    state.update(image=image_id, servers=servers, setup_seconds=time.monotonic() - started)
    write_json(state_path, state)
    return state


@contextmanager
def databases(workers, output, image):
    if output.exists():
        raise ValueError('Database state directory already exists')
    try:
        yield prepare(workers, output, image)
    finally:
        state = output / 'resources.json'
        if state.exists():
            original = sys.exc_info()[1]
            try:
                cleanup(state)
            except Exception as error:
                if original is None:
                    raise
                print(f'Cleanup also failed; inspect {state}: {error}', file=sys.stderr)


def offline_prefix(output):
    if sys.platform == 'darwin':
        policy = output / 'offline.sb'
        policy.write_text('(version 1)\n(allow default)\n(deny network*)\n'
                          '(allow network-outbound (remote ip "localhost:*"))\n'
                          '(allow network-inbound (local ip "localhost:*"))\n'
                          '(allow network-bind (local ip "localhost:*"))\n'
                          '(allow network* (local unix-socket) (remote unix-socket))\n')
        return ['sandbox-exec', '-f', str(policy)]
    # Linux callers run the Maven child through the existing CI namespace boundary.
    raise RuntimeError('Local launcher currently requires macOS; on Linux use CI namespace + prepare/check/cleanup')


def maven(arguments, log, env):
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open('w') as stream:
        process = subprocess.Popen(offline_prefix(log.parent) + ['./mvnw', '-o', '-B'] + arguments,
                                   cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            code = process.wait()
        finally:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
    if code:
        raise RuntimeError(f'Maven failed (exit {code}); inspect {log}')


def reports(directory):
    cases = Counter()
    suites = set()
    reported = set()
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    files = sorted(directory.rglob('TEST-*.xml'))
    if not files:
        raise ValueError('Surefire XML reports are missing')
    for file in files:
        suite = ET.parse(file).getroot()
        if suite.attrib['name'] in reported:
            raise ValueError('Duplicate suite report')
        reported.add(suite.attrib['name'])
        suites.add(suite.attrib['name'].split('$')[0])
        for key in totals:
            totals[key] += int(suite.attrib.get(key, '0'))
        for test in suite.findall('testcase'):
            cases[(test.attrib['classname'], test.attrib['name'])] += 1
    if not totals['tests'] or totals['failures'] or totals['errors'] or totals['skipped']:
        raise ValueError(f'Incomplete or failed test result: {totals}')
    if sum(cases.values()) != totals['tests']:
        raise ValueError('Inconsistent report totals')
    return {'totals': totals, 'cases': sorted([list(k) for k in cases.elements()]), 'suites': sorted(suites)}


def check_evidence(workers, scope, accelerated=True, target=None):
    target = target or ROOT / 'target'
    result = reports(target / 'surefire-reports')
    data = inventory()
    expected = set(data['contexts'] + (data['database_companions'] if scope == 'full' else []))
    actual = set(result['suites'])
    if not expected <= actual or actual & set(data['excluded'] + data['not_discovered']):
        raise ValueError('Missing required suites or unexpected excluded suites')
    if scope == 'contexts' and actual != expected:
        raise ValueError('Context comparison executed unexpected suites')
    if accelerated:
        server_ids = set()
        first_server = None
        assigned = set()
        for index in range(1, workers + 1):
            evidence = json.loads((target / f'context-workers/worker-{index}.json').read_text())
            server_ids.add(evidence['server_uuid'])
            if index == 1:
                first_server = evidence['server_uuid']
            if evidence['isolation'] != 'REPEATABLE-READ':
                raise ValueError('Unexpected MySQL server isolation')
            classes = {s.split('$')[0] for s in (target / f'context-workers/worker-{index}.classes').read_text().splitlines()}
            if not classes or classes & assigned:
                raise ValueError('Duplicate or empty worker assignment')
            assigned |= classes
            if (target / f'jacoco-context/{index}.exec').stat().st_size < 10:
                raise ValueError('Worker coverage is missing/empty')
        exclusive = set(data['exclusive_contexts'])
        if len(server_ids) != workers or assigned != expected - exclusive:
            raise ValueError('Workers share a database or do not cover the expected inventory')
        if exclusive:
            directory = target / 'context-exclusive-worker'
            evidence = json.loads((directory / 'worker-1.json').read_text())
            classes = {s.split('$')[0] for s in (directory / 'worker-1.classes').read_text().splitlines()}
            if evidence['server_uuid'] != first_server or evidence['isolation'] != 'REPEATABLE-READ':
                raise ValueError('Exclusive suites did not reuse the first isolated server after the worker pool')
            if classes != exclusive or classes & assigned:
                raise ValueError('Missing, duplicate or unexpected exclusive suite assignment')
            if (target / 'jacoco-context/exclusive.exec').stat().st_size < 10:
                raise ValueError('Exclusive coverage is missing/empty')
    xml = ET.parse(target / 'site/jacoco/jacoco.xml').getroot()
    result['coverage'] = {c.attrib['type']: dict(covered=int(c.attrib['covered']), missed=int(c.attrib['missed']))
                          for c in xml.findall('counter')}
    result['covered_lines'] = {p.attrib['name'] + '/' + f.attrib['name'] + ':' + line.attrib['nr']:
                               [int(line.attrib['ci']), int(line.attrib['cb'])]
                               for p in xml.findall('package') for f in p.findall('sourcefile') for line in f.findall('line')
                               if int(line.attrib['ci']) > 0}
    return result


def reset_test_outputs():
    for name in ('surefire-reports', 'context-workers', 'context-exclusive-worker', 'jacoco-context', 'site/jacoco'):
        shutil.rmtree(ROOT / 'target' / name, ignore_errors=True)
    for name in ('jacoco.exec', 'jacoco-merged.exec'):
        (ROOT / 'target' / name).unlink(missing_ok=True)


def check_coverage_log(log):
    if re.search(r'(?:Execution data for class .* does not match|Classes in bundle .* do not match)', log.read_text()):
        raise ValueError('JaCoCo class/bytecode mismatch')


def run(workers, output, image, scope, serial=False):
    env = safe_environment()
    before = fingerprint()
    started = time.monotonic()
    with databases(workers, output, image) as state:
        args = ['-DskipTests=false', '-Dorg.slf4j.simpleLogger.showDateTime=true']
        if not serial:
            args += ['-Pcontext-forks', f'-Dcontext.workers={workers}']
        if scope == 'full':
            args += ['clean', 'verify']
        else:
            reset_test_outputs()
            includes = output / 'contexts.txt'
            includes.write_text('\n'.join(n.replace('.', '/') + '.java' for n in inventory()['contexts']) + '\n')
            args += [f'-Dcontext.includesFile={includes}', 'jacoco:prepare-agent@prepare-context-agent',
                     'jacoco:prepare-agent@prepare-exclusive-context-agent',
                     'surefire:test@tests-context', 'surefire:test@tests-context-exclusive',
                     'jacoco:merge@merge-context-coverage',
                     'jacoco:report@report-context-coverage']
        test_start = time.monotonic()
        maven(args, output / 'maven.log', env)
        elapsed = time.monotonic() - test_start
        check_coverage_log(output / 'maven.log')
        result = check_evidence(workers, scope, not serial)
        if before != fingerprint():
            raise ValueError('Source/configuration changed during verification')
        result.update(commit=command(['git', 'rev-parse', 'HEAD'], cwd=ROOT), source=before,
                      bytecode=fingerprint(ROOT / 'target/classes'), workers=workers, scope=scope, serial=serial,
                      setup_seconds=state['setup_seconds'], test_seconds=elapsed, total_seconds=time.monotonic()-started)
        for name in ('surefire-reports', 'context-workers', 'context-exclusive-worker', 'jacoco-context', 'site/jacoco'):
            source = ROOT / 'target' / name
            if source.exists():
                shutil.copytree(source, output / name)
        for name in ('jacoco.exec', 'jacoco-merged.exec'):
            if (ROOT / 'target' / name).exists():
                shutil.copy2(ROOT / 'target' / name, output / name)
        write_json(output / 'result.json', result)
        print(json.dumps({k: result[k] for k in ('workers', 'scope', 'totals', 'test_seconds', 'total_seconds')}), flush=True)
        return result


def compare_results(baseline, current):
    for key in ('source', 'bytecode', 'cases'):
        if baseline[key] != current[key]:
            raise ValueError(f'Comparison mismatch: {key}')
    missing = sorted(set(baseline['covered_lines']) - set(current['covered_lines']))
    changes = {key: [baseline['covered_lines'][key], current['covered_lines'].get(key)]
               for key in baseline['covered_lines'] if baseline['covered_lines'][key] != current['covered_lines'].get(key)}
    return {'same_cases': True, 'missing_covered_lines': missing, 'coverage_changes': changes,
            'speedup': baseline['test_seconds'] / current['test_seconds']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['inventory', 'prepare', 'cleanup', 'check', 'compare', 'verify'])
    parser.add_argument('--workers', default='1')
    parser.add_argument('--scope', choices=['contexts', 'full'], default='contexts')
    parser.add_argument('--output', type=Path, default=Path('target/context-benchmark'))
    parser.add_argument('--image', default='mysql:8.4')
    parser.add_argument('--serial', action='store_true')
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--repeat-selected', type=int, default=2)
    parser.add_argument('--verification-log', type=Path)
    args = parser.parse_args()
    workers = [int(x) for x in args.workers.split(',')]
    if not workers or any(x not in (1, 2, 4) for x in workers):
        parser.error('workers must be 1, 2 or 4')
    if args.serial and workers != [1]:
        parser.error('serial baseline requires one worker')
    if args.action != 'compare' and len(workers) != 1:
        parser.error('only compare accepts multiple worker counts')
    if args.repeat_selected < 0:
        parser.error('repeat-selected must be nonnegative')
    output = args.output.resolve()
    inventory()
    if args.action == 'cleanup':
        cleanup(output / 'resources.json')
    elif args.action == 'prepare':
        if output.exists():
            raise ValueError('Database state directory already exists')
        try:
            prepare(workers[0], output, args.image)
        except BaseException:
            if (output / 'resources.json').exists():
                cleanup(output / 'resources.json')
            raise
    elif args.action == 'check':
        if args.verification_log is None:
            parser.error('check requires --verification-log to reject incompatible coverage')
        check_coverage_log(args.verification_log)
        result = check_evidence(workers[0], args.scope, not args.serial)
        write_json(output / 'evidence.json', result)
    elif args.action == 'inventory':
        if args.check:
            with tempfile.TemporaryDirectory(prefix='quizmaker-inventory-') as directory:
                maven(['-Dtest=ContextTestManifestTest', 'test'], Path(directory) / 'inventory.log', safe_environment())
        print('Manifest checked: ' + str(len(inventory()['contexts'])) + ' context suites')
    else:
        if output.exists():
            raise ValueError('Output already exists; choose a fresh path to prevent stale evidence')
        # Keep live logs/state outside target: a full clean verify deletes target.
        with tempfile.TemporaryDirectory(prefix='quizmaker-context-') as directory:
            staging = Path(directory)
            try:
                if args.action == 'verify':
                    run(workers[0], staging / 'verify', args.image, 'full', args.serial)
                else:
                    maven(['-DskipTests', 'process-test-classes'], staging / 'compile.log', safe_environment())
                    baseline = None
                    for index, count in enumerate(workers + [workers[-1]] * args.repeat_selected):
                        result = run(count, staging / f'run-{index+1}-{count}', args.image, args.scope)
                        if baseline is None:
                            baseline = result
                        else:
                            write_json(staging / f'comparison-{index+1}.json', compare_results(baseline, result))
            finally:
                shutil.copytree(staging, output, dirs_exist_ok=True)


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    try:
        main()
    except (RuntimeError, ValueError, OSError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
