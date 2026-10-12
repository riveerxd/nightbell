"use client"

import { useEffect } from "react"
import Lenis from "lenis"

/*
  Wheel and trackpad scrolling eased toward the target instead of jumping in
  steps, which is what lets the pinned scenes read as motion rather than as a
  slideshow. Touch is left native: Lenis only smooths wheel input by default,
  and a phone's own momentum is already the right feel. Reduced motion gets no
  Lenis at all.
*/
export function SmoothScroll() {
  useEffect(() => {
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return
    const lenis = new Lenis({ autoRaf: true, anchors: { offset: -72 }, lerp: 0.12 })
    return () => lenis.destroy()
  }, [])
  return null
}
