"use client"

import { useSyncExternalStore } from "react"

const QUERY = "(prefers-reduced-motion: reduce)"

function subscribe(cb: () => void) {
  const m = window.matchMedia(QUERY)
  m.addEventListener("change", cb)
  return () => m.removeEventListener("change", cb)
}

/*
  Reduced motion, answered the way the server answered it for the first render
  and the truth straight after. motion's useReducedMotion reads the media query
  during the first client render, so with the setting on, the hydrating render
  differed from the HTML: React threw the tree away, and the dark class the head
  script had put on the root went with it.
*/
export function useStill() {
  return useSyncExternalStore(subscribe, () => window.matchMedia(QUERY).matches, () => false)
}
