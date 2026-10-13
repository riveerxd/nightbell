"use client"

import { Globe, Server, Smartphone } from "lucide-react"
import { motion } from "motion/react"

import { useStill } from "@/lib/use-still"
import { cn } from "@/lib/utils"

import { Deep, DeepGroup } from "./deep"
import { PhoneFrame } from "./depth"
import { SectionHeading, Serif } from "./section"

const ROWS: [string, string, string, string][] = [
  ["Infrastructure", "Theirs. Nothing to run.", "A box, a container, a certificate, and monitoring for the monitor.", "The phone you already carry."],
  ["Account", "Required.", "Usually required, at least locally.", "None. There is nothing to sign up to."],
  ["Where tokens sit", "On their servers.", "On your server.", "On the device: monitors in one JSON document, sign-in secrets in an encrypted keystore vault."],
  ["Paging you", "Usually the paid tier.", "Yours to build, on top of a push service.", "A local alarm, so there is nothing to bill for."],
  ["At 3am you get", "An email or a push notification.", "Whatever you wired up.", "A full-screen page and an alarm that loops until you acknowledge it."],
  [
    "It cannot",
    "Fail quietly. You will get a bill.",
    "Watch itself.",
    "Check while the phone is off, or beat Android's 15 minute background floor without a foreground service.",
  ],
]

const COLS = [
  { icon: Globe, name: "Hosted service", eg: "like UptimeRobot" },
  { icon: Server, name: "Self-hosted", eg: "like Uptime Kuma" },
  { icon: Smartphone, name: "Nightbell", eg: "on your own phone" },
]

// "At 3am you get" is the whole argument, so it is the headline of each card
const LEAD = 4

/*
  Three answers to the same six questions. The two usual ones sit side by side,
  quieter; the phone gets the full width under them, in the accent, with the
  real alarm climbing out of its card. Everything is visible at once: an earlier
  pinned stack buried the first two cards under the third. The only motion is
  each card rising into place as it is reached.
*/
function Card({ i, wide = false }: { i: number; wide?: boolean }) {
  const c = COLS[i]
  const last = i === COLS.length - 1
  const still = useStill()
  const rest = ROWS.map((r, k) => [k, r] as const).filter(([k]) => k !== LEAD)
  return (
    <motion.section
      aria-labelledby={`cmp-${i}`}
      initial={still ? false : { opacity: 0, y: 40 }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={{ once: true, amount: 0.25 }}
      transition={{ duration: 0.7, delay: wide ? 0.1 : i * 0.12, ease: [0.16, 1, 0.3, 1] }}
      className={cn("relative", wide && "mt-16 sm:mt-20")}
    >
      {last && (
        <div
          className="pointer-events-none absolute -top-14 right-4 z-10 w-[5.5rem] rotate-[8deg] sm:-top-20 sm:right-12 sm:w-[10rem] lg:-top-28 lg:right-20 lg:w-[13rem]"
          aria-hidden
        >
          <PhoneFrame className="shadow-[0_50px_90px_-20px_rgb(0_0_0/0.75)]">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src="/hero/alarm.webp" alt="" width={1080} height={2400} loading="lazy" className="block w-full" />
          </PhoneFrame>
        </div>
      )}
      <div
        className={cn(
          "relative flex h-full flex-col overflow-hidden rounded-[1.75rem] p-6 sm:p-10",
          last ? "bg-primary shadow-elev-3 text-white" : "bg-surface shadow-elev-2"
        )}
      >
        <div className={cn("flex flex-wrap items-baseline gap-x-4 gap-y-1", last && "pr-20 sm:pr-44 lg:pr-64")}>
          <h3 id={`cmp-${i}`} className="flex items-center gap-2.5 text-lg font-semibold">
            <c.icon className="size-5 shrink-0" strokeWidth={1.75} aria-hidden />
            {c.name}
          </h3>
          <p className={cn("font-mono text-xs", last ? "text-white" : "text-muted-foreground")}>{c.eg}</p>
        </div>
        <dl className={cn("mt-6 grid gap-x-10 gap-y-5", wide ? "sm:grid-cols-2 lg:grid-cols-3 lg:pr-64" : "sm:grid-cols-2")}>
          <div className={cn(wide ? "sm:col-span-2 lg:col-span-3" : "sm:col-span-2", last && "pr-20 sm:pr-44 lg:pr-0")}>
            <dt className={cn("font-mono text-[11px] tracking-[0.14em] uppercase", last ? "text-white" : "text-muted-foreground")}>
              {ROWS[LEAD][0]}
            </dt>
            <dd className={cn("display mt-2 text-balance", wide ? "text-[clamp(2.2rem,4.8vw,4.6rem)]" : "text-[clamp(2rem,3.4vw,3.2rem)]")}>
              {ROWS[LEAD][i + 1]}
            </dd>
          </div>
          {rest.map(([k, r]) => (
            <div key={k}>
              <dt className={cn("font-mono text-[11px] tracking-[0.14em] uppercase", last ? "text-white" : "text-muted-foreground")}>
                {r[0]}
              </dt>
              <dd className={cn("mt-1.5 text-[15px] leading-snug", last && "font-medium")}>{r[i + 1]}</dd>
            </div>
          ))}
        </dl>
      </div>
    </motion.section>
  )
}

function Compare() {
  return (
    <div className="mt-14">
      <div className="grid gap-5 lg:grid-cols-2">
        <Card i={0} />
        <Card i={1} />
      </div>
      <Card i={2} wide />
    </div>
  )
}

export function Why() {
  return (
    <section id="why" className="relative py-20 sm:py-28">
      <div className="container-page">
        <SectionHeading
          title={
            <>
              Hosted, self-hosted, or <Serif className="text-brand">the phone in your pocket.</Serif>
            </>
          }
          lede="Both of the usual answers are better at being a monitoring service. Nightbell is trying to be the thing that wakes you at 3am without a subscription, a server or an account."
        />

        <Compare />

        <DeepGroup className="mt-14">
          <Deep title="Why I built it instead of paying for one">
            <p>
              I kept missing outages because ordinary monitoring notifications behaved like
              everything else in the shade. The services I tried either wanted a subscription before
              they would text me, or sent something that looked exactly like a marketing email and
              got swiped away with one. By the time I noticed, the interesting part of the incident
              was over.
            </p>
            <p>
              The hard part is alerting without crying wolf. An outage on an urgent monitor is not a
              line in the shade. It behaves like an incoming call, and everything else in the app
              exists to earn the right to do that.
            </p>
          </Deep>
        </DeepGroup>
      </div>
    </section>
  )
}
