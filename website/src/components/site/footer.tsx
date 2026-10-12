import Link from "next/link"

import { RELEASE, REPO } from "@/lib/site"

import { Mark } from "./mark"

const COLUMNS = [
  {
    title: "Product",
    links: [
      { href: "/#how", label: "How it works" },
      { href: "/#urgent", label: "Urgent alerts" },
      { href: "/#limits", label: "Honest limits" },
      { href: "/#install", label: "Install" },
    ],
  },
  {
    title: "Learn",
    links: [
      { href: "/docs", label: "Documentation" },
      { href: "/docs/health-format", label: "Health report format" },
      { href: "/faq", label: "FAQ" },
      { href: "/android-background-limits", label: "Android background limits" },
      { href: "/vs/uptimerobot", label: "Nightbell vs UptimeRobot" },
      { href: "/vs/uptime-kuma", label: "Nightbell vs Uptime Kuma" },
    ],
  },
  {
    title: "Source",
    links: [
      { href: REPO.url, label: "Repository", external: true },
      { href: REPO.releases, label: "All releases", external: true },
      { href: REPO.reference, label: "Reference docs", external: true },
      { href: REPO.license, label: "Licence, Apache 2.0", external: true },
    ],
  },
  {
    title: "Contact",
    links: [
      { href: REPO.bugReport, label: "Report a bug", external: true },
      { href: REPO.featureRequest, label: "Request a feature", external: true },
      { href: `https://github.com/${REPO.owner}`, label: "The person who built it", external: true },
    ],
  },
]

export function Footer() {
  return (
    <footer className="border-border relative overflow-hidden border-t">
      <div className="container-page pt-20 pb-10">
        <div className="grid gap-12 lg:grid-cols-[1.3fr_2fr]">
          <div className="max-w-sm">
            <Link href="/" className="inline-flex min-h-11 items-center gap-2.5 text-base font-semibold tracking-tight">
              <Mark size={26} />
              Nightbell
            </Link>
            <p className="text-muted-foreground mt-5 text-[15px] leading-relaxed">
              Uptime monitoring that runs on your phone, keeps your tokens on the device, and pages
              you like a phone call when something actually breaks.
            </p>
            <p className="text-muted-foreground mt-5 font-mono text-xs leading-relaxed">
              No analytics. No account. What leaves the device is the check itself, to the host you
              typed in, a connectivity probe you can repoint or switch off, so dead Wi-Fi is not
              reported as a dead service, and a version check every six hours that is counted but
              carries nothing that points back at your phone.
            </p>
          </div>
          <div className="grid grid-cols-2 gap-10 sm:grid-cols-4">
            {COLUMNS.map((col) => (
              <div key={col.title}>
                <h2 className="eyebrow mb-4">{col.title}</h2>
                <ul className="space-y-1 lg:space-y-3">
                  {col.links.map((l) => (
                    <li key={l.href}>
                      {"external" in l && l.external ? (
                        <a
                          href={l.href}
                          target="_blank"
                          rel="noopener"
                          className="text-foreground/80 hover:text-foreground inline-flex min-h-11 min-w-11 items-center text-sm transition-colors lg:min-h-0 lg:min-w-0"
                        >
                          {l.label}
                        </a>
                      ) : (
                        <Link
                          href={l.href}
                          className="text-foreground/80 hover:text-foreground inline-flex min-h-11 min-w-11 items-center text-sm transition-colors lg:min-h-0 lg:min-w-0"
                        >
                          {l.label}
                        </Link>
                      )}
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        </div>

        <div className="border-border text-muted-foreground mt-16 flex flex-wrap gap-x-6 gap-y-2 border-t pt-6 font-mono text-xs">
          <span>Nightbell v{RELEASE.version}</span>
          <span>Apache 2.0</span>
          <span>
            Android {RELEASE.minAndroid} and up, targeting API {RELEASE.targetSdk}
          </span>
          <span>Built and operated by one person</span>
        </div>
      </div>

      <div
        aria-hidden="true"
        className="pointer-events-none -mb-[0.2em] text-center leading-none font-semibold tracking-[-0.06em] select-none"
        style={{ fontSize: "clamp(4rem, 19vw, 17rem)" }}
      >
        <span className="from-foreground/[0.14] bg-linear-to-b to-transparent bg-clip-text text-transparent">
          Nightbell
        </span>
      </div>
    </footer>
  )
}
