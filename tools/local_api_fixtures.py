#!/usr/bin/env python3
"""Seed only the quizmaker-postman Docker project; never accepts remote targets."""

import copy
import fcntl
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import uuid


ROOT = Path(__file__).resolve().parents[1]
DEFINITIONS = ROOT / 'postman/fixtures'
STATE_PATH = ROOT / 'server/local/fixtures.private.json'
DATA_PATH = ROOT / 'postman/data/Personas.private.json'
ACCESS_DATA_PATH = ROOT / 'postman/data/Access.private.json'
BASE_URL = 'http://127.0.0.1:8080'
APP = 'quizmaker-postman-app-1'
MYSQL = 'quizmaker-postman-mysql-1'
GATEWAY = 'quizmaker-postman-gateway-1'
APPROVED = {
    'reader': (['ROLE_USER'], True),
    'author_a': (['ROLE_USER', 'ROLE_QUIZ_CREATOR'], True),
    'author_b': (['ROLE_USER', 'ROLE_QUIZ_CREATOR'], True),
    'moderator': (['ROLE_MODERATOR'], True),
    'admin': (['ROLE_ADMIN'], True),
    'disabled': (['ROLE_USER', 'ROLE_QUIZ_CREATOR'], False),
}
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def docker(*args, stdin=None):
    result = subprocess.run(['docker', *args], input=stdin, text=True,
                            capture_output=True, timeout=45)
    require(result.returncode == 0, 'Local Docker operation failed; check container status.')
    return result.stdout.strip()


def check_target():
    for container, service in [(APP, 'app'), (MYSQL, 'mysql'), (GATEWAY, 'gateway')]:
        target = docker('inspect', '--format',
                        '{{index .Config.Labels "com.docker.compose.project"}} '
                        '{{index .Config.Labels "com.docker.compose.service"}} '
                        '{{.State.Health.Status}}', container)
        require(target == f'quizmaker-postman {service} healthy',
                'Refusing to seed outside the healthy quizmaker-postman Docker project.')
    require(docker('exec', MYSQL, 'printenv', 'MYSQL_DATABASE') == 'quizmaker_local',
            'Unexpected database; no fixture changes were made.')
    ports = json.loads(docker('inspect', '--format', '{{json .NetworkSettings.Ports}}', GATEWAY))
    require(ports.get('8080/tcp') == [{'HostIp': '127.0.0.1', 'HostPort': '8080'}],
            'Expected a localhost-only gateway on port 8080.')
    require(api('GET', '/api/v1/health') == {'status': 'UP'}, 'Local API is unavailable.')


def api(method, path, body=None, token=None, expected=200):
    headers = {'Accept': 'application/json'}
    if token:
        headers['Authorization'] = f'Bearer {token}'
    if body is not None:
        headers['Content-Type'] = 'application/json'
    request = urllib.request.Request(BASE_URL + path, method=method, headers=headers,
                                     data=None if body is None else json.dumps(body).encode())
    try:
        response = HTTP.open(request, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        if response.status != expected:
            retry = response.headers.get('Retry-After', '')
            suffix = f'; respect Retry-After={retry}' if retry.isdigit() else ''
            raise RuntimeError(f'{method} {path} returned {response.status}, expected {expected}{suffix}. '
                               'Response and credentials were omitted.')
        raw = response.read()
        return json.loads(raw) if raw else None


def sql(statement):
    # Password comes from the existing container environment, not argv/output.
    output = docker('exec', '-i', MYSQL, 'sh', '-c',
                    'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --user="$MYSQL_USER" '
                    '--database="$MYSQL_DATABASE" --batch --skip-column-names', stdin=statement)
    return output.splitlines() if output else []


def text_literal(value):
    # Hex literals avoid shell/SQL interpolation of data read from local manifests.
    return f"CONVERT(0x{value.encode('utf-8').hex()} USING utf8mb4)"


def identifier(value):
    return f"UNHEX('{uuid.UUID(value).hex}')"


def single(rows, context):
    require(len(rows) <= 1, f'Ambiguous {context}; refusing to choose or overwrite a record.')
    return rows[0] if rows else None


def save_private(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix='.fixture-', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'w') as output:
            json.dump(value, output, indent=2)
            output.write('\n')
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def load_definitions():
    personas = json.loads((DEFINITIONS / 'personas.json').read_text())
    sets = json.loads((DEFINITIONS / 'question-sets.json').read_text())
    require(personas['formatVersion'] == sets['formatVersion'] == 1, 'Unsupported fixture format.')
    require({p['key'] for p in personas['personas']} == set(APPROVED),
            'Persona changes require a separately approved role matrix.')
    for persona in personas['personas']:
        require((persona['roles'], persona['active']) == APPROVED[persona['key']],
                'Persona roles/activation differ from the approved fixture matrix.')
        require(persona['username'] == 'qmfx_' + persona['key'], 'Unexpected fixture username.')
    require(len(sets['sets']) == 2, 'Expected two small question sets.')
    for group in sets['sets']:
        require(group['ownerPersona'] in ('author_a', 'author_b'), 'Unexpected question owner.')
        require(group['quiz']['visibility'] == 'PRIVATE', 'Fixtures must remain private.')
        require(len(group['questions']) == 5, 'Expected five questions per fixture set.')
    return personas['personas'], sets['sets']


def load_state(personas):
    if STATE_PATH.exists():
        require(STATE_PATH.stat().st_mode & 0o077 == 0, 'Private fixture state must have mode 600.')
        state = json.loads(STATE_PATH.read_text())
        require(state['formatVersion'] == 1, 'Unsupported private fixture state.')
        uuid.UUID(state['namespace'])
        return state
    # Existing fixed usernames must not be claimed after losing the private state.
    names = ','.join(text_literal(p['username']) for p in personas)
    require(not sql(f'SELECT username FROM users WHERE username IN ({names});'),
            'Fixture usernames already exist. Restore fixtures.private.json; passwords are never reset automatically.')
    state = {'formatVersion': 1, 'namespace': str(uuid.uuid4()), 'users': {}, 'sets': {}}
    for persona in personas:
        state['users'][persona['key']] = {
            'username': persona['username'],
            'email': f"{persona['username']}.{state['namespace']}@example.invalid",
            'password': 'Aa1!' + secrets.token_urlsafe(30),
        }
    save_private(STATE_PATH, state)
    return state


def seed_user(persona, state):
    account = state['users'][persona['key']]
    require(account['username'] == persona['username'], 'Private state identity mismatch.')
    rows = sql('SELECT BIN_TO_UUID(user_id), email, is_deleted FROM users WHERE username=' +
               text_literal(account['username']) + ';')
    row = single(rows, 'fixture user')
    if row:
        user_id, email, deleted = row.split('\t')
        require(email == account['email'] and deleted == '0',
                'Username belongs to another or deleted account; no credentials/roles were changed.')
        require(account.get('id', user_id) == user_id, 'Stored fixture identity differs from the database.')
    else:
        require('id' not in account, 'Saved fixture user is missing; reconcile the local state before reseeding.')
        user = api('POST', '/api/v1/auth/register',
                   {key: account[key] for key in ('username', 'email', 'password')}, expected=201)
        user_id = user['id']
    account['id'] = user_id
    save_private(STATE_PATH, state)
    user_key = identifier(user_id)
    actual_roles = sql('SELECT r.role_name FROM roles r JOIN user_roles ur ON ur.role_id=r.role_id '
                       f'WHERE ur.user_id={user_key} ORDER BY r.role_name;')
    active = sql(f'SELECT is_active FROM users WHERE user_id={user_key};')
    if actual_roles != sorted(persona['roles']) or active != [str(int(persona['active']))]:
        names = ','.join(text_literal(role) for role in persona['roles'])
        available = sql(f'SELECT role_name FROM roles WHERE role_name IN ({names}) ORDER BY role_name;')
        require(available == sorted(persona['roles']), 'Required existing roles are missing.')
        sql(f'START TRANSACTION; DELETE FROM user_roles WHERE user_id={user_key}; '
            f'INSERT INTO user_roles(user_id,role_id) SELECT {user_key},role_id FROM roles '
            f'WHERE role_name IN ({names}); UPDATE users SET is_active={int(persona["active"])}, '
            f'version=version+1 WHERE user_id={user_key}; COMMIT;')


def login(account):
    return api('POST', '/api/v1/auth/login',
               {'username': account['username'], 'password': account['password']})['accessToken']


def seed_set(group, state, token):
    owner_id = state['users'][group['ownerPersona']]['id']
    saved = state['sets'].setdefault(group['key'], {'questions': {}})
    rows = sql('SELECT BIN_TO_UUID(quiz_id) FROM quizzes WHERE creator_id=' + identifier(owner_id) +
               ' AND title=' + text_literal(group['quiz']['title']) + ' AND is_deleted=0;')
    quiz_id = single(rows, 'fixture quiz')
    if quiz_id:
        require(saved.get('id', quiz_id) == quiz_id, 'Fixture quiz identity mismatch.')
    else:
        require('id' not in saved, 'Fixture quiz was removed; refusing to silently recreate it.')
        quiz_id = api('POST', '/api/v1/quizzes', group['quiz'], token, expected=201)['quizId']
    saved['id'] = quiz_id
    save_private(STATE_PATH, state)
    for question in group['questions']:
        rows = sql('SELECT BIN_TO_UUID(q.id) FROM questions q JOIN quiz_questions qq ON qq.question_id=q.id '
                   f'WHERE qq.quiz_id={identifier(quiz_id)} AND q.question=' +
                   text_literal(question['request']['questionText']) + ' AND q.is_deleted=0;')
        question_id = single(rows, 'fixture question')
        if question_id:
            require(saved['questions'].get(question['key'], question_id) == question_id,
                    'Fixture question identity mismatch.')
        else:
            require(question['key'] not in saved['questions'], 'Fixture question was removed; reconcile it explicitly.')
            body = copy.deepcopy(question['request'])
            body['quizIds'] = [quiz_id]
            question_id = api('POST', '/api/v1/questions', body, token, expected=201)['questionId']
        saved['questions'][question['key']] = question_id
        save_private(STATE_PATH, state)
        actual = api('GET', '/api/v1/questions/' + question_id, token=token)
        require(actual['type'] == question['request']['type'] and
                actual['difficulty'] == question['request']['difficulty'] and
                actual['questionText'] == question['request']['questionText'] and
                actual['content'] == question['request']['content'], 'Question fixture differs from its definition.')


def verify(personas, groups, state, tokens):
    for persona in personas:
        account = state['users'][persona['key']]
        if not persona['active']:
            api('POST', '/api/v1/auth/login', {k: account[k] for k in ('username', 'password')}, expected=401)
            continue
        me = api('GET', '/api/v1/auth/me', token=tokens[persona['key']])
        require(me['id'] == account['id'] and me['isActive'] is True and
                sorted(me['roles']) == sorted(persona['roles']),
                'Fixture authentication does not match the approved persona.')
    # Existing route-level permission and private-resource boundaries, without writes.
    api('GET', '/api/v1/admin/roles', token=tokens['reader'], expected=403)
    api('GET', '/api/v1/admin/roles', token=tokens['admin'])
    for group in groups:
        quiz_id = state['sets'][group['key']]['id']
        owner = group['ownerPersona']
        other = 'author_b' if owner == 'author_a' else 'author_a'
        quiz = api('GET', '/api/v1/quizzes/' + quiz_id, token=tokens[owner])
        require(quiz['visibility'] == 'PRIVATE' and quiz['status'] == 'DRAFT' and
                quiz['creatorId'] == state['users'][owner]['id'] and quiz['questionCount'] == 5,
                'Fixture quiz is no longer the expected private five-question draft.')
        api('GET', '/api/v1/quizzes/' + quiz_id, token=tokens[other], expected=403)
        api('GET', '/api/v1/quizzes/' + quiz_id, expected=401)


def access_data(state):
    """Local Postman data for reader, both authors, moderator and administrator."""
    row = {}
    identities = []
    for prefix, key in [('reader', 'reader'), ('author', 'author_a'),
                        ('authorB', 'author_b'), ('moderator', 'moderator'), ('admin', 'admin')]:
        account = state['users'][key]
        for field in ('username', 'password'):
            row[prefix + field.capitalize()] = account[field]
        row[prefix + 'UserId'] = str(uuid.UUID(account['id']))
        identities.append(row[prefix + 'UserId'])
    row['targetQuizId'] = str(uuid.UUID(state['sets']['http-basics']['id']))
    row['targetOwnerId'] = str(uuid.UUID(state['users']['author_b']['id']))
    require(len(set(identities)) == 5 and row['targetOwnerId'] == row['authorBUserId'],
            'Access fixtures require distinct reader, both authors, moderator and admin identities.')
    return [row]


def main():
    require(len(sys.argv) == 1, 'This tool accepts no target/database/credential arguments.')
    lock_path = ROOT / 'server/local/fixtures.lock'
    descriptor = os.open(lock_path, os.O_CREAT | os.O_RDWR, 0o600)
    with os.fdopen(descriptor, 'w') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError('Another local fixture operation is running.') from None
        check_target()
        personas, groups = load_definitions()
        state = load_state(personas)
        tokens = {}
        try:
            for persona in personas:
                seed_user(persona, state)
            for persona in personas:
                if persona['active']:
                    tokens[persona['key']] = login(state['users'][persona['key']])
            for group in groups:
                seed_set(group, state, tokens[group['ownerPersona']])
            verify(personas, groups, state, tokens)
        finally:
            logout_failed = False
            for token in tokens.values():
                try:
                    api('POST', '/api/v1/auth/logout', token=token, expected=204)
                except (RuntimeError, OSError, ValueError):
                    logout_failed = True
            require(not logout_failed, 'Could not revoke every fixture session; restore the local API and retry fixtures.')
        rows = []
        for persona in personas:
            account = state['users'][persona['key']]
            rows.append({'persona': persona['key'],
                         **{key: account[key] for key in ('username', 'email', 'password')},
                         'userId': account['id'], 'expectedRoles': persona['roles'],
                         'expectedActive': persona['active'],
                         'javaQuizId': state['sets']['java-basics']['id'],
                         'httpQuizId': state['sets']['http-basics']['id']})
        save_private(DATA_PATH, rows)
        save_private(ACCESS_DATA_PATH, access_data(state))
        print('Local fixtures ready: 6 personas, 2 private draft quizzes, 10 questions.')
        print('Verified login, roles, disabled account, permission and ownership denials; sessions logged out.')
        print('Private local Runner data: postman/data/Personas.private.json (mode 600; ignored by Git).')
        print('Local accessFixtureData source: postman/data/Access.private.json (mode 600; ignored by Git).')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, ValueError, KeyError, OSError, subprocess.TimeoutExpired) as error:
        print(f'Fixture setup stopped: {error}', file=sys.stderr)
        sys.exit(1)
