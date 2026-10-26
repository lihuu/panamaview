#!/usr/bin/env python3
"""Check the native-image reachability metadata against the sources it describes.

GraalVM's native image cannot see java.lang.foreign calls the way it sees ordinary
code: a FunctionDescriptor is a runtime value, so the build has to be told which
native signatures the executable is allowed to call. That list lives in the
framework's reachability-metadata.json, and nothing in an ordinary build complains
when it drifts from the code -- the program still compiles, and then dies on the
first downcall with MissingForeignRegistrationError, on the machine that built the
binary. This script is that complaint, and CI runs it.

It covers both platform packages, macos and windows. One metadata file serves both:
a native image is built for one platform, and shapes are a union -- a macOS build
never calls a Windows shape and the reverse -- so the file lists every shape either
side may build, and this check insists that each side's shapes are in it.

What it checks:

  * every downcall shape the sources build is registered in foreign.downcalls;
  * every upcall site the sources spell out has a directUpcalls entry of the same shape
    when its target is a named static method, and a generic upcalls entry when it is
    not: a bound or dynamic MethodHandle cannot be named by class and method, and
    GraalVM registers those by descriptor alone;
  * every directUpcalls entry names a class and method that still exist, and the shape
    still matches the method's Java signature;
  * every directUpcalls entry has a matching reflection entry, and every reflection
    entry that names a framework class still names a method that exists, with the
    parameters that method actually takes;
  * every descriptor the sources build is accounted for: a constant or an inline
    construction that this check cannot place in a downcall or an upcall position is
    reported, never dropped. A descriptor it cannot place is a descriptor it cannot
    vouch for, and the whole point of the check is that a shape nobody registered is
    a native build that dies on the first downcall.

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

# The platform packages this file serves, as (package name, directory). Two packages,
# one metadata file -- see the docstring.
PACKAGES = [
    ("io.github.lihuu.panamaview.macos",
     SOURCE_ROOT / "io/github/lihuu/panamaview/macos"),
    ("io.github.lihuu.panamaview.windows",
     SOURCE_ROOT / "io/github/lihuu/panamaview/windows"),
]
PACKAGE_NAMES = [name for name, _ in PACKAGES]
PACKAGE_DIRS = [directory for _, directory in PACKAGES]

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

# The two shapes of a descriptor: written out where it is built, or named by a
# constant.
INLINE = re.compile(r"FunctionDescriptor\.(of|ofVoid)\s*\(")

# A type declaration. The modifiers are optional and the file's own indentation is
# not, because a nested class is not a top-level one.
DECLARATION = re.compile(
    r"(?m)^[ \t]*(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed|"
    r"strictfp)\s+)*(?:class|interface|enum|record)\s+([A-Za-z_$][A-Za-z0-9_$]*)")


class MetadataError(Exception):
    """The metadata file is not shaped the way this check reads it."""


# --------------------------------------------------------------------- reading


def strip_comments(text):
    """The text with its comments blanked out, and the spans of its literals.

    The blanking keeps every offset, so a span measured here is still true of the
    text that comes back.
    """
    out = []
    literals = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if text.startswith('"""', i):
            end = text.find('"""', i + 3)
            end = n if end < 0 else end + 3
            out.append(text[i:end])
            literals.append((i, end))
            i = end
            continue
        if c in "\"'":
            end = i + 1
            while end < n and text[end] != c:
                if text[end] == "\\":
                    end += 1
                end += 1
            end = min(end + 1, n)
            out.append(text[i:end])
            literals.append((i, end))
            i = end
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
    return "".join(out), literals


def mask_literals(text, literals):
    """The text with the contents of its literals blanked out.

    A brace inside a string is not a brace, and a file that puts one there would
    otherwise look like it declares a type inside a method.
    """
    if not literals:
        return text
    chars = list(text)
    for start, end in literals:
        for i in range(start, end):
            if chars[i] != "\n":
                chars[i] = " "
    return "".join(chars)


def in_literal(literals, index):
    """True when the character at `index` is inside a string or a character literal."""
    for start, end in literals:
        if start <= index < end:
            return True
        if index < start:
            return False
    return False


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
    """The top-level arguments of an argument list.

    Angle brackets are counted, so a generic parameter type with a comma in it --
    Map<String, Integer> -- is one parameter and not two.
    """
    args = []
    current = []
    depth = 0
    angles = 0
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
        elif c == "<" and i > 0 and (text[i - 1].isalnum() or text[i - 1] in "_$>"):
            # A generic type. A comparison has a space before the sign, and a
            # lambda's '->' has a dash before it, so neither is counted here.
            angles += 1
        elif c == ">" and angles > 0:
            angles -= 1
        if c == "," and depth == 0 and angles == 0:
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


def layout_shape(token, structs):
    """The shape of one layout, or None when the token is not a layout we know."""
    token = token.strip()
    if token in LAYOUTS:
        return LAYOUTS[token]
    if token in structs:
        return structs[token]
    # A layout named with a trailing call -- JAVA_DOUBLE.withName("x") -- still names
    # one layout, and the name is one of its parts.
    for part in token.split("."):
        part = part.strip()
        if part in LAYOUTS:
            return LAYOUTS[part]
        if part in structs:
            return structs[part]
    return None


def descriptor_shape(args, is_void, structs):
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
        resolved_return = layout_shape(return_type, structs)
        if resolved_return is None:
            return None
    resolved = []
    for parameter in parameters:
        shape = layout_shape(parameter, structs)
        if shape is None:
            return None
        resolved.append(shape)
    return (resolved_return, tuple(resolved))


def carrier(layout):
    """The Java carrier type of a native layout."""
    if layout in CARRIERS:
        return CARRIERS[layout]
    # A by-value aggregate arrives in a MemorySegment.
    return "java.lang.foreign.MemorySegment"


# ----------------------------------------------------------------- the sources


class Source:
    """One Java file in the package, with what the check needs from it."""

    def __init__(self, path, where, label):
        self.path = path
        # How the file is named in a message: its package's directory name and its path
        # inside that package, without the extension. Two files in different packages can
        # share a name, and the label says which package is meant.
        self.label = label
        self.where = where
        self.text, self.literals = strip_comments(path.read_text(encoding="utf-8"))
        self.masked = mask_literals(self.text, self.literals)
        self.package = ""
        self.types = []
        # Every type this file declares, nested ones included, as (Outer$Inner, match).
        self.all_types = []
        # The descriptor constants this file declares, and their shapes. A constant
        # whose shape cannot be read is kept with a None, because a descriptor the
        # check cannot read is a descriptor the check must report.
        self.constants = {}

    def parse_package(self):
        found = re.search(r"(?m)^\s*package\s+([\w.]+)\s*;", self.text)
        if found:
            self.package = found.group(1)

    def parse_types(self):
        """The types this file declares, top-level and nested.

        The class a file declares is not the file's name: a package-private class may
        be renamed inside its own file, and the metadata names classes, not files.

        Nested types are named the way the JVM names them -- Outer$Inner -- because
        that is how the metadata names them, and a method named there has to be looked
        up inside that type and not in its sibling. Com$Completed and Com$Event both
        declare invoke, with different signatures.
        """
        stack = []
        for match in DECLARATION.finditer(self.text):
            depth = self.depth_at(match.start())
            while len(stack) > depth:
                stack.pop()
            local = "$".join(stack + [match.group(1)])
            self.all_types.append((local, match))
            if depth == 0:
                self.types.append(match.group(1))
            stack.append(match.group(1))

    def depth_at(self, index):
        """How many braces are open at `index`."""
        before = self.masked[:index]
        return before.count("{") - before.count("}")

    def struct_layouts(self):
        """The by-value aggregates this file declares, as name -> element tokens."""
        found = {}
        for match in re.finditer(r"MemoryLayout\s+(\w+)\s*=\s*MemoryLayout\.structLayout\s*\(",
                                 self.text):
            end = match_paren(self.text, match.end() - 1)
            if end < 0:
                continue
            found[match.group(1)] = split_args(self.text[match.end():end - 1])
        return found

    def collect_constants(self, structs):
        """The descriptor constants this file declares."""
        for match in re.finditer(r"FunctionDescriptor\s+(\w+)\s*=\s*", self.text):
            if in_literal(self.literals, match.start()):
                continue
            found = re.match(r"FunctionDescriptor\.(of|ofVoid)\s*\(", self.text[match.end():])
            if not found:
                continue
            end = match_paren(self.text, match.end() + found.end() - 1)
            if end < 0:
                continue
            args = split_args(self.text[match.end() + found.end():end - 1])
            shape = descriptor_shape(args, found.group(1) == "ofVoid", structs)
            self.constants[match.group(1)] = shape

    def resolve(self, token, package):
        """The shape of a reference to a descriptor constant, or None."""
        token = token.strip()
        if "." in token:
            owner, _, name = token.rpartition(".")
            other = package.by_type_name.get(owner)
            if other is None:
                return None
            return other.constants.get(name)
        return self.constants.get(token)

    def method(self, name, owner=None):
        """(returnType, [parameterTypes]) of a declared method, or None.

        With `owner`, the search is confined to that type's body -- so Com$Completed's
        invoke and Com$Event's invoke are each found in their own type. Without it, the
        first declaration in the file wins, which is what a top-level class wants.
        """
        text = self.text
        start = 0
        if owner is not None:
            span = self.type_span(owner)
            if span is None:
                return None
            start, stop = span
            text = self.text[start:stop]
        pattern = (r"(?m)^[ \t]*(?:(?:public|private|protected|static|final|abstract|"
                   r"native|synchronized)\s+)*([\w.$<>\[\]]+)\s+(" + re.escape(name)
                   + r")\s*\(")
        for match in re.finditer(pattern, text):
            # A call can look like a declaration: 'return handleScriptMessage(null, ...);'
            # is a call, and reading it as one would report that the method takes four
            # nulls. What sits in the type position must be a type, and a keyword is
            # never a type.
            if match.group(1).rsplit(".", 1)[-1] in KEYWORDS:
                continue
            end = match_paren(text, match.end() - 1)
            if end < 0:
                continue
            parameters = []
            for parameter in split_args(text[match.end():end - 1]):
                words = parameter.replace("final ", "").split()
                if len(words) >= 2:
                    parameters.append(simple_name(" ".join(words[:-1])))
                else:
                    parameters.append(simple_name(parameter))
            return simple_name(match.group(1)), parameters
        return None

    def type_span(self, local):
        """(start, end) of a type's declaration, or None when it is not declared here."""
        for name, match in self.all_types:
            if name != local:
                continue
            opening = self.masked.find("{", match.end())
            if opening < 0:
                return None
            closing = self.matching_brace(opening)
            return (match.start(), closing + 1 if closing >= 0 else len(self.text))
        return None

    def matching_brace(self, start):
        """The index of the '}' closing the '{' at `start`, or -1."""
        depth = 0
        for i in range(start, len(self.masked)):
            if self.masked[i] == "{":
                depth += 1
            elif self.masked[i] == "}":
                depth -= 1
                if depth == 0:
                    return i
        return -1


class Package:
    """The platform packages: their sources, struct layouts and type names."""

    def __init__(self, directories):
        # rglob, not glob: a subpackage under macos/ is still macOS code, and a
        # descriptor it built would be just as unregistered as one built above.
        self.sources = []
        for directory in directories:
            label = directory.name
            self.sources.extend(
                Source(path, path.relative_to(directory).with_suffix("").as_posix(), label)
                for path in sorted(directory.rglob("*.java")))
        for source in self.sources:
            source.parse_package()
            source.parse_types()
        # Two rounds: every file's struct layouts first, then every descriptor. A
        # struct declared in a file that is read later is still a struct, and a
        # descriptor that names it must not lose it.
        self.structs = self.collect_structs()
        for source in self.sources:
            source.collect_constants(self.structs)
        # A type is named by the class it declares, not by the file it lives in.
        self.by_type_name = {}
        for source in self.sources:
            for name in source.types:
                self.by_type_name[name] = source
        # Every declared type, nested ones included. The value carries the local name
        # because a method entry has to be looked up inside that type, not its sibling.
        self.by_qualified_name = {}
        self.by_simple_name = {}
        for source in self.sources:
            for local, _ in source.all_types:
                self.by_qualified_name[qualified(source, local)] = (source, local)
                self.by_simple_name[local.rsplit("$", 1)[-1]] = (source, local)

    def collect_structs(self):
        """Every by-value aggregate in the package, as name -> shape.

        A struct's element may be another struct, and the files are not read in
        dependency order, so the names are collected first and the shapes resolved
        until nothing more can be resolved.
        """
        pending = {}
        for source in self.sources:
            for name, elements in source.struct_layouts().items():
                pending[name] = elements
        structs = {}
        while True:
            progress = False
            for name, elements in pending.items():
                if name in structs:
                    continue
                resolved = []
                for element in elements:
                    shape = layout_shape(element, structs)
                    if shape is None:
                        resolved = None
                        break
                    resolved.append(shape)
                if resolved is not None:
                    structs[name] = "struct(%s)" % ",".join(resolved)
                    progress = True
            if not progress:
                return structs

    def qualified(self, simple):
        """The fully qualified name of a type these packages declare, or None."""
        found = self.by_simple_name.get(simple)
        if found is None:
            return None
        source, local = found
        return qualified(source, local)

    def is_framework_class(self, class_name):
        """True when `class_name` names a class in one of the scanned packages."""
        return any(class_name.startswith(name + ".") for name in PACKAGE_NAMES)


def qualified(source, name):
    """The fully qualified name of a type a source declares."""
    return "%s.%s" % (source.package, name) if source.package else name


# ------------------------------------------------------------------ the calls

CALL = re.compile(r"([A-Za-z_$][A-Za-z0-9_$]*(?:\s*\.\s*[A-Za-z_$][A-Za-z0-9_$]*)*)\s*\(")


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


class Call:
    """One call, with the arguments it passes and where they are."""

    def __init__(self, source, callee, name_start, arguments, declaration):
        self.source = source
        self.callee = callee
        self.name_start = name_start
        self.arguments = arguments
        self.declaration = declaration

    @property
    def line(self):
        return line_of(self.source.text, self.name_start)


def calls(source):
    """Every call in a source.

    A call that begins inside a string literal is not a call: a literal that quotes
    code is documentation, and reading it as code is how a check invents a shape
    that nobody builds.
    """
    found = []
    for match in CALL.finditer(source.text):
        if in_literal(source.literals, match.start(1)):
            continue
        callee = re.sub(r"\s+", "", match.group(1))
        name_start = match.start(1) + match.group(1).rfind(callee.split(".")[-1])
        declaration = is_declaration(source.text, name_start)
        end = match_paren(source.text, match.end() - 1)
        if end < 0:
            continue
        arguments = []
        start = match.end()
        for argument in split_args(source.text[start:end - 1]):
            offset = source.text.find(argument, start, end - 1)
            arguments.append((argument, offset if offset >= 0 else start))
            start = offset + len(argument)
        found.append(Call(source, callee, name_start, arguments, declaration))
    return found


# -------------------------------------------------------------------- the file


def load_metadata():
    if not METADATA.is_file():
        sys.exit("no metadata file at %s" % METADATA)
    try:
        return json.loads(METADATA.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        sys.exit("%s is not valid JSON: %s" % (METADATA, error))


def shape_of(entry, where):
    """The shape of a metadata entry, or a message saying what is wrong with it."""
    if not isinstance(entry, dict):
        raise MetadataError("%s is not an object" % where)
    if "returnType" not in entry:
        raise MetadataError("%s has no 'returnType'" % where)
    if "parameterTypes" not in entry:
        raise MetadataError("%s has no 'parameterTypes'" % where)
    return (entry["returnType"], tuple(entry["parameterTypes"]))


def class_of(entry, where):
    """The class a metadata entry names, or a message saying what is wrong with it."""
    if not isinstance(entry, dict):
        raise MetadataError("%s is not an object" % where)
    if "class" not in entry:
        raise MetadataError("%s has no 'class'" % where)
    return entry["class"]


def method_of(entry, where):
    """The method a metadata entry names, or a message saying what is wrong with it."""
    if not isinstance(entry, dict):
        raise MetadataError("%s is not an object" % where)
    if "method" not in entry:
        raise MetadataError("%s has no 'method'" % where)
    return entry["method"]


# ----------------------------------------------------------------- the scan


class Scan:
    """What the sources say about the descriptors they build."""

    def __init__(self, package):
        self.package = package
        self.downcall_shapes = {}    # shape -> (source, line, how it was written)
        self.upcall_sites = []      # (class, method, shape, source, line)
        self.generic_upcall_shapes = {}   # shape -> (source, line, how) for stubs
                                          # whose target is not a named static method
        self.unreadable = []        # (source, line, how, reason)
        self.placed = set()         # the descriptors that reached a call site
        self.marker_calls = 0       # every call to a downcall or upcall helper
        self.call_sites = 0         # the ones that are calls and not declarations
        self.declarations = 0       # the helpers' own declarations
        self.stub_sites = 0         # every Linker.upcallStub call
        self.scan()

    def scan(self):
        for source in self.package.sources:
            for call in calls(source):
                if call.callee == "upcallStub" or call.callee.endswith(".upcallStub"):
                    if not call.declaration:
                        self.stub_sites += 1
                        self.stub(source, call)
                    continue
                kind = classify(call.callee)
                if kind is None:
                    continue
                self.marker_calls += 1
                if call.declaration:
                    self.declarations += 1
                    continue
                self.call_sites += 1
                if kind == "downcall":
                    for argument, offset in call.arguments:
                        self.place(source, call, argument, offset)
                else:
                    self.upcall(source, call)

    def place(self, source, call, argument, offset):
        """Account for one descriptor argument of a downcall, and record its shape."""
        shape = descriptor_of(argument, source, self.package)
        if shape is None:
            return
        self.placed.add((source, offset))
        constant = resolved_constant(argument, source, self.package)
        if constant is not None:
            self.placed.add(constant)
        for match in INLINE.finditer(argument):
            self.placed.add((source, offset + match.start()))
        self.downcall_shapes.setdefault(shape, (source, call.line, argument))

    def upcall(self, source, call):
        """Account for one upcall site, and record what it registers."""
        if len(call.arguments) < 3:
            self.unreadable.append((source, call.line, call.callee,
                                    "the call does not name a class, a method and a "
                                    "descriptor"))
            return
        class_argument, _ = call.arguments[0]
        method_argument, _ = call.arguments[1]
        descriptor_argument, offset = call.arguments[2]

        class_match = re.fullmatch(r"([A-Za-z_$][A-Za-z0-9_$]*)\s*\.\s*class",
                                   class_argument)
        method_match = re.fullmatch(r'"([^"]*)"', method_argument)
        if not class_match or not method_match:
            self.unreadable.append((source, call.line, call.callee,
                                    "the class and the method are not written out here"))
            return
        class_name = self.package.qualified(class_match.group(1))
        if class_name is None:
            self.unreadable.append((source, call.line, call.callee,
                                    "the class %s is not declared in %s, so it is not "
                                    "checked here" % (class_match.group(1), source.label)))
            return
        shape = descriptor_of(descriptor_argument, source, self.package)
        if shape is None:
            self.unreadable.append((source, call.line, call.callee,
                                    "the descriptor is not written out here"))
            return
        self.placed.add((source, offset))
        constant = resolved_constant(descriptor_argument, source, self.package)
        if constant is not None:
            self.placed.add(constant)
        for match in INLINE.finditer(descriptor_argument):
            self.placed.add((source, offset + match.start()))
        self.upcall_sites.append((class_name, method_match.group(1), shape,
                                  source, call.line))

    def stub(self, source, call):
        """Account for one Linker.upcallStub call: its descriptor and its target.

        Two kinds of target exist, and GraalVM registers them differently. A named
        static method becomes a directUpcalls entry -- class, method and shape, the
        optimized form. Anything else (a bound instance method, a lambda, a handle
        that arrived as a parameter) cannot be named by class and method, so the
        metadata registers the shape alone in foreign.upcalls.
        """
        if len(call.arguments) < 2:
            self.unreadable.append((source, call.line, call.callee,
                                    "the call does not take a target and a descriptor"))
            return
        target_argument, _ = call.arguments[0]
        descriptor_argument, offset = call.arguments[1]

        shape = descriptor_of(descriptor_argument, source, self.package)
        if shape is None:
            # A stub built inside a helper from the descriptor it was handed:
            # NativeBlock.of and ObjC.upcall do exactly this. Their call sites are the
            # marker calls this check reads by class and method, so there is nothing
            # to read here.
            return
        self.placed.add((source, offset))
        constant = resolved_constant(descriptor_argument, source, self.package)
        if constant is not None:
            self.placed.add(constant)
        for match in INLINE.finditer(descriptor_argument):
            self.placed.add((source, offset + match.start()))

        target = handle_target(target_argument, source, self.package)
        if target is not None and target[0] == "static" and target[1]:
            class_name = self.package.qualified(target[1])
            if class_name is not None:
                self.upcall_sites.append((class_name, target[2], shape, source, call.line))
                return
        self.generic_upcall_shapes.setdefault(shape, (source, call.line, target_argument))


def named_constant(argument):
    """The constant name an argument names, or None."""
    argument = argument.strip()
    if not re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*(\s*\.\s*[A-Za-z_$][A-Za-z0-9_$]*)?",
                        argument):
        return None
    return re.sub(r"\s+", "", argument)


def resolved_constant(argument, source, package):
    """The (source, name) of the constant an argument names, or None.

    A qualified name names the constant in the class it is qualified by, so
    'ObjC.VOID1' is the constant VOID1 of ObjC -- which is what the file that
    declares it is asked about.
    """
    token = named_constant(argument)
    if token is None:
        return None
    if "." in token:
        owner, _, name = token.rpartition(".")
        other = package.by_type_name.get(owner)
        if other is None:
            return None
        return (other, name)
    return (source, token)


def descriptor_of(argument, source, package):
    """The shape of an argument, whether it is written out or named."""
    argument = argument.strip()
    found = INLINE.match(argument)
    if found:
        end = match_paren(argument, found.end() - 1)
        if end < 0:
            return None
        args = split_args(argument[found.end():end - 1])
        return descriptor_shape(args, found.group(1) == "ofVoid", package.structs)
    return source.resolve(argument, package)


def handle_target(argument, source, package):
    """('static'|'virtual', class simple name or None, method or None) for a handle.

    The target of an upcallStub is written one of two ways: a MethodHandle constant
    assigned from LOOKUP.findStatic/findVirtual, or a call to a local helper that
    wraps one of them (Com.mh and Win32.mh both wrap findVirtual). Following both is
    what decides whether the stub can be registered by class and method or only by
    shape.
    """
    expression = argument.strip()
    token = named_constant(expression)
    if token is not None:
        expression = assigned_expression(token, source, package) or ""

    kind = handle_kind(expression, source)
    class_match = re.search(r"([A-Za-z_$][A-Za-z0-9_$]*)\s*\.\s*class", expression)
    method_match = re.search(r'"([^"]*)"', expression)
    if kind is None:
        return None
    if class_match is not None and method_match is not None:
        return (kind, class_match.group(1), method_match.group(1))
    return (kind, None, None)


def assigned_expression(token, source, package):
    """The expression a MethodHandle constant is assigned from, or None."""
    owner = source
    name = token
    if "." in token:
        qualifier, _, name = token.rpartition(".")
        owner = package.by_type_name.get(qualifier)
        if owner is None:
            return None
    match = re.search(r"\b" + re.escape(name) + r"\s*=\s*(.*?);", owner.text, re.S)
    return match.group(1).strip() if match else None


def handle_kind(expression, source):
    """'static', 'virtual', or None, for the expression a handle is built from."""
    if "findStatic" in expression:
        return "static"
    if "findVirtual" in expression:
        return "virtual"
    helper = re.match(r"\s*([A-Za-z_$][A-Za-z0-9_$]*)\s*\(", expression)
    if helper:
        body = helper_body(helper.group(1), source)
        if body is not None:
            if "findStatic" in body:
                return "static"
            if "findVirtual" in body:
                return "virtual"
    return None


def helper_body(name, source):
    """The declared body of a MethodHandle helper, near enough to see its lookup."""
    match = re.search(r"MethodHandle\s+" + re.escape(name) + r"\s*\(", source.text)
    if match is None:
        return None
    return source.text[match.start():match.start() + 1200]


# ----------------------------------------------------------------- the checks


def check_downcalls(metadata, package, scan, problems, notes):
    """Every shape the sources build in a downcall position is registered.

    Returns the shapes the file registers and the ones it does not, so that the
    report and the check cannot disagree about what is missing.
    """
    registered = {}
    for entry in metadata["foreign"]["downcalls"]:
        shape = shape_of(entry, "a downcalls entry")
        registered[shape] = entry

    missing = []
    for shape, (source, line, how) in sorted(scan.downcall_shapes.items()):
        if shape not in registered:
            missing.append(shape)
            problems.append("the sources build %s but the file does not register it "
                            "(%s/%s:%d, %s)"
                            % (shape_text(shape), source.label, source.where, line, how))

    for shape in sorted(registered):
        if shape not in scan.downcall_shapes:
            notes.append("the file registers %s and no source in the scanned packages "
                         "builds it" % shape_text(shape))

    # Every descriptor the sources build has to be accounted for. A descriptor this
    # check cannot place in a downcall or an upcall position is one whose shape it
    # cannot vouch for, and a shape nobody vouched for is a native build that dies on
    # the first downcall.
    for source in package.sources:
        for name, shape in sorted(source.constants.items()):
            if (source, name) in scan.placed:
                continue
            problems.append(
                "%s/%s.%s is %s and the check cannot see where it is used: it is not an "
                "argument of a downcall or of an upcall, so the check cannot vouch for it"
                % (source.label, source.where, name,
                   shape_text(shape) if shape else "a descriptor of an unreadable shape"))
        for offset, shape in inline_descriptors(source, package):
            if (source, offset) in scan.placed:
                continue
            problems.append(
                "%s/%s:%d builds a descriptor the check cannot place: it is not an "
                "argument of a downcall or of an upcall, so the check cannot vouch for it"
                % (source.label, source.where, line_of(source.text, offset)))

    return set(registered), missing


def inline_descriptors(source, package):
    """The FunctionDescriptor constructions in a file that are not a constant's own."""
    found = []
    for match in INLINE.finditer(source.text):
        if in_literal(source.literals, match.start()):
            continue
        before = source.text[:match.start()]
        # 'FunctionDescriptor NAME =' is the constant's own initializer; the
        # constant is accounted for by name instead.
        if re.search(r"FunctionDescriptor\s+\w+\s*=\s*$", before, re.S):
            continue
        end = match_paren(source.text, match.end() - 1)
        if end < 0:
            continue
        args = split_args(source.text[match.end():end - 1])
        shape = descriptor_shape(args, match.group(1) == "ofVoid", package.structs)
        found.append((match.start(), shape))
    return found


def check_upcalls(metadata, package, scan, problems, notes):
    """Every upcall site the sources spell out has a directUpcalls entry."""
    entries = {}
    for entry in metadata["foreign"]["directUpcalls"]:
        key = (class_of(entry, "a directUpcalls entry"),
               method_of(entry, "a directUpcalls entry"))
        entries.setdefault(key, []).append(entry)

    for class_name, method, shape, source, line in scan.upcall_sites:
        found = entries.get((class_name, method))
        if not found:
            problems.append("%s.%s is used as an upcall (%s/%s:%d) but the file has no "
                            "directUpcalls entry for it"
                            % (class_name, method, source.label, source.where, line))
            continue
        for entry in found:
            if shape_of(entry, "a directUpcalls entry") != shape:
                problems.append("the directUpcalls entry for %s.%s registers %s but the "
                                "call site builds %s"
                                % (class_name, method,
                                   shape_text(shape_of(entry, "a directUpcalls entry")),
                                   shape_text(shape)))


def check_generic_upcalls(metadata, scan, problems, notes):
    """Every upcall whose target is not a named static method has a shape entry.

    A bound instance method, a lambda and a MethodHandle that arrived as a parameter
    cannot be named in the metadata; GraalVM registers those stubs by descriptor in
    foreign.upcalls. A shape missing there fails at run time exactly like a missing
    downcall, so it is checked exactly like one.
    """
    registered = set()
    for entry in metadata["foreign"].get("upcalls", []):
        registered.add(shape_of(entry, "an upcalls entry"))

    for shape, (source, line, how) in sorted(scan.generic_upcall_shapes.items()):
        if shape not in registered:
            problems.append(
                "the sources build %s for an upcall whose target is not a named static "
                "method, and the file does not register it in foreign.upcalls "
                "(%s/%s:%d, %s)"
                % (shape_text(shape), source.label, source.where, line, how))

    for shape in sorted(registered):
        if shape not in scan.generic_upcall_shapes:
            notes.append("the file registers the upcall shape %s and no source in the "
                         "scanned packages builds it" % shape_text(shape))


def check_reflection(metadata, package, scan, problems, notes):
    """The registrations and the reflection entries point at real methods."""
    reflection = metadata["reflection"]
    reflection_entries = {}
    for entry in reflection:
        class_name = entry.get("type")
        if class_name is None:
            raise MetadataError("a reflection entry has no 'type'")
        for method in entry.get("methods", []):
            reflection_entries.setdefault((class_name, method.get("name")), []).append(method)

    for entry in metadata["foreign"]["directUpcalls"]:
        class_name = class_of(entry, "a directUpcalls entry")
        method = method_of(entry, "a directUpcalls entry")
        shape = shape_of(entry, "a directUpcalls entry")
        declared = package.by_qualified_name.get(class_name)
        if declared is None:
            if package.is_framework_class(class_name):
                problems.append("the directUpcalls entry for %s.%s names a class that is "
                                "not declared in the scanned packages" % (class_name, method))
            continue
        source, local = declared
        found = source.method(method, local)
        if found is None:
            problems.append("the directUpcalls entry for %s.%s names a method that is not "
                            "in %s" % (class_name, method, source.where))
            continue
        return_type, parameters = found
        expected = [carrier(parameter) for parameter in shape[1]]
        if [simple_name(p) for p in parameters] != [simple_name(p) for p in expected]:
            problems.append("%s.%s takes (%s) but its directUpcalls entry registers (%s)"
                            % (class_name, method,
                               ", ".join(parameters), ", ".join(expected)))
        if simple_name(return_type) != simple_name(carrier(shape[0])):
            problems.append("%s.%s returns %s but its directUpcalls entry registers %s"
                            % (class_name, method, return_type, shape[0]))
        if (class_name, method) not in reflection_entries:
            problems.append("%s.%s has a directUpcalls entry but no reflection entry"
                            % (class_name, method))

    for entry in reflection:
        class_name = entry["type"]
        declared = package.by_qualified_name.get(class_name)
        if declared is None:
            if package.is_framework_class(class_name):
                problems.append("the reflection entry for %s names a class that is not "
                                "declared in the scanned packages" % class_name)
            continue
        source, local = declared
        for method in entry.get("methods", []):
            found = source.method(method.get("name"), local)
            if found is None:
                problems.append("the reflection entry for %s.%s names a method that is not "
                                "in %s" % (class_name, method.get("name"), source.where))
                continue
            _, parameters = found
            declared = [simple_name(p) for p in method.get("parameterTypes", [])]
            if declared != [simple_name(p) for p in parameters]:
                problems.append("%s.%s takes (%s) but the reflection entry says (%s)"
                                % (class_name, method.get("name"),
                                   ", ".join(parameters), ", ".join(declared)))


# ----------------------------------------------------------------- the report


def report(metadata, package, scan, problems, notes, registered, missing):
    """Print what was checked, and what was found."""
    direct_only = {shape for _, _, shape, _, _ in scan.upcall_sites} - set(scan.downcall_shapes)
    generic_only = set(scan.generic_upcall_shapes) - set(scan.downcall_shapes) - direct_only

    print("the native-image metadata and the %s sources" % ", ".join(PACKAGE_NAMES))
    print()
    print("  the sources")
    print("    %d calls to a downcall or an upcall helper: %d call sites, %d declarations"
          % (scan.marker_calls, scan.call_sites, scan.declarations))
    print("    %d Linker.upcallStub sites" % scan.stub_sites)
    print()
    print("  downcall shapes")
    print("    the sources build %d downcall shapes; the file registers %d"
          % (len(scan.downcall_shapes), len(registered)))
    if direct_only:
        print("    %d more shape(s) are only ever direct upcalls, so they belong in "
              "directUpcalls and not in downcalls: %s"
              % (len(direct_only), ", ".join(shape_text(s) for s in sorted(direct_only))))
    if generic_only:
        print("    %d more shape(s) are only ever generic upcalls, so they belong in "
              "upcalls: %s"
              % (len(generic_only), ", ".join(shape_text(s) for s in sorted(generic_only))))
    if missing:
        print("    used but not registered:")
        for shape in sorted(missing):
            source, line, how = scan.downcall_shapes[shape]
            print("      %-30s <- %s/%s:%d (%s)"
                  % (shape_text(shape), source.label, source.where, line, how))
    else:
        print("    every shape the sources build is registered")
    print()
    print("  upcall registrations")
    print("    %d direct upcall sites, %d generic upcall shapes; the file registers "
          "%d directUpcalls entries and %d upcalls entries"
          % (len(scan.upcall_sites), len(scan.generic_upcall_shapes),
             len(metadata["foreign"]["directUpcalls"]),
             len(metadata["foreign"].get("upcalls", []))))
    if scan.unreadable:
        print("    %d site(s) cannot be resolved statically, so they are not checked:"
              % len(scan.unreadable))
        for source, line, callee, reason in scan.unreadable:
            print("      %s/%s:%d  %s(...)  -- %s"
                  % (source.label, source.where, line, callee, reason))
    else:
        print("    every upcall site has a registration of the same shape")
    print()
    print("  reflection")
    framework_entries = [e for e in metadata["reflection"]
                         if package.is_framework_class(e["type"])]
    other_entries = [e for e in metadata["reflection"]
                     if not package.is_framework_class(e["type"])]
    print("    %d framework entries checked; %d entries name JDK classes and are not "
          "checked here" % (len(framework_entries), len(other_entries)))

    if notes:
        print()
        print("  notes")
        for note in notes:
            print("    - %s" % note)

    if problems:
        print()
        print("%d problem(s):" % len(problems))
        for problem in problems:
            print("  - %s" % problem)


def main():
    metadata = load_metadata()
    foreign = metadata.get("foreign") or {}
    for section in ("downcalls", "directUpcalls", "upcalls"):
        if foreign.get(section) is None:
            sys.exit("%s has no 'foreign.%s'" % (METADATA, section))
    if metadata.get("reflection") is None:
        sys.exit("%s has no 'reflection'" % METADATA)

    package = Package(PACKAGE_DIRS)
    if not package.sources:
        sys.exit("no Java sources in %s" % ", ".join(str(d) for d in PACKAGE_DIRS))

    scan = Scan(package)
    problems = []
    notes = []
    try:
        registered, missing = check_downcalls(metadata, package, scan, problems, notes)
        check_upcalls(metadata, package, scan, problems, notes)
        check_generic_upcalls(metadata, scan, problems, notes)
        check_reflection(metadata, package, scan, problems, notes)
        report(metadata, package, scan, problems, notes, registered, missing)
    except MetadataError as error:
        sys.exit("%s: %s" % (METADATA, error))

    if problems:
        sys.exit("the native-image metadata does not match the sources")


if __name__ == "__main__":
    main()
