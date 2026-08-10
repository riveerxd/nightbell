import type { APIRoute } from 'astro';
import { SEO } from '../../site.config.mjs';

/**
 * A web app manifest, for the modest thing it is actually worth here.
 *
 * This is a one-page site for an Android app, not a web app, so there is no
 * service worker, no offline mode and no install prompt worth chasing. What the
 * manifest does buy is a proper name and icon when somebody adds the page to a
 * home screen from Chrome on the phone they are about to sideload onto, which is
 * a real path for this audience.
 *
 * `maskable` is not claimed, and the earlier note here saying it was justified by
 * a full-bleed plate was measured and found wrong. The plate reaches the edges
 * along each side, but its corners are rounded and therefore transparent:
 * `magick icon-192.png -format '%[pixel:p{0,0}]'` is srgba(0,0,0,0). A maskable
 * icon has to be opaque across the whole square, because the launcher discards
 * the icon's own silhouette and applies its shape mask, so on a launcher using a
 * square-ish mask those corners are holes rather than plate. Declaring `any`
 * alone means Android composites the icon onto its standard backdrop instead,
 * which is the correct fallback and looks like every other non-maskable icon on
 * the device. Making it genuinely maskable is a new asset, not a flag: a square
 * with no corner rounding and the mark pulled inside the central 80% safe zone.
 */
export const GET: APIRoute = () =>
  new Response(
    JSON.stringify(
      {
        name: 'Nightbell',
        short_name: 'Nightbell',
        description: SEO.description,
        start_url: '/',
        scope: '/',
        display: 'browser',
        background_color: '#000000',
        theme_color: SEO.themeColor,
        icons: [
          { src: '/icon-192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
          { src: '/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
          { src: '/favicon.svg', sizes: 'any', type: 'image/svg+xml' },
        ],
      },
      null,
      2,
    ),
    { headers: { 'Content-Type': 'application/manifest+json; charset=utf-8' } },
  );
