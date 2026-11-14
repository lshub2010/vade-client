# Real-window capture for the Vade client (Windows only) -- concurrency-safe version.
#
# WHAT IT DOES
#   Launches the Desktop app from a compiled classpath (java -cp ... vade.ui.MainKt),
#   waits for its AWT window (class SunAwtFrame), crops by the DWM extended frame
#   bounds and saves a PNG via PrintWindow with PW_RENDERFULLCONTENT (flag 2).
#   PrintWindow is used instead of CopyFromScreen because the agent session on this
#   host has no capturable desktop: CopyFromScreen throws "invalid parameter" and
#   yields a fully transparent bitmap, while PrintWindow asks the window itself to
#   render into our DC.
#
#   Sizing note: the DWM frame is ~14x7 px smaller than the requested client size,
#   so `-Size 480x900` produces a ~466x893 PNG. That is why the default is 480x900.
#
# WHY THIS FILE WAS REWRITTEN (concurrency safety)
#   Earlier versions stopped every java process on the machine before launching
#   (a blanket force-kill over all java pids). On this host several agents build in
#   parallel and a Gradle daemon is also a java process, so one screenshot call
#   turned sibling builds into "BUILD FAILED in 2s/11s" with no compiler errors at
#   all. The same blanket kill was originally added because WindowsOf(pid) could
#   match a stale window left over from the previous round, so old captures mixed
#   old builds into new shots. The rules in THIS version:
#     1. The only process stopped unconditionally is the one started by THIS run
#        (Start-Process -PassThru). Every exit path stops that exact pid only.
#     2. A leftover from a CRASHED previous run is cleaned via the pid record file
#        shots/.capture-pid ("pid|startTimeTicks"). The recorded pid is stopped
#        only when ALL of these hold: it is running; its process name is exactly
#        'java'; its start time matches the recorded start time (+/-5s, so a
#        recycled pid that now belongs to a different process is refused); and it
#        started before this run began. Any failed check -> nothing is killed.
#     3. Processes are never enumerated or stopped by name anywhere in this file.
#     4. Window matching uses Process.MainWindowHandle of THIS run's process
#        (validated: class starts with 'SunAwt', size > 300x300). While the handle
#        is 0 the script keeps polling until -WaitSec. One exact last-resort
#        fallback enumerates windows owned by THIS pid only (no other process can
#        ever match). On timeout: NO_WINDOW + exit 2, never a guessed window.
#
# PID RECORD FILE  shots/.capture-pid
#   Format: "<pid>|<startTimeTicks>" (ASCII, one line).
#   Written right after this run launches the app; removed once this run's own
#   process is confirmed stopped (or immediately when found stale/corrupt).
#   Removal is guarded: the file is only deleted when it still holds OUR record,
#   so a concurrent capture in another shell cannot have its record clobbered.
#   NOTE: concurrent captures in the SAME worktree are not supported (same output
#   file, same record file); captures from different worktrees are fully isolated.
#
# PARAMETERS
#   -Java <path>          (mandatory) java.exe used to launch the app.
#   -ClassPathFile <path> (mandatory) text file holding the runtime classpath
#                          (output of gradle :composeApp:printRuntimeClasspath).
#   -Bar <name>           (mandatory) app shell variant, e.g. fixed.
#   -OutFile <path>       (mandatory) PNG output path, e.g. shots/c-capture/x.png.
#   (-Java/-ClassPathFile/-Bar/-OutFile must still be supplied (dummies are fine)
#    for -CleanupOnly / -DarkCheckOnly runs, even though they are not used there.)
#   -Size WxH             requested window client size (default 480x900).
#   -Dest <name>          app destination preset (default sessions).
#   -Panel <name>         panel preset (default closed).
#   -Search <query>       open the search overlay preset with <query>.
#   -SearchEmpty          open the search overlay with nothing typed.
#   -Skin <name>          select a skin in the real app (passthrough to Main.kt).
#   -Showcase             open the skin showcase chat page.
#   -Approval             add the approval sheet on top of the showcase.
#   -Dark                 use the dark token set (passthrough --dark).
#   -Open <id>            open a session detail by id.
#   -Chat <preset>        seed the detail's derived state (implies -Open).
#   -Gallery              capture the IconGallery acceptance page instead.
#   -GalleryLight         IconGallery on the light canvas.
#   -WaitSec <n>          max seconds to wait for the app window (default 45).
#   -SettleMs <n>         ms to let the window render before capture (default 2600).
#   -DryRun               launch + window match + diagnostics, capture nothing.
#   -CleanupOnly          run only the previous-run leftover cleanup, then exit.
#   -DarkCheckOnly <png>  diagnostics only: compare an existing dark PNG with its
#                          sibling light PNG and exit (no launch, no capture).
#
# EXIT CODES
#   0  success (also: DryRun finished / CleanupOnly finished / DarkCheckOnly ran)
#   2  NO_WINDOW           no app window matched within -WaitSec
#   3  NO_CLASSPATH        -ClassPathFile missing or empty
#   4  PROCESS_EXITED      the launched app died before showing a window
#   5  DARKCHECK_NO_INPUT  -DarkCheckOnly file not found
#   6  TRANSPARENT_CAPTURE PrintWindow returned a fully transparent bitmap
#
# PREREQUISITES (from the repo root)
#   gradle :composeApp:desktopMainClasses :composeApp:printRuntimeClasspath -q > cp.txt
#
# USAGE
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/capture-window.ps1 `
#     -Java "C:/Program Files/Java/jdk-21.0.12/bin/java.exe" -ClassPathFile cp.txt `
#     -Bar fixed -OutFile shots/c-capture/desktop-fixed.png
#   (any modern PowerShell works; the file is pure ASCII on purpose -- PowerShell
#    5.1 mis-decodes non-BOM UTF-8 scripts, so never add non-ASCII text here)
param(
  [Parameter(Mandatory=$true)][string]$Java,
  [Parameter(Mandatory=$true)][string]$ClassPathFile,
  [Parameter(Mandatory=$true)][string]$Bar,
  [Parameter(Mandatory=$true)][string]$OutFile,
  [string]$Size = '480x900',
  [string]$Dest = 'sessions',
  [string]$Panel = 'closed',
  # U1-c: open the search overlay/palette on launch. `-Search 'AGP'` presets a query,
  # `-SearchEmpty` opens it with nothing typed. Both take the same state path as the
  # top-bar search button, so the capture is a real overlay, not a mock.
  [string]$Search = '',
  [switch]$SearchEmpty,
  # U2: skin selection + showcase page. `-Skin miuix` selects the Miuix skin in the
  # real app; `-Showcase` opens the skin's own chat page (message bubbles / tool
  # cards / input bar), and `-Approval` adds the approval sheet on top of it.
  # `-Dark` picks the dark token set.
  [string]$Skin = '',
  [switch]$Showcase,
  [switch]$Approval,
  [switch]$Dark,
  # U1-b: open a session's detail (`-Open s-rfc0004`) and seed its derived state
  # (`-Chat live|tools|queue|compose|preview|stopped`). Same code path as tapping a row.
  [string]$Open = '',
  [string]$Chat = '',
  # w2-icons: capture the IconGallery acceptance page instead of the app shell
  # (`-GalleryLight` flips it to the section-2 light canvas).
  [switch]$Gallery,
  [switch]$GalleryLight,
  [int]$WaitSec = 45,
  [int]$SettleMs = 2600,
  # Concurrency-safe operations (this file never stops processes by name):
  # -DryRun        launch + window match + diagnostics, then stop our pid; no capture.
  # -CleanupOnly   run only the previous-run leftover cleanup and exit (no launch).
  # -DarkCheckOnly compare an existing dark PNG with its sibling light PNG and exit.
  [switch]$DryRun,
  [switch]$CleanupOnly,
  [string]$DarkCheckOnly = ''
)

$runStart = Get-Date

Add-Type -AssemblyName System.Drawing
Add-Type -TypeDefinition @"
using System;
using System.Text;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public struct WRECT11 { public int Left; public int Top; public int Right; public int Bottom; }
public class WCap11 {
  public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr lParam);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr hWnd, StringBuilder s, int n);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetClassNameW(IntPtr hWnd, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out WRECT11 r);
  [DllImport("dwmapi.dll")] public static extern int DwmGetWindowAttribute(IntPtr hWnd, int attr, out WRECT11 r, int size);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hWnd, IntPtr hdc, uint flags);
  [DllImport("user32.dll")] static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
  public static void MakeTopMost(IntPtr h) { SetWindowPos(h, (IntPtr)(-1), 0, 0, 0, 0, 0x0001 | 0x0002 | 0x0040); }
  public static void DropTopMost(IntPtr h) { SetWindowPos(h, (IntPtr)(-2), 0, 0, 0, 0, 0x0001 | 0x0002); }
  // Exact last-resort helper: top-level visible windows owned by ONE process id.
  // Only ever called with the pid of the process THIS run launched.
  public static List<IntPtr> WindowsOfOwnPid(uint pid) {
    var list = new List<IntPtr>();
    EnumWindows(delegate(IntPtr h, IntPtr l) {
      uint p; GetWindowThreadProcessId(h, out p);
      if (p == pid && IsWindowVisible(h)) list.Add(h);
      return true;
    }, IntPtr.Zero);
    return list;
  }
  public static string Title(IntPtr h) { var sb = new StringBuilder(512); GetWindowTextW(h, sb, 512); return sb.ToString(); }
  public static string Cls(IntPtr h) { var sb = new StringBuilder(512); GetClassNameW(h, sb, 512); return sb.ToString(); }
}
"@
$DWMWA_EXTENDED_FRAME_BOUNDS = 9

$repoRoot = if ($PSScriptRoot) { Split-Path -Parent $PSScriptRoot } else { (Get-Location).Path }
$PidFile = Join-Path $repoRoot 'shots\.capture-pid'
$PidTickTolerance = 50000000   # 5 seconds, in .NET ticks (100ns each)

function Read-CapturePidRecord {
  # Returns $null when the record file does not exist. Otherwise a hashtable with
  # Pid (long), Ticks (long), Corrupt (bool) and Raw (trimmed file content).
  # Anything not matching "<pid>|<startTimeTicks>" with both parts positive is
  # flagged Corrupt.
  if (-not (Test-Path -LiteralPath $PidFile)) { return $null }
  $raw = Get-Content -Raw -LiteralPath $PidFile -ErrorAction SilentlyContinue
  if ($null -eq $raw) { return @{ Pid = [long]0; Ticks = [long]0; Corrupt = $true; Raw = '' } }
  $raw = $raw.Trim()
  if (-not $raw) { return @{ Pid = [long]0; Ticks = [long]0; Corrupt = $true; Raw = '' } }
  $rec = @{ Pid = [long]0; Ticks = [long]0; Corrupt = $false; Raw = $raw }
  $parts = $raw -split '\|'
  if ($parts.Count -lt 2) { $rec.Corrupt = $true; return $rec }
  $v = [long]0
  if (-not [long]::TryParse($parts[0], [ref]$v) -or $v -le 0) { $rec.Corrupt = $true; return $rec }
  $rec.Pid = $v
  $t = [long]0
  if (-not [long]::TryParse($parts[1], [ref]$t) -or $t -le 0) { $rec.Corrupt = $true; return $rec }
  $rec.Ticks = $t
  return $rec
}

function Write-CapturePidRecord {
  param([long]$ProcId, [long]$StartTicks)
  $dir = Split-Path -Parent $PidFile
  if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
  Set-Content -LiteralPath $PidFile -Value ("{0}|{1}" -f $ProcId, $StartTicks) -Encoding Ascii
}

function Remove-CapturePidRecord {
  # Deletes the record file ONLY if it still holds exactly $Record, so a capture
  # running in another shell cannot have its own record deleted by us.
  param([string]$Record)
  if (-not (Test-Path -LiteralPath $PidFile)) { return }
  $raw = Get-Content -Raw -LiteralPath $PidFile -ErrorAction SilentlyContinue
  if ($null -ne $raw -and $raw.Trim() -eq $Record) {
    Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
  }
}

function Invoke-CaptureLeftoverCleanup {
  # Kills AT MOST ONE process: the leftover recorded by a PREVIOUS run of this
  # script, and only after full validation. Never enumerates processes by name;
  # never touches any pid that is not named in the record file.
  $rec = Read-CapturePidRecord
  if ($null -eq $rec) {
    Write-Output "cleanup: no pid record at $PidFile; nothing to clean"
    return
  }
  if ($rec.Corrupt) {
    Write-Output 'cleanup: pid record corrupt (expected "pid|startTimeTicks"); removing it; nothing killed'
    Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
    return
  }
  $proc = Get-Process -Id $rec.Pid -ErrorAction SilentlyContinue
  if ($null -eq $proc) {
    Write-Output "cleanup: recorded leftover pid=$($rec.Pid) is not running (already exited); nothing killed"
    Remove-CapturePidRecord -Record $rec.Raw
    return
  }
  $startTime = $null
  try { $startTime = $proc.StartTime } catch { }
  if ($null -eq $startTime) {
    Write-Output "cleanup: pid=$($rec.Pid) exited while being validated; nothing killed"
    Remove-CapturePidRecord -Record $rec.Raw
    return
  }
  if ($proc.ProcessName -ne 'java') {
    Write-Output "cleanup: pid=$($rec.Pid) is now '$($proc.ProcessName)', not java (pid was recycled by another process); refusing to kill"
    Remove-CapturePidRecord -Record $rec.Raw
    return
  }
  $tickDiff = [Math]::Abs($startTime.Ticks - $rec.Ticks)
  if ($tickDiff -gt $PidTickTolerance) {
    Write-Output ("cleanup: pid=$($rec.Pid) is java but started {0}, which does not match the recorded run (diff {1} ticks); refusing to kill (pid was probably recycled)" -f $startTime.ToString('o'), $tickDiff)
    Remove-CapturePidRecord -Record $rec.Raw
    return
  }
  if ($startTime -ge $runStart) {
    Write-Output "cleanup: pid=$($rec.Pid) is java and matches the record but did not start before this run; refusing to kill"
    return
  }
  Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
  Write-Output ("cleanup: killed leftover pid={0} (java, started {1}, recorded by a previous run of this script)" -f $proc.Id, $startTime.ToString('o'))
  Remove-CapturePidRecord -Record $rec.Raw
}

function Stop-OwnCaptureProcess {
  # Stops ONLY the process this run launched (exact pid) and clears our pid record
  # once the process is confirmed gone. If it refuses to die, the record is kept
  # so the next run's validated cleanup can retry.
  param([long]$ProcId, [string]$Record)
  Stop-Process -Id $ProcId -Force -ErrorAction SilentlyContinue
  $proc = Get-Process -Id $ProcId -ErrorAction SilentlyContinue
  if ($null -eq $proc) {
    Remove-CapturePidRecord -Record $Record
    Write-Output "own pid=$ProcId stopped (confirmed exited); pid record removed"
    return
  }
  try { $proc.WaitForExit(5000) | Out-Null } catch { }
  if ($null -eq (Get-Process -Id $ProcId -ErrorAction SilentlyContinue)) {
    Remove-CapturePidRecord -Record $Record
    Write-Output "own pid=$ProcId stopped (confirmed exited after wait); pid record removed"
  } else {
    Write-Output "WARN: own pid=$ProcId still running 5s after stop request; pid record kept for the next run's validated cleanup"
  }
}

function Invoke-DarkParityCheck {
  # -Dark effectiveness check: compare the dark capture with its sibling light PNG
  # (filename with the 'dark' token replaced by 'light'). Warn when both are
  # byte-identical or share the same byte count -- MaterialSkin.kt ignores the
  # dark flag, which makes light and dark captures exactly equal.
  param([string]$OutPath)
  $refPath = $OutPath -replace 'dark', 'light'
  if ($refPath -eq $OutPath) {
    Write-Output "DARK_CHECK: filename contains no 'dark' token; parity check skipped"
    return
  }
  if (-not (Test-Path -LiteralPath $refPath)) {
    Write-Output "DARK_CHECK: light counterpart not found ($refPath); parity check skipped"
    return
  }
  $outBytes = (Get-Item -LiteralPath $OutPath).Length
  $refBytes = (Get-Item -LiteralPath $refPath).Length
  $outMd5 = (Get-FileHash -LiteralPath $OutPath -Algorithm MD5).Hash
  $refMd5 = (Get-FileHash -LiteralPath $refPath -Algorithm MD5).Hash
  Write-Output "DARK_CHECK: dark=$OutPath bytes=$outBytes md5=$outMd5"
  Write-Output "DARK_CHECK: light=$refPath bytes=$refBytes md5=$refMd5"
  if ($outMd5 -ieq $refMd5) {
    Write-Output 'DARK_WARNING: -Dark capture is byte-identical to the light counterpart (same md5) -> -Dark may not have taken effect (skin may not implement dark theme)'
  } elseif ($outBytes -eq $refBytes) {
    Write-output 'DARK_WARNING: -Dark capture has the same byte count as the light counterpart but a different md5 -> -Dark may not have taken effect; inspect the image'
  } else {
    Write-Output 'DARK_CHECK: dark differs from light (bytes and md5 both differ) -> dark theme probably rendered'
  }
}

# ---------------- main ----------------

# Diagnostics-only modes first: zero side effects (no cleanup, no launch, no record).
if ($DarkCheckOnly -ne '') {
  if (-not (Test-Path -LiteralPath $DarkCheckOnly)) {
    Write-Output "DARKCHECK_NO_INPUT: '$DarkCheckOnly' not found"
    exit 5
  }
  Invoke-DarkParityCheck -OutPath $DarkCheckOnly
  exit 0
}

Invoke-CaptureLeftoverCleanup
if ($CleanupOnly) {
  Write-Output 'cleanup-only: done (no launch, no capture, no pid record written)'
  exit 0
}

$cp = Get-Content -Raw -LiteralPath $ClassPathFile -ErrorAction SilentlyContinue
if (-not $cp -or -not $cp.Trim()) { Write-Output 'NO_CLASSPATH'; exit 3 }
$cp = $cp.Trim()

$arguments = @(
  '-Djava.awt.headless=false',
  '-cp', $cp,
  'vade.ui.MainKt',
  "--bar=$Bar",
  "--size=$Size",
  "--dest=$Dest",
  "--panel=$Panel"
)
if ($SearchEmpty) { $arguments += '--search' }
elseif ($PSBoundParameters.ContainsKey('Search') -and $Search -ne '') { $arguments += "--search=$Search" }
# U2: pass the skin / showcase / approval / dark switches straight through to Main.kt.
if ($PSBoundParameters.ContainsKey('Skin') -and $Skin -ne '') { $arguments += "--skin=$Skin" }
if ($Showcase) { $arguments += '--showcase' }
if ($Approval) { $arguments += '--approval' }
if ($Dark) { $arguments += '--dark' }
# U1-b: the detail screen. `-Chat` implies `-Open` when only the preset is given.
if ($Open -ne '') { $arguments += "--open=$Open" }
if ($Chat -ne '') { $arguments += "--chat=$Chat" }
# w2-icons: gallery page passthrough.
if ($Gallery) { $arguments += '--gallery' }
if ($GalleryLight) { $arguments += '--light' }

$p = Start-Process -FilePath $Java -ArgumentList $arguments -PassThru -WindowStyle Hidden
$ownTicks = [long]0
for ($i = 0; $i -lt 3; $i++) {
  try { $ownTicks = $p.StartTime.Ticks; break } catch { Start-Sleep -Milliseconds 250 }
}
Write-CapturePidRecord -ProcId $p.Id -StartTicks $ownTicks
$OwnRecord = "{0}|{1}" -f $p.Id, $ownTicks
Write-Output "launched pid=$($p.Id) bar=$Bar size=$Size dest=$Dest panel=$Panel skin=$Skin dark=$([bool]$Dark) dryrun=$([bool]$DryRun)"
Write-Output "pid-record: $PidFile = '$OwnRecord' (this run will only ever stop pid $($p.Id))"
if ($ownTicks -eq [long]0) { Write-Output 'WARN: could not read own StartTime; pid record has ticks=0 and will be treated as corrupt by the next run' }

# Window search, phase 1: the process's own main window handle. Precise by
# construction (a window of this exact process), then validated by class+size.
$main = [IntPtr]::Zero
$matchedBy = ''
$lastSeen = 'nothing seen yet'
$deadline = (Get-Date).AddSeconds($WaitSec)
while ((Get-Date) -lt $deadline) {
  Start-Sleep -Milliseconds 400
  if ($p.HasExited) {
    Write-Output 'PROCESS_EXITED'
    Remove-CapturePidRecord -Record $OwnRecord
    exit 4
  }
  $p.Refresh()
  $h = $p.MainWindowHandle
  if ($h -ne [IntPtr]::Zero) {
    $cls = [WCap11]::Cls($h)
    $r = New-Object WRECT11
    [void][WCap11]::GetWindowRect($h, [ref]$r)
    $w = $r.Right - $r.Left
    $ht = $r.Bottom - $r.Top
    $lastSeen = "hwnd=$h class=$cls title='$([WCap11]::Title($h))' rect=${w}x${ht}"
    if ($cls.StartsWith('SunAwt') -and $w -gt 300 -and $ht -gt 300) {
      $main = $h
      $matchedBy = 'MainWindowHandle'
      break
    }
  }
}
if ($main -eq [IntPtr]::Zero) {
  # Phase 2, exact last resort: MainWindowHandle never resolved within the wait.
  # Enumerate windows owned by THIS run's pid only (the process we just launched)
  # with the same class+size validation. No window of any other process can ever
  # match here, so a stale window from an earlier round cannot be picked either.
  foreach ($h in [WCap11]::WindowsOfOwnPid([uint32]$p.Id)) {
    $cls = [WCap11]::Cls($h)
    $r = New-Object WRECT11
    [void][WCap11]::GetWindowRect($h, [ref]$r)
    $w = $r.Right - $r.Left
    $ht = $r.Bottom - $r.Top
    if ($cls.StartsWith('SunAwt') -and $w -gt 300 -and $ht -gt 300) {
      $main = $h
      $matchedBy = 'own-pid-enum (MainWindowHandle stayed 0)'
      break
    }
  }
}
if ($main -eq [IntPtr]::Zero) {
  Write-Output "NO_WINDOW (waited ${WaitSec}s; no SunAwt window >300x300 belongs to pid $($p.Id); last seen: $lastSeen)"
  Stop-OwnCaptureProcess -ProcId $p.Id -Record $OwnRecord
  exit 2
}
Write-Output "window matched by=$matchedBy hwnd=$main class=$([WCap11]::Cls($main)) title='$([WCap11]::Title($main))'"

if ($DryRun) {
  Write-Output 'dryrun: window verified; capture skipped (no PNG written)'
  Stop-OwnCaptureProcess -ProcId $p.Id -Record $OwnRecord
  exit 0
}

[WCap11]::DropTopMost($main)
[WCap11]::MakeTopMost($main)
Start-Sleep -Milliseconds $SettleMs

$vis = New-Object WRECT11
$hr = [WCap11]::DwmGetWindowAttribute($main, $DWMWA_EXTENDED_FRAME_BOUNDS, [ref]$vis, 16)
if ($hr -ne 0 -or ($vis.Right - $vis.Left) -lt 60) {
  $vis = New-Object WRECT11
  [void][WCap11]::GetWindowRect($main, [ref]$vis)
}
$w = $vis.Right - $vis.Left; $h2 = $vis.Bottom - $vis.Top
Write-Output "window rect=$($vis.Left),$($vis.Top) ${w}x${h2}"

$bmp = New-Object System.Drawing.Bitmap($w, $h2)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$hdc = $g.GetHdc()
$ok = [WCap11]::PrintWindow($main, $hdc, 2)   # 2 = PW_RENDERFULLCONTENT
$g.ReleaseHdc($hdc)
if (-not $ok) { Write-Output 'PRINTWINDOW_FAILED' }
$center = $bmp.GetPixel([int]($w / 2), [int]($h2 / 2))
Write-Output "PrintWindow=$ok center=$center"
if ($center.A -eq 0) {
  Write-Output 'TRANSPARENT_CAPTURE'
  $g.Dispose(); $bmp.Dispose()
  Stop-OwnCaptureProcess -ProcId $p.Id -Record $OwnRecord
  exit 6
}

$dir = Split-Path -Parent $OutFile
if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
$bmp.Save($OutFile, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()
Write-Output "saved $OutFile ${w}x${h2} $((Get-Item -LiteralPath $OutFile).Length) bytes md5=$((Get-FileHash -LiteralPath $OutFile -Algorithm MD5).Hash)"

if ($Dark) { Invoke-DarkParityCheck -OutPath $OutFile }

[WCap11]::DropTopMost($main)
Stop-OwnCaptureProcess -ProcId $p.Id -Record $OwnRecord
