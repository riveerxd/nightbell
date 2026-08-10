import type { APIRoute } from 'astro';
import { SITE_URL, IS_PLACEHOLDER_DOMAIN } from '../../site.config.mjs';

/**
 * robots.txt, generated so it cannot disagree with the sitemap it points at.
 *
 * While SITE_URL is still the placeholder, this serves `Disallow: /`. That is
 * deliberate and it is the safest possible default: a site put behind Nginx
 * before the domain is settled would otherwise be crawled with canonical tags
 * pointing at a host that does not exist, and a wrong canonical is considerably
 * harder to undo than a delayed one. Set the real origin in site.config.mjs and
 * this flips to a normal allow-all on the next build. No flag to remember.
 */
export const GET: APIRoute = () => {
  const body = IS_PLACEHOLDER_DOMAIN
    ? [
        '# Nightbell is not published yet: site.config.mjs still holds the placeholder',
        '# origin, so every canonical URL on this build points at a host that does',
        '# not exist. Indexing that would be worse than not indexing it.',
        '#',
        '# Set SITE_URL to the real origin and rebuild. This file becomes an',
        '# ordinary allow-all with a Sitemap line, automatically.',
        'User-agent: *',
        'Disallow: /',
        '',
      ].join('\n')
    : [
        'User-agent: *',
        'Allow: /',
        '',
        `Sitemap: ${new URL('/sitemap.xml', SITE_URL).href}`,
        '',
      ].join('\n');

  return new Response(body, {
    headers: { 'Content-Type': 'text/plain; charset=utf-8' },
  });
};
