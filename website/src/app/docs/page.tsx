import { readFileSync } from "node:fs"
import { join } from "node:path"
import type { Metadata } from "next"

import { DocPage } from "@/components/site/doc-page"
import { RELEASE } from "@/lib/site"
import { pageMeta } from "@/lib/seo"
import { docsArticle } from "@/lib/structured"

const description =
  "The complete reference for Nightbell, the Android uptime monitor that runs on your phone. Every check type and its matching modes, the alert escalation matrix, the home-screen widget, the element picker, the diagnostic log, and the architecture behind them."

export const metadata: Metadata = pageMeta({
  path: "/docs",
  title: "Nightbell reference: every check, alert and setting",
  description,
})

const source = readFileSync(join(process.cwd(), "content/reference.md"), "utf8")

export default function Docs() {
  return (
    <DocPage
      source={source}
      eyebrow="Reference"
      title={
        <>
          Every check, alert <span className="text-muted-foreground">and setting.</span>
        </>
      }
      intro={
        <p>
          Everything Nightbell {RELEASE.version} does, and how. Setting up your own service for a Health endpoint monitor?
          That is in the{" "}
          <a className="text-foreground underline underline-offset-4" href="/docs/health-format">
            health report format
          </a>
          .
        </p>
      }
      jsonLd={(sections) => docsArticle({ path: "/docs", headline: "Nightbell reference", description, sections })}
    />
  )
}
