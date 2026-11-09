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
  PageActions,
  PageHero,
  Prose,
  type QA,
  Questions,
  Related,
  Shots,
} from "../vs/_kit"

const TITLE = "Homelab monitoring from an Android phone, no server"
const DESCRIPTION =
  "Ping, SSH, DNS and web UIs with self-signed certificates, checked from the phone on your Wi-Fi. And what happens to those checks when you leave the house."

export const metadata: Metadata = pageMeta({ path: "/homelab-monitoring-on-android", title: TITLE, description: DESCRIPTION })

const abs = (p: string) => new URL(p, SITE_URL).href

const ARTICLE = {
  "@context": "https://schema.org",
  "@type": "TechArticle",
  "@id": abs("/homelab-monitoring-on-android#article"),
  headline: "Watching a homelab from the phone, not from the homelab",
  description: DESCRIPTION,
  inLanguage: "en",
  dependencies: "Android 8.0 or later",
  author: { "@id": abs("/#author") },
  publisher: { "@id": abs("/#author") },
  about: { "@id": abs("/#app") },
  isAccessibleForFree: true,
  license: "https://www.apache.org/licenses/LICENSE-2.0",
}

const CHECKS: [string, string, string][] = [
  ["The box is on", "Ping", "Answers or not, with the round trip and packet loss. No root needed."],
  ["SSH is up", "TCP port 22", "Connects and hangs up without sending anything. Works the same for a database or any TCP service."],
  ["DNS still resolves", "UDP, DNS probe", "Asks Pi-hole, AdGuard Home or whatever runs on 53 a real question and reads the answer code."],
  ["The web UI loads", "Status check", "Plain http:// is allowed on purpose, so a box on the LAN without a certificate is a valid target."],
  ["Home Assistant is healthy", "Status check or JSON", "The front page for up or down, or an API response with a token in the headers for more."],
]

const QUESTIONS: QA[] = [
  {
    q: "Does anything have to be installed in the lab?",
    a: "No. Every check is an ordinary request from the phone: a ping, a TCP connection, a DNS question, an HTTP request. Nothing in the lab knows it is being watched.",
  },
  {
    q: "How often does it check?",
    a: "Every 15 minutes or so by default, because that is the floor Android gives background work. Strict monitoring brings it down to a minute for the price of a permanent notification.",
  },
  {
    q: "Can it watch things that are only reachable over Tailscale or WireGuard?",
    a: "Yes, while the phone's VPN is connected. The checks are traffic like any other app's, so they go wherever the VPN sends them. With the VPN off they fail, so keep those monitors as notifications rather than urgent.",
  },
  {
    q: "Will it wake me up for my own reboots?",
    a: "Not if you pause first: Pause on a monitor's own screen, or the pause button on the dashboard for everything. Paused checks do not run, so there is nothing to fail.",
  },
]

export default function Page() {
  return (
    <>
      <JsonLd data={ARTICLE} />
      <JsonLd data={faqLd("/homelab-monitoring-on-android#questions", QUESTIONS)} />
      <Nav />
      <main id="main">
        <PageHero
          eyebrow="Guide"
          title={
            <>
              Watch the homelab <Serif>from the phone, not from the homelab.</Serif>
            </>
          }
          lede="Monitoring that runs on the box it watches goes quiet when that box does. A phone on the same Wi-Fi has no such problem, needs nothing installed in the lab, and makes a noise you will actually hear."
        />

        <Block id="checks" title="What to check, and with which kind.">
          <dl className="border-border mt-12 max-w-4xl border-t">
            {CHECKS.map(([what, kind, how]) => (
              <div key={what} className="border-border grid gap-2 border-b py-6 sm:grid-cols-[14rem_11rem_1fr] sm:gap-6">
                <dt className="font-semibold">{what}</dt>
                <dd className="text-brand font-mono text-sm sm:pt-0.5">{kind}</dd>
                <dd className="text-muted-foreground leading-relaxed">{how}</dd>
              </div>
            ))}
          </dl>
          <Prose>
            <p>
              Put a ping and a port check on the same host and the detail screen lists them together, so
              one glance tells you which half broke:
            </p>
          </Prose>
          <Outcomes
            label="Reading a ping and a port check on the same host"
            rows={[
              {
                seen: "Ping up, port down",
                says: "the service",
                tone: "down",
                means: "The box is fine and whatever listens on that port is not. Restart the service, not the machine.",
              },
              {
                seen: "Ping down, port up",
                says: "ICMP is filtered",
                tone: "degraded",
                means: "The service answers, so the host is up. Something on the way drops ping; fix that or drop the ping monitor.",
              },
              {
                seen: "Both down",
                says: "the host or the route",
                tone: "down",
                means: "The box is off, hung, or out of reach from where the phone is. Away from home it is usually the last one.",
              },
            ]}
          />
          <Shots
            shots={[
              {
                src: "/screens/homelab-dashboard.webp",
                alt: "Nightbell's dashboard, all five homelab monitors operational, with two web UIs on top: Admin UI over https with a self-signed certificate and Dashboard over plain http.",
                caption: "Five checks, all against real services on the test machine. The addresses were changed to a home network's for the capture.",
              },
              {
                src: "/screens/homelab-hosts.webp",
                alt: "Further down the same dashboard: a DNS probe reading NOERROR, a ping to the lab server and the SSH port, all operational.",
                caption: "The host checks further down: DNS, ping and SSH.",
              },
            ]}
          />
        </Block>

        <Block id="down" tone="down" title="When something in the lab breaks.">
          <Prose>
            <p>
              For these I stopped the nginx container that served both web UIs and ran every check eight
              times. Both web cards are red with the reason on them, the other three are green, and the
              detail screen lists its neighbours under This host, which is the quickest way to see that
              the box is up and only the web server is gone.
            </p>
            <p>
              Set a monitor to urgent and the same failure takes the whole screen instead of arriving as a
              notification. The{" "}
              <Link href="/uptime-kuma-on-android#down" className="text-foreground underline underline-offset-4">
                Uptime Kuma guide
              </Link>{" "}
              shows that page.
            </p>
          </Prose>
          <Shots
            shots={[
              {
                src: "/screens/homelab-down.webp",
                alt: "Nightbell's dashboard saying 2 of 5 are down, with the Admin UI and Dashboard cards red and reading Connection refused or dropped.",
                caption: "The web server stopped: two of five down, each saying why.",
              },
              {
                src: "/screens/homelab-down-detail.webp",
                alt: "The Admin UI detail screen, down, with the connection error, and under This host the lab server's ping and SSH still operational and Dashboard down.",
                caption: "The detail screen. Ping and SSH on the same host still pass.",
              },
            ]}
          />
        </Block>

        <Block id="certificates" tone="brand" title="Self-signed certificates, without turning checking off.">
          <Prose>
            <p>
              Proxmox, a NAS, a router: half the web UIs in a homelab answer with a certificate no CA
              signed. Every HTTPS monitor has three ways to treat that.
            </p>
            <p>
              <strong>System CAs</strong> is the default and includes any CA you installed on the phone
              yourself, so a lab with its own CA works here once that CA is installed.{" "}
              <strong>Pinned key</strong> records the key on the first good check and demands the same
              one after, which is the right answer for a self-signed box: it works, and it notices if the
              key ever changes. <strong>Any certificate</strong> checks nothing at all, and the detail
              screen reminds you of that on every check.
            </p>
          </Prose>
        </Block>

        <Block id="away" tone="degraded" title="When you leave the house.">
          <Prose>
            <p>
              A monitor on <code>192.168.1.20</code> stops passing the moment the phone is off your
              Wi-Fi. That is true of your network, not a bug in the check, and there is no setting that
              pauses a monitor away from home.
            </p>
            <p>
              Two things work. If the phone runs a VPN back into the lab, WireGuard or anything else,
              the checks go through it like any other app&apos;s traffic and keep passing. If it does
              not, put the urgent alert on monitors that use addresses you can reach from anywhere, and
              keep the LAN-only ones as plain notifications so a walk to the shop does not set off an
              alarm.
            </p>
            <p>
              And if the lab runs its own monitoring, Uptime Kuma or anything else, point one check at
              that too. Monitoring cannot tell you it is down, and a phone can.
            </p>
          </Prose>
        </Block>

        <Block id="questions" title="Questions.">
          <Questions items={QUESTIONS} />
          <PageActions second={{ href: "/docs", label: "The full reference" }} />
          <Related
            links={[
              {
                href: "/uptime-kuma-on-android",
                title: "Uptime Kuma on Android",
                note: "One Nightbell monitor that reads every Kuma monitor's state and alarms on the one that went down.",
              },
              {
                href: "/wireguard-server-monitoring",
                title: "Watching a WireGuard server",
                note: "A service that never answers a stranger, and the one signal you can still read from it.",
              },
            ]}
          />
        </Block>
      </main>
      <Footer />
    </>
  )
}
