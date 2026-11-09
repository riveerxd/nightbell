// Tells Bing, through IndexNow, which pages a deploy actually changed.
//
// Bing is where the long, specific searches for this site show up, and it
// otherwise recrawls a small site every week or two. IndexNow asks for the URLs
// that changed and nothing else, and a build changes every page's script hashes
// whether or not a word moved, so a page counts as changed only when its title,
// its description or its Markdown copy differs from what is live right now.
//
//   node scripts/indexnow.mjs changed https://nightbell.app > urls.txt   # before the switch
//   node scripts/indexnow.mjs submit urls.txt [--dry-run]              # after it
//
// Neither step may fail a deploy. Both print a warning and exit 0, because the
// site going live cannot depend on Bing answering.
import { createHash } from "node:crypto"
import { existsSync, readFileSync } from "node:fs"
import { fileURLToPath } from "node:url"

import { INDEXNOW_KEY as KEY, SITE_URL } from "../src/lib/site.ts"

const ENDPOINT = "https://api.indexnow.org/indexnow"

const out = fileURLToPath(new URL("../out/", import.meta.url))

const fileFor = (path, ext) => `${out}${path === "/" ? "index" : path.slice(1)}.${ext}`
const mdPath = (path) => (path === "/" ? "/index.md" : `${path}.md`)

function head(html) {
  const title = html.match(/<title>([\s\S]*?)<\/title>/)?.[1] ?? ""
  const description = html.match(/<meta name="description" content="([^"]*)"/)?.[1] ?? ""
  return `${title}\n${description}`
}

const fingerprint = (html, md) => createHash("sha256").update(head(html)).update("\n").update(md).digest("hex")

function sitemapPaths() {
  const xml = readFileSync(`${out}sitemap.xml`, "utf8")
  return [...xml.matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => new URL(m[1]).pathname.replace(/\/$/, "") || "/")
}

async function fetchText(url) {
  const res = await fetch(url, { headers: { "user-agent": "nightbell-deploy" }, signal: AbortSignal.timeout(10_000) })
  return res.ok ? res.text() : null
}

async function changed(origin) {
  const urls = []
  for (const path of sitemapPaths()) {
    const html = readFileSync(fileFor(path, "html"), "utf8")
    const md = existsSync(fileFor(path, "md")) ? readFileSync(fileFor(path, "md"), "utf8") : ""
    let live = null
    try {
      const liveHtml = await fetchText(origin + path)
      const liveMd = md ? await fetchText(origin + mdPath(path)) : ""
      if (liveHtml !== null && liveMd !== null) live = fingerprint(liveHtml, liveMd)
    } catch (e) {
      console.error(`indexnow: could not read ${origin}${path} (${e.message}), counting it as changed`)
    }
    if (live !== fingerprint(html, md)) urls.push(SITE_URL + (path === "/" ? "/" : path))
  }
  return urls
}

async function submit(file, dryRun) {
  const urlList = readFileSync(file, "utf8").split("\n").map((l) => l.trim()).filter(Boolean)
  if (!urlList.length) {
    console.log("indexnow: no page changed, nothing to submit")
    return
  }
  const body = { host: new URL(SITE_URL).host, key: KEY, keyLocation: `${SITE_URL}/${KEY}.txt`, urlList }
  if (dryRun) {
    console.log(JSON.stringify(body, null, 2))
    return
  }
  const res = await fetch(ENDPOINT, {
    method: "POST",
    headers: { "content-type": "application/json; charset=utf-8" },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(15_000),
  })
  // 200 and 202 both mean accepted; 202 is a key IndexNow has not verified yet.
  if (res.status === 200 || res.status === 202) console.log(`indexnow: ${res.status}, submitted ${urlList.length}: ${urlList.join(" ")}`)
  else console.error(`indexnow: warn, ${res.status} ${await res.text().catch(() => "")}`)
}

const [cmd, arg, flag] = process.argv.slice(2)
try {
  if (cmd === "changed") console.log((await changed(arg ?? SITE_URL)).join("\n"))
  else if (cmd === "submit") await submit(arg, flag === "--dry-run")
  else console.error("usage: indexnow.mjs changed <origin> | submit <file> [--dry-run]")
} catch (e) {
  console.error(`indexnow: warn, ${e.message}`)
}
