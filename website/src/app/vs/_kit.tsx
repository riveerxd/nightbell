import { Fragment, type ReactNode } from "react"
import Link from "next/link"
import { ArrowRight, Download } from "lucide-react"

import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from "@/components/ui/accordion"
import { BlurFade } from "@/components/ui/blur-fade"
import { PhoneFrame } from "@/components/site/depth"
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

/*
  Screens from the app beside the text that explains them. Straight on and
  flat: these are evidence for a step, so nothing turns or floats. Each one is
  a capture from a real run, named in the alt text by what it shows.
*/
export function Shots({ shots }: { shots: { src: string; alt: string; caption: string }[] }) {
  return (
    <BlurFade inView delay={0.06}>
      <div
        className={cn(
          "mt-14 grid gap-10",
          shots.length > 1 && "sm:grid-cols-2",
          shots.length === 3 && "lg:grid-cols-3"
        )}
      >
        {shots.map((s) => (
          <figure key={s.src} className="mx-auto w-full max-w-[19rem]">
            <PhoneFrame className="shadow-elev-2">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={s.src} alt={s.alt} width={1080} height={2400} loading="lazy" className="block w-full" />
            </PhoneFrame>
            <figcaption className="text-muted-foreground mt-5 text-sm leading-relaxed">{s.caption}</figcaption>
          </figure>
        ))}
      </div>
    </BlurFade>
  )
}

/* A numbered setup step: what to do, then what you should see. */
export function Steps({ items, start = 1 }: { items: { title: string; body: ReactNode }[]; start?: number }) {
  return (
    <BlurFade inView delay={0.06}>
      <ol start={start} className="border-border mt-12 max-w-3xl border-t">
        {items.map((s, i) => (
          <li key={s.title} className="border-border grid grid-cols-[2.5rem_1fr] gap-x-4 border-b py-7">
            <span className="text-brand font-mono text-sm tabular-nums">{String(i + start).padStart(2, "0")}</span>
            <div>
              <p className="text-lg font-semibold">{s.title}</p>
              <div className="text-muted-foreground mt-2 space-y-3 leading-relaxed [&_code]:bg-muted [&_code]:text-foreground [&_code]:rounded-md [&_code]:px-1.5 [&_code]:py-0.5 [&_code]:font-mono [&_code]:text-[0.85em] [&_code]:break-words">
                {s.body}
              </div>
            </div>
          </li>
        ))}
      </ol>
    </BlurFade>
  )
}

export type Tone = "up" | "down" | "degraded"

const TONE_TEXT: Record<Tone, string> = { up: "text-up", down: "text-down", degraded: "text-degraded" }
const TONE_CHIP: Record<Tone, string> = {
  up: "text-up bg-up/10 ring-up/30",
  down: "text-down bg-down/10 ring-down/30",
  degraded: "text-degraded bg-degraded/10 ring-degraded/30",
}
const TONE_FILL: Record<Tone, string> = { up: "bg-up-fill", down: "bg-down-fill", degraded: "bg-degraded-fill" }

/*
  Output copied from a real run, with the line that matters picked out in the
  colour of what it means. A command line starts with "$ " and is printed dim,
  so the eye goes to what came back rather than what was typed.
*/
export function Output({
  label,
  lines,
  note,
}: {
  label: string
  lines: { text: string; tone?: Tone }[]
  note?: ReactNode
}) {
  return (
    <BlurFade inView delay={0.06}>
      <figure className="border-border bg-surface mt-12 max-w-4xl overflow-hidden rounded-[1.25rem] border">
        <figcaption className="border-border text-muted-foreground border-b px-5 py-3 font-mono text-[11px] tracking-[0.12em] uppercase">
          {label}
        </figcaption>
        {/* Wrapped rather than scrolled: a Prometheus series puts its value at
            the very end of a long line, and a scroll hid the one character the
            block is there to show. Lines are inline with real newlines between
            them, so the Markdown copy keeps them as lines. */}
        <pre className="px-5 py-5 font-mono text-[12.5px] leading-6 [overflow-wrap:anywhere] whitespace-pre-wrap sm:text-[13px]">
          {lines.map((l, i) => (
            <Fragment key={i}>
              <span
                className={cn(
                  l.text.startsWith("$ ") || l.text.startsWith("#") ? "text-muted-foreground" : "text-foreground",
                  l.tone && cn("rounded px-1 box-decoration-clone", TONE_CHIP[l.tone].split(" ").slice(0, 2).join(" "))
                )}
              >
                {l.text}
              </span>
              {i < lines.length - 1 && "\n"}
            </Fragment>
          ))}
        </pre>
        {note && (
          <p className="border-border text-muted-foreground border-t px-5 py-4 text-sm leading-relaxed">{note}</p>
        )}
      </figure>
    </BlurFade>
  )
}

/* A capture of something other than the phone, at its own shape. */
export function WideShot({
  src,
  alt,
  caption,
  width,
  height,
}: {
  src: string
  alt: string
  caption: string
  width: number
  height: number
}) {
  return (
    <BlurFade inView delay={0.06}>
      <figure className="mt-14 max-w-4xl">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          src={src}
          alt={alt}
          width={width}
          height={height}
          loading="lazy"
          className="border-border shadow-elev-2 block w-full rounded-[1rem] border"
        />
        <figcaption className="text-muted-foreground mt-5 max-w-2xl text-sm leading-relaxed">{caption}</figcaption>
      </figure>
    </BlurFade>
  )
}

/* What came back, what the monitor says about it, and what that means. */
export function Outcomes({
  label,
  rows,
}: {
  label: string
  rows: { seen: string; says: string; tone: Tone; means: string }[]
}) {
  return (
    <BlurFade inView delay={0.06}>
      <dl aria-label={label} className="border-border mt-12 max-w-4xl border-t">
        {rows.map((r) => (
          <div key={r.seen} className="border-border grid gap-3 border-b py-6 sm:grid-cols-[13rem_1fr] sm:gap-6">
            <dt className="font-semibold">{r.seen}</dt>
            <dd>
              <span
                className={cn(
                  "inline-flex items-center gap-2 rounded-full px-3 py-1 font-mono text-[12px] ring-1",
                  TONE_CHIP[r.tone]
                )}
              >
                <span aria-hidden className={cn("size-1.5 rounded-full", TONE_FILL[r.tone])} />
                {r.says}
              </span>
              <p className="text-muted-foreground mt-3 leading-relaxed">{r.means}</p>
            </dd>
          </div>
        ))}
      </dl>
    </BlurFade>
  )
}

/*
  One silent UDP check against three servers, on one time axis: the default
  three tries of fifteen seconds, then a ping with the same timeout. The point
  it makes is the one the app's progress panel makes, that a closed port is
  answered at once and a healthy WireGuard server is the slow case. Drawn, so
  the Markdown copy keeps the list underneath instead.
*/
type Leg = { from: number; to: number; label: string; kind: "try" | "ping" | "answer" }
const SILENT_RUNS: { server: string; legs: Leg[]; at: number; verdict: string; tone: Tone }[] = [
  {
    server: "Nothing on the port",
    legs: [{ from: 0, to: 0.6, label: "refused", kind: "answer" }],
    at: 0,
    verdict: "Down at once: port closed",
    tone: "down",
  },
  {
    server: "WireGuard running",
    legs: [
      { from: 0, to: 15, label: "try 1", kind: "try" },
      { from: 15, to: 30, label: "try 2", kind: "try" },
      { from: 30, to: 45, label: "try 3", kind: "try" },
      { from: 45, to: 46, label: "ping", kind: "answer" },
    ],
    at: 46,
    verdict: "Up after about 45 s: not refused, host answers ping",
    tone: "up",
  },
  {
    server: "Host gone",
    legs: [
      { from: 0, to: 15, label: "try 1", kind: "try" },
      { from: 15, to: 30, label: "try 2", kind: "try" },
      { from: 30, to: 45, label: "try 3", kind: "try" },
      { from: 45, to: 60, label: "ping", kind: "ping" },
    ],
    at: 60,
    verdict: "Down after 60 s: no refusal and no ping reply",
    tone: "down",
  },
]

export function SilentTimeline() {
  const pct = (s: number) => `${(s / 60) * 100}%`
  return (
    <BlurFade inView delay={0.06}>
      <figure className="mt-12 max-w-4xl">
        <div data-md="skip" aria-hidden className="space-y-8">
          {SILENT_RUNS.map((run) => (
            <div key={run.server}>
              <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
                <p className="font-semibold">{run.server}</p>
                <p className={cn("font-mono text-[12px]", TONE_TEXT[run.tone])}>{run.verdict}</p>
              </div>
              <div className="relative mt-3 h-7">
                <div className="bg-muted absolute inset-x-0 top-1/2 h-px" />
                {run.legs.map((leg) => (
                  <div
                    key={leg.label}
                    className={cn(
                      "absolute top-0 flex h-full items-center overflow-hidden rounded-md px-2 font-mono text-[11px] whitespace-nowrap",
                      leg.kind === "try" && "border-border bg-surface text-muted-foreground border",
                      leg.kind === "ping" && "border-border text-muted-foreground border border-dashed",
                      leg.kind === "answer" && cn("min-w-[0.75rem]", TONE_FILL[run.tone])
                    )}
                    style={{ left: pct(leg.from), width: `calc(${pct(leg.to - leg.from)} - 3px)` }}
                  >
                    {leg.kind !== "answer" && <span className="hidden sm:inline">{leg.label}</span>}
                  </div>
                ))}
              </div>
            </div>
          ))}
          <div className="text-muted-foreground relative h-4 font-mono text-[11px]">
            {[0, 15, 30, 45, 60].map((s) => (
              <span
                key={s}
                className={cn("absolute whitespace-nowrap", s === 0 ? "" : s === 60 ? "-translate-x-full" : "-translate-x-1/2")}
                style={{ left: pct(s) }}
              >
                {s} s
              </span>
            ))}
          </div>
        </div>
        <ol className="sr-only">
          {SILENT_RUNS.map((run) => (
            <li key={run.server}>
              {run.server}: {run.verdict}.
            </li>
          ))}
        </ol>
        <figcaption className="text-muted-foreground mt-6 max-w-2xl text-sm leading-relaxed">
          One check in silent mode at the defaults, three tries of 15 seconds and then a ping, against three
          different servers. The healthy one is the slow one.
        </figcaption>
      </figure>
    </BlurFade>
  )
}
