$ErrorActionPreference = 'Stop'

Write-Output "OS: $([System.Environment]::OSVersion.VersionString) 64bit=$([System.Environment]::Is64BitOperatingSystem)"
try {
    $ux = Get-Service -Name UxSms -ErrorAction Stop
    Write-Output "UxSms (DWM session manager): $($ux.Status)"
} catch { Write-Output "UxSms: not available" }
try {
    $dwm = Get-Process -Name dwm -ErrorAction Stop
    Write-Output "dwm.exe running: $($dwm.Count) instance(s)"
} catch { Write-Output "dwm.exe: not running" }

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public static class SgNative {
    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool SetWindowDisplayAffinity(IntPtr hWnd, uint dwAffinity);

    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool GetWindowDisplayAffinity(IntPtr hWnd, out uint pdwAffinity);

    [DllImport("kernel32.dll")]
    public static extern IntPtr GetConsoleWindow();

    [DllImport("user32.dll")]
    public static extern IntPtr GetDesktopWindow();

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    public static extern int GetClassName(IntPtr hWnd, System.Text.StringBuilder lpClassName, int nMaxCount);

    public static string ClassOf(IntPtr h) {
        var sb = new System.Text.StringBuilder(256);
        GetClassName(h, sb, sb.Capacity);
        return sb.ToString();
    }
}
'@

function Test-Affinity([string]$label, [IntPtr]$hwnd) {
    if ($hwnd -eq [IntPtr]::Zero) { Write-Output "$label : no hwnd"; return }
    $cls = [SgNative]::ClassOf($hwnd)
    foreach ($value in 0, 1, 0x11) {
        $ok = [SgNative]::SetWindowDisplayAffinity($hwnd, [uint32]$value)
        $err = [System.Runtime.InteropServices.Marshal]::GetLastWin32Error()
        $read = [uint32]0
        $rok = [SgNative]::GetWindowDisplayAffinity($hwnd, [ref]$read)
        Write-Output ("{0} class={1} SetWDA(0x{2:X})={3} err={4} GetWDA={5} value=0x{6:X}" -f $label, $cls, $value, $ok, $err, $rok, $read)
    }
    # verify the documented default: EXCLUDEFROMCAPTURE stays read back as 0x11
    $null = [SgNative]::SetWindowDisplayAffinity($hwnd, [uint32]0x11)
    $read = [uint32]0
    $rok = [SgNative]::GetWindowDisplayAffinity($hwnd, [ref]$read)
    Write-Output ("{0} after SetWDA(0x11): GetWDA={1} value=0x{2:X}" -f $label, $rok, $read)
    $null = [SgNative]::SetWindowDisplayAffinity($hwnd, [uint32]0)
}

# 1. Our own console window (top-level, owned by this process)
Test-Affinity 'console' ([SgNative]::GetConsoleWindow())

# 2. A brand-new top-level WinForms window owned by this process
Add-Type -AssemblyName System.Windows.Forms
$form = New-Object System.Windows.Forms.Form
$form.Text = 'AffinityProbeForm'
$form.Width = 300; $form.Height = 200
$form.Show()
[System.Windows.Forms.Application]::DoEvents()
Start-Sleep -Milliseconds 800
Test-Affinity 'winforms' $form.Handle
$form.Close()
$form.Dispose()

# 3. The desktop window (owned by explorer, i.e. a foreign process)
Test-Affinity 'desktop' ([SgNative]::GetDesktopWindow())

Write-Output "Privilege: $(([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator))"
