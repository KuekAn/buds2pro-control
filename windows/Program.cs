namespace BudsControl;

static class Program
{
    const string ShowEvent = @"Local\BudsControl.Windows.Show";

    /// <summary>
    /// Starts the tray app. "--tray" (used by autostart) starts without opening the panel.
    /// Launching it again while it runs just opens the running copy's panel.
    /// </summary>
    [STAThread]
    static void Main(string[] args)
    {
        using var single = new Mutex(true, @"Local\BudsControl.Windows", out bool first);
        using var show = new EventWaitHandle(false, EventResetMode.AutoReset, ShowEvent);
        if (!first) { show.Set(); return; }

        ApplicationConfiguration.Initialize();
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        Application.ThreadException += (_, e) => Log.Write($"UI error: {e.Exception}");
        AppDomain.CurrentDomain.UnhandledException += (_, e) => Log.Write($"fatal: {e.ExceptionObject}");

        var app = new TrayApp(showPanel: !args.Contains("--tray"));
        new Thread(() => { while (show.WaitOne()) app.ShowPanelFromAnyThread(); }) { IsBackground = true }.Start();
        Application.Run(app);
    }
}
