import { SEO } from "@/lib/site"

export const dynamic = "force-static"

export function GET() {
  return Response.json(
    {
      name: "Nightbell",
      short_name: "Nightbell",
      description: SEO.description,
      start_url: "/",
      scope: "/",
      display: "browser",
      background_color: "#050608",
      theme_color: "#050608",
      icons: [
        { src: "/icon-192.png", sizes: "192x192", type: "image/png", purpose: "any" },
        { src: "/icon-512.png", sizes: "512x512", type: "image/png", purpose: "any" },
        { src: "/favicon.svg", sizes: "any", type: "image/svg+xml" },
      ],
    },
    { headers: { "Content-Type": "application/manifest+json; charset=utf-8" } }
  )
}
