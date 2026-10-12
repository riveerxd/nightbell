// Writes the repository's star count into src/lib/stars.json before a build, so
// the nav can show it without any visitor's browser calling GitHub. If GitHub
// cannot be reached the file is left as it was: a stale count beats a failed
// build or a blank button.
import { readFileSync, writeFileSync } from "node:fs"

const file = new URL("../src/lib/stars.json", import.meta.url)
const old = JSON.parse(readFileSync(file, "utf8"))
try {
  const res = await fetch("https://api.github.com/repos/riveerxd/nightbell", {
    headers: { accept: "application/vnd.github+json", "user-agent": "nightbell.app build" },
    signal: AbortSignal.timeout(8000),
  })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  const stars = (await res.json()).stargazers_count
  if (typeof stars !== "number") throw new Error("no stargazers_count")
  writeFileSync(file, JSON.stringify({ stars }) + "\n")
  console.log(`stars: ${stars}`)
} catch (e) {
  console.warn(`stars: kept ${old.stars}, GitHub said ${e.message}`)
}
