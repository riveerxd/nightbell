import type { Metadata } from "next"

import { Footer } from "@/components/site/footer"
import { Nav } from "@/components/site/nav"
import { Serif } from "@/components/site/section"
import { SITE_URL } from "@/lib/site"
import { pageMeta } from "@/lib/seo"

import { Block, JsonLd, PageActions, PageHero, Prose, Related } from "../vs/_kit"
import { Cadence } from "./cadence"

const TITLE = "Android's 15 minute background limit, explained"
const DESCRIPTION =
  "Android clamps PeriodicWorkRequest to a fifteen minute minimum, and it is a floor rather than an interval: the work arrives at that mark or later. What that means for watching a service from a phone, what a foreground service buys, and what it costs in battery, in a permanent notification and in a Play Store listing."

export const metadata: Metadata = pageMeta({ path: "/android-background-limits", title: TITLE, description: DESCRIPTION })

const abs = (p: string) => new URL(p, SITE_URL).href

const ARTICLE = {
  "@context": "https://schema.org",
  "@type": "TechArticle",
  "@id": abs("/android-background-limits#article"),
  headline: "Why Android will not check anything more often than every fifteen minutes",
  description:
    "Android clamps PeriodicWorkRequest to a fifteen minute floor, and it is a floor rather than an interval. What that means for uptime monitoring on a phone, what a foreground service buys, and what it costs.",
  inLanguage: "en",
  proficiencyLevel: "Beginner",
  dependencies: "Android 8.0 and later",
  author: { "@id": abs("/#author") },
  publisher: { "@id": abs("/#author") },
  about: { "@id": abs("/#app") },
  isAccessibleForFree: true,
  license: "https://www.apache.org/licenses/LICENSE-2.0",
}

export default function Page() {
  return (
    <>
      <JsonLd data={ARTICLE} />
      <Nav />
      <main id="main">
        <PageHero
          eyebrow="Platform"
          title={
            <>
              Android will not run your background work <Serif>every minute.</Serif>
            </>
          }
          lede="The floor is fifteen minutes, it is a floor and not an interval, and there is exactly one way around it. This is what that way costs."
        >
          <div className="border-border mt-14 grid grid-cols-2 gap-px overflow-hidden rounded-[1.5rem] border bg-border">
            <div className="bg-background p-6 sm:p-8">
              <p className="eyebrow">Default floor</p>
              <p className="dotmatrix text-degraded mt-3 text-[clamp(3rem,8vw,6rem)] leading-none">15m</p>
              <p className="text-muted-foreground mt-3 text-sm">or later, batched by WorkManager</p>
            </div>
            <div className="bg-background p-6 sm:p-8">
              <p className="eyebrow">Strict floor</p>
              <p className="dotmatrix text-up mt-3 text-[clamp(3rem,8vw,6rem)] leading-none">1m</p>
              <p className="text-muted-foreground mt-3 text-sm">on time, for a permanent notification</p>
            </div>
          </div>
        </PageHero>

        <Block
          id="floor"
          title="Fifteen minutes is a constant in the framework, not a setting."
          lede={
            <>
              It is{" "}
              <code className="bg-muted text-foreground rounded-md px-1.5 py-0.5 font-mono text-[0.85em] break-all">
                PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS
              </code>
              , it applies to every Android app, and asking for less does not fail. It is quietly
              rounded up.
            </>
          }
        >
          <Prose>
            <p>
              That last part is what catches people. Ask <code>WorkManager</code> for a five minute
              period and nothing throws, nothing warns, and the schedule you get is fifteen. An app
              that passed the value straight through would show you &quot;every 5 minutes&quot; in
              its settings forever while checking four times an hour.
            </p>
            <p>
              Nightbell clamps it where you can see it, which is why Settings has a Checker health
              section at all. The value you typed and the value Android will honour are both shown,
              and the app says which one is running. That is not a feature so much as a refusal to lie
              about arithmetic.
            </p>
            <p>
              The second half is worse and gets discussed less. Fifteen minutes is a minimum, not a
              cadence. <code>WorkManager</code> batches wakeups so a phone is not woken forty times an
              hour by forty apps, so your work fires at fifteen minutes <strong>or later</strong>,
              depending on what else was queued, whether the device is in Doze, and how aggressive the
              manufacturer&apos;s own battery layer is. There is no API that asks for &quot;exactly
              fifteen&quot;.
            </p>
          </Prose>
          <Cadence />
        </Block>

        <Block
          id="foreground"
          tone="up"
          title={
            <>
              A foreground service, <Serif>which is the only thing that keeps a real cadence.</Serif>
            </>
          }
        >
          <Prose>
            <p>
              A foreground service is Android&apos;s bargain: your process stays alive and runs on
              your own schedule, and in exchange the user sees a permanent notification saying so for
              as long as it runs. There is no version of this where the app gets the cadence and the
              user does not get the notification, and any app that appears to have managed it is
              either running a service you have not noticed or is not checking as often as it says.
            </p>
            <p>
              Nightbell calls it strict monitoring, it is off by default, and turning it on runs the
              checks from a foreground service, with <code>WorkManager</code> kept underneath only as
              a repair sweep. Intervals are whole minutes, so a one minute interval is a one minute
              interval. The cost
              is exactly what the bargain says: a notification that does not go away, and real
              battery, because the phone is being woken on your schedule rather than batched into
              everybody else&apos;s.
            </p>
            <p>
              One thing happens without being asked for, and it is worth knowing about. An urgent
              outage that has not been acknowledged starts that same service on its own, whatever the
              setting says, and stops it the moment you acknowledge or the monitor recovers. An alarm
              that is supposed to keep sounding until you deal with it cannot be subject to being
              scheduled fifteen minutes from now.
            </p>
          </Prose>
        </Block>

        <Block
          id="subtype"
          tone="degraded"
          title={
            <>
              From Android 14 the service has to say what kind it is, <Serif>and the honest answer costs a store listing.</Serif>
            </>
          }
        >
          <Prose>
            <p>
              Android 14 made every foreground service declare a type. The obvious one for something
              that polls the network is <code>dataSync</code>, and it is the wrong one here: from Android
              15 <code>dataSync</code> is capped at roughly six hours of runtime a day, after which the
              system stops the service. For a sync job that is reasonable. For a pager it means the
              guarantee strict mode exists to make quietly expires in the evening.
            </p>
            <p>
              So Nightbell declares <code>specialUse</code>, which has no such cap and does have a
              price: <code>specialUse</code> is the subtype Google Play asks you to justify in review.
              That is the actual reason there is no Play listing, rather than any of the reasons people
              usually guess. F-Droid has no such requirement, which is where the app ships, and a
              direct APK is unaffected.
            </p>
          </Prose>
          <blockquote className="border-degraded mt-10 max-w-4xl border-l-2 pl-6">
            <p className="display text-[clamp(1.8rem,3.4vw,3rem)]">
              The app can keep the cadence it promises, or it can be on the Play Store, and it cannot
              be both without changing what it promises.
            </p>
          </blockquote>
        </Block>

        <Block id="practice" title="Which of the two to actually use, per monitor.">
          <Prose>
            <p>
              <strong>Leave the default on anything where the question is &quot;is this host still
              there&quot;.</strong> A fifteen minute check that occasionally lands at twenty two
              catches a dead host on its next pass, and nobody was going to act on the difference
              between noticing at 03:14 and noticing at 03:22. This is most monitors, including the
              one watching your other monitoring.
            </p>
            <p>
              <strong>Turn on strict monitoring when the thing you are watching can fail and recover
              inside a fifteen minute window</strong>, which mostly means a checkout flow, a payment
              callback or something behind a load balancer that takes one node out at a time. There
              the gap between checks is the gap in what you can see, and a permanent notification is
              a fair price for closing it.
            </p>
            <p>
              <strong>The thing not to do is turn it on everywhere</strong> because more often sounds
              better. It wakes the phone for every due check instead of letting Android batch them,
              and a fleet of hourly monitors running under strict mode is paying a battery cost for a
              resolution nothing is using.
            </p>
          </Prose>
          <PageActions second={{ href: "/docs", label: "The full reference" }} />
          <Related
            links={[
              {
                href: "/faq",
                title: "The rest of the questions",
                note: "What leaves the device, whether the alarm beats Do Not Disturb, and where your monitors are stored.",
              },
              {
                href: "/vs/uptime-kuma",
                title: "Against a self-hosted monitor",
                note: "If a fifteen minute floor is unacceptable, a machine that stays awake is the honest answer, and this is what each one is for.",
              },
            ]}
          />
        </Block>
      </main>
      <Footer />
    </>
  )
}
