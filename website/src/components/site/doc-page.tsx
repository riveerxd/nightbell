import type { ReactNode } from "react"
import GithubSlugger from "github-slugger"
import Markdown from "react-markdown"
import rehypeSlug from "rehype-slug"
import remarkGfm from "remark-gfm"

import { Footer } from "@/components/site/footer"
import { Nav } from "@/components/site/nav"
import { JsonLd } from "@/lib/structured"

// The contents rail is built from the same headings rehype-slug ids, with the
// same slugger, so a link in the rail can never point at an id that is not there.
export function toc(md: string) {
  const slugger = new GithubSlugger()
  const out: { depth: number; text: string; id: string }[] = []
  let fenced = false
  for (const line of md.split("\n")) {
    if (line.startsWith("```")) fenced = !fenced
    const m = !fenced && /^(#{2,3})\s+(.*)$/.exec(line)
    if (!m) continue
    const text = m[2].replace(/[`*_]/g, "").replace(/\[([^\]]+)\]\([^)]+\)/g, "$1")
    out.push({ depth: m[1].length, text, id: slugger.slug(text) })
  }
  return out
}

/* One long markdown document with a contents rail: the reference and the health report spec. */
export function DocPage({
  source,
  eyebrow,
  title,
  intro,
  jsonLd,
}: {
  source: string
  eyebrow: string
  title: ReactNode
  intro: ReactNode
  jsonLd: (sections: { text: string; id: string }[]) => object
}) {
  const items = toc(source)
  const sections = items.filter((i) => i.depth === 2)
  return (
    <>
      <JsonLd data={jsonLd(sections)} />
      <Nav />
      <main id="main">
        <header className="container-page pt-32 pb-14 sm:pt-40">
          <p className="eyebrow">
            {eyebrow} · {sections.length} sections
          </p>
          <h1 className="display mt-5 text-[clamp(3.4rem,9vw,8rem)]">{title}</h1>
          <div className="text-muted-foreground mt-6 max-w-2xl text-lg leading-relaxed">{intro}</div>
        </header>

        <div className="container-page border-border grid gap-12 border-t pt-12 pb-28 lg:grid-cols-[15rem_minmax(0,1fr)] lg:gap-16">
          <nav aria-label="Contents" className="hidden lg:block">
            <div className="sticky top-24 max-h-[calc(100dvh-7rem)] overflow-y-auto pr-2">
              <p className="eyebrow mb-4">Contents</p>
              <ol className="space-y-1">
                {sections.map((s, i) => (
                  <li key={s.id}>
                    <a
                      href={`#${s.id}`}
                      className="text-muted-foreground hover:text-foreground flex min-h-11 items-center gap-3 rounded-md text-sm transition-colors lg:min-h-0 lg:py-1.5"
                    >
                      <span className="text-muted-foreground font-mono text-xs leading-5">{String(i + 1).padStart(2, "0")}</span>
                      {s.text}
                    </a>
                  </li>
                ))}
              </ol>
            </div>
          </nav>

          <details className="border-border rounded-xl border lg:hidden">
            <summary className="flex min-h-11 cursor-pointer items-center px-4 text-sm font-semibold">Contents</summary>
            <ol className="space-y-1 px-4 pb-4">
              {sections.map((s) => (
                <li key={s.id}>
                  <a href={`#${s.id}`} className="text-muted-foreground flex min-h-11 items-center text-sm">
                    {s.text}
                  </a>
                </li>
              ))}
            </ol>
          </details>

          <article className="doc min-w-0">
            <Markdown
              remarkPlugins={[remarkGfm]}
              rehypePlugins={[rehypeSlug]}
              components={{
                // Two tables leave the corner cell empty in the source. It gets a
                // label for screen readers, or the first column has no header.
                th: ({ children, style, align }) => (
                  <th style={style} align={align}>
                    {children ?? <span className="sr-only">Item</span>}
                  </th>
                ),
              }}
            >
              {source}
            </Markdown>
          </article>
        </div>
      </main>
      <Footer />
    </>
  )
}
