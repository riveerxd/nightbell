"use client"

import { Component, useEffect, useRef, useState, useSyncExternalStore, type ReactNode } from "react"
import dynamic from "next/dynamic"
import { ArrowRight, Check, Download, TriangleAlert } from "lucide-react"
import {
  AnimatePresence,
  motion,
  useAnimationFrame,
  useMotionValue,
  useMotionValueEvent,
  useScroll,
  useTransform,
  type MotionValue,
} from "motion/react"

import { Ripple } from "@/components/ui/ripple"
import { ShimmerLink } from "@/components/ui/shimmer-link"
import { APK_MB, CHANNELS, DOWNLOAD_PATH } from "@/lib/site"
import { cn } from "@/lib/utils"

import { PhoneFrame } from "./depth"

/*
  The page plays the product. A phone rests beside the headline showing the
  real monitor list. Scroll, and it turns to face you while 03:14 comes up
  behind it, the lockscreen card lands, the screen goes red and the phone starts
  to buzz, and it keeps buzzing while it steps aside for the words, until
  Acknowledge is pressed. That is the app's own promise, played straight.

  The scroll is never hijacked: the section is tall and the stage is sticky, so
  the reader's own scroll drives every beat. The phone is WebGL; before it loads,
  without WebGL, or under reduced motion, the same phone is an image and the
  stage is an ordinary first screen.
*/

const PhoneScene = dynamic(() => import("./phone-scene"), { ssr: false, loading: () => null })

function subscribeMedia(q: string) {
  return (cb: () => void) => {
    const m = window.matchMedia(q)
    m.addEventListener("change", cb)
    return () => m.removeEventListener("change", cb)
  }
}
function useMedia(q: string, server = false) {
  return useSyncExternalStore(subscribeMedia(q), () => window.matchMedia(q).matches, () => server)
}

// the head script has already decided, and marked the root
const hasWebGL = () => document.documentElement.classList.contains("gl3d")
const glListeners = new Set<() => void>()
const useWebGL = () =>
  useSyncExternalStore(
    (cb) => {
      glListeners.add(cb)
      return () => glListeners.delete(cb)
    },
    hasWebGL,
    () => false
  )
// A phone the head script let through can still fail to draw: the scene's
// chunk never arrives, a screen texture does not, or the GPU refuses or drops
// the context. Each of those left an empty stage or took the whole page down
// with it, so any of them hands the hero to the picture phone for good.
function drop3d() {
  document.documentElement.classList.remove("gl3d")
  glListeners.forEach((cb) => cb())
}

class SceneBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false }
  static getDerivedStateFromError() {
    return { failed: true }
  }
  componentDidCatch() {
    drop3d()
  }
  render() {
    return this.state.failed ? null : this.props.children
  }
}
// false in the server render, so nothing decided on the client is in the HTML
const useClient = () => useSyncExternalStore(() => () => {}, () => true, () => false)

/* The 3D phone starts once the page has loaded and the browser is idle, so it
   never competes with the first paint or with the reader's first scroll. */
function subscribeIdle(cb: () => void) {
  const go = () => {
    idle = true
    cb()
  }
  const later = () => ("requestIdleCallback" in window ? requestIdleCallback(go, { timeout: 1500 }) : setTimeout(go, 200))
  if (document.readyState === "complete") later()
  else window.addEventListener("load", later, { once: true })
  return () => window.removeEventListener("load", later)
}
let idle = false
const useIdle = () => useSyncExternalStore(subscribeIdle, () => idle, () => false)

function useClock() {
  const [s, setS] = useState(7)
  useEffect(() => {
    const t = setInterval(() => setS((v) => (v + 1) % 60), 1000)
    return () => clearInterval(t)
  }, [])
  return String(s).padStart(2, "0")
}

/*
  The phone as a picture: first paint, reduced motion, and every browser that
  does not get the 3D one, which is most older phones. The picture plays the
  same story: the lock screen and the alarm come up on the same beats, and
  `useFlatPose` moves it through the same poses. It was a still that faded out
  on the first scroll, and the alarm then played to an empty stage.
*/
function StaticPhone({ screens }: { screens?: Screens }) {
  return (
    <figure className="pointer-events-none">
      <PhoneFrame edge="right">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          src="/hero/calm.webp"
          srcSet="/hero/calm-540.webp 540w, /hero/calm-720.webp 720w, /hero/calm.webp 1080w"
          sizes="(min-width: 768px) 19rem, 17rem"
          alt="The Nightbell monitor list at 3:14: a green All 5 operational card, then Checkout API and Auth service with their response times."
          width={1080}
          height={2400}
          fetchPriority="high"
          className="block w-full"
        />
        {/* only where the picture is the phone that plays: a browser that draws
            the 3D one, or stays still, never downloads these */}
        {screens &&
          (["lock", "alarm", "quiet"] as const).map((k) => (
            <motion.img
              key={k}
              src={`/hero/${k}.webp`}
              alt=""
              width={1080}
              height={2400}
              decoding="async"
              style={{ opacity: screens[k] }}
              className="absolute inset-0 block size-full"
            />
          ))}
      </PhoneFrame>
      <figcaption className="sr-only">All 5 operational</figcaption>
    </figure>
  )
}

type Screens = { lock: MotionValue<number>; alarm: MotionValue<number>; quiet: MotionValue<number> }

const clamp01 = (v: number) => Math.min(1, Math.max(0, v))
const span = (p: number, a: number, b: number) => clamp01((p - a) / (b - a))
const smooth = (t: number) => t * t * (3 - 2 * t)
const lerp = (a: number, b: number, t: number) => a + (b - a) * t

type Pose = { x: number; y: number; s: number; rx: number; ry: number; rz: number }
// the tilt the picture has always had beside the headline
const REST: Pose = { x: 0, y: 0, s: 1, rx: 5, ry: -22, rz: 3 }

/*
  The 3D phone's choreography for the picture, in pixels and degrees: resting
  beside the headline, turned to face the reader from 0.1 to 0.3, stepped
  aside for the words from 0.4 to 0.5, floating while it waits and buzzing
  from the alarm until it is acknowledged. Eased toward each frame the way the
  3D one is, so a fast flick still reads as a motion.

  Poses are offsets from where the CSS put it, measured from the box around the
  picture, which is never transformed itself. The first paint and the first
  frame of the story are then the same picture in the same place, and nothing
  jumps when the script arrives.

  Only transform and opacity change, on one layer. An old phone that cannot
  draw WebGL can still do that at the full frame rate.
*/
function useFlatPose({
  on,
  story,
  acked,
  mobile,
  stage,
  box,
  panel,
}: {
  on: boolean
  story: MotionValue<number>
  acked: boolean
  mobile: boolean
  stage: React.RefObject<HTMLElement | null>
  box: React.RefObject<HTMLElement | null>
  panel: React.RefObject<HTMLElement | null>
}) {
  const x = useMotionValue(REST.x)
  const y = useMotionValue(REST.y)
  const scale = useMotionValue(REST.s)
  const rotateX = useMotionValue(REST.rx)
  const rotateY = useMotionValue(REST.ry)
  const rotateZ = useMotionValue(REST.rz)
  const lock = useMotionValue(0)
  const alarm = useMotionValue(0)
  const quiet = useMotionValue(0)
  const geo = useRef<{ sw: number; sh: number; top: number; panelTop: number } | null>(null)
  const pose = useRef<Pose | null>(null)
  const seen = useRef(true)

  useEffect(() => {
    const s = stage.current
    if (!on || !s) return
    const measure = () => {
      const header = document.querySelector("header")?.getBoundingClientRect().height ?? 64
      geo.current = {
        sw: s.clientWidth,
        sh: s.clientHeight,
        top: header + 16,
        // layout, not the box on screen: the panel slides up as it arrives
        panelTop: panel.current?.offsetTop ?? s.clientHeight / 2,
      }
    }
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(s)
    if (panel.current) ro.observe(panel.current)
    // nothing to move once the hero has scrolled away
    const io = new IntersectionObserver(([e]) => (seen.current = e.isIntersecting))
    io.observe(s)
    return () => {
      ro.disconnect()
      io.disconnect()
    }
  }, [on, stage, panel])

  useAnimationFrame((ms, delta) => {
    const s = stage.current
    const b = box.current
    if (!on || !geo.current || !s || !b || !seen.current) return
    // Read every frame. On a phone the box is anchored under the buttons, and
    // those rise with the copy as it fades, so a size observer never hears that
    // it moved; measured once, the phone jumped 60px when anything else
    // remeasured. Nothing here writes layout, so the read is cheap.
    const sr = s.getBoundingClientRect()
    const br = b.getBoundingClientRect()
    const g = { ...geo.current, cx: br.left - sr.left + br.width / 2, cy: br.top - sr.top + br.height / 2, h: br.height }
    const p = story.get()
    const t = ms / 1000
    // A phone this falls back on can draw at four frames a second; held to
    // 100ms a step, every ease ran two and a half times slower than meant
    const dt = Math.min(delta, 250) / 1000

    // the captions sit on the bottom eighth, two lines at most
    const floor = g.sh * 0.92 - 88
    const fitFace = Math.min(mobile ? 1 : 1.15, (floor - g.top) / g.h)
    const face: Pose = { x: g.sw / 2 - g.cx, y: (g.top + floor) / 2 - g.cy, s: fitFace, rx: 0, ry: 0, rz: 0 }
    // On a phone the words take the bottom of the screen, so the phone steps up
    // above them, smaller, rather than off the top edge the way the 3D one does.
    const roof = mobile ? g.panelTop - 20 : g.sh - 40
    const fitSide = Math.max(0.3, Math.min(mobile ? 1 : 1.05, (roof - g.top) / g.h))
    const side: Pose = mobile
      ? { x: g.sw / 2 - g.cx, y: g.top + (g.h * fitSide) / 2 - g.cy, s: fitSide, rx: 2, ry: 8, rz: -2 }
      : { x: -g.h * 0.04, y: (g.top + roof) / 2 - g.cy, s: fitSide, rx: 0, ry: -12, rz: -1 }

    // The turn is done by 0.22, sooner than the 3D one's 0.3: the first caption
    // comes up at 0.2, and a picture still on its way, still large, ran into it.
    const m = smooth(span(p, 0.1, 0.22))
    const a = smooth(span(p, 0.4, 0.5))
    const target = { ...REST }
    for (const k of Object.keys(target) as (keyof Pose)[]) target[k] = lerp(lerp(REST[k], face[k], m), side[k], a)

    const idle = 1 - m
    target.y += Math.sin(t * 0.9) * 6 * idle
    target.ry += Math.sin(t * 0.5) * 4 * idle

    // a page opened part way down starts there instead of flying in
    const c = (pose.current ??= { ...target })
    const k = 1 - Math.exp(-dt * 7)
    for (const key of Object.keys(c) as (keyof Pose)[]) c[key] = lerp(c[key], target[key], k)

    // the buzz goes on after the easing, which would otherwise eat it; the motor
    // runs in bursts, as in the 3D phone
    const ring = acked ? 0 : span(p, 0.33, 0.37)
    const buzz = ring * (Math.sin(t * 5) > 0 ? 1 : 0.15)
    x.set(c.x + Math.sin(t * 70) * 3.5 * buzz)
    y.set(c.y + Math.sin(t * 62) * 1.5 * buzz)
    scale.set(c.s)
    rotateX.set(c.rx)
    rotateY.set(c.ry)
    rotateZ.set(c.rz + Math.sin(t * 55) * 1.2 * buzz)

    lock.set(smooth(span(p, 0.22, 0.28)))
    const al = smooth(span(p, 0.32, 0.36))
    alarm.set(al)
    // acknowledged, the page closes back to the lock screen with nothing on it
    const qTo = acked && al > 0.5 ? 1 : 0
    const q = quiet.get()
    quiet.set(Math.abs(q - qTo) < 0.002 ? qTo : lerp(q, qTo, 1 - Math.exp(-dt * 5)))
  })

  return { style: { x, y, scale, rotateX, rotateY, rotateZ }, screens: { lock, alarm, quiet } }
}

const WORDS = ["The", "uptime", "monitor", "that", "actually", "wakes", "you", "up."]

/*
  Each word rises out of its own mask at its final size. Only transform moves,
  so the layout is final from the first frame: an earlier version animated the
  width axis, which rewrapped the headline every frame and shoved the copy under
  it up and down while the page loaded.
*/
function Headline() {
  return (
    <h1 className="display text-[clamp(2.6rem,10.4vw,4.6rem)] text-balance sm:text-[clamp(3.6rem,5.6vw,6.8rem)]">
      {WORDS.map((w, i) => (
        <span key={i}>
          <span className="inline-block overflow-hidden pb-[0.06em] align-bottom">
            <span className={cn("rise inline-block", i >= 5 && "text-down")} style={{ animationDelay: `${80 + i * 45}ms` }}>
              {w}
            </span>
          </span>
          {i < WORDS.length - 1 && " "}
        </span>
      ))}
    </h1>
  )
}

/* The last beat: the phone keeps ringing beside this until it is acknowledged. */
function Ringing({ acked, onAck, sec }: { acked: boolean; onAck: () => void; sec: string }) {
  return (
    <div className="max-w-xl">
      <p className="text-down flex items-center gap-2 font-mono text-xs font-semibold tracking-[0.18em] uppercase">
        <TriangleAlert className="size-4" strokeWidth={2.5} aria-hidden />
        {acked ? "Acknowledged" : "Urgent, first alert"}
        <span className="text-muted-foreground tabular-nums">03:14:{sec}</span>
      </p>
      <h2 className="display mt-4 text-[clamp(3.2rem,7vw,7.4rem)]">
        Checkout API <span className={cn("transition-colors duration-700", acked ? "text-muted-foreground" : "text-down")}>is down.</span>
      </h2>
      <p className="text-muted-foreground mt-4 text-lg sm:text-xl">
        Connection refused. 4 checks failed, down for 20m {sec}s.
      </p>
      <div className="mt-8 min-h-14">
        <AnimatePresence mode="wait" initial={false}>
          {acked ? (
            <motion.p
              key="done"
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              className="flex max-w-md items-start gap-3 text-[17px] leading-snug"
            >
              <Check className="text-up mt-0.5 size-5 shrink-0" aria-hidden />
              Silenced for this outage, and nothing else. The monitor stays red until it recovers,
              and recovery re-arms the alarm.
            </motion.p>
          ) : (
            <motion.div key="ack" exit={{ opacity: 0, y: -8 }} className="flex flex-wrap items-center gap-x-5 gap-y-3">
              {/* rings going out from the button for as long as the phone rings:
                  the one control on the page that answers the alarm */}
              <span className="relative inline-flex">
                <span aria-hidden className="ack-ring border-down pointer-events-none absolute inset-0 rounded-full border-2 motion-reduce:hidden" />
                <span aria-hidden className="ack-ring border-down pointer-events-none absolute inset-0 rounded-full border-2 [animation-delay:1.2s] motion-reduce:hidden" />
                <button
                  type="button"
                  onClick={onAck}
                  className="shadow-elev-2 relative h-14 rounded-full bg-[#ff5c66] px-9 text-base font-semibold text-black transition-transform hover:scale-[1.03] active:scale-[0.97]"
                >
                  Acknowledge
                </button>
              </span>
              <span className="text-muted-foreground text-sm">Repeats every 5 min until you do.</span>
            </motion.div>
          )}
        </AnimatePresence>
      </div>
    </div>
  )
}

/*
  Where the phone may start on a narrow screen: just under the buttons, as a
  share of the stage's height. Measured, because whether the two buttons share
  a row depends on the width and the font, and a fixed spot put the phone on
  top of the F-Droid button on some phones and a third of a screen below it on
  others. Layout offsets rather than the on-screen box, so the scroll that
  lifts the copy away does not move the answer.
*/
function useRestTop(row: React.RefObject<HTMLElement | null>, stage: React.RefObject<HTMLElement | null>) {
  const [top, setTop] = useState<number | null>(null)
  useEffect(() => {
    const r = row.current
    const s = stage.current
    if (!r || !s) return
    const measure = () => {
      let y = r.offsetHeight
      for (let el: HTMLElement | null = r; el && el !== s; el = el.offsetParent as HTMLElement | null) y += el.offsetTop
      setTop((y + 24) / s.clientHeight)
    }
    const ro = new ResizeObserver(measure)
    ro.observe(r)
    ro.observe(s)
    return () => ro.disconnect()
  }, [row, stage])
  return top
}

export function Hero() {
  const ref = useRef<HTMLElement>(null)
  const stage = useRef<HTMLDivElement>(null)
  const ctaRow = useRef<HTMLDivElement>(null)
  const flatBox = useRef<HTMLDivElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const restTop = useRestTop(ctaRow, stage)
  const [acked, setAcked] = useState(false)
  const [live, setLive] = useState(false)
  const [ready, setReady] = useState(false)
  const sec = useClock()
  const mobile = !useMedia("(min-width: 768px)", true)
  const still = useMedia("(prefers-reduced-motion: reduce)")
  const gl = useWebGL()
  const ready3d = useIdle()
  const three = gl && !still && ready3d
  const flat = useClient() && !gl && !still
  const { scrollYProgress } = useScroll({ target: ref, offset: ["start start", "end end"] })
  // Every beat below is timed on 0 to 0.5. The story is finished at 80% of the
  // pinned scroll, so the last beat, the phone ringing beside the words, holds
  // for well under a screen instead of a screen and a half before the page moves on.
  const story = useTransform(scrollYProgress, [0, 0.8], [0, 0.5])

  useMotionValueEvent(story, "change", (p) => {
    // Unpinned under reduced motion, progress stops meaning time of night.
    // Asked here rather than cached: useScroll reports from a layout effect,
    // before a plain effect could have stored the answer.
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return
    setLive(p > 0.44)
    if (p < 0.2 && acked) setAcked(false)
  })

  const copyOpacity = useTransform(story, [0.08, 0.2], [1, 0])
  const copyY = useTransform(story, [0.08, 0.2], [0, -60])
  const capA = useTransform(story, [0.2, 0.24, 0.29, 0.32], [0, 1, 1, 0])
  const capB = useTransform(story, [0.33, 0.36, 0.41, 0.44], [0, 1, 1, 0])
  const ring = useTransform(story, [0.33, 0.37], [0, 1])
  const panel = useTransform(story, [0.42, 0.5], [0, 1])
  const panelY = useTransform(story, [0.42, 0.5], [40, 0])
  // the rings follow the phone to the side it steps to
  const ringX = useTransform(story, [0.4, 0.5], ["0%", mobile ? "0%" : "22%"])
  const ringY = useTransform(story, [0.4, 0.5], ["0%", mobile ? "-22%" : "0%"])
  const flatPose = useFlatPose({ on: flat, story, acked, mobile, stage, box: flatBox, panel: panelRef })

  return (
    <section
      id="top"
      ref={ref}
      aria-label="Nightbell, the uptime monitor that actually wakes you up"
      className="relative h-[290vh] motion-reduce:h-auto"
    >
      <div
        ref={stage}
        className="sticky top-0 h-dvh overflow-hidden motion-reduce:static motion-reduce:h-auto motion-reduce:min-h-dvh max-md:motion-reduce:flex max-md:motion-reduce:flex-col"
      >
        <motion.div
          style={{ opacity: acked ? 0 : ring, x: ringX, y: ringY }}
          className="pointer-events-none absolute inset-0 opacity-0 transition-opacity duration-700 motion-reduce:hidden"
          aria-hidden
        >
          <Ripple mainCircleSize={340} mainCircleOpacity={0.2} numCircles={6} className="[--foreground:var(--down)]" />
        </motion.div>

        {/* the phone */}
        <div
          className="absolute inset-0"
          role="img"
          aria-label="A phone showing the Nightbell monitor list. As the page scrolls, Checkout API fails, the lockscreen alert arrives, and the full-screen urgent page takes over the phone and rings until it is acknowledged."
        >
          {three && (
            // above the picture, so the picture stays solid underneath until the
            // 3D phone has fully faded in; crossfading the two left a pale ghost
            <div className={cn("absolute inset-0 z-[1] transition-opacity duration-500", ready ? "opacity-100" : "opacity-0")}>
              <SceneBoundary>
                <PhoneScene progress={story} mobile={mobile} restTop={restTop} acked={acked} onReady={() => setReady(true)} onFail={drop3d} />
              </SceneBoundary>
            </div>
          )}
        </div>
        <div
          ref={flatBox}
          className={cn(
            "hero-static absolute w-[64vw] max-w-[17rem]",
            "top-[56%] left-[20%] md:top-1/2 md:bottom-auto md:left-auto md:right-[9%] md:w-[clamp(14rem,21vw,19rem)] md:max-w-none md:translate-x-0 md:-translate-y-1/2",
            // Where the 3D phone will be drawn, the picture never shows: it was a
            // second phone in a slightly different pose for the moment the two
            // overlapped. Decided in the head script, before the first paint.
            "[.gl3d_&]:invisible",
            flat && "hero-flat",
            // Unpinned on a phone, a picture hung below the buttons ran down over
            // the next section, so there it takes its place in the page instead.
            "max-md:motion-reduce:relative max-md:motion-reduce:inset-auto max-md:motion-reduce:order-last max-md:motion-reduce:mx-auto max-md:motion-reduce:mt-12 max-md:motion-reduce:mb-20"
          )}
        >
          <motion.div style={{ ...flatPose.style, transformPerspective: 1600 }} className="will-change-transform">
            <StaticPhone screens={flat ? flatPose.screens : undefined} />
          </motion.div>
        </div>

        {/* the copy */}
        <motion.div
          style={{ opacity: copyOpacity, y: copyY }}
          className="container-page relative z-10 flex h-full flex-col pt-24 sm:pt-28 md:justify-center md:pt-16 motion-reduce:!transform-none motion-reduce:!opacity-100"
        >
          <div className="max-w-[46rem] md:max-w-[52%]">
            <Headline />
            <p className="rise-soft text-muted-foreground mt-6 max-w-md text-lg leading-relaxed sm:text-xl" style={{ animationDelay: "520ms" }}>
              An open-source Android app that runs every check from your phone. No server, no
              account.
            </p>
            <div ref={ctaRow} className="hero-cta rise-soft mt-8 flex flex-wrap items-center gap-3" style={{ animationDelay: "640ms" }}>
              <ShimmerLink
                href={DOWNLOAD_PATH}
                background="#2d67f5"
                shimmerColor="#ffffff"
                shimmerSize="0.1em"
                className="shadow-elev-2 h-12 gap-2.5 px-6 text-[15px] font-semibold"
              >
                <Download className="size-[18px]" aria-hidden />
                Download APK
                <span className="font-mono text-xs opacity-70">{APK_MB} MB</span>
              </ShimmerLink>
              <a
                href={CHANNELS.fdroid.url}
                target="_blank"
                rel="noopener"
                className="bg-surface/80 shadow-elev-1 hover:bg-surface-2 inline-flex h-12 items-center gap-2 rounded-full px-5 text-[15px] font-semibold backdrop-blur transition-colors"
              >
                Get it on F-Droid
                <ArrowRight className="size-4" aria-hidden />
              </a>
            </div>
          </div>
        </motion.div>

        {/* what is happening, one line at a time */}
        <div className="pointer-events-none absolute inset-x-0 bottom-[8vh] z-10 text-center motion-reduce:hidden" aria-hidden>
          <motion.p style={{ opacity: capA }} className="display absolute inset-x-0 bottom-0 px-5 text-[clamp(1.6rem,3vw,2.6rem)] opacity-0">
            03:14. Checkout API stops answering.
          </motion.p>
          <motion.p style={{ opacity: capB }} className="display absolute inset-x-0 bottom-0 px-5 text-[clamp(1.6rem,3vw,2.6rem)] opacity-0">
            It is marked urgent, <span className="text-down">so it rings.</span>
          </motion.p>
        </div>

        {/* and the phone keeps ringing beside the words until you answer it */}
        <motion.div
          ref={panelRef}
          style={{ opacity: panel, y: panelY }}
          className={cn(
            "container-page absolute inset-x-0 bottom-[7vh] z-20 opacity-0 md:top-1/2 md:bottom-auto md:-translate-y-1/2 motion-reduce:hidden",
            !live && "pointer-events-none"
          )}
          aria-hidden={!live}
          inert={!live}
        >
          <Ringing acked={acked} onAck={() => setAcked(true)} sec={sec} />
        </motion.div>
      </div>
    </section>
  )
}
