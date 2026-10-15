import type { ReactNode } from "react"
import Link from "next/link"
import { ArrowRight, Download } from "lucide-react"

import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from "@/components/ui/accordion"
import { BlurFade } from "@/components/ui/blur-fade"
import { SectionHeading } from "@/components/site/section"
import { APK_MB, DOWNLOAD_PATH, SITE_URL } from "@/lib/site"
import { cn } from "@/lib/utils"

/* The shared furniture of the long-form pages: comparisons and the platform note. */

export type QA = { q: string; a: string }

export const faqLd = (path: string, items: QA[]) => ({
  "@context": "https://schema.org",
  "@type": "FAQPage",
  "@id": new URL(path, SITE_URL).href,
  mainEntity: items.map((f) => ({
    "@type": "Question",
    name: f.q,
    acceptedAnswer: { "@type": "Answer", text: f.a },
  })),
})

export function JsonLd({ data }: { data: object }) {
  return <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify(data) }} />
}

export function PageHero({
  eyebrow,
  title,
  lede,
  children,
}: {
  eyebrow?: string
  title: ReactNode
  lede: string
  children?: ReactNode
}) {
  return (
    <section className="relative isolate overflow-hidden pt-32 pb-16 sm:pt-40 sm:pb-24">
      <div
        className="grid-lines pointer-events-none absolute inset-0 -z-10 [mask-image:radial-gradient(ellipse_70%_60%_at_30%_0%,black,transparent)]"
        aria-hidden
      />
      <div className="container-page">
        <BlurFade>
          <p className="eyebrow flex items-center gap-2.5">
            <span className="bg-brand size-1.5 rounded-full" aria-hidden />
            {eyebrow}
          </p>
          <h1 className="display mt-6 max-w-6xl text-[clamp(3rem,8vw,7rem)] text-balance">{title}</h1>
          <p className="text-muted-foreground mt-8 max-w-2xl text-lg leading-relaxed sm:text-xl">{lede}</p>
        </BlurFade>
        {children}
      </div>
    </section>
  )
}

export function Verdict({ items }: { items: { lead: ReactNode; body: string }[] }) {
  return (
    <BlurFade delay={0.15}>
      <dl
        className={cn(
          "border-border bg-border mt-14 grid gap-px overflow-hidden rounded-[1.5rem] border",
          items.length === 3 ? "lg:grid-cols-3" : "lg:grid-cols-2"
        )}
      >
        {items.map((v, i) => (
          <div key={i} className="bg-background flex flex-col p-6 sm:p-8">
            <dt className="text-lg leading-snug font-semibold">{v.lead}</dt>
            <dd className="text-muted-foreground mt-3 leading-relaxed">{v.body}</dd>
          </div>
        ))}
      </dl>
    </BlurFade>
  )
}

export function Pick({ children }: { children: ReactNode }) {
  return <span className="text-brand">{children}</span>
}

export function CompareTable({
  label,
  caption,
  them,
  rows,
}: {
  label: string
  caption: string
  them: { name: string; eg: string }
  rows: [string, string, string][]
}) {
  return (
    <BlurFade inView delay={0.08}>
      {/* On a phone the table became a sideways scroll that opened on the other
          product's column; each question is a short stack instead, Nightbell's
          answer first. The table below says the same, so the Markdown copy
          keeps only that. */}
      <dl data-md="skip" className="mt-10 grid gap-8 sm:hidden">
        {rows.map((r) => (
          <div key={r[0]} className="border-border border-t pt-5">
            <dt className="text-muted-foreground font-mono text-[11px] tracking-[0.12em] uppercase">{r[0]}</dt>
            <dd className="border-brand/60 mt-3 border-l-2 pl-4">
              <span className="text-brand block text-sm font-semibold">Nightbell</span>
              <span className="mt-1 block leading-relaxed font-medium">{r[2]}</span>
            </dd>
            <dd className="border-border mt-4 border-l-2 pl-4">
              <span className="text-muted-foreground block text-sm font-semibold">{them.name}</span>
              <span className="text-muted-foreground mt-1 block leading-relaxed">{r[1]}</span>
            </dd>
          </div>
        ))}
      </dl>
      <div
        className="mt-12 hidden overflow-x-auto pb-2 sm:block"
        role="region"
        aria-label={label}
        tabIndex={0}
      >
        <table className="w-full min-w-[38rem] border-separate border-spacing-0 text-left text-[15px]">
          <caption className="sr-only">{caption}</caption>
          <thead>
            <tr>
              <th scope="col" className="w-[24%]">
                <span className="sr-only">Aspect</span>
              </th>
              <th scope="col" className="w-[38%] px-5 pt-5 pb-4 align-bottom font-normal">
                <span className="block font-semibold">{them.name}</span>
                <span className="text-muted-foreground mt-1 block font-mono text-xs">{them.eg}</span>
              </th>
              <th
                scope="col"
                className="bg-brand/[0.07] border-brand/30 w-[38%] rounded-t-2xl border-x border-t px-5 pt-5 pb-4 align-bottom font-normal"
              >
                <span className="text-brand block font-semibold">Nightbell</span>
                <span className="text-muted-foreground mt-1 block font-mono text-xs">on your own phone</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, ri) => (
              <tr key={r[0]}>
                <th
                  scope="row"
                  className="border-border text-muted-foreground border-t py-5 pr-4 align-top text-sm font-medium"
                >
                  {r[0]}
                </th>
                <td className="border-border text-muted-foreground border-t px-5 py-5 align-top leading-relaxed">
                  {r[1]}
                </td>
                <td
                  className={cn(
                    "border-border bg-brand/[0.07] border-x-brand/30 border-x border-t px-5 py-5 align-top leading-relaxed font-medium",
                    ri === rows.length - 1 && "border-b-brand/30 rounded-b-2xl border-b"
                  )}
                >
                  {r[2]}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </BlurFade>
  )
}

export function Block({
  id,
  eyebrow,
  title,
  lede,
  tone,
  children,
  className,
}: {
  id: string
  eyebrow?: string
  title: ReactNode
  lede?: ReactNode
  tone?: "up" | "down" | "degraded" | "brand"
  children?: ReactNode
  className?: string
}) {
  return (
    <section id={id} className={cn("border-border relative border-t py-20 sm:py-28", className)}>
      <div className="container-page">
        <SectionHeading eyebrow={eyebrow} title={title} lede={lede} tone={tone} />
        {children}
      </div>
    </section>
  )
}

/* Long-form copy: a readable measure, with the lead phrase of each paragraph allowed to carry weight. */
export function Prose({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <BlurFade inView delay={0.06}>
      <div
        className={cn(
          "text-muted-foreground mt-10 max-w-3xl space-y-6 text-lg leading-relaxed",
          "[&_strong]:text-foreground [&_strong]:font-semibold",
          "[&_code]:bg-muted [&_code]:text-foreground [&_code]:rounded-md [&_code]:px-1.5 [&_code]:py-0.5 [&_code]:font-mono [&_code]:text-[0.85em] [&_code]:break-words",
          className
        )}
      >
        {children}
      </div>
    </BlurFade>
  )
}

export function Questions({ items }: { items: QA[] }) {
  return (
    <BlurFade inView delay={0.06}>
      <Accordion type="multiple" defaultValue={[items[0].q]} className="border-border mt-12 border-t">
        {items.map((f) => (
          <AccordionItem key={f.q} value={f.q} className="border-border border-b">
            <AccordionTrigger className="py-6 text-left text-lg font-semibold hover:no-underline sm:text-xl">
              {f.q}
            </AccordionTrigger>
            <AccordionContent className="text-muted-foreground max-w-3xl pb-7 text-base leading-relaxed">
              {f.a}
            </AccordionContent>
          </AccordionItem>
        ))}
      </Accordion>
    </BlurFade>
  )
}

export function PageActions({
  second,
}: {
  second: { href: string; label: string; external?: boolean }
}) {
  const cls =
    "border-border hover:bg-muted inline-flex h-12 items-center gap-2 rounded-full border px-5 text-[15px] font-semibold transition-colors"
  return (
    <div className="mt-10 flex flex-wrap gap-3">
      <a
        href={DOWNLOAD_PATH}
        className="bg-primary hover:bg-primary/90 inline-flex h-12 items-center gap-2.5 rounded-full px-6 text-[15px] font-semibold text-white transition-colors"
      >
        <Download className="size-[18px]" aria-hidden />
        Download APK, {APK_MB} MB
      </a>
      {second.external ? (
        <a href={second.href} target="_blank" rel="noopener" className={cls}>
          {second.label}
          <ArrowRight className="size-4" aria-hidden />
        </a>
      ) : (
        <Link href={second.href} className={cls}>
          {second.label}
          <ArrowRight className="size-4" aria-hidden />
        </Link>
      )}
    </div>
  )
}

export function Related({ links }: { links: { href: string; title: string; note: string }[] }) {
  return (
    <nav aria-label="Related pages" className="mt-16 grid gap-4 md:grid-cols-2">
      {links.map((l) => (
        <Link
          key={l.href}
          href={l.href}
          className="border-border hover:bg-surface group flex flex-col rounded-[1.5rem] border p-6 transition-colors sm:p-8"
        >
          <span className="display text-[clamp(1.8rem,3vw,2.5rem)]">{l.title}</span>
          <span className="text-muted-foreground mt-3 leading-relaxed">{l.note}</span>
          <span className="text-brand mt-6 inline-flex items-center gap-2 text-sm font-semibold">
            Read it
            <ArrowRight className="size-4 transition-transform group-hover:translate-x-1" aria-hidden />
          </span>
        </Link>
      ))}
    </nav>
  )
}
