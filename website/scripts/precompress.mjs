// Writes a .gz beside every text file in out/ so nginx's gzip_static can send
// the best compression once instead of gzip level 5 on every request. No .br:
// Ubuntu 22.04's nginx has no brotli module, and Cloudflare re-encodes for
// browsers that want brotli anyway. A .gz that would not be smaller is skipped,
// so nginx falls back to the plain file.
import { readdirSync, readFileSync, statSync, writeFileSync } from "node:fs"
import { join } from "node:path"
import { fileURLToPath } from "node:url"
import { constants, gzipSync } from "node:zlib"

const out = fileURLToPath(new URL("../out/", import.meta.url))
const TEXT = /\.(html|js|css|txt|json|svg|xml|webmanifest|vtt|ico)$/

let files = 0
let before = 0
let after = 0
for (const rel of readdirSync(out, { recursive: true })) {
  const file = join(out, rel)
  if (!TEXT.test(rel) || !statSync(file).isFile()) continue
  const body = readFileSync(file)
  const gz = gzipSync(body, { level: constants.Z_BEST_COMPRESSION })
  if (gz.length >= body.length) continue
  writeFileSync(`${file}.gz`, gz)
  files++
  before += body.length
  after += gz.length
}
console.log(`precompress: ${files} files, ${(before / 1e3).toFixed(0)} kB to ${(after / 1e3).toFixed(0)} kB`)
