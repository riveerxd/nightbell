import type { Metadata } from "next"
import Link from "next/link"

import { Footer } from "@/components/site/footer"
import { Nav } from "@/components/site/nav"
import { Serif } from "@/components/site/section"
import { SITE_URL } from "@/lib/site"
import { pageMeta } from "@/lib/seo"

import {
  Block,
  faqLd,
  JsonLd,
  Outcomes,
  Output,
  PageActions,
  PageHero,
  Prose,
  type QA,
  Questions,
  Related,
  Shots,
  Steps,
  WideShot,
} from "../vs/_kit"

const TITLE = "Uptime Kuma on Android, with an alarm that wakes you"
const DESCRIPTION =
  "Uptime Kuma already lists every monitor's state at /metrics. Point Nightbell at it and a monitor going down in Kuma becomes a full-screen alarm on your phone."

export const metadata: Metadata = pageMeta({ path: "/uptime-kuma-on-android", title: TITLE, description: DESCRIPTION })

const abs = (p: string) => new URL(p, SITE_URL).href

const ARTICLE = {
  "@context": "https://schema.org",
  "@type": "TechArticle",
  "@id": abs("/uptime-kuma-on-android#article"),
  headline: "Uptime Kuma on Android, read off its /metrics page",
  description: DESCRIPTION,
  inLanguage: "en",
  dependencies: "Uptime Kuma 2.5 and Android 8.0 or later",
  author: { "@id": abs("/#author") },
  publisher: { "@id": abs("/#author") },
  about: { "@id": abs("/#app") },
  isAccessibleForFree: true,
  license: "https://www.apache.org/licenses/LICENSE-2.0",
}

// Kuma 2.5.9's own output, from the run the screenshots were taken in. The label
// order is Kuma's; Nightbell sorts them when it quotes a series back.
const METRICS = [
  { text: "$ curl -s -u :$KUMA_KEY https://kuma.example.net/metrics | grep monitor_status" },
  { text: "# HELP monitor_status Monitor Status (1 = UP, 0= DOWN, 2= PENDING, 3= MAINTENANCE)" },
  { text: "# TYPE monitor_status gauge" },
  {
    text: 'monitor_status{monitor_id="1",monitor_name="Website",monitor_type="http",monitor_url="http://localhost:3001/",monitor_hostname="null",monitor_port="null"} 1',
  },
  {
    text: 'monitor_status{monitor_id="2",monitor_name="API",monitor_type="http",monitor_url="http://127.0.0.1:9/",monitor_hostname="null",monitor_port="null"} 0',
    tone: "down" as const,
  },
]

const QUESTIONS: QA[] = [
  {
    q: "Do I have to set up a notification in Kuma for this?",
    a: "No. Nightbell reads the state Kuma already publishes at /metrics. Kuma's own notifications can stay as they are, or stay off.",
  },
  {
    q: "Can the API key change anything in Kuma?",
    a: "Not through Nightbell. It only ever sends a GET to the address you give it, /metrics here. It never talks to Kuma's API or its web socket.",
  },
  {
    q: "What happens when Kuma itself goes down?",
    a: "The page Nightbell reads is gone, so the check fails with whatever the connection said, a refusal or a timeout, and that alarms like any other failure. A Kuma that crashed has nobody else to tell.",
  },
  {
    q: "Can one Kuma monitor wake me and the rest only notify?",
    a: 'Yes. Make one Nightbell monitor per Kuma monitor you care about at night, each with a label filter like monitor_name="API", and set only that one to urgent.',
  },
  {
    q: "Does it work over plain http on my LAN?",
    a: "Yes. The key then crosses the network in clear text on every check, and the setup screen warns you about it under the password field.",
  },
]

export default function Page() {
  return (
    <>
      <JsonLd data={ARTICLE} />
      <JsonLd data={faqLd("/uptime-kuma-on-android#questions", QUESTIONS)} />
      <Nav />
      <main id="main">
        <PageHero
          eyebrow="Guide"
          title={
            <>
              Uptime Kuma on your phone, <Serif>loud enough to wake you.</Serif>
            </>
          }
          lede="Kuma already publishes the state of every monitor it runs. Nightbell can read that list on a schedule and turn a red monitor into an alarm that takes the whole screen. Nothing to install on the Kuma side."
        />

        <Block id="metrics" title="Kuma already says which monitors are down. It says it at /metrics.">
          <Prose>
            <p>
              Uptime Kuma serves a Prometheus page at <code>/metrics</code>. Among the response times
              and certificate dates there is one line per monitor called <code>monitor_status</code>,
              and its value is the state. Kuma prints the legend itself, right above the lines:
              1 is up, 0 is down, 2 is pending, 3 is maintenance.
            </p>
            <p>
              Nightbell has a Prometheus monitor that reads pages like this one, and it checks every
              line that matches, not just the first. So one monitor on the phone covers every monitor
              in Kuma, and when one goes down the alarm names it.
            </p>
            <p>
              I tested this against Uptime Kuma 2.5.9 in Docker, with one monitor up and one pointed
              at a closed port. This is what its page said, cut down to the lines that matter:
            </p>
          </Prose>
          <Output
            label="Kuma 2.5.9, one monitor down"
            lines={METRICS}
            note="Worth running before you touch the phone. If curl prints these lines, the key works and Nightbell will see exactly the same thing. A 401 means the key is wrong or disabled."
          />
          <Prose>
            <p>Each of the four values reads like this to a monitor set to not equal 0:</p>
          </Prose>
          <Outcomes
            label="What each monitor_status value does"
            rows={[
              {
                seen: "1, up",
                says: "passes",
                tone: "up",
                means: "Nothing to see.",
              },
              {
                seen: "0, down",
                says: "fails, names the monitor",
                tone: "down",
                means: "The check fails on that line and quotes it, labels and all, so the alarm says which Kuma monitor went.",
              },
              {
                seen: "2, pending",
                says: "passes",
                tone: "up",
                means: "Kuma is retrying before it decides. Waking you for every retry is how alarms end up muted.",
              },
              {
                seen: "3, maintenance",
                says: "passes",
                tone: "up",
                means: "You put it there yourself.",
              },
            ]}
          />
        </Block>

        <Block id="setup" tone="brand" title="Setting it up.">
          <Steps
            items={[
              {
                title: "Make an API key in Kuma",
                body: (
                  <p>
                    Settings, API Keys, Add API Key. Give it a name and tick Don&apos;t expire, or put a
                    reminder in your calendar for the date you pick. Kuma locks <code>/metrics</code>{" "}
                    behind basic auth, and the key is the password.
                  </p>
                ),
              },
            ]}
          />
          <WideShot
            src="/screens/kuma-api-keys.webp"
            alt="Uptime Kuma 2.5.9's settings, on the API Keys page, with one active key called nightbell that never expires. The monitor list on the left has API at 0 percent and Website at 100."
            caption="Kuma's side of it, with one key made. The list keeps its name, not the key, so copy the key while Kuma is showing it."
            width={1600}
            height={950}
          />
          <Steps
            start={2}
            items={[
              {
                title: "Add a Prometheus monitor in Nightbell",
                body: (
                  <p>
                    Leave the source on Metrics and give it the full address, path included:{" "}
                    <code>https://kuma.example.net/metrics</code>.
                  </p>
                ),
              },
              {
                title: "Leave the username blank, put the key in the password",
                body: (
                  <p>
                    Kuma ignores the username. Over plain <code>http://</code> the key travels in clear
                    text on every check, and the app will tell you so.
                  </p>
                ),
              },
              {
                title: "Metric monitor_status, not equal to 0",
                body: (
                  <>
                    <p>
                      Type it, or use Browse this endpoint and pick it from the list. Then Not equal, and
                      0 as the threshold.
                    </p>
                    <p>
                      Not equal to 0 rather than equal to 1 is on purpose. A monitor Kuma is retrying sits
                      at 2 and one in a maintenance window sits at 3, and neither should wake anybody.
                    </p>
                  </>
                ),
              },
              {
                title: "Test now, then make it urgent",
                body: (
                  <p>
                    With everything up the test reads <code>2 series, all != 0</code>, one series per
                    Kuma monitor. Set the alert to urgent on the last step, or a down Kuma monitor arrives
                    as an ordinary notification.
                  </p>
                ),
              },
            ]}
          />
          <Shots
            shots={[
              {
                src: "/screens/kuma-sign-in.webp",
                alt: "Nightbell's sign-in fields for a Prometheus monitor: the username left blank, the Kuma key in the password, and a warning that basic auth over plain http sends it in clear text.",
                caption: "The username blank and the key in the password. The test Kuma ran on plain http, so the app warns about the key.",
              },
              {
                src: "/screens/kuma-test.webp",
                alt: "Nightbell's setup step for a Prometheus monitor: metric monitor_status, not equal to 0, and a failed test naming the Kuma monitor called API with a value of 0.",
                caption: "Test now against the real Kuma, with the API monitor down. The failure names it.",
              },
            ]}
          />
        </Block>

        <Block id="down" tone="down" title="What you get when a Kuma monitor goes down.">
          <Prose>
            <p>
              On its next check the phone reads the 0, and the monitor goes down with the line that did it.
              Set to urgent, that is a page over the lock screen that repeats, every five minutes by default,
              until you acknowledge it. Kuma answered fine, so the page shows its 200 and how long it took: the thing
              that broke is the monitor Kuma was watching, not Kuma.
            </p>
          </Prose>
          <Shots
            shots={[
              {
                src: "/screens/kuma-alarm.webp",
                alt: "Nightbell's full-screen urgent page: DOWN, Uptime Kuma, the monitor_status line for API with a value of 0, four failed checks, HTTP 200 and the latency.",
                caption: "The page a locked phone opens, built from the stored checks of the run above.",
              },
              {
                src: "/screens/kuma-detail.webp",
                alt: "The saved monitor's detail screen in Nightbell, down, naming the monitor_status series for API, signed in with the key as the password only.",
                caption: "Tap through and the detail screen has the series and every check since.",
              },
            ]}
          />
        </Block>

        <Block id="one" title="Watching one Kuma monitor instead of all of them.">
          <Prose>
            <p>
              Add a label filter: <code>monitor_name=&quot;API&quot;</code>. Now that monitor only
              reads the one line, and the rest of Kuma can be red without it caring. This is how you
              give one service an urgent alarm and leave the others as notifications: one Nightbell
              monitor per Kuma monitor you care about at 3am, each with its own alert setting.
            </p>
          </Prose>
        </Block>

        <Block id="limits" tone="degraded" title="What this does not do.">
          <Prose>
            <p>
              <strong>It is not instant.</strong> Kuma notices a failure inside its own interval, a
              minute by default. The phone hears about it on its next check, and by default Android runs
              those every fifteen minutes or later. Strict monitoring gets that down to one minute for
              the price of a permanent notification. The{" "}
              <Link href="/android-background-limits" className="text-foreground underline underline-offset-4">
                background limits page
              </Link>{" "}
              explains where that floor comes from.
            </p>
            <p>
              <strong>When Kuma itself goes down, this goes down too</strong>, because the page it reads
              is gone. That is the useful half. A Kuma that crashed or lost its certificate sends nothing,
              and its dashboard has nobody to tell. A phone reading it from another network is the thing
              that notices.
            </p>
            <p>
              <strong>It does not show you Kuma&apos;s history or its status pages.</strong> Nightbell keeps
              its own history of what it read, and that is all. Kuma stays the place to look things up.
            </p>
          </Prose>
        </Block>

        <Block id="questions" title="Questions.">
          <Questions items={QUESTIONS} />
          <PageActions second={{ href: "/docs", label: "The full reference" }} />
          <Related
            links={[
              {
                href: "/vs/uptime-kuma",
                title: "Against Uptime Kuma",
                note: "Why a phone and a self-hosted monitor are better together than either one alone.",
              },
              {
                href: "/android-background-limits",
                title: "Why fifteen minutes",
                note: "The platform floor behind the delay above, and what strict mode costs to get under it.",
              },
            ]}
          />
        </Block>
      </main>
      <Footer />
    </>
  )
}
