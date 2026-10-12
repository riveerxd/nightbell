"use client"

import { useEffect, useState } from "react"
import Link from "next/link"
import { useMotionValueEvent, useScroll } from "motion/react"
import { Download, Menu, Star, X } from "lucide-react"

import { AnimatedThemeToggler } from "@/components/ui/animated-theme-toggler"
import { ScrollProgress } from "@/components/ui/scroll-progress"
import { cn } from "@/lib/utils"
import { APK_MB, DOWNLOAD_PATH, REPO } from "@/lib/site"
import { stars } from "@/lib/stars.json"

import { GitHubIcon, Mark } from "./mark"

const LINKS = [
  { href: "/#how", label: "How it works" },
  { href: "/#urgent", label: "Alerts" },
  { href: "/#install", label: "Install" },
  { href: "/faq", label: "FAQ" },
  { href: "/docs", label: "Docs" },
]

export function Nav({ progress = false }: { progress?: boolean }) {
  const [scrolled, setScrolled] = useState(false)
  const [open, setOpen] = useState(false)

  const { scrollY } = useScroll()
  useMotionValueEvent(scrollY, "change", (y) => setScrolled(y > 12))

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false)
    window.addEventListener("keydown", onKey)
    document.body.style.overflow = "hidden"
    return () => {
      window.removeEventListener("keydown", onKey)
      document.body.style.overflow = ""
    }
  }, [open])

  return (
    <header
      className={cn(
        "fixed inset-x-0 top-0 z-50 transition-[background-color,border-color,backdrop-filter] duration-300",
        scrolled || open
          ? "border-border/80 bg-background/75 border-b backdrop-blur-xl backdrop-saturate-150"
          : "border-b border-transparent"
      )}
    >
      <div className="container-page flex h-16 items-center gap-6">
        <Link
          href="/"
          className="flex min-h-11 items-center gap-2.5 text-[15px] font-semibold tracking-tight"
          aria-label="Nightbell home"
          onClick={() => setOpen(false)}
        >
          <Mark size={24} />
          Nightbell
        </Link>

        <nav aria-label="Primary" className="hidden flex-1 items-center gap-1 lg:flex">
          {LINKS.map((l) => (
            <Link
              key={l.href}
              href={l.href}
              className="text-muted-foreground hover:text-foreground rounded-full px-3 py-1.5 text-sm transition-colors"
            >
              {l.label}
            </Link>
          ))}
        </nav>

        <div className="ml-auto flex items-center gap-1.5">
          <a
            href={REPO.url}
            target="_blank"
            rel="noopener"
            className="text-muted-foreground hover:text-foreground hover:bg-muted hidden h-11 items-center gap-2 rounded-full px-3 text-sm font-medium transition-colors sm:inline-flex lg:h-9"
            aria-label={`Star Nightbell on GitHub, ${stars} stars`}
          >
            <GitHubIcon className="size-[18px]" />
            <span className="inline-flex items-center gap-1 font-mono text-xs tabular-nums">
              <Star className="size-3.5 fill-current" aria-hidden />
              {stars}
            </span>
          </a>
          <AnimatedThemeToggler
            aria-label="Switch colour theme"
            className="text-muted-foreground hover:text-foreground hover:bg-muted flex size-11 items-center justify-center rounded-full transition-colors lg:size-9 [&_svg]:size-[18px]"
          />
          <a
            href={DOWNLOAD_PATH}
            className="bg-foreground text-background hover:bg-foreground/85 ml-1.5 hidden h-11 items-center gap-2 rounded-full px-4 lg:h-9 text-sm font-medium transition-colors sm:inline-flex"
          >
            <Download className="size-4" aria-hidden />
            Download APK
            <span className="font-mono text-xs opacity-60">{APK_MB} MB</span>
          </a>
          <button
            type="button"
            className="hover:bg-muted flex size-11 items-center justify-center rounded-full lg:hidden"
            aria-expanded={open}
            aria-controls="mobile-menu"
            aria-label={open ? "Close menu" : "Open menu"}
            onClick={() => setOpen((v) => !v)}
          >
            {open ? <X className="size-5" /> : <Menu className="size-5" />}
          </button>
        </div>
      </div>

      {open && (
        <div
          id="mobile-menu"
          className="bg-background/95 border-border h-[calc(100dvh-4rem)] overflow-y-auto border-t backdrop-blur-xl lg:hidden"
        >
          <nav aria-label="Mobile" className="container-page flex flex-col py-6">
            {LINKS.map((l) => (
              <Link
                key={l.href}
                href={l.href}
                onClick={() => setOpen(false)}
                className="border-border border-b py-4 text-2xl font-medium tracking-tight"
              >
                {l.label}
              </Link>
            ))}
            <a
              href={REPO.url}
              target="_blank"
              rel="noopener"
              className="border-border flex items-center gap-3 border-b py-4 text-2xl font-medium tracking-tight"
            >
              <GitHubIcon className="size-5" /> Star on GitHub
              <span className="text-muted-foreground ml-auto inline-flex items-center gap-1.5 font-mono text-base tabular-nums">
                <Star className="size-4 fill-current" aria-hidden />
                {stars}
              </span>
            </a>
            <a
              href={DOWNLOAD_PATH}
              className="bg-primary text-primary-foreground mt-8 inline-flex h-12 items-center justify-center gap-2 rounded-full text-base font-medium"
            >
              <Download className="size-5" aria-hidden />
              Download APK
              <span className="font-mono text-sm opacity-70">{APK_MB} MB</span>
            </a>
          </nav>
        </div>
      )}
      {progress && <ScrollProgress className="absolute from-[#6d5efc] via-[#6d5efc] to-brand top-auto bottom-[-1px] h-px bg-linear-to-r" />}
    </header>
  )
}
