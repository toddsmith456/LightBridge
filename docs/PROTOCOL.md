# Wire format and trust boundaries

Reference: Decimen Optical Transfer v0.3.0 (MIT), commit
29cba8fa25dd160c8b6aa18fe3b48fbc5bde2e36. Ports and upstream golden vectors are attributed
in source headers and NOTICE. No current AGPL source is incorporated.

## Frame, little endian

| Offset | Type | Meaning |
| --- | --- | --- |
| 0 | 2 bytes | `D1 0C` |
| 2 | u16 | random session ID |
| 4 | u32 | sequence (Kotlin Int retains all 32 bits) |
| 8 | u16 | source-block count |
| 10 | u16 | bytes per block |
| 12 | u32 | container size |
| 16 | u32 | container FNV-1a |
| 20 | bytes | XOR fountain payload |

QR is byte mode, ECC L, automatically selected mask, four-module quiet zone.
The mask is declared inside each QR symbol and is not part of the fountain protocol.
We use ZXing’s full mask evaluation rather than pinning mask 0, to improve detection
of heavily padded frames with this native scanner. The app uses ISO-8859-1 to map
bytes into ZXing's encoder without Base64. The receiver extracts BYTE_SEGMENTS, **not
text or QR raw codewords**. Multiple segments are concatenated. Camera frames use the
Y plane with row/pixel strides respected. QR finder detection handles orientation.

The source subset is determined by session+sequence, splitmix32, the pinned deterministic
log, robust-soliton CDF, and either rejection sampling or partial Fisher–Yates. Floating
point operation order is compatibility-critical. Tests pin published upstream fingerprints.
There is no handshake or acknowledgment. The native receiver locks to the first valid
stream and ignores other identities until explicit reset to protect partial progress.

## DCF2 container

`DCF2` magic (4), compression flag (1), UTF-8 name length (u16), UTF-8 MIME length (u16),
original size (u32), transmitted size (u32), SHA-256 (32), name, MIME, payload. Gzip is
chosen only if it saves more than 64 bytes. Verify size, gzip bound, FNV, and SHA-256
before exposing a file. Filenames are reduced to safe basenames and length-limited.
Internal files use UUIDs, never sender-controlled filesystem paths.

## Direct link (Link tab)

The optical path above is a one-way broadcast: the receiver never talks back, so there is nothing to
authenticate against and anyone with a camera can decode it. The Link tab is the other half of the
app — a two-way, authenticated, encrypted channel between exactly two paired devices, carried over a
Wi-Fi Direct socket. `docs/SECURITY.md` covers the threat model; this section covers the wire.

Everything below is implemented in the dependency-free `:link` module, so CI runs it as plain JVM
code (`./gradlew :link:test`, 65 tests).

### 1. Pairing (the QR code)

The host creates a Wi-Fi Direct group, binds a listener, and shows a single QR code:

```
LBWIFI1:<base64url of ConnectPayload>
```

`ConnectPayload` carries the session id (16 random bytes), the group SSID, the group passphrase, the
host's Wi-Fi Direct device address, the owner address and port, the host's **ephemeral** public key,
and the host's **long-term** identity public key plus its fingerprint. Nothing in it is secret: the
QR code is how the joiner learns where to connect, and the passphrase only saves the joiner from
having to negotiate the group itself.

A contact paired this way is stored as `{name, address, identity key, fingerprint, trust}` in the
app's private preferences, so a later transfer needs no QR code at all.

### 2. Handshake (plaintext, then not)

Two records — `u16` length prefix, then the body — are exchanged *before* the secure channel opens,
because there is not yet a key to encrypt them with:

1. **Hello** (joiner → host): session id, joiner ephemeral public key, joiner identity public key,
   joiner device name, and an ECDSA signature over the transcript so far.
2. **Accept** (host → joiner): the host's identity public key and its signature over the same
   transcript, which includes the joiner's ephemeral key.

Both sides then compute the shared secret with ephemeral ECDH (P-256), run it through HKDF-SHA256,
and derive **two directional AES-256-GCM keys** — one per direction, so a record reflected back at
its sender cannot authenticate. The long-term identity keys never encrypt anything; they only sign,
which is what gives each session forward secrecy.

The handshake is also where a joiner pinned in the contacts list is checked: if the scanned key does
not match the stored key for that device name, the session stops before a byte of file data exists.

### 3. The six-digit SAS

Both sides derive a six-digit short authentication string from the handshake transcript and display
it. **No offer, chunk, or verification record is sent until each side has received the other's
`Confirm(sas)`.** A relay that terminates the link on both ends cannot avoid this: it has to run
separate key exchanges with each device, so the two humans see two different codes. The comparison,
not the cryptography, is what turns "encrypted to somebody" into "encrypted to the right device".

### 4. Records after the channel opens

Every record is `u32` length + `u8` direction + `u64` counter, with the length, direction and counter
all inside the AES-GCM associated data. Consequences, all enforced rather than hoped for:

- reordering is rejected, so a relay cannot shuffle or replay chunks;
- the counter is part of the tag, so a chunk cannot be relocated to another position in the file;
- a record that fails authentication tears the session down instead of being skipped.

### 5. Messages

| Message | Direction | Meaning |
| --- | --- | --- |
| `Hello` / `Accept` | both | handshake, see above |
| `Confirm(sas)` | both | "the digits match on my screen" |
| `Offer` | sender → receiver | name, size, MIME, SHA-256 |
| `Accept` / `Decline` | receiver → sender | receiver's decision, including its own disk limits |
| `Chunk` | sender → receiver | file bytes, bounded by the same size ceilings as the optical path |
| `Complete` | sender → receiver | last chunk, so the receiver can hash |
| `Verified` | receiver → sender | hash matched and the file is in the inbox |
| `Failure` | either | human-readable reason, ends the session |
| `Bye` | either | orderly close |

The receiver writes directly into the app's private inbox (`filesDir/received`) with a sanitised
filename, so a received file appears in the Inbox tab alongside camera-received ones, tagged with
which path it arrived by. A `Verified` record is only sent after the SHA-256 of what landed on disk
matches the `Offer`.

### 6. Network reach

`LinkAddressPolicy` accepts only literal addresses in `192.168.49.0/24`, `169.254.0.0/16` or
`fe80::/10`, and rejects hostnames, scoped addresses, ports, and URL-shaped strings outright. The
inbound side re-checks every accepted socket against the same policy, because the host may fall back
to binding the wildcard address when the P2P interface does not exist yet. This is what makes the
`INTERNET` permission the manifest has to declare a permission the app cannot actually use for
anything but the two paired devices.

## Resource and integrity limits

- Original file ≤64 MiB; container ≤64 MiB + maximum protocol metadata.
- QR payload block ≤2933 bytes, K≤65535; K must equal ceil(total / block).
- Gzip counts actual output bytes; never trusts the trailer as an allocation bound.
- Deduplication ≤max(10,000, 12K) frames; pending graph ≤2 million edges and ≤96 MiB
  equation byte buffers (object overhead and solved blocks require additional RAM).
- Camera queue holds only latest image; ingestion channel holds at most 16 QR payloads.
- Verified inbox ≤256 MiB; requires 16 MiB spare filesystem space when storing a file.
- Android admission limit is min(64 MiB, max heap / 8), shown on Send. Expanded size is
  checked against this limit before inflation. App graph limit is 500,000 edges and the
  same heap-derived pending-byte budget; protocol defaults above are for JVM consumers.
- Android process memory and filesystem quota remain additional platform constraints.

FNV detects reconstruction mistakes; SHA-256 detects payload corruption. Neither is
sender authentication. The optical channel has no confidentiality. Never auto-execute
or automatically open a received file. App keeps only verified bytes, not camera images.
