#!/usr/bin/env python3
# Writes subtitles.ts: a DVB transport stream with a teletext stream (EN 300 472) of two subtitle pages, 888 in German
# and 777 for the hard of hearing in English, beside a short MPEG-2 picture FFmpeg makes (its clock, the PCR, is what
# FFmpeg times teletext by), as a recording has them: python3 make.py (ffmpeg on the path).
# What it shows, and when:
#   1.0 s  888: "Hallo!" in yellow over "Grüße aus Hamburg"     777: "(Musik)"
#   3.0 s  888: cleared                                          777: "(Applaus)"
#   5.0 s  888: "Bis bald."                                      777: cleared
import struct
import subprocess


def crc32_mpeg2(data):
    crc = 0xFFFFFFFF
    for byte in data:
        crc ^= byte << 24
        for _ in range(8):
            crc = (crc << 1) ^ 0x04C11DB7 if crc & 0x80000000 else crc << 1
            crc &= 0xFFFFFFFF
    return crc


def hamming(value):
    d1, d2, d3, d4 = value & 1, value >> 1 & 1, value >> 2 & 1, value >> 3 & 1
    p1 = 1 ^ d1 ^ d3 ^ d4
    p2 = 1 ^ d1 ^ d2 ^ d4
    p3 = 1 ^ d1 ^ d2 ^ d3
    p4 = 1 ^ p1 ^ d1 ^ p2 ^ d2 ^ p3 ^ d3 ^ d4
    return p1 | d1 << 1 | p2 << 2 | d2 << 3 | p3 << 4 | d3 << 5 | p4 << 6 | d4 << 7


def odd(code):
    return code | 0x80 if bin(code & 0x7F).count("1") % 2 == 0 else code & 0x7F


def sent(byte):
    return int(f"{byte:08b}"[::-1], 2)


def unit(magazine, packet, block):
    block = list(block) + [odd(0x20)] * (40 - len(block))
    address = [hamming((magazine & 7) | (packet & 1) << 3), hamming(packet >> 1)]
    return bytes([0x03, 0x2C, 0xC7, 0xE4] + [sent(b) for b in address + block])


def header(magazine, page, erase, option=0):
    c11_c14 = (option >> 2 & 1) << 1 | (option >> 1 & 1) << 2 | (option & 1) << 3
    nibbles = [page & 0xF, page >> 4, 0, 8 if erase else 0, 0, 8, 0, c11_c14]  # C6: a subtitle page
    return unit(magazine, 0, [hamming(n) for n in nibbles])


# German (C12 C13 C14 = 0 0 1): 7/D is ü, 7/E is ß.
GERMAN = {"ü": 0x7D, "ß": 0x7E}


def row(magazine, number, text, colour=None, double=True):
    codes = ([0x0D] if double else []) + [0x0B, 0x0B] + ([colour] if colour is not None else [])
    codes += [GERMAN.get(c, ord(c)) for c in text] + [0x0A, 0x0A]
    return unit(magazine, number, [odd(c) for c in codes])


def pes(pts_ms, units):
    pts = pts_ms * 90
    pts_bytes = bytes([0x21 | (pts >> 29 & 0x0E), pts >> 22 & 0xFF, 0x01 | (pts >> 14 & 0xFE), pts >> 7 & 0xFF,
                       0x01 | (pts << 1 & 0xFE)])
    units = list(units)
    count = 0
    while (45 + 1 + 46 * count) % 184 != 0 or count < len(units):
        count += 1
    units += [bytes([0xFF, 0x2C]) + b"\xFF" * 44] * (count - len(units))
    data = bytes([0x10]) + b"".join(units)
    head = pts_bytes + b"\xFF" * (0x24 - len(pts_bytes))
    body = bytes([0x84, 0x80, 0x24]) + head + data
    return b"\x00\x00\x01\xBD" + struct.pack(">H", len(body)) + body


counters = {}


def ts_packets(pid, payload, section=False):
    out = b""
    first = True
    if section:
        payload = b"\x00" + payload  # pointer field
    while payload or first:
        chunk = payload[:184]
        payload = payload[184:]
        cc = counters.get(pid, 0)
        counters[pid] = (cc + 1) & 0xF
        head = bytes([0x47, (0x40 if first else 0) | pid >> 8, pid & 0xFF])
        if len(chunk) < 184:
            if section:
                chunk += b"\xFF" * (184 - len(chunk))
                out += head + bytes([0x10 | cc]) + chunk
            else:
                stuffing = 184 - len(chunk) - 1
                adaptation = bytes([stuffing]) + (bytes([0x00]) + b"\xFF" * (stuffing - 1) if stuffing else b"")
                out += head + bytes([0x30 | cc]) + adaptation + chunk
        else:
            out += head + bytes([0x10 | cc]) + chunk
        first = False
    return out


def section(table_id, extension, body):
    length = 5 + len(body) + 4
    data = bytes([table_id, 0xB0 | length >> 8, length & 0xFF]) + struct.pack(">H", extension) + b"\xC1\x00\x00" + body
    return data + struct.pack(">I", crc32_mpeg2(data))


VIDEO_PID = 0x0100
TELETEXT_PID = 0x0101
PMT_PID = 0x1000
pat = section(0x00, 1, struct.pack(">HH", 1, 0xE000 | PMT_PID))
descriptor = bytes([0x56, 10]) + b"deu" + bytes([0x02 << 3 | 0, 0x88]) + b"eng" + bytes([0x05 << 3 | 7, 0x77])
streams = bytes([0x02, 0xE0 | VIDEO_PID >> 8, VIDEO_PID & 0xFF, 0xF0, 0x00])
streams += bytes([0x06, 0xE0 | TELETEXT_PID >> 8, TELETEXT_PID & 0xFF, 0xF0, len(descriptor)]) + descriptor
pmt = section(0x02, 1, struct.pack(">HH", 0xE000 | VIDEO_PID, 0xF000) + streams)

transmissions = [
    (1000, [header(8, 0x88, True, option=1), row(8, 20, "Hallo!", colour=0x03), row(8, 22, "Grüße aus Hamburg"),
            header(7, 0x77, True), row(7, 22, "(Musik)")]),
    (3000, [header(8, 0x88, True), header(7, 0x77, True), row(7, 22, "(Applaus)")]),
    (5000, [header(8, 0x88, True, option=1), row(8, 22, "Bis bald."), header(7, 0x77, True)]),
    # A last, empty transmission: the stream goes on past the last change.
    (6500, []),
]

video = subprocess.run(
    ["ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc=size=160x90:rate=25:duration=7", "-c:v", "mpeg2video",
     "-muxdelay", "0", "-mpegts_start_pid", str(VIDEO_PID), "-f", "mpegts", "-"],
    check=True, capture_output=True,
).stdout

stream = ts_packets(0, pat, True) + ts_packets(PMT_PID, pmt, True)
for at in range(0, len(video), 188):
    packet = video[at:at + 188]
    pid = (packet[1] & 0x1F) << 8 | packet[2]
    if pid != VIDEO_PID:
        continue
    stream += packet
    # Each transmission goes in once the clock has reached it (less the 40 ms EN 300 472 allows).
    if packet[3] & 0x20 and packet[4] > 0 and packet[5] & 0x10:
        pcr_ms = (packet[6] << 25 | packet[7] << 17 | packet[8] << 9 | packet[9] << 1 | packet[10] >> 7) // 90
        while transmissions and pcr_ms >= transmissions[0][0] - 40:
            pts_ms, units = transmissions.pop(0)
            stream += ts_packets(TELETEXT_PID, pes(pts_ms, units))
assert not transmissions, "the picture ended before every transmission went in"
open("subtitles.ts", "wb").write(stream)
