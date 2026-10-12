import { Checks } from "@/components/site/checks"
import { Cta } from "@/components/site/cta"
import { FaqSection } from "@/components/site/faq-section"
import { Film } from "@/components/site/film"
import { Footer } from "@/components/site/footer"
import { Hero } from "@/components/site/hero"
import { How } from "@/components/site/how"
import { Install } from "@/components/site/install"
import { Limits } from "@/components/site/limits"
import { Nav } from "@/components/site/nav"
import { Setup, Widgets } from "@/components/site/setup"
import { Urgent } from "@/components/site/urgent"
import { Why } from "@/components/site/why"
import { faqPageLd, faqs, LANDING_COUNT } from "@/lib/faq"
import { SITE_URL } from "@/lib/site"
import { JsonLd, promoVideo } from "@/lib/structured"

export default function Home() {
  const ld = faqPageLd(`${SITE_URL}/#faq`, faqs.slice(0, LANDING_COUNT))
  return (
    <>
      <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify(ld) }} />
      <JsonLd data={promoVideo} />
      <Nav progress />
      <main id="main">
        <Hero />
        <Film />
        <Why />
        <How />
        <Checks />
        <Widgets />
        <Setup />
        <Urgent />
        <Limits />
        <Install />
        <FaqSection />
        <Cta />
      </main>
      <Footer />
    </>
  )
}
