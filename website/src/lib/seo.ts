import type { Metadata } from "next"

import { MEDIA, SEO, SITE_URL } from "@/lib/site"

/*
  Every page's head, built the way the old site's Base layout built it. Next
  does not merge openGraph between a page and the root layout: a page that sets
  any of it replaces all of it, which is how three pages lost their share image.
  So each page states the whole set here, with the old site's exact values.
*/
const image = {
  url: SEO.ogImage,
  // Next resolves url against metadataBase but prints secureUrl as given, and a
  // relative og:image:secure_url is one scrapers cannot fetch.
  secureUrl: SITE_URL + SEO.ogImage,
  type: "image/jpeg",
  width: SEO.ogImageWidth,
  height: SEO.ogImageHeight,
  alt: SEO.ogImageAlt,
}

export function pageMeta({
  path,
  title,
  description,
  video = false,
}: {
  path: string
  title: string
  description: string
  video?: boolean
}): Metadata {
  return {
    title: { absolute: title },
    description,
    alternates: { canonical: path },
    openGraph: {
      type: "website",
      siteName: "Nightbell",
      title,
      description,
      url: path,
      locale: "en_GB",
      images: [image],
      ...(video && {
        videos: [{ url: MEDIA.promoFile, secureUrl: MEDIA.promoFile, type: "video/mp4", width: 1920, height: 1080 }],
      }),
    },
    twitter: {
      card: "summary_large_image",
      title,
      description,
      images: [{ url: SEO.ogImage, alt: SEO.ogImageAlt }],
    },
  }
}
