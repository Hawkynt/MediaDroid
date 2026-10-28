# Third-party notices — `:core:sources`

The network sources are read with these libraries, taken as published on Maven Central:

| Library | Version | Licence | Used for |
|---|---|---|---|
| [smbj](https://github.com/hierynomus/smbj) | 0.15.0 | Apache-2.0 | Windows / Samba shares (SMB 2/3) |
| [Apache Commons Net](https://commons.apache.org/proper/commons-net/) | 3.13.0 | Apache-2.0 | FTP and FTPS |
| [sshj](https://github.com/hierynomus/sshj) | 0.41.2 | Apache-2.0 | SFTP |
| [OkHttp](https://square.github.io/okhttp/) | 5.5.0 | Apache-2.0 | WebDAV |
| [Bouncy Castle](https://www.bouncycastle.org/) (bcprov, bcpkix, bcutil) | 1.86 | MIT (Bouncy Castle Licence) | cryptography for smbj and sshj |

Tests only (not shipped): [Apache FtpServer](https://mina.apache.org/ftpserver-project/) 1.2.1 and
[Apache MINA SSHD](https://mina.apache.org/sshd-project/) 2.20.0, both Apache-2.0, OkHttp's MockWebServer (Apache-2.0), [WsgiDAV](https://github.com/mar10/wsgidav) (MIT) and Samba's `smbd`
started on the machine running the integration tier.
