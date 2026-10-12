import { defineConfig, devices } from "@playwright/test"

const PORT = 4173
// E2E_BASE_URL runs the same suite against a deployed site instead of the local
// build, which is how a deploy is checked from outside.
const REMOTE = process.env.E2E_BASE_URL

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  // Headless Chromium draws the hero's WebGL on the CPU. Eleven of them at once
  // starved each other until page.evaluate timed out mid-scroll; at four the
  // whole suite passes and takes the same wall time.
  workers: 4,
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: REMOTE ?? `http://localhost:${PORT}`,
    trace: "retain-on-failure",
  },
  webServer: REMOTE
    ? undefined
    : {
        command: `npx serve out -l ${PORT} --no-clipboard`,
        url: `http://localhost:${PORT}`,
        reuseExistingServer: true,
      },
  projects: [
    { name: "desktop-chrome", use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 900 } } },
    { name: "laptop-chrome", use: { ...devices["Desktop Chrome"], viewport: { width: 1280, height: 720 } } },
    { name: "tablet-chrome", use: { ...devices["Desktop Chrome"], viewport: { width: 820, height: 1180 }, hasTouch: true } },
    { name: "mobile-chrome", use: { ...devices["Pixel 7"] } },
    { name: "desktop-firefox", use: { ...devices["Desktop Firefox"], viewport: { width: 1440, height: 900 } } },
    { name: "mobile-firefox", use: { ...devices["Desktop Firefox"], viewport: { width: 390, height: 844 } } },
    { name: "desktop-safari", use: { ...devices["Desktop Safari"], viewport: { width: 1440, height: 900 } } },
    { name: "iphone-safari", use: { ...devices["iPhone 15"] } },
    { name: "mobile-small", use: { ...devices["Pixel 7"], viewport: { width: 360, height: 740 } } },
  ],
})
