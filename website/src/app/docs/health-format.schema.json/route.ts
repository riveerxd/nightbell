import { readFileSync } from "node:fs"
import { join } from "node:path"

export const dynamic = "force-static"

// The spec links to its schema by a relative name, so it is served beside the page.
export function GET() {
  const body = readFileSync(join(process.cwd(), "../docs/health-format.schema.json"), "utf8")
  return new Response(body, { headers: { "content-type": "application/schema+json" } })
}
