import type { Metadata } from "next"

import { RELEASE } from "@/lib/site"

/*
  On the live server Nginx answers /download with a 302 to the release asset
  before this file is ever read. This page only exists so the static build and
  any host without that rule still hand over the APK instead of a 404.
*/
export const metadata: Metadata = {
  title: { absolute: "Downloading Nightbell" },
  // The layout's canonical points at the homepage, and a noindex page that
  // names another page as canonical sends search engines two opposite signals.
  alternates: { canonical: null },
  robots: { index: false, follow: true },
  other: { refresh: `0;url=${RELEASE.apkUrl}` },
}

export default function Download() {
  return (
    <main id="main" className="container-page flex min-h-dvh flex-col items-start justify-center gap-4">
      <meta httpEquiv="refresh" content={`0;url=${RELEASE.apkUrl}`} />
      <p className="eyebrow">Nightbell {RELEASE.version}</p>
      <h1 className="display text-6xl">Your download is starting.</h1>
      <p className="text-muted-foreground">
        If it does not,{" "}
        <a className="text-foreground underline underline-offset-4" href={RELEASE.apkUrl}>
          download {RELEASE.apkName} directly
        </a>
        .
      </p>
    </main>
  )
}
