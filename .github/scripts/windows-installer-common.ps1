# Shared Windows Installer packaging helpers. Dot-sourced by package-windows.ps1,
# verify-windows-installer.ps1, and test-windows-installer-version.ps1 so the
# upgrade identity, the version mapping, and the MSI metadata reader each exist
# exactly once.

$script:RepositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path

function Get-WindowsUpgradeUuid {
    $uuidFile = Join-Path $script:RepositoryRoot '.github\windows-upgrade-uuid.txt'
    $line = Get-Content -LiteralPath $uuidFile |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -ne '' -and -not $_.StartsWith('#') } |
        Select-Object -First 1
    if ($line -notmatch '^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$') {
        throw "Invalid Windows upgrade UUID in ${uuidFile}: '$line'"
    }
    $line.ToLowerInvariant()
}

# Maps a QTranslate release version to a Windows Installer ProductVersion.
#
# ProductVersion is strictly major.minor.build (255.255.65535) and cannot
# represent a semver prerelease label, so a prerelease candidate carries its
# base version: 1.6.0-rc.1 becomes 1.6.0. Two prerelease candidates of the same
# base therefore share a ProductVersion. That is a documented MSI limitation,
# not a release path: prerelease MSIs are internal CI candidates, and the
# upgrade smoke uses explicit synthetic versions instead. Public releases
# always advance major.minor.build, where the mapping is exact.
function ConvertTo-MsiProductVersion([string] $Version) {
    $base = ($Version -split '[-+]')[0]
    $parts = $base -split '\.'
    if ($parts.Count -ne 3 -or ($parts | Where-Object { $_ -notmatch '^\d+$' })) {
        throw "Cannot map '$Version' to an MSI ProductVersion: expected numeric major.minor.build"
    }
    $limits = @(255, 255, 65535)
    for ($i = 0; $i -lt 3; $i++) {
        if ([int] $parts[$i] -gt $limits[$i]) {
            throw "Cannot map '$Version' to an MSI ProductVersion: component $($parts[$i]) exceeds the $($limits[$i]) limit"
        }
    }
    $base
}

# Reads entries from an MSI's Property table (ProductVersion, ProductCode,
# UpgradeCode, ...) through the Windows Installer COM API.
function Get-MsiProperty([string] $MsiPath, [string[]] $Names) {
    $installer = $null
    $database = $null
    $view = $null
    try {
        $installer = New-Object -ComObject WindowsInstaller.Installer
        $database = $installer.GetType().InvokeMember('OpenDatabase', 'InvokeMethod', $null, $installer, @($MsiPath, 0))
        $view = $database.GetType().InvokeMember('OpenView', 'InvokeMethod', $null, $database,
            @('SELECT `Property`, `Value` FROM `Property`'))
        $view.GetType().InvokeMember('Execute', 'InvokeMethod', $null, $view, $null) | Out-Null
        $result = @{}
        while ($true) {
            $record = $view.GetType().InvokeMember('Fetch', 'InvokeMethod', $null, $view, $null)
            if ($null -eq $record) { break }
            try {
                $name = $record.GetType().InvokeMember('StringData', 'GetProperty', $null, $record, 1)
                $value = $record.GetType().InvokeMember('StringData', 'GetProperty', $null, $record, 2)
                if ($name -in $Names) { $result[$name] = $value }
            } finally {
                [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($record)
            }
        }
        $result
    } finally {
        foreach ($comObject in @($view, $database, $installer)) {
            if ($null -ne $comObject) {
                [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($comObject)
            }
        }
    }
}

# Runs the release readiness probe with a bounded wait. $Environment entries
# override process environment variables, so the probe can be pointed at an
# isolated APPDATA without touching the real user profile.
function Invoke-BoundedProbe([string] $Executable, [string[]] $Arguments, [hashtable] $Environment = @{}) {
    $start = [System.Diagnostics.ProcessStartInfo]::new($Executable)
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.Environment['JAVA_HOME'] = ''
    foreach ($key in $Environment.Keys) { $start.Environment[$key] = $Environment[$key] }
    foreach ($argument in $Arguments) { [void] $start.ArgumentList.Add($argument) }
    $process = [System.Diagnostics.Process]::Start($start)
    try {
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(120000)) {
            $process.Kill($true)
            throw "Readiness probe timed out: $Executable"
        }
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        Write-Host $stdout
        if ($process.ExitCode -ne 0 -or $stdout -notmatch 'Release readiness passed:') {
            throw "Readiness probe failed ($($process.ExitCode)): $Executable`n$stderr"
        }
    } finally { $process.Dispose() }
}
