<#
    QTranslate System OCR - Windows backend helper.

    Runs Windows.Media.Ocr out of process and writes the result as JSON. Everything is local; the
    image never leaves the machine.

    Invoked as:
        powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File windows-ocr.ps1 `
            -Command recognize|capabilities -ImagePath <path> -Language <bcp47> -OutputPath <path>

    Arguments are separate values and the process is started with -File, so no argument is ever
    interpreted as a command.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('recognize', 'capabilities')]
    [string]$Command,

    [string]$ImagePath = '',
    [string]$Language = '',
    [string]$OutputPath = ''
)

$ErrorActionPreference = 'Stop'

function Write-Result([hashtable]$Result) {
    $json = $Result | ConvertTo-Json -Compress -Depth 6
    if ([string]::IsNullOrEmpty($OutputPath)) {
        [Console]::Out.Write($json)
    }
    else {
        [System.IO.File]::WriteAllText($OutputPath, $json, (New-Object System.Text.UTF8Encoding($false)))
    }
}

function Write-Failure([string]$Category, [string]$Message) {
    Write-Result @{ ok = $false; category = $Category; error = $Message }
    exit 0
}

try {
    # Bridge WinRT IAsyncOperation<T> to a .NET Task so the script can wait on it. Windows
    # PowerShell 5.1, which ships with Windows, provides this.
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

    # Load the WinRT types used below into the session.
    $null = [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime]
    $null = [Windows.Globalization.Language, Windows.Globalization, ContentType = WindowsRuntime]
    $null = [Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]
    $null = [Windows.Storage.FileAccessMode, Windows.Storage, ContentType = WindowsRuntime]
    $null = [Windows.Graphics.Imaging.BitmapDecoder, Windows.Foundation, ContentType = WindowsRuntime]
    $null = [Windows.Storage.Streams.IRandomAccessStream, Windows.Storage.Streams, ContentType = WindowsRuntime]

    if ($Command -eq 'capabilities') {
        $tags = @()
        foreach ($lang in [Windows.Media.Ocr.OcrEngine]::AvailableRecognizerLanguages) {
            $tags += $lang.LanguageTag
        }

        # The engine's own limit; the plugin resizes to this rather than to a number of its own.
        Write-Result @{
            ok                = $true
            languages         = $tags
            maxImageDimension = [int][Windows.Media.Ocr.OcrEngine]::MaxImageDimension
        }
        exit 0
    }

    # --- recognize ---------------------------------------------------------------------------

    # A recognizer must be chosen; there is no user-profile fallback, so an absent language is an
    # error rather than a default the plugin substitutes.
    if ([string]::IsNullOrWhiteSpace($Language)) {
        Write-Failure 'unsupported_language' 'A language is required: Windows OCR does not detect the language of an image.'
    }

    $winLang = New-Object Windows.Globalization.Language($Language)
    if (-not [Windows.Media.Ocr.OcrEngine]::IsLanguageSupported($winLang)) {
        Write-Failure 'unsupported_language' "The OCR language '$Language' is not installed on this system."
    }

    $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage($winLang)
    if ($null -eq $engine) {
        Write-Failure 'no_engine' 'No Windows OCR recognizer is available for the requested language.'
    }

    if ([string]::IsNullOrEmpty($ImagePath) -or -not (Test-Path -LiteralPath $ImagePath)) {
        Write-Failure 'invalid_input' "Image file was not found: '$ImagePath'."
    }

    $file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($ImagePath)) ([Windows.Storage.StorageFile])
    $stream = Await ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
    try {
        $decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
        $bitmap = Await ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
        $ocr = Await ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])

        # Each line is reported with its words and their geometry. Words come back in visual order,
        # which is reversed for right-to-left text, so the plugin rebuilds the reading order from
        # this; `text` keeps the raw engine order for diagnostics and as a fallback.
        $lines = @()
        foreach ($line in $ocr.Lines) {
            $words = @()
            foreach ($word in $line.Words) {
                $rect = $word.BoundingRect
                $words += @{
                    t = $word.Text
                    x = [math]::Round($rect.X, 1)
                    y = [math]::Round($rect.Y, 1)
                }
            }
            $lines += @{
                text  = $line.Text
                words = $words
            }
        }

        Write-Result @{
            ok       = $true
            lines    = $lines
            text     = [string]::Join("`n", ($lines | ForEach-Object { $_.text }))
            language = $engine.RecognizerLanguage.LanguageTag
        }
    }
    finally {
        if ($null -ne $stream) { $stream.Dispose() }
    }
}
catch {
    Write-Failure 'recognition_failed' $_.Exception.Message
}
