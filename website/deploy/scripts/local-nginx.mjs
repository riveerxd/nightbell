// Serves out/ through the production nginx config, in Docker, on
// http://localhost:4180, so the e2e suite can run against what nginx does
// rather than what `npx serve` does. The two differ in exactly the places that
// break in production: try_files, the .html redirect, headers dropped by an
// add_header in a location, and the Accept negotiation for the Markdown copies.
//
//   npm run build
//   node deploy/scripts/local-nginx.mjs          # starts it, replacing an old one
//   E2E_BASE_URL=http://localhost:4180 npx playwright test --project=desktop-chrome
//   node deploy/scripts/local-nginx.mjs --stop
//
// The config is the file deploy.sh installs, cut down only where a laptop has
// to differ from the VPS: no TLS, a different port and root, and the two server
// blocks that redirect to the real one left out. The snippets are built the way
// deploy.sh builds them, with this build's CSP and download target.
//
// nginx 1.18 because that is what the VPS runs, and `if`, `map` and
// `charset_types` have all changed behaviour at least once since.
import { execFileSync } from "node:child_process"
import { mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { join } from "node:path"
import { fileURLToPath } from "node:url"

const NAME = "nightbell-nginx-local"
const PORT = 4180
const IMAGE = "nginx:1.18"

const site = fileURLToPath(new URL("../../", import.meta.url))
const deploy = join(site, "deploy")

const docker = (...args) => execFileSync("docker", args, { stdio: ["ignore", "pipe", "inherit"] }).toString()

try {
  docker("rm", "-f", NAME)
} catch {}
if (process.argv.includes("--stop")) process.exit(0)

const conf = readFileSync(join(deploy, "nginx/nightbell.app.conf"), "utf8")

// Split into top-level server blocks and everything between them, by braces.
// Comments in this file quote nginx syntax with braces in them, so comment lines
// are passed through without being counted.
const kept = []
let block = null
let depth = 0
for (const line of conf.split("\n")) {
  const code = line.replace(/#.*/, "")
  if (block === null && /^server\s*\{/.test(code)) {
    block = []
    depth = 0
  }
  if (block === null) {
    kept.push(line)
    continue
  }
  block.push(line)
  depth += (code.match(/\{/g) ?? []).length - (code.match(/\}/g) ?? []).length
  if (depth === 0) {
    if (block.some((l) => /^\s*server_name\s+nightbell\.app;/.test(l))) kept.push(...localise(block))
    block = null
  }
}

function localise(lines) {
  const out = []
  for (const line of lines) {
    if (/^\s*listen\s/.test(line)) {
      if (!out.some((l) => l.includes(`listen ${PORT};`))) out.push(`    listen ${PORT};`)
      continue
    }
    if (/^\s*(ssl_\w+|include\s+\/etc\/letsencrypt)/.test(line)) continue
    out.push(line.replace(/^(\s*root\s+)\/var\/www\/nightbell\.app\/current;/, "$1/srv/out;"))
  }
  return out
}

const tmp = join(site, "node_modules/.cache/nightbell-nginx")
rmSync(tmp, { recursive: true, force: true })
mkdirSync(join(tmp, "snippets"), { recursive: true })
mkdirSync(join(tmp, "log"), { recursive: true })
writeFileSync(join(tmp, "nightbell.conf"), kept.join("\n"))

const headers = readFileSync(join(deploy, "nginx/snippets/nightbell/security-headers.conf"), "utf8")
  .split("\n")
  .filter((l) => !/^add_header Content-Security-Policy /.test(l))
  .join("\n")
writeFileSync(join(tmp, "snippets/security-headers.conf"), headers + "\n" + readFileSync(join(site, "csp.generated.conf"), "utf8"))
writeFileSync(join(tmp, "snippets/download-target.conf"), readFileSync(join(site, "download-target.generated.conf"), "utf8"))
writeFileSync(
  join(tmp, "snippets/cloudflare-real-ip.conf"),
  readFileSync(join(deploy, "nginx/snippets/nightbell/cloudflare-real-ip.conf"), "utf8"),
)

docker(
  "run", "-d", "--name", NAME, "-p", `${PORT}:${PORT}`,
  "-v", `${join(site, "out")}:/srv/out:ro`,
  "-v", `${join(tmp, "nightbell.conf")}:/etc/nginx/conf.d/default.conf:ro`,
  "-v", `${join(tmp, "snippets")}:/etc/nginx/snippets/nightbell:ro`,
  "-v", `${join(tmp, "log")}:/var/log/nginx/nightbell`,
  IMAGE,
)

// nginx -t runs at start; a config that does not parse exits the container.
for (let i = 0; i < 50; i++) {
  try {
    const res = await fetch(`http://localhost:${PORT}/`)
    if (res.ok) {
      console.log(`nginx ${IMAGE.split(":")[1]} serving out/ on http://localhost:${PORT}`)
      process.exit(0)
    }
  } catch {}
  await new Promise((r) => setTimeout(r, 200))
}
console.error(docker("logs", NAME))
process.exit(1)
