import type { MetadataRoute } from "next"

import { SITE_URL } from "@/lib/site"

export const dynamic = "force-static"

const PAGES = ["/", "/faq", "/docs", "/docs/health-format", "/android-background-limits", "/vs/uptimerobot", "/vs/uptime-kuma"]

// No lastmod: a date stamped at build time says "changed today" on every rebuild.
export default function sitemap(): MetadataRoute.Sitemap {
  return PAGES.map((p) => ({ url: p === "/" ? SITE_URL : `${SITE_URL}${p}` }))
}
