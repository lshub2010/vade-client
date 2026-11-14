# Real-window capture for the §11 motion-blur probe (Windows only).
#
# Same machinery as tools/capture-window.ps1 (PrintWindow + PW_RENDERFULLCONTENT, DWM frame
# crop) but for the `--motion-probe` window, which takes a different flag set. Kept as a
# separate script rather than adding eight parameters to the existing one, so the U1-a/U1-c
# screenshot loop keeps working untouched.
#
# Prerequisites (from the repo root):
#   gradle :composeApp:desktopMainClasses :composeApp:printRuntimeClasspath -q > cp.txt
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/capture-motion.ps1 `
#     -Java "C:/Program Files/Java/jdk-21.0.12/bin/java.exe" -ClassPathFile cp.txt `
#     -OutFile shots/motion/freeze-0.png -FreezeBlur 0
param(
  [Parameter(Mandatory=$true)][string]$Java,
  [Parameter(Mandatory=$true)][string]$ClassPathFile,
  [Parameter(Mandatory=$true)][string]$OutFile,
  # Empty = do not pass --freeze-blur (live velocity path). "0" is a real value and must be
  # distinguishable from "not given", so this is a string, not a float.
  [string]$FreezeBlur = '',
  [switch]$BlurOff,
  [switch]$AutoScroll,
  [string]$Size = '916x774',
  [int]$WaitSec = 45,
  # Long enough for the sawtooth driver to be mid-scroll when the pixels are grabbed.
  [int]$SettleMs = 2600
)

Add-Type -AssemblyName System.Drawing
Add-Type -TypeDefinition @"
using System;
using System.Text;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public struct WRECT12 { public int Left; public int Top; public int Right; public int Bottom; }
public class WCap12 {
  public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr lParam);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetClassNameW(IntPtr hWnd, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out WRECT12 r);
  [DllImport("dwmapi.dll")] public static extern int DwmGetWindowAttribute(IntPtr hWnd, int attr, out WRECT12 r, int size);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hWnd, IntPtr hdc, uint flags);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
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
  '--motion-probe',
  "--size=$Size"
)
if ($FreezeBlur -ne '') { $arguments += "--freeze-blur=$FreezeBlur" }
if ($BlurOff) { $arguments += '--blur=off' }
if ($AutoScroll) { $arguments += '--autoscroll' }
# NOT -WindowStyle Hidden: a hidden window gets no Compose frame clock, so `--autoscroll`
# never advances the list and the capture reads IDLE. The window is made topmost below anyway.
$p = Start-Process -FilePath $Java -ArgumentList $arguments -PassThru
Write-Output "launched pid=$($p.Id) freeze='$FreezeBlur' blurOff=$BlurOff autoScroll=$AutoScroll size=$Size"

$main = [IntPtr]::Zero
$deadline = (Get-Date).AddSeconds($WaitSec)
while ((Get-Date) -lt $deadline) {
  Start-Sleep -Milliseconds 400
  if ($p.HasExited) { Write-Output 'PROCESS_EXITED'; exit 4 }
  foreach ($h in [WCap12]::WindowsOf([uint32]$p.Id)) {
    if (-not ([WCap12]::Cls($h)).StartsWith('SunAwt')) { continue }
    $r = New-Object WRECT12; [void][WCap12]::GetWindowRect($h, [ref]$r)
    if (($r.Right - $r.Left) -gt 300 -and ($r.Bottom - $r.Top) -gt 300) { $main = $h; break }
  }
  if ($main -ne [IntPtr]::Zero) { break }
}
if ($main -eq [IntPtr]::Zero) {
  Write-Output 'NO_WINDOW'
  Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
  exit 2
}

[void][WCap12]::ShowWindow($main, 5)   # SW_SHOW: real frames, real scroll
[WCap12]::DropTopMost($main)
[WCap12]::MakeTopMost($main)
Start-Sleep -Milliseconds $SettleMs

$vis = New-Object WRECT12
$hr = [WCap12]::DwmGetWindowAttribute($main, $DWMWA_EXTENDED_FRAME_BOUNDS, [ref]$vis, 16)
if ($hr -ne 0 -or ($vis.Right - $vis.Left) -lt 60) {
  $vis = New-Object WRECT12
  [void][WCap12]::GetWindowRect($main, [ref]$vis)
}
$w = $vis.Right - $vis.Left; $h2 = $vis.Bottom - $vis.Top
Write-Output "window rect=$($vis.Left),$($vis.Top) ${w}x${h2}"

$bmp = New-Object System.Drawing.Bitmap($w, $h2)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$hdc = $g.GetHdc()
$ok = [WCap12]::PrintWindow($main, $hdc, 2)   # 2 = PW_RENDERFULLCONTENT
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

[WCap12]::DropTopMost($main)
Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
Write-Output "killed pid=$($p.Id)"
