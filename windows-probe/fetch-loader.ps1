# Fetches WebView2Loader.dll — the one file the Windows side carries that macOS does not.
#
# Microsoft distributes the loader inside the Microsoft.Web.WebView2 NuGet package. This
# script downloads that package, checks the extracted DLL's sha256 against the pin below, and
# copies just the x64 DLL into loader/. The same pattern as the Tailwind standalone binary in
# the vanilla-tailwind template: fetched once, checksum-verified, never committed.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File fetch-loader.ps1

$ErrorActionPreference = "Stop"

$sdkVersion = "1.0.4258.31"
$dllSha256  = "3426dcc55fdfb8b5e7ac623cf33b4ccf283fbf68a0c5a65bddfdb4d749a8abee"
$url        = "https://www.nuget.org/api/v2/package/Microsoft.Web.WebView2/$sdkVersion"

$package = Join-Path $env:TEMP "Microsoft.Web.WebView2.$sdkVersion.zip"
$extract = Join-Path $env:TEMP "Microsoft.Web.WebView2.$sdkVersion"

Write-Host "downloading $url"
Invoke-WebRequest -Uri $url -OutFile $package

if (Test-Path $extract) { Remove-Item -Recurse -Force $extract }
# The package is a zip archive in .nupkg clothing, and Expand-Archive in Windows PowerShell
# 5.1 checks the extension before it checks the content — hence the .zip name above.
Expand-Archive -Path $package -DestinationPath $extract

$dll  = Join-Path $extract "build\native\x64\WebView2Loader.dll"
$hash = (Get-FileHash -Algorithm SHA256 $dll).Hash.ToLower()
if ($hash -ne $dllSha256) {
    throw "checksum mismatch for WebView2Loader.dll: expected $dllSha256, got $hash - refusing to use it"
}

New-Item -ItemType Directory -Force "$PSScriptRoot\loader" | Out-Null
Copy-Item $dll "$PSScriptRoot\loader\WebView2Loader.dll" -Force
Write-Host "loader written to $PSScriptRoot\loader\WebView2Loader.dll (sha256 verified)"
