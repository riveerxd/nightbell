"use client"

import { useRef, useSyncExternalStore } from "react"
import { motion, useInView } from "motion/react"

/*
  Android's fifteen minute floor, drawn once. Two rows on the same sixty minute
  scale: what PeriodicWorkRequest gives you and what a foreground service gives
  you. The floor is PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS. The strict
  floor is the one minute Validation accepts: CheckEngine.MIN_TICK_MS, fifteen
  seconds, is how often the loop looks, not an interval anyone can set.

  The drift on the top row is illustrative and the caption says so. The shape is
  the claim: every run lands at or after the mark that asked for it, never
  before. Minute labels are HTML because SVG text scaled with the viewBox was
  four pixels tall on a phone.
*/

const x = (minute: number) => 30 + (minute / 60) * 1140

const DRIFT: { asked: number; ran: number | null }[] = [
  { asked: 0, ran: 0 },
  { asked: 15, ran: 18 },
  { asked: 30, ran: 34 },
  { asked: 45, ran: 52 },
  // asked for on the hour and not yet run when the hour ended, the ordinary case
  { asked: 60, ran: null },
]

const STRICT = [0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60]
const LABELS = [0, 15, 30, 45, 60]

function Axis() {
  return (
    <p className="text-muted-foreground relative mt-1 h-5 font-mono text-[11px]" aria-hidden>
      {LABELS.map((m) => (
        <span key={m} className="absolute -translate-x-1/2" style={{ left: `${2.5 + (m / 60) * 95}%` }}>
          {m === 0 ? "0" : `${m}m`}
        </span>
      ))}
    </p>
  )
}

function Mark({ d, color, width, delay, on }: { d: string; color: string; width: number; delay: number; on: boolean }) {
  return (
    <motion.path
      d={d}
      stroke={color}
      strokeWidth={width}
      strokeLinecap="round"
      fill="none"
      initial={false}
      animate={on ? { pathLength: 1, opacity: 1 } : { pathLength: 0, opacity: 0 }}
      transition={{ duration: 0.5, delay, ease: [0.16, 1, 0.3, 1] }}
    />
  )
}

export function Cadence() {
  const ref = useRef<HTMLElement>(null)
  const seen = useInView(ref, { once: true, amount: 0.4 })
  // The markup ships drawn. Script only hides it in order to replay it, and
  // never does under reduced motion.
  const armed = useSyncExternalStore(
    () => () => {},
    () => !window.matchMedia("(prefers-reduced-motion: reduce)").matches,
    () => false
  )
  const on = !armed || seen

  return (
    <figure ref={ref} className="border-border bg-surface mt-12 rounded-[1.5rem] border p-5 sm:p-8">
      <div>
        <div className="flex flex-wrap items-baseline justify-between gap-x-6 gap-y-1">
          <p className="eyebrow">Default, WorkManager</p>
          <p className="text-muted-foreground text-sm">at least 15 minutes, and later when the phone is busy</p>
        </div>
        <svg viewBox="0 0 1200 82" role="img" aria-labelledby="cad-a-t cad-a-d" className="mt-4 w-full">
          <title id="cad-a-t">Periodic work over one hour</title>
          <desc id="cad-a-d">
            Checks asked for at zero, fifteen, thirty, forty five and sixty minutes. They ran at zero,
            eighteen, thirty four and fifty two, each at or after the mark that asked for it, and the
            one asked for on the hour has not run by the time the hour ends.
          </desc>
          {DRIFT.filter((d) => d.ran !== null && d.ran > d.asked).map((d, i) => (
            <Mark key={`g${i}`} d={`M ${x(d.asked)} 34 H ${x(d.ran as number)}`} color="var(--muted-foreground)" width={1} delay={0.3 + i * 0.12} on={on} />
          ))}
          {DRIFT.map((d, i) => (
            <Mark key={`a${i}`} d={`M ${x(d.asked)} 16 V 52`} color="var(--muted-foreground)" width={2} delay={i * 0.08} on={on} />
          ))}
          {DRIFT.filter((d) => d.ran !== null).map((d, i) => (
            <Mark key={`r${i}`} d={`M ${x(d.ran as number)} 10 V 58`} color="var(--degraded-fill)" width={4} delay={0.4 + i * 0.12} on={on} />
          ))}
          <path d="M 30 72 H 1170" stroke="var(--border)" strokeWidth="1" fill="none" />
        </svg>
        <Axis />
        <p className="text-muted-foreground mt-3 flex flex-wrap gap-x-6 gap-y-1 text-sm">
          <span className="flex items-center gap-2">
            <span className="bg-muted-foreground h-3 w-0.5" aria-hidden />
            asked for
          </span>
          <span className="flex items-center gap-2">
            <span className="bg-degraded-fill h-3 w-1 rounded-full" aria-hidden />
            actually ran
          </span>
        </p>
      </div>

      <div className="border-border mt-8 border-t pt-8">
        <div className="flex flex-wrap items-baseline justify-between gap-x-6 gap-y-1">
          <p className="eyebrow">Strict, foreground service</p>
          <p className="text-muted-foreground text-sm">your interval, exactly, down to one minute</p>
        </div>
        <svg viewBox="0 0 1200 82" role="img" aria-labelledby="cad-b-t cad-b-d" className="mt-4 w-full">
          <title id="cad-b-t">Strict foreground monitoring over the same hour</title>
          <desc id="cad-b-d">Thirteen checks, evenly spaced every five minutes across the hour, with no drift.</desc>
          {STRICT.map((m, i) => (
            <Mark key={m} d={`M ${x(m)} 10 V 58`} color="var(--up-fill)" width={4} delay={0.6 + i * 0.04} on={on} />
          ))}
          <path d="M 30 72 H 1170" stroke="var(--border)" strokeWidth="1" fill="none" />
        </svg>
        <Axis />
        <p className="text-muted-foreground mt-3 flex items-center gap-2 text-sm">
          <span className="bg-up-fill h-3 w-1 shrink-0 rounded-full" aria-hidden />
          every check, on time, for a permanent notification and real battery
        </p>
      </div>

      <figcaption className="text-muted-foreground border-border mt-8 border-t pt-6 text-sm leading-relaxed">
        The drift on the top row is illustrative: how late a check actually lands depends on what else
        asked to wake your phone. What is not illustrative is the direction. Periodic work arrives at
        or after its mark and never before it, because fifteen minutes is the floor rather than the
        interval.
      </figcaption>
    </figure>
  )
}
