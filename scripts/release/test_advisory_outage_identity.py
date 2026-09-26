"""Linux process-identity regression, with real HTTP listeners and scoped signals.

The ps shim changes only calendar lstart output; the actual processes and kernel
start ticks remain unchanged. This does not change the host clock.
"""
import base64
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request
import unittest

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(sys.platform == 'linux', 'Linux /proc identity regression')
class AdvisoryOutageIdentityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='dcg identity ')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.package = self.root / 'relocated package'
        shutil.copytree(ROOT / 'packaging/local', self.package)
        self.data = self.root / 'persistent state'
        self.state = self.data / 'run'
        self.state.mkdir(parents=True)
        self.helper = self.package / 'bin/dcg'
        (self.state / 'ai-enabled').write_text('true\n')
        self.env = {**os.environ, 'DCG_DATA_DIR': str(self.data), 'COLUMNS': '80'}
        for port in (8080, 8081):
            with socket.socket() as sock:
                if sock.connect_ex(('127.0.0.1', port)) == 0:
                    self.skipTest(f'Port {port} is occupied')
        shim = self.root / 'tools'
        shim.mkdir()
        self.clock = self.root / 'calendar-start'
        self.clock.write_text('Sat Sep 26 12:00:00 2026\n')
        ps = shim / 'ps'
        ps.write_text('''#!/bin/bash
case " $* " in
  *" -o lstart= "*) cat "$TEST_CALENDAR_START";;
  *) exec "$TEST_REAL_PS" "$@";;
esac
''')
        ps.chmod(0o755)
        self.env.update(TEST_CALENDAR_START=str(self.clock), TEST_REAL_PS=shutil.which('ps'),
                        PATH=str(shim) + os.pathsep + self.env['PATH'])
        self.processes = {}
        self.addCleanup(self.cleanup_processes)

    def cleanup_processes(self):
        for process in self.processes.values():
            if process.poll() is None:
                process.kill()
            process.wait()
            process.stdout.close()

    def shell(self, body, *args):
        return subprocess.run(['bash', '-c', 'source "$1"; state_init; ' + body,
                               'test', str(self.helper), *map(str, args)],
                              env=self.env, capture_output=True, text=True, timeout=45)

    def command(self, name):
        return subprocess.run([str(self.package / 'bin' / name)], env=self.env,
                              capture_output=True, text=True, timeout=80)

    def launch(self, name):
        script = self.root / (name + '.py')
        port = 8080 if name == 'java' else 8081
        script.write_text(f'''import ctypes, http.server
ctypes.CDLL(None).prctl(15, b'{"java" if name == "java" else "dcgaimodel"}', 0, 0, 0)
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b'{{"status":"UP"}}')
    def log_message(self, *args): pass
server = http.server.HTTPServer(('127.0.0.1', {port}), Handler)
print('ready', flush=True)
server.serve_forever()
''')
        if name == 'java':
            args = [str(self.package / 'lib/contract-service-4.0.0-rc.1.jar'),
                    f'--spring.config.location=file:{self.data}/application-local-demo.properties']
        else:
            args = [str(self.package / 'bin/dcgaimodel'), 'serve-shadow-inference',
                    '--artifact-root', str(self.package / 'model'), '--bind', '127.0.0.1:8081']
        process = subprocess.Popen([sys.executable, str(script), *args], stdout=subprocess.PIPE,
                                   text=True, env=self.env)
        self.processes[name] = process
        self.assertEqual(process.stdout.readline().strip(), 'ready')
        result = self.shell('remember "$2" "$3"; alive "$2"', name, process.pid)
        self.assertEqual(result.returncode, 0, result.stderr)

    def start_pair(self):
        self.launch('java')
        self.launch('rust')
        self.assertEqual(self.command('status').returncode, 0)
        return (self.state / 'java.pid').read_bytes()

    def change_calendar(self):
        self.clock.write_text('Sat Sep 26 12:00:02 2026\n')

    def test_calendar_change_preserves_java_during_rust_outage_and_cleanup(self):
        identity = self.start_pair()
        self.change_calendar()
        stopped = self.shell('lock; stop_one rust')  # Exact acceptance outage setup.
        self.assertEqual(stopped.returncode, 0, stopped.stderr)
        self.processes['rust'].wait(timeout=2)
        self.assertIsNone(self.processes['java'].poll())
        result = self.command('status')
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        for line in ('Java service: RUNNING', 'Deterministic enforcement: ACTIVE',
                     'AI advisory mode: UNAVAILABLE', 'Rust advisory process: STOPPED'):
            self.assertIn(line, result.stdout)
        self.assertEqual((self.state / 'java.pid').read_bytes(), identity)
        stopped = self.command('stop')
        self.assertEqual(stopped.returncode, 0, stopped.stderr)
        self.processes['java'].wait(timeout=2)
        self.assertFalse(list(self.state.glob('*.pid')))
        for port in (8080, 8081):
            with socket.socket() as sock:
                self.assertNotEqual(sock.connect_ex(('127.0.0.1', port)), 0)

    def test_legacy_calendar_identity_loses_both_processes(self):
        # Reproduce the shared identity weakness without relying on WSL clock drift.
        helper = self.helper.read_text()
        start = helper.index('signature() {')
        end = helper.index('recorded_process_exists()', start)
        self.helper.write_text(helper[:start] +
                               'signature() { ps -p "$1" -o lstart=; }\n' + helper[end:])
        self.start_pair()
        self.change_calendar()
        self.assertNotEqual(self.shell('alive java').returncode, 0)
        self.assertNotEqual(self.shell('alive rust').returncode, 0)
        stopped = self.shell('stop_one rust')
        # The fixed stop preserves evidence and reports failure instead of
        # claiming Rust stopped and deleting its identity record.
        self.assertNotEqual(stopped.returncode, 0)
        self.assertTrue((self.state / 'rust.pid').exists())
        self.assertIsNone(self.processes['rust'].poll())
        result = self.command('status')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Java service: UNVERIFIED', result.stdout)
        self.assertIn('saved process identity mismatch', result.stderr)
        self.assertIsNone(self.processes['java'].poll())

    def test_health_failure_does_not_mean_process_stopped(self):
        self.start_pair()
        self.helper.write_text(self.helper.read_text().replace(
            'ready() { ', 'ready() { [[ "$1" != *:8080/* ]] || return 1; ', 1))
        status = self.command('status')
        self.assertEqual(status.returncode, 1)
        self.assertIn('Java service: RUNNING', status.stdout)
        self.assertIn('Deterministic enforcement: INACTIVE', status.stdout)
        self.assertIn('health probe failed', status.stderr)

    def test_kernel_identity_matches_proc_start_ticks_even_with_parentheses(self):
        self.launch('java')
        pid = self.processes['java'].pid
        fields = Path(f'/proc/{pid}/stat').read_text().rsplit(') ', 1)[1].split()
        boot = Path('/proc/sys/kernel/random/boot_id').read_text().strip()
        self.assertEqual(self.shell('signature "$2"', pid).stdout.strip(), f'linux:{boot}:{fields[19]}')
        # Bash itself temporarily uses a comm containing spaces and parentheses.
        script = self.root / 'odd-comm.py'
        script.write_text('''import ctypes, os, subprocess
ctypes.CDLL(None).prctl(15, b'odd ) ( name)', 0, 0, 0)
subprocess.run(['bash', '-c', 'source "$1"; signature "$2"', 'test', os.environ['HELPER'], str(os.getpid())], check=True)
''')
        result = subprocess.run([sys.executable, str(script)], env={**self.env, 'HELPER': str(self.helper)},
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertRegex(result.stdout, r'^linux:[0-9a-f-]+:[0-9]+\n$')


    @unittest.skipUnless(os.environ.get('DCG_TEST_SERVICE_JAR') and os.environ.get('DCG_TEST_RUST_ROOT'),
                         'Set DCG_TEST_SERVICE_JAR and DCG_TEST_RUST_ROOT for real inference')
    def test_real_prediction_outage_preserves_java_and_cleanup(self):
        shutil.copytree(ROOT / 'contracts', self.package / 'contracts')
        (self.package / 'lib').mkdir()
        shutil.copyfile(os.environ['DCG_TEST_SERVICE_JAR'],
                        self.package / 'lib/contract-service-4.0.0-rc.1.jar')
        rust_root = Path(os.environ['DCG_TEST_RUST_ROOT'])
        shutil.copy2(rust_root / 'dcgaimodel', self.package / 'bin/dcgaimodel')
        shutil.copytree(rust_root / 'model', self.package / 'model')
        self.env.update(DCG_AI_ENABLED='true', DCG_AI_STARTUP_TIMEOUT_SECONDS='10')
        self.addCleanup(lambda: self.command('stop'))
        started = self.command('start')
        self.assertEqual(started.returncode, 0, started.stderr + started.stdout)
        self.assertIn('AI advisory: available', started.stdout)
        identity = (self.state / 'java.pid').read_bytes()
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        password = (self.data / 'password').read_text()
        auth = 'Basic ' + base64.b64encode(('demo:' + password).encode()).decode()

        def http(path, payload=None):
            request = urllib.request.Request('http://127.0.0.1:8080' + path,
                        data=json.dumps(payload).encode() if payload is not None else None,
                        headers={'Authorization': auth, 'Content-Type': 'application/json'})
            with opener.open(request, timeout=5) as response:
                return json.load(response)

        def check(event):
            submitted = http('/checks', {'contractId': 'orders.created', 'baseVersion': 'v1',
                                         'candidateVersion': 'v2', 'mode': 'BACKWARD',
                                         'commitSha': 'identity-regression', 'triggeredBy': 'test'})
            run_id = submitted['runId']
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                result = http('/checks/' + run_id)
                observed = any(f'event={event} ' in line and f'run_id={run_id} ' in line
                               for line in (self.data / 'logs/java.log').read_text().splitlines())
                if result['status'] in ('PASS', 'FAIL') and observed:
                    return {key: result[key] for key in ('status', 'breakingChanges', 'warnings')}
                time.sleep(.2)
            self.fail(f'No completed authoritative check with {event}')

        baseline = check('shadow_inference_prediction')
        self.change_calendar()
        stopped = self.shell('lock; stop_one rust')
        self.assertEqual(stopped.returncode, 0, stopped.stderr)
        status = self.command('status')
        self.assertEqual(status.returncode, 0, status.stdout + status.stderr)
        self.assertIn('Java service: RUNNING', status.stdout)
        self.assertIn('Deterministic enforcement: ACTIVE', status.stdout)
        self.assertIn('AI advisory mode: UNAVAILABLE', status.stdout)
        self.assertIn('Rust advisory process: STOPPED', status.stdout)
        restarted = self.command('start')
        self.assertEqual(restarted.returncode, 0, restarted.stdout + restarted.stderr)
        self.assertIn('already running', restarted.stdout)
        self.assertEqual(check('shadow_inference_call_failed'), baseline)
        self.assertEqual((self.state / 'java.pid').read_bytes(), identity)
        self.assertEqual(http('/actuator/health')['status'], 'UP')
        stopped = self.command('stop')
        self.assertEqual(stopped.returncode, 0, stopped.stdout + stopped.stderr)
        self.assertFalse(list(self.state.glob('*.pid')))
        for port in (8080, 8081):
            with socket.socket() as sock:
                self.assertNotEqual(sock.connect_ex(('127.0.0.1', port)), 0)


if __name__ == '__main__':
    unittest.main()
