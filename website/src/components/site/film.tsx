"use client"

import { useLayoutEffect, useRef, useState } from "react"
import { Play, X } from "lucide-react"
import { AnimatePresence, motion, useReducedMotion, useScroll, useTransform } from "motion/react"

import media from "@/lib/media.json"
import { MEDIA } from "@/lib/site"

const TRANSCRIPT = [
  { t: "0:01", s: "Most uptime alerts are one more notification you swipe away." },
  { t: "0:05", s: "Nightbell runs entirely on your Android phone. No server, no account, no third party." },
  { t: "0:12", s: "Four steps to add a monitor. Point it at anything that answers over HTTP." },
  { t: "0:17", s: "Or at a GitHub repository, and it reports what changed." },
  { t: "0:23", s: "When a check fails, Nightbell doesn’t send you something you can miss." },
  { t: "0:28", s: "It takes the whole screen, and the alarm loops until you acknowledge it." },
  { t: "0:33", s: "Nightbell. The uptime monitor that needs no server, no account, and actually wakes you up." },
]

const mmss = (s: number) => `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, "0")}`

/*
  The poster starts as an object on the page, tipped back and lifted off it, and
  as you scroll it straightens and grows to the width of the page's content, the
  way a screen comes up to meet you. It used to run on to the edges of the
  window and lose its corners, which read as the page breaking rather than the
  film arriving. It pivots on its bottom edge: pivoting on
  the top swung the near edge out over the chapter buttons and swallowed their
  taps. Under reduced motion it is simply flat.
*/
export function Film() {
  const [open, setOpen] = useState(false)
  const [start, setStart] = useState(0)
  const closeBtn = useRef<HTMLButtonElement>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const stage = useRef<HTMLDivElement>(null)
  const still = useReducedMotion()
  // Straight and full size by the time the poster is in the middle of the
  // screen, which is where it is watched. Finishing as its top reached the top
  // of the screen meant it only settled on the way out.
  const { scrollYProgress } = useScroll({ target: stage, offset: ["start end", "center center"] })
  const scale = useTransform(scrollYProgress, [0, 1], [0.62, 1])
  const tilt = useTransform(scrollYProgress, [0, 1], [24, 0])

  const play = (at = 0) => {
    setStart(at)
    setOpen(true)
  }

  // A layout effect, so Escape works from the first frame the dialog is on
  // screen; a plain effect left a window after paint where it did nothing.
  useLayoutEffect(() => {
    if (!open) return
    const opener = trigger.current
    closeBtn.current?.focus()
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false)
    window.addEventListener("keydown", onKey)
    document.body.style.overflow = "hidden"
    return () => {
      window.removeEventListener("keydown", onKey)
      document.body.style.overflow = ""
      opener?.focus({ preventScroll: true })
    }
  }, [open])

  return (
    <section id="film" aria-label="The forty second film" className="relative pt-16 pb-24 sm:pb-32">
      <div className="container-page mb-10 flex flex-wrap items-end justify-between gap-6">
        <h2 className="display max-w-3xl text-[clamp(2.6rem,6.4vw,5.75rem)]">
          Forty seconds, <span className="text-muted-foreground">start to alarm.</span>
        </h2>
        <p className="text-muted-foreground font-mono text-sm">{mmss(media.promo.seconds)}, captions on</p>
      </div>

      <div ref={stage} className="container-page [perspective:1800px]">
        <motion.div
          style={still ? undefined : { scale, rotateX: tilt }}
          className="shadow-elev-3 relative origin-bottom overflow-hidden rounded-[1.75rem] bg-black"
        >
          <button
            ref={trigger}
            type="button"
            onClick={() => play(0)}
            aria-label="Play the forty second Nightbell film"
            className="group relative block w-full"
          >
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={MEDIA.promoPoster}
              alt="The Nightbell urgent alert on a phone, beside the words When it breaks, Nightbell wakes you up."
              width={1920}
              height={1080}
              loading="lazy"
              className="aspect-video max-h-[100dvh] w-full object-cover transition-[filter] duration-700 group-hover:brightness-90"
            />
            {/* in the poster's empty corner: centred, it sat on the poster's own headline */}
            <span className="absolute inset-0 flex items-end justify-start p-4 sm:p-8">
              <span className="flex size-16 items-center justify-center rounded-full bg-white/15 ring-1 ring-white/25 backdrop-blur-md transition-transform duration-300 group-hover:scale-110 sm:size-24">
                <span className="flex size-12 items-center justify-center rounded-full bg-white text-black shadow-2xl sm:size-16">
                  <Play className="ml-0.5 size-5 fill-current sm:ml-1 sm:size-6" aria-hidden />
                </span>
              </span>
            </span>
          </button>
        </motion.div>
      </div>

      <div className="container-page">
        <ol className="mt-6 flex snap-x gap-2 overflow-x-auto pb-2 lg:grid lg:grid-cols-6 lg:overflow-visible" aria-label="Chapters">
          {media.promo.chapters.map((c) => (
            <li key={c.at} className="shrink-0 snap-start">
              <button
                type="button"
                onClick={() => play(c.at)}
                className="bg-surface shadow-elev-1 hover:bg-surface-2 w-36 rounded-2xl px-4 py-3 text-left transition-[background-color,transform] hover:-translate-y-0.5 lg:w-full"
              >
                <span className="text-muted-foreground block font-mono text-[11px]">{mmss(c.at)}</span>
                <span className="mt-1 block text-sm font-medium">{c.label}</span>
              </button>
            </li>
          ))}
        </ol>

        <details className="group bg-surface shadow-elev-1 mt-3 rounded-2xl">
          <summary className="text-muted-foreground hover:text-foreground flex min-h-11 cursor-pointer list-none items-center justify-between px-4 py-3 text-sm transition-colors [&::-webkit-details-marker]:hidden">
            Read the video transcript
            <span className="font-mono text-xs transition-transform group-open:rotate-45">+</span>
          </summary>
          <ol className="space-y-2.5 px-4 pb-5">
            {TRANSCRIPT.map((l) => (
              <li key={l.t} className="flex gap-4 text-[15px]">
                <time className="text-muted-foreground w-10 shrink-0 font-mono text-xs leading-6">{l.t}</time>
                <span>{l.s}</span>
              </li>
            ))}
          </ol>
        </details>
      </div>

      <AnimatePresence>
        {open && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="fixed inset-0 z-[60] flex items-center justify-center bg-black/80 p-4 backdrop-blur-md"
            onClick={() => setOpen(false)}
            role="dialog"
            aria-modal="true"
            aria-label="Nightbell film"
          >
            <motion.div
              initial={{ scale: 0.92, opacity: 0 }}
              animate={{ scale: 1, opacity: 1 }}
              exit={{ scale: 0.95, opacity: 0 }}
              transition={{ type: "spring", damping: 30, stiffness: 300 }}
              className="relative w-full max-w-5xl"
              onClick={(e) => e.stopPropagation()}
            >
              <button
                ref={closeBtn}
                type="button"
                onClick={() => setOpen(false)}
                className="absolute -top-12 right-0 flex size-11 items-center justify-center rounded-full bg-white/10 text-white ring-1 ring-white/20 hover:bg-white/20"
                aria-label="Close the film"
              >
                <X className="size-5" />
              </button>
              <video
                className="aspect-video w-full rounded-2xl bg-black shadow-2xl ring-1 ring-white/10"
                src={MEDIA.promoFile}
                poster={MEDIA.promoPoster}
                controls
                autoPlay
                playsInline
                onLoadedMetadata={(e) => {
                  if (start) e.currentTarget.currentTime = start
                }}
              >
                <track kind="captions" src="/media/nightbell-promo.en.vtt" srcLang="en" label="English" default />
              </video>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>
    </section>
  )
}
