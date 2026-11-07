# Third-party notices — `:desktop`

The desktop app's platform services use these libraries, taken as published on Maven Central:

| Library | Version | Licence | Used for |
|---|---|---|---|
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | 1.12.0 | Apache-2.0 | `state.json` (StateStore), and the `settings.json` of earlier versions taken over (DesktopStore) |
| [JmDNS](https://github.com/jmdns/jmdns) | 3.6.4 | Apache-2.0 | SMB servers announced on the local network (SmbDiscovery) |
| [dbus-java](https://github.com/hypfvieh/dbus-java) (core, transport-native-unixsocket) | 5.2.2 | MIT | the session bus on Linux: MPRIS media keys, the screensaver's inhibit |
| [JNA](https://github.com/java-native-access/jna) | 5.19.1 | Apache-2.0 (or LGPL-2.1) | Windows' System Media Transport Controls, WinRT called through it |
| [SLF4J API](https://www.slf4j.org/) | as JmDNS and smbj pull it in | MIT | JmDNS's and dbus-java's logging |

Beside the native library the packages carry the OpenMP runtime it needs, unmodified: GCC's `libgomp.so.1` in the
AppImage (GPL-3.0 with the GCC Runtime Library Exception, `THIRD-PARTY-NOTICE.libgomp.txt`) and Visual C++'s
`vcomp140.dll` in the MSI and the zip (Visual Studio's distributable code, `THIRD-PARTY-NOTICE.vcomp140.txt`).

The System Media Transport Controls are called by each function's place in its interface table and by the interfaces'
ids, as the Windows SDK's `windows.media.h` and `windows.media.playback.h` give them (read from the bindings
[windows-rs](https://github.com/microsoft/windows-rs) generates from Microsoft's own metadata, MIT / Apache-2.0); the
button handler's id is worked out as WinRT does for a parameterized interface and checked against the SDK's value.
MPRIS follows the [MPRIS D-Bus Interface Specification](https://specifications.freedesktop.org/mpris-spec/latest/),
the screensaver's inhibit the freedesktop [Idle Inhibition Service](https://specifications.freedesktop.org/idle-inhibit-spec/latest/).
