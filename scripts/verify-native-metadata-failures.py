#!/usr/bin/env python3
"""Prove that the native-image metadata check fails when it should.

The check (scripts/verify-native-metadata.py) is the last thing standing between a
broken native build and a shipped binary: it is what notices that the code builds a
native shape the metadata does not register, and a native build that misses a shape
dies at run time, on the machine that built the binary. A check whose own failures
have only ever been demonstrated by hand is a check whose failures evaporate -- the
evidence was in a reviewer's message and a pair of terminal windows, and neither is a
test.

So this drives it. Each case below breaks one thing in a copy of the tree, runs the
check against the copy, and insists that the check fails *and says why*: a check
that exits 1 for an unrelated reason satisfies "it fails" and proves nothing.

The fixtures are the check's own failure modes, reproduced on files this driver
writes itself, and they do not depend on what else is in the sources. A fixture that
broke because somebody added a menu item would be worse than no fixture at all,
because it would fail for a reason nobody had anything to do with. Where a case can
be staged, the tree is checked *before* the break as well, so that the break is
shown to be the cause.

Usage: verify-native-metadata-failures.py
"""

import json
import pathlib
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
CHECK = "verify-native-metadata.py"
PACKAGE_DIR = "framework/src/main/java/io/github/lihuu/panamaview/macos"
PACKAGE_NAME = "io.github.lihuu.panamaview.macos"
METADATA = ("framework/src/main/resources/META-INF/native-image/"
            "io.github.lihuu/panamaview/reachability-metadata.json")

# The Java layout each native shape is written with. This is the check's own
# vocabulary: a driver that knew fewer carriers than the check would crash on a
# metadata the check tolerates, and a CI step that crashes on a metadata that grew
# is a CI step that stops people from growing the metadata.
JAVA_LAYOUTS = {
    "void*": "ADDRESS",
    "jbyte": "JAVA_BYTE",
    "jshort": "JAVA_SHORT",
    "jint": "JAVA_INT",
    "jlong": "JAVA_LONG",
    "jfloat": "JAVA_FLOAT",
    "jdouble": "JAVA_DOUBLE",
    "jboolean": "JAVA_BOOLEAN",
}

# The shapes the driver can build: a run of pointers. It takes the first one the
# metadata does not already register, so a metadata that grows does not break the
# fixture. The run is long enough that it cannot be exhausted.
SHAPE_SIZES = range(2, 64)

passed = 0
failed = 0


def ok(label):
    global passed
    passed += 1
    print("  PASS  %s" % label)


def bad(label, detail=""):
    global failed
    failed += 1
    print("  FAIL  %s" % label)
    if detail:
        for line in detail.splitlines():
            print("        %s" % line)


def say(title):
    print("\n=== %s" % title)


# ------------------------------------------------------------------ the copy


def make_tree(work, name):
    """A copy of the tree the check can run against."""
    root = work / name
    shutil.copytree(ROOT / "framework", root / "framework",
                    ignore=shutil.ignore_patterns("target"))
    (root / "scripts").mkdir(parents=True)
    shutil.copy(ROOT / "scripts" / CHECK, root / "scripts" / CHECK)
    return root


def run_check(root):
    """(exit code, output) of the check against a copy of the tree."""
    done = subprocess.run([sys.executable, str(root / "scripts" / CHECK)],
                          capture_output=True, text=True)
    return done.returncode, done.stdout + done.stderr


def expect_clean(label, root):
    """The tree must be clean. A fixture that breaks it another way proves nothing."""
    code, output = run_check(root)
    if code != 0:
        bad(label, "the tree should have been clean:\n" + output)
        return False
    ok(label)
    return True


def expect_failure(label, root, wanted):
    """The check must fail, and say why."""
    code, output = run_check(root)
    if code == 0:
        bad(label, "the check passed:\n" + output)
        return
    if wanted not in output:
        bad(label, "the check failed, but not for this reason:\n" + output)
        return
    ok(label)
    for line in output.splitlines():
        if wanted in line:
            print("        %s" % line.strip())


# ------------------------------------------------------------------ the files


def write_java(root, name, text):
    """A Java file in the macos package of the copy."""
    (root / PACKAGE_DIR / name).write_text(text, encoding="utf-8")


def read_metadata(root):
    return json.loads((root / METADATA).read_text(encoding="utf-8"))


def write_metadata(root, metadata):
    (root / METADATA).write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")


def registered_shapes(root):
    """The shapes the metadata registers, as the strings the check prints."""
    metadata = read_metadata(root)
    return {entry["returnType"] + "(" + ",".join(entry["parameterTypes"]) + ")"
            for entry in metadata["foreign"]["downcalls"]}


def java_descriptor(shape):
    """The Java that builds a shape, or None when the driver cannot write it.

    A by-value aggregate cannot be written here, and neither can a carrier the
    driver does not know. Returning None rather than raising is the point: the
    driver then picks another shape, where a crash would stop the whole run.
    """
    return_type, _, parameters = shape.partition("(")
    parameters = parameters.rstrip(")").split(",") if parameters else []
    if "struct(" in return_type or any("struct(" in p for p in parameters):
        return None
    layouts = parameters if return_type == "void" else [return_type] + parameters
    if any(layout not in JAVA_LAYOUTS for layout in layouts):
        return None
    if return_type == "void":
        return "FunctionDescriptor.ofVoid(%s)" % ", ".join(JAVA_LAYOUTS[l] for l in layouts)
    return "FunctionDescriptor.of(%s)" % ", ".join(JAVA_LAYOUTS[l] for l in layouts)


def a_registered_shape(root):
    """A registered shape the driver can write in Java, or None."""
    for shape in sorted(registered_shapes(root)):
        if java_descriptor(shape) is not None:
            return shape
    return None


def an_unregistered_shape(root):
    """A shape the metadata does not register, and the Java that builds it."""
    registered = registered_shapes(root)
    for size in SHAPE_SIZES:
        shape = "void*(" + ",".join(["void*"] * size) + ")"
        if shape not in registered:
            return shape, java_descriptor(shape)
    return None, None


def a_framework_upcall(metadata):
    """The first directUpcalls entry that names a class in the package.

    The check does not look at an entry whose class is outside the package -- a JDK
    class is the JDK's business -- so a break aimed at one of those would change
    nothing, and a fixture that breaks nothing is worse than no fixture.
    """
    for entry in metadata["foreign"]["directUpcalls"]:
        if entry["class"].startswith(PACKAGE_NAME + "."):
            return entry
    return None


SHAPE_SOURCE = """package io.github.lihuu.panamaview.macos;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MethodHandle;
import java.lang.foreign.SymbolLookup;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * A fixture: a native shape, and a downcall that uses it.
 */
final class FixtureShape {{

    private static final FunctionDescriptor SHAPE = {descriptor};

    static MethodHandle handle(SymbolLookup lookup, String name) {{
        return Linker.nativeLinker().downcallHandle(
                lookup.find(name).orElseThrow(), SHAPE);
    }}
}}
"""

PLACED_SOURCE = """package io.github.lihuu.panamaview.macos;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MethodHandle;
import java.lang.foreign.SymbolLookup;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * A fixture: a descriptor, and how it is used -- in a downcall, and then in a
 * variable the check cannot follow.
 */
final class FixtureUnplaced {{

    private static final FunctionDescriptor SHAPE = {descriptor};

    static MethodHandle handle(SymbolLookup lookup, String name) {{
        return Linker.nativeLinker().downcallHandle(
                lookup.find(name).orElseThrow(), SHAPE);
    }}

    static void held() {{
        FunctionDescriptor descriptor = SHAPE;
    }}
}}
"""

DECLARED_SOURCE = """package io.github.lihuu.panamaview.macos;

import java.lang.foreign.MemorySegment;

/**
 * A fixture: a class the metadata names, so that renaming it is a real failure.
 */
final class FixtureDeclared {{

    public static void handleFixture(MemorySegment self, MemorySegment cmd,
                                     MemorySegment sender) {{
    }}
}}
"""

DECLARED_CLASS = "io.github.lihuu.panamaview.macos.FixtureDeclared"


# ------------------------------------------------------------------- the cases


def case_unregistered_shape(work):
    """A shape the sources build that the file does not register."""
    say("a shape the sources build that the file does not register")
    root = make_tree(work, "shape")
    registered = a_registered_shape(root)
    if registered is None:
        bad("a shape to build", "no registered shape can be written in Java")
        return
    descriptor = java_descriptor(registered)
    write_java(root, "FixtureShape.java", SHAPE_SOURCE.format(descriptor=descriptor))
    expect_clean("the same file, with a registered shape, is accepted", root)

    shape, descriptor = an_unregistered_shape(root)
    if shape is None:
        bad("a shape to build", "the metadata registers every shape the driver can build")
        return
    write_java(root, "FixtureShape.java", SHAPE_SOURCE.format(descriptor=descriptor))
    expect_failure("the check notices the unregistered shape", root,
                   "the sources build %s but the file does not register it" % shape)


def case_missing_method(work):
    """An upcall entry naming a method that does not exist."""
    say("an upcall entry naming a method that does not exist")
    root = make_tree(work, "method")
    expect_clean("the tree is clean before the break", root)
    metadata = read_metadata(root)
    entry = a_framework_upcall(metadata)
    if entry is None:
        bad("an upcall entry to break", "the metadata names no class in the package")
        return
    entry["method"] = "aMethodThatDoesNotExist"
    write_metadata(root, metadata)
    expect_failure("the check notices the method that is not there", root,
                   "names a method that is not in")


def case_missing_reflection(work):
    """An upcall entry with no matching reflection entry."""
    say("an upcall entry with no matching reflection entry")
    root = make_tree(work, "reflection")
    expect_clean("the tree is clean before the break", root)
    metadata = read_metadata(root)
    entry = a_framework_upcall(metadata)
    if entry is None:
        bad("an upcall entry to break", "the metadata names no class in the package")
        return
    for reflected in metadata["reflection"]:
        if reflected["type"] != entry["class"]:
            continue
        methods = [m for m in reflected["methods"] if m["name"] != entry["method"]]
        if methods:
            reflected["methods"] = methods
        else:
            metadata["reflection"].remove(reflected)
        break
    write_metadata(root, metadata)
    expect_failure("the check notices the missing reflection entry", root,
                   "has a directUpcalls entry but no reflection entry")


def case_unplaced_descriptor(work):
    """A descriptor the check cannot place."""
    say("a descriptor the check cannot place")
    root = make_tree(work, "unplaced")
    registered = a_registered_shape(root)
    if registered is None:
        bad("a shape to build", "no registered shape can be written in Java")
        return
    descriptor = java_descriptor(registered)
    write_java(root, "FixtureUnplaced.java", PLACED_SOURCE.format(descriptor=descriptor))
    expect_clean("the same file, with the descriptor in a downcall, is accepted", root)

    write_java(root, "FixtureUnplaced.java", PLACED_SOURCE.format(descriptor=descriptor))
    text = (root / PACKAGE_DIR / "FixtureUnplaced.java").read_text(encoding="utf-8")
    text = text.replace(
        """    static MethodHandle handle(SymbolLookup lookup, String name) {
        return Linker.nativeLinker().downcallHandle(
                lookup.find(name).orElseThrow(), SHAPE);
    }

""", "")
    (root / PACKAGE_DIR / "FixtureUnplaced.java").write_text(text, encoding="utf-8")
    expect_failure("the check notices the descriptor it cannot place", root,
                   "the check cannot see where it is used")


def case_renamed_class(work):
    """A class that is not declared in the file the metadata names."""
    say("a class that is not declared in the file the metadata names")
    root = make_tree(work, "renamed")
    write_java(root, "FixtureDeclared.java", DECLARED_SOURCE)
    metadata = read_metadata(root)
    metadata["foreign"]["directUpcalls"].append({
        "class": DECLARED_CLASS,
        "method": "handleFixture",
        "returnType": "void",
        "parameterTypes": ["void*", "void*", "void*"],
    })
    metadata["reflection"].append({
        "type": DECLARED_CLASS,
        "methods": [{
            "name": "handleFixture",
            "parameterTypes": ["java.lang.foreign.MemorySegment",
                               "java.lang.foreign.MemorySegment",
                               "java.lang.foreign.MemorySegment"],
        }],
    })
    write_metadata(root, metadata)
    expect_clean("the same file, with the class named as the metadata names it, is accepted",
                 root)

    path = root / PACKAGE_DIR / "FixtureDeclared.java"
    path.write_text(path.read_text(encoding="utf-8")
                    .replace("class FixtureDeclared {", "class FixtureDeclaredRenamed {"),
                    encoding="utf-8")
    expect_failure("the check notices the class that is not declared", root,
                   "names a class that is not declared in")


def main():
    say("the tree as it is")
    with tempfile.TemporaryDirectory(prefix="panamaview-metadata-") as work:
        work = pathlib.Path(work)
        expect_clean("the check passes on the tree as it is", make_tree(work, "pristine"))

        case_unregistered_shape(work)
        case_missing_method(work)
        case_missing_reflection(work)
        case_unplaced_descriptor(work)
        case_renamed_class(work)

    print("\n%s passed, %s failed" % (passed, failed))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
