import type { Metadata } from "next"
import Link from "next/link"

import { Footer } from "@/components/site/footer"
import { Nav } from "@/components/site/nav"

export const metadata: Metadata = {
  title: "Page not found",
  description:
    "That page does not exist on this site. Nightbell is an open-source Android uptime monitor that runs every check on your phone, with no account and no server, and the landing page has the download and the source.",
  // Next already prints a noindex robots tag on its own for this page, so a
  // second one here only doubled it.
  // Without this the 404 inherits the homepage canonical, which tells crawlers
  // every dead link is a copy of the homepage.
  alternates: { canonical: null },
}

export default function NotFound() {
  return (
    <>
      <Nav />
      <main id="main" className="container-page flex min-h-[80dvh] flex-col justify-center pt-28 pb-20">
        <p className="eyebrow text-down flex items-center gap-2">
          <span className="bg-down size-1.5 animate-pulse rounded-full" /> Down · this page
        </p>
        <p className="dotmatrix text-down mt-6 text-[clamp(7rem,26vw,22rem)] leading-[0.85]" aria-hidden>
          404
        </p>
        <h1 className="display mt-6 text-[clamp(2.6rem,6vw,5rem)]">This one is actually down.</h1>
        <p className="text-muted-foreground mt-5 max-w-xl text-lg leading-relaxed">
          The link you followed points at something this site does not serve. Nothing on your
          phone or your network is broken, and there is nothing to retry.
        </p>
        <div className="mt-10 flex flex-wrap gap-3">
          <Link href="/" className="bg-primary hover:bg-primary/90 inline-flex h-12 items-center rounded-full px-6 font-semibold text-white transition-colors">
            Back to the start
          </Link>
          <Link href="/faq" className="border-border hover:bg-muted inline-flex h-12 items-center rounded-full border px-6 font-semibold transition-colors">
            Questions
          </Link>
          <Link href="/docs" className="border-border hover:bg-muted inline-flex h-12 items-center rounded-full border px-6 font-semibold transition-colors">
            Reference
          </Link>
        </div>
      </main>
      <Footer />
    </>
  )
}
