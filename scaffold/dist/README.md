# panamaview scaffolder

This archive contains the `panamaview` scaffolder and the framework it generates projects for.

```
panamaview                     the scaffolder, a native executable — no JVM needed to run it
lib/panamaview-<version>.jar   the framework
lib/panamaview-<version>.pom
```

```
$ ./panamaview templates

$ ./panamaview create my-app --template vue
creating /work/my-app
  template  vue               Vue 3 with a history-mode router, built by Vite
  package   com.example.myapp
  title     My App
  18 files written
```

The name, package and title come from the directory, so one option is usually enough.
`--template` is required when there is no terminal to ask on.

## Before the generated project will build

It depends on `io.github.lihuu:panamaview`, which is **not on Maven Central yet**, so the first
build fails with a message that names the artifact and not the fix:

```
[ERROR] Could not find artifact io.github.lihuu:panamaview:jar:<version> in central
```

The framework is in `lib/`, so either install that copy — once, into your local repository:

```sh
mvn install:install-file \
    -Dfile=lib/panamaview-<version>.jar \
    -DpomFile=lib/panamaview-<version>.pom
```

or point your `~/.m2/settings.xml` at a repository that has it, if you have one. Then:

```sh
cd my-app
./run.sh              # on the JVM
./build-native.sh     # → target/<app>, a standalone executable
```

## Not on Maven Central yet, on purpose

panamaview is at 0.1.0 and has not been used to build a real application. Until it has, the version
on Central would be a claim nobody has tested. `lib/` is here so that deciding to try it does not
also require a checkout of the repository.

## What each template needs

| template | frontend | needs Node |
| --- | --- | --- |
| `vanilla` | plain HTML, hand-written CSS | no |
| `vanilla-tailwind` | plain HTML + Tailwind CSS | no |
| `react` | React, built by Vite | yes |
| `vue` | Vue 3 with a history-mode router | yes |
| `angular` | Angular | yes |

Node is needed to **edit and build** three of them, never to run one: a released app is a native
executable plus static files.
