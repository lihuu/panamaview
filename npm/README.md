# create-panamaview

A scaffolder for [panamaview](https://github.com/lihuu/panamaview) — a Java framework that drives
macOS's own web view, WKWebView, over the FFM API. No JNI, no JNA, no bundled browser engine.

```sh
npx create-panamaview create my-chat-app --template vue
cd my-chat-app
./run.sh
```

`create` is not optional. The scaffolder's first argument is its command — `panamaview create`,
`panamaview templates` — and this package's `bin` is that program. So `npx create-panamaview my-app`
is an unknown command, and says so.

## Requirements

**Node 18 or newer**, which is what npm is, on any platform. Generating a project needs nothing
else: no JVM, no Maven, no Apple Silicon. The scaffolder was a native executable, 13 MB per platform
for a job whose whole content is "copy a directory and rename three strings", and the package
declared `os: darwin` and `cpu: arm64` so that npm refused every other machine rather than hand over
a command that could not run. It is JavaScript now, and the declarations are gone.

**JDK 25 and Maven**, to build and run a project this generates. That is inherent to a Java
framework rather than to the scaffolder — generating a project needs neither, and neither does
`npx`. Building a standalone executable from a generated project additionally wants GraalVM with
`native-image`.

No generated app needs Node at run time; three of the five templates use it to build their frontend,
and `panamaview templates` marks which.

## What it ships

Both halves of the problem, inside the package, so that nothing is fetched from a repository at any
point:

- **the scaffolder**, as JavaScript, run by Node — generating a project needs no JVM
- **the framework**, `io.github.lihuu:panamaview`, laid out as a Maven repository

That second one is the point. The framework is not on Maven Central, so a generated project would
otherwise depend on an artifact that cannot be resolved. Instead the scaffolder copies the
framework into `<project>/.panamaview/lib` and points the generated `pom.xml` at it with a
`file://` repository:

```xml
<repository>
  <id>panamaview-bundled</id>
  <url>file://${project.basedir}/.panamaview/lib</url>
</repository>
```

So `./run.sh` is all a project needs. There is no repository to configure, nothing to
`mvn install` first, and — the quiet failure this removes — no *other* copy of the framework on the
machine that Maven could pick up instead of the one the project was generated with. The build log
names `panamaview-bundled` as the origin, and that is checkable rather than promised.

The carried copy is listed in the generated project's `.gitignore`: it is a build input, not source,
and it is replaced on the next generation.

## Usage

```
panamaview create <dir> [options]
panamaview templates
panamaview version | help

create options
  -t, --template <name>   which template to use; without it you are asked,
                          unless there is no terminal, in which case it is required
  -p, --package <pkg>     Java package for the app   [default: com.example.<dir>]
      --title <text>      window and page title      [default: from the directory]
  -f, --force             write into a directory that is not empty
```

The name, the package and the title all come from the directory, so one option is usually enough.
`create .` generates into the directory you are standing in. Nothing is overwritten without
`--force`, and a project directory with a space in it is fine.

| template | frontend | needs Node | routing |
| --- | --- | --- | --- |
| `vanilla` | plain HTML, CSS and JS — no build step | no | — |
| `vanilla-tailwind` | plain HTML styled with Tailwind | no | — |
| `react` | React, built by Vite | yes | — |
| `vue` | Vue 3 with a history-mode router, built by Vite | yes | history mode |
| `angular` | Angular with a history-mode router | yes | history mode |

Then, in the generated project:

```sh
./dev.sh            # dev server with hot reload, where the template has one
./run.sh            # build and run on the JVM
./build-native.sh   # → target/<app>, a standalone executable (needs GraalVM)
```

## Installing it permanently

`npx` fetches into a cache each time and runs it; a global install puts the same command on the
PATH, and after that it is `panamaview` rather than `npx create-panamaview`.

```sh
npm install -g create-panamaview     # then: panamaview create my-app --template vue
```

Check where npm's global prefix is first. If its `bin` directory is not on your PATH, neither is the
command — and that looks like a broken install rather than a misplaced one. Point `--prefix` at a
directory you know is on the PATH instead of changing npm's configuration:

```sh
npm install -g --prefix ~/.local create-panamaview
```

There was a Homebrew formula in the repository until 2026-10-12. It is gone, and deliberately not
replaced by one marked unmaintained: a formula that pins a release it cannot install from is worse
than no formula, and a tap only covers macOS while this package runs wherever npm does.

## Versions

The package version is the framework version. `npx create-panamaview@0.2.0` generates a project
pinned to `0.2.0` and carrying that release's jar, so the two cannot drift apart mid-project.
Upgrading the package changes what your *next* project pins; existing projects keep the copy they
were generated with until you generate them again.

## License

MIT — the same as panamaview.
