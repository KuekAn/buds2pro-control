using System.Net.Sockets;

namespace BudsControl;

/// <summary>Last known state of the buds, as shown in the tray and the panel.</summary>
public sealed record BudsState
{
    public bool Connected { get; init; }
    public bool Connecting { get; init; }
    /// <summary>The buds are paired but connected to another device (or off / in the case).</summary>
    public bool Elsewhere { get; init; }
    public bool Paired { get; init; } = true;
    public string Name { get; init; } = "Galaxy Buds2 Pro";
    public int BattL { get; init; } = -1;
    public int BattR { get; init; } = -1;
    public int BattCase { get; init; } = -1;
    /// <summary>0 disconnected, 1 wearing, 2 out of ear, 3 in case.</summary>
    public int PlaceL { get; init; } = -1;
    public int PlaceR { get; init; } = -1;
    public int Mode { get; init; } = -1;
    public bool Reseating { get; init; }

    public bool SingleBud => Connected && ((PlaceL == 1) ^ (PlaceR == 1));
    public string ShortName => Name.Replace("Galaxy ", "");

    public string ModeLabel => Mode switch
    {
        Protocol.ModeAnc => "ANC", Protocol.ModeAmbient => "Ambient", Protocol.ModeOff => "Off", _ => "?",
    };

    public static string Batt(int v, int place) => v is >= 1 and <= 100 && place != 0 ? $"{v}%" : "–";

    public string Status => Connected
        ? (PlaceL, PlaceR) switch
        {
            (1, 1) => "In ears",
            (1, _) => "Left in ear",
            (_, 1) => "Right in ear",
            (3, 3) => "In case",
            _ => "Connected",
        }
        : Connecting ? "Connecting…"
        : !Paired ? "Not paired with this PC"
        : Elsewhere ? "Not on this PC"
        : "Not connected";
}

/// <summary>
/// The RFCOMM control link, ported from the Android BudsService. "Sticky" like the phone app:
/// it only opens the link while Windows already has the buds connected, so it never pulls
/// them away from another device unless asked (<see cref="Connect"/> with force).
/// </summary>
public sealed class BudsLink
{
    readonly object gate = new();
    readonly object ackLock = new();
    NetworkStream? stream;
    Thread? worker;
    volatile bool stopping;
    int lastModeAck = -1;

    public BudsState State { get; private set; } = new();
    public event Action<BudsState>? Changed;

    void Update(Func<BudsState, BudsState> f)
    {
        BudsState s;
        lock (gate) { State = f(State); s = State; }
        Changed?.Invoke(s);
    }

    public bool IsRunning => worker?.IsAlive == true;

    /// <summary>
    /// Opens the link if it isn't open. Without force, only when Windows reports the buds
    /// connected; with force (the user asked), it pages the buds even if they're elsewhere.
    /// </summary>
    public void Connect(bool force = false)
    {
        if (IsRunning) return;
        stopping = false;
        worker = new Thread(() => Run(force)) { IsBackground = true, Name = "buds-link" };
        worker.Start();
    }

    public void Disconnect()
    {
        stopping = true;
        try { stream?.Dispose(); } catch { }
    }

    void Run(bool force)
    {
        var dev = Bluetooth.FindBuds(Settings.Current.Address);
        if (dev == null)
        {
            Log.Write("No paired Galaxy Buds found");
            Update(s => s with { Connected = false, Connecting = false, Paired = false });
            return;
        }
        if (!dev.Connected && !force)
        {
            Update(s => s with { Connected = false, Connecting = false, Elsewhere = true, Paired = true, Name = dev.Name });
            return;
        }
        Update(s => s with { Connecting = true, Paired = true, Name = dev.Name });

        NetworkStream? ns = null;
        for (int attempt = 1; attempt <= 3 && ns == null && !stopping; attempt++)
        {
            try { ns = Bluetooth.Connect(dev.Address); }
            catch (Exception e)
            {
                Log.Write($"connect {attempt} failed: {e.Message}");
                Thread.Sleep(1500 * attempt);
            }
        }
        if (ns == null)
        {
            Update(s => s with { Connected = false, Connecting = false, Elsewhere = !dev.Connected });
            return;
        }

        stream = ns;
        Settings.Current.Address = dev.Address;
        Settings.Save();
        Log.Write($"connected to {dev.Name}");
        Update(s => s with { Connected = true, Connecting = false, Elsewhere = false });

        var parser = new Protocol.Parser();
        var buf = new byte[1024];
        try
        {
            while (true)
            {
                int n = ns.Read(buf, 0, buf.Length);
                if (n <= 0) break;
                foreach (var p in parser.Feed(buf, n)) Handle(p);
            }
        }
        catch (Exception e) { if (!stopping) Log.Write($"link closed: {e.Message}"); }
        stream = null;
        try { ns.Dispose(); } catch { }
        Update(s => s with { Connected = false, Connecting = false, Reseating = false });
    }

    void Handle(Protocol.Packet p)
    {
        var b = p.Payload;
        int U(int i) => i < b.Length ? b[i] : -1;
        switch (p.Id)
        {
            case Protocol.StatusUpdated when b.Length >= 7:
                Update(s => s with { BattL = U(1), BattR = U(2), PlaceL = U(5) >> 4, PlaceR = U(5) & 0x0F, BattCase = U(6) });
                break;
            case Protocol.ExtendedStatusUpdated when b.Length >= 13:
                // Byte 12 = noise control (0 off, 1 ANC, 2 ambient), verified on Buds2 Pro rev 0x0E.
                Update(s => s with
                {
                    BattL = U(2), BattR = U(3), PlaceL = U(6) >> 4, PlaceR = U(6) & 0x0F, BattCase = U(7),
                    Mode = U(12) is >= 0 and <= 2 ? U(12) : s.Mode,
                });
                break;
            case Protocol.NoiseControlsUpdate when b.Length > 0 && U(0) is >= 0 and <= 2:
                Update(s => s with { Mode = U(0) });
                break;
            case Protocol.Ack when U(0) == Protocol.NoiseControls && U(1) is >= 0 and <= 2:
                Update(s => s with { Mode = U(1) });
                lock (ackLock) { lastModeAck = U(1); Monitor.PulseAll(ackLock); }
                break;
        }
    }

    void Write(byte[] packet)
    {
        var ns = stream ?? throw new IOException("not connected");
        lock (ns) { ns.Write(packet); ns.Flush(); }
    }

    public void SetMode(int mode)
    {
        if (mode is < 0 or > 2 || stream == null) return;
        try { Write(Protocol.Encode(Protocol.NoiseControls, (byte)mode)); Update(s => s with { Mode = mode }); }
        catch (Exception e) { Log.Write($"set mode failed: {e.Message}"); }
    }

    /// <summary>Send a mode, wait up to 2 s for the buds' ack, then 1 s to settle.</summary>
    void SetModeAndWait(int m)
    {
        lock (ackLock) lastModeAck = -1;
        Write(Protocol.Encode(Protocol.NoiseControls, (byte)m));
        var deadline = DateTime.UtcNow.AddSeconds(2);
        lock (ackLock)
        {
            while (lastModeAck != m)
            {
                var left = deadline - DateTime.UtcNow;
                if (left <= TimeSpan.Zero) { Log.Write($"no ack for mode {m}"); break; }
                Monitor.Wait(ackLock, left);
            }
        }
        Thread.Sleep(1000);
    }

    /// <summary>
    /// Software stand-in for re-seating the buds: two off/on cycles, then check they ended on
    /// the target mode. A command sent mid-transition is silently dropped, hence the waits.
    /// </summary>
    public void Reseat()
    {
        var s0 = State;
        if (s0.Reseating || stream == null) return;
        int mode = s0.Mode is >= 0 and <= 2 ? s0.Mode : Protocol.ModeAnc;
        int bounce = mode == Protocol.ModeOff ? Protocol.ModeAnc : Protocol.ModeOff;
        Update(s => s with { Reseating = true });
        new Thread(() =>
        {
            try
            {
                for (int i = 0; i < 2; i++) { SetModeAndWait(bounce); SetModeAndWait(mode); }
                for (int r = 0; r < 3; r++)
                {
                    Thread.Sleep(800);
                    if (State.Mode == mode) break;
                    SetModeAndWait(mode);
                }
            }
            catch (Exception e) { Log.Write($"reseat failed: {e.Message}"); }
            Update(s => s with { Reseating = false });
        }) { IsBackground = true }.Start();
    }

    /// <summary>Called on a timer: notices the buds arriving on (or leaving) this PC.</summary>
    public void Poll()
    {
        if (IsRunning) return;
        var dev = Bluetooth.FindBuds(Settings.Current.Address);
        if (dev == null) { if (State.Paired) Update(s => s with { Paired = false }); return; }
        if (dev.Connected) Connect();
        else if (!State.Elsewhere || !State.Paired) Update(s => s with { Elsewhere = true, Paired = true, Name = dev.Name });
    }
}
