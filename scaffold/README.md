# panamaview-scaffold

Creates a panamaview project from one of the templates in this repository.

```
panamaview create <dir> [options]
panamaview templates
```

```console
$ panamaview templates
panamaview 0.1.0

  vanilla           no Node       plain HTML, CSS and JS — no build step
  vanilla-tailwind  no Node       plain HTML styled with Tailwind
  react             needs Node    React, built by Vite
  vue               needs Node    Vue 3 with a history-mode router, built by Vite
  angular           needs Node    Angular with a history-mode router

  'needs Node' is about editing and building, not running: a released app
  is a native executable plus static files, with no Node anywhere.

$ panamaview create my-chat-app --template vue
creating /work/my-chat-app
  template  vue               Vue 3 with a history-mode router, built by Vite
  package   com.example.mychatapp
  title     My Chat App
  18 files written

next
  cd /work/my-chat-app
  ./dev.sh          dev server with hot reload   (needs Node)
  ./run.sh          production build, served from the app's own scheme
  ./build-native.sh → target/my-chat-app

  README.md has the rest.
```

## Options

| | |
| --- | --- |
| `-t, --template <name>` | which template. Without it you are asked — unless there is no terminal, in which case it is required and the error says so. |
| `-p, --package <pkg>` | Java package. Default `com.example.<dir>`. |
| `--title <text>` | window and page title. Default: the directory name, title-cased. |
| `-f, --force` | write into a directory that is not empty. |

Defaults come from the directory name, so `panamaview create my-chat-app --template vue` needs
nothing else: the artifact is `my-chat-app`, the package `com.example.mychatapp`, the title
`My Chat App`, and the executable `target/my-chat-app`.

`create .` works too, and takes its name from the directory you are standing in.

## Before it will build

A generated project depends on `io.github.lihuu:panamaview`, so that artifact has to be resolvable.
Until it is published to Maven Central, there are two ways:

```sh
# in the panamaview checkout — resolvable on this machine only
mvn install -DskipTests

# or publish it somewhere, so a generated project builds on a machine that has no checkout
./scripts/deploy-private.sh
```

The second is what makes the scaffolder useful to anyone but its author. It is verified the same way
everything else here is: after uploading, it resolves both artifacts back from an empty local
repository and compares hashes. An upload that returns 201 is not evidence that another machine can
fetch what was built.

## When the package carries the framework

A generated project depends on `io.github.lihuu:panamaview`, which is not on Maven Central, so a
scaffolder delivered through npm or Homebrew carries the framework with it: the package ships the jar
and its pom laid out as a small Maven repository, and the project it generates is pointed at that
copy with a `file://` repository. There is nothing to configure on the far machine, and no private
repository to be told about.

It is found without being told where to look. There are four rules, in this order, and no searching:

| | |
| --- | --- |
| `PANAMAVIEW_LIBRARY` | if it is set and not empty. Set, but not a library, is an error rather than a fall-back — someone who names a directory meant that directory. |
| `<dir of the executable>/lib` | a Homebrew package: binary in `bin/`, library in `lib/`. |
| `<dir of the executable>/../lib` | an npm package: binary in `bin/`, library in `../lib/`. |
| nothing | a jar run out of a checkout. No copy, no repository, no comment — the framework comes from a repository or from `mvn install`, exactly as it always has. |

"Looks like a library" means the framework's jar *and* pom, at the version this scaffolder generates
projects for, inside `io/github/lihuu/panamaview/<version>/`. A directory with the right name and the
wrong version is not used: a generated project pins one version, and a copy at another would surface
later as an unresolved dependency, where the cause is not visible.

The directory of the executable comes from `ProcessHandle`, and it is the path as invoked rather than
the resolved one. That is deliberate: Homebrew's `bin` entry is a symlink into the Cellar, and the
`lib` beside that symlink is exactly where the library is meant to be found. A launcher that execs a
symlink to the binary therefore has to exec the binary inside the package instead.

What a generated project gets:

```
my-chat-app/
  .panamaview/lib/io/github/lihuu/panamaview/<version>/   the framework, as a Maven repository
  pom.xml                    a <repositories> block, before <dependencies>
  .gitignore                 .panamaview/ appended to whatever the template had
```

The block declares `panamaview-bundled` at `file://${project.basedir}/.panamaview/lib` — relative to
the project, not fixed when it was generated, so a project that is moved or copied still builds. The
id is what Maven records beside the artifact it resolved, in `_remote.repositories`, which is what
makes "the copy in the project was used, not one from this machine" checkable rather than a claim.

`scripts/verify-scaffold-library.sh` does exactly that: it lays out a packaged scaffolder, generates
from every template, and then builds one of them with a settings file that declares no repositories
and an empty local Maven repository. In that build the copy inside the project is the only thing that
can supply the framework, and the origin Maven recorded for the jar says whether it did.

## How it is delivered

Through npm, as the package `create-panamaview`, and the package carries the framework the projects
it generates depend on — so generating a project needs no repository configured and no network:

```sh
npx create-panamaview create my-app --template vue
```

Two forms, one program. `npx` downloads into a cache and runs it, so nothing is installed and the
next `npx` may fetch a newer version. `npm install -g create-panamaview` puts the same executable
permanently on the PATH as `panamaview`, so afterwards it is `panamaview create my-app --template
vue`. The bytes run are identical either way; only who puts them there differs.

The npm package is built from this directory: `npm/scripts/build-package.sh` builds the native
executable and stages the framework beside it, and CI's `verify-npm-package.sh` installs the packed
tarball into a throwaway prefix and builds a generated project against an empty repository to prove
the carried copy is the one that gets used.

**A native executable, not a jar.** The scaffolder needs no `java.lang.foreign`, no WebKit and no
reachability metadata, so its native image is a plain one — and a launcher that needs a JVM to start
is a worse answer for the one job this thing has. It is macOS arm64 only, and the package declares
that with `os`/`cpu`, so npm refuses the install rather than producing a command that cannot run.

The Maven artifact `io.github.lihuu:panamaview-scaffold` also exists in the private repository, put
there by `scripts/deploy-private.sh`. It is how the framework itself is distributed for people
building against it on a machine that has the repository configured. It is not the way a user gets
the scaffolder.

## Building it in this checkout

Building this needs a POSIX shell, so it is macOS or Linux. On Windows the reactor stops at this
module: `scripts/embed-templates.sh` is `sh` and reads the git index through it, and a Windows `sh`
(which is Git Bash) is not the same environment — measured on Windows 11, the staging step failed on
`dirname` being a WinGet shim and on `$(cd .. && pwd)` coming back as `//templates`. That does not
reach anyone using the scaffolder, because the scaffolder is *distributed* as a built artifact
rather than built per user; it is a limit on contributing to it from Windows.

This module is part of the repository's Maven reactor, so it is built from the repository root:

```sh
cd ..                    # the repository root, where the parent pom is
mvn -q install           # the framework, the scaffolder and the demo
cd scaffold && ./build-native.sh        # → target/panamaview, a native executable
mvn -q package                          # → target/panamaview-scaffold-<version>.jar
```

`mvn -q install` at the root rather than `mvn package` here, because the scaffolder inherits the
version from the parent pom: Maven has to be able to resolve that parent, and the framework has to
be installed before the templates staged into this jar are generated against it.

The native form is for the loop around the scaffolder: `scripts/verify-scaffold.sh` runs it, and it
makes working on a template a build rather than a jar swap. It is not the delivery format — see
above.

## How the templates get in

`templates/` is staged into the jar by `scripts/embed-templates.sh` at build time, along with a
manifest of what was staged. The manifest is the scaffolder's single source of truth about which
templates exist and which files they contain.

Four details there are load-bearing, and each one was a real failure:

**"Which files are a template?" is answered by `git ls-files`, not `find`.**
`find templates -type f` walks `node_modules/`, `dist/` and `target/` — gigabytes of build output
that is not part of any template. And an exclusion list cannot be made correct: the
`vanilla-tailwind` template generates `tailwind.css` and `manifest.txt` *inside its own source
tree*, so pruning by directory name misses them. Version control is the only answer that stays
right, because that is exactly the question it already answers.

**`addDefaultExcludes` has to be turned off twice.**
Maven's default excludes include `.gitignore`, and they are applied by the resources plugin on the
way into `target/classes` *and* by the jar plugin on the way into the jar. Avoiding the same trap in
the staging script is not enough — the files get dropped a second time, and the result is a
generated project with no `.gitignore`, which commits `target/` and `node_modules/`.

**The executable bit is recorded per file.**
A jar carries no file modes, so `run.sh` would come back out `0644` and `./run.sh` would fail with
"permission denied" — in a project that otherwise looks complete. The manifest marks the files that
git recorded as `100755`.

**Every template directory must appear in the manifest.**
A template that exists on disk but is not committed yet would otherwise be skipped without a word,
and the scaffolder would quietly offer one fewer choice than the repository has. The build fails
instead.

## How a project gets renamed

A template carries real values — `com.example`, `myapp`, `My App` — rather than placeholders, so
that a template stays a working project on its own: you can copy one out of the repository by hand,
and `scripts/verify-templates.sh` builds them straight from the working tree.

Substitution is a **single pass** over an alternation, not three sequential replaces. That is not
style. With sequential replaces, `--package com.example.myapp` would have the package rewritten by
the later `myapp` rule, silently yielding `com.example.<app>`. One pass cannot rewrite text it just
inserted.

Both spellings of a package are handled, in both file contents and file paths — the templates'
READMEs name `src/main/java/com/example/Main.java`, so the slash form appears inside a *text* file
too. Handling only the dot form left that one behind.

Text versus binary is decided from the bytes, not from an extension list: the one binary file in the
templates is a `.ico`, and an extension list has to be maintained. Nothing depends on the guess
being right — a file wrongly treated as binary keeps its original tokens, and the verification fails
on a single surviving one.

## Verifying it

```sh
./scripts/verify-scaffold.sh              # every template the manifest lists
./scripts/verify-scaffold.sh vue          # just one, while iterating
```

It builds the jar, generates each template into an unrelated directory, and takes the result to a
native executable **using the generated project's own scripts**. Two checks, failing for different
reasons:

- **A leftover token** means a rename was missed. The project may still build, and it is wrong.
- **A build failure** means a coupling was missed that no rename could have fixed — a path derived
  from the app name, or a name shape Maven or Angular will not accept. The residual check passes
  cleanly on a project like that, because every token *was* substituted.

The generated project uses a name with a hyphen and a package outside `com.example`, so that a
leftover token cannot hide inside a correct one: searching a project whose package is
`com.example.x` for `com.example` matches the right answer. An earlier version of this check did
exactly that, and passed while proving nothing.

## What is checked, and what is not

Verified: generation, the absence of leftover names, file permissions, the JVM build, the native
build, and the asset-staging check inside the native executable.

Not verified: a window actually opening. CI has no window server, so the headless check is what is
left — it stages the assets and resolves every `src`/`href` in the built page against them, which
catches the failure that matters most (page loads, script does not, window is blank).
