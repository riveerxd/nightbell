import type { Metadata } from "next"
import { ArrowRight } from "lucide-react"

import { BlurFade } from "@/components/ui/blur-fade"
import { FaqList } from "@/components/site/faq-section"
import { Footer } from "@/components/site/footer"
import { Nav } from "@/components/site/nav"
import { faqPageLd, faqs, GROUPS } from "@/lib/faq"
import { DOWNLOAD_PATH, SITE_URL } from "@/lib/site"
import { pageMeta } from "@/lib/seo"

export const metadata: Metadata = pageMeta({
  path: "/faq",
  title: "Nightbell FAQ: on-device uptime monitoring, answered",
  description:
    "Sixteen answers about Nightbell, the Android uptime monitor that runs every check on the phone. What leaves the device, why Android will not check faster than every fifteen minutes without a foreground service, whether the alarm beats Do Not Disturb, and where your monitors are stored.",
})

export default function FaqPage() {
  const ld = faqPageLd(`${SITE_URL}/faq#faq`, faqs)
  return (
    <>
      <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify(ld) }} />
      <Nav />
      <main id="main">
        <header className="container-page pt-32 pb-16 sm:pt-40">
          <BlurFade>
            <p className="eyebrow">{faqs.length} answers</p>
            <h1 className="display mt-5 text-[clamp(3.4rem,9vw,8rem)]">
              Questions, <span className="text-muted-foreground">answered straight.</span>
            </h1>
            <p className="text-muted-foreground mt-6 max-w-2xl text-lg leading-relaxed">
              Every answer here can be checked against the source, the reference docs or a released
              APK. Two of them are reasons not to install it.
            </p>
          </BlurFade>
          <nav aria-label="Groups" className="mt-10 flex flex-wrap gap-2">
            {GROUPS.map((g) => (
              <a
                key={g.id}
                href={`#${g.id}`}
                className="border-border hover:bg-muted inline-flex min-h-11 items-center rounded-full border px-4 text-sm font-medium transition-colors"
              >
                <span className="text-muted-foreground mr-2 font-mono text-xs">{g.no}</span>
                {g.rail}
              </a>
            ))}
          </nav>
        </header>

        <div id="faq">
          {GROUPS.map((g) => (
            <section key={g.id} id={g.id} className="border-border scroll-mt-20 border-t py-20 sm:py-24">
              <div className="container-page grid gap-10 lg:grid-cols-[0.8fr_1.2fr] lg:gap-20">
                <div className="lg:sticky lg:top-28 lg:self-start">
                  <p className="dotmatrix text-brand text-3xl">{g.no}</p>
                  <h2 className="display mt-4 text-[clamp(2.2rem,4.4vw,3.8rem)]">{g.rail}</h2>
                  <p className="mt-4 text-lg font-medium">{g.head}</p>
                  <p className="text-muted-foreground mt-3 leading-relaxed">{g.lede}</p>
                </div>
                <FaqList items={faqs.filter((f) => f.group === g.id)} />
              </div>
            </section>
          ))}
        </div>

        <section className="border-border border-t py-20">
          <div className="container-page flex flex-col items-start justify-between gap-6 sm:flex-row sm:items-center">
            <h2 className="display text-[clamp(2.2rem,5vw,4rem)]">Answered enough?</h2>
            <a
              href={DOWNLOAD_PATH}
              className="bg-primary hover:bg-primary/90 inline-flex h-12 items-center gap-2 rounded-full px-6 font-semibold text-white transition-colors"
            >
              Download the APK <ArrowRight className="size-4" aria-hidden />
            </a>
          </div>
        </section>
      </main>
      <Footer />
    </>
  )
}
