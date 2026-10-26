# Package the app as a Windows installer: a portable zip and a per-user MSI.
#
# The native executable is only half of a distributable application. WebView2Loader.dll has to
# travel with it, and a user expects an entry they can install and uninstall rather than a folder
# to drag somewhere. This script produces both:
#
#   target\dist\<name>-<version>-win-x64.zip   portable: unzip anywhere, run the exe
#   target\dist\<name>-<version>-win-x64.msi   per-user installer, no administrator prompt
#
# Identity defaults come from the pom, exactly as on macOS: artifactId is the executable's name,
# the version is the package version, and the bundle identifier is groupId.artifactId. The MSI's
# UpgradeCode is derived from that bundle identifier, so a new version upgrades the installed
# product instead of appearing beside it -- changing the bundle id means a different product.
#
# The MSI is built with WiX v4 or later (the `wix` command). Install it with:
#   dotnet tool install --global wix
# Use -NoMsi to produce only the portable zip where WiX is not available.
#
# Signing is not done here and is not pretended: an unsigned installer warns SmartScreen, and
# signing needs a certificate this repository does not hold. See docs/packaging.md.
#
# Usage: .\package.ps1 [-Name NAME] [-Title TITLE] [-BundleId ID] [-Version V] [-Manufacturer M]
#                      [-Exe PATH] [-Loader PATH] [-Icon FILE.ico] [-Output DIR] [-NoMsi] [-NoBuild]
[CmdletBinding()]
param(
    [string]$Name = "",
    [string]$Title = "",
    [string]$BundleId = "",
    [string]$Version = "",
    [string]$Manufacturer = "",
    [string]$Exe = "",
    [string]$Loader = "loader\WebView2Loader.dll",
    [string]$Icon = "",
    [string]$Output = "target\dist",
    [switch]$NoMsi,
    [switch]$NoBuild
)

$ErrorActionPreference = "Continue"

function Fail([string]$message) {
    Write-Error "package.ps1: $message" -ErrorAction Continue
    exit 1
}

function XmlEscape([string]$text) {
    return $text.Replace("&", "&amp;").Replace("<", "&lt;").Replace(">", "&gt;").Replace('"', "&quot;")
}

function PomValue([string]$element) {
    if (-not (Test-Path pom.xml)) { return "" }
    $text = Get-Content pom.xml -Raw
    $match = [regex]::Match($text, "<" + [regex]::Escape($element) + ">([^<]*)</" + [regex]::Escape($element) + ">")
    if ($match.Success) { return $match.Groups[1].Value }
    return ""
}

# A deterministic GUID from the bundle identifier: the same app always upgrades the same product,
# and two apps never collide. Version/variant bits are set so it is a well-formed RFC 4122 UUID.
function Get-UpgradeCode([string]$identifier) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes("panamaview-upgrade:" + $identifier))
    } finally {
        $sha.Dispose()
    }
    $bytes[6] = ($bytes[6] -band 0x0F) -bor 0x50
    $bytes[8] = ($bytes[8] -band 0x3F) -bor 0x80
    return ([guid]::new([byte[]]$bytes[0..15])).ToString().ToUpperInvariant()
}

$group = PomValue "groupId"
$artifact = PomValue "artifactId"
$pomVersion = PomValue "version"

if (-not $Name) { $Name = $artifact }
if (-not $Name) { Fail "no <artifactId> in pom.xml; pass -Name" }
if (-not $Title) { $Title = $Name }
if (-not $Version) { $Version = $pomVersion }
if (-not $BundleId) {
    if (-not $group) { Fail "no <groupId> in pom.xml; pass -BundleId" }
    $BundleId = "$group.$artifact"
}
if (-not $Manufacturer) { $Manufacturer = $group }
if (-not $Manufacturer) { $Manufacturer = $Title }
if (-not $Exe) { $Exe = "target\$artifact.exe" }

if ($Version -notmatch '^\d+\.\d+\.\d+$') {
    Fail "version '$Version' is not major.minor.build; MSI requires three numeric parts (pass -Version, e.g. -Version 1.2.0)"
}
if ($BundleId -notmatch '^[A-Za-z0-9.-]+\.[A-Za-z0-9.-]+$') {
    Fail "bundle id '$BundleId' is not reverse-DNS; pass -BundleId, e.g. -BundleId com.example.myapp"
}
foreach ($value in @($Name, $Title, $Manufacturer)) {
    if ($value -match '[\\/:*?"<>|]') {
        Fail "'$value' contains a character Windows does not allow in a file or folder name"
    }
}

if (-not (Test-Path $Exe)) {
    if ($NoBuild) { Fail "no executable at $Exe; build it first" }
    if (-not (Test-Path build-native.ps1)) { Fail "no executable at $Exe and no build-native.ps1 beside this script" }
    Write-Output "==> building the native binary"
    & .\build-native.ps1
    if ($LASTEXITCODE -ne 0) { Fail "build-native.ps1 failed ($LASTEXITCODE)" }
}
if (-not (Test-Path $Exe)) { Fail "no executable at $Exe after the build" }

if (-not (Test-Path $Loader)) {
    if (Test-Path fetch-loader.ps1) {
        Write-Output "==> fetching WebView2Loader.dll"
        & .\fetch-loader.ps1
        if ($LASTEXITCODE -ne 0) { Fail "fetch-loader.ps1 failed ($LASTEXITCODE)" }
    }
}
if (-not (Test-Path $Loader)) {
    Fail "no WebView2Loader.dll at $Loader -- run fetch-loader.ps1 or pass -Loader"
}

if ($Icon) {
    if ($Icon -notmatch '\.ico$') { Fail "the icon must be a .ico file; got '$Icon'" }
    if (-not (Test-Path $Icon)) { Fail "no icon at $Icon" }
}

$stage = Join-Path $Output "$Name-win-x64"
$zip = Join-Path $Output "$Name-$Version-win-x64.zip"
$msi = Join-Path $Output "$Name-$Version-win-x64.msi"

if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force -Path $stage | Out-Null
Copy-Item $Exe (Join-Path $stage "$Name.exe") -Force
Copy-Item $Loader (Join-Path $stage "WebView2Loader.dll") -Force

Write-Output "==> writing $zip"
if (Test-Path $zip) { Remove-Item -Force $zip }
Compress-Archive -Path $stage -DestinationPath $zip -Force

if (-not $NoMsi) {
    $wix = Get-Command wix -ErrorAction SilentlyContinue
    if (-not $wix) {
        $fallback = Join-Path $env:USERPROFILE ".dotnet\tools\wix.exe"
        if (Test-Path $fallback) { $wix = $fallback }
    }
    if (-not $wix) {
        Fail @"
no 'wix' command on PATH. Install WiX v4 or later, then run this again:
    dotnet tool install --global wix
Or pass -NoMsi to produce only the portable zip.
"@
    }

    $exePath = XmlEscape (Resolve-Path $Exe).Path
    $loaderPath = XmlEscape (Resolve-Path $Loader).Path
    # The WiX source and its debug symbols are build intermediates; only the MSI belongs in the
    # output directory beside the zip.
    $wixDir = Join-Path $Output "wix"
    New-Item -ItemType Directory -Force -Path $wixDir | Out-Null
    $wxs = Join-Path $wixDir "installer.wxs"
    $builtMsi = Join-Path $wixDir (Split-Path $msi -Leaf)

    $iconXml = ""
    $shortcutIcon = ""
    if ($Icon) {
        $iconXml = '    <Icon Id="AppIcon" SourceFile="' + (XmlEscape (Resolve-Path $Icon).Path) + '" />' + "`n" +
                   '    <Property Id="ARPPRODUCTICON" Value="AppIcon" />' + "`n"
        $shortcutIcon = ' Icon="AppIcon"'
    }

    $source = @"
<?xml version="1.0" encoding="UTF-8"?>
<Wix xmlns="http://wixtoolset.org/schemas/v4/wxs">
  <Package Name="$(XmlEscape $Title)" Manufacturer="$(XmlEscape $Manufacturer)" Version="$Version"
           UpgradeCode="$(Get-UpgradeCode $BundleId)" Language="1033" Scope="perUser">
    <MajorUpgrade DowngradeErrorMessage="A newer version of $Title is already installed." />
    <MediaTemplate EmbedCab="yes" />
$iconXml    <StandardDirectory Id="LocalAppDataFolder">
      <Directory Id="INSTALLFOLDER" Name="$(XmlEscape $Name)" />
    </StandardDirectory>
    <Feature Id="Main">
      <ComponentGroupRef Id="AppFiles" />
    </Feature>
  </Package>
  <Fragment>
    <ComponentGroup Id="AppFiles" Directory="INSTALLFOLDER">
      <Component Id="MainExecutable" Guid="*">
        <File Id="AppExe" Source="$exePath" KeyPath="yes">
          <Shortcut Id="StartMenuShortcut" Directory="ProgramMenuFolder" Name="$(XmlEscape $Title)"
                    Description="$(XmlEscape $Title)" WorkingDirectory="INSTALLFOLDER" Advertise="yes"$shortcutIcon />
        </File>
      </Component>
      <Component Id="Loader" Guid="*">
        <File Id="WebView2Loader" Source="$loaderPath" KeyPath="yes" />
      </Component>
    </ComponentGroup>
  </Fragment>
</Wix>
"@
    Set-Content -Path $wxs -Value $source -Encoding UTF8

    Write-Output "==> writing $msi"
    if (Test-Path $msi) { Remove-Item -Force $msi }
    if (Test-Path $builtMsi) { Remove-Item -Force $builtMsi }
    & $wix build $wxs -arch x64 -o $builtMsi
    if ($LASTEXITCODE -ne 0) { Fail "wix build failed ($LASTEXITCODE)" }
    Move-Item -Force $builtMsi $msi
}

Write-Output "[package] $zip"
if (-not $NoMsi) { Write-Output "[package] $msi" }
if (-not $NoMsi) {
    Write-Output "[package] per-user install under %LOCALAPPDATA%\$Name; unsigned unless you sign it -- see docs/packaging.md."
}
