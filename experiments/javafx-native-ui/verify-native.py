#!/usr/bin/env python3
"""Run the real native controls program from a relocated one-file package."""
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

base = Path.cwd()
isolated = Path(tempfile.mkdtemp(prefix="isolated-ui-", dir=base / "target"))
binary = isolated / "javafx-native-ui"
shutil.copy2(base / "target/dist/javafx-native-ui", binary)
temp = isolated / "tmp"
temp.mkdir()
log_path = base / "target/native-run.log"
maps_path = base / "target/loaded-libraries.txt"
with log_path.open("w") as log:
    process = subprocess.Popen([
        str(binary), "-Djavafx.cachedir=" + str(isolated / "cache"),
        "-Djava.io.tmpdir=" + str(temp), "-Dpoc.snapshot=" + str(base / "target/native.ppm"),
    ], cwd=isolated, env={"PATH": "/usr/bin:/bin", "TMPDIR": str(temp) + "/"},
       stdout=log, stderr=subprocess.STDOUT)
    print(f"[ui-verifier] PID={process.pid} isolated={isolated}", flush=True)
    try:
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline and process.poll() is None:
            if "PASS control-state=" in log_path.read_text():
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
required = ["image=runtime", "PASS stage-showing=true", "PASS button-event on-fx-thread=true count=1",
            "PASS control-state=Action count: 1 · Native UI verified", "PASS snapshot=780x420",
            "close-request", "stop closeRequested=true exit=0", "launch-returned exit=0"]
assert process.returncode == 0 and all(marker in output for marker in required), "Native control/exit test failed"
maps = maps_path.read_text()
assert "libglass.dylib" in maps, "JavaFX native window library missing"
for forbidden in ("libjfxwebkit.dylib", "libjvm.dylib", "JavaVirtualMachines/"):
    assert forbidden not in maps, "Unexpected dependency: " + forbidden
print("[ui-verifier] PASS relocated native UI, no WebKit/JVM/JDK mapped, normal exit")
