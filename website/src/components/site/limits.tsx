import { BlurFade } from "@/components/ui/blur-fade"
import { cn } from "@/lib/utils"

import { PhoneFrame } from "./depth"
import { SectionHeading } from "./section"

/* Names and button words exactly as the setup screen beside them shows them. */
const GRANTS = [
  { name: "Notifications", body: "Without it nothing is posted at all.", how: "Allow" },
  { name: "Unrestricted battery", body: "Checks keep their schedule, and an urgent page keeps repeating in your pocket.", how: "Allow" },
  { name: "Full-screen alerts", body: "An urgent page wakes the screen instead of waiting on the lockscreen.", how: "Settings" },
  { name: "Get through Do Not Disturb", body: "Without it, Do Not Disturb and Bedtime silence urgent pages completely.", how: "Settings" },
]

const LIMITS = [
  ["Doze can still delay a 15 minute check.", "Delay is reported in Settings and never paged, because it is not an outage."],
  ["Strict mode costs a permanent notification.", "And real battery. That is Android's price for a real cadence."],
  ["Element checks need a page that renders.", "Slow single-page apps get about five seconds after load before an element counts as missing."],
  ["Quiet hours hold urgent pages back.", "Unless you turn on the bypass. That default is wrong, and it is the next thing I want to fix."],
]

export function Limits() {
  return (
    <section id="limits" className="relative py-20 sm:py-28">
      <div className="container-page">
        <SectionHeading
          tone="degraded"
          title="Four grants, and the limits that stay after them."
          lede="Android will let an app believe it is alerting you while it delivers nothing. These four switches are what stop that."
        />

        <div className="mt-14 grid items-center gap-12 md:grid-cols-[minmax(0,15rem)_1fr] lg:grid-cols-[minmax(0,19rem)_1fr] lg:gap-20">
          <BlurFade inView className="mx-auto w-[64%] max-w-[17rem] md:w-full [perspective:1400px]">
            <PhoneFrame edge="left" edgeFrom="md" className="shadow-elev-3 md:[transform:rotateY(12deg)_rotateZ(-1.5deg)]">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src="/screens/grants-setup.webp"
                alt="The Nightbell setup screen, Let Nightbell wake you when something breaks, showing 2 of 4 ready: notifications and full-screen alerts granted, unrestricted battery with an Allow button and Get through Do Not Disturb with a Settings button."
                width={1080}
                height={2400}
                loading="lazy"
                className="block w-full"
              />
            </PhoneFrame>
          </BlurFade>

          <div>
            <ol>
              {GRANTS.map((g) => (
                <li key={g.name} className="border-border grid grid-cols-[1fr_auto] items-baseline gap-x-6 border-t py-5 last:border-b">
                  <h3 className="text-[clamp(1.25rem,1.9vw,1.6rem)] font-semibold tracking-tight">{g.name}</h3>
                  <span
                    className={cn(
                      "rounded-full px-3 py-1 font-mono text-[11px] tracking-[0.1em] uppercase",
                      g.how === "Allow" ? "bg-foreground/10 text-foreground" : "ring-border text-foreground ring-1"
                    )}
                  >
                    {g.how}
                  </span>
                  <p className="text-muted-foreground col-span-2 mt-1.5 text-[16px] leading-relaxed">{g.body}</p>
                </li>
              ))}
            </ol>
            <p className="text-muted-foreground mt-5 text-sm leading-relaxed">
              Allow is a dialog inside the app. Settings is a trip to Android&apos;s own screen, because
              Android gives apps no way to flip those two, and the list re-checks itself when you come back.
            </p>
          </div>
        </div>

        <div className="mt-24 grid gap-12 lg:grid-cols-[auto_1fr] lg:gap-20">
          {/* the floor everything else on this list follows from, at the size it deserves */}
          <div className="flex items-end gap-5 lg:block">
            <p className="dotmatrix text-[clamp(7rem,16vw,13rem)] leading-[0.8]" aria-hidden>
              15
            </p>
            <p className="max-w-[15rem] pb-2 text-lg leading-snug lg:mt-6 lg:pb-0">
              minutes, the shortest interval Android gives a background check. Strict mode goes lower.
            </p>
          </div>
          <dl className="grid content-start gap-x-12 gap-y-8 sm:grid-cols-2">
            {LIMITS.map(([head, body]) => (
              <div key={head}>
                <dt className="text-lg leading-snug font-semibold">{head}</dt>
                <dd className="text-muted-foreground mt-1.5 text-[15px] leading-relaxed">{body}</dd>
              </div>
            ))}
          </dl>
        </div>

        <p className="text-muted-foreground mt-14 max-w-2xl text-[15px] leading-relaxed">
          CI runs the 1,237 JVM tests and a release build on every push. The 567 on-device tests are run by hand before
          every release.
        </p>
      </div>
    </section>
  )
}
