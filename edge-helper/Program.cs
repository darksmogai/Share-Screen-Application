using System.Runtime.InteropServices;
using Microsoft.Web.WebView2.WinForms;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new ProtectedBrowserForm());
    }
}

internal sealed class ProtectedBrowserForm : Form
{
    private const uint WdaNone = 0x00000000;
    private const uint WdaExcludeFromCapture = 0x00000011;
    private readonly TextBox address = new() { Dock = DockStyle.Fill, Text = "https://chatgpt.com" };
    private readonly Button go = new() { Text = "Go", AutoSize = true };
    private readonly Button protect = new() { Text = "Remove protection", AutoSize = true };
    private readonly Label status = new() { AutoSize = true, Text = "Starting Edge…", Padding = new Padding(8, 6, 8, 0) };
    private readonly WebView2 webView = new() { Dock = DockStyle.Fill };
    private bool protectedNow;

    public ProtectedBrowserForm()
    {
        Text = "ScreenGuard Protected Edge Browser";
        Width = 1200;
        Height = 800;
        StartPosition = FormStartPosition.CenterScreen;
        ShowInTaskbar = false;
        go.Click += (_, _) => Navigate();
        address.KeyDown += (_, e) => { if (e.KeyCode == Keys.Enter) Navigate(); };
        protect.Click += (_, _) => SetProtection(!protectedNow);

        var toolbar = new TableLayoutPanel { Dock = DockStyle.Top, Height = 42, ColumnCount = 4, Padding = new Padding(6) };
        toolbar.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        toolbar.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        toolbar.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        toolbar.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        toolbar.Controls.Add(address, 0, 0);
        toolbar.Controls.Add(go, 1, 0);
        toolbar.Controls.Add(protect, 2, 0);
        toolbar.Controls.Add(status, 3, 0);
        Controls.Add(webView);
        Controls.Add(toolbar);
        Shown += async (_, _) => await InitializeBrowserAsync();
    }

    private async Task InitializeBrowserAsync()
    {
        try
        {
            await webView.EnsureCoreWebView2Async();
            webView.CoreWebView2.NavigationCompleted += (_, e) => status.Text = e.IsSuccess ? "Edge ready" : "Navigation failed";
            SetProtection(true);
            Navigate();
            status.Text = "Protected from capture";
        }
        catch (Exception ex)
        {
            status.Text = "Edge WebView2 unavailable";
            MessageBox.Show(this, ex.Message, "ScreenGuard", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private void Navigate()
    {
        if (webView.CoreWebView2 is null) return;
        var value = address.Text.Trim();
        if (value.Length == 0) return;
        if (!value.Contains("://", StringComparison.Ordinal)) value = "https://" + value;
        address.Text = value;
        webView.CoreWebView2.Navigate(value);
    }

    private void SetProtection(bool enable)
    {
        if (SetWindowDisplayAffinity(Handle, enable ? WdaExcludeFromCapture : WdaNone))
        {
            protectedNow = enable;
            protect.Text = enable ? "Remove protection" : "Protect window";
            status.Text = enable ? "Protected from capture" : "Edge ready - not protected";
        }
        else status.Text = "Windows could not change protection";
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SetWindowDisplayAffinity(IntPtr hWnd, uint affinity);
}
