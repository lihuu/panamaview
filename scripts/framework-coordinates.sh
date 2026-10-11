#!/bin/sh
# The framework's own coordinates, read from the pom that will actually be published.
#
# Source this from anywhere inside the checkout:
#
#   . scripts/framework-coordinates.sh
#   echo "$FRAMEWORK_VERSION"
#
# Why this exists as a file rather than a line in each script. The build is a reactor: the root pom
# is the parent and aggregator, and the framework is a module under framework/. Everything
# downstream still needs to know what the *framework* is called — its version goes into the
# templates' pins, the npm package's own version, and every bundled Maven repository — and there
# used to be five separate copies of "read the first <version> in pom.xml". Five copies of a rule is
# five chances for one of them to keep pointing at the root pom after the root pom stopped being the
# framework. That is exactly what happened when this layout landed: a guard read the parent's
# coordinates, found no <artifactId>panamaview</artifactId> beside the version it wanted, and
# crashed with a NoneType traceback.
#
# So the location is stated once, here, and the scripts ask. Moving the framework's pom is one edit
# rather than a hunt.
#
# FRAMEWORK_POM is overridable for the same reason PANAMAVIEW_LIBRARY is: a test that wants to check
# the failure path needs a way to point somewhere else without editing this file.
#
# Where the framework's pom is, found by walking up from here rather than by a fixed relative path.
# Callers do not share a working directory — Maven runs scaffold/scripts/embed-templates.sh with the
# scaffolder as cwd, so `framework/pom.xml` would resolve under scaffold/. Walking up terminates on
# the first directory holding both framework/pom.xml and the reactor's pom.xml; requiring both keeps
# it from latching onto some other framework/pom.xml further up the tree.
FRAMEWORK_POM="${FRAMEWORK_POM:-}"
if [ -z "$FRAMEWORK_POM" ]; then
    COORDINATES_DIR="$(pwd)"
    while [ "$COORDINATES_DIR" != "/" ]; do
        if [ -f "$COORDINATES_DIR/framework/pom.xml" ] && [ -f "$COORDINATES_DIR/pom.xml" ]; then
            FRAMEWORK_POM="$COORDINATES_DIR/framework/pom.xml"
            break
        fi
        COORDINATES_DIR="$(dirname "$COORDINATES_DIR")"
    done
fi

if [ -z "$FRAMEWORK_POM" ] || [ ! -f "$FRAMEWORK_POM" ]; then
    echo "framework-coordinates.sh: no framework/pom.xml above $(pwd)" >&2
    echo "framework-coordinates.sh: run from inside the panamaview checkout, or set FRAMEWORK_POM." >&2
    exit 1
fi

# The <parent> block is deleted before anything is read, and that is the whole reason this is not
# three lines of sed in each caller. The framework's pom inherits from the reactor's parent, so its
# first <groupId>, <artifactId> and <version> are the *parent's*. Reading the first of each would
# report io.github.lihuu:panamaview-parent — a real artifact, in this repository, at this version —
# so the wrong answer would look right and every check downstream would agree with it.
#
# sed rather than a python here-document, and the reason is not style: /bin/sh on macOS is bash 3.2,
# which loses its place in a script when a here-document appears inside a command substitution. It
# ran part of this file it had already passed and reported an unclosed quote at the bottom of it.
# npm/scripts/build-package.sh carries the same note for the same reason.
# What is left is the pom's header: its own coordinates, and nothing nested. Three things are cut,
# each because leaving it in produces a confidently wrong answer rather than an empty one:
#
#   <parent>…</parent>   the parent's <groupId>/<version> are the first in the file and are a real
#                        artifact at this version, so reading them "succeeds" and reports
#                        io.github.lihuu:panamaview-parent as the framework.
#   <!-- … -->           this repository explains itself in comments, and one of them reads "no
#                        groupId or version: both are inherited" — a sentence that a line-oriented
#                        read matches as markup.
#   <properties> onward  groupId and artifactId also name Maven plugins further down. Cutting at the
#                        first top-level section leaves only what precedes any nesting.
FRAMEWORK_HEADER="$(sed -e '/<parent>/,/<\/parent>/d' -e '/<!--/,/-->/d' "$FRAMEWORK_POM" \
    | sed -e '/<properties>/,$d' -e '/<dependencies>/,$d' -e '/<build>/,$d' \
          -e '/<modules>/,$d' -e '/<profiles>/,$d' -e '/<distributionManagement>/,$d')"

FRAMEWORK_GROUP="$(printf '%s\n' "$FRAMEWORK_HEADER" \
    | sed -n 's:.*<groupId>\([^<]*\)</groupId>.*:\1:p' | head -1)"
FRAMEWORK_ARTIFACT="$(printf '%s\n' "$FRAMEWORK_HEADER" \
    | sed -n 's:.*<artifactId>\([^<]*\)</artifactId>.*:\1:p' | head -1)"
FRAMEWORK_VERSION="$(printf '%s\n' "$FRAMEWORK_HEADER" \
    | sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' | head -1)"

# An empty groupId or version means the module inherits it from the reactor's parent, which is where
# the version lives when there is only meant to be one. Read it from the parent rather than making
# every caller know which of the two arrangements is in force — that question has one answer, and it
# belongs here.
FRAMEWORK_PARENT_POM="$(dirname "$FRAMEWORK_POM")/../pom.xml"
if [ -z "$FRAMEWORK_GROUP" ] && [ -f "$FRAMEWORK_PARENT_POM" ]; then
    FRAMEWORK_GROUP="$(sed -n 's:.*<groupId>\([^<]*\)</groupId>.*:\1:p' "$FRAMEWORK_PARENT_POM" | head -1)"
fi
if [ -z "$FRAMEWORK_VERSION" ] && [ -f "$FRAMEWORK_PARENT_POM" ]; then
    FRAMEWORK_VERSION="$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' "$FRAMEWORK_PARENT_POM" | head -1)"
fi

# Reported rather than defaulted. An empty coordinate that flows into a path would produce a
# repository laid out under a directory named after nothing, and the failure would surface as
# "artifact not found" a long way from the pom that could not be read.
for pair in "groupId=$FRAMEWORK_GROUP" "artifactId=$FRAMEWORK_ARTIFACT" "version=$FRAMEWORK_VERSION"; do
    case "$pair" in
        *=) echo "framework-coordinates.sh: ${pair%=} is missing from $FRAMEWORK_POM" >&2
            echo "framework-coordinates.sh: looked outside any <parent> block, then in the parent," >&2
            echo "framework-coordinates.sh: $FRAMEWORK_PARENT_POM" >&2
            exit 1 ;;
    esac
done

# The path the artifact sits at inside a Maven repository root: <group as directories>/<artifact>/
# <version>/. Derived rather than written down a second time, since that is the other thing every
# bundled layout has to agree about.
FRAMEWORK_REPO_PATH="$(printf '%s/%s/%s' \
    "$(printf '%s' "$FRAMEWORK_GROUP" | tr '.' '/')" "$FRAMEWORK_ARTIFACT" "$FRAMEWORK_VERSION")"

export FRAMEWORK_POM FRAMEWORK_GROUP FRAMEWORK_ARTIFACT FRAMEWORK_VERSION FRAMEWORK_REPO_PATH
