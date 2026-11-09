import { Fragment } from "react";
import type { Metadata } from "next";
import Link from "next/link";
import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { ArrowUpRight, ChevronDown, Download } from "lucide-react";

import { PhoneFrame } from "@/components/site/depth";
import { Footer } from "@/components/site/footer";
import { Nav } from "@/components/site/nav";
import releases from "@/lib/changelog.json";
import { pageMeta } from "@/lib/seo";
import { DOWNLOAD_PATH, RELEASE } from "@/lib/site";

/*
  The release notes as they were published on GitHub, written into
  src/lib/changelog.json by scripts/changelog.mjs before every build. Nothing on
  this page is typed by hand except the furniture around them.
*/

type Release = (typeof releases)[number];

const DAY = 86_400_000;
const first = releases[releases.length - 1];
const newest = releases[0];
const span = Math.round(
  (Date.parse(newest.date) - Date.parse(first.date)) / DAY,
);
const firstNightbell = [...releases]
  .reverse()
  .find((r) => r.name === "Nightbell")!;

const description = `Every Nightbell release from ${first.version} to ${newest.version}, with the notes each one shipped with: what changed, what was fixed and why.`;

export const metadata: Metadata = pageMeta({
  path: "/changelog",
  title: `Nightbell changelog: every release, ${first.version} to ${newest.version}`,
  description,
});

const date = (d: string, month: "short" | "long" = "short") =>
  new Date(`${d}T00:00:00Z`).toLocaleDateString("en-GB", {
    day: "numeric",
    month,
    year: "numeric",
    timeZone: "UTC",
  });

const id = (r: Release) => `v${r.version}`;
const mb = (b: number) => `${(b / 1e6).toFixed(1)} MB`;

const GROUPS = [
  { label: "Nightbell 3", test: (r: Release) => r.version.startsWith("3.") },
  { label: "Pulse 2", test: (r: Release) => r.version.startsWith("2.") },
  { label: "Pulse 1", test: (r: Release) => r.version.startsWith("1.") },
];

// Below lg the index rail is gone, and on a phone this page is long enough that
// scrolling is no way to find 2.2.0. The chevron turns with the open state so the
// row says which way it will go before it is tapped.
function Jump() {
  return (
    <details className="group border-border mb-12 rounded-2xl border lg:hidden">
      <summary className="flex min-h-12 cursor-pointer list-none items-center justify-between gap-4 px-4 text-sm font-semibold [&::-webkit-details-marker]:hidden">
        Jump to a release
        <ChevronDown
          className="text-muted-foreground size-4 transition-transform duration-200 ease-out group-open:rotate-180"
          aria-hidden
        />
      </summary>
      <div className="border-border space-y-6 border-t px-4 pt-4 pb-6">
        {GROUPS.map((g) => (
          <div key={g.label}>
            <p className="eyebrow mb-2 text-center">{g.label}</p>
            <ol className="grid grid-cols-3 gap-2 sm:grid-cols-6">
              {releases.filter(g.test).map((r) => (
                <li key={r.tag}>
                  <a
                    href={`#${id(r)}`}
                    className="border-border text-muted-foreground hover:text-foreground hover:border-foreground/30 flex h-11 items-center justify-center rounded-lg border font-mono text-sm tabular-nums transition-colors duration-200"
                  >
                    {r.version}
                  </a>
                </li>
              ))}
            </ol>
          </div>
        ))}
      </div>
    </details>
  );
}

function Index() {
  return (
    <nav aria-label="Releases" className="hidden lg:block">
      <div className="sticky top-24 max-h-[calc(100dvh-7rem)] overflow-y-auto pr-2">
        {GROUPS.map((g) => {
          const items = releases.filter(g.test);
          return (
            <div key={g.label} className="mb-6">
              <p className="eyebrow mb-2">
                {g.label} · {items.length}
              </p>
              <ol>
                {items.map((r) => (
                  <li key={r.tag}>
                    <a
                      href={`#${id(r)}`}
                      className="text-muted-foreground hover:text-foreground flex items-baseline justify-between gap-3 rounded-md py-1 text-sm transition-colors"
                    >
                      <span className="font-mono">{r.version}</span>
                      <span className="text-xs">
                        {date(r.date).replace(/ \d{4}$/, "")}
                      </span>
                    </a>
                  </li>
                ))}
              </ol>
            </div>
          );
        })}
      </div>
    </nav>
  );
}

// Release notes use ## for their own sections. Under a page whose h2 is the
// version, those step down a level so the outline stays a tree.
const shift = {
  h1: "h3",
  h2: "h3",
  h3: "h4",
  h4: "h5",
  h5: "h6",
} as const;

/*
  Screenshots that show what one release added, taken from the guides. Only a
  release whose change is actually on screen gets one: a picture of the current
  app beside notes about something else would be a caption telling a lie.
*/
type Shot = { src: string; alt: string; caption: string };
const SHOTS: Record<string, Shot[]> = {
  "3.24.0": [
    {
      src: "/screens/wireguard-try.webp",
      alt: "Test now on a silent UDP monitor, showing try 2 of 3, listening for a refusal, up to 38 seconds left",
      caption:
        "Test now on a silent UDP check says which try is running and how long is left.",
    },
  ],
  "3.22.0": [
    {
      src: "/screens/wireguard-up.webp",
      alt: "A WireGuard monitor of the UDP port kind, operational, with what silent mode saw",
      caption: "A WireGuard server watched as a UDP port, in silent mode.",
    },
  ],
  "3.20.0": [
    {
      src: "/screens/homelab-hosts.webp",
      alt: "Dashboard with a DNS server, a lab server checked by ping and SSH checked as a TCP port, all operational",
      caption: "Ping and TCP port monitors next to a UDP one, on a homelab.",
    },
  ],
  "3.18.0": [
    {
      src: "/screens/detail-health.webp",
      alt: "A Health endpoint monitor that is down, naming the failing part, Payments, with a Mark expected button",
      caption:
        "A Health endpoint monitor names the part that failed. Mark expected came in 3.19.0.",
    },
  ],
  "3.14.0": [
    {
      src: "/screens/detail-github.webp",
      alt: "A GitHub repository monitor showing 4 downloads on the newest release and 1677 in total",
      caption:
        "Downloads on the newest release and the running total, on a repository monitor.",
    },
  ],
  "3.11.0": [
    {
      src: "/screens/detail-prometheus.webp",
      alt: "A Prometheus monitor reading a query, operational, with its response time chart and configuration",
      caption: "A Prometheus monitor reading one query from the server.",
    },
  ],
};

function Phones({ shots }: { shots: Shot[] }) {
  return (
    <div className="mt-8 flex flex-wrap gap-8">
      {shots.map((s) => (
        <figure key={s.src} className="w-full max-w-[15rem]">
          <PhoneFrame className="shadow-elev-2">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={s.src}
              alt={s.alt}
              width={1080}
              height={2400}
              loading="lazy"
              className="block w-full"
            />
          </PhoneFrame>
          <figcaption className="text-muted-foreground mt-4 text-sm leading-relaxed">
            {s.caption}
          </figcaption>
        </figure>
      ))}
    </div>
  );
}

const button =
  "inline-flex h-11 items-center gap-2 rounded-full px-5 text-sm font-semibold transition-colors duration-200";

/*
  One column, one left edge. The version, the date and the buttons sit on the
  same 48rem measure as the notes, so nothing lines up against a different edge
  than the text under it, on a phone or a desktop.
*/
function Entry({ r, latest }: { r: Release; latest: boolean }) {
  return (
    <article
      id={id(r)}
      className={`border-border max-w-3xl scroll-mt-24 border-t py-12 sm:py-16 ${latest ? "border-t-0 pt-0 sm:pt-0" : ""}`}
    >
      <header className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <h2 className="display text-[clamp(2.5rem,6vw,3.5rem)]">
          <a
            href={`#${id(r)}`}
            className="hover:text-brand transition-colors duration-200"
          >
            <span className="sr-only">{r.name} </span>
            {r.version}
          </a>
        </h2>
        {latest && (
          <span className="border-brand/40 text-brand rounded-full border px-2.5 py-1 font-mono text-[0.7rem] leading-none tracking-[0.12em] uppercase">
            Latest
          </span>
        )}
        <p className="text-muted-foreground ml-auto font-mono text-xs tabular-nums">
          <time dateTime={r.date}>{date(r.date)}</time>
          {r.name === "Pulse" && <> · as Pulse</>}
        </p>
      </header>

      {r.title && (
        <p className="text-foreground mt-6 text-xl leading-snug font-semibold text-balance">
          {r.title}
        </p>
      )}

      <div className={r.title ? "doc notes mt-4" : "doc notes mt-6"}>
        <Markdown
          remarkPlugins={[remarkGfm]}
          components={Object.fromEntries(
            Object.entries(shift).map(([from, To]) => [
              from,
              ({ children }: { children?: React.ReactNode }) => (
                <To>{children}</To>
              ),
            ]),
          )}
        >
          {r.body}
        </Markdown>
      </div>

      {SHOTS[r.version] && <Phones shots={SHOTS[r.version]} />}

      <div className="mt-8 flex flex-wrap gap-2">
        {latest ? (
          <a
            href={DOWNLOAD_PATH}
            className={`${button} bg-primary hover:bg-primary/90 text-white`}
          >
            <Download className="size-4" aria-hidden />
            Download APK
            <span className="font-mono text-xs tabular-nums opacity-70">
              {mb(RELEASE.apkBytes)}
            </span>
          </a>
        ) : (
          r.apk && (
            <a
              href={r.apk.url}
              className={`${button} border-border hover:bg-muted border`}
            >
              <Download className="size-4" aria-hidden />
              APK
              <span className="text-muted-foreground font-mono text-xs tabular-nums">
                {mb(r.apk.bytes)}
              </span>
            </a>
          )
        )}
        <a
          href={r.url}
          className={`${button} border-border hover:bg-muted border`}
        >
          Release on GitHub
          <ArrowUpRight className="text-muted-foreground size-4" aria-hidden />
        </a>
      </div>
    </article>
  );
}

function Pulse() {
  return (
    <aside
      className="border-border mb-12 max-w-3xl rounded-2xl border p-6 sm:p-8"
      aria-labelledby="pulse"
    >
      <p className="eyebrow">Before {firstNightbell.version}</p>
      <h2 id="pulse" className="display mt-3 text-[clamp(2rem,4.5vw,3rem)]">
        Nightbell was called Pulse.
      </h2>
      <p className="text-muted-foreground mt-4 max-w-2xl leading-relaxed">
        Same app, same person. Each of 1.x, 2.x and 3.0.0 used a different
        application id, so none of them updates in place over another: moving
        across means exporting your monitors and importing them again. The{" "}
        <a
          href={`#${id(firstNightbell)}`}
          className="text-foreground underline underline-offset-4"
        >
          {firstNightbell.version} notes
        </a>{" "}
        say how.
      </p>
    </aside>
  );
}

export default function Changelog() {
  const switchIndex = releases.indexOf(firstNightbell);
  return (
    <>
      <Nav />
      <main id="main">
        <header className="container-page relative isolate grid items-center gap-12 pt-32 pb-16 sm:pt-40 lg:grid-cols-[minmax(0,1fr)_15rem] lg:gap-16">
          <div>
            <p className="eyebrow">Changelog · {releases.length} releases</p>
            <h1 className="display mt-5 text-[clamp(3.4rem,9vw,8rem)]">
              Every release,{" "}
              <span className="text-muted-foreground">in its own words.</span>
            </h1>
            <div className="text-muted-foreground mt-6 max-w-2xl text-lg leading-relaxed">
              <p>
                {releases.length} versions in {span} days, from {first.version}{" "}
                on {date(first.date, "long")} to{" "}
                <a
                  href={`#${id(newest)}`}
                  className="text-foreground underline underline-offset-4"
                >
                  {newest.version}
                </a>{" "}
                on {date(newest.date, "long")}. These are the notes each release
                shipped with, the same ones on GitHub. The newest is the one{" "}
                <Link
                  href="/#install"
                  className="text-foreground underline underline-offset-4"
                >
                  the site hands out
                </Link>
                .
              </p>
            </div>
          </div>
          <figure className="mx-auto w-full max-w-[13rem] lg:max-w-none">
            <PhoneFrame className="shadow-elev-2">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src="/screens/update-banner.webp"
                alt="Nightbell telling a 3.23.0 install that 3.24.0 is available, 2.9 MB, with What's new and Install buttons"
                width={1080}
                height={2400}
                fetchPriority="high"
                className="block w-full"
              />
            </PhoneFrame>
            <figcaption className="text-muted-foreground mt-4 text-center text-sm leading-relaxed lg:text-left">
              What a 3.23.0 install showed the day 3.24.0 came out. Install
              fetches the APK, What&apos;s new opens these same notes on GitHub.
            </figcaption>
          </figure>
        </header>

        <div className="container-page grid gap-12 pt-8 pb-28 lg:grid-cols-[11rem_minmax(0,1fr)] lg:gap-16">
          <Index />
          <div className="min-w-0">
            <Jump />
            {releases.map((r, i) => (
              <Fragment key={r.tag}>
                {i === switchIndex + 1 && <Pulse />}
                <Entry r={r} latest={r.tag === RELEASE.tag} />
              </Fragment>
            ))}
          </div>
        </div>
      </main>
      <Footer />
    </>
  );
}
