import type { ReactNode } from "react"

import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from "@/components/ui/accordion"
import { cn } from "@/lib/utils"

/* The long version of a section, folded. It is in the markup either way. */
export function DeepGroup({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <Accordion type="multiple" className={cn("border-border border-t", className)}>
      {children}
    </Accordion>
  )
}

export function Deep({ title, children }: { title: string; children: ReactNode }) {
  return (
    <AccordionItem value={title} className="border-border border-b">
      <AccordionTrigger className="py-5 text-base font-semibold hover:no-underline">{title}</AccordionTrigger>
      <AccordionContent className="text-muted-foreground max-w-3xl space-y-4 pb-6 text-[15px] leading-relaxed">
        {children}
      </AccordionContent>
    </AccordionItem>
  )
}
