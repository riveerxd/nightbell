// @ts-check
import { defineConfig } from 'astro/config';
import { SITE_URL, IS_PLACEHOLDER_DOMAIN } from './site.config.mjs';

if (IS_PLACEHOLDER_DOMAIN) {
  console.warn(
    [
      '',
      '  ! SITE_URL is still the placeholder (' + SITE_URL + ').',
      '    Canonical, Open Graph and JSON-LD URLs are being built from a host',
      '    that does not exist, so robots.txt is serving Disallow: / to stop an',
      '    accidental deploy being indexed under it.',
      '    Set the real origin in site.config.mjs and both go away.',
      '',
    ].join('\n'),
  );
}

export default defineConfig({
  site: SITE_URL,
  output: 'static',
  trailingSlash: 'ignore',
  build: {
    // One page, one stylesheet, no reason to spend a round trip on it. Astro
    // will still split anything large enough to be worth caching separately.
    inlineStylesheets: 'always',
    format: 'file',
  },
  image: {
    // Screenshots are 2320 px wide PNGs off a real device. Astro re-encodes and
    // resizes them at build time; nothing here is served at its source size.
    responsiveStyles: true,
  },
  devToolbar: { enabled: false },
  compressHTML: true,
});
