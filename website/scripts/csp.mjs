// Writes the Content-Security-Policy line for Nginx after a build. The live
// policy is script-src 'self', and Next puts its hydration data in inline
// scripts, so under that policy every page loads as dead HTML: no 3D hero, no
// switcher, no theme toggle. 'unsafe-inline' would undo the point of the policy,
// so instead every inline script in out/ is hashed and the hashes are listed.
// They change on every build, which is why this runs after one and the line is
// installed with the files it was made from, never on its own.
import { createHash } from "node:crypto"
import { readdirSync, readFileSync, writeFileSync } from "node:fs"
import { join } from "node:path"
import { fileURLToPath } from "node:url"

const out = fileURLToPath(new URL("../out/", import.meta.url))
const pages = readdirSync(out, { recursive: true }).filter((f) => f.endsWith(".html"))

const hashes = new Set()
for (const page of pages) {
  const html = readFileSync(join(out, page), "utf8")
  for (const [, attrs, body] of html.matchAll(/<script(?![^>]*\bsrc=)([^>]*)>([\s\S]*?)<\/script>/g)) {
    // JSON-LD is data, not script, and a CSP never blocks it
    if (attrs.includes("application/ld+json")) continue
    hashes.add(`'sha256-${createHash("sha256").update(body).digest("base64")}'`)
  }
}

// The live policy from website/deploy/nginx/snippets/nightbell/security-headers.conf
// with only script-src widened.
const policy = [
  "default-src 'self'",
  `script-src 'self' ${[...hashes].sort().join(" ")}`,
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self'",
  "media-src 'self'",
  "font-src 'self'",
  "connect-src 'self'",
  "manifest-src 'self'",
  "object-src 'none'",
  "frame-src 'none'",
  "worker-src 'none'",
  "frame-ancestors 'none'",
  "form-action 'none'",
  "base-uri 'none'",
].join("; ")

const file = new URL("../csp.generated.conf", import.meta.url)
writeFileSync(file, `add_header Content-Security-Policy "${policy}" always;\n`)
console.log(`csp: ${hashes.size} inline script hashes from ${pages.length} pages`)
