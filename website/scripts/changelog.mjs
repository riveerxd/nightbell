// Writes every GitHub release into src/lib/changelog.json before a build, so
// /changelog is the release notes as they were published and not a second copy
// someone has to remember. Step 9 of a release builds the site after the GitHub
// release exists, so the new version arrives on the page with no extra step.
//
// If GitHub cannot be reached the file is left as it was, the way stars.mjs does
// it. What a stale file must not do is ship a site that hands out a version its
// own changelog has never heard of, so the build stops on that instead.
//
// The dates come from the git tags where they exist. The releases before 2.4.3
// were all published to GitHub on one afternoon when the repository moved, and
// their published_at says so; the tags still carry the day each one was cut.
import { execFileSync } from "node:child_process"
import { readFileSync, writeFileSync } from "node:fs"

import { RELEASE, REPO } from "../src/lib/site.ts"

const file = new URL("../src/lib/changelog.json", import.meta.url)
const slug = new URL(REPO.url).pathname.slice(1)

function tagDates() {
  try {
    const out = execFileSync("git", ["for-each-ref", "refs/tags", "--format=%(refname:short) %(creatordate:short)"], {
      encoding: "utf8",
    })
    return new Map(out.trim().split("\n").map((l) => l.split(" ")))
  } catch {
    return new Map()
  }
}

// The older notes were written before the house style banned dashes. They are
// reprinted here as site copy, so the dashes go the way they would in an edit:
// a range reads "1 to 3", anything else becomes a comma. Built from char codes
// so this file does not hold the characters it removes.
const D = `[${String.fromCharCode(0x2013, 0x2014)}]`
function undash(s) {
  return s
    .replace(new RegExp(`(\\d)\\s*${D}\\s*(\\d)`, "g"), "$1 to $2")
    .replace(new RegExp(`\\s*${D}\\s*$`, "gm"), ",")
    .replace(new RegExp(`\\s+${D}\\s+`, "g"), ", ")
    .replace(new RegExp(D, "g"), ", ")
}

// "Nightbell 3.0.5: answering the security review" keeps the part after the
// version; "Pulse 2.2.0" keeps nothing, because the version is printed anyway.
function subtitle(name, version) {
  const rest = name.replace(new RegExp(`^(Nightbell |Pulse )?${version.replace(/\./g, "\\.")}`), "")
  const t = undash(rest).replace(/^[\s:,]+/, "").trim()
  return t.charAt(0).toUpperCase() + t.slice(1)
}

function issues(body) {
  return body.replace(/\b(issues?) #(\d+)/gi, (_, w, n) => `${w} [#${n}](${REPO.url}/issues/${n})`)
}

// Every release up to 3.x carried the same adb install lines, which the site
// already says better on its own page, and 2.4.3 opened by repeating its own
// version as a heading. The rest is printed as it was published.
function trim(body, version) {
  const lines = body.split("\n")
  const out = []
  let skip = 0
  for (const line of lines) {
    const h = /^(#{1,6})\s+(.*)$/.exec(line)
    if (h && skip && h[1].length <= skip) skip = 0
    if (h && /^install(ing)?$/i.test(h[2].trim())) {
      skip = h[1].length
      continue
    }
    if (!skip) out.push(line)
  }
  return out
    .join("\n")
    .replace(new RegExp(`^#{1,6}\\s+(Nightbell |Pulse )?${version.replace(/\./g, "\\.")}\\s*\n+`), "")
    .trim()
}

function cmp(a, b) {
  const x = a.split(".").map(Number)
  const y = b.split(".").map(Number)
  for (let i = 0; i < 3; i++) if (x[i] !== y[i]) return x[i] - y[i]
  return 0
}

async function fetchAll() {
  const all = []
  for (let page = 1; page < 10; page++) {
    const res = await fetch(`https://api.github.com/repos/${slug}/releases?per_page=100&page=${page}`, {
      headers: { accept: "application/vnd.github+json", "user-agent": "nightbell.app build" },
      signal: AbortSignal.timeout(10000),
    })
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const batch = await res.json()
    all.push(...batch)
    if (batch.length < 100) break
  }
  return all
}

let releases
try {
  const dates = tagDates()
  releases = (await fetchAll())
    .filter((r) => !r.draft && !r.prerelease)
    .map((r) => {
      const version = r.tag_name.replace(/^v/, "")
      const apk = r.assets.find((a) => a.name.endsWith(".apk"))
      const body = issues(trim(undash(r.body.replace(/\r\n/g, "\n").trim()), version))
      return {
        version,
        tag: r.tag_name,
        name: Number(version.split(".")[0]) < 3 ? "Pulse" : "Nightbell",
        date: dates.get(r.tag_name) ?? r.published_at.slice(0, 10),
        title: subtitle(r.name, version),
        body,
        url: r.html_url,
        apk: apk ? { name: apk.name, url: apk.browser_download_url, bytes: apk.size } : null,
      }
    })
    .sort((a, b) => cmp(b.version, a.version))
  writeFileSync(file, `${JSON.stringify(releases, null, 1)}\n`)
  console.log(`changelog: ${releases.length} releases, newest ${releases[0].version}`)
} catch (e) {
  releases = JSON.parse(readFileSync(file, "utf8"))
  console.warn(`changelog: kept ${releases.length} releases, GitHub said ${e.message}`)
}

if (!releases.some((r) => r.tag === RELEASE.tag)) {
  console.error(`changelog: ${RELEASE.tag} is what the site hands out, and it has no GitHub release yet.`)
  console.error("Publish the release first (docs/FDROID.md), or build with GitHub reachable.")
  process.exit(1)
}
