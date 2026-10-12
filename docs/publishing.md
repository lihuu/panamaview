# Publishing panamaview

This guide explains how `panamaview` is published to private Maven repositories and to **Maven Central**.

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
