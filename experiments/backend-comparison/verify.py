#!/usr/bin/env python3
"""Bounded actual application run; validates page/bridge report and process exit."""
import argparse
import os
import signal
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('backend', choices=['ffm', 'javafx'])
parser.add_argument('mode', choices=['jvm', 'native'])
args = parser.parse_args()
os.chdir(Path(__file__).resolve().parent)
backend_name = 'ffm-system-webview' if args.backend == 'ffm' else 'javafx-webview'
target = Path('target') / args.backend
target.mkdir(parents=True, exist_ok=True)
command = ['./run-jvm.sh', args.backend] if args.mode == 'jvm' else [str((target / 'dist' / (args.backend + '-comparison')).resolve())]
environment = None
working_directory = None
if args.mode == 'native':
    working_directory = Path(tempfile.mkdtemp(prefix='isolated-', dir=target)).resolve()
    binary = working_directory / command[0].split('/')[-1]
    shutil.copy2(command[0], binary)
    temporary = working_directory / 'tmp'
    temporary.mkdir()
    command = [str(binary), '-Djava.io.tmpdir=' + str(temporary),
               '-Djavafx.cachedir=' + str(working_directory / 'cache')]
    environment = {'PATH': '/usr/bin:/bin', 'TMPDIR': str(temporary) + '/'}
try:
    with (target / (args.mode + '-run.log')).open('w') as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT,
                                   cwd=working_directory, env=environment, start_new_session=True)
        if args.mode == 'native':
            deadline = time.monotonic() + 20
            while process.poll() is None and time.monotonic() < deadline:
                if '[panamaview] page loaded:' in (target / 'native-run.log').read_text():
                    with (target / 'loaded-libraries.txt').open('w') as maps:
                        subprocess.run(['/usr/bin/vmmap', '-w', str(process.pid)],
                                       stdout=maps, stderr=subprocess.STDOUT, check=True, timeout=10)
                    break
                time.sleep(0.02)
        process.wait(timeout=55)
    output, code = (target / (args.mode + '-run.log')).read_text(), process.returncode
except subprocess.TimeoutExpired as error:
    os.killpg(process.pid, signal.SIGKILL)
    process.wait()
    output = (target / (args.mode + '-run.log')).read_text()
    output += '\nFAIL external timeout\n'
    code = 124
(target / (args.mode + '-run.log')).write_text(output)
print(output, end='')
print('process_exit=' + str(code))
marker = '[comparison] backend=' + backend_name + ' passed=12 failed=0'
passed = code == 0 and marker in output
if args.mode == 'native' and passed:
    maps = (target / 'loaded-libraries.txt').read_text()
    passed = 'libjvm.dylib' not in maps and 'JavaVirtualMachines/' not in maps
    if args.backend == 'ffm':
        passed = passed and 'libjfxwebkit.dylib' not in maps and 'libglass.dylib' not in maps
    else:
        passed = passed and 'libjfxwebkit.dylib' in maps and 'libglass.dylib' in maps
    print('relocated_package_and_libraries=' + ('PASS' if passed else 'FAIL'))
raise SystemExit(0 if passed else 1)
