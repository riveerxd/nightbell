import { expect, test, type Page } from "@playwright/test"

const ROUTES = ["/", "/faq", "/docs", "/docs/health-format", "/android-background-limits", "/vs/uptimerobot", "/vs/uptime-kuma"]

function watchErrors(page: Page) {
  const errors: string[] = []
  page.on("pageerror", (e) => errors.push(`pageerror: ${e.message}`))
  page.on("console", (m) => {
    if (m.type() === "error") errors.push(`console: ${m.text()}`)
  })
  page.on("response", (r) => {
    if (r.status() >= 400) errors.push(`${r.status()} ${r.url()}`)
  })
  return errors
}

// Measured against the device width, not innerWidth: on a phone an overflowing
// element widens the layout viewport itself, so innerWidth grows to match and a
// scrollWidth <= innerWidth check passes while the page renders zoomed out.
async function noHorizontalScroll(page: Page) {
  const device = page.viewportSize()!.width
  const [scroll, inner] = await page.evaluate(() => [document.documentElement.scrollWidth, window.innerWidth])
  expect(inner, "layout viewport wider than the device").toBe(device)
  expect(scroll, "page scrolls sideways").toBeLessThanOrEqual(device)
}

for (const route of ROUTES) {
  test(`${route} loads clean, has one h1, and fits the viewport`, async ({ page }) => {
    const errors = watchErrors(page)
    await page.goto(route)
    await page.waitForLoadState("networkidle")
    await expect(page.locator("h1")).toHaveCount(1)
    await noHorizontalScroll(page)
    // walk the page so every in-view component mounts and runs
    const h = await page.evaluate(() => document.documentElement.scrollHeight)
    for (let y = 0; y < h; y += 700) {
      await page.evaluate((yy) => window.scrollTo({ top: yy, behavior: "instant" }), y)
      await page.waitForTimeout(40)
    }
    await noHorizontalScroll(page)
    expect(errors).toEqual([])
  })
}

test("every internal link resolves, anchors included", async ({ page, request }) => {
  // Targets are fetched once each and their ids read from the static markup,
  // which is what a crawler or a no-script reader gets.
  const html = new Map<string, string>()
  const fetchPage = async (path: string) => {
    // Against the live site /download is a counted click and a real APK fetch
    // from GitHub, so a run there would inflate both numbers. Its own test
    // covers it locally.
    if (process.env.E2E_BASE_URL && path === "/download") return ""
    if (!html.has(path)) {
      const res = await request.get(path)
      expect(res.status(), path).toBeLessThan(400)
      html.set(path, await res.text())
    }
    return html.get(path)!
  }
  for (const route of ROUTES) {
    await page.goto(route)
    const hrefs = await page.$$eval("a[href]", (as) => as.map((a) => a.getAttribute("href") ?? ""))
    for (const href of new Set(hrefs)) {
      if (!href.startsWith("/") || href.startsWith("//")) continue
      const [path, hash] = href.split("#")
      const body = await fetchPage(path || route)
      if (hash) expect(body.includes(`id="${hash}"`), `#${hash} on ${path || route} (from ${route})`).toBe(true)
    }
  }
})

test("download buttons point at the counted redirect, and it hands over the APK", async ({ page }) => {
  await page.goto("/")
  const hrefs = await page.$$eval("a", (as) => as.filter((a) => /download/i.test(a.textContent ?? "")).map((a) => a.getAttribute("href")))
  expect(hrefs.length).toBeGreaterThan(0)
  for (const h of hrefs) expect(["/download", "https://f-droid.org/en/packages/me.river.nightbell/"]).toContain(h)
  const res = await page.request.get("/download")
  expect(await res.text()).toContain("Nightbell-3.18.0-release.apk")
})

test("the hero alarm takes the screen on scroll and Acknowledge silences it", async ({ page }) => {
  await page.goto("/")
  await expect(page.getByRole("heading", { level: 1 })).toContainText("wakes you up")
  const hero = page.locator("#top")
  const box = await hero.boundingBox()
  expect(box).not.toBeNull()
  // scroll in steps to 60% of the pinned section
  const target = Math.round(box!.height * 0.6)
  // stepped document scrolling rather than wheel events, which mobile WebKit
  // does not accept; the hero reads scroll position either way
  for (let y = 0; y <= target; y += 150) {
    await page.evaluate((yy) => window.scrollTo({ top: yy, behavior: "instant" }), y)
    await page.waitForTimeout(30)
  }
  const ack = page.getByRole("button", { name: "Acknowledge", exact: true })
  await expect(ack).toBeVisible()
  await ack.click()
  await expect(page.getByText("Silenced for this outage, and nothing else.")).toBeVisible()
  await expect(ack).toHaveCount(0)
})

test("the film opens in a dialog with captions, and Escape closes it", async ({ page }) => {
  await page.goto("/")
  await page.getByRole("button", { name: "Play the forty second Nightbell film" }).click()
  const dialog = page.getByRole("dialog", { name: "Nightbell film" })
  await expect(dialog).toBeVisible()
  await expect(dialog.locator("video track[kind=captions]")).toHaveCount(1)
  await page.keyboard.press("Escape")
  await expect(dialog).toHaveCount(0)
})

test("a chapter button opens the film at that chapter", async ({ page }) => {
  await page.goto("/")
  await page.getByRole("list", { name: "Chapters" }).getByRole("button", { name: /The alert/ }).click()
  const video = page.getByRole("dialog").locator("video")
  await expect.poll(async () => video.evaluate((v: HTMLVideoElement) => v.currentTime), { timeout: 10000 }).toBeGreaterThan(20)
  await page.getByRole("button", { name: "Close the film" }).click()
  await expect(page.getByRole("dialog")).toHaveCount(0)
})

test("the setup wizard switches screens when a step is picked", async ({ page }) => {
  await page.goto("/#setup")
  const tab = page.getByRole("tab", { name: /Expectations/ })
  await tab.click()
  await expect(tab).toHaveAttribute("aria-selected", "true")
  // all four screens sit in the deck; the one in front is the only one with a description
  const front = page.locator('#step-panel img:not([alt=""])')
  await expect(front).toHaveCount(1)
  await expect(front).toHaveAttribute("src", "/screens/wizard-3.webp")
  await page.keyboard.press("ArrowDown")
  await expect(page.getByRole("tab", { name: /Cadence/ })).toHaveAttribute("aria-selected", "true")
})

test("copy buttons copy the digest", async ({ page, context, browserName }) => {
  test.skip(browserName !== "chromium", "clipboard permissions are chromium only")
  await context.grantPermissions(["clipboard-read", "clipboard-write"])
  await page.goto("/#install")
  const first = page.locator("#install").getByRole("button", { name: "Copy", exact: true }).nth(1)
  await first.scrollIntoViewIfNeeded()
  await first.click()
  await expect(page.locator("#install").getByText("Copied")).toBeVisible()
  const text = await page.evaluate(() => navigator.clipboard.readText())
  expect(text).toBe("9ad2ee741f1b1350079c0f092a64173308ca07a12fe00a1452c1328b0a5215ff")
})

test("the theme toggle switches to light and survives a reload", async ({ page }) => {
  await page.goto("/faq")
  await expect(page.locator("html")).toHaveClass(/dark/)
  await page.getByRole("button", { name: "Switch colour theme" }).click()
  await expect(page.locator("html")).not.toHaveClass(/dark/)
  const bg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor)
  expect(bg).toBe("rgb(236, 238, 242)")
  await page.reload()
  await expect(page.locator("html")).not.toHaveClass(/dark/)
})

test("FAQ answers open", async ({ page }) => {
  await page.goto("/faq")
  const q = page.locator("summary", { hasText: "Is it actually free?" })
  await q.scrollIntoViewIfNeeded()
  await q.click()
  await expect(page.getByText("Apache 2.0, the whole thing, source included.")).toBeVisible()
})

test("navigation works at this width", async ({ page }) => {
  await page.goto("/")
  const menu = page.getByRole("button", { name: "Open menu" })
  if (await menu.isVisible()) {
    await menu.click()
    await page.getByRole("navigation", { name: "Mobile" }).getByRole("link", { name: "FAQ" }).click()
  } else {
    await page.getByRole("navigation", { name: "Primary" }).getByRole("link", { name: "FAQ" }).click()
  }
  await expect(page).toHaveURL(/\/faq$/)
  await expect(page.getByRole("heading", { level: 1 })).toContainText("Questions")
})

test("docs contents links land on their section", async ({ page }) => {
  await page.goto("/docs")
  const ids = await page.$$eval("nav[aria-label=Contents] a, details a", (as) => as.map((a) => a.getAttribute("href")))
  expect(ids.length).toBeGreaterThan(10)
  for (const id of ids) await expect(page.locator(`[id="${id!.slice(1)}"]`)).toHaveCount(1)
})

test("unknown paths get the 404 page", async ({ page }) => {
  const res = await page.goto("/this-does-not-exist")
  expect(res?.status()).toBe(404)
  await expect(page.getByRole("heading", { level: 1 })).toContainText("actually down")
})

test("robots, sitemap and manifest are served", async ({ request }) => {
  expect(await (await request.get("/robots.txt")).text()).toContain("Sitemap: https://nightbell.app/sitemap.xml")
  const sitemap = await (await request.get("/sitemap.xml")).text()
  for (const r of ROUTES.slice(1)) expect(sitemap).toContain(`https://nightbell.app${r}`)
  const manifest = await (await request.get("/site.webmanifest")).json()
  expect(manifest.name).toBe("Nightbell")
})

test.describe("reduced motion", () => {
  test.use({ reducedMotion: "reduce" })
  test("the hero is a normal page, not a pinned stage", async ({ page }) => {
    await page.goto("/")
    const h = await page.locator("#top").evaluate((el) => el.getBoundingClientRect().height)
    const vh = await page.evaluate(() => window.innerHeight)
    expect(h).toBeLessThan(vh * 1.6)
    await expect(page.getByRole("link", { name: /Download APK/ }).first()).toBeVisible()
  })
})

test.describe("reduced motion, scrolled", () => {
  test.use({ reducedMotion: "reduce" })
  test("the hero stays in its calm state when scrolled", async ({ page }) => {
    await page.goto("/")
    await page.evaluate(() => window.scrollTo({ top: 600, behavior: "instant" }))
    await page.waitForTimeout(300)
    await expect(page.getByText("All 5 operational")).toBeAttached()
    await expect(page.getByText("1 of 5 is down")).toHaveCount(0)
  })
})

test("the install command is shown in full and has its own copy button", async ({ page }) => {
  await page.goto("/")
  const cmd = page.locator("#install pre")
  await expect(cmd).toContainText("adb install -r Nightbell-3.18.0-release.apk")
  await expect(cmd.locator("xpath=ancestor::div[1]").getByRole("button", { name: "Copy" })).toBeVisible()
})

test("the hero phone is a 3D scene where WebGL exists, and a picture where it does not", async ({ page }) => {
  // headless browsers draw WebGL in software, where the page shows the picture;
  // this forces the 3D path so it is still exercised
  await page.addInitScript(() => localStorage.setItem("force3d", "1"))
  await page.goto("/")
  const gl = await page.evaluate(() => !!document.createElement("canvas").getContext("webgl2"))
  if (gl) {
    await expect(page.locator("#top canvas")).toHaveCount(1)
  } else {
    await expect(page.locator("#top canvas")).toHaveCount(0)
  }
  // the picture is in the markup either way, for the first paint and for anything without script
  await expect(page.locator('#top img[src="/hero/calm.webp"]')).toHaveCount(1)
})

test("app screenshots are captured sharp enough to be shown large", async ({ page }) => {
  await page.goto("/")
  // Every screenshot the page references, decoded from the file itself: some sit
  // in a layout that never shows at this width, so the img would never load.
  const srcs = await page.$$eval('img[src^="/hero/"], img[src^="/screens/"]', (imgs) => [
    ...new Set(imgs.map((i) => i.getAttribute("src")!)),
  ])
  expect(srcs.length).toBeGreaterThan(10)
  const widths = await page.evaluate(async (list) => {
    const out: { src: string; w: number }[] = []
    for (const src of list) {
      const bmp = await createImageBitmap(await (await fetch(src)).blob())
      out.push({ src, w: bmp.width })
    }
    return out
  }, srcs)
  // detail.png is the one capture of a page-element check, and it is only ever
  // drawn about 200 css px wide, so 494 real pixels still covers a 2x screen.
  // Widgets are captured at their own size on the home screen, not phone width.
  const blurry = widths.filter((s) => s.src !== "/screens/detail.png" && !s.src.startsWith("/screens/widget-") && s.w < 1080)
  expect(blurry).toEqual([])
})

test("the hero headline holds its size while it animates in, so nothing below it jumps", async ({ page }) => {
  await page.goto("/", { waitUntil: "commit" })
  const h1 = page.getByRole("heading", { level: 1 })
  await h1.waitFor()
  // sample the box through the load animation; an animated width axis rewraps
  // the lines and changes the height, which is what shoved the copy around
  const heights: number[] = []
  for (let i = 0; i < 12; i++) {
    heights.push(Math.round((await h1.boundingBox())!.height))
    await page.waitForTimeout(100)
  }
  expect(new Set(heights).size, `heights ${heights.join(",")}`).toBe(1)
})

for (const section of ["#watch", "#setup"]) {
  test(`nothing in ${section} resizes the page while the reader sits still`, async ({ page, browserName }) => {
    test.skip(browserName !== "chromium", "layout-shift entries are a Chromium API")
    await page.goto("/")
    await page.locator(section).scrollIntoViewIfNeeded()
    await page.locator(section).evaluate((el) => el.scrollIntoView({ block: "center", behavior: "instant" }))
    // The status code cycles every 1.6 s and the wizard advances every 5.2 s;
    // either one used to change its container's height and shove the page.
    const shift = await page.evaluate(
      () =>
        new Promise<number>((resolve) => {
          let total = 0
          new PerformanceObserver((list) => {
            for (const e of list.getEntries() as unknown as { value: number; hadRecentInput: boolean }[])
              if (!e.hadRecentInput) total += e.value
          }).observe({ type: "layout-shift" })
          setTimeout(() => resolve(total), 6000)
        })
    )
    expect(shift).toBe(0)
  })
}

test("every kind of check is reachable, and switching kinds moves nothing", async ({ page }, info) => {
  test.skip((page.viewportSize()?.width ?? 0) < 1024, "the switcher is the desktop layout; phones list every kind")
  test.skip(info.project.name.includes("firefox"), "layout-shift entries are a Chromium API")
  await page.goto("/")
  const tabs = page.locator("#watch [role=tab]")
  await expect(tabs).toHaveText([/Status check/, /Request and response/, /Page element/, /GitHub repository/, /Prometheus/, /Health endpoint/])
  const panel = page.locator("#watch [role=tabpanel]")
  await panel.scrollIntoViewIfNeeded()
  const before = await panel.boundingBox()
  const shift = page.evaluate(
    () =>
      new Promise<number>((resolve) => {
        let total = 0
        new PerformanceObserver((list) => {
          for (const e of list.getEntries() as unknown as { value: number }[]) total += e.value
        }).observe({ type: "layout-shift" })
        setTimeout(() => resolve(total), 4000)
      })
  )
  for (let i = 0; i < 6; i++) {
    await tabs.nth(i).click()
    await expect(tabs.nth(i)).toHaveAttribute("aria-selected", "true")
    expect((await panel.boundingBox())!.height).toBe(before!.height)
  }
  expect(await shift).toBe(0)
})

test("the urgent section follows the light theme", async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem("theme", "light"))
  await page.goto("/")
  const bg = await page.locator("#urgent").evaluate((el) => getComputedStyle(el).backgroundColor)
  const [r, g, b] = bg.match(/\d+/g)!.map(Number)
  expect((r + g + b) / 3, `background ${bg}`).toBeGreaterThan(200)
})

test("the comparison names all three answers to the same six questions", async ({ page }) => {
  await page.goto("/")
  const why = page.locator("#why")
  for (const name of ["Hosted service", "Self-hosted", "Nightbell"]) {
    await expect(why.getByRole("heading", { level: 3, name })).toHaveCount(1)
  }
  await expect(why.locator("dl")).toHaveCount(3)
  for (const dl of await why.locator("dl").all()) await expect(dl.locator("dt")).toHaveCount(6)
})

test("folded answers are in the static HTML, so the FAQPage data matches the page", async ({ request }) => {
  const cases: [string, string][] = [
    ["/faq", "Apache 2.0, the whole thing, source included."],
    ["/", "Only in strict mode. Android"],
    ["/", "A status or request monitor is an OkHttp call from your phone"],
    ["/", "I kept missing outages because ordinary monitoring notifications"],
  ]
  for (const [path, phrase] of cases) {
    // scripts removed first: the JSON-LD carries the same answers and would pass
    // this on its own, which is the mismatch the test exists to catch
    const body = (await (await request.get(path)).text()).replace(/<script[\s\S]*?<\/script>/g, "")
    expect(body.includes(phrase), `${phrase} in ${path}`).toBe(true)
  }
})

test("every page keeps the old site's search title and a full share card", async ({ request }) => {
  const titles: Record<string, string> = {
    "/": "Nightbell: Android uptime monitoring with no server or account",
    "/faq": "Nightbell FAQ: on-device uptime monitoring, answered",
    "/docs": "Nightbell reference: every check, alert and setting",
  }
  for (const path of ["/", "/faq", "/docs", "/vs/uptimerobot", "/vs/uptime-kuma", "/android-background-limits"]) {
    const html = await (await request.get(path)).text()
    if (titles[path]) expect(html, path).toContain(`<title>${titles[path]}</title>`)
    for (const tag of ['property="og:image"', 'property="og:image:alt"', 'name="twitter:image"', 'name="twitter:image:alt"', 'rel="canonical"'])
      expect(html.includes(tag), `${tag} on ${path}`).toBe(true)
  }
})

test("structured data carries every entity the old site published, and parses", async ({ request }) => {
  const want: Record<string, string[]> = {
    "/": ["SoftwareApplication", "WebSite", "Person", "VideoObject", "FAQPage"],
    "/faq": ["SoftwareApplication", "WebSite", "Person", "FAQPage"],
    "/docs": ["SoftwareApplication", "WebSite", "Person", "TechArticle"],
    "/android-background-limits": ["TechArticle"],
    "/vs/uptimerobot": ["FAQPage"],
    "/vs/uptime-kuma": ["FAQPage"],
  }
  for (const [path, types] of Object.entries(want)) {
    const html = await (await request.get(path)).text()
    const found = [...html.matchAll(/<script type="application\/ld\+json"[^>]*>([\s\S]*?)<\/script>/g)].map(
      (m) => JSON.parse(m[1])["@type"]
    )
    for (const t of types) expect(found, `${t} on ${path}`).toContain(t)
  }
})
