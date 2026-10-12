// Copies the two films into public/media from docs/video, where the repository
// already keeps them. Committing them here as well would put the same 8.6 MB
// into history twice, and history cannot be shrunk afterwards. The site's names
// stay fixed while the source names carry the cut, so a re-render is a change to
// this table and nothing else.
import { copyFileSync, existsSync, readFileSync } from "node:fs"

const FILMS = [
  ["../docs/video/nightbell-promo-v3.mp4", "public/media/nightbell-promo.mp4"],
  ["../docs/video/nightbell-alert-firing-v3.mp4", "public/media/nightbell-alert-firing.mp4"],
]

for (const [from, to] of FILMS) {
  const src = new URL(`../${from}`, import.meta.url)
  const dst = new URL(`../${to}`, import.meta.url)
  if (!existsSync(src)) throw new Error(`media: ${from} is missing, and the page links to ${to}`)
  if (existsSync(dst) && readFileSync(src).equals(readFileSync(dst))) continue
  copyFileSync(src, dst)
  console.log(`media: ${to} from ${from}`)
}
