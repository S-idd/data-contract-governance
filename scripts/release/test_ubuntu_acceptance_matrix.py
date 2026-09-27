"""Report/orchestration tests; real backend runs use the separate rehearsal."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[2] / 'evaluation/ubuntu-24.04/scripts/ubuntu-wsl2-acceptance.py'
spec = importlib.util.spec_from_file_location('acceptance', SCRIPT)
acceptance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(acceptance)


class AcceptanceMatrixTests(unittest.TestCase):
    def rehearsal(self, failing=None):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            bundle = root / 'bundle'
            (bundle / 'examples/contracts/orders.created').mkdir(parents=True)
            (bundle / 'workspace/contracts').mkdir(parents=True)
            evidence = root / 'evidence'
            commands = []
            mode = 'false'
            def run(command, **kwargs):
                nonlocal mode
                name = Path(command[0]).name
                args = command[1:]
                commands.append((name, args))
                if name == 'start-dcg.sh':
                    mode = kwargs['env']['DCG_AI_ENABLED']
                output = ''
                if name == 'status-dcg.sh':
                    output = 'Deterministic enforcement: ACTIVE\nAI advisory mode: ' + ('DISABLED' if mode == 'false' else 'AVAILABLE')
                failure = name == 'run-iems-postman.sh' and args == [failing]
                return subprocess.CompletedProcess(command, int(failure), output)
            original_read = Path.read_text
            def read(path, *args, **kwargs):
                if str(path) == '/proc/sys/kernel/osrelease':
                    return 'microsoft-standard-WSL2'
                return original_read(path, *args, **kwargs)
            with patch.object(acceptance, 'BUNDLE', bundle), patch.object(acceptance, 'SCRIPTS', bundle / 'scripts'), \
                 patch.object(acceptance.platform, 'system', return_value='Linux'), \
                 patch.object(acceptance.platform, 'machine', return_value='x86_64'), \
                 patch.object(acceptance, 'os_release', return_value={'ID': 'ubuntu', 'VERSION_ID': '24.04'}), \
                 patch.object(acceptance, 'port_free', return_value=True), \
                 patch.object(acceptance.subprocess, 'run', side_effect=run), \
                 patch.object(Path, 'read_text', read), \
                 patch('sys.argv', [str(SCRIPT), '--evidence', str(evidence)]):
                result = acceptance.main()
            return result, json.loads((evidence / 'results.json').read_text()), commands

    def test_complete_matrix_has_23_checks_and_explicit_api_results(self):
        result, report, commands = self.rehearsal()
        self.assertEqual(result, 0)
        self.assertEqual(report['check_count'], 23)
        self.assertEqual(report['api_database_matrix'], dict.fromkeys(('sqlite', 'postgres', 'mysql'), 'PASS'))
        self.assertEqual([check['check'] for check in report['checks'] if check['check'].startswith('newman-')],
                         ['newman-iems-api', 'newman-iems-postgres', 'newman-iems-mysql'])
        for backend in ('sqlite', 'postgres', 'mysql'):
            start = commands.index(('start-iems.sh', [backend]))
            self.assertEqual(commands[start + 1], ('run-iems-postman.sh', [backend]))
            self.assertEqual(commands[start + 2], ('stop-iems.sh', []))

    def test_postgres_api_failure_is_not_hidden_by_startup_or_cleanup(self):
        result, report, commands = self.rehearsal('postgres')
        self.assertEqual(result, 1)
        self.assertNotEqual(report['status'], 'PASS')
        self.assertEqual(report['api_database_matrix'], {'sqlite': 'PASS', 'postgres': 'FAIL', 'mysql': 'NOT_RUN'})
        self.assertEqual(report['checks'][-1]['check'], 'newman-iems-postgres')
        self.assertEqual(report['cleanup'], [{'action': name, 'status': 'PASS'}
                         for name in ('stop-iems', 'stop-dcg', 'database-down')])
        self.assertNotIn(('start-iems.sh', ['mysql']), commands)


if __name__ == '__main__':
    unittest.main()
