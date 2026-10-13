#!/usr/bin/env python3
"""Bounded process-level verification; inspect the executable's actual assertions."""
import argparse
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--log", required=True)
parser.add_argument("--timeout", type=float, default=45)
parser.add_argument("command", nargs=argparse.REMAINDER)
args = parser.parse_args()
command = args.command[1:] if args.command[:1] == ["--"] else args.command
timed_out = False
with Path(args.log).open("w") as log:
    process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, text=True)
    print(f"[runner] PID={process.pid} log={args.log}", flush=True)
    try:
        process.wait(timeout=args.timeout)
    except subprocess.TimeoutExpired:
        timed_out = True
        process.kill()
        process.wait()
        log.write("\n[runner] FAIL process did not terminate within deadline\n")
output = Path(args.log).read_text()
print(output, end="")
if timed_out:
    raise SystemExit(2)
expected = [
    "PASS stage-showing=true", "PASS local-html=", "PASS external-script=",
    "PASS external-css=", "PASS java-to-javascript=", "PASS javascript-to-java-return=",
    "PASS java-callback-count=1", "PASS rendered-page-state=", "close-request",
    "PASS assets-removed=true", "stop closeRequested=true exit=0", "launch-returned exit=0",
]
missing = [marker for marker in expected if marker not in output]
print(f"[runner] exit={process.returncode} missing={missing}")
raise SystemExit(0 if process.returncode == 0 and not missing else 1)
