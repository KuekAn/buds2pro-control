namespace BudsControl;

/// <summary>
/// Galaxy Buds (Buds+ and later) RFCOMM framing, ported from the Android app.
///   [0xFD] [len lo] [len hi | flags] [msg id] [payload...] [crc16 x2] [0xDD]
/// len = 1 (id) + payload + 2 (crc), 10 bits. CRC-16/XMODEM over id + payload.
/// </summary>
public static class Protocol
{
    public static readonly Guid SppUuid = new("2e73a4ad-332d-41fc-90e2-16bef06523f2");

    const byte Som = 0xFD, Eom = 0xDD;

    public const int StatusUpdated = 0x60;
    public const int ExtendedStatusUpdated = 0x61;
    public const int NoiseControlsUpdate = 0x77;
    public const int NoiseControls = 0x78;
    public const int Ack = 0x42;

    public const int ModeOff = 0, ModeAnc = 1, ModeAmbient = 2;

    /// <summary>CRC byte order on the wire; flipped automatically if the buds use the other one.</summary>
    public static volatile bool CrcLittleEndian = true;

    public sealed record Packet(int Id, byte[] Payload, bool CrcOk);

    public static byte[] Encode(int id, params byte[] payload)
    {
        int size = payload.Length + 3;
        var o = new byte[size + 4];
        o[0] = Som;
        o[1] = (byte)(size & 0xFF);
        o[2] = (byte)((size >> 8) & 0x03);
        o[3] = (byte)id;
        payload.CopyTo(o, 4);
        int crc = Crc16(o, 3, 1 + payload.Length);
        byte hi = (byte)(crc >> 8), lo = (byte)crc;
        o[4 + payload.Length] = CrcLittleEndian ? lo : hi;
        o[5 + payload.Length] = CrcLittleEndian ? hi : lo;
        o[6 + payload.Length] = Eom;
        return o;
    }

    /// <summary>Stream parser: feed raw socket bytes, get whole packets out.</summary>
    public sealed class Parser
    {
        readonly List<byte> buf = new();

        public List<Packet> Feed(byte[] data, int n)
        {
            buf.AddRange(data.AsSpan(0, n).ToArray());
            var outp = new List<Packet>();
            while (true)
            {
                int start = buf.IndexOf(Som);
                if (start < 0) { buf.Clear(); break; }
                if (start > 0) buf.RemoveRange(0, start);
                if (buf.Count < 3) break;
                int size = buf[1] | ((buf[2] & 0x03) << 8);
                int total = size + 4;
                if (size < 3) { buf.RemoveAt(0); continue; }
                if (buf.Count < total) break;
                if (buf[total - 1] != Eom) { buf.RemoveAt(0); continue; }
                var frame = buf.GetRange(0, total).ToArray();
                int id = frame[3];
                var payload = frame[4..(4 + size - 3)];
                int crc = Crc16(frame, 3, size - 2);
                int c0 = frame[total - 3], c1 = frame[total - 2];
                bool le = (c0 | (c1 << 8)) == crc, be = ((c0 << 8) | c1) == crc;
                if (be && !le) CrcLittleEndian = false;
                if (le && !be) CrcLittleEndian = true;
                outp.Add(new Packet(id, payload, le || be));
                buf.RemoveRange(0, total);
            }
            return outp;
        }
    }

    public static int Crc16(byte[] data, int off, int len)
    {
        int crc = 0;
        for (int i = off; i < off + len; i++)
        {
            crc ^= data[i] << 8;
            for (int k = 0; k < 8; k++) crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
            crc &= 0xFFFF;
        }
        return crc;
    }

    public static string Hex(byte[] b) => Convert.ToHexString(b).Chunk(2).Select(c => new string(c)).Aggregate("", (a, s) => a.Length == 0 ? s : a + " " + s);
}
