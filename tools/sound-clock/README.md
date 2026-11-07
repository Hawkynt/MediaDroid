# Sound clock

How steady Java Sound's played position is as the clock the desktop's pictures follow (`JavaSoundSink` in
`engine/desktop`): the same output — 48 kHz, 16-bit stereo, a one-second buffer filled before it starts, silence
written as it plays — its position read every millisecond for ten seconds, against the wall clock.

```sh
java tools/sound-clock/JavaSoundProbe.java
```

It prints how soon the position first moves, the steps it moves in, its rate against the wall clock with how far it
strays from that straight line, whether it stands still while paused, and whether a flush resets it.

Linux 7.2, PipeWire through ALSA's default device, Temurin 21 (`DirectAudioDevice`), 2026-11-06:

```
first move after 5.4 ms
position updates: 938 in 10 s; step median 10667 us, p95 10667 us, max 10667 us
rate against the wall clock: 1.00005; residual rms 3.08 ms, max 5.41 ms
paused 300 ms: position moved 0.00 ms
after flush: position kept (not reset)
```

Not measured yet: Windows (DirectSound), and following a change of the default output device.
