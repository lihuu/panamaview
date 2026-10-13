#!/usr/bin/env python3
"""Relocate only the executable; run with no Java paths, trace loaded dylibs, and verify exit."""
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

base = Path.cwd()
standalone = Path(tempfile.mkdtemp(prefix="isolated-package-", dir=base / "target"))
binary = standalone / "javafx-webview-poc"
shutil.copy2(base / "target/dist/javafx-webview-poc", binary)
cache = standalone / "runtime-cache"
temp = standalone / "tmp"
temp.mkdir()
log_path = base / "target/standalone-run.log"
maps_path = base / "target/standalone-loaded-libraries.txt"
environment = {"PATH": "/usr/bin:/bin", "TMPDIR": str(temp) + "/"}
with log_path.open("w") as log:
    process = subprocess.Popen([
        str(binary), "-Djavafx.cachedir=" + str(cache), "-Dpoc.closeDelayMillis=5000",
        "-Djava.io.tmpdir=" + str(temp),
        "-Dpoc.snapshot=" + str(base / "target/standalone.ppm"),
    ], cwd=standalone, env=environment, stdout=log, stderr=subprocess.STDOUT)
    print(f"[standalone] PID={process.pid} package={standalone}", flush=True)
    try:
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline and process.poll() is None:
            if "PASS javascript-to-java-return=" in log_path.read_text():
                break
            time.sleep(0.05)
        with maps_path.open("w") as maps:
            subprocess.run(["/usr/bin/vmmap", "-w", str(process.pid)], stdout=maps,
                           stderr=subprocess.STDOUT, timeout=10, check=True)
        process.wait(timeout=20)
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()
output = log_path.read_text()
print(output, end="")
maps = maps_path.read_text()
required = ["image=runtime", "PASS external-css=", "PASS java-to-javascript=",
            "PASS javascript-to-java-return=", "PASS assets-removed=true", "launch-returned exit=0"]
assert process.returncode == 0 and all(item in output for item in required), "Native selftest failed"
assert "libjfxwebkit.dylib" in maps, "WebKit was not present in runtime mappings"
assert "libjvm.dylib" not in maps, "Unexpected JVM library in runtime mappings"
assert "JavaVirtualMachines/" not in maps, "Unexpected installed-JDK dependency"
assert not list(temp.glob("javafx-webview-poc-*")), "Application resources were not cleaned up"
print("[standalone] PASS relocated binary, minimal environment, no JVM/JDK mapped, clean exit")
print("[standalone] initial package contained only one executable; caches extracted at runtime")
(base / "target/standalone-summary.txt").write_text(
    f"package={standalone}\nexecutable_bytes={binary.stat().st_size}\n"
    "environment=PATH:/usr/bin:/bin;TMPDIR:isolated/tmp\nexit=0\n"
    "libjfxwebkit mapped=yes\nlibjvm mapped=no\ninstalled JDK mapped=no\n"
    "scope=environment/filesystem relocation on a host that still has JDKs installed; not a clean-machine test\n")
