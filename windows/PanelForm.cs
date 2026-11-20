using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;

namespace BudsControl;

/// <summary>Rounded, flat button drawn by hand (WinForms buttons can't do this cleanly).</summary>
public sealed class RoundButton : Control
{
    [System.ComponentModel.DesignerSerializationVisibility(System.ComponentModel.DesignerSerializationVisibility.Hidden)]
    public Color Fill { get; set; } = Color.Gray;
    [System.ComponentModel.DesignerSerializationVisibility(System.ComponentModel.DesignerSerializationVisibility.Hidden)]
    public int Radius { get; set; } = 14;
    bool hover, down;

    public RoundButton()
    {
        SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer |
                 ControlStyles.UserPaint | ControlStyles.ResizeRedraw | ControlStyles.SupportsTransparentBackColor, true);
        Cursor = Cursors.Hand;
        TabStop = false;
    }

    protected override void OnMouseEnter(EventArgs e) { hover = true; Invalidate(); base.OnMouseEnter(e); }
    protected override void OnMouseLeave(EventArgs e) { hover = false; down = false; Invalidate(); base.OnMouseLeave(e); }
    protected override void OnMouseDown(MouseEventArgs e) { down = true; Invalidate(); base.OnMouseDown(e); }
    protected override void OnMouseUp(MouseEventArgs e) { down = false; Invalidate(); base.OnMouseUp(e); }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.Clear(Parent?.BackColor ?? BackColor);
        var c = Fill;
        if (Enabled && (hover || down))
        {
            float k = down ? 0.16f : 0.08f;
            bool darkFill = c.GetBrightness() < 0.5f;
            c = darkFill ? Blend(c, Color.White, k) : Blend(c, Color.Black, k);
        }
        int r = (int)(Radius * DeviceDpi / 96f);
        using (var path = Rounded(new Rectangle(0, 0, Width - 1, Height - 1), r))
        using (var b = new SolidBrush(c)) g.FillPath(b, path);
        TextRenderer.DrawText(g, Text, Font, ClientRectangle, ForeColor,
            TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis);
    }

    static Color Blend(Color a, Color b, float t) => Color.FromArgb(a.A,
        (int)(a.R + (b.R - a.R) * t), (int)(a.G + (b.G - a.G) * t), (int)(a.B + (b.B - a.B) * t));

    public static GraphicsPath Rounded(Rectangle rc, int r)
    {
        var p = new GraphicsPath();
        int d = Math.Min(r * 2, Math.Min(rc.Width, rc.Height));
        p.AddArc(rc.X, rc.Y, d, d, 180, 90);
        p.AddArc(rc.Right - d, rc.Y, d, d, 270, 90);
        p.AddArc(rc.Right - d, rc.Bottom - d, d, d, 0, 90);
        p.AddArc(rc.X, rc.Bottom - d, d, d, 90, 90);
        p.CloseFigure();
        return p;
    }
}

/// <summary>The flyout opened from the tray icon: battery, mode buttons, re-seat.</summary>
public sealed class PanelForm : Form
{
    readonly BudsLink link;
    readonly Label title = new(), status = new();
    readonly Label[] battValue = { new(), new(), new() }, battPlace = { new(), new(), new() };
    readonly RoundButton anc = new() { Text = "ANC" }, amb = new() { Text = "Ambient" }, off = new() { Text = "Off" };
    readonly RoundButton reseat = new() { Text = "Re-seat" }, connect = new() { Text = "Connect on this PC" };
    Theme theme = Theme.Current();

    public PanelForm(BudsLink link)
    {
        this.link = link;
        FormBorderStyle = FormBorderStyle.None;
        ShowInTaskbar = false;
        TopMost = true;
        StartPosition = FormStartPosition.Manual;
        Text = "Buds Control";
        AutoScaleMode = AutoScaleMode.None;
        DoubleBuffered = true;

        Controls.AddRange(new Control[] { title, status, anc, amb, off, reseat, connect });
        Controls.AddRange(battValue); Controls.AddRange(battPlace);
        anc.Click += (_, _) => link.SetMode(Protocol.ModeAnc);
        amb.Click += (_, _) => link.SetMode(Protocol.ModeAmbient);
        off.Click += (_, _) => link.SetMode(Protocol.ModeOff);
        reseat.Click += (_, _) => link.Reseat();
        connect.Click += (_, _) => { if (link.State.Paired) TakeOver(); else OpenBluetoothSettings(); };
        Deactivate += (_, _) => Hide();
        KeyPreview = true;
        KeyDown += (_, e) => { if (e.KeyCode == Keys.Escape) Hide(); };
    }

    /// <summary>Asks the buds onto this PC: pages them over RFCOMM; falls back to Bluetooth settings.</summary>
    void TakeOver()
    {
        link.Connect(force: true);
        Task.Run(async () =>
        {
            await Task.Delay(12000);
            if (!link.State.Connected)
                BeginInvoke(() => OpenBluetoothSettings());
        });
    }

    public static void OpenBluetoothSettings()
    {
        try { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("ms-settings:bluetooth") { UseShellExecute = true }); }
        catch { }
    }

    float S => DeviceDpi / 96f;
    int P(float v) => (int)Math.Round(v * S);

    void LayoutAll()
    {
        int w = P(340), pad = P(16), gap = P(8);
        var big = new Font("Segoe UI Semibold", 11f * 1f);
        title.Font = big; status.Font = new Font("Segoe UI", 9f);
        title.SetBounds(pad, pad, w - 2 * pad, P(22));
        status.SetBounds(pad, pad + P(22), w - 2 * pad, P(18));

        int y = pad + P(48), colW = (w - 2 * pad) / 3;
        for (int i = 0; i < 3; i++)
        {
            battValue[i].Font = new Font("Segoe UI Semibold", 13f);
            battPlace[i].Font = new Font("Segoe UI", 8.5f);
            battValue[i].SetBounds(pad + i * colW, y, colW, P(26));
            battPlace[i].SetBounds(pad + i * colW, y + P(26), colW, P(18));
        }
        y += P(54);

        int bw = (w - 2 * pad - 2 * gap) / 3, bh = P(64);
        foreach (var (b, i) in new[] { anc, amb, off }.Select((b, i) => (b, i)))
        {
            b.Font = new Font("Segoe UI Semibold", 11.5f);
            b.SetBounds(pad + i * (bw + gap), y, bw, bh);
        }
        y += bh + gap;
        reseat.Font = connect.Font = new Font("Segoe UI", 10f);
        reseat.SetBounds(pad, y, w - 2 * pad, P(38));
        connect.SetBounds(pad, y, w - 2 * pad, P(38));
        y += P(38) + pad;
        ClientSize = new Size(w, y);
    }

    public void ShowNear()
    {
        theme = Theme.Current();
        LayoutAll();
        Render(link.State);
        var wa = Screen.FromPoint(Cursor.Position).WorkingArea;
        Location = new Point(wa.Right - Width - P(12), wa.Bottom - Height - P(12));
        Show();
        Activate();
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        int round = 2; // DWMWCP_ROUND (Windows 11; ignored on 10)
        try { DwmSetWindowAttribute(Handle, 33, ref round, sizeof(int)); } catch { }
    }

    [DllImport("dwmapi.dll")] static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

    static string Place(int p) => p switch { 1 => "In ear", 2 => "Out of ear", 3 => "In case", 0 => "Not connected", _ => "" };

    public void Render(BudsState s)
    {
        BackColor = theme.Bg;
        foreach (var l in new[] { title }.Concat(battValue)) { l.ForeColor = theme.Text; l.BackColor = theme.Bg; }
        foreach (var l in new[] { status }.Concat(battPlace)) { l.ForeColor = theme.Dim; l.BackColor = theme.Bg; }

        title.Text = s.ShortName;
        status.Text = s.Reseating ? "Re-seating…" : s.Status;
        bool live = s.Connected;
        bool hasCase = live && s.BattCase is >= 1 and <= 100;
        battValue[0].Text = live ? $"L {BudsState.Batt(s.BattL, s.PlaceL)}" : "L –";
        battValue[1].Text = live ? $"R {BudsState.Batt(s.BattR, s.PlaceR)}" : "R –";
        battValue[2].Text = hasCase ? $"Case {s.BattCase}%" : "";
        battPlace[0].Text = live ? Place(s.PlaceL) : "";
        battPlace[1].Text = live ? Place(s.PlaceR) : "";
        battPlace[2].Text = "";

        foreach (var (b, mode) in new[] { (anc, Protocol.ModeAnc), (amb, Protocol.ModeAmbient), (off, Protocol.ModeOff) })
        {
            bool on = live && s.Mode == mode;
            b.Fill = on ? (s.SingleBud ? theme.Single : theme.Accent) : theme.Card;
            b.ForeColor = on ? (s.SingleBud ? theme.OnSingle : theme.OnAccent) : (live ? theme.Text : theme.Dim);
            b.Enabled = live;
            b.Invalidate();
        }
        reseat.Fill = theme.Card; reseat.ForeColor = theme.Text;
        connect.Fill = theme.Accent; connect.ForeColor = theme.OnAccent;
        reseat.Visible = live;
        reseat.Enabled = !s.Reseating;
        connect.Visible = !live;
        connect.Text = s.Connecting ? "Connecting…" : !s.Paired ? "Pair in Bluetooth settings" : "Connect on this PC";
        connect.Enabled = !s.Connecting;
        reseat.Invalidate(); connect.Invalidate();
    }
}
