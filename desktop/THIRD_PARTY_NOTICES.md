# Third-party notices — `:desktop`

The desktop app's platform services use these libraries, taken as published on Maven Central:

| Library | Version | Licence | Used for |
|---|---|---|---|
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | 1.12.0 | Apache-2.0 | `settings.json` (DesktopStore) |
| [JmDNS](https://github.com/jmdns/jmdns) | 3.6.4 | Apache-2.0 | SMB servers announced on the local network (SmbDiscovery) |
| [SLF4J API](https://www.slf4j.org/) | as JmDNS and smbj pull it in | MIT | JmDNS's logging |
