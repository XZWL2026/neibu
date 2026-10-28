import sys
sys.dont_write_bytecode = True
import importlib.util
import pathlib
import tempfile
import unittest
import threading
import time
import json

class WorkerTest(unittest.TestCase):
    def setUp(self):
        path = pathlib.Path(__file__).with_name('worker.py')
        self.assertTrue(path.exists(), 'persistent depth worker is missing')
        spec = importlib.util.spec_from_file_location('worker', path)
        self.worker = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.worker)

    def test_duration_boundary(self):
        self.worker.validate_selection(30, '720p')
        self.worker.validate_selection(30.001, '480p')
        for seconds, resolution in [(30.001, '720p'), (float('nan'), '480p'), (0, '480p'), (5, '1080p')]:
            with self.assertRaises(ValueError): self.worker.validate_selection(seconds, resolution)

    def test_persistent_idempotency_and_restart(self):
        with tempfile.TemporaryDirectory() as root:
            jobs = self.worker.Jobs(pathlib.Path(root))
            payload = {'owner': 'owner', 'sourceFileId': 'file', 'resolution': '480p'}
            self.assertIsNone(jobs.lookup('job', payload))
            folder = jobs.folder('job'); folder.mkdir()
            (folder/'input.mp4').write_bytes(b'video')
            jobs.write('job', dict(payload, status='RUNNING'))
            orphan = jobs.folder('interrupted'); orphan.mkdir(); (orphan / 'input.part').write_bytes(b'incomplete')
            restarted = self.worker.Jobs(pathlib.Path(root))
            self.assertFalse((orphan / 'input.part').exists())
            self.assertEqual('QUEUED', restarted.lookup('job', payload)['status'])
            with self.assertRaises(ValueError): restarted.lookup('job', dict(payload, owner='other'))
            for name in ['../job', '.', '/tmp/job', 'job/other']:
                with self.assertRaises(ValueError): restarted.folder(name)

    def test_http_auth_identity_and_result_boundary(self):
        import urllib.request
        import urllib.error
        with tempfile.TemporaryDirectory() as root:
            jobs = self.worker.Jobs(pathlib.Path(root))
            identity = dict(owner='owner', sourceFileId='file', resolution='480p', status='COMPLETE')
            jobs.write('job', identity)
            (jobs.folder('job') / 'output.mp4').write_bytes(b'video')
            server = self.worker.server(jobs, 'local-test-token-12345678901234567890', 0)
            thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            try:
                def call(path, data=None, headers=None):
                    request = urllib.request.Request(f'http://127.0.0.1:{server.server_port}' + path, data=data, headers=headers or {})
                    try:
                        with urllib.request.urlopen(request) as response: return response.status, response.read()
                    except urllib.error.HTTPError as error:
                        with error: return error.code, error.read()
                self.assertEqual(401, call('/jobs/job/result')[0])
                auth = {'Authorization': 'Bearer local-test-token-12345678901234567890'}
                self.assertEqual((200, b'video'), call('/jobs/job/result', headers=auth))
                headers = dict(auth, **{'Content-Type': 'video/mp4', 'X-Owner-Id': 'owner', 'X-Source-File-Id': 'file', 'X-Resolution': '480p'})
                self.assertEqual(202, call('/jobs/job', b'v' * (4 * 1024 * 1024), headers)[0])
                self.assertEqual(409, call('/jobs/job', b'video', dict(headers, **{'X-Owner-Id': 'other'}))[0])
                self.assertEqual(404, call('/jobs/../state.json', headers=auth)[0])
                from unittest.mock import patch
                with patch.object(self.worker, 'inspect_video', side_effect=ValueError('invalid video')):
                    self.assertEqual(422, call('/jobs/rejected', b'video', headers)[0])
                self.assertFalse((jobs.folder('rejected') / 'input.part').exists())
            finally:
                server.shutdown(); server.server_close(); thread.join()

if __name__ == '__main__': unittest.main()
