[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('voices', 'synthesize')][string]$Command,
    [string]$OutputPath = '',
    [string]$InputPath = '',
    [string]$Voice = '',
    [double]$Rate = 1.0
)

$ErrorActionPreference = 'Stop'
try {
    # The same WinRT IAsyncOperation<T> bridge used by windows-ocr.ps1.
    Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null
    $asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
        $_.Name -eq 'AsTask' -and
        $_.GetParameters().Count -eq 1 -and
        $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
    })[0]
    function Await($Operation, $ResultType) {
        $task = $asTaskGeneric.MakeGenericMethod($ResultType).Invoke($null, @($Operation))
        $task.Wait(-1) | Out-Null
        $task.Result
    }

    $null = [Windows.Media.SpeechSynthesis.SpeechSynthesizer, Windows.Media.SpeechSynthesis, ContentType = WindowsRuntime]
    $null = [Windows.Media.SpeechSynthesis.SpeechSynthesisStream, Windows.Media.SpeechSynthesis, ContentType = WindowsRuntime]
    $null = [Windows.Storage.Streams.IInputStream, Windows.Storage.Streams, ContentType = WindowsRuntime]

    if ($Command -eq 'voices') {
        $voices = @(
            foreach ($item in [Windows.Media.SpeechSynthesis.SpeechSynthesizer]::AllVoices) {
                [pscustomobject]@{
                    id = $item.Id
                    name = $item.DisplayName
                    locale = $item.Language.Replace('_', '-')
                    gender = $item.Gender.ToString()
                }
            }
        )
        $json = ConvertTo-Json -InputObject $voices -Compress -Depth 3
        [System.IO.File]::WriteAllText($OutputPath, $json, (New-Object System.Text.UTF8Encoding($false)))
        exit 0
    }

    # Resolve the exact installed WinRT voice; never silently use DefaultVoice.
    $selected = [Windows.Media.SpeechSynthesis.SpeechSynthesizer]::AllVoices |
        Where-Object { $_.Id -ceq $Voice } | Select-Object -First 1
    if ($null -eq $selected) {
        [Console]::Error.WriteLine('voice_not_installed')
        exit 2
    }
    $text = [System.IO.File]::ReadAllText($InputPath, [System.Text.Encoding]::UTF8)
    $synth = New-Object Windows.Media.SpeechSynthesis.SpeechSynthesizer
    try {
        $synth.Voice = $selected
        $synth.Options.SpeakingRate = [Math]::Max(0.5, [Math]::Min(6.0, $Rate))
        $speechStream = Await ($synth.SynthesizeTextToStreamAsync($text)) ([Windows.Media.SpeechSynthesis.SpeechSynthesisStream])
        try {
            if ($speechStream.ContentType -notin @('audio/wav', 'audio/x-wav', 'audio/wave')) {
                throw ('unsupported_audio_format=' + $speechStream.ContentType)
            }
            $source = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead(
                [Windows.Storage.Streams.IInputStream]$speechStream
            )
            $target = [System.IO.File]::Create($OutputPath)
            try { $source.CopyTo($target) }
            finally {
                $target.Dispose()
                $source.Dispose()
            }
        }
        finally { $speechStream.Dispose() }
    }
    finally { $synth.Dispose() }
}
catch {
    if ($_.Exception.Message.StartsWith('unsupported_audio_format=')) {
        [Console]::Error.WriteLine($_.Exception.Message)
        exit 3
    }
    # Diagnostics contain the native code and category, never the input text.
    $failure = $_.Exception
    while ($null -ne $failure.InnerException) { $failure = $failure.InnerException }
    [Console]::Error.WriteLine(('winrt_speech_failed HRESULT=0x{0:X8} type={1}' -f
        $failure.HResult, $failure.GetType().FullName))
    exit 1
}
