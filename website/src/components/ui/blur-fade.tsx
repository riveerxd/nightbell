import type { CSSProperties, ReactNode } from "react"

import { cn } from "@/lib/utils"

/*
  Magic UI's BlurFade, rebuilt without script. The original server-rendered
  every block at opacity 0 and waited for hydration to reveal it, so on a slow
  phone the FAQ headline and half the landing page were blank for seconds, and
  without script they never appeared. Now the markup is always visible and the
  reveal is a CSS scroll-driven animation: a block fades up as it enters the
  viewport, one already on screen at load is simply there, and a browser
  without scroll timelines, or a reader who asked for less motion, gets no
  animation at all. The props are kept so call sites did not change; `delay`
  staggers by pushing where in the entry the reveal finishes.
*/
export function BlurFade({
  children,
  className,
  delay = 0,
}: {
  children: ReactNode
  className?: string
  delay?: number
  inView?: boolean
}) {
  return (
    <div className={cn("reveal", className)} style={{ "--reveal-delay": `${Math.round(delay * 100)}%` } as CSSProperties}>
      {children}
    </div>
  )
}
