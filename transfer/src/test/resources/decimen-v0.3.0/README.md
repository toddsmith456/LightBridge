Fixtures generated with the original MIT Decimen Optical Transfer v0.3.0 TypeScript
packFile, LTEncoder, and packFrame, commit 29cba8fa25dd160c8b6aa18fe3b48fbc5bde2e36.
Copyright (c) 2026 Evan Crawley (Bash Alarmist); MIT terms in docs/licenses/DECIMEN-MIT.txt.

Reproduce from the tagged archive with:
node --experimental-transform-types tools/generate_interop_fixtures.mjs /path/to/v0.3.0

Node 22.22+ required. Generator changes only the temporary module's import extension
for Node's loader, not protocol logic. Test data is synthetic, not private user files.
Frames are concatenated 147-byte packets (20-byte header, 127-byte fountain block).
Binary input has 2049 pseudo-random bytes. Gzip input has 24,000 bytes of repeated text.
