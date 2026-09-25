param(
    [string]$HostName = "localhost",
    [int]$Port = 9999,
    [ValidateSet("gemini", "openai")]
    [string]$Provider = "openai",
    # Optional:
    # - "albert_zweistein" / "tom_sawyer" fuer feste Personas
    # - "?" fuer einen dynamisch generierten Mystery Guest
    # - leer lassen fuer interaktive Auswahl im AI-Fenster
    [string]$Persona = "",
    # Optional: wenn nicht gesetzt, wird $env:GEMINI_KEY (falls vorhanden) verwendet.
    [string]$GeminiKey = "",
    # Optional: wenn nicht gesetzt, wird $env:OPENAI_API_KEY (falls vorhanden) verwendet.
    [string]$OpenAiKey = "",
    # Deaktiviert das automatische Anordnen (z.B. Remote/Headless).
    [switch]$NoArrange = $true
)

$ErrorActionPreference = "Stop"
$validPersonas = @("", "tom_sawyer", "albert_zweistein", "?")
$Persona = if ($null -eq $Persona) { "" } else { $Persona.Trim() }

if ($validPersonas -notcontains $Persona) {
    throw "Ungueltige Persona '$Persona'. Erlaubt sind: tom_sawyer, albert_zweistein, ?, oder leer fuer die interaktive Auswahl."
}

function Get-AppPropertyValue {
    param(
        [Parameter(Mandatory=$true)][string]$Key
    )

    $propsPath = Join-Path $PSScriptRoot "src\main\resources\application.properties"
    if (-not (Test-Path -LiteralPath $propsPath)) {
        return ""
    }

    foreach ($line in Get-Content -LiteralPath $propsPath) {
        $trimmed = $line.Trim()
        if ([string]::IsNullOrWhiteSpace($trimmed)) { continue }
        if ($trimmed.StartsWith("#") -or $trimmed.StartsWith("!")) { continue }

        $idx = $trimmed.IndexOf("=")
        if ($idx -lt 1) { continue }

        $name = $trimmed.Substring(0, $idx).Trim()
        if ($name -ne $Key) { continue }

        $value = $trimmed.Substring($idx + 1).Trim()
        if ($value -match '^\$\{.+\}$') { return "" }
        return $value
    }

    return ""
}

function Find-ShadedJar {
    $target = Join-Path $PSScriptRoot "target"
    if (-not (Test-Path $target)) {
        throw "Ordner 'target' nicht gefunden. Bitte zuerst bauen: mvn -DskipTests package"
    }
    $jar = Get-ChildItem -Path $target -Filter "*-shaded.jar" -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if (-not $jar) {
        throw "Kein '*-shaded.jar' in 'target' gefunden. Bitte zuerst bauen: mvn -DskipTests package"
    }
    return $jar.FullName
}

function Ensure-FreshShadedJar {
    $jarPath = Find-ShadedJar
    $jarItem = Get-Item -LiteralPath $jarPath

    $inputs = @()
    $srcRoot = Join-Path $PSScriptRoot "src"
    if (Test-Path $srcRoot) {
        $inputs += Get-ChildItem -Path $srcRoot -Recurse -File
    }

    $pomPath = Join-Path $PSScriptRoot "pom.xml"
    if (Test-Path $pomPath) {
        $inputs += Get-Item -LiteralPath $pomPath
    }

    if ($inputs.Count -gt 0) {
        $latestInput = $inputs | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($latestInput.LastWriteTime -gt $jarItem.LastWriteTime) {
            Write-Host "Shaded JAR ist veraltet. Fuehre 'mvn -DskipTests package' aus..."
            Push-Location $PSScriptRoot
            try {
                & mvn -DskipTests package | Out-Host
                if ($LASTEXITCODE -ne 0) {
                    throw "Maven package fehlgeschlagen (ExitCode=$LASTEXITCODE)."
                }
            } finally {
                Pop-Location
            }
            $jarPath = Find-ShadedJar
        }
    }

    return $jarPath
}

$jarPath = Ensure-FreshShadedJar

Write-Host "Jar: $jarPath"
if ([string]::IsNullOrWhiteSpace($Persona)) {
    Write-Host "Starte: server -> human -> ai (Host=$HostName Port=$Port Provider=$Provider Persona=<interactive selection>)"
} else {
    Write-Host "Starte: server -> human -> ai (Host=$HostName Port=$Port Provider=$Provider Persona=$Persona)"
}

# Fenster-Titel, damit wir sie spaeter eindeutig finden und anordnen koennen.
$titleServer = "AI Chat - Server"
$titleHuman  = "AI Chat - Human"
$titleAi     = "AI Chat - AI"

function Start-TitledPowerShell {
    param(
        [Parameter(Mandatory=$true)][string]$Title,
        [Parameter(Mandatory=$true)][string]$Command
    )

    # Use -EncodedCommand to avoid brittle quoting rules when the command contains quotes (e.g. API keys).
    $script = @"
Set-Location -LiteralPath '$($PSScriptRoot.Replace("'", "''"))'
`$host.UI.RawUI.WindowTitle = '$($Title.Replace("'", "''"))'
$Command
"@
    $bytes = [System.Text.Encoding]::Unicode.GetBytes($script)
    $encoded = [Convert]::ToBase64String($bytes)

    return Start-Process -FilePath "powershell.exe" -WorkingDirectory $PSScriptRoot -PassThru -ArgumentList @(
        "-NoExit",
        "-NoProfile",
        "-EncodedCommand",
        $encoded
    )
}

function Enable-WindowArrange {
    if ($NoArrange) { return $false }
    try {
        Add-Type -AssemblyName System.Windows.Forms | Out-Null
        if (-not ("AiChatWin32" -as [type])) {
            Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class AiChatWin32 {
  [DllImport("user32.dll", SetLastError=true)]
  public static extern bool MoveWindow(IntPtr hWnd, int X, int Y, int nWidth, int nHeight, bool bRepaint);

  [DllImport("user32.dll", SetLastError=true)]
  public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

  [DllImport("user32.dll", SetLastError=true)]
  public static extern bool SetWindowPos(IntPtr hWnd, IntPtr hWndInsertAfter, int X, int Y, int cx, int cy, uint uFlags);

  public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

  [DllImport("user32.dll")]
  public static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);

  [DllImport("user32.dll", CharSet=CharSet.Unicode)]
  public static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);

  [DllImport("user32.dll", CharSet=CharSet.Unicode)]
  public static extern int GetWindowTextLength(IntPtr hWnd);
}
"@ | Out-Null
        }
        return $true
    } catch {
        Write-Host "Hinweis: Window-Arrange deaktiviert (Win32/Forms nicht verfuegbar): $($_.Exception.Message)"
        return $false
    }
}

function Wait-ForMainWindowHandle {
    param(
        [Parameter(Mandatory=$true)][System.Diagnostics.Process]$Process,
        [int]$TimeoutSeconds = 10
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $Process.Refresh()
            $h = $Process.MainWindowHandle
            if ($h -ne [IntPtr]::Zero) { return $h }
        } catch {
            return [IntPtr]::Zero
        }
        Start-Sleep -Milliseconds 100
    }
    return [IntPtr]::Zero
}

function Find-WindowHandleByTitleContains {
    param(
        [Parameter(Mandatory=$true)][string]$Needle,
        [int]$TimeoutSeconds = 10
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $script:found = [IntPtr]::Zero
        $cb = [AiChatWin32+EnumWindowsProc]{
            param([IntPtr]$hWnd, [IntPtr]$lParam)
            $len = [AiChatWin32]::GetWindowTextLength($hWnd)
            if ($len -le 0) { return $true }
            $sb = New-Object System.Text.StringBuilder ($len + 1)
            [void][AiChatWin32]::GetWindowText($hWnd, $sb, $sb.Capacity)
            $t = $sb.ToString()
            if ($t -and $t.IndexOf($Needle, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
                $script:found = $hWnd
                return $false
            }
            return $true
        }
        [void][AiChatWin32]::EnumWindows($cb, [IntPtr]::Zero)
        if ($script:found -ne [IntPtr]::Zero) { return $script:found }
        Start-Sleep -Milliseconds 150
    }
    return [IntPtr]::Zero
}

function Move-WindowByProcess {
    param(
        [Parameter(Mandatory=$true)][System.Diagnostics.Process]$Process,
        [Parameter(Mandatory=$true)][string]$TitleHint,
        [Parameter(Mandatory=$true)][int]$X,
        [Parameter(Mandatory=$true)][int]$Y,
        [Parameter(Mandatory=$true)][int]$W,
        [Parameter(Mandatory=$true)][int]$H
    )

    $h = Wait-ForMainWindowHandle -Process $Process -TimeoutSeconds 12
    if ($h -eq [IntPtr]::Zero) {
        # Fallback: Fenster per EnumWindows ueber Titel finden (robuster bei Terminal-Hosts / wechselnden Handles).
        $h = Find-WindowHandleByTitleContains -Needle $TitleHint -TimeoutSeconds 8
        if ($h -eq [IntPtr]::Zero) {
            Write-Host "Konnte Fenster nicht finden (PID=$($Process.Id), TitelHint='$TitleHint'). Layout uebersprungen."
            return
        }
    }
    # Console-Fenster koennen initial maximiert sein oder ihre Position kurz nach dem Start noch aendern.
    # Deshalb: erst restore, dann mehrfach SetWindowPos/MoveWindow (best effort) mit kurzer Pause.
    $SW_RESTORE = 9
    $SWP_NOZORDER = 0x0004
    $SWP_NOACTIVATE = 0x0010
    $SWP_SHOWWINDOW = 0x0040
    for ($i = 0; $i -lt 6; $i++) {
        $okShow = [AiChatWin32]::ShowWindow($h, $SW_RESTORE)
        $okPos = [AiChatWin32]::SetWindowPos($h, [IntPtr]::Zero, $X, $Y, $W, $H, ($SWP_NOZORDER -bor $SWP_NOACTIVATE -bor $SWP_SHOWWINDOW))
        $okMove = [AiChatWin32]::MoveWindow($h, $X, $Y, $W, $H, $true)
        if (-not ($okPos -or $okMove)) {
            $err = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
            Write-Host "Move/Pos failed (TitleHint='$TitleHint', hWnd=$h, err=$err, showOk=$okShow)"
        }
        Start-Sleep -Milliseconds 150
    }
}

# 1) Server
$pServer = Start-TitledPowerShell -Title $titleServer -Command ("java -jar `"$jarPath`" server")

Start-Sleep -Seconds 1

# 2) Human-Client
$pHuman = Start-TitledPowerShell -Title $titleHuman -Command ("java -jar `"$jarPath`" human $HostName $Port")

Start-Sleep -Seconds 1

# 3) AI-Client (Provider und API-Key nur in diesem Prozess setzen)
$providerArg = $Provider.Trim().ToLowerInvariant()
$aiPrefix = ""
if ($providerArg -eq "openai") {
    $keyFromProps = Get-AppPropertyValue -Key "openai.api.key"
    if (-not [string]::IsNullOrWhiteSpace($OpenAiKey)) {
        $keyToUse = $OpenAiKey
    } elseif (-not [string]::IsNullOrWhiteSpace($keyFromProps)) {
        $keyToUse = $keyFromProps
    } else {
        $keyToUse = $env:OPENAI_API_KEY
    }
    if ([string]::IsNullOrWhiteSpace($keyToUse)) {
        throw "OpenAI-Key fehlt. Setze 'openai.api.key' in application.properties oder OPENAI_API_KEY und starte erneut."
    }
    if (-not [string]::IsNullOrWhiteSpace($keyToUse)) {
        $k = $keyToUse.Replace("'", "''")
        $aiPrefix = "`$env:OPENAI_API_KEY='$k'; "
    }
} else {
    $keyFromProps = Get-AppPropertyValue -Key "gemini.api.key"
    if (-not [string]::IsNullOrWhiteSpace($GeminiKey)) {
        $keyToUse = $GeminiKey
    } elseif (-not [string]::IsNullOrWhiteSpace($keyFromProps)) {
        $keyToUse = $keyFromProps
    } else {
        $keyToUse = $env:GEMINI_KEY
    }
    if (-not [string]::IsNullOrWhiteSpace($keyToUse)) {
        $k = $keyToUse.Replace("'", "''")
        $aiPrefix = "`$env:GEMINI_KEY='$k'; "
    }
}

$aiArgs = if ([string]::IsNullOrWhiteSpace($Persona)) {
    "ai $HostName $Port"
} else {
    $personaArg = "'" + $Persona.Replace("'", "''") + "'"
    "ai $personaArg $HostName $Port"
}

$pAi = Start-TitledPowerShell -Title $titleAi -Command ("${aiPrefix}java '-Dai.provider=$providerArg' -jar `"$jarPath`" $aiArgs")

if (Enable-WindowArrange) {
    # Kurz warten, damit conhost seine initiale Fenster-Geometrie gesetzt hat.
    Start-Sleep -Milliseconds 800

    $hServer = Wait-ForMainWindowHandle -Process $pServer -TimeoutSeconds 8
    if ($hServer -eq [IntPtr]::Zero) {
        $hServer = Find-WindowHandleByTitleContains -Needle $titleServer -TimeoutSeconds 6
    }
    $screen = if ($hServer -ne [IntPtr]::Zero) { [System.Windows.Forms.Screen]::FromHandle($hServer) } else { [System.Windows.Forms.Screen]::PrimaryScreen }
    $wa = $screen.WorkingArea
    $sx = [int]$wa.X
    $sy = [int]$wa.Y
    $sw = [int]$wa.Width
    $sh = [int]$wa.Height

    # Layout:
    # - Human links oben (haelfte Breite)
    # - AI rechts oben (haelfte Breite)
    # - Server mittig unten (ca. 2/3 Breite)
    $gap = 10
    $topH = [int]($sh * 0.60)
    $bottomH = [int]($sh - $topH - $gap)
    if ($bottomH -lt 200) { $bottomH = 200 }

    $leftW = [int](($sw - $gap) / 2)
    $rightW = [int]($sw - $leftW - $gap)

    $serverW = [int]($sw * 0.66)
    if ($serverW -gt $sw) { $serverW = $sw }
    $serverX = [int]($sx + (($sw - $serverW) / 2))
    $serverY = [int]($sy + $topH + $gap)

    # Leicht versetzt: erst AI/Human, dann Server, damit Handles sicher verfuegbar sind.
    Move-WindowByProcess -Process $pHuman -TitleHint $titleHuman -X $sx -Y $sy -W $leftW -H $topH
    Move-WindowByProcess -Process $pAi -TitleHint $titleAi -X ([int]($sx + $leftW + $gap)) -Y $sy -W $rightW -H $topH
    Move-WindowByProcess -Process $pServer -TitleHint $titleServer -X $serverX -Y $serverY -W $serverW -H $bottomH
}

Write-Host "Fertig. In den Client-Fenstern: /quit zum Beenden. Server per Ctrl+C."
Write-Host "Hinweis: Ohne -Persona startet der AI-Client mit interaktiver Persona-Auswahl."
