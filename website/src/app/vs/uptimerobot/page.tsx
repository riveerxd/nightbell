import type { Metadata } from "next"

import { pageMeta } from "@/lib/seo"

import { Footer } from "@/components/site/footer"
import { Nav } from "@/components/site/nav"
import { Serif } from "@/components/site/section"

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

const TITLE = "Nightbell vs UptimeRobot: phone or hosted service"
const DESCRIPTION =
  "UptimeRobot keeps watching while your phone is off and holds the history; Nightbell runs every check on the device, needs no account, and pages you with a full-screen alarm instead of a push notification. Which one to pick, and why running both is reasonable."

export const metadata: Metadata = pageMeta({ path: "/vs/uptimerobot", title: TITLE, description: DESCRIPTION })

/*
  Nothing here states a price, a free tier limit, a monitor count or a check
  interval for the other product. Those change without notice. Every claim is
  structural and checkable by a reader in thirty seconds.
*/
const QUESTIONS: QA[] = [
  {
    q: "Is Nightbell a replacement for UptimeRobot?",
    a: "For most people, no, and the reason is not a feature. A hosted monitor runs on somebody else’s machine, so it keeps checking while your phone is off, out of signal or flat. Nightbell runs every check on the phone, so a phone that is asleep in a drawer is a monitor that is not checking. If the thing you need is a service that watches without you, Nightbell is the wrong tool and no amount of it being free changes that. What it replaces is the paging half: the part that is supposed to wake you at 3am and usually arrives as a push notification you slept through.",
  },
  {
    q: "Can I use both at the same time?",
    a: "Yes, and that is the setup this app was built for. Point Nightbell at the same endpoints your hosted monitor watches and you get two independent checks from two different networks, one of which is the network you are actually on. The hosted one keeps the history and the status page and catches what happens while you sleep; the local one is the thing that makes a noise you cannot swipe away. Nightbell can also watch the hosted service’s own status endpoint, which is the one gap a hosted monitor structurally cannot cover: it cannot tell you it is down.",
  },
  {
    q: "Does Nightbell need an account?",
    a: "No, and there is nothing to make one with. There is no server, no user table and no login screen anywhere in the app, which is why the download is an APK and not a sign-up. The practical consequence is that there is exactly one copy of your monitors and it is on the device, so Settings, About, Backup and transfer is the only way they get off it.",
  },
  {
    q: "Where do my API tokens end up?",
    a: "On the phone, in one JSON document in DataStore inside the app private directory, with sign-in secrets kept apart in an encrypted keystore vault. A request monitor can carry custom headers, and those headers are a credential, which is the reason this matters: with a hosted monitor the token has to be stored on their infrastructure for their prober to send it, and with Nightbell the prober is the phone, so there is nowhere else for it to be. Nightbell also ships no analytics library and no crash reporter, which is checkable against the dependency list rather than something you have to take on trust.",
  },
]

const ROWS: [string, string, string][] = [
  ["What does the checking", "Their probes, in their data centres.", "Your phone, over whatever connection it is on."],
  ["While your phone is off", "Still watching.", "Not watching. This is the honest cost of having no server."],
  ["Account", "Required.", "There is nothing to sign up to."],
  [
    "Where your tokens sit",
    "On their infrastructure, because their prober has to send them.",
    "On the device, because the device is the prober.",
  ],
  ["At 3am you get", "An email or a push notification.", "The whole screen in red and an alarm looping until you acknowledge it."],
  ["History", "Kept for you, on their side, for as long as your plan says.", "Samples on the device. One phone, one copy, no cloud backup."],
  ["Status page", "Yes, and other people can read it.", "None. There is no server to serve one from."],
  [
    "Who watches the watcher",
    "Nobody. A hosted monitor cannot tell you it is down.",
    "You can point it at the hosted monitor's own status endpoint.",
  ],
]

export default function Page() {
  return (
    <>
      <JsonLd data={faqLd("/vs/uptimerobot#faq", QUESTIONS)} />
      <Nav />
      <main id="main">
        <PageHero
          eyebrow="Comparison"
          title={
            <>
              Nightbell and UptimeRobot <Serif>are not the same kind of thing.</Serif>
            </>
          }
          lede="One is a monitoring service that runs on somebody else's machines. The other is a pager that runs on yours. Most of the confusion between them is that both are described as uptime monitoring."
        >
          <Verdict
            items={[
              {
                lead: (
                  <>
                    Pick <Pick>UptimeRobot</Pick> if the checking has to happen without you.
                  </>
                ),
                body: "It runs on their infrastructure, so it watches while your phone is off, flat, in flight mode or in a tunnel. It keeps the history, it publishes a status page other people can read, and setting it up is a form rather than four Android permissions. Nightbell does none of that and is not trying to.",
              },
              {
                lead: (
                  <>
                    Pick <Pick>Nightbell</Pick> if the alert is what keeps failing.
                  </>
                ),
                body: "An outage arrives as a full-screen page with an alarm that loops until you acknowledge it, on a channel that bypasses Do Not Disturb once you grant it. No account, no server, and your tokens stay on the device because the device is what makes the request.",
              },
              {
                lead: (
                  <>
                    Run <Pick>both</Pick> if you are on call for something that matters.
                  </>
                ),
                body: "They fail in different directions, so each covers the gap the other leaves. Setting up the pair is further down the page.",
              },
            ]}
          />
        </PageHero>

        <Block
          id="side-by-side"
          title="Where the two actually differ, rather than where the feature lists do."
          lede="Every row here is structural. None of it moves when either product ships a release."
        >
          <CompareTable
            label="UptimeRobot and Nightbell compared"
            caption="UptimeRobot and Nightbell compared across eight aspects"
            them={{ name: "UptimeRobot", eg: "a hosted service" }}
            rows={ROWS}
          />
        </Block>

        <Block id="hosted-wins" title="Three things a phone structurally cannot do.">
          <Prose>
            <p>
              <strong>It watches while you are not there.</strong> This is the one that matters and it
              is not a feature gap that a future release closes. The check runs on the device, so a
              device that is off, flat or out of signal is a check that did not happen. Nightbell
              tells you that in Settings under Checker health rather than quietly reporting everything
              as fine, but telling you is not the same as watching.
            </p>
            <p>
              <strong>It keeps history you can hand to somebody.</strong> A hosted monitor holds
              months of it on infrastructure that is not yours to lose. Nightbell keeps samples on the
              phone, which means the history and the phone have the same fate, and Settings, Backup
              and transfer is the only thing standing between a dropped handset and starting over.
            </p>
            <p>
              <strong>It publishes a status page.</strong> Other people can read a hosted status page
              without installing anything, which is most of the point of having one during an
              incident. There is no server here, so there is nothing to serve one from, and
              pretending otherwise would mean building the exact thing this app exists to avoid.
            </p>
          </Prose>
        </Block>

        <Block
          id="phone-wins"
          tone="down"
          title={
            <>
              The alert is the part that keeps failing, <Serif>and it is the part that runs locally.</Serif>
            </>
          }
          lede="A hosted monitor's last mile is a push notification. Yours is an alarm the operating system is already built to let through."
        >
          <Prose>
            <p>
              A push notification has to survive a delivery service, a doze window, a notification
              channel and whatever your phone was doing at the time. It arrives silent, it stacks
              behind eleven other things, and it is dismissed by the same swipe that clears a
              delivery update. Every person who has ever slept through an outage slept through a
              notification that was delivered correctly.
            </p>
            <p>
              Nightbell does not need to send you the alarm. It is already on the device that would have
              received the push, so the outage becomes a full-screen activity in red with a looping
              alarm, on a dedicated channel that bypasses Do Not Disturb once you grant access. It
              keeps making noise until you acknowledge it or the monitor recovers. The page on your
              phone has no delivery step to fail.
            </p>
            <p>
              The second thing is quieter and matters more to some people than the alarm does. A
              request monitor can carry custom headers, and a header is often a credential. With a
              hosted monitor that credential has to live on their infrastructure, because their
              prober is what sends it. Here the prober is your phone, so the token has nowhere else
              to be, and the app ships no analytics library and no crash reporter to send it anywhere
              by accident. That is checkable against the dependency list rather than a promise.
            </p>
          </Prose>
        </Block>

        <Block
          id="both"
          tone="degraded"
          title="They fail in different directions, which is the argument for two."
        >
          <Prose>
            <p>
              Point both at the same endpoints. The hosted service checks from its network and keeps
              the record; Nightbell checks from the network you are actually standing on, which
              catches the class of outage where the service is up and your route to it is not. Two
              probes from two places disagreeing is information rather than noise.
            </p>
            <p>
              Then point Nightbell at the hosted monitor&apos;s own status endpoint. That is the gap
              no hosted monitor can close for itself: when it is the thing that is down, it is also
              the thing that would have told you. A local pager watching the remote watcher costs one
              monitor.
            </p>
            <p>
              Set the hosted alerts to email and let them be the record. Let the phone be the thing
              that makes a noise. That is the split this app was built for, and it is why the FAQ has
              said since the first release that if you already run a monitoring service, Nightbell is
              a reasonable pager to put in front of it.
            </p>
          </Prose>
        </Block>

        <Block id="faq" title="The four that decide it.">
          <Questions items={QUESTIONS} />
          <PageActions second={{ href: "/faq", label: "All sixteen answers" }} />
          <Related
            links={[
              {
                href: "/vs/uptime-kuma",
                title: "Against Uptime Kuma",
                note: "The self-hosted case, where the account and the bill are already gone and the argument is different.",
              },
              {
                href: "/android-background-limits",
                title: "Why fifteen minutes",
                note: "What Android will and will not let a background app do, and what strict mode costs to get around it.",
              },
            ]}
          />
        </Block>
      </main>
      <Footer />
    </>
  )
}
