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
    // The picture sits in the page under the buttons on a phone, which makes
    // the hero taller than a screen on a short one like the iPhone 15, so the
    // check is that the stage is not pinned and the run is not the 290vh one.
    const stage = await page.locator("#top > div").first().evaluate((el) => getComputedStyle(el).position)
    expect(stage).not.toBe("sticky")
    const h = await page.locator("#top").evaluate((el) => el.getBoundingClientRect().height)
    const vh = await page.evaluate(() => window.innerHeight)
    expect(h).toBeLessThan(vh * 2)
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

test.describe("reduced motion, hydrated", () => {
  test.use({ reducedMotion: "reduce" })
  test("the page hydrates without a mismatch and keeps the dark theme", async ({ page }) => {
    // motion's own reduced-motion hook answered differently in the first client
    // render than on the server; React threw the tree away and the dark class
    // on the root went with it
    const errors = watchErrors(page)
    await page.goto("/")
    await page.waitForLoadState("load")
    await page.waitForTimeout(1500)
    expect(errors.filter((e) => /hydrat|#418|#423/i.test(e))).toEqual([])
    await expect(page.locator("html")).toHaveClass(/\bdark\b/)
  })

  test("the hero picture ends before the next section starts", async ({ page }) => {
    await page.goto("/")
    const phone = await page.locator("#top .hero-static").boundingBox()
    const film = await page.locator("#film").boundingBox()
    expect(phone!.y + phone!.height).toBeLessThanOrEqual(film!.y + 1)
  })
})

// WebKit in the container composites the whole home page in software and
// manages a frame every second or two there, with or without the picture phone, so
// the picture phone's per-frame pose gets this long to arrive
const PAINT = { timeout: 15_000 }

test("without 3D the picture phone plays the alarm and goes quiet on Acknowledge", async ({ page, browserName }) => {
  test.slow(browserName === "webkit", "a frame every second or two in the container, see PAINT")
  // what an older phone gets: no WebGL 2, or one drawn in software. It was a
  // still that faded out on the first scroll while the alarm played to nothing.
  const errors = watchErrors(page)
  await page.goto("/?no3d")
  await expect(page.locator("html")).not.toHaveClass(/\bgl3d\b/)
  const phone = page.locator("#top .hero-static")
  const alarm = phone.locator('img[src="/hero/alarm.webp"]')
  await expect(alarm).toHaveCount(1)
  // no blurred filter on a layer that moves every frame: on a low memory phone
  // it ran out of tile memory and the words beside it stopped drawing
  expect(await phone.locator(".phone-shadow").evaluate((el) => getComputedStyle(el).filter)).toBe("none")
  const run = await page.locator("#top").evaluate((el) => el.getBoundingClientRect().height - innerHeight)
  // story 0.3, at 48% of the pinned run: turned to face the reader, in the middle
  await page.evaluate((y) => window.scrollTo({ top: y, behavior: "instant" }), run * 0.48)
  await expect
    .poll(() => phone.locator("figure").evaluate((el) => Math.abs(el.getBoundingClientRect().left + el.getBoundingClientRect().width / 2 - innerWidth / 2)), PAINT)
    .toBeLessThan(12)

  // story 0.5, at 80%: the alarm is up and the phone is still on screen beside
  // the words; it used to have faded out by now
  await page.evaluate((y) => window.scrollTo({ top: y, behavior: "instant" }), run * 0.8)
  await expect.poll(() => alarm.evaluate((el) => Number(getComputedStyle(el).opacity)), PAINT).toBeGreaterThan(0.95)
  await expect(phone.locator("figure")).toBeInViewport(PAINT)

  await page.getByRole("button", { name: "Acknowledge" }).click()
  const quiet = phone.locator('img[src="/hero/quiet.webp"]')
  await expect.poll(() => quiet.evaluate((el) => Number(getComputedStyle(el).opacity)), PAINT).toBeGreaterThan(0.95)
  expect(errors).toEqual([])
})

test("a phone with only WebGL 1, as Safari was before iOS 15, gets the picture phone with no flag", async ({ page }) => {
  const errors = watchErrors(page)
  await page.addInitScript(() => {
    const get = HTMLCanvasElement.prototype.getContext
    HTMLCanvasElement.prototype.getContext = function (this: HTMLCanvasElement, type: string, ...rest: unknown[]) {
      if (type === "webgl2") return null
      return (get as (...a: unknown[]) => RenderingContext | null).call(this, type, ...rest)
    } as typeof get
    localStorage.setItem("force3d", "1")
  })
  await page.goto("/")
  await expect(page.locator("html")).not.toHaveClass(/\bgl3d\b/)
  const phone = page.locator("#top .hero-static")
  await expect(phone).toBeVisible()
  const run = await page.locator("#top").evaluate((el) => el.getBoundingClientRect().height - innerHeight)
  await page.evaluate((y) => window.scrollTo({ top: y, behavior: "instant" }), run * 0.8)
  await expect.poll(() => phone.locator('img[src="/hero/alarm.webp"]').evaluate((el) => Number(getComputedStyle(el).opacity)), PAINT).toBeGreaterThan(0.95)
  await expect(phone.locator("figure")).toBeInViewport(PAINT)
  await expect(page.locator("#top canvas")).toHaveCount(0)
  expect(errors).toEqual([])
})

// A phone that passes the head script's check can still fail to draw the 3D
// phone. Each of these left the stage empty or replaced the whole site with
// the error page, on exactly the old and flaky phones the picture exists for.
const FAULTS: Record<string, (page: Page) => Promise<unknown>> = {
  "its code does not arrive": (page) =>
    page.route(/\/_next\/static\/chunks\/.+\.js$/, async (route) => {
      const res = await route.fetch()
      if ((await res.text()).includes("WebGLRenderer")) await route.abort()
      else await route.fulfill({ response: res })
    }),
  "its screens do not arrive": (page) => page.route(/-hd\.webp$/, (route) => route.abort()),
  "the GPU will not give it a context": (page) =>
    page.addInitScript(() => {
      // the head script's probe gets its context; the scene's own canvas does not
      const get = HTMLCanvasElement.prototype.getContext
      let calls = 0
      HTMLCanvasElement.prototype.getContext = function (this: HTMLCanvasElement, type: string, ...rest: unknown[]) {
        if (/webgl/.test(type) && calls++ > 0) return null
        return (get as (...a: unknown[]) => RenderingContext | null).call(this, type, ...rest)
      } as typeof get
    }),
  "the GPU drops its context": async () => {},
}

for (const [fault, inject] of Object.entries(FAULTS)) {
  test(`the picture phone takes over when the 3D phone fails: ${fault}`, async ({ page }) => {
    const gl = await page.evaluate(() => !!document.createElement("canvas").getContext("webgl2"))
    test.skip(!gl, "no WebGL 2 here, so the 3D path never starts")
    await page.addInitScript(() => localStorage.setItem("force3d", "1"))
    await inject(page)
    await page.goto("/")
    if (fault === "the GPU drops its context") {
      await expect(page.locator("#top canvas")).toHaveCount(1)
      await page.locator("#top canvas").evaluate((c: HTMLCanvasElement) => c.getContext("webgl2")?.getExtension("WEBGL_lose_context")?.loseContext())
    }
    const phone = page.locator("#top .hero-static")
    // the scene only finds out once it has loaded and asked for its screens,
    // which on a CPU drawn canvas under four workers is past the default wait
    await expect(phone).toBeVisible({ timeout: 20_000 })
    await expect(page.locator("#top canvas")).toHaveCount(0)
    const run = await page.locator("#top").evaluate((el) => el.getBoundingClientRect().height - innerHeight)
    await page.evaluate((y) => window.scrollTo({ top: y, behavior: "instant" }), run * 0.8)
    const alarm = phone.locator('img[src="/hero/alarm.webp"]')
    await expect.poll(() => alarm.evaluate((el) => Number(getComputedStyle(el).opacity)), PAINT).toBeGreaterThan(0.95)
    await expect(phone.locator("figure")).toBeInViewport(PAINT)
  })
}

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

// The Markdown copies are written from the built HTML by scripts/markdown.mjs.
// What can go wrong is a page losing its copy, a copy losing the page's words to
// a layout change, or chrome leaking into it, so that is what these read.
test("every page links a Markdown copy that carries its words and none of its chrome", async ({ request }) => {
  for (const route of ROUTES) {
    const html = await (await request.get(route)).text()
    const href = html.match(/<link rel="alternate" type="text\/markdown" href="([^"]+)"/)?.[1]
    expect(href, `alternate link on ${route}`).toBeTruthy()
    const res = await request.get(new URL(href!).pathname)
    expect(res.status(), href).toBe(200)
    expect(res.headers()["content-type"], href).toContain("text/markdown")
    const md = await res.text()
    const canonical = route === "/" ? "https://nightbell.app" : `https://nightbell.app${route}`
    expect(md.startsWith(`<!-- ${canonical} -->\n`), `${href} names its page first`).toBe(true)
    const h1 = html.match(/<h1[^>]*>([\s\S]*?)<\/h1>/)![1].replace(/<[^>]+>/g, "").replace(/&#x27;/g, "'").replace(/&amp;/g, "&")
    expect(md, `${href} has the page's h1`).toContain(`# ${h1}`)
    // one comment, the canonical; React's <!-- --> separators are stripped
    expect(md.match(/<!--/g)?.length, `stray comments in ${href}`).toBe(1)
    for (const leak of ["<script", "class=", "](/", "Switch colour theme", "Built and operated by one person"]) expect(md.includes(leak), `${leak} in ${href}`).toBe(false)
  }
})

test("the error page and the download redirect offer no Markdown copy", async ({ request }) => {
  for (const path of ["/this-does-not-exist"]) {
    const html = await (await request.get(path)).text()
    expect(html.includes('type="text/markdown"'), path).toBe(false)
  }
  const download = await request.get("/download", { maxRedirects: 0 })
  expect((await download.text()).includes('type="text/markdown"')).toBe(false)
})

test("llms.txt lists every page's Markdown copy, and every link in it resolves", async ({ request }) => {
  const res = await request.get("/llms.txt")
  expect(res.status()).toBe(200)
  expect(res.headers()["content-type"]).toContain("text/plain")
  const body = await res.text()
  expect(body.startsWith("# Nightbell\n\n> ")).toBe(true)
  for (const route of ROUTES) expect(body, route).toContain(`(https://nightbell.app${route === "/" ? "/index" : route}.md)`)
  for (const [, url] of body.matchAll(/\]\((https:\/\/nightbell\.app[^)]+)\)/g)) {
    const r = await request.get(new URL(url).pathname)
    expect(r.status(), url).toBe(200)
  }
})

// Content negotiation is nginx's job, and `npx serve` does not do it. Run the
// suite against deploy/scripts/local-nginx.mjs, or production, to cover it.
test("asking for text/markdown gets the Markdown at the page's own URL, and a browser still gets HTML", async ({ request }) => {
  test.skip(!process.env.E2E_BASE_URL, "npx serve has no content negotiation; run against nginx")
  for (const route of ROUTES) {
    const copy = await (await request.get(route === "/" ? "/index.md" : `${route}.md`)).text()
    const md = await request.get(route, { headers: { Accept: "text/markdown, text/html;q=0.9" } })
    expect(md.status(), route).toBe(200)
    expect(md.headers()["content-type"], route).toBe("text/markdown; charset=utf-8")
    expect(md.headers()["vary"], route).toContain("Accept")
    const canonical = route === "/" ? "https://nightbell.app/" : `https://nightbell.app${route}`
    expect(md.headers()["link"], route).toBe(`<${canonical}>; rel="canonical"`)
    expect(await md.text(), route).toBe(copy)
    const page = await request.get(route, { headers: { Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8" } })
    expect(page.headers()["content-type"], route).toContain("text/html")
    expect(page.headers()["vary"], route).toContain("Accept")
  }
  // a file is a file whatever the client asks for
  const robots = await request.get("/robots.txt", { headers: { Accept: "text/markdown" } })
  expect(await robots.text()).toContain("Sitemap: https://nightbell.app/sitemap.xml")
  const missing = await request.get("/this-does-not-exist", { headers: { Accept: "text/markdown" } })
  expect(missing.status()).toBe(404)
})

test("screen readers hear the background floor's number, and the marquee once", async ({ page }) => {
  await page.goto("/")
  await expect(page.locator("p", { hasText: "minutes, the shortest interval" })).toContainText("15 minutes")
  // the sr-only list says "No icon proxy"; the marquee's own pills must be hidden
  const exposed = await page.evaluate(
    () => [...document.querySelectorAll("span")].filter((s) => s.textContent === "icon proxy" && !s.closest('[aria-hidden="true"]')).length
  )
  expect(exposed).toBe(0)
})
