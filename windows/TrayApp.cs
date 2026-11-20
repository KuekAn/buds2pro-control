using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;

namespace BudsControl;

/// <summary>
/// Lives in the notification area: icon shows the mode (solid = ANC, outline = Ambient,
/// faint = Off / not connected), left click opens the panel, right click the menu.
/// </summary>
public sealed class TrayApp : ApplicationContext
{
    readonly BudsLink link = new();
    readonly NotifyIcon icon = new();
    readonly PanelForm panel;
    readonly ContextMenuStrip menu = new();
    readonly ToolStripMenuItem mAnc = new("ANC"), mAmb = new("Ambient"), mOff = new("Off");
    readonly ToolStripMenuItem mReseat = new("Re-seat"), mConnect = new("Connect on this PC");
    readonly ToolStripMenuItem mAuto = new("Start with Windows");
    readonly ToolStripMenuItem mTwice = new("Re-seat with two cycles");
    readonly System.Windows.Forms.Timer poll = new() { Interval = 4000 };
    IntPtr lastHicon;

    public TrayApp(bool showPanel)
    {
        panel = new PanelForm(link);
        _ = panel.Handle; // create the handle so background events can be marshalled to the UI thread

        mAnc.Click += (_, _) => link.SetMode(Protocol.ModeAnc);
        mAmb.Click += (_, _) => link.SetMode(Protocol.ModeAmbient);
        mOff.Click += (_, _) => link.SetMode(Protocol.ModeOff);
        mReseat.Click += (_, _) => link.Reseat();
        mConnect.Click += (_, _) => { if (link.State.Paired) link.Connect(force: true); else PanelForm.OpenBluetoothSettings(); };
        mAuto.Click += (_, _) => { Settings.StartWithWindows = !mAuto.Checked; mAuto.Checked = Settings.StartWithWindows; };
        mTwice.Click += (_, _) =>
        {
            Settings.Current.ReseatCycles = mTwice.Checked ? 1 : 2;
            Settings.Save();
            mTwice.Checked = Settings.Current.ReseatCycles == 2;
        };
        var mLog = new ToolStripMenuItem("Open log folder", null, (_, _) => OpenFolder());
        var mExit = new ToolStripMenuItem("Exit", null, (_, _) => Exit());
        menu.Items.AddRange(new ToolStripItem[] {
            mAnc, mAmb, mOff, new ToolStripSeparator(), mReseat, mConnect, new ToolStripSeparator(), mTwice, mAuto, mLog, mExit,
        });
        menu.Opening += (_, _) =>
        {
            mAuto.Checked = Settings.StartWithWindows;
            mTwice.Checked = Settings.Current.ReseatCycles == 2;
            RenderMenu(link.State);
        };

        icon.ContextMenuStrip = menu;
        icon.MouseClick += (_, e) =>
        {
            if (e.Button != MouseButtons.Left) return;
            if (panel.Visible) panel.Hide(); else panel.ShowNear();
        };
        link.Changed += s => { if (panel.IsHandleCreated) panel.BeginInvoke(() => Render(s)); };
        Render(link.State);
        icon.Visible = true;

        poll.Tick += (_, _) => Task.Run(link.Poll);
        poll.Start();
        Task.Run(link.Poll);
        if (showPanel) panel.ShowNear();
    }

    /// <summary>Second launch of the exe: open the panel of this running copy.</summary>
    public void ShowPanelFromAnyThread() => panel.BeginInvoke(() => panel.ShowNear());

    void Render(BudsState s)
    {
        SetIcon(s);
        var tip = s.Connected
            ? $"{s.ShortName} · {s.ModeLabel} · L {BudsState.Batt(s.BattL, s.PlaceL)} R {BudsState.Batt(s.BattR, s.PlaceR)}" +
              (s.BattCase is >= 1 and <= 100 ? $" · Case {s.BattCase}%" : "")
            : $"{s.ShortName} · {s.Status}";
        icon.Text = tip.Length > 127 ? tip[..127] : tip;
        RenderMenu(s);
        if (panel.Visible) panel.Render(s);
    }

    void RenderMenu(BudsState s)
    {
        foreach (var (m, mode) in new[] { (mAnc, Protocol.ModeAnc), (mAmb, Protocol.ModeAmbient), (mOff, Protocol.ModeOff) })
        {
            m.Checked = s.Connected && s.Mode == mode;
            m.Enabled = s.Connected;
        }
        mReseat.Enabled = s.Connected && !s.Reseating;
        mConnect.Visible = !s.Connected;
        mConnect.Text = s.Paired ? "Connect on this PC" : "Pair in Bluetooth settings…";
    }

    /// <summary>Draws the buds glyph (same shape as the Android icon) for the tray.</summary>
    void SetIcon(BudsState s)
    {
        int size = SystemInformation.SmallIconSize.Width;
        using var bmp = new Bitmap(size, size);
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Color.Transparent);
            var baseColor = Theme.TaskbarLight() ? Color.FromArgb(26, 28, 32) : Color.White;
            bool active = s.Connected && s.Mode is Protocol.ModeAnc or Protocol.ModeAmbient;
            if (s.Connected && s.SingleBud && active) baseColor = Color.FromArgb(111, 179, 168);
            int alpha = !s.Connected ? 90 : s.Mode == Protocol.ModeOff ? 140 : 255;
            var c = Color.FromArgb(alpha, baseColor);
            float k = size / 24f;
            using var path = new GraphicsPath();
            foreach (float x in new[] { 3f, 13f })
            {
                path.AddPath(RoundButton.Rounded(Rect(x, 4, 8, 11, k), (int)(4 * k)), false);
                path.AddPath(RoundButton.Rounded(Rect(x + (x < 10 ? 3 : 2), 13, 3, 7.5f, k), (int)(1.5f * k)), false);
            }
            if (s.Connected && s.Mode == Protocol.ModeAmbient)
            {
                using var pen = new Pen(c, Math.Max(1.5f, 1.6f * k));
                g.DrawPath(pen, path);
            }
            else
            {
                using var b = new SolidBrush(c);
                g.FillPath(b, path);
            }
        }
        var h = bmp.GetHicon();
        icon.Icon = Icon.FromHandle(h);
        if (lastHicon != IntPtr.Zero) DestroyIcon(lastHicon);
        lastHicon = h;
    }

    static Rectangle Rect(float x, float y, float w, float h, float k) =>
        new((int)Math.Round(x * k), (int)Math.Round(y * k), Math.Max(1, (int)Math.Round(w * k)), Math.Max(1, (int)Math.Round(h * k)));

    [DllImport("user32.dll")] static extern bool DestroyIcon(IntPtr h);

    static void OpenFolder()
    {
        Directory.CreateDirectory(Settings.Dir);
        System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(Settings.Dir) { UseShellExecute = true });
    }

    void Exit()
    {
        poll.Stop();
        link.Disconnect();
        icon.Visible = false;
        if (lastHicon != IntPtr.Zero) DestroyIcon(lastHicon);
        ExitThread();
    }
}
