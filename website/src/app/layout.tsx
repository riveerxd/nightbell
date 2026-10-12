import type { Metadata, Viewport } from "next"
import { JetBrains_Mono } from "next/font/google"
import localFont from "next/font/local"

import { pageMeta } from "@/lib/seo"
import { SEO, SITE_URL } from "@/lib/site"
import { JsonLd, person, softwareApplication, website } from "@/lib/structured"
import { SmoothScroll } from "@/components/site/smooth-scroll"
import "lenis/dist/lenis.css"
import "./globals.css"

// Archivo, self-hosted and cut down: Google's latin file carries weights 100 to
// 900, widths 62 to 125 per cent and about 300 glyphs, at 90 KB. The site sets
// 400 to 800, never wider than 100 per cent, and fewer than 140 glyphs, so the
// file keeps exactly that and weighs 34 KB. Rebuilt from that latin file with
// fontTools' instancer and subsetter, over every character in src/ and content/.
const sans = localFont({
  src: "../fonts/archivo.woff2",
  variable: "--font-archivo",
  weight: "400 800",
  display: "swap",
  declarations: [{ prop: "font-stretch", value: "62% 100%" }],
  // Next's generated stand-in names only Arial, which Android does not have,
  // and scales it to 102.8%; measured on real copy Archivo sets 0.988 times
  // Arial's width. The face in globals.css fixes both, so body text wraps the
  // same before and after the font arrives.
  adjustFontFallback: false,
  fallback: ["Body Fallback", "sans-serif"],
})
// Not preloaded: it only sets small labels, and as a preload it was the
// heaviest request racing the hero image and the headline face for the first
// paint. The labels swap in a moment later against a metric matched fallback.
const mono = JetBrains_Mono({ variable: "--font-jetbrains", subsets: ["latin"], preload: false })

export const metadata: Metadata = {
  metadataBase: new URL(SITE_URL),
  ...pageMeta({ path: "/", title: SEO.title, description: SEO.description, video: true }),
  title: { default: SEO.title, template: "%s · Nightbell" },
  applicationName: "Nightbell",
  icons: {
    icon: [
      { url: "/favicon.svg", type: "image/svg+xml" },
      { url: "/favicon.ico", sizes: "any" },
    ],
    apple: "/apple-touch-icon.png",
  },
  manifest: "/site.webmanifest",
}

export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: dark)", color: "#0b0d12" },
    { media: "(prefers-color-scheme: light)", color: "#eceef2" },
  ],
  colorScheme: "dark light",
}

/*
  Runs before first paint so a light-mode visitor never sees a flash of the
  dark page. Dark is the default; only an explicit choice stored by the toggle
  turns it off.
*/
/*
  Whether the hero phone will be 3D, decided before the first paint so the
  picture phone is never drawn and then swapped. No WebGL 2, reduced motion, or
  WebGL drawn in software (no GPU: the frame rate collapses and the page stalls
  for seconds) all get the picture instead. force3d is for the e2e suite, whose
  headless browser renders in software but still has to exercise the 3D path.
*/
const GL3D = `function gl3d(){try{if(matchMedia("(prefers-reduced-motion: reduce)").matches)return false;var g=document.createElement("canvas").getContext("webgl2");if(!g)return false;var f=false;try{f=localStorage.getItem("force3d")==="1"}catch(e){}if(f)return true;var d=g.getExtension("WEBGL_debug_renderer_info");var r=d?String(g.getParameter(d.UNMASKED_RENDERER_WEBGL)):"";return !/swiftshader|llvmpipe|softpipe|software/i.test(r)}catch(e){return false}}`
const themeScript = `try{var t=localStorage.getItem("theme");if(t!=="light")document.documentElement.classList.add("dark")}catch(e){document.documentElement.classList.add("dark")}`+GL3D+`if(gl3d())document.documentElement.classList.add("gl3d")`

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html
      lang="en"
      suppressHydrationWarning
      className={`${sans.variable} ${mono.variable} antialiased`}
      // headlines fall back to a condensed stand-in rather than the body's; see .display
      style={{ "--font-display": `${sans.style.fontFamily.split(",")[0]}, "Display Fallback", sans-serif` } as React.CSSProperties}
    >
      <head>
        <script dangerouslySetInnerHTML={{ __html: themeScript }} />
        <JsonLd data={softwareApplication} />
        <JsonLd data={website} />
        <JsonLd data={person} />
      </head>
      <body className="min-h-dvh overflow-x-clip">
        <SmoothScroll />
        <a
          href="#main"
          className="bg-primary text-primary-foreground sr-only z-[100] rounded-full px-4 py-2 focus:not-sr-only focus:fixed focus:top-3 focus:left-3"
        >
          Skip to content
        </a>
        {children}
      </body>
    </html>
  )
}
