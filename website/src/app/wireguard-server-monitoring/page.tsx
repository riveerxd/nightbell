import type { Metadata } from "next"

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
  SilentTimeline,
  Steps,
} from "../vs/_kit"

const TITLE = "Monitoring a WireGuard server from an Android phone"
const DESCRIPTION =
  "WireGuard ignores packets it cannot authenticate, so a port check that waits for a reply calls a healthy server dead. What to check instead, and what it still misses."

export const metadata: Metadata = pageMeta({ path: "/wireguard-server-monitoring", title: TITLE, description: DESCRIPTION })

const abs = (p: string) => new URL(p, SITE_URL).href

const ARTICLE = {
  "@context": "https://schema.org",
  "@type": "TechArticle",
  "@id": abs("/wireguard-server-monitoring#article"),
  headline: "Monitoring a WireGuard server when it never answers",
  description: DESCRIPTION,
  inLanguage: "en",
  dependencies: "Android 8.0 or later",
  author: { "@id": abs("/#author") },
  publisher: { "@id": abs("/#author") },
  about: { "@id": abs("/#app") },
  isAccessibleForFree: true,
  license: "https://www.apache.org/licenses/LICENSE-2.0",
}

const QUESTIONS: QA[] = [
  {
    q: "Why does Test now take most of a minute?",
    a: "Because a healthy WireGuard server says nothing, and the only way to hear nothing is to wait for it. At the defaults that is three tries of 15 seconds and then a ping. A closed port is refused in milliseconds, so the slow answer is the good one.",
  },
  {
    q: "Can I make it quicker?",
    a: "Use fewer tries or a shorter timeout on the same screen. The extra tries are there so one lost refusal does not read as up, so on a lossy link keep at least two.",
  },
  {
    q: "My server drops ping. Can I still watch it?",
    a: "Not with silent mode alone. It will read no refusal and no ping reply every time and say it cannot tell. Allow ICMP echo through, or add a check on something else on the same host that does answer, like SSH on a TCP port.",
  },
  {
    q: "Does the phone have to be on the VPN?",
    a: "No, and for this check it should not be. It looks at the server from outside, the way a peer does before the handshake. Checks on things behind the tunnel are a separate monitor that needs the tunnel up.",
  },
]

export default function Page() {
  return (
    <>
      <JsonLd data={ARTICLE} />
      <JsonLd data={faqLd("/wireguard-server-monitoring#questions", QUESTIONS)} />
      <Nav />
      <main id="main">
        <PageHero
          eyebrow="Guide"
          title={
            <>
              WireGuard never answers a stranger. <Serif>That is the whole problem.</Serif>
            </>
          }
          lede="A WireGuard server drops every packet it cannot authenticate, without a word. That is good security and it breaks every monitor that waits for a reply. This is what Nightbell checks instead, and where that check runs out."
        />

        <Block id="why" title="Why the usual checks get it wrong.">
          <Prose>
            <p>
              <strong>A TCP port check</strong> says down every time, because WireGuard only speaks UDP
              and nothing is listening on TCP 51820. People ran into exactly that with Nightbell&apos;s
              own TCP check, which is why it has a UDP check now.
            </p>
            <p>
              <strong>A UDP check that waits for a reply</strong> also says down every time. A healthy
              server and a dead one look the same from outside: nothing comes back. The only packet
              you can get out of it without the keys is the kernel saying the port is closed.
            </p>
            <p>
              <strong>Ping</strong> tells you the machine is up. It says nothing about whether
              WireGuard is running on it.
            </p>
            <p>
              netcat shows the whole difficulty in two lines. Against the WireGuard port it reports
              success, which only means nothing refused it within three seconds. Against a port with
              nothing behind it, it fails, because the kernel refused it straight away.
            </p>
          </Prose>
          <Output
            label="netcat on the WireGuard host"
            lines={[
              { text: "$ nc -vzu -w 3 127.0.0.1 51820" },
              { text: "Connection to 127.0.0.1 51820 port [udp/*] succeeded!", tone: "up" },
              { text: "$ nc -vzu -w 3 127.0.0.1 51821" },
              { text: "$ echo $?" },
              { text: "1", tone: "down" },
            ]}
            note="That success is a guess, and it is the same guess a powered-off host earns from far enough away. Silent mode makes the same reading and then asks the host for a ping before it calls anything up."
          />
        </Block>

        <Block id="silent" tone="brand" title="Silent mode reads the one signal there is.">
          <Prose>
            <p>
              Nightbell sends a datagram to the port and listens for two different kinds of nothing.
              If nothing is bound to the port, the host sends back an ICMP port unreachable, and that
              is a real answer: the check goes down and says the port is closed. If no refusal comes,
              something is holding the port, so it pings the host in the same check. A reply there
              means the host is alive and the port is taken, and the monitor reads{" "}
              <code>Not refused, host answers ping</code>.
            </p>
            <p>
              If neither comes back, it goes down and says it cannot tell, because silence is also what
              a host that has fallen off the internet sounds like.
            </p>
          </Prose>
          <SilentTimeline />
          <Outcomes
            label="What silent mode reports"
            rows={[
              {
                seen: "A refusal",
                says: "UDP port 51820 closed on vpn.example.net",
                tone: "down",
                means: "The host is up and nothing listens on the port. WireGuard is stopped, or listening somewhere else.",
              },
              {
                seen: "No refusal, ping answers",
                says: "Not refused, host answers ping",
                tone: "up",
                means: "Something holds the port on a live host. Almost always WireGuard, though see below for what this cannot rule out.",
              },
              {
                seen: "No refusal, no ping",
                says: "No refusal and no ping reply",
                tone: "down",
                means: "The host is down, unreachable from where the phone is, or drops ping. The monitor says it cannot tell which.",
              },
            ]}
          />
          <Steps
            items={[
              {
                title: "Add a UDP port monitor",
                body: <p>The host is the server&apos;s public address or name, the same one your peers use.</p>,
              },
              {
                title: "Tap the WireGuard chip",
                body: (
                  <p>
                    It fills in 51820 and switches the probe to silent. If your server listens on another
                    port, type that port and pick silent yourself.
                  </p>
                ),
              },
              {
                title: "Make sure the host answers ping",
                body: (
                  <p>
                    Silent mode leans on it. A server whose firewall drops ICMP echo will always read as
                    &quot;cannot tell&quot;, and the monitor will say exactly that rather than guess.
                  </p>
                ),
              },
            ]}
          />
          <Shots
            shots={[
              {
                src: "/screens/wireguard-chip.webp",
                alt: "The target step of a new UDP monitor in Nightbell, with the 51820 WireGuard chip selected and the port filled in.",
                caption: "The WireGuard chip. The host here is the test machine as the emulator sees it.",
              },
              {
                src: "/screens/wireguard-before.webp",
                alt: "The expectations step in silent mode, explaining that the service won't answer a stranger, with Test now and a line under it: takes up to 60 s, 3 tries of 15 s each, then a ping.",
                caption: "Before the tap, the screen says how long the test can take.",
              },
              {
                src: "/screens/wireguard-try.webp",
                alt: "Test now running in silent mode: try 2 of 3, listening for a refusal, up to 38 seconds left, with a bar for each try and one for the ping.",
                caption: "While it waits: which try, how long is left, and what it is listening for.",
              },
              {
                src: "/screens/wireguard-up.webp",
                alt: "Nightbell's detail screen for a UDP monitor on port 51820 in silent mode, up, reading Not refused, host answers ping.",
                caption: "The saved monitor after seven checks against a real WireGuard server in Docker. The 1 ms is the ping; the check itself took most of a minute.",
              },
            ]}
          />
        </Block>

        <Block id="server" title="What the server knows that the phone cannot.">
          <Prose>
            <p>
              When the monitor says up and you want to be sure, ask the server. <code>wg show</code>{" "}
              prints the port it holds, and for each peer the last handshake, which is the one thing that
              proves the tunnel is actually used.
            </p>
          </Prose>
          <Output
            label="wg show, on the server in the screenshots"
            lines={[
              { text: "$ wg show" },
              { text: "interface: wg0" },
              { text: "  public key: LJ6+rcLI/eA3loa4AbUNYRjPENIiVeiWvSLiCmOYUys=" },
              { text: "  private key: (hidden)" },
              { text: "  listening port: 51820", tone: "up" },
              { text: "" },
              { text: "peer: EYCLNdBaxd9/KoqOwAPf+xA+rheesqtvJnbEZywzvD0=" },
              { text: "  preshared key: (hidden)" },
              { text: "  allowed ips: 10.13.13.2/32" },
            ]}
            note="No latest handshake line under the peer: nobody has connected yet. Silent mode reads this server as up, correctly, because the port is held. Whether peers get in is a question only the inside of the tunnel can answer."
          />
        </Block>

        <Block id="limits" tone="degraded" title="What silent mode cannot see.">
          <Prose>
            <p>
              <strong>A process that is hung but still holds the port.</strong> The kernel keeps the
              port bound, so there is no refusal, and the host still answers ping. It reads as up. This
              is the weakest &quot;up&quot; any check in the app gives, and the app words it that way on
              purpose.
            </p>
            <p>
              <strong>A server that accepts the handshake and routes nothing.</strong> Forwarding
              switched off, a wrong <code>AllowedIPs</code>, a dead upstream. From outside the tunnel all
              of that looks healthy. The only way to see it is from inside: a check on something you can
              only reach through the tunnel, run while the phone is connected to it.
            </p>
            <p>
              <strong>A refusal that never arrives.</strong> Down depends on the host&apos;s ICMP
              port unreachable getting back to the phone. If something on the way swallows it, a
              stopped server looks exactly like a running one. I could not show you the down screen
              here for that reason: the Android emulator&apos;s network drops those packets, so the
              stopped container in this test still read as up. A real network normally passes the
              refusal back, and Nightbell&apos;s own tests check that path against a server on the
              device itself.
            </p>
            <p>
              <strong>Anything while your phone is off.</strong> The checks run on the phone. That is
              the price of having no server, and the other pages are honest about it too.
            </p>
          </Prose>
        </Block>

        <Block id="questions" title="Questions.">
          <Questions items={QUESTIONS} />
          <PageActions second={{ href: "/docs", label: "The full reference" }} />
          <Related
            links={[
              {
                href: "/homelab-monitoring-on-android",
                title: "The rest of the homelab",
                note: "Ping, SSH, web UIs with self-signed certificates, and what changes when you leave the house.",
              },
              {
                href: "/faq",
                title: "The rest of the questions",
                note: "What leaves the device, whether the alarm beats Do Not Disturb, and what it does to the battery.",
              },
            ]}
          />
        </Block>
      </main>
      <Footer />
    </>
  )
}
