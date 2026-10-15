"use client"

import { useRef } from "react"
import { Database, Server } from "lucide-react"

import { AnimatedBeam } from "@/components/ui/animated-beam"
import { BlurFade } from "@/components/ui/blur-fade"
import { Marquee } from "@/components/ui/marquee"

import { Deep, DeepGroup } from "./deep"
import { Parallax, PhoneFrame } from "./depth"
import { SectionHeading, Serif } from "./section"

const ABSENT = [
  "relay",
  "queue",
  "worker",
  "backend",
  "account",
  "push service",
  "analytics SDK",
  "crash reporter",
  "icon proxy",
]


/*
  The whole path at the size of the page: the real phone on one side, your
  server on the other, and the company that is usually between them set large
  and struck out, so the absence is the thing you see.
*/
function PathDiagram() {
  const box = useRef<HTMLDivElement>(null)
  const phone = useRef<HTMLDivElement>(null)
  const service = useRef<HTMLDivElement>(null)

  return (
    <div
      ref={box}
      className="bg-surface shadow-elev-2 relative mt-14 overflow-hidden rounded-[1.75rem] px-5 pt-12 pb-8 sm:px-12 sm:pt-16"
      role="img"
      aria-label="A request goes from your phone straight to your service and back. The monitoring company that would normally sit in between is crossed out."
    >
      {/* Three across on a wide screen. On a phone there is no room for three
          columns, so it staggers: the phone on the left, and on the right the
          struck-out middleman above the server, still sitting in the line the
          request travels along. */}
      <div className="relative grid grid-cols-[0.85fr_1.15fr] items-center gap-x-4 gap-y-6 md:grid-cols-[1fr_auto_1fr] md:gap-x-8">
        <div className="row-span-2 flex flex-col items-center gap-4 md:row-span-1">
          <div ref={phone} className="relative z-10 w-full max-w-[12rem] [perspective:1200px]">
            <PhoneFrame edge="left" className="shadow-elev-3 [transform:rotateY(16deg)_rotateZ(-3deg)]">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src="/hero/calm.webp" alt="" width={1080} height={2400} loading="lazy" className="block w-full" />
            </PhoneFrame>
          </div>
          <span className="text-center text-sm font-semibold sm:text-base">Your phone runs the check</span>
        </div>

        <Parallax speed={-0.05} className="relative z-10 col-start-2 row-start-1 self-end text-center md:self-center">
          <p className="display text-muted-foreground decoration-down text-[clamp(1.7rem,4.6vw,4.6rem)] leading-[0.9] line-through decoration-[0.08em]">
            Monitoring
            <br />
            company
          </p>
          <p className="text-down mt-3 font-mono text-[11px] tracking-[0.16em] uppercase sm:text-xs">not in the path</p>
        </Parallax>

        <div className="col-start-2 row-start-2 flex flex-col items-center gap-4 self-start md:col-start-3 md:row-start-1 md:self-center">
          <div
            ref={service}
            className="bg-surface-2 shadow-elev-3 relative z-10 flex w-full max-w-[16rem] flex-col gap-3 rounded-[1.5rem] p-3 [container-type:inline-size] [transform:perspective(1200px)_rotateY(-12deg)_rotateZ(2deg)] sm:gap-4 sm:p-6"
          >
            <Server className="text-up size-10 sm:size-14" strokeWidth={1.5} aria-hidden />
            <div>
              {/* sized off the tile's own width (22 monospace characters, a little over 0.6em each), so
                the URL is one line in any column the grid gives it */}
            <p className="font-mono text-[min(15px,6.3cqw)] whitespace-nowrap">api.nightbell.app/health</p>
              <p className="text-up mt-1 font-mono text-base font-semibold sm:text-xl">200</p>
            </div>
          </div>
          <span className="text-center text-sm font-semibold sm:text-base">Your service answers it</span>
        </div>
      </div>

      <div className="mt-10 flex flex-col items-start gap-3 sm:flex-row sm:items-center sm:justify-between">
        <span className="bg-surface-2 shadow-elev-1 inline-flex items-center gap-2 rounded-full px-3.5 py-1.5 font-mono text-xs">
          <Database className="text-brand size-3.5" aria-hidden />
          state stays here: one JSON document in DataStore
        </span>
        <span className="text-muted-foreground font-mono text-xs">phone → your service → phone</span>
      </div>

      <AnimatedBeam
        containerRef={box}
        fromRef={phone}
        toRef={service}
        curvature={-120}
        pathColor="var(--foreground)"
        pathOpacity={0.12}
        gradientStartColor="#2f6bff"
        gradientStopColor="#2fd98a"
        duration={3.2}
      />
      <AnimatedBeam
        containerRef={box}
        fromRef={phone}
        toRef={service}
        curvature={120}
        reverse
        pathColor="var(--foreground)"
        pathOpacity={0.12}
        gradientStartColor="#2fd98a"
        gradientStopColor="#2f6bff"
        duration={3.2}
        delay={1.6}
      />
    </div>
  )
}

export function How() {
  return (
    <section id="how" className="relative py-20 sm:py-28">
      <div className="container-page">
        <SectionHeading
          title={
            <>
              The check leaves your phone and goes to your service.{" "}
              <Serif className="text-muted-foreground">That is the entire path.</Serif>
            </>
          }
        />
        <BlurFade inView delay={0.1}>
          <PathDiagram />
        </BlurFade>

        <div className="mt-20 grid gap-10 lg:grid-cols-[1fr_1.4fr] lg:gap-16">
          <BlurFade inView>
            <h3 className="display text-[clamp(2rem,3.6vw,3.2rem)]">
              Not in the path, and not in the dependency list.
            </h3>
            <p className="text-muted-foreground mt-4 leading-relaxed">
              Every one of these is something a monitoring app normally has. All of them can be
              checked against the repository, and none of them is there.
            </p>
          </BlurFade>
          <BlurFade inView delay={0.1} className="min-w-0">
            <div aria-hidden className="relative flex flex-col gap-3 overflow-hidden [mask-image:linear-gradient(to_right,transparent,black_12%,black_88%,transparent)]">
              {[false, true].map((reverse) => (
                <Marquee key={String(reverse)} reverse={reverse} pauseOnHover className="[--duration:38s] [--gap:0.75rem] p-0">
                  {(reverse ? [...ABSENT].reverse() : ABSENT).map((a) => (
                    <span
                      key={a}
                      className="bg-surface shadow-elev-1 text-muted-foreground decoration-down/70 my-2 inline-flex items-center gap-2 rounded-full px-4 py-2 font-mono text-sm line-through decoration-2"
                    >
                      {a}
                    </span>
                  ))}
                </Marquee>
              ))}
            </div>
            <ul className="sr-only">
              {ABSENT.map((a) => (
                <li key={a}>No {a}</li>
              ))}
            </ul>
          </BlurFade>
        </div>

        <BlurFade inView>
          <p className="text-muted-foreground border-brand mt-14 max-w-3xl border-l-2 pl-5 text-lg leading-relaxed">
            What it does not buy you is anonymity from the thing you are watching. Your service sees
            the request and the IP it came from, exactly as it would for any other client on your
            network. That is what a check is. Routing a monitor through your own proxy is the one
            thing that changes it, and only for that monitor.
          </p>
        </BlurFade>

        <DeepGroup className="mt-12">
          <Deep title="Implementation details">
            <p>
              A status or request monitor is an OkHttp call from your phone to the host you typed in.
              A page-element monitor loads the page in an offscreen WebView on the same phone and
              reads the DOM nodes you picked by tapping them. There is no relay, no queue and no
              worker somewhere else, because there is nowhere else. The one exception is a hop you set
              up yourself: a monitor can be routed through a SOCKS5 proxy running on your own device,
              which is how a Tor or I2P hidden service gets checked. It goes where you point it and
              nowhere else, and it is off until you turn it on.
            </p>
            <p>
              Everything the app knows lives in one JSON document in DataStore: your monitors, their
              headers and bodies, their sample history and your settings. Sign-in secrets are the
              exception: they sit in a vault encrypted with a keystore key and never go into a
              backup. Backup and transfer writes
              that document through the Storage Access Framework, which is why Nightbell asks for no
              storage permission and why you choose where the file goes. Nothing is uploaded, and
              there is no import path that could reach anything you did not hand it.
            </p>
          </Deep>
          <Deep title="What that does and does not buy you">
            <p>
              It buys you this: an API token in a monitor&apos;s headers is on your phone and on your
              server, and nowhere in between. No third party can leak it, subpoena it, or change its
              terms of service. There is no analytics library and no crash reporter in the dependency
              list, and site icons are fetched from the monitored origin itself rather than through
              an icon proxy.
            </p>
            <p>
              Two hosts get contacted that you did not choose. The connectivity reference, which you
              can repoint or switch off. And nightbell.app, every six hours, for the current version
              number: that one is counted, and what the count is allowed to know is a date, a status
              and which version asked. Not an address, not an identifier, not a hash standing in for
              one. It is the difference between knowing that four hundred copies of this are running
              and knowing anything at all about the four hundred people running them, and only the
              first of those is any of my business.
            </p>
          </Deep>
        </DeepGroup>
      </div>
    </section>
  )
}
