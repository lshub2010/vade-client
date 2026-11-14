# Real-window capture for the Vade UI skeleton (Windows only).
#
# Same idea as spike/miuix/tools/capture-window.ps1: launch the Desktop app from the
# compiled classpath, find its Skiko window (SunAwtFrame), crop by the DWM extended frame
# bounds, save a PNG. One deliberate difference: the pixels come from **PrintWindow with
# PW_RENDERFULLCONTENT (flag 2)**, not CopyFromScreen. On this host the agent session has
# no capturable desktop (`CopyFromScreen` throws "invalid parameter" and yields a fully
# transparent bitmap), while PrintWindow still asks the window to render itself into our DC.
#
# Sizing note: the DWM frame is ~14x7 px smaller than the requested window client size, so
# `-Size 480x900` produces a 466x893 PNG. That is why the default here is 480x900.
#
# Prerequisites (from ui/):
#   gradle :composeApp:desktopMainClasses :composeApp:printRuntimeClasspath -q > cp.txt
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/capture-window.ps1 `
#     -Java "C:/Program Files/Java/jdk-21.0.12/bin/java.exe" -ClassPathFile cp.txt `
#     -Bar fixed -OutFile shots/u1a/desktop-fixed.png
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
  # top-bar 搜索 button, so the capture is a real overlay, not a mock.
  [string]$Search = '',
  [switch]$SearchEmpty,
  # U1-b: open a session's detail (`-Open s-rfc0004`) and seed its derived state
  # (`-Chat live|tools|queue|compose|preview|stopped`). Same code path as tapping a row.
  [string]$Open = '',
  [string]$Chat = '',
  [int]$WaitSec = 45,
  [int]$SettleMs = 2600
)

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
  public static List<IntPtr> WindowsOf(uint pid) {
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

$cp = (Get-Content -Raw -LiteralPath $ClassPathFile).Trim()
if (-not $cp) { Write-Output 'NO_CLASSPATH'; exit 3 }

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
# U1-b: the detail screen. `-Chat` implies `-Open` when only the preset is given.
if ($Open -ne '') { $arguments += "--open=$Open" }
if ($Chat -ne '') { $arguments += "--chat=$Chat" }
$p = Start-Process -FilePath $Java -ArgumentList $arguments -PassThru -WindowStyle Hidden
Write-Output "launched pid=$($p.Id) bar=$Bar size=$Size dest=$Dest"

$main = [IntPtr]::Zero
$deadline = (Get-Date).AddSeconds($WaitSec)
while ((Get-Date) -lt $deadline) {
  Start-Sleep -Milliseconds 400
  if ($p.HasExited) { Write-Output 'PROCESS_EXITED'; exit 4 }
  foreach ($h in [WCap11]::WindowsOf([uint32]$p.Id)) {
    if (-not ([WCap11]::Cls($h)).StartsWith('SunAwt')) { continue }
    $r = New-Object WRECT11; [void][WCap11]::GetWindowRect($h, [ref]$r)
    if (($r.Right - $r.Left) -gt 300 -and ($r.Bottom - $r.Top) -gt 300) { $main = $h; break }
  }
  if ($main -ne [IntPtr]::Zero) { break }
}
if ($main -eq [IntPtr]::Zero) {
  Write-Output 'NO_WINDOW'
  Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
  exit 2
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
if ($center.A -eq 0) { Write-Output 'TRANSPARENT_CAPTURE'; $g.Dispose(); $bmp.Dispose(); Stop-Process -Id $p.Id -Force; exit 6 }

$dir = Split-Path -Parent $OutFile
if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
$bmp.Save($OutFile, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()
Write-Output "saved $OutFile ${w}x${h2} $((Get-Item $OutFile).Length) bytes"

[WCap11]::DropTopMost($main)
Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
Write-Output "killed pid=$($p.Id)"
