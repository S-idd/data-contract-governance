#!/usr/bin/env python3
"""Opt-in real IEMS/Newman matrix rehearsal; not Ubuntu/WSL2 acceptance.

Uses a fresh private evidence directory, isolated Compose project/volumes, real
DCG CLI gates, and fixed loopback evaluation ports. Never reuses user databases.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import secrets
import shutil
import socket
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--iems-root', type=Path, required=True)
    parser.add_argument('--iems-jar', type=Path, required=True)
    parser.add_argument('--dcg-cli-jar', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    for port in (8090, 54329, 33069):
        with socket.socket() as sock:
            if sock.connect_ex(('127.0.0.1', port)) == 0:
                raise SystemExit(f'Port {port} occupied; leaving it untouched')
    evidence = args.evidence.resolve()
    evidence.mkdir(mode=0o700)  # Existing directories are rejected.
    bundle = evidence / 'bundle'
    shutil.copytree(ROOT / 'evaluation/ubuntu-24.04', bundle)
    for script_path in list((bundle / 'scripts').glob('*.sh')) + list((bundle / 'docker/init').glob('*.sh')):
        script_path.chmod(0o755)
    shutil.copytree(ROOT / 'packaging/local', bundle / 'dcg')
    (bundle / 'dcg/lib').mkdir()
    shutil.copy2(args.dcg_cli_jar, bundle / 'dcg/lib/contract-cli-4.0.0-rc.1-all.jar')
    (bundle / 'iems').mkdir()
    shutil.copy2(args.iems_jar, bundle / 'iems/iems.jar')
    for name in ('contracts', 'postman'):
        shutil.copytree(args.iems_root / name, bundle / 'iems' / name)
    shutil.copy2(ROOT / 'contracts/policy-packs.json', bundle / 'iems/contracts/policy-packs.json')
    shutil.copy2(ROOT / 'scripts/release/fixtures/iems/seed_notification.py', bundle / 'iems/postman')
    env = {**os.environ, 'COMPOSE_PROJECT_NAME': 'dcg-matrix-' + secrets.token_hex(6)}
    # Verify proxy bypass without changing registry access used by npx.
    env['NO_PROXY'] = env['no_proxy'] = ''
    summary = {'status': 'RUNNING', 'system': platform.platform(), 'architecture': platform.machine(),
               'scope': 'IEMS/API matrix rehearsal only; Ubuntu 24.04 WSL2 acceptance outstanding',
               'compose_project': env['COMPOSE_PROJECT_NAME'], 'checks': [], 'newman': []}
    summary['java'] = subprocess.check_output(['java', '-version'], stderr=subprocess.STDOUT, text=True)
    summary['input_sha256'] = {label: hashlib.sha256(path.read_bytes()).hexdigest() for label, path in
                               [('iems_jar', args.iems_jar), ('dcg_cli_jar', args.dcg_cli_jar),
                                ('collection', args.iems_root / 'postman/iems-api-collection.json')]}
    summary['iems_source_checkout_commit'] = subprocess.check_output(
        ['git', '-C', str(args.iems_root), 'rev-parse', 'HEAD'], text=True).strip()

    def run(name, command, expected=0):
        begin = time.monotonic()
        result = subprocess.run(command, env=env, cwd=bundle, capture_output=True, text=True, timeout=240)
        (evidence / (name + '.log')).write_text(result.stdout + result.stderr)
        summary['checks'].append({'check': name, 'exit_code': result.returncode,
                                  'status': 'PASS' if result.returncode == expected else 'FAIL',
                                  'seconds': round(time.monotonic() - begin, 2)})
        print(name, result.returncode, flush=True)
        if result.returncode != expected:
            raise RuntimeError(f'{name} failed; inspect private log')
        return result

    def script(name, *parameters):
        return [str(bundle / 'scripts' / name), *parameters]

    databases = iems = False
    try:
        databases = True
        run('database-up', script('database-up.sh'))
        for backend in ('sqlite', 'postgres', 'mysql'):
            iems = True
            run('start-iems-' + backend, script('start-iems.sh', backend))
            # Missing/wrong-backend selection must not write a fixture or call Newman.
            wrong = 'postgres' if backend == 'sqlite' else 'sqlite'
            run('reject-mismatch-' + backend, script('run-iems-postman.sh', wrong), expected=1)
            for attempt in (1, 2):
                name = f'newman-{backend}-{attempt}'
                # A deliberately unreachable proxy must not capture local API traffic.
                # npx is already cached after the first invocation.
                previous = {key: env.get(key) for key in ('HTTP_PROXY', 'http_proxy', 'HTTPS_PROXY', 'https_proxy', 'npm_config_offline')}
                if attempt == 2:
                    env.update({key: 'http://127.0.0.1:1' for key in previous if key != 'npm_config_offline'})
                    env['npm_config_offline'] = 'true'
                try:
                    run(name, script('run-iems-postman.sh', backend))
                finally:
                    for key, value in previous.items():
                        if value is None: env.pop(key, None)
                        else: env[key] = value
                report_path = max((bundle / 'workspace/evidence').glob(f'newman-{backend}-*/results.json'),
                                  key=lambda path: path.stat().st_mtime)
                report = json.loads(report_path.read_text())['run']
                item = {'backend': backend, 'attempt': attempt, 'assertions': report['stats']['assertions'],
                        'failures': len(report['failures'])}
                summary['newman'].append(item)
                if item['failures'] or not item['assertions']['total']:
                    raise RuntimeError(f'{name} did not complete the assertions')
            run('stop-iems-' + backend, script('stop-iems.sh'))
            iems = False
            with socket.socket() as sock:
                if sock.connect_ex(('127.0.0.1', 8090)) == 0:
                    raise RuntimeError('IEMS listener survived stop')
        summary['status'] = 'PASS'
    except Exception as exc:
        summary['status'] = 'FAIL'
        summary['error'] = str(exc)
    finally:
        try:
            if iems:
                run('cleanup-iems', script('stop-iems.sh'))
            if databases and (bundle / 'docker/.env').exists():
                run('cleanup-databases', ['docker', 'compose', '--env-file', str(bundle / 'docker/.env'),
                     '-f', str(bundle / 'docker/compose.yaml'), 'down', '--volumes'])
        except Exception as exc:
            summary['status'] = 'FAIL'
            summary['cleanup_error'] = str(exc)
        (evidence / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(summary['status'], evidence / 'summary.json', flush=True)
    return 0 if summary['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
