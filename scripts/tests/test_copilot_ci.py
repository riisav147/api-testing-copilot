import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('copilot_ci', Path(__file__).parents[1] / 'copilot-ci.py')
ci = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ci)


class FakeClient:
    def __init__(self, state='COMPLETED', success=True):
        self.state, self.success, self.posts = state, success, []

    def request(self, path, payload=None, **kwargs):
        if path.startswith('/plans/'):
            return {'scenarioRevisions': [1, 1], 'scenarios': [{'method': 'GET'}, {'method': 'POST'}], 'specificationId': 'spec'}
        if path.startswith('/specifications/'):
            return {'project': {'id': 'project'}}
        if path.endswith('/environments'):
            return [{'id': 'env', 'baseUrl': 'http://127.0.0.1:8081', 'revision': 2}]
        if path == '/batches':
            self.posts.append(payload)
            return {'id': payload['id'], 'state': self.state}
        if path.endswith('/cancel'):
            self.state, self.success = 'CANCELLED', False
            return {'id': self.posts[0]['id'], 'state': self.state}
        if 'format=json' in path:
            return {'id': self.posts[0]['id'], 'state': self.state, 'success': self.success,
                    'cases': [{'state': 'PASSED' if self.success else 'UNKNOWN'}]}
        if 'format=junit' in path:
            return '<testsuite tests="1"/>'
        return {'id': self.posts[0]['id'], 'state': self.state}


class CliTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.config = {'planId': '39ca1ec9-91de-485c-8a80-93332825128f', 'environmentId': None,
                       'cases': [{'scenarioIndex': 0, 'expectedRevision': 1}], 'parallelism': 1, 'independent': False}
        self.path = self.root / 'config.json'
        self.path.write_text(json.dumps(self.config))
        self.args = SimpleNamespace(config=self.path, authorize_target='sandbox', output=self.root / 'artifacts', timeout=1)

    def test_duplicate_cli_invocations_reuse_one_submission_identity(self):
        client = FakeClient()
        self.assertEqual(ci.run(client, self.args), 0)
        self.assertEqual(ci.run(client, self.args), 0)
        self.assertEqual(client.posts[0]['id'], client.posts[1]['id'])
        self.assertTrue((self.args.output / 'results.json').exists())
        self.assertTrue((self.args.output / 'junit.xml').exists())

    def test_failed_or_uncertain_results_exit_nonzero(self):
        self.assertEqual(ci.run(FakeClient(success=False), self.args), 1)

    def test_authorization_and_stale_revisions_fail_before_post(self):
        client = FakeClient()
        with self.assertRaises(ValueError):
            ci.reviewed_payload(client, self.config, 'http://127.0.0.1:9999')
        self.config['cases'][0]['expectedRevision'] = 2
        with self.assertRaises(ValueError):
            ci.reviewed_payload(client, self.config, 'sandbox')
        self.assertEqual(client.posts, [])

    def test_environment_revision_is_bound_to_approval(self):
        self.config['environmentId'] = 'env'
        payload = ci.reviewed_payload(FakeClient(), self.config, 'http://127.0.0.1:8081')
        self.assertEqual(payload['expectedEnvironmentRevision'], 2)
        self.assertEqual(payload['expectedTarget'], 'http://127.0.0.1:8081')

    def test_parallel_writes_are_rejected(self):
        self.config.update(cases=[{'scenarioIndex': 1, 'expectedRevision': 1}], parallelism=2, independent=True)
        with self.assertRaises(ValueError):
            ci.reviewed_payload(FakeClient(), self.config, 'sandbox')

    def test_backend_origin_and_redirect_policy(self):
        for url in ['http://localhost:8080', 'https://127.0.0.1:8080', 'http://user:secret@127.0.0.1:8080', 'http://127.0.0.1:8080/private', 'http://169.254.169.254:8080']:
            with self.assertRaises(ValueError):
                ci.Client(url)
        self.assertIsNone(ci.NoRedirect().redirect_request(None, None, 302, '', {}, 'http://example.com'))

    def test_artifact_directory_cannot_silently_become_a_new_submission(self):
        ci.run(FakeClient(), self.args)
        self.config['cases'] = [{'scenarioIndex': 1, 'expectedRevision': 1}]
        self.path.write_text(json.dumps(self.config))
        client = FakeClient()
        with self.assertRaises(ValueError):
            ci.run(client, self.args)
        self.assertEqual(client.posts, [])

    def test_timeout_cancels_queued_work_and_fails(self):
        client = FakeClient(state='RUNNING')
        with patch.object(ci.time, 'monotonic', side_effect=[0, 2]):
            self.assertEqual(ci.run(client, self.args), 1)
        self.assertEqual(client.state, 'CANCELLED')

    def test_config_rejects_duplicate_cases_and_unexpected_secret_fields(self):
        self.config['cases'] *= 2
        self.path.write_text(json.dumps(self.config))
        with self.assertRaises(ValueError):
            ci.load_config(self.path)
        self.config['apiKey'] = 'do-not-accept-secrets-in-config'
        self.path.write_text(json.dumps(self.config))
        with self.assertRaises(ValueError):
            ci.load_config(self.path)


if __name__ == '__main__':
    unittest.main()
