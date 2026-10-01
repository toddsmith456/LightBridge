// SPDX-License-Identifier: MIT
// Usage: node --experimental-transform-types tools/generate_interop_fixtures.mjs /path/to/v0.3.0/archive
// The reference archive must be Decimen commit 29cba8fa25dd160c8b6aa18fe3b48fbc5bde2e36 (MIT).
import { readFileSync, writeFileSync, mkdtempSync, mkdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
const root = process.argv[2];
if (!root) throw new Error('Pass the extracted MIT v0.3.0 source directory.');
if (JSON.parse(readFileSync(join(root, 'package.json'))).version !== '0.3.0' ||
    !readFileSync(join(root, 'LICENSE'), 'utf8').startsWith('MIT License')) throw new Error('Expected MIT v0.3.0.');
const temp = mkdtempSync(join(tmpdir(), 'lightbridge-reference-'));
try {
    writeFileSync(join(temp, 'package.json'), '{"type":"module"}');
    writeFileSync(join(temp, 'protocol.ts'), readFileSync(join(root, 'shared/protocol.ts')));
    writeFileSync(join(temp, 'fountain.ts'), readFileSync(join(root, 'shared/fountain.ts'), 'utf8').replace('"./protocol"', '"./protocol.ts"'));
    const { packFile, packFrame, fnv1a } = await import(pathToFileURL(join(temp, 'protocol.ts')));
    const { LTEncoder } = await import(pathToFileURL(join(temp, 'fountain.ts')));
    const destination = resolve('transfer/src/test/resources/decimen-v0.3.0');
    mkdirSync(destination, { recursive: true });
    for (const compress of [false, true]) {
        let seed = 731;
        const source = compress ? new TextEncoder().encode('Light through a camera.\n'.repeat(1000)) :
            Uint8Array.from({length: 2049}, () => { seed = Math.imul(seed, 1664525) + 1013904223; return seed >>> 24; });
        const packed = await packFile('résumé.bin', 'application/octet-stream', source);
        const encoder = new LTEncoder(packed.container, 127, 4242);
        const frames = [];
        for (let seq = 0; seq < encoder.k * 6 + 50; seq++) {
            frames.push(packFrame({sessionId: 4242, seq, k: encoder.k, blockLen: 127,
                totalLen: packed.container.length, payloadFnv: fnv1a(packed.container)}, encoder.encode(seq)));
        }
        const name = compress ? 'gzip' : 'binary';
        writeFileSync(join(destination, `${name}.source`), source);
        writeFileSync(join(destination, `${name}.container`), packed.container);
        writeFileSync(join(destination, `${name}.frames`), Buffer.concat(frames));
        console.log(`${name}: ${source.length} bytes, ${packed.compression}, K=${encoder.k}, ${frames.length} frames`);
    }
} finally { rmSync(temp, {recursive: true, force: true}); }
