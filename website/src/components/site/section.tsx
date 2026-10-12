import type { ReactNode } from "react"

import { BlurFade } from "@/components/ui/blur-fade"
import { cn } from "@/lib/utils"

export function SectionHeading({
  eyebrow,
  title,
  lede,
  align = "left",
  className,
  tone,
}: {
  eyebrow?: string
  title: ReactNode
  lede?: ReactNode
  align?: "left" | "center"
  className?: string
  tone?: "up" | "down" | "degraded" | "brand"
}) {
  const dot = {
    up: "bg-up-fill",
    down: "bg-down-fill",
    degraded: "bg-degraded-fill",
    brand: "bg-brand",
  }[tone ?? "brand"]
  return (
    <BlurFade inView className={cn(align === "center" && "mx-auto text-center", "max-w-5xl", className)}>
      {eyebrow && (
        <p className={cn("eyebrow flex items-center gap-2.5", align === "center" && "justify-center")}>
          <span className={cn("h-px w-6", dot)} aria-hidden />
          {eyebrow}
        </p>
      )}
      <h2 className={cn("display text-[clamp(2.6rem,6.4vw,5.75rem)] text-balance", eyebrow && "mt-5")}> 
        {title}
      </h2>
      {lede && (
        <p
          className={cn(
            "text-muted-foreground mt-6 max-w-2xl text-lg leading-relaxed text-pretty",
            align === "center" && "mx-auto"
          )}
        >
          {lede}
        </p>
      )}
    </BlurFade>
  )
}

/* The second clause of a heading, set back so the first one reads as the claim. */
export function Serif({ children, className }: { children: ReactNode; className?: string }) {
  return <span className={cn("text-muted-foreground", className)}>{children}</span>
}
