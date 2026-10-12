"use client"

import { useRef, useState } from "react"
import { ArrowUpRight, Check, Copy, Download, ShieldCheck } from "lucide-react"

import { BlurFade } from "@/components/ui/blur-fade"
import { BorderBeam } from "@/components/ui/border-beam"
import { APK_MB, CHANNELS, DOWNLOAD_PATH, RELEASE, REPO } from "@/lib/site"
import { cn } from "@/lib/utils"

import { Deep, DeepGroup } from "./deep"
import { SectionHeading, Serif } from "./section"

function copyByCommand(text: string) {
  const pen = document.createElement("textarea")
  pen.value = text
  pen.setAttribute("readonly", "")
  pen.style.position = "fixed"
  pen.style.opacity = "0"
  document.body.append(pen)
  pen.select()
  let done = false
  try {
    done = document.execCommand("copy")
  } catch {
    done = false
  }
  pen.remove()
  return done
}

/*
  A 64 character digest exists to be compared against another one, so each
  gets its own button. A refused clipboard says so instead of claiming success.
*/
export function CopyButton({ value, label = "Copy" }: { value: string; label?: string }) {
  const [state, setState] = useState<"idle" | "done" | "fail">("idle")
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined)
  return (
    <button
      type="button"
      onClick={async () => {
        let ok = true
        try {
          await navigator.clipboard.writeText(value)
        } catch {
          ok = copyByCommand(value)
        }
        setState(ok ? "done" : "fail")
        clearTimeout(timer.current)
        timer.current = setTimeout(() => setState("idle"), 2000)
      }}
      className="bg-surface-2 shadow-elev-1 hover:bg-muted inline-flex h-11 shrink-0 items-center gap-1.5 rounded-full px-4 text-xs font-medium transition-colors active:scale-[0.97] lg:h-8 lg:px-3"
    >
      {state === "done" ? <Check className="text-up size-3.5" aria-hidden /> : <Copy className="size-3.5" aria-hidden />}
      <span aria-live="polite">{state === "done" ? "Copied" : state === "fail" ? "Copy it by hand" : label}</span>
    </button>
  )
}

// This site first: it carries a release the day it ships, and it is where the
// app itself looks for the next one. F-Droid builds on its own schedule and was
// two releases behind when this list was reordered.
const CHANNEL_ROWS = [
  {
    c: { live: true, name: "nightbell.app", url: DOWNLOAD_PATH },
    note: "The newest release the day it ships. The app checks here for the next one and installs it when you tap, with Android asking first.",
    state: "Recommended",
    go: "Download",
  },
  {
    c: CHANNELS.fdroid,
    note: "Serves my APK rather than a rebuild of it, on F-Droid's own build schedule, so it can be several releases behind this page.",
    state: "Updates later",
    go: "Open",
  },
  { c: CHANNELS.github, note: "The APK, from the tag it was built at.", state: "Available now", go: "Open" },
  { c: CHANNELS.obtainium, note: "Paste the repository URL for updates without a store.", state: "Works today", go: "Copy the URL" },
  {
    c: CHANNELS.izzyondroid,
    note: "Not submitted. It was meant to be the fast route and F-Droid proper landed first.",
    state: "Not yet",
    go: null,
  },
]

export function Install() {
  return (
    <section id="install" className="relative py-20 sm:py-28">
      <div className="container-page">
        <SectionHeading
          title={
            <>
              One APK, {APK_MB} MB. <Serif>Signed with a key you can check.</Serif>
            </>
          }
        />

        <BlurFade inView>
          <div className="bg-surface shadow-elev-3 relative mt-14 overflow-hidden rounded-[1.75rem]">
            <div className="grid gap-10 p-6 sm:p-10 lg:grid-cols-[1fr_auto] lg:items-end">
              <div>
                <p className="eyebrow">Latest release</p>
                <p className="dotmatrix mt-3 text-[clamp(4rem,10vw,8.5rem)] leading-[0.85]">{RELEASE.version}</p>
              </div>
              <div className="flex flex-wrap gap-3">
                <a
                  href={DOWNLOAD_PATH}
                  className="bg-primary hover:bg-primary/90 shadow-elev-2 inline-flex h-12 items-center gap-2.5 rounded-full px-6 text-[15px] font-semibold text-white transition-colors"
                >
                  <Download className="size-[18px]" aria-hidden />
                  Download APK
                  <span className="font-mono text-xs opacity-70">{APK_MB} MB</span>
                </a>
                <a
                  href={REPO.latestRelease}
                  target="_blank"
                  rel="noopener"
                  className="bg-surface-2 shadow-elev-1 hover:bg-muted inline-flex h-12 items-center gap-2 rounded-full px-5 text-[15px] font-semibold transition-colors"
                >
                  Release notes
                  <ArrowUpRight className="size-4" aria-hidden />
                </a>
              </div>
            </div>
            <dl className="border-border grid grid-cols-2 border-t lg:grid-cols-4">
              {[
                ["File", RELEASE.apkName],
                ["Needs", `Android ${RELEASE.minAndroid}+`],
                ["Licence", "Apache 2.0"],
                ["Store listing", "F-Droid"],
              ].map(([k, v], i) => (
                <div
                  key={k}
                  className={cn(
                    "border-border px-6 py-5 sm:px-10",
                    i % 2 === 1 && "border-l",
                    i >= 2 && "border-t lg:border-t-0",
                    i === 2 && "lg:border-l"
                  )}
                >
                  <dt className="eyebrow text-[11px]">{k}</dt>
                  <dd className="mt-1.5 font-mono text-sm break-all">
                    {v}
                  </dd>
                </div>
              ))}
            </dl>
            <BorderBeam size={260} duration={10} colorFrom="#6d5efc" colorTo="#2f6bff" borderWidth={1.5} />
          </div>
        </BlurFade>

        <div className="mt-6 grid gap-6 lg:grid-cols-[1.25fr_1fr]">
          <BlurFade inView>
            <ul className="bg-surface shadow-elev-2 divide-border divide-y overflow-hidden rounded-[1.75rem]">
              {CHANNEL_ROWS.map(({ c, note, state, go }) => (
                <li key={c.name} className="flex flex-wrap items-center gap-x-4 gap-y-2 px-5 py-5 sm:px-6">
                  <span className={cn("size-2 shrink-0 rounded-full", c.live ? "bg-up-fill" : "bg-muted-foreground/40")} aria-hidden />
                  <span className="min-w-0 flex-1">
                    <span className="flex flex-wrap items-baseline gap-x-3">
                      <span className="font-semibold">{c.name}</span>
                      <span className={cn("font-mono text-[11px] tracking-[0.1em] uppercase", c.live ? "text-up" : "text-muted-foreground")}>
                        {state}
                      </span>
                    </span>
                    <span className="text-muted-foreground mt-0.5 block text-sm">{note}</span>
                  </span>
                  {go && c.url &&
                    (go === "Copy the URL" ? (
                      <CopyButton value={c.url} label="Copy the URL" />
                    ) : c.url === DOWNLOAD_PATH ? (
                      <a
                        href={c.url}
                        className="bg-primary hover:bg-primary/90 inline-flex h-11 items-center gap-1.5 rounded-full px-4 text-xs font-semibold text-white transition-colors lg:h-8 lg:px-3"
                      >
                        <Download className="size-3.5" aria-hidden />
                        {go}
                      </a>
                    ) : (
                      <a
                        href={c.url}
                        target="_blank"
                        rel="noopener"
                        className="bg-surface-2 shadow-elev-1 hover:bg-muted inline-flex h-11 items-center gap-1 rounded-full px-4 text-xs font-medium transition-colors lg:h-8 lg:px-3"
                      >
                        {go}
                        <ArrowUpRight className="size-3.5" aria-hidden />
                      </a>
                    ))}
                </li>
              ))}
            </ul>
          </BlurFade>

          <BlurFade inView delay={0.08} className="flex flex-col gap-6">
            <div className="bg-surface shadow-elev-2 flex h-full flex-col justify-between gap-6 rounded-[1.75rem] p-6 sm:p-8">
              <p className="text-muted-foreground text-sm">From a computer, once the APK above is downloaded:</p>
              <pre className="font-mono text-[15px] leading-relaxed break-all whitespace-pre-wrap">
                <code>
                  <span className="text-muted-foreground select-none">$ </span>adb install -r {RELEASE.apkName}
                </code>
              </pre>
              <div className="flex justify-end">
                <CopyButton value={`adb install -r ${RELEASE.apkName}`} />
              </div>
            </div>
          </BlurFade>
        </div>

        <div className="mt-6 grid gap-6 lg:grid-cols-2">
          <BlurFade inView>
            <p className="bg-surface shadow-elev-1 flex h-full gap-3 rounded-[1.75rem] p-6 leading-relaxed">
              <ShieldCheck className="text-up mt-0.5 size-5 shrink-0" aria-hidden />
              F-Droid rebuilds Nightbell from this source and publishes it only when their output
              matches the APK above byte for byte, so both downloads carry the same signature and
              either one updates the other.
            </p>
          </BlurFade>
          <BlurFade inView delay={0.06}>
            <p className="bg-surface shadow-elev-1 text-muted-foreground flex h-full gap-3 rounded-[1.75rem] p-6 leading-relaxed">
              <ShieldCheck className="mt-0.5 size-5 shrink-0" aria-hidden />
              F-Droid is the only store Nightbell is listed in. There is no Play listing and no other
              store anywhere. If you find one, it is not mine.
            </p>
          </BlurFade>
        </div>

        <BlurFade inView>
          <div className="bg-surface shadow-elev-2 mt-6 rounded-[1.75rem] p-6 sm:p-8">
            <h3 className="text-lg font-semibold">Check what you downloaded</h3>
            <dl className="mt-5 space-y-5">
              {[
                ["APK SHA-256", RELEASE.apkSha256, true],
                ["Signing certificate SHA-256", RELEASE.signingCertSha256, true],
                ["Certificate subject", RELEASE.signingCertDn, false],
              ].map(([k, v, copy]) => (
                <div key={k as string} className="grid gap-2 md:grid-cols-[14rem_1fr] md:items-center md:gap-6">
                  <dt className="text-muted-foreground text-sm">{k as string}</dt>
                  {/* the button lives inside the dd: a dl may hold only dt and dd */}
                  <dd className="flex flex-col items-start gap-2 md:flex-row md:items-center md:justify-between md:gap-6">
                    <span className="font-mono text-[13px] break-all">{v as string}</span>
                    {copy && <CopyButton value={v as string} />}
                  </dd>
                </div>
              ))}
            </dl>
            <p className="text-muted-foreground border-border mt-6 border-t pt-5 text-sm leading-relaxed">
              Signed with my key, not Google&apos;s, so Play Protect asks once. The certificate fingerprint
              is what says the next version came from the same person.
            </p>
          </div>
        </BlurFade>

        <DeepGroup className="mt-12">
          <Deep title="Upgrading, and building it yourself">
            <p>
              Android refuses an update signed by a different key, which is what makes the
              certificate fingerprint above worth checking before you trust an APK you did not get from
              the link on this page.
            </p>
            <p>
              Anything from 3.0.1 onward updates in place and keeps your monitors. 3.0.0 and Pulse
              2.x cannot be updated over, so the route across is the JSON export in Settings; the
              FAQ has the steps.
              Building it yourself needs JDK 17 and an Android SDK with API 36; the commands are in
              the{" "}
              <a className="text-foreground underline underline-offset-4" href={REPO.readme} target="_blank" rel="noopener">
                README
              </a>
              .
            </p>
          </Deep>
        </DeepGroup>
      </div>
    </section>
  )
}
