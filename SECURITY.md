# Security policy

Do not use public issues for sensitive vulnerability details. Contact the repository
maintainer through GitHub's private vulnerability reporting when available.

This app is offline, not encrypted or authenticated. A QR sender is untrusted input.
All input lengths, frame dimensions, decompression output and filenames are checked.
The receiver verifies the container checksum and file SHA-256 before persistence.
An attacker controlling both payload and hash can still send any file; do not open
unknown files without appropriate precautions. Hash verification is not a signature.

No INTERNET permission, telemetry, camera recordings, cloud backup, broad storage access,
or exported file provider. Only the launcher activity is exported. FileProvider shares
only the received-file subtree with per-URI read grants. Android or a chosen document
provider/sharing target may independently use a network.

Partial transfers reside in process memory. Completed files reside in private storage.
Uninstalling removes the inbox; explicit exports are outside app control. Deletion does
not guarantee secure erasure. A hostile stream can consume bounded resources or waste
time; users can reset. Very large transfers may exceed low-RAM device capabilities.

Before distribution: execute docs/RELEASE-CHECKLIST.md, audit dependency advisories,
review licenses, and sign with a private release key. A successful CI build alone is not
a security audit or proof of real-camera interoperability.
