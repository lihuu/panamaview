# Migrating to panamaview 1.0

`panamaview` 1.0.0 moves every class the framework ships into a package named after
the coordinates it is published under. This guide is the whole of that change; beyond
renaming what you import, there is nothing to do.

---

## 1. The rename

| Artifact | Package before | Package in 1.0.0 |
| :--- | :--- | :--- |
| `io.github.lihuu:panamaview` | `panamaview` | `io.github.lihuu.panamaview` |
| `io.github.lihuu:panamaview` | `panamaview.macos` | `io.github.lihuu.panamaview.macos` |
| `io.github.lihuu:panamaview` | `panamaview.windows` | `io.github.lihuu.panamaview.windows` |
| `panamaview-demo` (in this repository, not published) | `panamaview.demo` | `io.github.lihuu.panamaview.demo` |
| `panamaview-windows-probe` (in this repository, not published) | `panamaview.probe` | `io.github.lihuu.panamaview.probe` |

The rule is uniform: **prefix, never restructure**. `panamaview.X` became
`io.github.lihuu.panamaview.X`. No class was renamed, moved between subpackages, made
public, or hidden, so a mechanical rewrite of the package prefix is the entire
migration.

On disk, the sources moved with their packages —
`framework/src/main/java/panamaview/` is now
`framework/src/main/java/io/github/lihuu/panamaview/`.

## 2. Why this happened at 1.0.0

The framework has been published as `io.github.lihuu:panamaview` since its first
release, while its classes lived in a bare top-level `panamaview.*`. The two namespaces
disagreed, and they cannot both be right. A `groupId` is an identity the publisher can
prove — here a GitHub account — whereas a bare top-level package is a claim on the
JVM's global namespace that nothing backs and that any other project may already have
taken.

The rename is unavoidably source-incompatible: every `import` of a framework class
changes. That is what a major version is for, and 1.0.0 is the release that is allowed
to be one. It is also the cheapest moment available — 0.3.0 is the only version on
Maven Central, so there is no installed base to break, and every release after this one
would only make the rename more expensive.

## 3. Migrating a project

### Step 1 — move the dependency to 1.0.0

```xml
<dependency>
  <groupId>io.github.lihuu</groupId>
  <artifactId>panamaview</artifactId>
  <version>1.0.0</version>
</dependency>
```

The `groupId` and `artifactId` are unchanged; only the version moves.

### Step 2 — rewrite the imports

```sh
# 1. import statements
grep -rl '^import panamaview\.' --include='*.java' . \
  | xargs sed -i '' -e 's/^import panamaview\./import io.github.lihuu.panamaview./'
# On GNU sed (Linux), drop the empty '' after -i.

# 2. fully-qualified references, e.g. `new panamaview.windows.WindowsHost()`
grep -rl -E '(^|[^.A-Za-z0-9_])panamaview\.(macos\.|windows\.|probe\.|demo\.)?[A-Z]' --include='*.java' . \
  | xargs sed -i '' -E 's/([^.A-Za-z0-9_])panamaview\.(macos\.|windows\.|probe\.|demo\.)?([A-Z])/\1io.github.lihuu.panamaview.\2\3/g'
```

Both passes are safe to re-run: the second cannot match what the first wrote, because
the new prefix puts a `.` in front of the word. An IDE's *replace in path* over
`panamaview.` → `io.github.lihuu.panamaview.` does the same job with more review, which
is a reasonable way to do it on a large tree.

Neither pass touches the runtime names that share the word but are not packages —
`"panamaview.check"`, `"panamaview.js"` and the rest of the list in section 5 — because
it only rewrites a prefix that is followed by a class name.

### Step 3 — rebuild

Then re-run whatever you use to check the build. There is no `module-info.java` and no
`META-INF/services` file in the framework, so no `--add-exports`, `--add-opens` or
service declaration refers to these packages and none needs an edit.

### Before and after

```java
// 0.3.0
import panamaview.Resources;
import panamaview.UiHost;
import panamaview.WebApp;
import panamaview.macos.MacOsHost;

// 1.0.0
import io.github.lihuu.panamaview.Resources;
import io.github.lihuu.panamaview.UiHost;
import io.github.lihuu.panamaview.WebApp;
import io.github.lihuu.panamaview.macos.MacOsHost;
```

## 4. Names in strings

The compiler finds imports for you. It does not find a class named inside a string, and
that kind of reference fails in a packaging step or at run time rather than at compile
time. If your project has any of these, they need the same prefix:

- `main.class` in a pom or a launch script, if it points at a framework class;
- a `--initialize-at-build-time=`/`--initialize-at-run-time=` argument naming a framework
  package;
- a `-classpath` invocation or a `Class.forName` naming one;
- a `reachability-metadata.json` of your own that registers framework classes for
  reflection or foreign calls.

The framework's own `reachability-metadata.json` ships inside the jar and has been
updated along with the classes, so a consumer's Native Image build picks the new names
up transitively and needs no edit.

## 5. What deliberately did not change

Only package identifiers moved. Several names in this project share the word *panamaview*
without being packages, and renaming them would be a second, unrelated breaking change to
the public surface:

- the `[panamaview]` log prefixes;
- the `panamaview.js` client bridge and the `/panamaview/` resource path it is served from;
- the `panamaview://app/` URL scheme on macOS and the `https://app.panamaview/` origin on
  Windows;
- the `.panamaview` directory a generated project carries its framework copy in, and the
  `panamaview-bundled` repository id declared inside it;
- the `panamaview.*` system properties — `panamaview.dev`, `panamaview.check`,
  `panamaview.snapshot`, `panamaview.navigateAway`, `panamaview.webview2.loader`;
- the Maven coordinates `io.github.lihuu:panamaview` and
  `io.github.lihuu:panamaview-parent`.

Two more are untouched on purpose, and they are the ones most likely to be mistaken for
an oversight:

- `templates/*/src/main/java/com/example/Main.java` still declares `package com.example`.
  That is the placeholder namespace a generated project renames to its own, through
  `create-panamaview --package`; it is what a template is for, and it was never
  panamaview's package to move.
- Each template's own `<version>`, and each template's frontend `package.json` version.
  Those are the version of the *application* a template describes, not of the framework.
  Tying them together would make a generated project's version jump every time
  panamaview released.

## 6. The npm side moves with it

`create-panamaview` is released at 1.0.0 in lockstep with the framework. A project
generated by it pins `panamaview` 1.0.0 and imports the new packages from the first line
of its `Main.java`, so nothing generated from 1.0.0 needs this guide at all.

Only a project generated by `create-panamaview` 0.3.0 — or written by hand against
0.3.0 — does.

## 7. There is no compatibility shim

1.0.0 ships one namespace, not two. No class is kept under `panamaview.*` as a deprecated
delegate to its new name.

A shim would have to live forever, or be deleted later as a second breaking change. In
the meantime every class in the framework would carry two names, two entries in the
documentation and two ways for a bug report to arrive — all of it paid to spare builds
that a mechanical edit to their imports fixes outright. The framework is at 1.0 with a
single downstream project, so a clean break now is the honest trade against a permanent
second surface.

One consequence is worth stating plainly: because there is no shim, a build left at
0.3.0's imports does not fail with a message about a rename. It fails with
`package panamaview does not exist`, which is the compiler naming the exact line to
change.
