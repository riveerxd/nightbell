import type { Metadata } from "next"

import { Footer } from "@/components/site/footer"
import { Nav } from "@/components/site/nav"
import { Serif } from "@/components/site/section"
import { REPO } from "@/lib/site"
import { pageMeta } from "@/lib/seo"

import {
  Block,
  CompareTable,
  faqLd,
  JsonLd,
  PageActions,
  PageHero,
  Pick,
  Prose,
  Questions,
  Related,
  Verdict,
  type QA,
} from "../_kit"

const TITLE = "Nightbell vs Uptime Kuma: the pager problem"
const DESCRIPTION =
  "Uptime Kuma watches while you sleep and keeps the history, and it cannot tell you when it is the thing that is down. Nightbell runs on your phone, needs no machine, and pages you with a local alarm. Why running both is the setup, and how to point one at the other."

export const metadata: Metadata = pageMeta({ path: "/vs/uptime-kuma", title: TITLE, description: DESCRIPTION })

const QUESTIONS: QA[] = [
  {
    q: "Should I stop running Uptime Kuma?",
    a: "No. If you already have it running, it is doing the half of the job Nightbell cannot do: watching while you sleep, keeping the history, and answering to other people through a status page. Nothing on this page is an argument for turning it off. The argument is that the last hop of its alerting runs on a machine you also have to keep alive, and that a phone in the room with you is a better place to put an alarm than a webhook is.",
  },
  {
    q: "What does Nightbell do that Uptime Kuma cannot?",
    a: "Two things, and the second one is the real answer. It needs no machine, so there is nothing to provision, certificate, update or back up, and the setup cost is installing an APK rather than standing up a container. And it can watch Uptime Kuma. A self-hosted monitor is structurally unable to tell you it is down, because the thing that would send the message is the thing that is down; a pager on a phone, on a different network, with a local alarm, closes exactly that gap and costs one monitor to set up.",
  },
  {
    q: "Can Nightbell watch my Uptime Kuma instance?",
    a: "Yes, and it is the setup worth doing even if you change nothing else. Point a status check at your instance and set the alert to urgent. Now the failure mode where your monitoring host reboots into a broken certificate at 4am, and every monitor on it goes quiet rather than red, is a full-screen alarm on your phone instead of a thing you find out about from a customer. Nightbell runs on a different device and a different network, which is what makes it able to see the outage at all.",
  },
  {
    q: "Does it do notification providers the way Uptime Kuma does?",
    a: "Not dozens. It posts to Slack, Teams, Discord, Google Chat, ntfy, Gotify, Telegram or any webhook, and that is the whole list. Uptime Kuma fans out to dozens of services because it is a server with no screen and no speaker, so reaching a person means handing the message to something else. Nightbell is already on the device it needs to reach, so the alert is a full-screen activity and a looping alarm on a channel that bypasses Do Not Disturb. The page on the phone itself needs no delivery step. That is a narrower feature and a shorter failure chain, and which of those two you want depends on whether your problem is routing alerts or sleeping through them.",
  },
]

const ROWS: [string, string, string][] = [
  ["What you have to run", "A machine, a container, a certificate, and updates for all three.", "Nothing. It is an APK."],
  ["While your phone is off", "Still watching.", "Not watching. That is the cost of having no server."],
  ["Reaching you", "A notification provider, a webhook, or an app in the middle.", "No delivery step for the alarm: it is already on the device. Webhooks too, if you want them."],
  [
    "At 3am you get",
    "Whatever your provider delivered, however your phone decided to show it.",
    "The whole screen in red and an alarm looping until acknowledged.",
  ],
  ["Status page", "Yes, and other people can read it.", "None."],
  ["Who watches it", "Nothing on the same box, by definition.", "Different device, different network, so it can watch the box."],
  ["Setup cost", "An evening, then ongoing maintenance.", "Four permission grants and a URL."],
]

export default function Page() {
  return (
    <>
      <JsonLd data={faqLd("/vs/uptime-kuma#faq", QUESTIONS)} />
      <Nav />
      <main id="main">
        <PageHero
          eyebrow="Comparison"
          title={
            <>
              Uptime Kuma cannot page you <Serif>about Uptime Kuma.</Serif>
            </>
          }
          lede="That is not a criticism of it. It is the one failure a self-hosted monitor is structurally unable to cover, and it is the failure a pager on your phone is for."
        >
          <Verdict
            items={[
              {
                lead: (
                  <>
                    Keep <Pick>Uptime Kuma</Pick>. It is doing work Nightbell cannot.
                  </>
                ),
                body: "It watches while your phone is off, it holds the history, it has status pages and maintenance windows, and it fans out to dozens of notification services. Nothing here is an argument for turning it off.",
              },
              {
                lead: (
                  <>
                    Add <Pick>Nightbell</Pick> as the thing that makes the noise.
                  </>
                ),
                body: "No machine to keep alive, no certificate to renew, and an alarm that loops on a channel that bypasses Do Not Disturb. Point it at your Kuma instance and the one outage your monitoring cannot report becomes the one it shouts about.",
              },
            ]}
          />
        </PageHero>

        <Block
          id="side-by-side"
          title="Two tools that overlap on the word monitoring and almost nowhere else."
          lede="Structural rows only. None of this changes when either project ships a release."
        >
          <CompareTable
            label="Uptime Kuma and Nightbell compared"
            caption="Uptime Kuma and Nightbell compared across seven aspects"
            them={{ name: "Uptime Kuma", eg: "on a box you run" }}
            rows={ROWS}
          />
        </Block>

        <section id="the-gap" className="relative bg-[#f8eef0] py-20 sm:py-28 dark:bg-[#11080a] dark:[--border:rgb(255_255_255/0.1)] dark:[--muted-foreground:#c4adb0]">
          <div className="pointer-events-none absolute inset-x-0 top-0 h-px bg-down/50" aria-hidden />
          <div className="container-page">
            <h2 className="display max-w-5xl text-[clamp(2.6rem,6.4vw,5.75rem)] text-balance">
              The outage your monitoring cannot report{" "}
              <span className="text-down">is the one it is having.</span>
            </h2>
            <Prose>
              <p>
                Every self-hosted monitor has the same hole in it, and it is not a bug anybody can
                fix. The component that notices a failure and the component that tells you about it
                are on the same machine. When that machine reboots into a broken certificate, fills
                its disk, or comes back up with the container stopped, nothing is noticed and
                therefore nothing is sent. The dashboard is not red. The dashboard is not there.
              </p>
              <p>
                What that looks like in practice is a quiet night. No alerts is indistinguishable from
                everything being fine, which is exactly why it goes on for hours, and it is usually a
                customer who ends it.
              </p>
              <p>
                A phone closes it because a phone is somewhere else. Different device, different
                network, different power supply, nothing shared with the box except the thing being
                watched. Point one status check at your instance, set the alert to urgent, and the
                failure that used to be silence becomes the loudest thing in the room.
              </p>
            </Prose>
          </div>
        </section>

        <Block
          id="setup"
          tone="degraded"
          title="One monitor, and the settings that make it worth having."
          className="border-t-0"
        >
          <Prose>
            <p>
              Add a status check pointing at your instance over the address you would actually reach
              it on from outside the house, not the LAN one. A monitor on a LAN address stops passing
              the moment you leave, which is a true fact about your network rather than a bug, and it
              is the easiest way to build this setup wrong.
            </p>
            <p>
              Set the alert to urgent so it takes the whole screen rather than arriving as a
              notification, and grant Do Not Disturb access so it is allowed to make noise at the
              hour you need it to. Urgent is on the monitor&apos;s cadence step, Do Not Disturb access is
              in the pager setup screen, and neither is on by
              default, because an app that gave itself permission to override your phone&apos;s silent
              mode without being asked would deserve the review it got.
            </p>
            <p>
              Leave the cadence on the default. Fifteen minutes is Android&apos;s floor for background
              work and it is the right interval for this particular monitor: you are watching for a
              host that has gone away entirely, not for a slow response, and strict mode&apos;s
              permanent notification is not worth spending on a check that would catch the outage on
              its next pass anyway.
            </p>
          </Prose>
        </Block>

        <Block id="faq" title="What people actually ask about running the two together.">
          <Questions items={QUESTIONS} />
          <PageActions second={{ href: REPO.reference, label: "Reference docs", external: true }} />
          <Related
            links={[
              {
                href: "/vs/uptimerobot",
                title: "Against UptimeRobot",
                note: "The hosted case, where the account and where your tokens live are back on the table.",
              },
              {
                href: "/android-background-limits",
                title: "Why fifteen minutes",
                note: "The platform floor behind the cadence advice above, and what a foreground service buys.",
              },
            ]}
          />
        </Block>
      </main>
      <Footer />
    </>
  )
}
