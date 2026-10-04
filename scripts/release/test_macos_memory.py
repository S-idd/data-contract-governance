"""Numeric boundary, missing-evidence and process-selection tests; no memory stress."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / 'evaluation/macos-arm64/scripts/memory_telemetry.py'
spec = importlib.util.spec_from_file_location('mac_memory', MODULE)
mem = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mem)


def reading(level=1, swap=0, swapouts=0, page=16384):
    return {'pressure_level': level, 'swap_used_bytes': swap, 'page_size_bytes': page,
            'vm_counters': {'Swapouts': swapouts}}


def fixture():
    samples = []
    for second in range(41):
        processes = [] if second < 30 else [
            {'pid': 101, 'ppid': 100, 'role': 'cli', 'rss_kib': 102400},
            {'pid': 102, 'ppid': 1, 'role': 'java', 'rss_kib': 204800},
            {'pid': 103, 'ppid': 1, 'role': 'rust', 'rss_kib': 10240}]
        samples.append({'elapsed_seconds': second, 'phase': 'baseline' if second < 30 else 'workload',
                        'combined_rss_kib': sum(p['rss_kib'] for p in processes),
                        'processes': processes, 'memory': reading()})
    return {'synthetic': True, 'functional_status': 'PASS', 'host': {'is_8gb_target': True},
            'telemetry': {'baseline_seconds': 30, 'samples': samples, 'errors': []},
            'memory_before': reading(), 'memory_after': reading()}


def classify(data):
    return mem.classify_memory(data.get('telemetry'), data.get('memory_before'), data.get('memory_after'),
                               data['functional_status'], data['host']['is_8gb_target'])


def cases():
    result = []
    def case(name, expected, edit=lambda d: None):
        d = fixture()
        edit(d)
        result.append((name, expected, d))

    def rss(d, kib):
        s = d['telemetry']['samples'][-1]
        s['processes'] = [{'pid': 9, 'ppid': 1, 'role': 'java', 'rss_kib': kib}]
        s['combined_rss_kib'] = kib

    case('normal_8gb', 'PASS')
    case('exact_1gib_rss', 'PASS', lambda d: rss(d, 1024*1024))
    case('above_1gib_rss', 'REVIEW', lambda d: rss(d, 1024*1024+1))
    case('just_below_1536mib_rss', 'REVIEW', lambda d: rss(d, 1536*1024-1))
    case('exact_1536mib_rss', 'FAIL', lambda d: rss(d, 1536*1024))
    for amount, expected in [(128, 'PASS'), (129, 'REVIEW'), (255, 'REVIEW'), (256, 'FAIL')]:
        case(f'swap_used_{amount}mib', expected,
             lambda d, n=amount: d['memory_after'].update(swap_used_bytes=n*mem.MIB))
        case(f'swapout_{amount}mib', expected,
             lambda d, n=amount: d['memory_after']['vm_counters'].update(Swapouts=n*mem.MIB//16384))
    case('transient_swap_then_recovered', 'FAIL',
         lambda d: d['telemetry']['samples'][32]['memory'].update(swap_used_bytes=256*mem.MIB))
    case('single_critical', 'FAIL', lambda d: d['telemetry']['samples'][34]['memory'].update(pressure_level=4))
    case('transient_warning', 'REVIEW', lambda d: d['telemetry']['samples'][34]['memory'].update(pressure_level=2))
    case('warning_4_seconds', 'REVIEW',
         lambda d: [s['memory'].update(pressure_level=2) for s in d['telemetry']['samples'][30:35]])
    case('warning_exact_5_seconds', 'FAIL',
         lambda d: [s['memory'].update(pressure_level=2) for s in d['telemetry']['samples'][30:36]])
    case('pressured_baseline', 'REVIEW', lambda d: d['telemetry']['samples'][8]['memory'].update(pressure_level=2))
    case('critical_baseline_only', 'REVIEW', lambda d: d['telemetry']['samples'][8]['memory'].update(pressure_level=4))
    case('short_baseline', 'REVIEW', lambda d: d['telemetry'].update(baseline_seconds=29.9))
    case('no_samples', 'REVIEW', lambda d: d['telemetry'].update(samples=[]))
    case('sampling_error', 'REVIEW', lambda d: d['telemetry']['errors'].append({'error':'ps failed'}))
    case('long_gap', 'REVIEW', lambda d: d['telemetry']['samples'].__delitem__(slice(32,35)))
    case('unknown_pressure_level', 'REVIEW', lambda d: d['telemetry']['samples'][33]['memory'].update(pressure_level=0))
    case('missing_pressure', 'REVIEW', lambda d: d['telemetry']['samples'][33]['memory'].pop('pressure_level'))
    case('counter_reset', 'REVIEW', lambda d: d['memory_before']['vm_counters'].update(Swapouts=100))
    case('page_size_changed', 'REVIEW', lambda d: d['memory_after'].update(page_size_bytes=4096))
    case('missing_after', 'REVIEW', lambda d: d.update(memory_after=None))
    case('fail_overrides_missing_data', 'FAIL', lambda d: (rss(d, 1536*1024),d.update(memory_after=None)))
    case('missing_cli_coverage', 'REVIEW', lambda d: [p.update(role='helper') for s in d['telemetry']['samples'] for p in s['processes'] if p['role']=='cli'])
    case('duplicate_pid', 'REVIEW', lambda d: d['telemetry']['samples'][-1]['processes'][0].update(pid=102))
    case('sum_mismatch', 'REVIEW', lambda d: d['telemetry']['samples'][-1].update(combined_rss_kib=1))
    case('negative_rss', 'REVIEW', lambda d: d['telemetry']['samples'][-1]['processes'][0].update(rss_kib=-1))
    case('larger_host_metrics_pass_not_8gb', 'PASS', lambda d: d['host'].update(is_8gb_target=False))
    return result


class MemoryQualificationTests(unittest.TestCase):
    def test_all_numeric_and_missing_data_cases(self):
        for name, expected, data in cases():
            with self.subTest(name=name):
                self.assertEqual(classify(data)['memory_qualification'], expected)

    def test_larger_host_never_claims_8gb(self):
        data=fixture(); data['host']['is_8gb_target']=False
        result=classify(data)
        self.assertEqual(result['memory_qualification'],'PASS')
        self.assertEqual(result['eight_gb_qualification'],'NOT_TARGET')

    def test_functional_failure_cannot_pass_overall(self):
        data=fixture(); data['functional_status']='FAIL'
        self.assertEqual(classify(data)['status'],'FAIL')
        self.assertEqual(classify(data)['memory_qualification'],'REVIEW')

    def test_simultaneous_sum_not_sum_of_independent_peaks(self):
        data=fixture()
        for i,s in enumerate(data['telemetry']['samples'][30:]):
            s['processes'][0]['rss_kib']=0
            s['processes'][1]['rss_kib']=900*1024 if i%2 else 10*1024
            s['processes'][2]['rss_kib']=10*1024 if i%2 else 900*1024
            s['combined_rss_kib']=sum(p['rss_kib'] for p in s['processes'])
        self.assertEqual(classify(data)['memory_qualification'],'PASS')
        self.assertEqual(classify(data)['memory_metrics']['peak_combined_rss_bytes'],910*mem.MIB)

    def test_children_deduplicated_reparenting_and_pid_reuse(self):
        def row(pid, ppid, start, command='java'):
            return {'pid':pid,'ppid':ppid,'rss_kib':10,'start':start,'command':command}
        rows={1:row(1,0,'init'),10:row(10,1,'parent'),11:row(11,10,'child'),12:row(12,11,'grandchild'),99:row(99,1,'unrelated')}
        selected,known=mem.select_workload(rows,{10:('parent','cli-pass'),11:('child','cli-pass')},{})
        self.assertEqual([p['pid'] for p in selected],[10,11,12])
        rows.pop(10); rows[11]['ppid']=1
        selected,known=mem.select_workload(rows,{},known)
        self.assertEqual([p['pid'] for p in selected],[11,12])
        rows[11]['start']='reused'
        selected,_=mem.select_workload(rows,{},known)
        self.assertEqual([p['pid'] for p in selected],[12])

    def test_native_parser_uses_reported_page_size(self):
        raw='Mach Virtual Memory Statistics: (page size of 4096 bytes)\nSwapouts: 33.\nPages free: 123.\n'
        reading=mem.parse_memory('free 80%', 'vm.swapusage: total = 1.00G used = 256.00M free = 0.75G',raw,'2')
        self.assertEqual(reading['swap_used_bytes'],256*mem.MIB)
        self.assertEqual(reading['vm_counters']['Swapouts'],33)
        self.assertEqual(reading['pressure_state'],'warn')
        with self.assertRaises(ValueError): mem.parse_memory('', 'bad swap',raw,'1')
        with self.assertRaises(ValueError): mem.parse_memory('', 'used = 0.00M',raw,'0')

    def test_command_line_replay_pass_review_fail_and_exit_codes(self):
        selected={'normal_8gb':0,'above_1gib_rss':2,'exact_1536mib_rss':1}
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            for name,expected,data in cases():
                if name not in selected: continue
                source=root/(name+'.json'); source.write_text(json.dumps(data))
                dest=root/(name+'-result.json')
                run=subprocess.run([sys.executable,str(MODULE),'--classify-report',str(source),'--output',str(dest)],capture_output=True,text=True)
                self.assertEqual(run.returncode,selected[name],run.stderr)
                output=json.loads(dest.read_text())
                self.assertEqual(output['memory_qualification'],expected)
                self.assertTrue(output['synthetic_input'])
                self.assertEqual(output['classification_source'],'offline-replay-not-a-live-acceptance-run')


if __name__=='__main__': unittest.main()
