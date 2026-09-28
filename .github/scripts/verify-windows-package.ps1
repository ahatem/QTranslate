param(
    [Parameter(Mandatory = $true)][string] $Version,
    [Parameter(Mandatory = $true)][string] $PackageDirectory
)

$ErrorActionPreference = 'Stop'
$package = (Resolve-Path -LiteralPath $PackageDirectory).Path
$archive = Join-Path $package "QTranslate-$Version-windows-x64.zip"
if (-not (Test-Path -LiteralPath $archive -PathType Leaf)) { throw "Windows candidate archive is missing: $archive" }
$extracted = Join-Path $package 'verified-extraction'
Expand-Archive -LiteralPath $archive -DestinationPath $extracted
$root = Join-Path $extracted 'QTranslate'
foreach ($file in @('QTranslate.exe', 'runtime/bin/java.exe', 'app/QTranslate.jar',
        'portable-plugin-ids.txt', 'LICENSE', 'NOTICE.md')) {
    if (-not (Test-Path -LiteralPath (Join-Path $root $file) -PathType Leaf)) {
        throw "Windows candidate is missing $file"
    }
}
foreach ($directory in @('plugins', 'languages', 'themes', 'icons', 'LICENSES', 'THIRD_PARTY_LICENSES')) {
    if (-not (Test-Path -LiteralPath (Join-Path $root $directory) -PathType Container)) {
        throw "Windows candidate is missing $directory"
    }
}
function Invoke-BoundedProbe([string] $executable, [string[]] $arguments) {
    $start = [System.Diagnostics.ProcessStartInfo]::new($executable)
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.Environment['JAVA_HOME'] = ''
    foreach ($argument in $arguments) { [void] $start.ArgumentList.Add($argument) }
    $process = [System.Diagnostics.Process]::Start($start)
    try {
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(120000)) {
            $process.Kill($true)
            throw "Readiness probe timed out: $executable"
        }
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        Write-Host $stdout
        if ($process.ExitCode -ne 0 -or $stdout -notmatch 'Release readiness passed:') {
            throw "Readiness probe failed ($($process.ExitCode)): $executable`n$stderr"
        }
    } finally { $process.Dispose() }
}

$jar = Join-Path $root 'app/QTranslate.jar'
Invoke-BoundedProbe (Join-Path $root 'runtime/bin/java.exe') @(
    '-Djava.awt.headless=true', '-cp', $jar,
    'com.github.ahatem.qtranslate.app.ReleaseArtifactProbeKt', $root)
Invoke-BoundedProbe (Join-Path $root 'QTranslate.exe') @('--release-probe', $root)
