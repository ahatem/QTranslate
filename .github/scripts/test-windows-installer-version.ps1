# Tests the release-version -> MSI ProductVersion mapping used by the Windows
# packaging scripts. Runs without a build: pwsh ./.github/scripts/test-windows-installer-version.ps1

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'windows-installer-common.ps1')

$failures = 0
function Assert-Mapping([string] $Version, [string] $Expected) {
    $actual = ConvertTo-MsiProductVersion $Version
    if ($actual -ne $Expected) {
        $script:failures++
        Write-Host "FAIL: '$Version' mapped to '$actual', expected '$Expected'"
    } else {
        Write-Host "ok: '$Version' -> '$actual'"
    }
}
function Assert-Rejects([string] $Version) {
    try {
        $actual = ConvertTo-MsiProductVersion $Version
        $script:failures++
        Write-Host "FAIL: '$Version' mapped to '$actual', expected rejection"
    } catch {
        Write-Host "ok: '$Version' rejected"
    }
}

# Releases map exactly.
Assert-Mapping '1.5.1' '1.5.1'
Assert-Mapping '1.6.0' '1.6.0'
Assert-Mapping '2.0.0' '2.0.0'
Assert-Mapping '255.255.65535' '255.255.65535'

# Prerelease/build metadata cannot exist in a ProductVersion: the candidate
# carries its base version. Two prereleases of one base share it - the
# documented MSI limitation that keeps prerelease MSIs as CI candidates only.
Assert-Mapping '1.6.0-rc.1' '1.6.0'
Assert-Mapping '1.6.0-rc.2' '1.6.0'
Assert-Mapping '1.6.0-rc.1+ci.7' '1.6.0'
Assert-Mapping '1.6.0+build.5' '1.6.0'

# Malformed or out-of-range versions are rejected, never silently truncated.
Assert-Rejects '1.6'
Assert-Rejects '1.6.0.1'
Assert-Rejects '256.0.0'
Assert-Rejects '1.256.0'
Assert-Rejects '1.6.65536'
Assert-Rejects 'x.y.z'
Assert-Rejects ''

if ($failures -gt 0) { throw "$failures MSI version mapping test(s) failed" }
Write-Host 'MSI version mapping tests passed'
