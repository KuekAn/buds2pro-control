using System.Text.Json;
using Microsoft.Win32;

namespace BudsControl;

/// <summary>Small settings file in %LOCALAPPDATA%\BudsControl.</summary>
public sealed class Settings
{
    public ulong Address { get; set; }
    /// <summary>Off/on cycles per re-seat: 1 (default) or 2.</summary>
    public int ReseatCycles { get; set; } = 1;

    public static readonly string Dir =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "BudsControl");
    static string FilePath => Path.Combine(Dir, "settings.json");

    public static Settings Current { get; private set; } = Load();

    static Settings Load()
    {
        try { return JsonSerializer.Deserialize<Settings>(File.ReadAllText(FilePath)) ?? new(); }
        catch { return new(); }
    }

    public static void Save()
    {
        try { Directory.CreateDirectory(Dir); File.WriteAllText(FilePath, JsonSerializer.Serialize(Current)); }
        catch { }
    }

    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    const string RunName = "BudsControl";

    /// <summary>Per-user autostart via the Run key; only changed when the user ticks the menu item.</summary>
    public static bool StartWithWindows
    {
        get
        {
            using var k = Registry.CurrentUser.OpenSubKey(RunKey);
            return k?.GetValue(RunName) is string;
        }
        set
        {
            using var k = Registry.CurrentUser.OpenSubKey(RunKey, writable: true);
            if (k == null) return;
            if (value) k.SetValue(RunName, $"\"{Environment.ProcessPath}\" --tray");
            else k.DeleteValue(RunName, throwOnMissingValue: false);
        }
    }
}

/// <summary>Link log, mirrored to %LOCALAPPDATA%\BudsControl\link.log (kept under ~256 KB).</summary>
public static class Log
{
    static readonly object gate = new();
    public static string FilePath => Path.Combine(Settings.Dir, "link.log");

    public static void Write(string msg)
    {
        lock (gate)
        {
            try
            {
                Directory.CreateDirectory(Settings.Dir);
                var f = new FileInfo(FilePath);
                if (f.Exists && f.Length > 256 * 1024) f.Delete();
                File.AppendAllText(FilePath, $"{DateTime.Now:HH:mm:ss} {msg}{Environment.NewLine}");
            }
            catch { }
        }
    }
}

/// <summary>Light or dark, following Windows' app theme.</summary>
public sealed record Theme(Color Bg, Color Card, Color Text, Color Dim, Color Accent, Color OnAccent,
    Color Single, Color OnSingle, bool Dark)
{
    public static Theme Current()
    {
        bool light = true;
        try
        {
            using var k = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
            light = k?.GetValue("AppsUseLightTheme") is int v ? v != 0 : true;
        }
        catch { }
        return light
            ? new Theme(C("#F2F4F7"), C("#E2E6EC"), C("#1A1C20"), C("#7A808A"), C("#3D6DF2"), Color.White,
                C("#8CC7BE"), C("#10302B"), false)
            : new Theme(C("#202226"), C("#2E3137"), C("#ECEEF2"), C("#8A909A"), C("#6F93FF"), C("#0B1020"),
                C("#6FB3A8"), C("#0C2420"), true);
    }

    /// <summary>Whether the taskbar (where the tray icon sits) is light.</summary>
    public static bool TaskbarLight()
    {
        try
        {
            using var k = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
            return k?.GetValue("SystemUsesLightTheme") is int v && v != 0;
        }
        catch { return false; }
    }

    static Color C(string hex) => ColorTranslator.FromHtml(hex);
}
