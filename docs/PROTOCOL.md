# SyncPad wire protocol, version 1

Everything except Bluetooth mode uses this protocol. Bluetooth mode is plain
HID: the phone is a standard mouse/keyboard/gamepad and no host software runs.

## Ports

| Port | Use |
|---|---|
| TCP 47800 | Control, screen and file channels |
| UDP 47800 | Low-latency pointer packets |
| UDP 47801 | Discovery |

All integers are big-endian.

## Pairing

The host shows a link (QR code, or sent over a USB cable with adb):

    syncpad://pair?v=1&h=<hostId hex8>&d=<deviceId hex8>&k=<key hex32>&n=<name>&p=47800&a=<ip,ip,...>

`k` is a fresh 256-bit key. The first successful handshake stores the device on
the host and the host replaces the link, so a link pairs exactly one phone. It
also expires after 10 minutes.

## Discovery

Phone broadcasts `SPD?` to UDP 47801. Host answers `SPD!` + JSON
`{"h": hostId, "p": port}`, to nearby addresses only. Nothing in it is secret or names the laptop; a phone only
trusts a host after the handshake proves the host has the key.

## TCP handshake

    phone -> host   "SPD1" | kind u8 | deviceId 8 | clientNonce 16
    host  -> phone  "SPD1" | hostNonce 16          (or closes the socket)

kind: 1 control, 2 screen, 3 file.

Keys: HKDF-SHA256(ikm = k, salt = clientNonce | hostNonce, info = label), 32
bytes each, labels `syncpad1 c2s`, `syncpad1 s2c`, `syncpad1 udp c2s`,
`syncpad1 udp s2c`.

## TCP frames

`length u32 | ciphertext`, AES-256-GCM. The nonce is 4 zero bytes plus a u64
counter per direction that starts at 0 and is never sent. The length bytes are
the AAD. Max frame 4 MiB (64 KiB for the phone's frames on control).

Plaintext starting with `{` is JSON, anything else is binary.

First phone frame: `{"t":"hello","model":...}`. Host answers
`{"t":"welcome","sid":u32,"name":...,"udp":47800,"h":hostId}`.

## UDP packets

`sid u32 | seq u64 | ciphertext`, AES-256-GCM, nonce = 4 zero bytes + seq, AAD =
the first 12 bytes. seq starts at 1 and strictly increases.

## Binary messages (TCP or UDP)

    state   01 | q u32 | x i32 | y i32 | sx i32 | sy i32
    button  02 | (same as state) | button u8 | down u8        (TCP only)
    uping   09 | ts u64          (UDP; host echoes it back)

**x, y, sx, sy are running totals, not deltas.** x/y are in 1/64 pixel, sx/sy
in wheel units (120 = one notch), all wrapping at 32 bits. The host applies
the difference from the last total it saw and ignores anything whose `q` is not
newer. So a lost UDP packet costs nothing: the next one already contains its
movement. A button message carries the totals too, so a click always lands
exactly where the cursor was when the finger tapped, even though it travels
over a different transport. Totals and `q` start at zero for every control connection. Phone sends state by UDP while the host answers
`uping`, otherwise by TCP.

## JSON messages, phone to host

| t | fields |
|---|---|
| ping | ts; host answers pong with the same ts |
| key | k name, d 1 down / 0 up / 2 tap |
| combo | keys [names] pressed in order, released in reverse |
| text | s |
| abs | x, y in 0..65535 of monitor m |
| zoom | d wheel units, sent as Ctrl+wheel |
| open | v URL, file or program |
| sys | a: lock, sleep, monitor_off |
| clip | s text |
| clipget | host answers with clip |
| pad | gamepad: b bitmask, lx ly rx ry -1..1, lt rt 0..1 |
| link | cable: true when this network is the USB cable (tethering) |
| record | ask for one shortcut pressed on the laptop's keyboard; recordstop cancels |
| opt | pointer: "precise" or "raw"; smooth: 0..1; clipsync: bool |

Host to phone: `pong`, `clip {s}`, `offer {id,name,size}`, `notice {s}`,
`bye {why}`, `recorded {keys}` or `recorded {err}`, `ask {rid,op,path,...}`.

If the phone sends nothing for 4 s, or the socket drops, the host releases every
key and button that session was holding.

## Screen channel

After hello: phone sends `{"t":"next","w":maxWidth,"q":quality,"m":monitor}`;
host answers with one binary frame
`10 | cursorX u16 | cursorY u16 | width u16 | height u16 | monitors u8 | JPEG`.
One request, one frame: a slow link lowers the frame rate and never builds delay.

## File channel

Phone to host: `{"t":"put","name","size"}`, host `{"t":"go"}`, then binary frames
`20 | bytes`, then host `{"t":"done","name"}`.
Host to phone: `{"t":"get","id"}`, host `{"t":"go","name","size"}`, then
binary frames `20 | bytes`, `{"t":"done"}`, then phone `{"t":"saved"}`. The host repeats an
offer on every connection until it gets `saved`.

### Laptop files

The phone sends `{"t":"fs","op":...}` repeatedly on one file channel, one answer each:
`{"t":"ok",...}` or `{"t":"err","s"}`.

| op | fields | answer |
|---|---|---|
| roots | | folders, drives |
| list | path, hidden | path, parent, items of {n, d, s, m, h}, more |
| search | path, q | items with `in` |
| thumb | path, w | binary `21 \| JPEG` |
| text | path | s, more |
| open, reveal, trash | path | |
| mkdir, rename | path, name | |
| get | path | `go`, data frames, `done`; a folder arrives as a zip with size -1 |
| put | path, name, size | as `put` above |

Only plain `X:\\` paths are accepted. Deleting means the Recycle Bin, or it is refused.

### Phone files

The laptop sends `ask {rid, op, path}` on the control channel. The phone opens a file channel and starts it with
`{"t":"reply","rid",...}`, with `err` if it refuses. Ops: `list` (items), `get` (size, then data frames, `done`), `put` (then the
laptop sends data frames and `done`, and the phone answers `saved`). The phone refuses everything unless its owner has
turned this on, and only answers while SyncPad is open.
