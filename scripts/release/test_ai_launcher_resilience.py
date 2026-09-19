"""Opt-in installed-development-package host tests. Never edits the installed package.

DCG_TEST_PACKAGE=/absolute/package DCG_TEST_EVIDENCE=/absolute/private/evidence \
  python3 -m unittest scripts.release.test_ai_launcher_resilience -v
"""
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import unittest
import urllib.request

PACKAGE = os.environ.get('DCG_TEST_PACKAGE')
EVIDENCE = os.environ.get('DCG_TEST_EVIDENCE')
IEMS = os.environ.get('IEMS_TEST_ROOT')


@unittest.skipUnless(PACKAGE and EVIDENCE, 'Set DCG_TEST_PACKAGE and DCG_TEST_EVIDENCE')
class InstalledLauncherResilienceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='dcg-phase2-launcher-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.package = self.root / 'package'
        shutil.copytree(PACKAGE, self.package, copy_function=os.link)
        self.state = self.root / 'state'
        self.evidence = Path(EVIDENCE) / self._testMethodName
        self.evidence.mkdir(parents=True, exist_ok=True)
        self.commands = []
        self.addCleanup(self.capture)
        self.addCleanup(self.stop)
        self.env = {**os.environ, 'DCG_DATA_DIR': str(self.state), 'DCG_AI_ENABLED': 'true',
                    'DCG_AI_STARTUP_TIMEOUT_SECONDS': '3'}
        for port in (8080, 8081):
            with socket.socket() as sock:
                if sock.connect_ex(('127.0.0.1', port)) == 0:
                    self.skipTest(f'Port {port} already has a listener')

    def command(self, name, expected=0):
        run = subprocess.run([str(self.package / 'bin' / name)], env=self.env,
                             capture_output=True, text=True, timeout=90)
        self.commands.append({'command': name, 'exit': run.returncode,
                              'stdout': run.stdout, 'stderr': run.stderr})
        if run.returncode != expected:
            pid_file = self.state / 'run/java.pid'
            details = {'pid_file': pid_file.read_text() if pid_file.exists() else None,
                       'java_log': (self.state / 'logs/java.log').read_text()[-1000:]
                       if (self.state / 'logs/java.log').exists() else None}
            self.commands[-1]['diagnostics'] = details
        self.assertEqual(run.returncode, expected, run.stdout + run.stderr)
        return run.stdout

    def health(self, port):
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(f'http://127.0.0.1:{port}/' +
                         ('actuator/health' if port == 8080 else 'health/ready'), timeout=2) as response:
            self.assertEqual(response.status, 200)
            return response.read()

    def iems_advisory(self, expected_status):
        if not IEMS:
            return
        output = self.evidence / 'iems-advisory.json'
        run = subprocess.run(['python3', str(Path(IEMS) / 'scripts/dcg/advisory_rehearsal.py'),
                              '--scenario', 'all', '--endpoint', 'http://127.0.0.1:8081',
                              '--output', str(output)],
                             env={**self.env, 'DCG_HOME': str(self.package)},
                             capture_output=True, text=True, timeout=90)
        self.commands.append({'command': 'iems-advisory', 'exit': run.returncode,
                              'stdout': run.stdout[-1000:], 'stderr': run.stderr})
        self.assertEqual(run.returncode, 0, run.stderr)
        result = json.loads(output.read_text())
        self.assertEqual(result['compatible']['finalEnforcementResult'], 'PASS')
        self.assertEqual(result['breaking']['finalEnforcementResult'], 'FAIL')
        self.assertEqual(result['multiple-breaking']['finalEnforcementResult'], 'FAIL')
        self.assertTrue(all(contract['advisory']['advisoryStatus'] == expected_status
                            for scenario in result.values() for contract in scenario['contracts']))

    def stop(self):
        if (self.state / 'run').exists():
            run = subprocess.run([str(self.package / 'bin/stop')], env=self.env,
                                 capture_output=True, text=True, timeout=45)
            self.commands.append({'command': 'cleanup-stop', 'exit': run.returncode,
                                  'stdout': run.stdout, 'stderr': run.stderr})
            self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
            time.sleep(1)

    def capture(self):
        (self.evidence / 'commands.json').write_text(json.dumps(self.commands, indent=2) + '\n')
        for name in ('java', 'rust'):
            source = self.state / 'logs' / f'{name}.log'
            if source.is_file():
                shutil.copyfile(source, self.evidence / f'{name}.log')

    def test_disabled(self):
        self.env['DCG_AI_ENABLED'] = 'false'
        self.command('start')
        self.health(8080)
        self.assertIn('AI advisory mode: DISABLED', self.command('status'))
        self.assertFalse((self.state / 'run/rust.pid').exists())
        self.assertFalse((self.state / 'logs/rust.log').exists())

    def test_ready_and_inference(self):
        self.assertIn('AI advisory: available.', self.command('start'))
        self.health(8080)
        self.health(8081)
        self.assertIn('AI advisory mode: AVAILABLE', self.command('status'))
        payload = json.dumps({'base_schema': {'type': 'object'},
                              'candidate_schema': {'type': 'object'},
                              'policy_pack': 'baseline'}).encode()
        request = urllib.request.Request('http://127.0.0.1:8081/v1/shadow/predict', payload,
                                         {'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=2) as response:
            self.assertEqual(len(json.load(response)['predictions']), 3)
        self.iems_advisory('AVAILABLE')
        # Test-only probe failure after readiness: status must say UNAVAILABLE,
        # not STARTING, while the tracked Rust process still exists.
        helper = self.package / 'bin/dcg'
        content = helper.read_text()
        helper.unlink()
        helper.write_text(content.replace(
            "ready() { curl --noproxy '*'",
            "ready() { [[ \"$1\" != *:8081/* ]] || return 1; curl --noproxy '*'"))
        helper.chmod(0o755)
        self.assertIn('AI advisory mode: UNAVAILABLE', self.command('status'))

    def test_missing_rust(self):
        (self.package / 'bin/dcgaimodel').unlink()
        self.assertIn('AI advisory: unavailable', self.command('start'))
        self.health(8080)
        self.assertIn('AI advisory mode: UNAVAILABLE', self.command('status'))
        self.assertFalse((self.state / 'run/rust.pid').exists())
        self.iems_advisory('UNAVAILABLE')

    def test_failed_rust_startup(self):
        model = next((self.package / 'model').rglob('seed-20260826-normal-family-split.json'))
        model.unlink()
        model.write_text('{}')
        self.assertIn('AI advisory: unavailable', self.command('start'))
        self.health(8080)
        self.assertIn('AI advisory mode: UNAVAILABLE', self.command('status'))

    def test_readiness_timeout(self):
        # Test-only fault: the actual Rust binary runs, but this copy's readiness
        # helper withholds the ready response. The installed package is untouched.
        helper = self.package / 'bin/dcg'
        content = helper.read_text()
        helper.unlink()
        helper.write_text(content.replace(
            "ready() { curl --noproxy '*'",
            "ready() { [[ \"$1\" != *:8081/* ]] || return 1; curl --noproxy '*'"))
        helper.chmod(0o755)
        self.env['DCG_AI_STARTUP_TIMEOUT_SECONDS'] = '3'
        process = subprocess.Popen([str(self.package / 'bin/start')], env=self.env,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                if (self.state / 'run/rust.pid').exists() and process.poll() is None:
                    break
                time.sleep(.02)
            else:
                self.fail('Rust never reached the controlled STARTING state')
            self.assertIn('AI advisory mode: STARTING', self.command('status'))
            stdout, stderr = process.communicate(timeout=15)
            self.commands.append({'command': 'start', 'exit': process.returncode,
                                  'stdout': stdout, 'stderr': stderr})
            self.assertEqual(process.returncode, 0, stderr)
            self.assertIn('AI advisory: unavailable', stdout)
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=5)
        self.health(8080)
        self.assertIn('AI advisory mode: UNAVAILABLE', self.command('status'))
        self.assertFalse((self.state / 'run/rust.pid').exists())

    def test_unrelated_ai_port_is_not_owned_or_stopped(self):
        with socket.socket() as sentinel:
            sentinel.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sentinel.bind(('127.0.0.1', 8081))
            sentinel.listen()
            self.assertIn('AI advisory: unavailable', self.command('start'))
            self.health(8080)
            self.assertIn('Rust advisory process: NOT OWNED', self.command('status'))
            self.stop()
            self.assertEqual(sentinel.getsockname()[1], 8081)


if __name__ == '__main__':
    unittest.main()
