"use client"

import { ArrowRight, Download } from "lucide-react"

import { BlurFade } from "@/components/ui/blur-fade"
import { ShimmerLink } from "@/components/ui/shimmer-link"
import { APK_MB, CHANNELS, DOWNLOAD_PATH, RELEASE } from "@/lib/site"

import { Parallax, PhoneFrame } from "./depth"

/*
  The two states of the product at two depths: the quiet list far back, the
  alarm close and tipped toward you, and the headline in front of both. On a
  phone there is no room beside the words, and phones behind the paragraph
  made it unreadable, so there the pair sits under the buttons instead, and
  so it does up to 1280 px: below that the headline ran across both phones. The
  drift is kept small enough that neither phone reaches the section's edge.
*/
export function Cta() {
  return (
    <section aria-labelledby="cta-title" className="relative isolate overflow-hidden pt-28 pb-16 sm:py-48">
      <div className="pointer-events-none absolute inset-0 -z-10 hidden [perspective:1600px] xl:block" aria-hidden>
        <Parallax speed={-0.075} className="absolute top-[10%] left-[6%] w-[22vw] max-w-[15rem] will-change-auto lg:left-[10%]">
          <PhoneFrame edge="left" className="shadow-elev-2 [transform:rotateY(22deg)_rotateZ(-6deg)]">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src="/hero/calm.webp" alt="" width={1080} height={2400} loading="lazy" className="block w-full" />
          </PhoneFrame>
        </Parallax>
        <Parallax speed={0.1} className="absolute right-[5%] bottom-[8%] w-[26vw] max-w-[18rem] will-change-auto lg:right-[9%]">
          <PhoneFrame edge="right" className="shadow-elev-3 [transform:rotateY(-20deg)_rotateZ(5deg)]">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src="/hero/alarm.webp" alt="" width={1080} height={2400} loading="lazy" className="block w-full" />
          </PhoneFrame>
        </Parallax>
      </div>
      <div className="container-page text-center">
        <BlurFade inView>
          <p className="dotmatrix text-down text-3xl">03:14</p>
          <h2 id="cta-title" className="display mx-auto mt-6 max-w-5xl text-[clamp(3.2rem,9vw,8.5rem)] [text-shadow:0_20px_60px_var(--background)] xl:max-w-[38vw] xl:text-[clamp(4rem,6vw,8rem)]">
            Put it on call <span className="text-brand">tonight.</span>
          </h2>
          <p className="text-foreground/80 mx-auto mt-6 max-w-xl text-lg leading-relaxed xl:max-w-[34vw]">
            {APK_MB} MB, Android {RELEASE.minAndroid} and up, no account to make. Add one monitor,
            mark it urgent, and the next outage is a phone call instead of a footnote.
          </p>
          <div className="mt-10 flex flex-wrap items-center justify-center gap-3">
            <ShimmerLink
              href={DOWNLOAD_PATH}
              background="#2d67f5"
              shimmerColor="#ffffff"
                shimmerSize="0.1em"
              className="shadow-elev-2 h-13 gap-2.5 px-7 text-base font-semibold"
            >
              <Download className="size-5" aria-hidden />
              Download APK
            </ShimmerLink>
            <a
              href={CHANNELS.fdroid.url}
              target="_blank"
              rel="noopener"
              className="bg-surface shadow-elev-1 hover:bg-surface-2 inline-flex h-13 items-center gap-2 rounded-full px-6 text-base font-semibold transition-colors"
            >
              Get it on F-Droid
              <ArrowRight className="size-4" aria-hidden />
            </a>
          </div>
        </BlurFade>

        <div className="relative mx-auto mt-14 h-[22rem] max-w-xs [perspective:1400px] md:h-[30rem] md:max-w-md xl:hidden" aria-hidden>
          {/* the same two depths as on a wide screen, travelling against each other */}
          <Parallax speed={-0.09} className="absolute top-0 left-0 w-[46%] will-change-auto">
            <PhoneFrame edge="left" className="shadow-elev-2 [transform:rotateY(18deg)_rotateZ(-7deg)]">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src="/hero/calm.webp" alt="" width={1080} height={2400} loading="lazy" className="block w-full" />
            </PhoneFrame>
          </Parallax>
          <Parallax speed={0.12} className="absolute top-8 right-0 w-[52%] will-change-auto">
            <PhoneFrame edge="right" className="shadow-elev-3 [transform:rotateY(-16deg)_rotateZ(5deg)]">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src="/hero/alarm.webp" alt="" width={1080} height={2400} loading="lazy" className="block w-full" />
            </PhoneFrame>
          </Parallax>
        </div>
      </div>
    </section>
  )
}
