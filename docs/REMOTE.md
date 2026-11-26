# Reaching the laptop from another network

The goal: the phone and laptop on different Wi-Fi networks, or the phone on
mobile data, with the lowest latency possible, for free, inside the two apps,
with no account and nothing extra to install.

## What decides latency

Once two devices are talking, the only thing that matters is the path the
packets take. A **direct** path between the two networks is as fast as the
internet allows; nothing can beat it. A **relayed** path adds the trip to the
relay and back. So the best method is the one that:

1. gets a direct path as often as possible (UDP hole punching through both routers),
2. falls back to a relay when it cannot, so it always works,
3. keeps trying to upgrade from relayed to direct,
4. carries the pointer as unreliable datagrams, so a lost packet never delays
   the next one (SyncPad already sends running totals, so a lost packet costs nothing).

## The options

| | Direct path | Fallback | Account / login | Inside our apps | Datagrams | Cost |
|---|---|---|---|---|---|---|
| **iroh** | yes, hole punching with QUIC multipath | public relays, then upgrades to direct | none, dial by public key | yes: Python wheel and Android AAR | yes | free (public relays for hobby use) |
| Tailscale (tsnet / libtailscale) | yes, very good | DERP relays | needs a Tailscale account, or running Headscale | Go library; no Python or Kotlin build | through a whole VPN stack | free tier, but a login |
| WebRTC data channels | yes (ICE) | TURN, which nobody gives away | none, but needs a signalling server we would run | large native library on both | yes (unreliable channels) | TURN and signalling servers |
| Own hole punching (STUN + UDP) | yes on most home routers | none: fails on strict NAT and many mobile networks | none, but needs a rendezvous server | small | yes | a server we would run |
| AnyDesk / TeamViewer style | their protocol, their servers | their relays | account | closed | n/a | not available to embed |

AnyDesk and similar products reach their latency the same way: direct UDP when
possible, their own relays otherwise, and a codec tuned for screens. The
network part is what iroh provides openly.

## Choice: iroh

- Same direct-path latency as anything else, and the most robust fallback for free.
- No login: each device has a key pair; the laptop's id travels to the phone when
  they first connect at home, and the phone's id goes back the same way.
- It ships ready-made for both of our platforms: `iroh` on PyPI for the laptop,
  `computer.iroh:iroh-android` on Maven Central for the phone. No Rust toolchain.
- QUIC datagrams for the pointer, streams for everything else.

Costs: the Android download grows by about 6 MB (one 64-bit library), the
Windows app by about 15 MB. The public relays are shared and rate limited; if
SyncPad grows, an own relay (`iroh-relay`, one small server) can replace them
with one setting.

## How it is built

```
phone app                                              laptop app
Link ──TCP──▶ 127.0.0.2:port ┐                       ┌─▶ server on 127.0.0.1
Link ──UDP──▶ 127.0.0.2:port ┤ IrohTunnel ═ iroh ═ Remote ┤   (seen from 127.0.0.2,
                             ┘  streams + datagrams  └─▶  so it says "Internet")
```

- `host/syncpad/remote.py`: the laptop's endpoint. It checks every caller against
  the paired phones' ids, then bridges each stream to a TCP connection and the
  datagrams to UDP on its own server.
- `android/.../net/IrohTunnel.kt`: the phone's endpoint, offered to `Link` as an
  ordinary loopback address. `Link` tries USB and the local network first and
  uses the tunnel only when nothing nearby answers; while on the tunnel it keeps
  checking whether the laptop is nearby again.
- SyncPad's own AES-256-GCM protocol runs unchanged inside iroh's encryption.
- Tests: `host/tests/test_remote.py` and `android/.../RemoteTest.kt` run both
  ends through iroh against the real laptop app.

## Not yet measured

Both ends were tested on one computer, where iroh finds a direct path. Phone on
mobile data against a laptop at home is the real test; the phone shows
"Connected over Internet" and adds "relayed" when it is not direct.
