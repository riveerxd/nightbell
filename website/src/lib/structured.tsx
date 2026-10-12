// The site-wide structured data, ported from website/src/layouts/Base.astro so
// the entities and their @ids are what search engines already have on record.
import media from "@/lib/media.json"
import { MEDIA, RELEASE, REPO, SEO, SITE_URL, APK_MB } from "@/lib/site"

const abs = (p: string) => new URL(p, SITE_URL).href

export const softwareApplication = {
  "@context": "https://schema.org",
  "@type": "SoftwareApplication",
  "@id": abs("/#app"),
  name: "Nightbell",
  applicationCategory: "DeveloperApplication",
  applicationSubCategory: "Uptime monitoring",
  operatingSystem: `Android ${RELEASE.minAndroid}+`,
  softwareVersion: RELEASE.version,
  fileSize: `${APK_MB} MB`,
  installUrl: REPO.latestRelease,
  downloadUrl: RELEASE.apkUrl,
  license: "https://www.apache.org/licenses/LICENSE-2.0",
  isAccessibleForFree: true,
  url: SITE_URL,
  description: SEO.description,
  softwareHelp: REPO.reference,
  codeRepository: REPO.url,
  programmingLanguage: "Kotlin",
  offers: { "@type": "Offer", price: "0", priceCurrency: "USD" },
  featureList: [
    "HTTP status checks with exact, any 2xx, range and any-response matching",
    "Request and response monitors with custom method, headers, body and body assertions",
    "Page-element monitors that resolve DOM nodes in a real WebView",
    "Health endpoint monitors that read a JSON health report and judge each part of it",
    "Prometheus monitors that read a metrics endpoint, a PromQL query or an Alertmanager",
    "GitHub repository monitors for stars, issues, pull requests, comments, releases and downloads",
    "Webhooks to Slack, Teams, Discord, Google Chat, ntfy, Gotify, Telegram or any URL",
    "Full-screen urgent alert with a looping alarm until acknowledged",
    "Latency budgets with a separate degraded alert track",
    "Home-screen widget, worst monitor first",
    "On-device checks with no account and no server",
  ],
}

export const website = {
  "@context": "https://schema.org",
  "@type": "WebSite",
  "@id": abs("/#website"),
  url: SITE_URL,
  name: "Nightbell",
  description: SEO.description,
  inLanguage: "en",
  publisher: { "@id": abs("/#author") },
  about: { "@id": abs("/#app") },
}

export const person = {
  "@context": "https://schema.org",
  "@type": "Person",
  "@id": abs("/#author"),
  name: "river",
  url: `https://github.com/${REPO.owner}`,
}

export const promoVideo = {
  "@context": "https://schema.org",
  "@type": "VideoObject",
  "@id": abs("/#promo"),
  name: MEDIA.promoName,
  description: MEDIA.promoDescription,
  thumbnailUrl: [abs(MEDIA.promoPoster)],
  contentUrl: abs(media.promo.file),
  uploadDate: MEDIA.promoUploaded,
  duration: `PT${Math.round(media.promo.seconds)}S`,
  width: media.promo.width,
  height: media.promo.height,
  inLanguage: "en",
  isFamilyFriendly: true,
  license: "https://www.apache.org/licenses/LICENSE-2.0",
  creator: { "@id": abs("/#author") },
  about: { "@id": abs("/#app") },
}

export const docsArticle = ({
  path,
  headline,
  description,
  sections,
}: {
  path: string
  headline: string
  description: string
  sections: { text: string; id: string }[]
}) => ({
  "@context": "https://schema.org",
  "@type": "TechArticle",
  "@id": abs(`${path}#article`),
  headline,
  description,
  inLanguage: "en",
  dependencies: `Android ${RELEASE.minAndroid} and later`,
  version: RELEASE.version,
  author: { "@id": abs("/#author") },
  publisher: { "@id": abs("/#author") },
  about: { "@id": abs("/#app") },
  isAccessibleForFree: true,
  license: "https://www.apache.org/licenses/LICENSE-2.0",
  hasPart: sections.map((s) => ({ "@type": "WebPageElement", name: s.text, url: abs(`${path}#${s.id}`) })),
})

export function JsonLd({ data }: { data: object }) {
  return <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify(data) }} />
}
