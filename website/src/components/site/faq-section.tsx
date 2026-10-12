import Link from "next/link"
import { ArrowRight } from "lucide-react"

import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from "@/components/ui/accordion"
import { faqs, LANDING_COUNT, type Faq } from "@/lib/faq"

import { SectionHeading } from "./section"

export function FaqList({ items }: { items: Faq[] }) {
  return (
    <Accordion type="multiple" className="space-y-3">
      {items.map((f) => (
        <AccordionItem key={f.q} value={f.q} className="bg-surface shadow-elev-1 open:shadow-elev-2 rounded-[1.75rem] px-5 transition-shadow sm:px-7">
          <AccordionTrigger className="py-6 text-left text-lg font-semibold hover:no-underline sm:text-xl">
            {f.q}
          </AccordionTrigger>
          <AccordionContent className="text-muted-foreground max-w-3xl pb-7 text-base leading-relaxed">
            {f.a}
          </AccordionContent>
        </AccordionItem>
      ))}
    </Accordion>
  )
}

export function FaqSection() {
  return (
    <section id="faq" className="relative py-20 sm:py-28">
      <div className="container-page grid gap-12 lg:grid-cols-[0.8fr_1.2fr] lg:gap-20">
        <div className="lg:sticky lg:top-28 lg:self-start">
          <SectionHeading title="Worth asking before you install it." />
          <Link
            href="/faq"
            className="bg-surface shadow-elev-1 hover:bg-surface-2 mt-8 inline-flex h-11 items-center gap-2 rounded-full px-5 text-sm font-semibold transition-colors"
          >
            {faqs.length - LANDING_COUNT} more answers
            <ArrowRight className="size-4" aria-hidden />
          </Link>
        </div>
        <FaqList items={faqs.slice(0, LANDING_COUNT)} />
      </div>
    </section>
  )
}
