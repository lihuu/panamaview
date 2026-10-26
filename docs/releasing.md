# Release Workflow & Version Management

This document explains the release process and the strict version invariants maintained across the `panamaview` repository.

---

## 1. The Release Checklist

A release is three acts in this order: **a tag**, **a Maven Central publication**, and **an npm publication**. The first two are scripted; the third is the maintainer's, because npm's second factor is a passkey.

0. **Decide the version.** Semver from the last tag; new features mean a minor.

1. **Commit `<scm><tag>` before cutting.** `release.sh` deliberately does not rewrite it — the tag names a commit that does not exist yet — and a published pom is permanent. In the root `pom.xml`, set `<tag>vX.Y.Z</tag>`, commit, push.

2. **Cut it.**

   ```sh
   ./scripts/release.sh minor          # or patch / major / x.y.z
   ./scripts/release.sh --dry-run patch
   ```

   It requires `main` and a clean tree, runs `scripts/verify-templates.sh` (all five templates) and `scripts/verify-scaffold.sh` (generated project → native executable), bumps the ten version fields, commits `Release vX.Y.Z`, creates the annotated tag and pushes main + tag. **Destination: GitHub — source only, no artifacts.**

3. **The tag workflow.** `.github/workflows/release.yml` checks the tag against every version field and runs the scaffolder selftest. Nothing is uploaded.

4. **Upload to Central, validation only.** `./scripts/publish-central.sh` builds and GPG-signs, assembles the bundle, uploads it to the Sonatype Central Portal and polls to `VALIDATED`. It stops there and prints the deployment id. **Destination: a Central Portal deployment, not yet public.**

5. **Publish that deployment — irreversible.** POST `https://central.sonatype.com/api/v1/publisher/deployment/<id>` with the token from `~/.m2/settings.xml`, or run `publish-central.sh --release` to upload a fresh bundle and publish it. A published version cannot be withdrawn. **Destination: Maven Central (`repo1.maven.org`).**

6. **Publish the scaffolder — the maintainer's step.**

   ```sh
   cd npm && npm publish
   ```

   It must come after Central: generated projects pin the framework version and have to resolve it. The `prepack` hook rebuilds the framework and stages `lib/` and `templates/`. It is manual because npm's 2FA is a passkey, which no script can answer. **Destination: npm (`create-panamaview`).**

7. **Verify.** `repo1.maven.org` serves the pom, jar, sources, javadoc and signatures; `npm view create-panamaview version` is the new version; and a generated project runs:

   ```sh
   cd /tmp && npx --yes create-panamaview@X.Y.Z create probe --template vanilla
   cd probe && ./run.sh
   ```

   A fresh npm version can answer `404`/`ETARGET` for about three minutes; that is propagation, not a failed publish.

### What is automatic, and what is not

| Step | Who | Why |
| --- | --- | --- |
| 0–1 choose the version, set the tag | maintainer | a decision, and a published pom is permanent |
| 2–3 tag, verify | scripts + CI | gated, and reversible until the tag is pushed |
| 4 upload to Central | script | validation only |
| 5 publish | maintainer's explicit go-ahead, then a script/API call | irreversible |
| 6 npm | **maintainer only** | passkey 2FA is interactive |
| 7 verify | anyone | read-only |

Details for each destination: [Publishing to Maven Central](publishing.md#2-publishing-to-maven-central) and [Publishing to npm](publishing.md#3-publishing-to-npm).

---

## 2. Version Distribution (The 13 Version Fields)

Across the repository, version strings exist in **thirteen files**, but only **seven** move during a framework release:

| File | Field | Moves on Release? | Meaning |
| :--- | :--- | :---: | :--- |
| `pom.xml` | `<version>` | **Yes** | Root reactor version; inherited by submodules. |
| `framework/pom.xml` | *(inherited)* | — | Framework artifact version. |
| `demo-app/pom.xml` | *(inherited)* | — | Demo app module. |
| `windows-probe/pom.xml` | *(inherited)* | — | Windows verification probe module. |
| `templates/*/pom.xml` (×5) | `<panamaview.version>` | **Yes** | Dependency pin in scaffolded projects. |
| `npm/package.json` | `version` | **Yes** | NPM package version carrying the framework. |
| `templates/*/pom.xml` | `<version>` | **No** | Application version defined by the template. |
| `templates/*/frontend/package.json` (×3) | `version` | **No** | Frontend bundle package version. |

### Invariant Checks

- `templates/*/pom.xml` must pin the exact framework version being released; otherwise generated projects will fail to resolve. `verify-templates.sh` asserts this in CI.
- `npm/package.json` must match the framework version; otherwise the bundled framework and the generated `pom.xml` pin diverge. `npm/scripts/build-package.sh` validates this during packaging.
