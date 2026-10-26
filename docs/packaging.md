# Packaging an app

`build-native.sh` leaves a bare executable. That is enough to run the app, and not enough to
hand it to someone: macOS gives a process without a bundle no name the system will show, the
notification centre refuses to start at all, and on Windows the WebView2 loader has to travel
beside the executable and there is nothing to install.

`package.sh` (macOS) and `package.ps1` (Windows) turn a built app into something installable.
They are in every template, so a generated project carries its own copy; this document describes
both.

## Identity comes from the pom

Neither script invents an application name. The defaults are the facts the build already has:

| What | Default | Override |
|---|---|---|
| Executable name | `artifactId` | `--name` (macOS), `-Name` (Windows) |
| Display name | the executable name | `--title`, `-Title` |
| Bundle / product identifier | `groupId.artifactId` | `--bundle-id`, `-BundleId` |
| Version | `version` | `--version`, `-Version` |
| Vendor (Windows ARP) | `groupId` | `-Manufacturer` |

The window title is deliberately not read. It lives in Java, and a packaging script that parsed
source to find it would be guessing; pass `--title` when the display name differs from the
artifact.

Changing the bundle identifier is not cosmetic on Windows: the MSI's `UpgradeCode` is derived
from it, so a changed identifier is a different product rather than an upgrade.

## macOS: `.app` and `.dmg`

```sh
./package.sh
```

Builds the native binary if it is missing, assembles `target/dist/<title>.app`, ad-hoc signs it,
and writes `target/dist/<artifactId>-<version>.dmg` with an `/Applications` link.

| Flag | Meaning |
|---|---|
| `--name NAME` | executable inside the bundle (default: `artifactId`) |
| `--title TITLE` | display name and dmg volume name (default: `--name`) |
| `--bundle-id ID` | reverse-DNS identifier (default: `groupId.artifactId`) |
| `--version V` | dotted numeric version (default: pom version) |
| `--icon FILE.icns` | bundle icon |
| `--sign IDENTITY` | `codesign` identity (default: `-`, ad-hoc) |
| `--no-build` | fail rather than build a missing binary |
| `--output DIR` | where to write artifacts (default: `target/dist`) |

The ad-hoc signature is enough to run the app on the machine that built it. Distributing it to
other machines needs a Developer ID signature and notarization, which this repository has no
credentials for:

```sh
codesign --force --options runtime --sign "Developer ID Application: Your Name (TEAMID)" \
    "target/dist/My App.app"
xcrun notarytool submit "target/dist/My App.dmg" --keychain-profile your-profile --wait
xcrun stapler staple "target/dist/My App.dmg"
```

The hardened runtime (`--options runtime`) is what notarization expects. Neither step is
automated here, because a script that cannot get a certificate should not pretend to sign.

## Windows: portable zip and per-user MSI

```powershell
.\build-native.ps1     # -> target\myapp.exe
.\package.ps1          # -> target\dist\myapp-0.1.0-win-x64.zip
                       #    target\dist\myapp-0.1.0-win-x64.msi
```

`build-native.ps1` is the Windows counterpart of `build-native.sh`: compile, resolve the
dependency classpath, run `native-image`. There is no `-XstartOnFirstThread`, because Windows
does not need it.

`package.ps1` stages the executable with `WebView2Loader.dll` beside it, writes a portable zip,
and — when WiX is available — a per-user MSI:

- installed under `%LOCALAPPDATA%\<Name>`, so there is no administrator prompt and no UAC;
- a Start-menu shortcut;
- uninstall through Apps & features;
- `MajorUpgrade` on the derived `UpgradeCode`, so a newer version replaces the older one.

| Flag | Meaning |
|---|---|
| `-Name NAME` | executable and install-folder name (default: `artifactId`) |
| `-Title TITLE` | display name (default: `-Name`) |
| `-BundleId ID` | product identity, and the source of the `UpgradeCode` |
| `-Version V` | `major.minor.build` (default: pom version) |
| `-Manufacturer M` | vendor shown in Apps & features (default: `groupId`) |
| `-Exe PATH` | executable to package (default: `target\<artifactId>.exe`) |
| `-Loader PATH` | loader DLL (default: `loader\WebView2Loader.dll`, fetched if missing) |
| `-Icon FILE.ico` | shortcut and Apps & features icon |
| `-Output DIR` | where to write artifacts (default: `target\dist`) |
| `-NoMsi` | portable zip only, no WiX needed |
| `-NoBuild` | fail rather than build a missing executable |

WiX v4 or v5 is required for the MSI (v6 and later require accepting the Open Source
Maintenance Fee EULA inside the build):

```powershell
dotnet tool install --global wix --version 5.0.2
```

or, on a machine without the .NET SDK, install the standalone command-line tools (the WiX v5
`wix-cli-x64.msi` from its GitHub release). Without WiX, `-NoMsi` still produces a zip.

The MSI is not signed. SmartScreen will warn until it is; signing needs a certificate this
repository does not hold, and `signtool` is the step:

```powershell
signtool sign /fd SHA256 /tr http://timestamp.digicert.com /td SHA256 /a "target\dist\myapp-0.1.0-win-x64.msi"
```

## Where the loader is found

A packaged Windows app ships `WebView2Loader.dll` beside its executable. `WindowsHost` looks
beside the executable first and under the working directory second, so an installed app started
by a shortcut works regardless of the directory Windows happens to give it. Development runs
keep using the `loader/` directory that `fetch-loader.ps1` stages.

macOS needs no such file: WebKit is part of the system.

## What is not covered yet

- **Icons by default.** There is no icon in the templates; pass one, or accept the system default.
- **Code signing and notarization.** Documented above as manual steps.
- **Windows packaging for the frontend templates.** `react`, `vue` and `angular` build their
  frontend through POSIX scripts; `vanilla-tailwind` fetches a POSIX binary. Only `vanilla` has a
  Windows native build and packager today. The others run on the JVM on Windows, as before.
- **Attaching installers to a release.** The release is still source-only; see
  [releasing.md](releasing.md).

## Verifying

On macOS, `scripts/verify-packaging.sh [template]` stages the npm package, generates a project,
runs its own `./package.sh`, and checks the bundle identity, the signature, the dmg mount, and
that the bundled binary runs from another working directory. CI runs it for `vanilla` on every
change, and the Windows job packages the native self-test, installs the MSI silently, runs the
installed executable from `C:\`, and uninstalls.
