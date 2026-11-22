import http.server
import json
from pathlib import Path
import tempfile
import threading
import unittest
import uuid
from unittest.mock import patch

import local_api_fixtures as fixtures


class LocalFixtureSafetyTest(unittest.TestCase):
    def test_public_environment_contains_only_an_empty_access_placeholder(self):
        environment = json.loads((fixtures.ROOT / 'postman/environments/Local-Docker.postman_environment.json').read_text())
        values = {entry['key']: entry['value'] for entry in environment['values']}
        self.assertTrue(values.get('accessFixtureData') == '', 'Public Access fixture value must stay empty.')
        self.assertEqual({'baseUrl', 'accessFixtureData'}, set(values))

    def test_access_data_pairs_author_with_another_owners_existing_quiz(self):
        state = {'users': {}, 'sets': {'http-basics': {'id': str(uuid.uuid4())}}}
        for key in ('reader', 'author_a', 'author_b', 'moderator', 'admin'):
            state['users'][key] = {'id': str(uuid.uuid4()), 'username': 'fixture_' + key,
                                   'password': 'synthetic-local-test-placeholder'}
        row, = fixtures.access_data(state)
        self.assertEqual(state['users']['author_a']['id'], row['authorUserId'])
        self.assertEqual(state['users']['author_b']['id'], row['authorBUserId'])
        self.assertEqual(state['users']['author_b']['username'], row['authorBUsername'])
        self.assertEqual(state['users']['author_b']['password'], row['authorBPassword'])
        self.assertNotEqual(row['authorUserId'], row['authorBUserId'])
        self.assertEqual(state['users']['moderator']['id'], row['moderatorUserId'])
        self.assertEqual(state['users']['moderator']['username'], row['moderatorUsername'])
        self.assertEqual(state['users']['moderator']['password'], row['moderatorPassword'])
        self.assertNotIn(row['moderatorUserId'], [row['authorUserId'], row['authorBUserId'], row['adminUserId']])
        self.assertEqual(state['users']['author_b']['id'], row['targetOwnerId'])
        self.assertEqual(state['sets']['http-basics']['id'], row['targetQuizId'])
        self.assertNotEqual(row['authorUserId'], row['targetOwnerId'])
        self.assertNotIn('ownerPassword', row)
        for key in ('author_b', 'moderator'):
            with self.subTest(colliding_identity=key):
                original_id = state['users'][key]['id']
                state['users'][key]['id'] = state['users']['author_a']['id']
                with self.assertRaisesRegex(RuntimeError, 'distinct'):
                    fixtures.access_data(state)
                state['users'][key]['id'] = original_id

    def test_wrong_docker_project_is_rejected_before_http_or_sql(self):
        with patch.object(fixtures, 'docker', return_value='another-project app healthy'), \
                patch.object(fixtures, 'api') as api, patch.object(fixtures, 'sql') as sql:
            with self.assertRaisesRegex(RuntimeError, 'Refusing to seed outside'):
                fixtures.check_target()
            api.assert_not_called()
            sql.assert_not_called()

    def test_private_files_keep_data_and_replace_insecure_existing_mode(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'fixtures.private.json'
            path.write_text('{}')
            path.chmod(0o644)
            fixtures.save_private(path, {'example': ['local-only']})
            self.assertEqual({'example': ['local-only']}, json.loads(path.read_text()))
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            self.assertEqual([path], list(path.parent.iterdir()))

    def test_api_never_follows_redirects_or_forwards_credentials_to_another_path(self):
        target_hits = []

        class RedirectHandler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path == '/start':
                    self.send_response(302)
                    self.send_header('Location', '/must-not-be-called')
                    self.end_headers()
                else:
                    target_hits.append(self.path)
                    self.send_response(200)
                    self.end_headers()
                    self.wfile.write(b'{}')

            def log_message(self, *args):
                pass

        with http.server.ThreadingHTTPServer(('127.0.0.1', 0), RedirectHandler) as server:
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            try:
                with patch.object(fixtures, 'BASE_URL', f'http://127.0.0.1:{server.server_port}'):
                    with self.assertRaisesRegex(RuntimeError, 'returned 302'):
                        fixtures.api('GET', '/start')
            finally:
                server.shutdown()
                worker.join(timeout=5)
        self.assertEqual([], target_hits)


if __name__ == '__main__':
    unittest.main()
