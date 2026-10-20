# Release Workflow & Version Management

This document explains the release process and the strict version invariants maintained across the `panamaview` repository.

---

## 1. Triggering a Release

Releases are executed via `scripts/release.sh`:

```sh
./scripts/release.sh patch          # Or minor, major, or an explicit x.y.z
./scripts/release.sh --dry-run patch
```

The script enforces verification before making any modifications:
1. Runs `scripts/verify-templates.sh` and `scripts/verify-scaffold.sh`.
2. Verifies that every template can be generated and built to a native executable.
3. Bumps versions across all relevant configuration files.
4. Commits, tags (`vX.Y.Z`), and pushes to origin.
5. GitHub Actions (`.github/workflows/release.yml`) triggers on the tag to verify agreement across files.

A release is not finished at the tag. Publishing the scaffolder is a separate, manual act — `cd npm && npm publish` — because no credential for the registry lives in this repository. See [Publishing to npm](publishing.md#3-publishing-to-npm) for the two commands, the `prepack` hook they trigger, and the `--ignore-scripts` trap that ships a package which cannot scaffold.

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
