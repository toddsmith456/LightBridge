# Security posture

LightBridge has two transfer paths. They have different threat models, and the app is built so the
weaker one cannot be mistaken for the stronger one.

| | Optical (Send / Receive) | Direct link (Link tab) |
| --- | --- | --- |
| Transport | Animated QR codes → camera | Wi-Fi Direct socket between the two devices |
| Confidentiality | **None.** Anything that can see the screen can receive the file. | AES-256-GCM, unique key per session |
| Authenticity | SHA-256 proves the bytes are intact, not who sent them. | ECDSA P-256 identity keys + a six-digit SAS both sides confirm |
| Network permission | Not used. Works in airplane mode. | `INTERNET`, required by Android for any socket |
| Who can receive | Anyone within camera range | Only the device holding the matching private key |

The UI says this out loud: the Send tab carries a permanent "Offline, not encrypted" note, and Home
offers the encrypted path for anything sensitive.

## Why the app requests the network permission

It used to request no network permission at all. Wi-Fi Direct ended that, and not by choice:
Android's own documentation states that Wi-Fi Direct "doesn't require an internet connection, but it
does use standard Java sockets, which requires the INTERNET permission." Without it the platform
fails the socket with `EPERM`.

Granting the permission unconditionally would be sloppy. The mitigation is a runtime invariant.

### The address invariant

`dev.lightbridge.link.LinkAddressPolicy` is consulted before every outbound connection and on every
inbound one (the host may fall back to binding the wildcard address, so a stranger who finds the port
is closed out and the listener keeps waiting for the real peer). It accepts only
literal addresses inside the ranges a Wi-Fi Direct group can actually use:

- `192.168.49.0/24` (the group owner is always `192.168.49.1`)
- `169.254.0.0/16` (link-local IPv4)
- `fe80::/10`, with any interface scope stripped (link-local IPv6)

It rejects everything else, and — this is the part that matters — **it rejects names**. Not
"resolves names and then checks the result": a hostname, a DNS-style literal, an abbreviated form,
an octal/hex-encoded address or a scoped non-link-local IPv6 address never reaches a resolver,
because `InetAddress.getByName` is only ever called on strings that already passed the literal
check. The link module's unit tests cover each of those cases (`AddressPolicyTest`), including the
ones designed to look acceptable at a glance.

So the network permission is real, but the only reachable endpoints are the two paired devices.

## What the direct link guarantees

- **Forward secrecy per session.** The session key comes from an ephemeral ECDH P-256 exchange; the
  long-term identity key only signs it, so a future key compromise does not decrypt past transfers.
- **No unverified bytes reach the recipient's inbox.** The transport stays closed — no offers, no
  chunks — until both sides have sent `Confirm(sas)` over the encrypted channel. A relay attacker who
  sits between the two devices sees different keys on each side and therefore a different six-digit
  code than either human does; the mismatch is what the confirmation step is for.
- **Replay and reordering are structurally impossible.** Every record carries a length, a direction
  byte and a counter in its AES-GCM associated data. A record that is out of order, repeated, or
  reflected back at the sender fails authentication and tears the session down.
- **Files land somewhere private.** Received files go to the app's own `filesDir` (never the shared
  store), with `allowBackup=false` so no copy reaches cloud backup, and filenames sanitised before
  they touch the filesystem.
- **Contacts are just keys.** A contact is a name, an address and a public key held in the app's
  private preferences. Nothing about a contact is sent anywhere; forgetting a contact deletes it.

## What it does not guarantee

- **If the SAS is skipped, the guarantee is gone.** The comparison is the authentication step. Two
  people who tap "They match" without looking have an encrypted channel to *someone*.
- **A malicious peer can send a malicious file.** LightBridge verifies integrity, not intent. It
  never auto-opens what it receives.
- **The optical path is not private** and is not meant to be.
- **Two-device interop is verified by hand.** Wi-Fi Direct cannot be exercised on emulators; see
  `docs/RELEASE-CHECKLIST.md` for the physical two-device checklist.
