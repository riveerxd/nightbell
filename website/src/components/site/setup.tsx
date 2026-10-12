"use client"

import { useEffect, useRef, useState } from "react"
import { motion, useInView } from "motion/react"

import { BlurFade } from "@/components/ui/blur-fade"
import { cn } from "@/lib/utils"

import { Parallax, PhoneFrame } from "./depth"
import { SectionHeading, Serif } from "./section"

const STEPS = [
  {
    title: "What to watch",
    body: "Status check, request and response, page element, GitHub repository, Prometheus or health endpoint. This is the only choice that changes the rest of the wizard.",
    keys: ["status check", "request and response", "page element", "GitHub repository", "Prometheus", "health endpoint"],
    alt: "Step one of the new-monitor wizard: the kinds of check listed as rows, Status check selected with a tick, then Request and response, Page element, GitHub repo and Prometheus.",
  },
  {
    title: "Target",
    body: "The URL, and for a request monitor the method, headers and body. For a page monitor this is where the live preview opens and you tap the nodes you care about.",
    keys: ["name", "url", "method", "headers", "body", "test now"],
    alt: "Step two: a name field reading Orders service, a URL field reading https://api.nightbell.app/orders/health, a row of methods with GET selected, an empty headers section, a Route through SOCKS5 switch, certificate trust set to System CAs, and a Test now button.",
  },
  {
    title: "Expectations",
    body: "What healthy means. A status mode, a body assertion, or one expectation per watched element. Test now runs the whole thing once, there and then, before you commit to it.",
    keys: ["status mode", "expected code", "body assertion", "test now"],
    alt: "Step three: status code modes with Exact code selected, an expected code of 200 with a grid of common codes, six response body assertions with No body check selected, the route and certificate row, and a Test now button.",
  },
  {
    title: "Cadence and alerts",
    body: "How often, how long to wait for an answer, and whether this monitor is urgent. Also the latency budget, above which a successful but slow answer counts as degraded rather than down.",
    keys: ["interval", "timeout", "follow redirects", "latency budget", "urgent"],
    alt: "Step four: check every 15 minutes with shortcuts from one minute to twelve hours, a 15 second timeout, follow redirects and active both switched on, a custom latency budget switched off, the urgent switch, and a Create monitor button.",
  },
]

const DWELL = 5200

export function Setup() {
  const [step, setStep] = useState(0)
  const [auto, setAuto] = useState(true)
  const box = useRef<HTMLDivElement>(null)
  const seen = useInView(box, { amount: 0.4 })

  useEffect(() => {
    if (!auto || !seen) return
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return
    const t = setTimeout(() => setStep((s) => (s + 1) % STEPS.length), DWELL)
    return () => clearTimeout(t)
  }, [step, auto, seen])

  const pick = (i: number) => {
    setAuto(false)
    setStep(i)
  }

  return (
    <section id="setup" className="relative overflow-x-clip py-20 sm:py-28">
      <div className="container-page">
        <SectionHeading
          title={
            <>
              Four steps. <Serif>Everything is changeable afterwards.</Serif>
            </>
          }
        />

        <div ref={box} className="mt-14">
          <div role="tablist" aria-label="The four steps of adding a monitor" className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            {STEPS.map((s, i) => {
              const on = i === step
              return (
                <button
                  key={s.title}
                  type="button"
                  role="tab"
                  id={`step-tab-${i}`}
                  aria-selected={on}
                  aria-controls="step-panel"
                  onClick={() => pick(i)}
                  onKeyDown={(e) => {
                    if (e.key === "ArrowDown" || e.key === "ArrowRight") pick((i + 1) % STEPS.length)
                    if (e.key === "ArrowUp" || e.key === "ArrowLeft") pick((i + STEPS.length - 1) % STEPS.length)
                  }}
                  tabIndex={on ? 0 : -1}
                  className={cn(
                    "group relative overflow-hidden rounded-[1.75rem] px-5 py-5 text-left transition-[background-color,box-shadow,transform] duration-300",
                    on ? "bg-surface shadow-elev-2" : "hover:bg-surface/60 active:scale-[0.98]"
                  )}
                >
                  <span className={cn("dotmatrix block text-2xl transition-colors", on ? "text-brand" : "text-muted-foreground/75")}>
                    0{i + 1}
                  </span>
                  <span
                    className={cn(
                      "display mt-2 block text-[clamp(1.6rem,2.6vw,2.4rem)] transition-colors",
                      on ? "text-foreground" : "text-muted-foreground/85 group-hover:text-foreground"
                    )}
                  >
                    {s.title}
                  </span>
                  {on && auto && seen && (
                    <span
                      className="bg-brand absolute bottom-0 left-0 h-0.5 origin-left animate-[stepfill_linear_forwards] motion-reduce:hidden"
                      style={{ animationDuration: `${DWELL}ms`, width: "100%" }}
                    />
                  )}
                </button>
              )
            })}
          </div>

          <div
            id="step-panel"
            role="tabpanel"
            aria-labelledby={`step-tab-${step}`}
            className="relative mx-auto mt-14 aspect-[1080/2400] w-full max-w-[15rem] [perspective:1600px] sm:max-w-[19rem]"
          >
            {/* Flat, not preserve-3d: in one shared 3D space the turning phones
                sliced through each other mid-move and the browser ignored the
                stacking order. Each phone keeps its own 3D for its body. */}
            {STEPS.map((st, i) => {
              const d = i - step
              const on = d === 0
              return (
                <motion.div
                  key={st.title}
                  className="absolute inset-0"
                  initial={false}
                  animate={{
                    x: `${d * 44}%`,
                    z: -Math.abs(d) * 160,
                    rotateY: d === 0 ? 0 : d > 0 ? -24 : 24,
                  }}
                  transition={{ type: "spring", stiffness: 140, damping: 22 }}
                  style={{ zIndex: 10 - Math.abs(d), visibility: Math.abs(d) > 2 ? "hidden" : "visible" }}
                  aria-hidden={!on}
                >
                  <PhoneFrame edge={d > 0 ? "right" : d < 0 ? "left" : undefined} className={on ? "shadow-elev-3" : "shadow-elev-2"}>
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img
                      src={`/screens/wizard-${i + 1}.webp`}
                      alt={on ? st.alt : ""}
                      width={1080}
                      height={2400}
                      loading="lazy"
                      className="block w-full"
                    />
                  </PhoneFrame>
                </motion.div>
              )
            })}
          </div>

          <div className="mx-auto mt-6 max-w-2xl text-center">
            {/* Every step's text sits in the same cell and only the picked one
                shows, so the block is always as tall as the longest step and the
                five second auto-advance never resizes the page under the reader. */}
            <div className="grid">
              {STEPS.map((st, i) => (
                <div
                  key={st.title}
                  aria-hidden={i !== step}
                  className={cn(
                    "[grid-area:1/1] transition-[opacity,translate] duration-300 ease-out",
                    i === step ? "translate-y-0 opacity-100" : "pointer-events-none translate-y-2 opacity-0"
                  )}
                >
                  <p className="text-muted-foreground text-lg leading-relaxed">{st.body}</p>
                  <p className="mt-4 flex flex-wrap justify-center gap-1.5">
                    {st.keys.map((k) => (
                      <code key={k} className="bg-surface shadow-elev-1 rounded-lg px-2 py-1 font-mono text-xs">
                        {k}
                      </code>
                    ))}
                  </p>
                </div>
              ))}
            </div>
            <p className="text-muted-foreground mt-8 text-sm leading-relaxed">
              Nothing here is locked in. Every field is editable from the monitor&apos;s detail screen
              afterwards, and Test now runs the whole check once before you commit to it.
            </p>
          </div>
        </div>
      </div>
    </section>
  )
}

/*
  Three sizes of the same widget, laid over each other at three depths: as the
  page moves they slide apart at different speeds, which is the whole argument
  of the section, one widget that rearranges itself.
*/
export function Widgets() {
  return (
    <section aria-labelledby="widgets-title" className="relative overflow-x-clip py-20 sm:py-28">
      <div className="container-page grid items-center gap-14 lg:grid-cols-[0.8fr_1.4fr] lg:gap-10">
        <BlurFade inView>
          <p className="eyebrow">Home-screen widget</p>
          <h2 id="widgets-title" className="display mt-4 text-[clamp(2.6rem,5.4vw,5rem)]">
            Worst monitor first.
          </h2>
          <p className="text-muted-foreground mt-5 max-w-md text-lg leading-relaxed">
            Put Nightbell on your home screen and see every monitor without opening the app, the
            one in trouble at the top. Drag the widget flat and the list flows into columns instead
            of disappearing below the fold.
          </p>
        </BlurFade>
        {/* eslint-disable @next/next/no-img-element */}
        <div className="relative mx-auto aspect-[5/4] w-full max-w-2xl">
          <Parallax speed={-0.04} rotate={-2} className="absolute top-0 left-0 w-[50%]">
            <img
              src="/screens/widget-tall.webp"
              width={875}
              height={875}
              alt="The Nightbell widget at its tall size, one column listing six monitors worst first: Checkout API down, Search index slow at 3370 ms, then four healthy services with their response times."
              className="w-full drop-shadow-[0_40px_60px_rgb(0_0_0/0.45)]"
              loading="lazy"
            />
          </Parallax>
          <Parallax speed={0.1} rotate={1.5} className="absolute top-[14%] right-0 w-[60%]">
            <img
              src="/screens/widget-wide.webp"
              width={1120}
              height={630}
              alt="The same widget dragged wider, the six monitors spilling into two columns with a checked just now line underneath."
              className="w-full drop-shadow-[0_50px_70px_rgb(0_0_0/0.5)]"
              loading="lazy"
            />
          </Parallax>
          <Parallax speed={0.22} className="absolute bottom-0 left-[8%] w-[86%]">
            <img
              src="/screens/widget-flat.webp"
              width={1400}
              height={315}
              alt="The same widget dragged flat into a single wide row, showing the three worst monitors and nothing else."
              className="w-full drop-shadow-[0_60px_80px_rgb(0_0_0/0.55)]"
              loading="lazy"
            />
          </Parallax>
        </div>
        {/* eslint-enable @next/next/no-img-element */}
      </div>
    </section>
  )
}
