// Full-page and fold screenshots of every route at the widths that matter.
// Usage: node scripts/shots.mjs <outDir> [route ...] [--theme=light] [--fold]
import { chromium } from "@playwright/test"
import { mkdirSync } from "node:fs"

const args = process.argv.slice(2)
const out = args.shift()
const theme = (args.find((a) => a.startsWith("--theme=")) ?? "--theme=dark").split("=")[1]
const fold = args.includes("--fold")
const routes = args.filter((a) => !a.startsWith("--"))
if (!routes.length) routes.push("/")
const widths = (process.env.WIDTHS ?? "1440,390").split(",").map(Number)
mkdirSync(out, { recursive: true })

const browser = await chromium.launch()
for (const w of widths) {
  const ctx = await browser.newContext({
    viewport: { width: w, height: w < 600 ? 844 : 900 },
    deviceScaleFactor: 1,
    isMobile: w < 600,
    hasTouch: w < 600,
    reducedMotion: process.env.REDUCED ? "reduce" : "no-preference",
  })
  await ctx.addInitScript((t) => localStorage.setItem("theme", t), theme)
  const page = await ctx.newPage()
  const errors = []
  page.on("pageerror", (e) => errors.push(String(e)))
  page.on("console", (m) => m.type() === "error" && errors.push(m.text()))
  for (const r of routes) {
    await page.goto(`http://localhost:${process.env.PORT ?? 4173}${r}`, { waitUntil: "networkidle" })
    await page.waitForTimeout(2200)
    const name = `${(r === "/" ? "home" : r.replace(/\//g, "_").replace(/^_/, ""))}-${w}-${theme}`
    if (!fold) {
      // walk the page so in-view animations fire before the full capture
      const h = await page.evaluate(() => document.documentElement.scrollHeight)
      for (let y = 0; y < h; y += 500) {
        await page.evaluate((yy) => window.scrollTo({ top: yy, behavior: "instant" }), y)
        await page.waitForTimeout(120)
      }
      await page.waitForTimeout(1500)
      await page.evaluate(() => window.scrollTo({ top: 0, behavior: "instant" }))
      await page.waitForTimeout(400)
    }
    await page.screenshot({ path: `${out}/${name}.png`, fullPage: !fold })
    const sw = await page.evaluate(() => [document.documentElement.scrollWidth, window.innerWidth])
    console.log(name, "scrollWidth", sw.join("/"), errors.length ? "ERRORS: " + errors.join(" | ") : "")
    errors.length = 0
  }
  await ctx.close()
}
await browser.close()
