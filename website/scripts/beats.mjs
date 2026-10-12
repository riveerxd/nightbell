// Viewport captures at given scroll offsets (in viewport heights).
// Usage: node scripts/beats.mjs <outDir> <route> <width> <vh,vh,...> [--theme=light]
import { chromium, webkit, firefox } from "@playwright/test"
import { mkdirSync } from "node:fs"
const [out, route, w, list, ...rest] = process.argv.slice(2)
const theme = (rest.find((a) => a.startsWith("--theme=")) ?? "--theme=dark").split("=")[1]
mkdirSync(out, { recursive: true })
const width = Number(w)
const b = await ({ chromium, webkit, firefox })[process.env.ENGINE ?? "chromium"].launch()
const ctx = await b.newContext({ viewport: { width, height: width < 600 ? 844 : 900 }, isMobile: width < 600 && process.env.ENGINE !== "firefox", hasTouch: width < 600 })
await ctx.addInitScript((t) => localStorage.setItem("theme", t), theme)
const p = await ctx.newPage()
const errs = []
p.on("pageerror", (e) => errs.push(String(e)))
p.on("console", (m) => m.type() === "error" && errs.push(m.text()))
await p.goto(`http://localhost:4173${route}`, { waitUntil: "networkidle" })
await p.waitForTimeout(1500)
const vh = await p.evaluate(() => innerHeight)
let prev = 0
for (const v of list.split(",").map(Number)) {
  // scroll in steps so scroll-linked values settle like a real reader's
  const target = Math.round(v * vh)
  for (let y = prev; y !== target; y += Math.sign(target - y) * Math.min(120, Math.abs(target - y))) {
    await p.evaluate((yy) => scrollTo({ top: yy, behavior: "instant" }), y)
    await p.waitForTimeout(16)
  }
  await p.evaluate((yy) => scrollTo({ top: yy, behavior: "instant" }), target)
  prev = target
  await p.waitForTimeout(900)
  await p.screenshot({ path: `${out}/${route === "/" ? "home" : route.replace(/\W/g, "")}-${width}-${theme}-${v}.png` })
}
console.log("errors:", errs.length ? errs : "none")
await b.close()
