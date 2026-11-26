<#
.SYNOPSIS
  Build "Chromium uBlock" on Windows: a portable Chromium with uBlock Origin Lite built in.

.DESCRIPTION
  Produces dist\chromium-ublock\ with:
    chromium\               the browser (latest Chromium snapshot)
    extensions\ublock\      uBlock Origin Lite, unpacked
    Chromium uBlock.cmd     launcher that always starts Chromium with uBO loaded

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\build.ps1 -Install
#>
param(
  [string]$Chromium = "",      # use an existing chrome.exe instead of downloading
  [string]$Revision = "",      # Chromium snapshot revision (default: latest)
  [string]$UblockZip = "",     # local uBlock Origin Lite .zip (default: latest release)
  [string]$Out = "",
  [switch]$Install             # add Desktop and Start menu shortcuts
)
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"   # Invoke-WebRequest is very slow with progress on
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Snapshots = "https://commondatastorage.googleapis.com/chromium-browser-snapshots/Win_x64"
$UbolApi   = "https://api.github.com/repos/uBlockOrigin/uBOL-home/releases/latest"
$UbolGit   = "https://github.com/uBlockOrigin/uBOL-home"

function Log($m) { Write-Host "==> $m" -ForegroundColor Cyan }

if (-not $Out) { $Out = Join-Path $PSScriptRoot "dist\chromium-ublock" }
$Work = Join-Path ([IO.Path]::GetTempPath()) ("chromium-ublock-" + [guid]::NewGuid())
New-Item -ItemType Directory -Force $Work | Out-Null
try {
  if (Test-Path $Out) { Remove-Item -Recurse -Force $Out }
  New-Item -ItemType Directory -Force (Join-Path $Out "extensions") | Out-Null

  # ------------------------------------------------------------ Chromium
  if ($Chromium) {
    $Exe = (Resolve-Path $Chromium).Path
    Log "Using existing Chromium: $Exe"
  } else {
    if (-not $Revision) { $Revision = (Invoke-WebRequest -UseBasicParsing "$Snapshots/LAST_CHANGE").Content.Trim() }
    Log "Downloading Chromium Win_x64 snapshot r$Revision"
    $zip = Join-Path $Work "chrome-win.zip"
    Invoke-WebRequest -UseBasicParsing "$Snapshots/$Revision/chrome-win.zip" -OutFile $zip
    Expand-Archive $zip -DestinationPath (Join-Path $Work "chromium")
    Move-Item (Join-Path $Work "chromium\chrome-win") (Join-Path $Out "chromium")
    Set-Content (Join-Path $Out "chromium\REVISION") $Revision
    $Exe = Join-Path $Out "chromium\chrome.exe"
  }

  # ------------------------------------------------------------ uBlock Origin Lite
  $Ubo = Join-Path $Out "extensions\ublock"
  if (-not $UblockZip) {
    Log "Looking up latest uBlock Origin Lite release"
    try {
      $rel = Invoke-RestMethod $UbolApi
      $asset = $rel.assets | Where-Object { $_.name -like "*chromium*.zip" } | Select-Object -First 1
      if ($asset) {
        Log "Downloading $($asset.name)"
        $UblockZip = Join-Path $Work "ubol.zip"
        Invoke-WebRequest -UseBasicParsing $asset.browser_download_url -OutFile $UblockZip
      }
    } catch { Write-Warning "Release lookup failed: $_" }
  }
  if ($UblockZip) {
    Expand-Archive $UblockZip -DestinationPath (Join-Path $Work "ubol")
    $manifest = Get-ChildItem (Join-Path $Work "ubol") -Recurse -Depth 2 -Filter manifest.json | Select-Object -First 1
    if (-not $manifest) { throw "no manifest.json in $UblockZip" }
    Move-Item $manifest.DirectoryName $Ubo
  } else {
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw "GitHub releases unreachable and git not installed" }
    Log "Fetching uBlock Origin Lite from git"
    git clone -q --depth 1 $UbolGit (Join-Path $Work "ubol-git")
    Move-Item (Join-Path $Work "ubol-git\chromium") $Ubo
  }
  $ver = (Get-Content (Join-Path $Ubo "manifest.json") -Raw | ConvertFrom-Json).version
  Log "uBlock Origin Lite $ver"

  # ------------------------------------------------------------ launcher
  $exeRef = if ($Chromium) { $Exe } else { "%~dp0chromium\chrome.exe" }
  $launcher = Join-Path $Out "Chromium uBlock.cmd"
  @"
@echo off
rem Starts Chromium with uBlock Origin Lite always loaded.
if "%CHROMIUM_UBLOCK_PROFILE%"=="" set "CHROMIUM_UBLOCK_PROFILE=%LOCALAPPDATA%\chromium-ublock"
start "" "$exeRef" --user-data-dir="%CHROMIUM_UBLOCK_PROFILE%" --load-extension="%~dp0extensions\ublock" --disable-features=DisableLoadExtensionCommandLineSwitch --no-default-browser-check %*
"@ | Set-Content -Encoding ASCII $launcher

  # ------------------------------------------------------------ shortcuts
  if ($Install) {
    $shell = New-Object -ComObject WScript.Shell
    $targets = @([Environment]::GetFolderPath("Desktop"), [Environment]::GetFolderPath("Programs"))
    foreach ($dir in $targets) {
      $lnk = $shell.CreateShortcut((Join-Path $dir "Chromium uBlock.lnk"))
      $lnk.TargetPath = $launcher
      $lnk.WorkingDirectory = $Out
      $lnk.IconLocation = "$Exe,0"
      $lnk.WindowStyle = 7   # minimized: hides the brief console flash
      $lnk.Save()
    }
    Log "Added Desktop and Start menu shortcuts"
  }
  Log "Done: $launcher"
} finally {
  Remove-Item -Recurse -Force $Work -ErrorAction SilentlyContinue
}
