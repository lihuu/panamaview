#!/usr/bin/env python3
"""Experimental, explicit patch of a copied JavaFX 25.0.2 mac-arm64 library.

The shipped library has a required load command for libjvm without JVM symbol imports.
Weaken that single load command, ad-hoc sign the copy, and create a separate JAR.
Never modify the downloaded Maven artifact or any installed JavaFX/JDK.
"""
import hashlib
import re
import struct
import subprocess
import zipfile
from pathlib import Path

target = Path("target")
classpath = (target / "classpath.txt").read_text().strip().split(":")
source = next(Path(p) for p in classpath if p.endswith("javafx-web-25.0.2-mac-aarch64.jar"))
patch_dir = target / "patched-webkit"
patch_dir.mkdir(exist_ok=True)
library = patch_dir / "libjfxwebkit.dylib"
with zipfile.ZipFile(source) as jar:
    original = jar.read("libjfxwebkit.dylib")
library.write_bytes(original)
symbols = subprocess.check_output(["nm", "-u", str(library)], text=True)
if re.search(r"\b_(?:JNI_|JVM_|JNU_)", symbols):
    raise SystemExit("Refusing patch: library has JVM symbol imports")
(patch_dir / "undefined-symbols.txt").write_text(symbols)
data = bytearray(original)
magic, cpu, _, _, commands, _, _, _ = struct.unpack_from("<8I", data)
if magic != 0xFEEDFACF or cpu != 0x0100000C:
    raise SystemExit("Expected thin arm64 Mach-O")
offset = 32
changed = 0
for _ in range(commands):
    command, size = struct.unpack_from("<II", data, offset)
    if size < 8 or offset + size > len(data):
        raise SystemExit("Invalid Mach-O load command")
    if command == 0xC:  # LC_LOAD_DYLIB
        name_offset = struct.unpack_from("<I", data, offset + 8)[0]
        name = data[offset + name_offset:offset + size].split(b"\0", 1)[0]
        if name == b"@rpath/libjvm.dylib":
            struct.pack_into("<I", data, offset, 0x80000018)  # LC_LOAD_WEAK_DYLIB
            changed += 1
    offset += size
if changed != 1:
    raise SystemExit(f"Expected one libjvm dependency, found {changed}")
library.write_bytes(data)
subprocess.run(["codesign", "--force", "--sign", "-", str(library)], check=True)
patched = library.read_bytes()
output = patch_dir / source.name
with zipfile.ZipFile(source) as jar, zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as result:
    for entry in jar.infolist():
        result.writestr(entry, patched if entry.filename == "libjfxwebkit.dylib" else jar.read(entry.filename))
new_classpath = [str(output.resolve()) if Path(p) == source else p for p in classpath]
(target / "classpath-patched.txt").write_text(":".join(new_classpath))
print("Experimental patch: LC_LOAD_DYLIB → LC_LOAD_WEAK_DYLIB for @rpath/libjvm.dylib")
print("Original library SHA256:", hashlib.sha256(original).hexdigest())
print("Patched library SHA256:", hashlib.sha256(patched).hexdigest())
print("Patched Maven artifact copy:", output)
