"use client"

import { useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode } from "react"
import { MousePointer2 } from "lucide-react"
import { AnimatePresence, motion, useInView, useReducedMotion } from "motion/react"
import { siGithub, siPrometheus, type SimpleIcon } from "simple-icons"

import { cn } from "@/lib/utils"

import { PhoneFrame } from "./depth"
import { SectionHeading, Serif } from "./section"

function Chips({ items, lit, className }: { items: string[]; lit?: string; className?: string }) {
  return (
    <div className={cn("flex flex-wrap gap-1.5", className)}>
      {items.map((v) => (
        <code
          key={v}
          className={cn(
            "bg-surface-2 shadow-elev-1 rounded-lg px-2 py-1 font-mono text-[12px]",
            v === lit && "ring-brand text-foreground ring-1"
          )}
        >
          {v}
        </code>
      ))}
    </div>
  )
}

const CODES = ["200", "204", "503", "no response"]

/*
  The code is the input and the verdict is the answer, so they sit side by side
  at sizes you can read across a room. It plays once when it comes into view
  and rests on no response, which is what the screenshot behind it shows: a
  loop left the card saying 200 up in front of a phone saying down. The mode it
  judges by is the lit chip above, not a label in here.
*/
function StatusDemo() {
  const box = useRef<HTMLDivElement>(null)
  const seen = useInView(box, { once: true, amount: 0.6 })
  const still = useReducedMotion()
  const last = CODES.length - 1
  const [i, setI] = useState(0)
  useEffect(() => {
    if (!seen || still) return
    const t = setInterval(() => setI((v) => (v < last ? v + 1 : v)), 1200)
    return () => clearInterval(t)
  }, [seen, still, last])
  const code = CODES[still ? last : i]
  const ok = code.startsWith("2")
  const none = code === "no response"
  return (
    <div ref={box} aria-live="off" className="flex items-center justify-between gap-[min(1rem,4cqw)] [container-type:inline-size]">
      {/* the box is always the height of a full-size code, so the shorter
          "no response" line never resizes what it sits in */}
      <p
        className={cn(
          "dotmatrix flex h-[clamp(5rem,8vw,7rem)] items-center leading-none whitespace-nowrap transition-colors duration-300",
          // sized off the card's own width, so it stays on one line beside the
          // verdict at any width: about 4.1em wide in this face
          none ? "text-[min(2.8rem,12cqw)]" : "text-[clamp(5rem,8vw,7rem)]",
          ok ? "text-up" : "text-down"
        )}
      >
        {code}
      </p>
      {/* one width for UP and DOWN, dot pinned left: a pill or dot that moved at
          each change was a layout shift */}
      <span
        className={cn(
          "inline-flex w-[5.2em] shrink-0 items-center gap-[0.4em] rounded-full px-[0.65em] py-[0.3em] font-mono text-[min(1.6rem,8cqw)] font-semibold tracking-[0.12em] uppercase ring-1 transition-colors duration-300",
          ok ? "text-up bg-up/10 ring-up/30" : "text-down bg-down/10 ring-down/30"
        )}
      >
        <span aria-hidden className={cn("size-[0.4em] rounded-full", ok ? "bg-up-fill" : "bg-down-fill")} />
        {ok ? "up" : "down"}
      </span>
    </div>
  )
}

function JsonDemo() {
  return (
    <pre className="overflow-x-auto font-mono text-[12.5px] leading-6 sm:text-[13px]">
      <span className="text-muted-foreground">{"{"}</span>
      {"\n  "}
      <span className="text-brand">&quot;data&quot;</span>: {"{"}
      {"\n    "}
      <span className="text-brand">&quot;items&quot;</span>: [{"{"}
      {"\n      "}
      <span className="bg-up/15 text-up rounded px-1">&quot;state&quot;: &quot;ready&quot;</span>
      {"\n    "}
      {"}"}]{"\n  "}
      {"}"}
      {"\n"}
      <span className="text-muted-foreground">{"}"}</span>
      {"\n\n"}
      <span className="text-muted-foreground">path </span>
      <span className="text-up">data.items[0].state</span>
      <span className="text-muted-foreground"> equals </span>
      <span className="text-up">ready</span>
    </pre>
  )
}

const FALLBACK = ["id", "data attribute", "shortest CSS path", "XPath", "text fingerprint"]

function ElementDemo() {
  return (
    <div className="relative px-1">
      <div className="space-y-2" aria-hidden>
        <div className="bg-muted h-2.5 w-2/5 rounded" />
        <div className="bg-muted h-2 w-4/5 rounded" />
        <div className="relative mt-4 inline-flex items-center gap-3 rounded-lg px-3 py-2 outline-2 outline-offset-2 outline-[var(--brand)] outline-dashed">
          <span className="text-sm font-semibold">Widget Deluxe</span>
          <span className="text-down font-mono text-sm line-through decoration-1">£42.00</span>
          <span className="font-mono text-sm">£58.50</span>
          <MousePointer2 className="fill-brand text-brand absolute -right-3 -bottom-4 size-5" />
        </div>
        <div className="bg-muted mt-3 h-2 w-3/5 rounded" />
      </div>
      <ol className="mt-5 flex flex-wrap items-center gap-1.5 font-mono text-[11px]" aria-label="Selector fallback order">
        {FALLBACK.map((f, i) => (
          <li key={f} className="flex items-center gap-1.5">
            <span className={cn("rounded-md border px-2 py-1", i === 0 ? "border-brand/50 text-brand" : "border-border text-muted-foreground")}>
              {f}
            </span>
            {i < FALLBACK.length - 1 && <span className="text-muted-foreground">→</span>}
          </li>
        ))}
      </ol>
    </div>
  )
}

/* A brand's own mark from Simple Icons, in the text colour: the page spends colour on verdicts only. */
function Mark({ icon, className }: { icon: SimpleIcon; className?: string }) {
  return (
    <svg viewBox="0 0 24 24" role="img" aria-label={icon.title} className={cn("shrink-0 fill-current", className)}>
      <path d={icon.path} />
    </svg>
  )
}

/*
  What the phone's shade shows for this repository, in the app's own wording
  (domain/GitHubEvents.kt). The release and the two issues are real; nothing
  here is a made-up count.
*/
const EVENTS = [
  ["New release on riveerxd/nightbell", "v3.18.0 Nightbell 3.18.0"],
  ["New issue on riveerxd/nightbell", "#20 [Feature] Immediate retries"],
  ["New issue on riveerxd/nightbell", "#14 [Feature] Query an Alertmanager (Prometheus/Mimir)"],
]

function GitHubDemo() {
  return (
    <div>
      <div className="flex items-center gap-3">
        <Mark icon={siGithub} className="size-7" />
        <span className="font-mono text-[13px]">riveerxd/nightbell</span>
      </div>
      <ol className="mt-5 grid gap-2" aria-label="Notifications from this repository">
        {EVENTS.map(([title, body]) => (
          <li key={body} className="bg-surface-2 shadow-elev-1 flex gap-3 rounded-2xl px-4 py-3">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src="/favicon.svg" alt="" width={16} height={16} className="mt-0.5 size-4 shrink-0" />
            <span className="min-w-0">
              <span className="block truncate text-[14px] font-semibold">{title}</span>
              <span className="text-muted-foreground block truncate text-[13px]">{body}</span>
            </span>
          </li>
        ))}
      </ol>
    </div>
  )
}

/* One example per source: what is asked, what came back, and the verdict it earns. */
const SOURCES = [
  { source: "PromQL", ask: "sum(rate(errors_total[5m]))", got: "0.42", rule: "below 1", up: true },
  { source: "Alertmanager", ask: 'severity="critical"', got: "Nothing firing", rule: "", up: true },
  { source: "Metrics", ask: 'up{job="checkout"}', got: "0", rule: "equals 1", up: false },
]

function PrometheusDemo() {
  return (
    <div>
      <div className="flex items-center gap-3">
        <Mark icon={siPrometheus} className="size-7" />
        <span className="font-mono text-[13px]">Prometheus, Alertmanager</span>
      </div>
      <dl className="mt-5 grid gap-4">
        {SOURCES.map((r) => (
          <div key={r.source} className="border-border grid gap-1 border-l-2 pl-4">
            <dt className="text-muted-foreground font-mono text-[11px] tracking-[0.12em] uppercase">{r.source}</dt>
            <dd className="truncate font-mono text-[13px]">{r.ask}</dd>
            <dd className="flex items-baseline gap-3 font-mono text-[13px]">
              <span>{r.got}</span>
              {r.rule && <span className="text-muted-foreground">{r.rule}</span>}
              <span className={cn("ml-auto text-[11px] font-semibold tracking-[0.14em] uppercase", r.up ? "text-up" : "text-down")}>
                {r.up ? "up" : "down"}
              </span>
            </dd>
          </div>
        ))}
      </dl>
    </div>
  )
}

/* A real screen with the demo floating in front of it, a hand's width closer. */
function Stage({ src, w = 1080, h = 2400, children, tilt = 1 }: { src: string; w?: number; h?: number; children: ReactNode; tilt?: number }) {
  return (
    <div className="relative mx-auto w-full max-w-md pt-2 pb-12 [perspective:1600px]">
      <div className="mx-auto w-[56%]" style={{ transform: `rotateY(${-14 * tilt}deg) rotateZ(${-2 * tilt}deg)` }}>
        <PhoneFrame edge={tilt > 0 ? "right" : "left"} className="shadow-elev-3">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={src} alt="" width={w} height={h} loading="lazy" className="block w-full" />
        </PhoneFrame>
      </div>
      <div className="absolute inset-x-0 bottom-0 sm:-left-6" style={{ transform: `rotateY(${8 * tilt}deg)` }}>
        <div className="bg-surface-2 shadow-elev-3 rounded-3xl p-5 ring-1 ring-white/5 sm:p-6">{children}</div>
      </div>
    </div>
  )
}

/*
  The report a service writes about itself, drawn the way the detail screen
  reads it: a bar for the verdict, then the worst part opened up. Its rule is a
  number line rather than a sentence, so the measured value can be seen
  crossing the line it was judged by, and its log is the evidence under it.
  Every value is the example in docs/health-format.md, not a real service.
*/
const PARTS = [
  { name: "Email queue", status: "degraded", note: "340 waiting, usually under 50" },
  { name: "Database", status: "up", note: "12 ms" },
] as const
const TONE = { up: "text-up", degraded: "text-degraded", down: "text-down" } as const
const LOG = [
  ["13:58:00", "error", "Gateway declined payment"],
  ["14:01:40", "warn", "Retry queue above 100"],
] as const

function HealthDemo() {
  const still = useReducedMotion()
  // the line runs 0 to 20 failures; the rule says down at 10, the report saw 14
  const at = (n: number) => `${(n / 20) * 100}%`
  return (
    <div className="grid gap-5">
      <div>
        <div className="flex h-2 gap-1" aria-hidden>
          <span className="bg-down-fill flex-1 rounded-full" />
          <span className="bg-degraded-fill flex-1 rounded-full" />
          <span className="bg-up-fill flex-1 rounded-full" />
        </div>
        <p className="mt-2 font-mono text-[12px]">
          <span className="text-down">1 down</span>
          <span className="text-muted-foreground">, </span>
          <span className="text-degraded">1 degraded</span>
          <span className="text-muted-foreground">, </span>
          <span className="text-up">1 up</span>
        </p>
      </div>

      <div className="border-down/60 border-l-2 pl-4">
        <p className="flex items-baseline gap-3">
          <span className="text-[15px] font-semibold">Payments</span>
          <span className="text-down ml-auto font-mono text-[11px] font-semibold tracking-[0.14em] uppercase">down</span>
        </p>
        <p className="text-muted-foreground text-[13px]">Failed card payments, last 5m</p>

        {/* the rule, drawn: everything right of the line is down */}
        <div className="relative mt-5 mb-7 h-6" role="img" aria-label="Failed card payments: 14 observed, down at 10 or more">
          <span className="bg-border absolute inset-x-0 top-1/2 h-px" />
          <span className="bg-down/15 absolute top-1/2 right-0 h-3 -translate-y-1/2" style={{ left: at(10) }} />
          <span className="bg-foreground absolute top-0 bottom-0 w-px" style={{ left: at(10) }} />
          <span className="text-muted-foreground absolute top-full mt-1 -translate-x-1/2 font-mono text-[10px] whitespace-nowrap" style={{ left: at(10) }}>
            down at 10
          </span>
          {/* The marker sits at 14 and the layer carrying it slides in from 0.
              A transform, because animating left moves layout and counted as
              layout shift. Percentages of x are the layer's own width, which is
              the line's. */}
          <motion.span
            className="absolute inset-0"
            initial={still ? false : { x: `-${(14 / 20) * 100}%` }}
            animate={{ x: "0%" }}
            transition={{ duration: 1.1, ease: [0.16, 1, 0.3, 1], delay: 0.15 }}
          >
            <motion.span
              className="absolute top-1/2 size-3 -translate-x-1/2 -translate-y-1/2 rounded-full"
              style={{ left: at(14) }}
              initial={still ? false : { backgroundColor: "var(--up-fill)" }}
              animate={{ backgroundColor: "var(--down-fill)" }}
              transition={{ duration: 1.1, delay: 0.15 }}
            />
            <span className="text-down absolute bottom-full mb-1 -translate-x-1/2 font-mono text-[12px] font-semibold" style={{ left: at(14) }}>
              14
            </span>
          </motion.span>
        </div>

        <ol className="grid gap-1 font-mono text-[11.5px]" aria-label="Evidence log">
          {LOG.map(([t, level, msg]) => (
            <li key={t} className="flex gap-3">
              <span className="text-muted-foreground">{t}</span>
              <span className={cn("w-10 shrink-0 uppercase", level === "error" ? "text-down" : "text-degraded")}>{level}</span>
              <span className="min-w-0">{msg}</span>
            </li>
          ))}
        </ol>
      </div>

      <ul className="grid gap-2">
        {PARTS.map((p) => (
          <li key={p.name} className="border-border border-l-2 pl-4 text-[13px]">
            <span className="flex items-baseline gap-3">
              <span className="font-semibold">{p.name}</span>
              <span className={cn("ml-auto font-mono text-[11px] font-semibold tracking-[0.14em] uppercase", TONE[p.status])}>{p.status}</span>
            </span>
            <span className="text-muted-foreground block">{p.note}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

// The real screen each kind is shown on, where one has been captured.
const SHOTS: Record<string, { src: string; w?: number; h?: number; tilt?: number }> = {
  status: { src: "/screens/detail-status.webp", tilt: -1 },
  request: { src: "/screens/wizard-3.webp" },
  element: { src: "/screens/detail.png", w: 494, h: 1073, tilt: -1 },
  github: { src: "/screens/detail-github.webp" },
  prometheus: { src: "/screens/detail-prometheus.webp", tilt: -1 },
  health: { src: "/screens/detail-health.webp" },
}

type Kind = {
  id: string
  name: string
  cost: string
  body: string
  chips?: string[][]
  /** the chip the demo is judging by */
  lit?: string
  demo: ReactNode
}

const KINDS: Kind[] = [
  {
    id: "status",
    name: "Status check",
    cost: "cheapest",
    body: "Hit a URL, assert on the status code. A refused connection reports no response and no latency rather than inventing a zero.",
    chips: [["exact code", "any 2xx", "a range", "any response at all"]],
    demo: <StatusDemo />,
    lit: "any 2xx",
  },
  {
    id: "request",
    name: "Request and response",
    cost: "precise",
    body: "Method, headers and a body, then an assertion on what comes back. It catches the deploy that answers 200 with an empty payload.",
    chips: [
      ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"],
      ["contains", "does not contain", "exactly equals", "matches regex", "JSON field equals", "JSON field exists"],
    ],
    demo: <JsonDemo />,
  },
  {
    id: "element",
    name: "Page element",
    cost: "heaviest",
    body: "Loads the real page in a WebView and watches the nodes you tapped. Every node is read from the same page load, and the alert names the one that broke.",
    chips: [["element exists", "element is gone", "text equals", "text contains", "text unchanged", "or an attribute instead of text"]],
    demo: <ElementDemo />,
  },
  {
    id: "github",
    name: "GitHub repository",
    cost: "activity",
    body: "Five tracks of what happens on one repository, each switched on separately. A rate limit is never an outage; a 404 or a dead network is.",
    chips: [["stars", "issues and pull requests", "comments", "releases", "downloads"]],
    demo: <GitHubDemo />,
  },
  {
    id: "prometheus",
    name: "Prometheus",
    cost: "metrics",
    body: "A metrics endpoint, a PromQL query or an Alertmanager. One kind, because the three differ only in the URL and how the answer is read.",
    chips: [["above", "at least", "below", "at most", "equals", "not equal"]],
    demo: <PrometheusDemo />,
  },
  {
    id: "health",
    name: "Health endpoint",
    cost: "your rules",
    body: "Reads a report your service writes about itself. The verdict pages; each part brings the rule that decided it, what it measured and what it saw. New in 3.18.0.",
    chips: [["up", "degraded", "down"], ["none", "headers", "basic", "single sign-on"]],
    demo: <HealthDemo />,
  },
]

/*
  Desktop: the kinds are a list you pick from and one stage that shows the pick.
  The stage has a fixed height, so switching kinds never moves the page. Phones
  and tablets get every kind written out, one under another, because a tab strip
  hides four fifths of the section from someone who only scrolls.
*/
export function Checks() {
  const [active, setActive] = useState(0)
  const still = useReducedMotion()
  const base = useId()
  const tabs = useRef<(HTMLButtonElement | null)[]>([])
  const k = KINDS[active]

  const onKey = (e: KeyboardEvent) => {
    const step = e.key === "ArrowDown" || e.key === "ArrowRight" ? 1 : e.key === "ArrowUp" || e.key === "ArrowLeft" ? -1 : 0
    if (!step) return
    e.preventDefault()
    const next = (active + step + KINDS.length) % KINDS.length
    setActive(next)
    tabs.current[next]?.focus()
  }

  return (
    <section id="watch" className="relative py-20 sm:py-28">
      <div className="container-page">
        <SectionHeading
          tone="up"
          title={
            <>
              Six kinds of check. <Serif>Pick the cheapest one that would catch the outage.</Serif>
            </>
          }
        />

        <div className="mt-14 hidden gap-10 lg:grid lg:grid-cols-[minmax(0,4fr)_minmax(0,8fr)] xl:gap-14">
          <div role="tablist" aria-orientation="vertical" aria-label="Kinds of check" onKeyDown={onKey} className="flex h-[29rem] flex-col">
            {KINDS.map((kind, i) => {
              const on = i === active
              return (
                <button
                  key={kind.id}
                  ref={(el) => {
                    tabs.current[i] = el
                  }}
                  role="tab"
                  id={`${base}-tab-${kind.id}`}
                  aria-selected={on}
                  aria-controls={`${base}-panel`}
                  tabIndex={on ? 0 : -1}
                  onClick={() => setActive(i)}
                  className={cn(
                    "border-border flex flex-1 cursor-pointer items-center justify-between gap-4 border-t text-left transition-colors last:border-b",
                    on ? "text-foreground border-t-foreground" : "text-muted-foreground hover:text-foreground"
                  )}
                >
                  <span className="text-[clamp(1.35rem,2vw,1.75rem)] font-semibold tracking-tight">{kind.name}</span>
                  <span className="font-mono text-[11px] tracking-[0.12em] whitespace-nowrap uppercase">{kind.cost}</span>
                </button>
              )
            })}
          </div>

          <div
            role="tabpanel"
            id={`${base}-panel`}
            aria-labelledby={`${base}-tab-${k.id}`}
            className="bg-surface shadow-elev-2 relative overflow-hidden rounded-[1.75rem] p-9 xl:p-10"
          >
            <AnimatePresence mode="wait" initial={false}>
              <motion.div
                key={k.id}
                initial={still ? false : { opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                exit={still ? undefined : { opacity: 0, y: -6 }}
                transition={{ duration: 0.22, ease: [0.16, 1, 0.3, 1] }}
                className="grid h-full grid-cols-2 gap-10 xl:gap-14"
              >
                <div>
                  <p className="text-xl leading-relaxed text-pretty">{k.body}</p>
                  {k.chips?.map((c, i) => <Chips key={i} items={c} lit={k.lit} className={i ? "mt-2" : "mt-6"} />)}
                </div>
                <div className="flex min-w-0 flex-col justify-center">{k.demo}</div>
              </motion.div>
            </AnimatePresence>
          </div>
        </div>

        <div className="mt-12 flex flex-col gap-6 lg:hidden">
          {KINDS.map((kind) => (
            <article key={kind.id} className="bg-surface shadow-elev-2 rounded-[1.75rem] p-6 sm:p-10">
              <p className="text-muted-foreground font-mono text-[11px] tracking-[0.12em] uppercase">{kind.cost}</p>
              <h3 className="display mt-2 text-[clamp(2.4rem,9vw,4rem)]">{kind.name}</h3>
              <p className="text-muted-foreground mt-4 text-lg leading-relaxed">{kind.body}</p>
              {kind.chips?.map((c, i) => <Chips key={i} items={c} lit={kind.lit} className={i ? "mt-2" : "mt-6"} />)}
              <div className="mt-8">
                {/* every kind on a phone is the app's own screen with the demo in front of it */}
                {SHOTS[kind.id] ? (
                  <Stage {...SHOTS[kind.id]}>{kind.demo}</Stage>
                ) : (
                  kind.demo
                )}
              </div>
            </article>
          ))}
        </div>
      </div>
    </section>
  )
}
