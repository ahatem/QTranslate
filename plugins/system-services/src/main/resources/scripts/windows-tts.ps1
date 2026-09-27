param(
    [ValidateSet("voices", "synthesize")][string]$Command,
    [string]$OutputPath,
    [string]$InputPath,
    [string]$Voice,
    [int]$Rate = 0
)

$ErrorActionPreference = "Stop"
try {
    $synth = New-Object -ComObject SAPI.SpVoice
    if ($Command -eq "voices") {
        $voices = @($synth.GetVoices() | ForEach-Object {
            $token = $_
            try {
                # Registry entries can remain after a voice stops working. Probe into memory,
                # never the speakers, before offering that voice to the application.
                $probe = New-Object -ComObject SAPI.SpVoice
                $memory = New-Object -ComObject SAPI.SpMemoryStream
                $probe.Voice = $token
                $probe.AudioOutputStream = $memory
                $null = $probe.Speak("test", 16)
                if ($memory.GetData().Length -gt 44) {
                    $localeCode = ($token.GetAttribute("Language") -split ";")[0]
                    $culture = [System.Globalization.CultureInfo]::GetCultureInfo(
                        [Convert]::ToInt32($localeCode, 16)
                    )
                    [pscustomobject]@{
                        id = $token.Id
                        name = $token.GetDescription()
                        locale = $culture.Name
                        gender = $token.GetAttribute("Gender")
                    }
                }
            } catch {
                # A broken voice is not an available capability.
            }
        })
        $json = ConvertTo-Json -InputObject $voices -Compress -Depth 3
        [System.IO.File]::WriteAllText($OutputPath, $json, (New-Object System.Text.UTF8Encoding($false)))
    } else {
        $token = $synth.GetVoices() | Where-Object { $_.Id -eq $Voice } | Select-Object -First 1
        if ($null -eq $token) { exit 2 }
        $text = [System.IO.File]::ReadAllText($InputPath, [System.Text.Encoding]::UTF8)
        $stream = New-Object -ComObject SAPI.SpFileStream
        try {
            $stream.Open($OutputPath, 3, $false)
            $synth.Voice = $token
            $synth.AudioOutputStream = $stream
            $synth.Rate = [Math]::Max(-10, [Math]::Min(10, $Rate))
            $null = $synth.Speak($text, 16)
        } finally {
            $stream.Close()
        }
    }
} catch {
    exit 1
}
