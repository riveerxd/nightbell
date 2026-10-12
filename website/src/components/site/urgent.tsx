"use client"

import { useEffect, useRef } from "react"
import { motion, useReducedMotion } from "motion/react"

import media from "@/lib/media.json"
import { cn } from "@/lib/utils"

import { Deep, DeepGroup } from "./deep"
import { PhoneFrame } from "./depth"

/*
  One outage, told in the order it reaches you. The screens are real captures and
  read 3:14, so that is the time the first two carry. The third is the repeat,
  which has no clock of its own, only the default interval.
*/
const MOMENTS = [
  {
    time: "3:14",
    title: "It gets through",
    body: "A dedicated alarm channel that bypasses Do Not Disturb. The notification is ongoing, so there is no swipe that clears it.",
    src: "/screens/urgent-lock.webp",
    alt: "A locked phone at 3:14 with a red Nightbell notification at the top: Checkout API is down, Connection refused or dropped.",
  },
  {
    time: "3:14",
    title: "It wakes the screen",
    body: "With full-screen alerts allowed, the page takes the screen and the siren loops until you act. A phone on silent still vibrates.",
    src: "/screens/urgent-alarm.webp",
    alt: "The full-screen Nightbell urgent page: DOWN, Checkout API, Connection refused, down for 20 minutes, four failed checks, with Acknowledge and Re-check now buttons and the line Repeats every 5 min until acknowledged.",
  },
  {
    time: "+5 min",
    title: "It keeps asking",
    body: "Posted again every five minutes until you acknowledge. Ack ends this outage only, and recovery re-arms it for the next one.",
    src: "/screens/urgent-detail.webp",
    alt: "The Nightbell monitor screen for Checkout API marked Down with Connection refused, a 90 per cent uptime ring for the past three hours, and Check, Pause and Mute 1h buttons.",
  },
]

const CONTROLS = [
  ["On the page", "Acknowledge, Re-check now"],
  ["In the shade", "Ack, Re-check, Mute for 1 hour"],
  ["Ringer switch", "Followed. Silent still vibrates"],
  ["Repeat", "Every 5 minutes by default"],
]

function Payoff() {
  const ref = useRef<HTMLVideoElement>(null)
  useEffect(() => {
    const v = ref.current
    if (!v) return
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return
    const io = new IntersectionObserver(([e]) => (e.isIntersecting ? v.play().catch(() => {}) : v.pause()), {
      threshold: 0.35,
    })
    io.observe(v)
    return () => io.disconnect()
  }, [])
  return (
    <figure>
      <video
        ref={ref}
        src={media.payoff.file}
        poster="/media/nightbell-alert-firing-poster.webp"
        muted
        loop
        playsInline
        preload="none"
        controls
        aria-label="A check fails, the monitor card turns red, the phone wakes, and the full-screen urgent page fills the screen."
        className="shadow-elev-3 aspect-video w-full rounded-[1.75rem] bg-black object-cover"
      />
      <figcaption className="text-muted-foreground mt-3 font-mono text-xs">
        An eleven second clip of a failed check becoming the alarm. No sound, and it loops.
      </figcaption>
    </figure>
  )
}

export function Urgent() {
  const still = useReducedMotion()
  const rise = (i: number) =>
    still
      ? {}
      : {
          // transform only: a half-faded screen fails contrast while it animates
          initial: { y: 56 },
          whileInView: { y: 0 },
          viewport: { once: true, amount: 0.3 },
          transition: { duration: 0.7, delay: i * 0.12, ease: [0.16, 1, 0.3, 1] as const },
        }

  return (
    <section
      id="urgent"
      className="relative overflow-x-clip bg-[#f8eef0] py-24 sm:py-32 dark:bg-[#11080a] dark:[--border:rgb(255_255_255/0.1)] dark:[--muted-foreground:#b9a3a6]"
    >
      <div className="bg-down/50 pointer-events-none absolute inset-x-0 top-0 h-px" aria-hidden />
      <div className="container-page">
        <div className="mx-auto max-w-6xl text-center">
          <h2 className="display text-[clamp(2.8rem,6.4vw,6rem)] text-balance">
            When it breaks, <span className="text-down">Nightbell wakes you up.</span>
          </h2>
          <p className="text-muted-foreground mx-auto mt-6 max-w-xl text-lg leading-relaxed text-pretty">
            Urgent is a per-monitor switch for the things you cannot sleep through. It is the one part
            of the app allowed to be loud.
          </p>
        </div>

        {/* three moments of one outage: the screen first, then the minute it happened */}
        <ol className="mt-16 grid gap-14 md:mt-20 md:grid-cols-3 md:grid-rows-[auto_auto] md:gap-x-8 md:gap-y-0 lg:gap-x-12">
          {MOMENTS.map((m, i) => (
            <li key={m.title} className="flex flex-col md:row-span-2 md:grid md:grid-rows-subgrid">
              <motion.div {...rise(i)} className="md:self-end [perspective:1600px]">
                <PhoneFrame
                  edge={i === 0 ? "left" : i === 2 ? "right" : undefined}
                  edgeFrom="md"
                  className={cn(
                    "shadow-elev-3 mx-auto w-[56%] max-w-[17rem] md:w-full",
                    i === 0 && "md:max-w-[14.5rem] md:[transform:rotateY(16deg)]",
                    i === 1 && "md:max-w-[17rem]",
                    i === 2 && "md:max-w-[14.5rem] md:[transform:rotateY(-16deg)]"
                  )}
                >
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={m.src} alt={m.alt} width={1080} height={2400} loading="lazy" className="block w-full" />
                </PhoneFrame>
              </motion.div>
              <div className="border-down/40 mt-10 border-t pt-5 md:mt-12">
                <p className="dotmatrix text-down text-4xl leading-none">{m.time}</p>
                <h3 className="mt-4 text-2xl font-semibold tracking-tight">{m.title}</h3>
                <p className="text-muted-foreground mt-2 max-w-sm text-[16px] leading-relaxed">{m.body}</p>
              </div>
            </li>
          ))}
        </ol>

        <div className="mt-24 grid items-center gap-12 lg:grid-cols-[1.6fr_1fr] lg:gap-16">
          <Payoff />
          <dl className="grid content-end">
            {CONTROLS.map(([k, v]) => (
              <div key={k} className="border-border grid grid-cols-[8.5rem_1fr] gap-4 border-t py-4">
                <dt className="text-muted-foreground font-mono text-[11px] leading-6 tracking-[0.12em] uppercase">{k}</dt>
                <dd className="text-[16px] leading-6">{v}</dd>
              </div>
            ))}
          </dl>
        </div>

        <DeepGroup className="mt-14">
          <Deep title="Exact acknowledgement behaviour">
            <p>
              Acknowledging stops the repeats for that outage and nothing else. The monitor stays red,
              the ordinary down notification stays where it was, and recovery re-arms the loop so the
              next outage shouts again. Urgent overrides cooldown and the repeat toggle, because that
              is the point, but it still honours the master switch, the per-monitor switch, mute and
              the failure threshold. It means &quot;do not let me miss this&quot;, not &quot;ignore
              everything I configured&quot;.
            </p>
          </Deep>
        </DeepGroup>
      </div>
    </section>
  )
}
