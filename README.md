# Buds Control

An Android app for the Samsung Galaxy Buds2 Pro that works without Samsung's apps or account. It replaces Galaxy Wearable and the Buds2 Pro plugin, and talks to the buds directly over Bluetooth RFCOMM (SPP).

The only network use is the update check: on request, or once a day if you turn that on in More, it reads [release/latest.json](release/latest.json) from this repo and can download the newer APK. There are no accounts, analytics or other connections.

## Features

- Battery for L, R and the case, with a small in-ear / out of ear / in case line under each bud. Noise control: ANC, Ambient or Off.
- Sticky with two devices: the app only opens its control link when the buds are already connected to this phone, so it never pulls them away from another device. A "Use the buds on this phone" button moves them on request.
- Re-seat in one tap, for when the left and right buds pick up a different noise mode.
- A soft teal state when only one bud is in an ear.
- Home screen widgets, each in a solid or see-through "glass" style:
  - Controls: battery, ANC / Ambient / Off and re-seat; one row at 4x1, a full grid at 4x2 and up.
  - Vertical: the same buttons stacked in a column (1x3).
  - Mode tile (solid) and a minimal faint tile, both down to 1x1; tap cycles the mode.
  On the one-row layouts, tap the battery column to bring the buds to this device and double-tap it to open the app.
- An ongoing notification with mode actions while linked; More can switch it to a silent one without buttons (Android requires some notification while the link runs).
- Touch controls:
  - a master on/off switch
  - single, double and triple tap, and touch and hold
  - edge double tap and the call gestures
  - the hold action per side
  - the noise-mode cycle.
- Find my buds, on its own page. It needs a 2-second hold, and it is blocked while a bud is worn.
- One-earbud noise control and custom ambient settings.
- A 10-band equaliser (31 Hz to 16 kHz, +/-12 dB) that runs on the phone's audio output, since the buds only take preset EQ. It can import:
  - Poweramp preset .json exports (one or many presets per file)
  - AutoEQ ParametricEQ and FixedBandEQ files, and Equalizer APO filter lines
  - AutoEQ GraphicEQ lines
  - plain "frequency gain" pairs

  Imported profiles are saved by name, each with its preamp (automatic, or set by hand from -15 to +5 dB). By default the EQ only acts while the buds are the output, and it attaches per app like Wavelet (apps that announce their audio session, such as Poweramp and Spotify). An "All audio" switch uses the global mix instead, which covers every app.

  Android's global EQ (DynamicsProcessing) works in FFT blocks, so it can sound very slightly less clean than a player's own EQ such as Poweramp's. To reduce this, the 10-point curve is spread smoothly over 48 narrow bands, with 20 ms blocks and no limiter.

## Known limits on Buds2 Pro

- The buds store the ambient volume (0x84) and ANC level (0x83) commands but ignore them.
- In an ear test, custom ambient levels and Extra loud (0x96) made no audible difference.
- There is no SPP command for the buds' own media volume. Samsung's app sets the phone's media volume and relies on AVRCP absolute volume.

## Windows

[windows/](windows/) holds a small tray app for the same buds (C# WinForms, .NET 9, no packages). It talks to the buds over Windows' own Bluetooth RFCOMM sockets.

- The tray icon shows the mode: solid for ANC, outline for Ambient, faint for Off or not connected. Hover for battery.
- Left click opens a panel with L/R/case battery, in-ear status, ANC / Ambient / Off and Re-seat. Right click has the same actions, Start with Windows, and the log folder.
- It is sticky like the phone app: it only opens its link while Windows already has the buds connected. "Connect on this PC" asks for them on purpose.
- Download [release/BudsControl-Windows-0.1.0.exe](release/BudsControl-Windows-0.1.0.exe) (one file, ~200 KB). It needs Microsoft's [.NET 9 Desktop Runtime](https://dotnet.microsoft.com/download/dotnet/9.0). Windows may warn that it's from an unknown publisher, because it isn't code-signed.
- Pair the buds with the PC first (Settings > Bluetooth & devices). To build it yourself, run `windowsuild.ps1` with the .NET 9 SDK.

## Build

Use `.\build.ps1`, or `-NoInstall` to build only. It needs a JDK, the Android SDK and Gradle. It has no third-party dependencies in the app; JUnit is used only for the EQ parser tests (`gradlew testDebugUnitTest`).

Protocol details come from [GalaxyBudsClient](https://github.com/timschneeb/GalaxyBudsClient) and from testing on hardware (firmware revision 0x0E).

## Download

Get the latest debug-signed APK from [release/](release/) (see [latest.json](release/latest.json) for the current version) and open it on the phone, or install it with `adb install -r`. After that, More > Updates can check for and install new versions.

Updates install only over an APK signed with the same key, so builds from a different machine's debug key need an uninstall first.
