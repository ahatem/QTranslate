param(
    [Parameter(Mandatory = $true)][string] $Version,
    [Parameter(Mandatory = $true)][string] $PortableArchive,
    [Parameter(Mandatory = $true)][string] $OutputDirectory,
    [string] $PackageVersion = (($Version -split '[-+]')[0])
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'windows-installer-common.ps1')
$archive = (Resolve-Path -LiteralPath $PortableArchive).Path
$output = [System.IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $output) {
    throw "Output directory already exists: $output"
}
New-Item -ItemType Directory -Path $output | Out-Null

$portableRoot = Join-Path $output 'portable'
Expand-Archive -LiteralPath $archive -DestinationPath $portableRoot
$distribution = Join-Path $portableRoot 'QTranslate'
$appJar = Join-Path $distribution 'QTranslate.jar'
$inventory = Join-Path $distribution 'portable-plugin-ids.txt'
foreach ($required in @($appJar, $inventory)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Portable candidate is missing $required"
    }
}
foreach ($name in @('plugins', 'languages', 'themes', 'icons', 'LICENSES', 'THIRD_PARTY_LICENSES')) {
    if (-not (Test-Path -LiteralPath (Join-Path $distribution $name) -PathType Container)) {
        throw "Portable candidate is missing $name"
    }
}

$inputDir = Join-Path $output 'package-input'
New-Item -ItemType Directory -Path $inputDir | Out-Null
Copy-Item -LiteralPath $appJar -Destination (Join-Path $inputDir 'QTranslate.jar')

# Reflectively loaded providers and plugin code require modules beyond static analysis.
$modules = @(
    'java.base', 'java.desktop', 'java.instrument', 'java.logging',
    'java.management', 'java.naming', 'java.net.http', 'java.prefs',
    'java.security.jgss', 'java.xml.crypto', 'jdk.charsets', 'jdk.unsupported',
    'jdk.crypto.ec', 'jdk.localedata', 'jdk.zipfs'
) -join ','
$runtime = Join-Path $output 'runtime-image'
& jlink --add-modules $modules --strip-debug --no-header-files --no-man-pages `
    --compress=2 --output $runtime
if ($LASTEXITCODE -ne 0) { throw "jlink failed with exit code $LASTEXITCODE" }

$imageParent = Join-Path $output 'app-image'
& jpackage --type app-image --name QTranslate --input $inputDir `
    --main-jar QTranslate.jar --main-class com.github.ahatem.qtranslate.app.MainKt `
    --app-version $PackageVersion --icon 'ui-swing/src/main/resources/icons/app/icon.ico' `
    --runtime-image $runtime --dest $imageParent --java-options '-Dfile.encoding=UTF-8'
if ($LASTEXITCODE -ne 0) { throw "jpackage failed with exit code $LASTEXITCODE" }

$image = Join-Path $imageParent 'QTranslate'
foreach ($name in @('plugins', 'languages', 'themes', 'icons', 'LICENSES', 'THIRD_PARTY_LICENSES')) {
    Copy-Item -LiteralPath (Join-Path $distribution $name) -Destination (Join-Path $image $name) -Recurse
}
foreach ($name in @('LICENSE', 'NOTICE.md', 'portable-plugin-ids.txt')) {
    Copy-Item -LiteralPath (Join-Path $distribution $name) -Destination (Join-Path $image $name)
}

# The portable archive carries portable.flag, because that archive is a portable distribution. This
# image must not: it is the same payload an installer will lay down under Program Files or
# %LOCALAPPDATA%\Programs, where data belongs in the OS per-user location and writing into the
# installation directory is exactly what must not happen. So the marker is asserted absent here, the
# image is verified as-is, and only then is a portable copy made and marked for the ZIP.
$markerName = 'portable.flag'
if (Test-Path -LiteralPath (Join-Path $image $markerName)) {
    throw "The Windows app image must not contain $markerName; it is the installer payload"
}
Write-Host "Canonical app image carries no $markerName (installed semantics)"

# The MSI is built from the marker-free canonical image above, never from the portable staging
# copy below: an installed QTranslate must keep user data in the OS per-user location, which the
# portable marker would disable.
$PackageVersion = ConvertTo-MsiProductVersion $PackageVersion
$msiStaging = Join-Path $output 'msi-staging'
New-Item -ItemType Directory -Path $msiStaging | Out-Null
# jpackage hands the license file to the WiX license dialog, which only renders RTF.
$licenseRtf = Join-Path $msiStaging 'LICENSE.rtf'
$licenseText = (Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot '..\..\LICENSE')) `
    -replace '\\', '\\' -replace '\{', '\{' -replace '\}', '\}' -replace "`r?`n", '\par '
Set-Content -LiteralPath $licenseRtf -Value "{\rtf1\ansi\deff0 $licenseText}" -NoNewline -Encoding ascii
& jpackage --type msi --app-image $image --name QTranslate --app-version $PackageVersion `
    --dest $msiStaging --vendor 'QTranslate contributors' `
    --description 'Select text in any application, press Ctrl+Q, and understand it without leaving what you are doing.' `
    --about-url 'https://github.com/ahatem/QTranslate' `
    --win-help-url 'https://github.com/ahatem/QTranslate/issues' `
    --license-file $licenseRtf `
    --win-menu --win-menu-group QTranslate --win-per-user-install `
    --win-upgrade-uuid (Get-WindowsUpgradeUuid)
if ($LASTEXITCODE -ne 0) { throw "jpackage MSI build failed with exit code $LASTEXITCODE" }
$builtMsi = @(Get-ChildItem -LiteralPath $msiStaging -Filter 'QTranslate-*.msi')
if ($builtMsi.Count -ne 1) { throw "Expected exactly one MSI in $msiStaging, found $($builtMsi.Count)" }
$msi = Join-Path $output "QTranslate-$Version-windows-x64.msi"
Move-Item -LiteralPath $builtMsi[0].FullName -Destination $msi
Write-Host "Windows installer: $msi (MSI ProductVersion $PackageVersion, per-user)"

$portableRoot = Join-Path $output 'portable-staging'
New-Item -ItemType Directory -Path $portableRoot | Out-Null
# Named QTranslate so the archive extracts into the same folder name as every other release asset.
$portableImage = Join-Path $portableRoot 'QTranslate'
Copy-Item -LiteralPath $image -Destination $portableImage -Recurse
New-Item -ItemType File -Path (Join-Path $portableImage $markerName) | Out-Null

$zip = Join-Path $output "QTranslate-$Version-windows-x64.zip"
Compress-Archive -LiteralPath $portableImage -DestinationPath $zip
Write-Host "Windows app image: $image (installed semantics, marker-free)"
Write-Host "Windows archive: $zip (portable copy, $markerName present)"
