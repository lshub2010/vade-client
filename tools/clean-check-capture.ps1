# clean-check-capture.ps1 -- pid-scoped real-window capture of the packaged
# vade-ui.exe (app-image). Never kills ANY process: the caller stops the app
# separately. Window search is restricted to processes whose image name is
# vade-ui.exe AND, when -ProcId is given, to that exact pid.
#
# Capture technique mirrors tools/capture-window.ps1 (PrintWindow with
# PW_RENDERFULLCONTENT), because this session has no capturable desktop.
param(
  [string]$ProcId = '',
  [Parameter(Mandatory=$true)][string]$OutFile
)

Add-Type -AssemblyName System.Drawing
Add-Type -TypeDefinition @"
using System;
using System.Text;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public struct WRECTcc { public int Left; public int Top; public int Right; public int Bottom; }
public class WCapcc {
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetClassNameW(IntPtr hWnd, StringBuilder s, int n);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr hWnd, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out WRECTcc r);
  [DllImport("dwmapi.dll")] public static extern int DwmGetWindowAttribute(IntPtr hWnd, int attr, out WRECTcc r, int size);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hWnd, IntPtr hdc, uint flags);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
  public static List<IntPtr> WindowsOfPid(uint pid) {
    var list = new List<IntPtr>();
    EnumWindows(delegate(IntPtr h, IntPtr l) {
      uint p; GetWindowThreadProcessId(h, out p);
      if (p == pid && IsWindowVisible(h)) list.Add(h);
      return true;
    }, IntPtr.Zero);
    return list;
  }
  public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr lParam);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
  public static string Title(IntPtr h) { var sb = new StringBuilder(512); GetWindowTextW(h, sb, 512); return sb.ToString(); }
  public static string Cls(IntPtr h) { var sb = new StringBuilder(512); GetClassNameW(h, sb, 512); return sb.ToString(); }
}
"@
$DWMWA_EXTENDED_FRAME_BOUNDS = 9

# candidate pids: either the given one, or every running vade-ui.exe (read-only)
if ($ProcId -ne '') {
  $pids = @([uint32]$ProcId)
} else {
  $pids = @(Get-Process vade-ui -ErrorAction SilentlyContinue | ForEach-Object { [uint32]$_.Id })
  Write-Output "no -ProcId given; scanning windows of all vade-ui.exe pids: $($pids -join ',')"
}

$main = [IntPtr]::Zero
$found = $false
foreach ($p in $pids) {
  foreach ($h in [WCapcc]::WindowsOfPid($p)) {
    $cls = [WCapcc]::Cls($h)
    $r = New-Object WRECTcc
    [void][WCapcc]::GetWindowRect($h, [ref]$r)
    $w = $r.Right - $r.Left; $ht = $r.Bottom - $r.Top
    $title = [WCapcc]::Title($h)
    Write-Output "pid=$p hwnd=$h class=$cls title='$title' rect=${w}x${ht}"
    if ($cls.StartsWith('SunAwt') -and $w -gt 300 -and $ht -gt 300) { $main = $h; $found = $true; break }
  }
  if ($found) { break }
}

if (-not $found) {
  Write-Output "NO_WINDOW: no SunAwt window >300x300 among vade-ui.exe processes (pids: $($pids -join ','))"
  exit 2
}

Write-Output "matched hwnd=$main class=$([WCapcc]::Cls($main)) title='$([WCapcc]::Title($main))'"
$vis = New-Object WRECTcc
$hr = [WCapcc]::DwmGetWindowAttribute($main, $DWMWA_EXTENDED_FRAME_BOUNDS, [ref]$vis, 16)
if ($hr -ne 0 -or (($vis.Right - $vis.Left) -lt 60)) {
  $vis = New-Object WRECTcc
  [void][WCapcc]::GetWindowRect($main, [ref]$vis)
}
$w = $vis.Right - $vis.Left; $ht = $vis.Bottom - $vis.Top
Write-Output "capture rect=$($vis.Left),$($vis.Top) ${w}x${ht}"

$bmp = New-Object System.Drawing.Bitmap($w, $ht)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$hdc = $g.GetHdc()
$ok = [WCapcc]::PrintWindow($main, $hdc, 2)
$g.ReleaseHdc($hdc)
Write-Output "PrintWindow=$ok"
$center = $bmp.GetPixel([int]($w/2), [int]($ht/2))
Write-Output "center pixel=$center"
if ($center.A -eq 0) { Write-Output 'TRANSPARENT_CAPTURE'; $g.Dispose(); $bmp.Dispose(); exit 6 }

$dir = Split-Path -Parent $OutFile
if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
$bmp.Save($OutFile, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()
Write-Output "saved $OutFile ${w}x${ht} $((Get-Item -LiteralPath $OutFile).Length) bytes md5=$((Get-FileHash -LiteralPath $OutFile -Algorithm MD5).Hash)"
exit 0
