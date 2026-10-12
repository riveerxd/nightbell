import { readFileSync } from "node:fs"
import { join } from "node:path"
import type { Metadata } from "next"

import { DocPage } from "@/components/site/doc-page"
import { pageMeta } from "@/lib/seo"
import { docsArticle } from "@/lib/structured"

const description =
  "How to write the health report a Nightbell Health endpoint monitor reads: the JSON document, its parts, the rules each part is judged by, the evidence log, how responses are read, signing in, and a drop-in ASP.NET Core writer."

export const metadata: Metadata = pageMeta({
  path: "/docs/health-format",
  title: "Health report format: what your endpoint serves",
  description,
})

// Read from the repository's own spec at build time rather than copied in, so
// the page cannot drift from it. Its title line is the page's h1 instead.
// The spec's curl example points at example.com, and the site's demo hosts are
// all nightbell.app, so only that host is swapped on the way in.
const source = readFileSync(join(process.cwd(), "../docs/health-format.md"), "utf8")
  .replace(/^# .*\n+/, "")
  .replaceAll("https://example.com/", "https://api.nightbell.app/")

export default function HealthFormat() {
  return (
    <DocPage
      source={source}
      eyebrow="Health report format, version 1"
      title={
        <>
          What your endpoint <span className="text-muted-foreground">serves.</span>
        </>
      }
      intro={
        <p>
          The JSON your service serves for a Health endpoint monitor, the rules it is judged by, and a writer for ASP.NET
          Core. How Nightbell reads it is in the{" "}
          <a className="text-foreground underline underline-offset-4" href="/docs#health-endpoint">
            reference
          </a>
          , and the{" "}
          <a className="text-foreground underline underline-offset-4" href="/docs/health-format.schema.json">
            JSON Schema
          </a>{" "}
          validates it.
        </p>
      }
      jsonLd={(sections) => docsArticle({ path: "/docs/health-format", headline: "Health report format", description, sections })}
    />
  )
}
