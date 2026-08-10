import type { APIRoute } from 'astro';
import { SITE_URL } from '../../site.config.mjs';

/**
 * The sitemap, hand-rolled.
 *
 * @astrojs/sitemap would do this too, and would be the right call the moment
 * there is a second page. For one URL it is a dependency, a build step and a
 * config block to produce eight lines of XML, so the eight lines are written
 * out instead. Add pages here when there are pages to add.
 *
 * `lastmod` is left off on purpose. A date stamped at build time says "this
 * page changed" every time anything in the repository is rebuilt, which is
 * exactly the signal lastmod is supposed to carry and exactly the one it would
 * then stop carrying. Set it from real edit dates or not at all.
 */
const PAGES = ['/'];

/**
 * The same normalisation `Base.astro` applies to the canonical link.
 *
 * It has to be the same or the two disagree about the root: `new URL('/', ...)`
 * produces a trailing slash and the canonical strips it, so the sitemap was
 * submitting `https://nightbell.app/` for a page whose canonical said
 * `https://nightbell.app`. Search engines reconcile that themselves, but a
 * sitemap whose job is to state the canonical URL should not need reconciling.
 */
const canonical = (path: string) => new URL(path, SITE_URL).href.replace(/\/$/, '') || SITE_URL;

export const GET: APIRoute = () => {
  const urls = PAGES.map((p) => {
    const loc = canonical(p);
    return `  <url>\n    <loc>${loc}</loc>\n  </url>`;
  }).join('\n');

  const xml = `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
${urls}
</urlset>
`;

  return new Response(xml, {
    headers: { 'Content-Type': 'application/xml; charset=utf-8' },
  });
};
