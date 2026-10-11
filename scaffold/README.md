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

## How it is delivered

As a Maven artifact, the same way the framework is:

```sh
mvn dependency:get -Dartifact=io.github.lihuu:panamaview-scaffold:<version>
java -jar ~/.m2/repository/io/github/lihuu/panamaview-scaffold/<version>/panamaview-scaffold-<version>.jar \
     create my-app --template vue
```

Published to the same private repository as the framework, by the same `scripts/deploy-private.sh`,
which resolves both back from an empty local repository afterwards. Neither is on Maven Central:
that waits on evidence that someone has built a real application on this, which is a claim this
repository cannot make about itself yet.

**A jar, not a native executable.** The native build works — 13 MB, no foreign registration, no
reachability metadata, no JVM flags — but shipping it would mean one build per platform attached to
a release page, and this repository no longer attaches artifacts to releases. A jar is one file that
runs wherever the JDK does. That is why `mvn dependency:get` is the documented way to get it rather
than a download link.

## Building it in this checkout

```sh
./build-native.sh        # → target/panamaview, a native executable
mvn -q package           # → target/panamaview-scaffold-<version>.jar, runnable with java -jar
```

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
