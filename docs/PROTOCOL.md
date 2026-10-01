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
