"use client"

import { useRef, useSyncExternalStore, type ReactNode } from "react"
import { motion, useReducedMotion, useScroll, useTransform } from "motion/react"

import { cn } from "@/lib/utils"

/*
  Layers that travel at different speeds through the viewport read as sitting at
  different distances. `speed` is how far the layer travels on top of the scroll
  while its box crosses the screen, as a fraction of the viewport: positive runs
  ahead of the page and reads as closer, negative lags and reads as further.
*/
export function Parallax({
  speed = 0.1,
  rotate = 0,
  className,
  children,
}: {
  speed?: number
  rotate?: number
  className?: string
  children: ReactNode
}) {
  const ref = useRef<HTMLDivElement>(null)
  const still = useReducedMotion()
  const { scrollYProgress } = useScroll({ target: ref, offset: ["start end", "end start"] })
  const y = useTransform(scrollYProgress, [0, 1], [`${speed * 50}vh`, `${-speed * 50}vh`])
  const r = useTransform(scrollYProgress, [0, 1], [rotate, -rotate])
  return (
    <motion.div ref={ref} style={still ? undefined : { y, rotate: r }} className={cn("will-change-transform", className)}>
      {children}
    </motion.div>
  )
}

const WIDE = "(min-width: 1024px) and (prefers-reduced-motion: no-preference)"
function subscribeWide(cb: () => void) {
  const m = window.matchMedia(WIDE)
  m.addEventListener("change", cb)
  return () => m.removeEventListener("change", cb)
}
/* Pinned and panned scenes only where there is room for them and motion is welcome. */
export function useWide() {
  return useSyncExternalStore(subscribeWide, () => window.matchMedia(WIDE).matches, () => false)
}

/*
  A phone drawn in CSS, proportioned like a real one at any size. Every measure
  is a share of the phone itself: corners are percentages of the frame's own box
  (horizontal / vertical, so they stay circular on a tall shape), and the bezel
  is the screen's margin, which resolves against the frame's width. Fixed rem
  values made a small phone a pill with a thick rim.

  A turned phone shows its side wall on the edge that turns away, so `edge`
  draws a darker copy of the frame behind it, shifted toward that side. It is
  flat on purpose. Ten slabs stacked in depth looked right in desktop Chrome,
  but phone GPUs sometimes sorted them in front of the screen while the phone
  moved, and the screen flashed grey. The shadow is a filter on a flat wrapper,
  because as a box-shadow on a turned layer Chrome cut it off at the layer edge.
*/
export function PhoneFrame({
  children,
  className,
  edge,
  edgeFrom,
}: {
  children: ReactNode
  className?: string
  edge?: "left" | "right"
  /** For a phone only turned from a breakpoint up: below it, it is flat and gets no wall. */
  edgeFrom?: "md"
}) {
  return (
    <div className="phone-shadow">
      <div className={cn("relative rounded-[13%/5.9%]", className, "shadow-none")}>
        {edge && (
          <div
            aria-hidden
            className={cn(
              edgeFrom === "md" && "hidden md:block",
              "absolute inset-y-[0.4%] rounded-[13%/5.9%] bg-[#16171c] dark:bg-[#2c2f37]",
              edge === "right" ? "right-[-1.6%] left-[1.6%]" : "right-[1.6%] left-[-1.6%]"
            )}
          />
        )}
        {/* the outline only on a phone seen straight on: on a turned one it drew a
            pale line between the side wall and the bezel */}
        <div
          className={cn(
            // flow-root keeps the screen's margin inside the body: without it the
            // top and bottom margins collapsed through, and phones had a bezel on
            // the sides only
            "relative flow-root rounded-[13%/5.9%] bg-[#16171c] dark:bg-[#2c2f37]",
            !edge && "ring-1 ring-black/25 dark:ring-white/16",
            edge && edgeFrom === "md" && "ring-1 ring-black/25 md:ring-0 dark:ring-white/16"
          )}
        >
          <div className="relative m-[2.2%] overflow-hidden rounded-[11%/4.95%] bg-black">{children}</div>
        </div>
      </div>
    </div>
  )
}
