"""Isolated lifecycle regressions; uses real sockets/processes, never installed DCG."""
import http.server
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / 'packaging/local/bin/dcg'


class LauncherLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='dcg lifecycle ')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.state = self.root / 'state'
        self.state.mkdir()
        self.env = {**os.environ, 'DCG_DATA_DIR': str(self.state), 'COLUMNS': '80'}

    def shell(self, body, *args, timeout=45):
        return subprocess.run(['bash', '-c', 'source "$1"; STATE=$2; DATA=$2; ' + body,
                               'test', str(HELPER), str(self.state), *map(str, args)],
                              env=self.env, capture_output=True, text=True, timeout=timeout)

    def test_probe_ignores_proxy_environment_and_curlrc(self):
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200 if self.path == '/actuator/health' else 404)
                self.end_headers()
            def log_message(self, *args):
                pass
        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        # An inherited request method makes the old probe fail even when health is UP.
        (self.root / '.curlrc').write_text('request = "POST"\n')
        self.env.update({'CURL_HOME': str(self.root), 'NO_PROXY': '', 'no_proxy': ''})
        for name in ('http_proxy', 'HTTP_PROXY', 'https_proxy', 'HTTPS_PROXY', 'all_proxy', 'ALL_PROXY'):
            self.env[name] = 'http://127.0.0.1:1'
        url = f'http://127.0.0.1:{server.server_port}/actuator/health'
        before = subprocess.run(['curl', '--noproxy', '*', '-fsS', '--max-time', '2', url],
                                env=self.env, capture_output=True)
        self.assertNotEqual(before.returncode, 0)
        self.assertEqual(self.shell('ready "$3"', url).returncode, 0)
        failed = self.shell('alive() { return 0; }; wait_ready java "$3" 1', url + '/missing')
        self.assertNotEqual(failed.returncode, 0)
        self.assertIn('health HTTP 404', failed.stderr)

    def test_dead_process_returns_without_waiting_for_deadline(self):
        begin = time.monotonic()
        result = self.shell('wait_ready java http://127.0.0.1:1/actuator/health 120')
        self.assertNotEqual(result.returncode, 0)
        self.assertLess(time.monotonic() - begin, 3)
        self.assertIn('saved identity', result.stderr)

    def test_slow_probe_uses_elapsed_deadline(self):
        begin = time.monotonic()
        result = self.shell('alive() { return 0; }; ready() { sleep "$2"; return 1; }; '
                            'wait_ready java http://127.0.0.1:1/actuator/health 2')
        self.assertNotEqual(result.returncode, 0)
        self.assertLess(time.monotonic() - begin, 4)

    def managed_process(self, stuck):
        script = self.root / 'service.py'
        script.write_text('''import signal, socket, sys, time
s = socket.socket()
s.bind(('127.0.0.1', 0))
s.listen()
def stop(*args):
    print('Graceful shutdown complete', flush=True)
    if sys.argv[1] == 'False':
        sys.exit(0)
signal.signal(signal.SIGTERM, stop)
print(s.getsockname()[1], flush=True)
while True: time.sleep(.1)
''')
        process = subprocess.Popen([shutil.which('python3'), str(script), str(stuck),
                                    str(ROOT / 'packaging/local/lib/contract-service-4.0.0-rc.1.jar'),
                                    f'--spring.config.location=file:{self.state}/application-local-demo.properties'],
                                   stdout=subprocess.PIPE, text=True)
        def cleanup():
            if process.poll() is None:
                process.kill()
            process.wait()
            process.stdout.close()
        self.addCleanup(cleanup)
        port = int(process.stdout.readline())
        result = self.shell('printf "%s\\n%s\\n" "$3" "$(signature "$3")" > "$STATE/java.pid"; alive java', process.pid)
        self.assertEqual(result.returncode, 0, result.stderr)
        return process, port

    def test_graceful_stop_and_repeated_stop(self):
        process, port = self.managed_process(False)
        result = self.shell('stop_one java; stop_one java')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn('sending KILL', result.stderr)
        process.wait(timeout=2)
        with socket.socket() as sock:
            self.assertNotEqual(sock.connect_ex(('127.0.0.1', port)), 0)
        self.assertFalse((self.state / 'java.pid').exists())

    def test_stuck_shutdown_escalates_only_owned_process(self):
        process, port = self.managed_process(True)
        sentinel = subprocess.Popen(['sleep', '90'])
        self.addCleanup(lambda: (sentinel.terminate(), sentinel.wait()))
        result = self.shell('stop_one java')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('sending KILL to verified PID', result.stderr)
        self.assertIn('Graceful shutdown complete', process.stdout.readline())
        self.assertEqual(process.wait(timeout=2), -9)
        self.assertIsNone(sentinel.poll())
        with socket.socket() as sock:
            self.assertNotEqual(sock.connect_ex(('127.0.0.1', port)), 0)
        self.assertFalse((self.state / 'java.pid').exists())

    def test_reused_identity_is_not_signalled(self):
        process, _ = self.managed_process(True)
        (self.state / 'java.pid').write_text(f'{process.pid}\nwrong start time\n')
        self.assertEqual(self.shell('stop_one java').returncode, 0)
        self.assertIsNone(process.poll())


    @unittest.skipUnless(os.environ.get('DCG_TEST_SERVICE_JAR'), 'Set DCG_TEST_SERVICE_JAR for real Java lifecycle')
    def test_real_java_captured_start_stop(self):
        with socket.socket() as sock:
            if sock.connect_ex(('127.0.0.1', 8080)) == 0:
                self.skipTest('Port 8080 is occupied')
        package = self.root / 'package'
        shutil.copytree(ROOT / 'packaging/local', package)
        shutil.copytree(ROOT / 'contracts', package / 'contracts')
        (package / 'lib').mkdir()
        shutil.copyfile(os.environ['DCG_TEST_SERVICE_JAR'],
                        package / 'lib/contract-service-4.0.0-rc.1.jar')
        self.env.update({'DCG_AI_ENABLED': 'false', 'http_proxy': 'http://127.0.0.1:1',
                         'HTTPS_PROXY': 'http://127.0.0.1:1', 'NO_PROXY': '', 'no_proxy': ''})
        def command(name):
            return subprocess.run([str(package / 'bin' / name)], env=self.env,
                                  capture_output=True, text=True, timeout=160)
        self.addCleanup(lambda: command('stop'))
        for _ in range(2):
            started = command('start')
            self.assertEqual(started.returncode, 0, started.stdout + started.stderr)
            self.assertIn('DCG ready:', started.stdout)
            self.assertEqual(command('status').returncode, 0)
            stopped = command('stop')
            self.assertEqual(stopped.returncode, 0, stopped.stdout + stopped.stderr)
            self.assertFalse((self.state / 'run/java.pid').exists())
            with socket.socket() as sock:
                self.assertNotEqual(sock.connect_ex(('127.0.0.1', 8080)), 0)
        with socket.socket() as sentinel:
            sentinel.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sentinel.bind(('127.0.0.1', 8080))
            sentinel.listen()
            stopped = command('stop')
            self.assertNotEqual(stopped.returncode, 0)
            self.assertIn('still has a listener', stopped.stderr)
            self.assertEqual(sentinel.getsockname()[1], 8080)


if __name__ == '__main__':
    unittest.main()
