# Publishing panamaview

This guide explains how `panamaview` is published to private Maven repositories, to **Maven Central**, and how the scaffolder is published to **npm**.

---

## 1. Local / Private Deployment

When testing locally without installing from npm or Maven Central:

```sh
# Local install for this machine only
mvn install -DskipTests
```

To publish to a private team repository:

```sh
./scripts/deploy-private.sh
```

`deploy-private.sh` reads repository URLs and credentials from `~/.m2/settings.xml` rather than carrying them in the repository. It:
- Distinguishes release and snapshot repositories based on version.
- Rejects republishing a version that already exists.
- Resolves artifacts back into an isolated local repository to verify hash consistency (`--verify-only` runs this check standalone).

---

## 2. Publishing to Maven Central

`panamaview` is available on Maven Central under:
- **Group ID**: `io.github.lihuu`
- **Artifact ID**: `panamaview`
- **Catalog**: <https://repo1.maven.org/maven2/io/github/lihuu/panamaview/>

### The Central Publishing Pipeline

Modern Central publishing uses Sonatype's **Central Portal** rather than the legacy OSSRH / Nexus staging.

```sh
./scripts/publish-central.sh              # Build, sign, validate layout, upload
./scripts/publish-central.sh --release    # Publish the uploaded staging bundle
```

> **Why a script instead of `mvn -Pcentral deploy`?**  
> `central-publishing-maven-plugin` treats its own staging directory as a Maven local repository. The default install writes local bookkeeping files (`maven-metadata-local.xml`, `_remote.repositories`, checksums) into the tree it is about to publish. Central rejects these metadata files.  
> `publish-central.sh` coordinates the build and signing with the plugin, strips out unauthorized metadata files, validates the bundle against Central layout constraints, and uploads via the Central Portal API.

### Prerequisites for Central Publishing

1. **GPG Key**: Central requires signatures on all artifacts. Use an **RSA 4096 primary key** published to a keyserver (e.g. `keyserver.ubuntu.com`).
2. **Central Portal Token**: Configured in `~/.m2/settings.xml` under server ID `central`. Generate via <https://central.sonatype.com/usertoken>.
3. **Namespace Claim**: `io.github.lihuu` granted via GitHub authorization on the Sonatype Central Portal.

### Reactor Profile Details

The `-Pcentral` profile in `pom.xml` activates:
- `maven-source-plugin` & `maven-javadoc-plugin` (mandatory `-sources.jar` and `-javadoc.jar`).
- `maven-gpg-plugin` with `--pinentry-mode loopback`.
- `central-publishing-maven-plugin` for validation and bundle bundling.
- Exclusion of non-published application modules (`panamaview-demo`, `panamaview-windows-probe`).

---

## 3. Publishing to npm

The scaffolder ships as **`create-panamaview`** on npm:

- **Package**: `create-panamaview` — unscoped, so it is public by default
- **Entry point**: `npx create-panamaview create <dir> --template <vanilla|react|vue|angular|vanilla-tailwind>` — the subcommand is not optional; `npx create-panamaview <dir>` prints the usage text instead of scaffolding
- **What it carries**: `cli.js`, `src/`, the five templates under `templates/`, and the framework itself as a Maven repository root under `lib/`

This is the front door of the project, and it is the one artifact whose version is read by people who have never cloned the repository. A package left behind at an older version generates projects pinned to that older framework version, which is why `release.yml` checks `npm/package.json` against the tag like it checks the poms.

### Publishing is a manual step

`scripts/release.sh` moves `npm/package.json` to the release version along with the poms, and tags the commit — but it does not publish. CI does not either: `release.yml` stages the package with `build-package.sh` and asks `cli.js --selftest` to read every file it ships, then stops. Nothing in this repository holds a registry credential — there is no `NPM_TOKEN` in the workflows and no `setup-node` registry auth — so a tag push cannot publish under the maintainer's name.

So a release is two acts — the tag, then the publish:

```sh
./scripts/release.sh patch              # versions, tag, push
cd npm && npm publish --access public   # the scaffolder
```

`--access public` is redundant for an unscoped package — the default is already public — and it is kept only because the habit for scoped packages is hard to unlearn. It does no harm.

### What `npm publish` actually runs

`npm/package.json` declares one hook:

```json
"scripts": { "prepack": "./scripts/build-package.sh" }
```

`build-package.sh` builds the framework, lays the jar out as a Maven repository root under `lib/`, and copies the five templates into `templates/`. It has to be a hook rather than committed state, because npm's `files` allowlist cannot reach outside the package directory: nothing above `npm/` can be shipped as it stands.

⚠️ **`npm publish --ignore-scripts` publishes a package that cannot scaffold.** It skips the hook, so `lib/` and `templates/` are whatever happens to be sitting on disk — and on a fresh clone that is nothing. npm reports no problem while doing it: packing a tree that has only `cli.js`, `src/` and `package.json` with `--ignore-scripts` yields a three-file tarball and exits 0. The damage shows up later, on the user's machine, as a missing library or an empty template list. Publish without the flag and let the hook run.

### Verifying a publish

Before publishing, the same staging can be exercised without touching the registry:

```sh
./scripts/verify-npm-package.sh
```

After publishing, prove the package works from the registry rather than from the working tree:

```sh
npm view create-panamaview version                                    # what is actually live
cd /tmp && npx --yes create-panamaview@X.Y.Z create probe --template vanilla
cd probe && ./run.sh
```

The generated `pom.xml` must pin `X.Y.Z` and resolve it from Maven Central — so Central has to be published first, and a version published to npm before Central will fail at the first build rather than at `npm publish`.

### The first few minutes look like a failure

`npm publish` finishing is not the same as the version being installable, and both failure modes are indistinguishable from a real error unless you know to wait.

**The version is missing from the registry.** A successful publish answers `202 Accepted` and prints *"Your package is being processed and may take a few minutes to become available"*. During that window `curl https://registry.npmjs.org/<pkg>/<version>` answers `version not found: 1.0.0` and the tarball URL answers `404`. Measured on 1.0.0: publish at 17:31:56, version present at 17:34:27, tarball reachable at 17:34:39 — about three minutes.

**`npx` claims the version does not exist.** Two caches sit between you and a fresh publish. npm's local packument cache still holds the old metadata, so `npx <pkg>@X.Y.Z` fails with `ETARGET No matching version found`; the CDN edge also caches the earlier `404` for the tarball, so the same version can answer `200` with a cache-busting query string and `404` without one for a couple of minutes. Neither is a bad publish. Retry after the CDN entry expires (`ETARGET` → a minute; stale `404` → about three, measured), or ask the registry directly with a unique query string to see whether the artifact itself is there.

### Credentials

`npm publish` authenticates with the token in `~/.npmrc`, written by `npm login`. Granular tokens (`npm_…`) expire. When `npm whoami` answers `401`, the token is dead and `npm publish` fails before it reaches the registry — log in again (browser, plus the account's 2FA code) rather than searching for a flag.

A `401` *during* a publish is a different thing and needs nothing from you: npm sees it, opens the browser for web auth, polls `/-/v1/done` until you confirm, and re-sends the `PUT` itself. If the terminal appears to hang at that point, it is waiting on the browser tab it just opened.

One warning during publish is noise and worth recognising so it is not chased: `"bin[panamaview]" script name cli.js was invalid and removed`. Nothing is removed. npm normalises `./cli.js` to `cli.js` in the copy it sends and its change-log wording says "removed" — the published `bin` is intact (`{'panamaview': 'cli.js'}` on 1.0.0). Writing `"bin": { "panamaview": "cli.js" }` without the `./` keeps it quiet.
