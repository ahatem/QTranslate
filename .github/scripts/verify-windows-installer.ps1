# Exercises the real Windows Installer lifecycle of the MSI candidate:
# metadata, silent install, installed-mode probe, in-place upgrade against a
# synthetic higher version built from the same app-image, and uninstall - with
# QTranslate user data kept in an isolated APPDATA and verified byte-for-byte
# across both upgrade and uninstall.

param(
    [Parameter(Mandatory = $true)][string] $Version,
    [Parameter(Mandatory = $true)][string] $PackageDirectory,
    # Measured installer is ~95 MiB; the budget only exists to catch large
    # accidental regressions, same philosophy as the Gradle release-size checks.
    [long] $MaxInstallerBytes = 130MB,
    [switch] $SkipUpgradeSmoke
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'windows-installer-common.ps1')
$package = (Resolve-Path -LiteralPath $PackageDirectory).Path
$logDirectory = Join-Path $package 'installer-logs'
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null

# Per-user products register under HKCU on older Windows; Windows 11 24H2 / Server 2025
# moved per-user ARP entries to HKLM, so both hives are always scanned.
function Get-QTranslateRegistration {
    @('HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*',
        'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*',
        'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*') |
        Get-ItemProperty -ErrorAction SilentlyContinue |
        Where-Object { $_.DisplayName -eq 'QTranslate' }
}

function Wait-QTranslateRegistration([int] $Attempts = 20) {
    for ($i = 0; $i -lt $Attempts; $i++) {
        $entries = @(Get-QTranslateRegistration)
        if ($entries.Count -gt 0) { return $entries }
        Start-Sleep -Seconds 1
    }
    @()
}

function Resolve-InstallDirectory($Entry) {
    if ($Entry.InstallLocation -and (Test-Path -LiteralPath $Entry.InstallLocation -PathType Container)) {
        return (Resolve-Path -LiteralPath $Entry.InstallLocation).Path
    }
    if ($Entry.DisplayIcon) {
        $icon = ($Entry.DisplayIcon -replace '^"|"$', '') -replace ',\d+$', ''
        if (Test-Path -LiteralPath $icon -PathType Leaf) { return (Split-Path -Parent $icon) }
    }
    foreach ($candidate in @("$env:LOCALAPPDATA\Programs\QTranslate", "$env:LOCALAPPDATA\QTranslate", "$env:ProgramFiles\QTranslate")) {
        if (Test-Path -LiteralPath $candidate -PathType Container) { return $candidate }
    }
    throw 'Could not resolve the QTranslate installation directory from the uninstall registration'
}

function Invoke-Msiexec([string] $Operation, [string] $Target, [string] $LogFile, [string] $What) {
    $arguments = "$Operation `"$Target`" /qn /norestart /l*v `"$LogFile`""
    $process = Start-Process msiexec.exe -ArgumentList $arguments -Wait -PassThru
    if ($process.ExitCode -notin @(0, 3010)) {
        $tail = if (Test-Path -LiteralPath $LogFile) { (Get-Content -LiteralPath $LogFile -Tail 40) -join "`n" } else { '(no log)' }
        throw "$What failed with msiexec exit code $($process.ExitCode). Log: $LogFile`n$tail"
    }
    $process.ExitCode
}

function Get-TreeFingerprint([string] $Root) {
    $map = @{}
    if (Test-Path -LiteralPath $Root) {
        Get-ChildItem -LiteralPath $Root -Recurse -File | ForEach-Object {
            $map[$_.FullName.Substring($Root.Length)] = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
        }
    }
    $map
}

function Assert-TreeEqual([hashtable] $Before, [hashtable] $After, [string] $What) {
    $removed = @($Before.Keys | Where-Object { -not $After.ContainsKey($_) })
    $added = @($After.Keys | Where-Object { -not $Before.ContainsKey($_) })
    $changed = @($Before.Keys | Where-Object { $After.ContainsKey($_) -and $After[$_] -ne $Before[$_] })
    if ($removed.Count -or $added.Count -or $changed.Count) {
        throw "$What changed:`nremoved: $($removed -join ', ')`nadded: $($added -join ', ')`nchanged: $($changed -join ', ')"
    }
}

# The release probe deliberately isolates its own writes into the distribution
# it examines, so after each probe run the installation directory holds files
# the MSI never laid down. Those are probe artifacts, not installer payload;
# remove them so uninstall is tested against exactly the MSI-owned tree.
function Clear-NonPayloadFiles([string] $Root, [string[]] $Payload) {
    Get-ChildItem -LiteralPath $Root -Recurse -File |
        Where-Object { $_.FullName.Substring($Root.Length) -notin $Payload } |
        Remove-Item -Force
    Get-ChildItem -LiteralPath $Root -Recurse -Directory |
        Sort-Object { $_.FullName.Length } -Descending |
        Where-Object { -not (Get-ChildItem -LiteralPath $_.FullName -Force) } |
        Remove-Item -Force
}

function Test-StartMenuShortcut {
    @( (Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\QTranslate\QTranslate.lnk'),
        (Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\QTranslate.lnk'),
        (Join-Path $env:ProgramData 'Microsoft\Windows\Start Menu\Programs\QTranslate\QTranslate.lnk'),
        (Join-Path $env:ProgramData 'Microsoft\Windows\Start Menu\Programs\QTranslate.lnk') ) |
        Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
        Select-Object -First 1
}

function Assert-InstalledPayload([string] $InstallRoot) {
    foreach ($file in @('QTranslate.exe', 'runtime\bin\java.exe', 'app\QTranslate.jar', 'portable-plugin-ids.txt')) {
        if (-not (Test-Path -LiteralPath (Join-Path $InstallRoot $file) -PathType Leaf)) {
            throw "Installed payload is missing $file"
        }
    }
    foreach ($directory in @('plugins', 'languages', 'themes', 'icons')) {
        if (-not (Test-Path -LiteralPath (Join-Path $InstallRoot $directory) -PathType Container)) {
            throw "Installed payload is missing $directory"
        }
    }
    if (@(Get-ChildItem -LiteralPath (Join-Path $InstallRoot 'plugins') -Filter '*.jar').Count -eq 0) {
        throw 'Installed payload has no bundled plugin JARs'
    }
    if (Test-Path -LiteralPath (Join-Path $InstallRoot 'portable.flag')) {
        throw 'Installed payload must not carry portable.flag'
    }
}

$msi = Join-Path $package "QTranslate-$Version-windows-x64.msi"
if (-not (Test-Path -LiteralPath $msi -PathType Leaf)) { throw "Windows installer candidate is missing: $msi" }
$msiSize = (Get-Item -LiteralPath $msi).Length
if ($msiSize -gt $MaxInstallerBytes) {
    throw ("Installer size budget exceeded: {0:N1} MiB > {1:N1} MiB" -f ($msiSize / 1MB), ($MaxInstallerBytes / 1MB))
}
Write-Host ("Installer: {0} ({1:N1} MiB)" -f $msi, ($msiSize / 1MB))

$metadata = Get-MsiProperty $msi @('ProductVersion', 'ProductCode', 'UpgradeCode')
$expectedVersion = ConvertTo-MsiProductVersion $Version
$upgradeUuid = (Get-WindowsUpgradeUuid).Trim('{}')
$actualUpgradeCode = ([string] $metadata['UpgradeCode']).Trim('{}')
if ($metadata['ProductVersion'] -ne $expectedVersion) {
    throw "MSI ProductVersion $($metadata['ProductVersion']) does not match $expectedVersion"
}
if ($actualUpgradeCode -ne $upgradeUuid) {
    throw "MSI UpgradeCode $actualUpgradeCode does not match the committed stable upgrade UUID $upgradeUuid"
}
Write-Host "MSI ProductVersion $expectedVersion, UpgradeCode $actualUpgradeCode (stable UUID)"

if (@(Get-QTranslateRegistration).Count -gt 0) {
    throw 'QTranslate is already installed on this machine; uninstall it before running the installer smoke'
}

$isolatedAppData = Join-Path ([System.IO.Path]::GetTempPath()) "qtranslate-msi-smoke-$([guid]::NewGuid().ToString('N'))"
$userDataRoot = Join-Path $isolatedAppData 'QTranslate'
New-Item -ItemType Directory -Path $isolatedAppData | Out-Null
$summary = [ordered]@{ MsiSizeBytes = $msiSize; ProductVersion = $expectedVersion; UpgradeCode = $actualUpgradeCode }

try {
    $summary['InstallExitCode'] = Invoke-Msiexec '/i' $msi (Join-Path $logDirectory 'install-A.log') 'Install'
    $registration = @(Wait-QTranslateRegistration)
    if ($registration.Count -ne 1) { throw "Expected exactly one QTranslate uninstall registration, found $($registration.Count)" }
    $entry = $registration[0]
    if ($entry.DisplayVersion -ne $expectedVersion) { throw "ARP DisplayVersion $($entry.DisplayVersion) != $expectedVersion" }
    if (-not $entry.UninstallString) { throw 'ARP entry has no UninstallString' }
    $installRoot = Resolve-InstallDirectory $entry
    $summary['InstallDirectory'] = $installRoot
    Write-Host "Installed per-user at $installRoot (ARP DisplayVersion $($entry.DisplayVersion), Publisher $($entry.Publisher))"
    Assert-InstalledPayload $installRoot
    $shortcut = Test-StartMenuShortcut
    if (-not $shortcut) { throw 'Start Menu shortcut for QTranslate is missing' }
    Write-Host "Start Menu shortcut: $shortcut"
    $payload = @(Get-ChildItem -LiteralPath $installRoot -Recurse -File | ForEach-Object { $_.FullName.Substring($installRoot.Length) })

    Invoke-BoundedProbe (Join-Path $installRoot 'QTranslate.exe') @('--release-probe', $installRoot) @{ APPDATA = $isolatedAppData }
    if (-not (Test-Path -LiteralPath $userDataRoot -PathType Container)) {
        throw "Installed probe did not resolve the OS per-user data root $userDataRoot"
    }
    if ((Resolve-Path -LiteralPath $userDataRoot).Path -eq (Resolve-Path -LiteralPath $installRoot).Path) {
        throw 'User data root must differ from the installation root'
    }
    Write-Host "Installed-mode probe passed; user data root resolves to $userDataRoot"
    Clear-NonPayloadFiles $installRoot $payload

    $canary = Join-Path $userDataRoot 'userdata-canary-d2.txt'
    Set-Content -LiteralPath $canary -Value "D2 installer smoke $([guid]::NewGuid())" -NoNewline
    $userDataBefore = Get-TreeFingerprint $userDataRoot

    if (-not $SkipUpgradeSmoke) {
        $appImage = Join-Path $package 'app-image\QTranslate'
        if (-not (Test-Path -LiteralPath $appImage -PathType Container)) {
            throw "Upgrade smoke needs the canonical app image at $appImage"
        }
        $versionParts = $expectedVersion -split '\.'
        $candidateVersion = '{0}.{1}.{2}' -f $versionParts[0], $versionParts[1], ([int] $versionParts[2] + 1)
        # Synthetic higher version from the same canonical image: proves the stable UpgradeCode
        # yields an in-place upgrade, not a side-by-side install. No source or MSI rewriting.
        $upgradeStaging = Join-Path $package 'msi-upgrade-staging'
        New-Item -ItemType Directory -Path $upgradeStaging | Out-Null
        & jpackage --type msi --app-image $appImage --name QTranslate --app-version $candidateVersion `
            --dest $upgradeStaging --vendor 'QTranslate contributors' `
            --description 'Select text in any application, press Ctrl+Q, and understand it without leaving what you are doing.' `
            --about-url 'https://github.com/ahatem/QTranslate' `
            --win-help-url 'https://github.com/ahatem/QTranslate/issues' `
            --win-menu --win-menu-group QTranslate --win-per-user-install `
            --win-upgrade-uuid $upgradeUuid
        if ($LASTEXITCODE -ne 0) { throw "jpackage upgrade-candidate build failed with exit code $LASTEXITCODE" }
        $candidateMsi = @(Get-ChildItem -LiteralPath $upgradeStaging -Filter 'QTranslate-*.msi')[0].FullName
        $candidateMetadata = Get-MsiProperty $candidateMsi @('ProductVersion', 'ProductCode', 'UpgradeCode')
        if ($candidateMetadata['ProductVersion'] -ne $candidateVersion) {
            throw "Upgrade candidate ProductVersion $($candidateMetadata['ProductVersion']) != $candidateVersion"
        }
        if (([string] $candidateMetadata['UpgradeCode']).Trim('{}') -ne $upgradeUuid) {
            throw 'Upgrade candidate UpgradeCode differs from the stable upgrade UUID'
        }

        $summary['UpgradeFrom'] = $expectedVersion
        $summary['UpgradeTo'] = $candidateVersion
        $summary['UpgradeExitCode'] = Invoke-Msiexec '/i' $candidateMsi (Join-Path $logDirectory 'install-B.log') 'Upgrade install'
        $afterUpgrade = @(Wait-QTranslateRegistration)
        if ($afterUpgrade.Count -ne 1) {
            throw "Upgrade must leave exactly one QTranslate registered, found $($afterUpgrade.Count) (side-by-side install)"
        }
        if ($afterUpgrade[0].DisplayVersion -ne $candidateVersion) {
            throw "ARP DisplayVersion after upgrade is $($afterUpgrade[0].DisplayVersion), expected $candidateVersion"
        }
        if ($afterUpgrade[0].PSChildName.Trim('{}') -ne ([string] $candidateMetadata['ProductCode']).Trim('{}')) {
            throw 'Registered product after upgrade is not the upgrade candidate'
        }
        $installRoot = Resolve-InstallDirectory $afterUpgrade[0]
        Assert-InstalledPayload $installRoot
        Invoke-BoundedProbe (Join-Path $installRoot 'QTranslate.exe') @('--release-probe', $installRoot) @{ APPDATA = $isolatedAppData }
        Clear-NonPayloadFiles $installRoot $payload
        Assert-TreeEqual $userDataBefore (Get-TreeFingerprint $userDataRoot) 'User data across upgrade'
        Write-Host "In-place upgrade $expectedVersion -> ${candidateVersion}: one product registered, user data intact"
    }

    $finalEntry = @(Get-QTranslateRegistration)[0]
    $summary['UninstallExitCode'] = Invoke-Msiexec '/x' $finalEntry.PSChildName (Join-Path $logDirectory 'uninstall.log') 'Uninstall'
    Start-Sleep -Seconds 2
    foreach ($file in @('QTranslate.exe', 'app\QTranslate.jar', 'runtime\bin\java.exe')) {
        if (Test-Path -LiteralPath (Join-Path $installRoot $file)) { throw "Uninstall left $file behind" }
    }
    foreach ($directory in @('plugins', 'app', 'runtime')) {
        if (Test-Path -LiteralPath (Join-Path $installRoot $directory)) { throw "Uninstall left $directory behind" }
    }
    if ((Test-Path -LiteralPath $installRoot) -and @(Get-ChildItem -LiteralPath $installRoot -Recurse -Force).Count -gt 0) {
        throw "Uninstall left files under $installRoot"
    }
    if (@(Get-QTranslateRegistration).Count -ne 0) { throw 'Uninstall left the ARP registration behind' }
    if (Test-StartMenuShortcut) { throw 'Uninstall left the Start Menu shortcut behind' }
    Assert-TreeEqual $userDataBefore (Get-TreeFingerprint $userDataRoot) 'User data across uninstall'
    if ((Get-Content -Raw -LiteralPath $canary).Length -eq 0) { throw 'User data canary lost its content' }
    Write-Host 'Uninstall removed the payload, registration, and shortcut; user data survived byte-for-byte'

    $summary['Result'] = 'PASS'
} finally {
    foreach ($leftover in @(Get-QTranslateRegistration)) {
        Write-Host "Cleanup: uninstalling leftover product $($leftover.PSChildName)"
        try {
            Invoke-Msiexec '/x' $leftover.PSChildName (Join-Path $logDirectory 'cleanup.log') 'Cleanup uninstall' | Out-Null
        } catch { Write-Host "Cleanup uninstall failed: $_" }
    }
    Remove-Item -LiteralPath $isolatedAppData -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath (Join-Path $package 'msi-upgrade-staging') -Recurse -Force -ErrorAction SilentlyContinue
}

$summary.GetEnumerator() | ForEach-Object { Write-Host ("{0} = {1}" -f $_.Key, $_.Value) }
