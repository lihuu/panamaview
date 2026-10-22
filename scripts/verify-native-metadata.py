#!/usr/bin/env python3
"""Check the native-image reachability metadata against the sources it describes.

GraalVM's native image cannot see java.lang.foreign calls the way it sees ordinary
code: a FunctionDescriptor is a runtime value, so the build has to be told which
native signatures the executable is allowed to call. That list lives in the
framework's reachability-metadata.json, and nothing in an ordinary build complains
when it drifts from the code -- the program still compiles, and then dies on the
first downcall with MissingForeignRegistrationError, on the machine that built the
binary. This script is that complaint, and CI runs it.

It covers the macos package only. The native image this file serves is the macOS
one; the windows package's downcalls are NOT registered here, so a Windows
consumer's native build would need its own shapes -- a separate piece of work, and
this script does not pretend it is done.

What it checks:

  * every downcall shape the macos sources build is registered in foreign.downcalls;
  * every upcall site the sources spell out has a directUpcalls entry of the same shape;
  * every directUpcalls entry names a class and method that still exist, and the shape
    still matches the method's Java signature;
  * every directUpcalls entry has a matching reflection entry, and every reflection
    entry that names a framework class still names a method that exists, with the
    parameters that method actually takes.

What it cannot check: the upcall side forwards. Some upcall sites name their class
and method through variables -- NativeBlock builds its stub from an owner and a
method it was handed -- so no static check can know what they register. Those sites
are reported, never passed silently.

Usage: verify-native-metadata.py
"""

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
METADATA = ROOT / ("framework/src/main/resources/META-INF/native-image/"
                   "io.github.lihuu/panamaview/reachability-metadata.json")
SOURCE_ROOT = ROOT / "framework/src/main/java"
PACKAGE = "io.github.lihuu.panamaview.macos"
PACKAGE_DIR = SOURCE_ROOT / "io/github/lihuu/panamaview/macos"

# The metadata's own vocabulary: 'void*' is a pointer, the j-prefixed names are the
# scalar carriers, and 'struct(...)' is a by-value aggregate.
LAYOUTS = {
    "ADDRESS": "void*",
    "JAVA_BYTE": "jbyte",
    "JAVA_SHORT": "jshort",
    "JAVA_INT": "jint",
    "JAVA_LONG": "jlong",
    "JAVA_FLOAT": "jfloat",
    "JAVA_DOUBLE": "jdouble",
    "JAVA_BOOLEAN": "jboolean",
}

# The Java carrier type of each layout. This is what a directUpcalls shape and the
# method it points at have to agree on: FFM hands a void* to a MemorySegment and a
# jbyte to a byte, and a stub built for the wrong carrier fails at run time.
CARRIERS = {
    "void": "void",
    "void*": "java.lang.foreign.MemorySegment",
    "jbyte": "byte",
    "jshort": "short",
    "jint": "int",
    "jlong": "long",
    "jfloat": "float",
    "jdouble": "double",
    "jboolean": "boolean",
}

# Words that can precede a call without making it a declaration.
KEYWORDS = {
    "return", "new", "throw", "case", "else", "yield", "await", "assert", "do",
    "instanceof", "this", "super", "null", "true", "false", "var", "synchronized",
}


# --------------------------------------------------------------------- reading


def strip_comments(text):
    """The text with its comments blanked out, so a match never sees a comment."""
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if text.startswith('"""', i):
            end = text.find('"""', i + 3)
            end = n if end < 0 else end + 3
            out.append(text[i:end])
            i = end
            continue
        if c in "\"'":
            end = i + 1
            while end < n and text[end] != c:
                if text[end] == "\\":
                    end += 1
                end += 1
            out.append(text[i:end + 1])
            i = end + 1
            continue
        if text.startswith("//", i):
            end = text.find("\n", i)
            end = n if end < 0 else end
            out.append(" " * (end - i))
            i = end
            continue
        if text.startswith("/*", i):
            end = text.find("*/", i + 2)
            end = n if end < 0 else end + 2
            out.append("".join(ch if ch == "\n" else " " for ch in text[i:end]))
            i = end
            continue
        out.append(c)
        i += 1
    return "".join(out)


def match_paren(text, start):
    """The index just past the ')' closing the '(' at `start`."""
    depth = 0
    i = start
    n = len(text)
    while i < n:
        c = text[i]
        if c in "\"'":
            end = i + 1
            while end < n and text[end] != c:
                if text[end] == "\\":
                    end += 1
                end += 1
            i = end + 1
            continue
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return -1


def split_args(text):
    """The top-level arguments of an argument list."""
    args = []
    current = []
    depth = 0
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c in "\"'":
            end = i + 1
            while end < n and text[end] != c:
                if text[end] == "\\":
                    end += 1
                end += 1
            current.append(text[i:end + 1])
            i = end + 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        if c == "," and depth == 0:
            args.append("".join(current))
            current = []
            i += 1
            continue
        current.append(c)
        i += 1
    if current:
        args.append("".join(current))
    return [a.strip() for a in args]


def line_of(text, index):
    """The 1-based line the character at `index` is on."""
    return text.count("\n", 0, index) + 1


def simple_name(type_name):
    """'java.lang.foreign.MemorySegment' -> 'MemorySegment'."""
    name = type_name.split("<")[0].strip()
    return name.rsplit(".", 1)[-1]


# ------------------------------------------------------------------- the shapes
#
# A shape is a (returnType, (parameterTypes...)) pair, in the metadata's own
# vocabulary -- the same one the file is written in, so the two can be compared
# without translating either of them.


def shape_text(shape):
    """'void*(void*,void*)' -- how the report names a shape."""
    return_type, parameters = shape
    return "%s(%s)" % (return_type, ",".join(parameters))


def layout_shape(token):
    """The shape of one layout, or None when the token is not a layout we know."""
    token = token.strip()
    if token in LAYOUTS:
        return LAYOUTS[token]
    if token in STRUCTS:
        return STRUCTS[token]
    # A layout named with a trailing call -- JAVA_DOUBLE.withName("x") -- still names
    # one layout, and the name is one of its parts.
    for part in token.split("."):
        part = part.strip()
        if part in LAYOUTS:
            return LAYOUTS[part]
        if part in STRUCTS:
            return STRUCTS[part]
    return None


def descriptor_shape(args, is_void):
    """The shape of a FunctionDescriptor built from `args`, or None if unresolvable."""
    if is_void:
        return_type, parameters = "void", args
    elif args:
        return_type, parameters = args[0], args[1:]
    else:
        return None
    if is_void:
        resolved_return = "void"
    else:
        resolved_return = layout_shape(return_type)
        if resolved_return is None:
            return None
    resolved = []
    for parameter in parameters:
        shape = layout_shape(parameter)
        if shape is None:
            return None
        resolved.append(shape)
    return (resolved_return, tuple(resolved))


# ----------------------------------------------------------------- the sources


class Source:
    """One Java file in the macos package, with what the check needs from it."""

    def __init__(self, path):
        self.path = path
        self.name = path.stem
        self.text = strip_comments(path.read_text(encoding="utf-8"))
        self.constants = {}
        self.parse()

    def parse(self):
        for match in re.finditer(r"MemoryLayout\s+(\w+)\s*=\s*MemoryLayout\.structLayout\s*\(",
                                 self.text):
            end = match_paren(self.text, match.end() - 1)
            if end < 0:
                continue
            elements = []
            for element in split_args(self.text[match.end():end - 1]):
                shape = layout_shape(element)
                if shape is None:
                    elements = None
                    break
                elements.append(shape)
            if elements is not None:
                STRUCTS[match.group(1)] = "struct(%s)" % ",".join(elements)

        for match in re.finditer(r"FunctionDescriptor\s+(\w+)\s*=\s*", self.text):
            found = re.match(r"FunctionDescriptor\.(of|ofVoid)\s*\(", self.text[match.end():])
            if not found:
                continue
            end = match_paren(self.text, match.end() + found.end() - 1)
            if end < 0:
                continue
            args = split_args(self.text[match.end() + found.end():end - 1])
            shape = descriptor_shape(args, found.group(1) == "ofVoid")
            if shape is not None:
                self.constants[match.group(1)] = shape

    def resolve(self, token):
        """The shape of a reference to a descriptor constant, or None."""
        token = token.strip()
        if "." in token:
            owner, _, name = token.rpartition(".")
            other = BY_SIMPLE_NAME.get(owner)
            if other is None:
                return None
            return other.constants.get(name)
        return self.constants.get(token)

    def method(self, name):
        """(returnType, [parameterTypes]) of a declared method, or None."""
        pattern = (r"(?m)^[ \t]*(?:(?:public|private|protected|static|final|abstract|"
                   r"native|synchronized)\s+)*([\w.$<>\[\]]+)\s+" + re.escape(name) + r"\s*\(")
        for match in re.finditer(pattern, self.text):
            end = match_paren(self.text, match.end() - 1)
            if end < 0:
                continue
            parameters = []
            for parameter in split_args(self.text[match.end():end - 1]):
                words = parameter.replace("final ", "").split()
                if len(words) >= 2:
                    parameters.append(simple_name(" ".join(words[:-1])))
                else:
                    parameters.append(simple_name(parameter))
            return simple_name(match.group(1)), parameters
        return None

    def carriers(self, shape):
        """The Java carrier types of a shape, in the order the stub sees them."""
        return_type, parameters = shape
        carriers = []
        for parameter in parameters:
            carriers.append(carrier(parameter))
        return carriers


def carrier(layout):
    """The Java carrier type of a native layout."""
    if layout in CARRIERS:
        return CARRIERS[layout]
    # A by-value aggregate arrives in a MemorySegment.
    return "java.lang.foreign.MemorySegment"


# ------------------------------------------------------------------ the calls

CALL = re.compile(r"(?:\.\s*)?([A-Za-z_$][A-Za-z0-9_$]*(?:\s*\.\s*[A-Za-z_$][A-Za-z0-9_$]*)*)\s*\(")


def is_declaration(text, name_start):
    """True when the identifier at `name_start` is being declared, not called."""
    before = text[:name_start].rstrip()
    if not before or before.endswith("."):
        return False
    word = re.search(r"([A-Za-z_$][A-Za-z0-9_$]*)$", before)
    if not word:
        return False
    # 'return foo(' is a call; 'MemorySegment foo(' is a declaration.
    return word.group(1) not in KEYWORDS


def classify(callee):
    """'downcall', 'upcall' or None -- which list a call belongs to."""
    if callee in ("call", "bind", "downcallHandle") or \
            callee.endswith((".call", ".bind", ".downcallHandle")):
        return "downcall"
    if callee == "NativeBlock.of" or callee == "upcall" or callee.endswith(".upcall"):
        return "upcall"
    return None


def calls(source):
    """Every call in a source, as (callee, nameStart, arguments)."""
    found = []
    for match in CALL.finditer(source.text):
        callee = re.sub(r"\s+", "", match.group(1))
        name_start = match.start(1) + match.group(1).rfind(callee.split(".")[-1])
        if is_declaration(source.text, name_start):
            continue
        end = match_paren(source.text, match.end() - 1)
        if end < 0:
            continue
        found.append((callee, name_start,
                      split_args(source.text[match.end():end - 1])))
    return found


# -------------------------------------------------------------------- the file


def load_metadata():
    if not METADATA.is_file():
        sys.exit("no metadata file at %s" % METADATA)
    try:
        return json.loads(METADATA.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        sys.exit("%s is not valid JSON: %s" % (METADATA, error))


def registered_shape(entry):
    return (entry["returnType"], tuple(entry["parameterTypes"]))


def source_of(class_name):
    """The Source for a framework class, or None when it is not one of ours."""
    if not class_name.startswith(PACKAGE + "."):
        return None
    path = PACKAGE_DIR / (class_name.rsplit(".", 1)[1] + ".java")
    if not path.is_file():
        return None
    for source in SOURCES:
        if source.path == path:
            return source
    return None


# ----------------------------------------------------------------- the report


class Report:
    def __init__(self):
        self.problems = []
        self.notes = []

    def fail(self, message):
        self.problems.append(message)

    def note(self, message):
        self.notes.append(message)


SOURCES = []
BY_SIMPLE_NAME = {}
STRUCTS = {}


def main():
    metadata = load_metadata()
    foreign = metadata.get("foreign") or {}
    downcalls = foreign.get("downcalls")
    direct_upcalls = foreign.get("directUpcalls")
    reflection = metadata.get("reflection")
    if downcalls is None or direct_upcalls is None or reflection is None:
        sys.exit("%s has no 'foreign.downcalls', 'foreign.directUpcalls' or 'reflection'"
                 % METADATA)

    global SOURCES, BY_SIMPLE_NAME
    SOURCES = [Source(path) for path in sorted(PACKAGE_DIR.glob("*.java"))]
    if not SOURCES:
        sys.exit("no Java sources in %s" % PACKAGE_DIR)
    BY_SIMPLE_NAME = {source.name: source for source in SOURCES}

    report = Report()

    # ------------------------------------------------- what the sources use
    used_downcalls = {}          # shape -> (source, line, how it was written)
    upcall_sites = []            # (class, method, shape, source, line)
    unresolved = []              # (source, line, the call as written)

    for source in SOURCES:
        for callee, name_start, arguments in calls(source):
            kind = classify(callee)
            if kind == "downcall":
                for argument in arguments:
                    shape = descriptor_of(argument, source)
                    if shape is None:
                        continue
                    used_downcalls.setdefault(
                        shape, (source, line_of(source.text, name_start), argument))
            elif kind == "upcall":
                if callee == "NativeBlock.of" or callee.endswith(".upcall"):
                    site, reason = upcall_site(source, name_start, arguments)
                    if site is None:
                        unresolved.append((source, line_of(source.text, name_start),
                                           callee, reason))
                    else:
                        upcall_sites.append(site)

    # ------------------------------------------------- downcalls are registered
    registered = {registered_shape(entry): entry for entry in downcalls}
    missing = sorted(shape for shape in used_downcalls if shape not in registered)
    for shape in missing:
        source, line, how = used_downcalls[shape]
        report.fail("the sources build %s but the file does not register it "
                    "(%s/%s:%d, %s)"
                    % (shape_text(shape), PACKAGE, source.name, line, how))

    unused = sorted(shape for shape in registered if shape not in used_downcalls)
    for shape in unused:
        report.note("the file registers %s and no source in %s builds it"
                    % (shape_text(shape), PACKAGE))

    # ------------------------------------------------- upcalls are registered
    upcall_entries = {}
    for entry in direct_upcalls:
        upcall_entries.setdefault((entry["class"], entry["method"]), []).append(entry)

    for class_name, method, shape, source, line in upcall_sites:
        entries = upcall_entries.get((class_name, method))
        if not entries:
            report.fail("%s.%s is used as an upcall (%s/%s:%d) but the file has no "
                        "directUpcalls entry for it"
                        % (class_name, method, PACKAGE, source.name, line))
            continue
        for entry in entries:
            if registered_shape(entry) != shape:
                report.fail("the directUpcalls entry for %s.%s registers %s but the "
                            "call site builds %s"
                            % (class_name, method,
                               shape_text(registered_shape(entry)), shape_text(shape)))

    # ------------------------------- the registrations point at real methods
    reflection_entries = {}
    for entry in reflection:
        for method in entry.get("methods", []):
            reflection_entries.setdefault((entry["type"], method["name"]), []).append(method)

    for entry in direct_upcalls:
        class_name = entry["class"]
        method = entry["method"]
        shape = registered_shape(entry)
        source = source_of(class_name)
        if source is None:
            if class_name.startswith(PACKAGE + "."):
                report.fail("the directUpcalls entry for %s.%s names a class that is "
                            "not in the sources" % (class_name, method))
            continue
        found = source.method(method)
        if found is None:
            report.fail("the directUpcalls entry for %s.%s names a method that is not "
                        "in %s" % (class_name, method, source.name))
            continue
        return_type, parameters = found
        expected = source.carriers(shape)
        if [simple_name(p) for p in parameters] != [simple_name(p) for p in expected]:
            report.fail("%s.%s takes (%s) but its directUpcalls entry registers (%s)"
                        % (class_name, method,
                           ", ".join(parameters), ", ".join(expected)))
        if simple_name(return_type) != simple_name(carrier(shape[0])):
            report.fail("%s.%s returns %s but its directUpcalls entry registers %s"
                        % (class_name, method, return_type, shape[0]))
        if (class_name, method) not in reflection_entries:
            report.fail("%s.%s has a directUpcalls entry but no reflection entry"
                        % (class_name, method))

    # ------------------------------- the reflection entries point at real methods
    for entry in reflection:
        class_name = entry["type"]
        source = source_of(class_name)
        if source is None:
            if class_name.startswith(PACKAGE + "."):
                report.fail("the reflection entry for %s names a class that is not in "
                            "the sources" % class_name)
            continue
        for method in entry.get("methods", []):
            found = source.method(method["name"])
            if found is None:
                report.fail("the reflection entry for %s.%s names a method that is not "
                            "in %s" % (class_name, method["name"], source.name))
                continue
            _, parameters = found
            declared = [simple_name(p) for p in method.get("parameterTypes", [])]
            if declared != [simple_name(p) for p in parameters]:
                report.fail("%s.%s takes (%s) but the reflection entry says (%s)"
                            % (class_name, method["name"],
                               ", ".join(parameters), ", ".join(declared)))

    # ------------------------------------------------------------- the report
    every_shape = set(used_downcalls)
    for _, _, shape, _, _ in upcall_sites:
        every_shape.add(shape)
    upcall_only = sorted(every_shape - set(used_downcalls))

    print("the native-image metadata and the %s sources" % PACKAGE)
    print()
    print("  downcall shapes")
    print("    the sources build %d shapes; the file registers %d"
          % (len(every_shape), len(registered)))
    if upcall_only:
        print("    %d of them is only ever an upcall, so it belongs in directUpcalls and"
              % len(upcall_only))
        print("    not in downcalls: %s"
              % ", ".join(shape_text(shape) for shape in upcall_only))
    if missing:
        print("    used but not registered:")
        for shape in missing:
            source, line, how = used_downcalls[shape]
            print("      %-30s <- %s/%s:%d (%s)"
                  % (shape_text(shape), PACKAGE, source.name, line, how))
    else:
        print("    every shape the sources build is registered")
    print()
    print("  upcall registrations")
    print("    %d upcall sites, %d directUpcalls entries"
          % (len(upcall_sites) + len(unresolved), len(direct_upcalls)))
    if unresolved:
        print("    %d site(s) cannot be resolved statically, so they are not checked:"
              % len(unresolved))
        for source, line, callee, reason in unresolved:
            print("      %s/%s:%d  %s(...)  -- %s"
                  % (PACKAGE, source.name, line, callee, reason))
    else:
        print("    every upcall site has a directUpcalls entry of the same shape")
    print()
    print("  reflection")
    framework_entries = [e for e in reflection if e["type"].startswith(PACKAGE + ".")]
    other_entries = [e for e in reflection if not e["type"].startswith(PACKAGE + ".")]
    print("    %d framework entries checked; %d entries name JDK classes and are not "
          "checked here" % (len(framework_entries), len(other_entries)))

    if report.notes:
        print()
        print("  notes")
        for note in report.notes:
            print("    - %s" % note)

    if report.problems:
        print()
        print("%d problem(s):" % len(report.problems))
        for problem in report.problems:
            print("  - %s" % problem)
        sys.exit("the native-image metadata does not match the sources")


def descriptor_of(argument, source):
    """The shape of an argument, whether it is written out or named."""
    argument = argument.strip()
    found = re.match(r"FunctionDescriptor\.(of|ofVoid)\s*\(", argument)
    if found:
        end = match_paren(argument, found.end() - 1)
        if end < 0:
            return None
        args = split_args(argument[found.end():end - 1])
        return descriptor_shape(args, found.group(1) == "ofVoid")
    if re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*(\s*\.\s*[A-Za-z_$][A-Za-z0-9_$]*)?", argument):
        return source.resolve(argument)
    return None


def upcall_site(source, name_start, arguments):
    """(site, reason) for an upcall call -- the site is None when it cannot be read."""
    if len(arguments) < 3:
        return None, "the call does not name a class, a method and a descriptor"
    class_match = re.fullmatch(r"([A-Za-z_$][A-Za-z0-9_$]*)\s*\.\s*class", arguments[0])
    if not class_match:
        return None, "the class is not written out here"
    method_match = re.fullmatch(r'"([^"]*)"', arguments[1])
    if not method_match:
        return None, "the method is not written out here"
    shape = descriptor_of(arguments[2], source)
    if shape is None:
        return None, "the descriptor is not written out here"
    class_name = PACKAGE + "." + class_match.group(1)
    return ((class_name, method_match.group(1), shape, source,
             line_of(source.text, name_start)), None)


if __name__ == "__main__":
    main()
