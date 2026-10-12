"use client"

import { createContext, useContext, type ComponentProps } from "react"
import { ChevronDownIcon } from "lucide-react"

import { cn } from "@/lib/utils"

/*
  The shadcn accordion's API on native <details>. The Radix version unmounts a
  closed panel, so every FAQ answer and every folded paragraph was missing from
  the static HTML: invisible to a crawler, to a reader without script, and in
  direct contradiction of the FAQPage structured data that claims those answers
  are on the page. <details> keeps the content in the document and opens with no
  script at all.
*/
const Open = createContext<string[]>([])

function Accordion({
  className,
  defaultValue = [],
  children,
  ...rest
}: ComponentProps<"div"> & { type?: "single" | "multiple"; defaultValue?: string[] }) {
  // `type` is accepted for API parity; every native <details> opens on its own.
  const props = Object.fromEntries(Object.entries(rest).filter(([k]) => k !== "type"))
  return (
    <Open.Provider value={defaultValue}>
      <div data-slot="accordion" className={className} {...props}>
        {children}
      </div>
    </Open.Provider>
  )
}

function AccordionItem({
  className,
  value,
  children,
  ...props
}: ComponentProps<"details"> & { value: string }) {
  const open = useContext(Open).includes(value)
  return (
    <details data-slot="accordion-item" open={open || undefined} className={cn("group/acc", className)} {...props}>
      {children}
    </details>
  )
}

function AccordionTrigger({ className, children, ...props }: ComponentProps<"summary">) {
  return (
    <summary
      data-slot="accordion-trigger"
      className={cn(
        "flex cursor-pointer list-none items-start justify-between gap-4 rounded-lg text-left outline-none focus-visible:ring-3 focus-visible:ring-ring/50 [&::-webkit-details-marker]:hidden",
        className
      )}
      {...props}
    >
      <span>{children}</span>
      <ChevronDownIcon
        aria-hidden
        className="text-muted-foreground mt-1 size-4 shrink-0 transition-transform duration-200 group-open/acc:rotate-180"
      />
    </summary>
  )
}

function AccordionContent({ className, children, ...props }: ComponentProps<"div">) {
  return (
    <div data-slot="accordion-content" className={className} {...props}>
      {children}
    </div>
  )
}

export { Accordion, AccordionContent, AccordionItem, AccordionTrigger }
