using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;

namespace BudsControl;

/// <summary>
/// Paired-device lookup (Win32 BluetoothApis) and RFCOMM sockets (Winsock AF_BTH),
/// so no NuGet packages are needed.
/// </summary>
public static class Bluetooth
{
    public sealed record Device(ulong Address, string Name, bool Connected);

    [StructLayout(LayoutKind.Sequential)]
    struct SearchParams
    {
        public uint dwSize;
        public int fReturnAuthenticated, fReturnRemembered, fReturnUnknown, fReturnConnected, fIssueInquiry;
        public byte cTimeoutMultiplier;
        public IntPtr hRadio;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct SystemTime { public ushort y, mo, dow, d, h, mi, s, ms; }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct DeviceInfo
    {
        public uint dwSize;
        public ulong Address;
        public uint ulClassofDevice;
        public int fConnected, fRemembered, fAuthenticated;
        public SystemTime stLastSeen, stLastUsed;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 248)] public string szName;
    }

    [DllImport("BluetoothApis.dll", SetLastError = true)]
    static extern IntPtr BluetoothFindFirstDevice(ref SearchParams p, ref DeviceInfo info);
    [DllImport("BluetoothApis.dll", SetLastError = true)]
    static extern bool BluetoothFindNextDevice(IntPtr hFind, ref DeviceInfo info);
    [DllImport("BluetoothApis.dll")]
    static extern bool BluetoothFindDeviceClose(IntPtr hFind);

    /// <summary>Paired (remembered) devices with their current connection state. No inquiry, so fast.</summary>
    public static List<Device> PairedDevices()
    {
        var list = new List<Device>();
        var p = new SearchParams
        {
            dwSize = (uint)Marshal.SizeOf<SearchParams>(),
            fReturnAuthenticated = 1, fReturnRemembered = 1, fReturnConnected = 1,
        };
        var info = new DeviceInfo { dwSize = (uint)Marshal.SizeOf<DeviceInfo>() };
        IntPtr h = BluetoothFindFirstDevice(ref p, ref info);
        if (h == IntPtr.Zero) return list;
        try
        {
            do
            {
                list.Add(new Device(info.Address, info.szName ?? "", info.fConnected != 0));
                info = new DeviceInfo { dwSize = (uint)Marshal.SizeOf<DeviceInfo>() };
            } while (BluetoothFindNextDevice(h, ref info));
        }
        finally { BluetoothFindDeviceClose(h); }
        return list;
    }

    /// <summary>The paired buds: the remembered address first, else the best name match.</summary>
    public static Device? FindBuds(ulong saved)
    {
        var all = PairedDevices();
        var byAddr = all.FirstOrDefault(d => d.Address == saved && saved != 0);
        if (byAddr != null) return byAddr;
        var buds = all.Where(d => d.Name.Contains("Buds", StringComparison.OrdinalIgnoreCase)).ToList();
        return buds.FirstOrDefault(d => d.Name.Contains("Buds2 Pro", StringComparison.OrdinalIgnoreCase))
               ?? buds.FirstOrDefault();
    }

    const AddressFamily AfBth = (AddressFamily)32;
    const ProtocolType BthProtoRfcomm = (ProtocolType)3;

    /// <summary>SOCKADDR_BTH (packed: family u16, address u64, service GUID, port u32 = 30 bytes).</summary>
    sealed class BthEndPoint(ulong address, Guid service) : EndPoint
    {
        public override AddressFamily AddressFamily => AfBth;

        public override SocketAddress Serialize()
        {
            var sa = new SocketAddress(AfBth, 30);
            var a = BitConverter.GetBytes(address);
            for (int i = 0; i < 8; i++) sa[2 + i] = a[i];
            var g = service.ToByteArray();
            for (int i = 0; i < 16; i++) sa[10 + i] = g[i];
            for (int i = 0; i < 4; i++) sa[26 + i] = 0; // port 0: look the channel up by service UUID
            return sa;
        }

        public override EndPoint Create(SocketAddress socketAddress) => this;
    }

    /// <summary>Opens an RFCOMM stream to the buds' Samsung SPP service. Blocks until connected or throws.</summary>
    public static NetworkStream Connect(ulong address)
    {
        var s = new Socket(AfBth, SocketType.Stream, BthProtoRfcomm);
        try
        {
            s.Connect(new BthEndPoint(address, Protocol.SppUuid));
            return new NetworkStream(s, ownsSocket: true);
        }
        catch { s.Dispose(); throw; }
    }
}
