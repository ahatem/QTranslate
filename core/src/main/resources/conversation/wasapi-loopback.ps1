<#
  Records what a Windows output device is playing (WASAPI loopback) and streams it to stdout.

    -Command list                 One line per active output device: "<id>`t<friendly name>".
                                  The default device comes first.
    -Command capture [-DeviceId]  Writes an 8-byte header (int32 sample rate, int32 channel count,
                                  little-endian) and then 16-bit little-endian interleaved PCM
                                  until stdin closes or stdout is closed by the reader.

  WASAPI delivers no packets while nothing is playing, so silence is synthesised from the clock to
  keep the stream's timeline aligned with the microphone's.
#>
param(
    [Parameter(Mandatory = $true)][ValidateSet('list', 'list-apps', 'capture')][string]$Command,
    [string]$DeviceId = ''
)

$ErrorActionPreference = 'Stop'

Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Diagnostics;
using System.Threading;
using System.Collections.Generic;
using System.Runtime.InteropServices;

namespace QTranslate.Loopback {

[StructLayout(LayoutKind.Sequential)]
struct PropertyKey { public Guid fmtid; public int pid; }

[StructLayout(LayoutKind.Explicit)]
struct PropVariant {
    [FieldOffset(0)] public short vt;
    [FieldOffset(8)] public IntPtr pointer;
}

[ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
class MMDeviceEnumeratorCom { }

[ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDeviceEnumerator {
    int EnumAudioEndpoints(int dataFlow, int stateMask, out IMMDeviceCollection devices);
    int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
    int GetDevice([MarshalAs(UnmanagedType.LPWStr)] string id, out IMMDevice device);
}

[ComImport, Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDeviceCollection {
    int GetCount(out int count);
    int Item(int index, out IMMDevice device);
}

[ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDevice {
    int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object instance);
    int OpenPropertyStore(int access, out IPropertyStore properties);
    int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
}

[ComImport, Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IPropertyStore {
    int GetCount(out int count);
    int GetAt(int index, out PropertyKey key);
    int GetValue(ref PropertyKey key, out PropVariant value);
}

[ComImport, Guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioClient {
    int Initialize(int shareMode, int streamFlags, long bufferDuration, long periodicity, IntPtr format, IntPtr sessionGuid);
    int GetBufferSize(out uint frames);
    int GetStreamLatency(out long latency);
    int GetCurrentPadding(out uint frames);
    int IsFormatSupported(int shareMode, IntPtr format, out IntPtr closest);
    int GetMixFormat(out IntPtr format);
    int GetDevicePeriod(out long defaultPeriod, out long minimumPeriod);
    int Start();
    int Stop();
    int Reset();
    int SetEventHandle(IntPtr handle);
    int GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
}

[ComImport, Guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioCaptureClient {
    int GetBuffer(out IntPtr data, out uint frames, out uint flags, out ulong devicePosition, out ulong qpcPosition);
    int ReleaseBuffer(uint frames);
    int GetNextPacketSize(out uint frames);
}

[ComImport, Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioSessionManager2 {
    int GetAudioSessionControl(IntPtr sessionId, int streamFlags, out IntPtr sessionControl);
    int GetSimpleAudioVolume(IntPtr sessionId, int streamFlags, out IntPtr audioVolume);
    int GetSessionEnumerator(out IAudioSessionEnumerator sessionEnum);
}

[ComImport, Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioSessionEnumerator {
    int GetCount(out int count);
    int GetSession(int index, out IAudioSessionControl session);
}

[ComImport, Guid("F4B1A599-7266-4319-A8CA-E70ACB11E8CD"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioSessionControl {
    int GetState(out int state);
    int GetDisplayName([MarshalAs(UnmanagedType.LPWStr)] out string displayName);
}

[ComImport, Guid("bfb7ff88-7239-4fc9-8fa2-07c950be9c6d"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioSessionControl2 {
    int GetState(out int state);
    int GetDisplayName([MarshalAs(UnmanagedType.LPWStr)] out string displayName);
    int SetDisplayName([MarshalAs(UnmanagedType.LPWStr)] string displayName, ref Guid eventContext);
    int GetIconPath([MarshalAs(UnmanagedType.LPWStr)] out string iconPath);
    int SetIconPath([MarshalAs(UnmanagedType.LPWStr)] string iconPath, ref Guid eventContext);
    int GetGroupingParam(out Guid groupingParam);
    int SetGroupingParam(ref Guid groupingParam, ref Guid eventContext);
    int RegisterAudioSessionNotification(IntPtr newNotifications);
    int UnregisterAudioSessionNotification(IntPtr newNotifications);
    int GetSessionIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string sessionIdentifier);
    int GetSessionInstanceIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string sessionInstanceIdentifier);
    int GetProcessId(out uint processId);
    int IsSystemSoundsSession();
}

public static class Recorder {
    const int RENDER = 0, CONSOLE = 0, ACTIVE = 1, CLSCTX_ALL = 23;
    const int SHARED = 0, LOOPBACK = 0x00020000, SILENT = 0x2;

    static void Check(int hr, string what) {
        if (hr != 0) throw new Exception(what + " failed: HRESULT=0x" + hr.ToString("X8"));
    }

    static IMMDeviceEnumerator Enumerator() {
        return (IMMDeviceEnumerator)new MMDeviceEnumeratorCom();
    }

    static string FriendlyName(IMMDevice device) {
        IPropertyStore store;
        if (device.OpenPropertyStore(0, out store) != 0) return "";
        var key = new PropertyKey { fmtid = new Guid("A45C254E-DF1C-4EFD-8020-67D146A850E0"), pid = 14 };
        PropVariant value;
        if (store.GetValue(ref key, out value) != 0 || value.vt != 31) return "";
        return Marshal.PtrToStringUni(value.pointer);
    }

    public static List<string> List() {
        var result = new List<string>();
        var enumerator = Enumerator();
        string defaultId = null;
        IMMDevice defaultDevice;
        if (enumerator.GetDefaultAudioEndpoint(RENDER, CONSOLE, out defaultDevice) == 0) {
            defaultDevice.GetId(out defaultId);
            result.Add(defaultId + "\t" + FriendlyName(defaultDevice));
        }
        IMMDeviceCollection devices;
        Check(enumerator.EnumAudioEndpoints(RENDER, ACTIVE, out devices), "EnumAudioEndpoints");
        int count;
        devices.GetCount(out count);
        for (int i = 0; i < count; i++) {
            IMMDevice device;
            if (devices.Item(i, out device) != 0) continue;
            string id;
            device.GetId(out id);
            if (id != defaultId) result.Add(id + "\t" + FriendlyName(device));
        }
        return result;
    }

    public static List<string> ListApps() {
        var result = new List<string>();
        var enumerator = Enumerator();
        IMMDeviceCollection devices;
        if (enumerator.EnumAudioEndpoints(RENDER, ACTIVE, out devices) != 0) return result;
        int devCount;
        devices.GetCount(out devCount);

        var seenKeys = new HashSet<string>();
        var mgrGuid = typeof(IAudioSessionManager2).GUID;

        for (int d = 0; d < devCount; d++) {
            IMMDevice dev;
            if (devices.Item(d, out dev) != 0) continue;
            string devId;
            dev.GetId(out devId);
            string devName = FriendlyName(dev);

            object mgrObj;
            if (dev.Activate(ref mgrGuid, CLSCTX_ALL, IntPtr.Zero, out mgrObj) != 0 || mgrObj == null) continue;
            var mgr = (IAudioSessionManager2)mgrObj;

            IAudioSessionEnumerator sessionEnum;
            if (mgr.GetSessionEnumerator(out sessionEnum) != 0 || sessionEnum == null) continue;

            int sessionCount;
            sessionEnum.GetCount(out sessionCount);
            for (int s = 0; s < sessionCount; s++) {
                IAudioSessionControl ctrl;
                if (sessionEnum.GetSession(s, out ctrl) != 0 || ctrl == null) continue;
                var ctrl2 = ctrl as IAudioSessionControl2;
                if (ctrl2 == null) continue;

                uint pid;
                if (ctrl2.GetProcessId(out pid) != 0 || pid == 0) continue;

                string pairKey = pid + ":" + devId;
                if (!seenKeys.Add(pairKey)) continue;

                string procName = "";
                string windowTitle = "";
                try {
                    var proc = Process.GetProcessById((int)pid);
                    procName = proc.ProcessName;
                    windowTitle = proc.MainWindowTitle ?? "";
                } catch {
                    continue; // Process already exited or inaccessible
                }

                if (string.IsNullOrEmpty(procName)) continue;
                if (string.Equals(procName, "audiodg", StringComparison.OrdinalIgnoreCase)) continue;

                // Format: <pid>\t<procName>\t<windowTitle>\t<deviceId>\t<deviceName>
                result.Add(pid + "\t" + procName + "\t" + windowTitle + "\t" + devId + "\t" + devName);
            }
        }
        return result;
    }

    public static void Capture(string deviceId) {
        var enumerator = Enumerator();
        IMMDevice device;
        if (string.IsNullOrEmpty(deviceId)) Check(enumerator.GetDefaultAudioEndpoint(RENDER, CONSOLE, out device), "GetDefaultAudioEndpoint");
        else Check(enumerator.GetDevice(deviceId, out device), "GetDevice");

        var clientId = typeof(IAudioClient).GUID;
        object instance;
        Check(device.Activate(ref clientId, CLSCTX_ALL, IntPtr.Zero, out instance), "Activate");
        var client = (IAudioClient)instance;

        IntPtr format;
        Check(client.GetMixFormat(out format), "GetMixFormat");
        int channels = Marshal.ReadInt16(format, 2);
        int rate = Marshal.ReadInt32(format, 4);
        int bits = Marshal.ReadInt16(format, 14);
        int tag = Marshal.ReadInt16(format, 0) & 0xFFFF;
        if (tag == 0xFFFE) tag = Marshal.ReadInt16(format, 24) & 0xFFFF; // WAVEFORMATEXTENSIBLE sub-format
        bool isFloat = tag == 3;
        if (!(isFloat && bits == 32) && !(tag == 1 && bits == 16))
            throw new Exception("Unsupported mix format: tag=" + tag + " bits=" + bits);

        Check(client.Initialize(SHARED, LOOPBACK, 2000000, 0, format, IntPtr.Zero), "Initialize");
        var captureId = typeof(IAudioCaptureClient).GUID;
        object service;
        Check(client.GetService(ref captureId, out service), "GetService");
        var capture = (IAudioCaptureClient)service;

        var stdout = Console.OpenStandardOutput();
        var header = new byte[8];
        BitConverter.GetBytes(rate).CopyTo(header, 0);
        BitConverter.GetBytes(channels).CopyTo(header, 4);
        stdout.Write(header, 0, 8);
        stdout.Flush();

        // The reader closing our stdin is the polite stop signal.
        bool stopping = false;
        var watcher = new Thread(() => { try { Console.OpenStandardInput().ReadByte(); } catch { } stopping = true; });
        watcher.IsBackground = true;
        watcher.Start();

        Check(client.Start(), "Start");
        var clock = Stopwatch.StartNew();
        long emitted = 0;
        int bytesPerFrame = channels * 2;
        var buffer = new byte[rate * bytesPerFrame];
        try {
            while (!stopping) {
                uint packet;
                Check(capture.GetNextPacketSize(out packet), "GetNextPacketSize");
                if (packet == 0) {
                    long expected = clock.ElapsedMilliseconds * rate / 1000;
                    long gap = expected - emitted;
                    if (gap > rate / 10) { // nothing has played for 100 ms: fill the timeline with silence
                        int frames = (int)Math.Min(gap, rate);
                        Array.Clear(buffer, 0, frames * bytesPerFrame);
                        stdout.Write(buffer, 0, frames * bytesPerFrame);
                        emitted += frames;
                    }
                    Thread.Sleep(10);
                    continue;
                }
                while (packet != 0) {
                    IntPtr data; uint frames, flags; ulong devicePosition, qpcPosition;
                    Check(capture.GetBuffer(out data, out frames, out flags, out devicePosition, out qpcPosition), "GetBuffer");
                    int length = (int)frames * bytesPerFrame;
                    if (buffer.Length < length) buffer = new byte[length];
                    if ((flags & SILENT) != 0) {
                        Array.Clear(buffer, 0, length);
                    } else if (isFloat) {
                        int samples = (int)frames * channels;
                        var floats = new float[samples];
                        Marshal.Copy(data, floats, 0, samples);
                        for (int i = 0; i < samples; i++) {
                            float f = floats[i];
                            if (f > 1f) f = 1f; else if (f < -1f) f = -1f;
                            short s = (short)(f * 32767f);
                            buffer[i * 2] = (byte)s;
                            buffer[i * 2 + 1] = (byte)(s >> 8);
                        }
                    } else {
                        Marshal.Copy(data, buffer, 0, length);
                    }
                    Check(capture.ReleaseBuffer(frames), "ReleaseBuffer");
                    stdout.Write(buffer, 0, length);
                    emitted += frames;
                    Check(capture.GetNextPacketSize(out packet), "GetNextPacketSize");
                }
                stdout.Flush();
            }
        } catch (IOException) {
            // The reader went away: a normal way to stop.
        } finally {
            client.Stop();
            Marshal.FreeCoTaskMem(format);
        }
    }
}
}
'@

try {
    if ($Command -eq 'list') {
        [QTranslate.Loopback.Recorder]::List() | ForEach-Object { [Console]::Out.WriteLine($_) }
    } elseif ($Command -eq 'list-apps') {
        [QTranslate.Loopback.Recorder]::ListApps() | ForEach-Object { [Console]::Out.WriteLine($_) }
    } else {
        [QTranslate.Loopback.Recorder]::Capture($DeviceId)
    }
    exit 0
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
