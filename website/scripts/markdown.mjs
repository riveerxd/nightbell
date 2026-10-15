// Writes a Markdown copy beside every page in out/, and /llms.txt that lists them.
//
// The copies are made from the exported HTML, not kept by hand. A second source
// for the same words is a second place to forget at release time, and the FAQ
// would then answer one way to a browser and another way to a crawler.
//
// Only <main> is converted. The header, footer and theme switcher are chrome, and
// anything marked aria-hidden is decoration a screen reader already skips, which
// is the same reading a language model should get.
//
// nginx serves these as text/markdown, and also answers a request for /faq with
// faq.md when the client's Accept header asks for text/markdown. See the
// `nightbell_md` map in deploy/nginx/nightbell.app.conf.
import { readFileSync, readdirSync, writeFileSync } from "node:fs"
import { join } from "node:path"
import { fileURLToPath } from "node:url"

import { fromHtml } from "hast-util-from-html"
import { toMdast } from "hast-util-to-mdast"
import { gfmToMarkdown } from "mdast-util-gfm"
import { toMarkdown } from "mdast-util-to-markdown"

import { CHANNELS, RELEASE, REPO, SEO, SITE_URL } from "../src/lib/site.ts"

const out = fileURLToPath(new URL("../out/", import.meta.url))

// Not pages anybody reads: /download is a counted 302 in production and the
// other two are the error page.
const SKIP = new Set(["404.html", "_not-found.html", "download.html"])

const BLOCK = new Set(["div", "p", "section"])
const IMG = new Set(["img"])
const INLINE = new Set(["span", "a", "time", "div", "code"])
const DROP_TAGS = new Set(["script", "style", "noscript", "template", "svg", "canvas", "video", "audio", "button", "nav", "form", "iframe"])

const KEEP_EMPTY = new Set(["img", "br", "hr", "td", "th", "tr"])

function text(node) {
  if (node.type === "text") return node.value
  return (node.children ?? []).map(text).join(" ")
}

function hasTag(node, tags) {
  return (node.children ?? []).some((c) => c.type === "element" && (tags.has(c.tagName) || hasTag(c, tags)))
}

function prune(node) {
  if (!node.children) return
  node.children = node.children.filter((child) => {
    // React's <!-- --> separators between adjacent text nodes.
    if (child.type === "comment") return false
    if (child.type !== "element") return true
    if (DROP_TAGS.has(child.tagName)) return false
    const p = child.properties ?? {}
    if (p.ariaHidden === "true" || p.ariaHidden === true || p.hidden) return false
    // Set in the source where a section is in the markup twice, once per
    // breakpoint, or where a figure only makes sense drawn.
    if (p.dataMd === "skip") return false
    // Images with no alt text are decorative by the page's own declaration.
    if (child.tagName === "img" && !p.alt) return false
    return true
  })
  for (const child of node.children) prune(child)
  // Flex layouts put words in sibling spans with no whitespace between them,
  // which a browser spaces with gap and Markdown runs together: the release
  // channels came out as "nightbell.appRecommendedThe newest release".
  if (node.tagName !== "pre" && node.tagName !== "code") {
    node.children = node.children.flatMap((child, i) => {
      const prev = node.children[i - 1]
      const glued = prev && prev.type === "element" && child.type === "element" && INLINE.has(prev.tagName) && INLINE.has(child.tagName)
      const afterText = prev && prev.type === "text" && /\S$/.test(prev.value) && child.type === "element" && child.tagName === "span" && node.tagName === "a"
      return glued || afterText ? [{ type: "text", value: " " }, child] : [child]
    })
  }
  node.children = node.children.flatMap((child) => {
    if (child.type !== "element") return [child]
    // A call to action is styled with layered divs inside the <a>, and Markdown
    // has no block inside a link, so the converter split one button into three
    // links. Its words are all that is worth keeping.
    if (child.tagName === "a" && hasTag(child, BLOCK)) {
      return [{ ...child, children: [{ type: "text", value: text(child).replace(/\s+/g, " ").trim() }] }]
    }
    // The transcript prints a timestamp and the line beside it with no space.
    if (child.tagName === "time") return [child, { type: "text", value: " " }]
    // Lists of buttons, like the film's chapter strip, are empty once the
    // buttons are gone, and an empty item is a stray "1." in the output.
    if (!KEEP_EMPTY.has(child.tagName) && !text(child).trim() && !hasTag(child, IMG)) return []
    return [child]
  })
}

// <details> is how the FAQ folds its answers. Unfolded, the summary is the
// question, and a question reads best as a heading over its answer.
function unfoldDetails(node) {
  if (!node.children) return
  for (const child of node.children) unfoldDetails(child)
  node.children = node.children.flatMap((child) => {
    if (child.type !== "element" || child.tagName !== "details") return [child]
    return child.children.map((c) =>
      c.type === "element" && c.tagName === "summary" ? { ...c, tagName: "h3" } : c,
    )
  })
}

function find(node, tag) {
  if (node.type === "element" && node.tagName === tag) return node
  for (const child of node.children ?? []) {
    const hit = find(child, tag)
    if (hit) return hit
  }
  return null
}

let pageUrl = SITE_URL

function absolute(node) {
  if (node.type === "link" || node.type === "image") {
    if (node.url.startsWith("/")) node.url = SITE_URL + node.url
    else if (node.url.startsWith("#")) node.url = pageUrl + node.url
  }
  for (const child of node.children ?? []) absolute(child)
}

function meta(html, name) {
  const m = html.match(new RegExp(`<meta name="${name}" content="([^"]*)"`))
  return m ? decode(m[1]) : ""
}

function decode(s) {
  return s
    .replace(/&quot;/g, '"')
    .replace(/&#x27;|&#39;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&")
}

function convert(html) {
  const tree = fromHtml(html)
  const main = find(tree, "main")
  if (!main) throw new Error("no <main>")
  prune(main)
  unfoldDetails(main)
  const mdast = toMdast(main)
  absolute(mdast)
  return toMarkdown(mdast, { extensions: [gfmToMarkdown({ tablePipeAlign: false })], bullet: "-", emphasis: "_", rule: "-" })
    .replace(/\n{3,}/g, "\n\n")
    // Buttons sit side by side with a gap and no space, and a flattened button
    // can keep the space its icon stood next to.
    .replace(/\]\(([^)\s]+)\)\[/g, "]($1) [")
    .replace(/\[([^\]]*?)\s+\]\(/g, "[$1](")
    .trim()
}

const pages = []
for (const rel of readdirSync(out, { recursive: true })) {
  if (!rel.endsWith(".html") || SKIP.has(rel)) continue
  const html = readFileSync(join(out, rel), "utf8")
  const path = rel === "index.html" ? "/" : `/${rel.replace(/\.html$/, "")}`
  const mdRel = rel.replace(/\.html$/, ".md")
  const title = decode(html.match(/<title>([^<]*)<\/title>/)?.[1] ?? "")
  const description = meta(html, "description")
  const canonical = path === "/" ? SITE_URL : SITE_URL + path
  pageUrl = canonical
  const body = convert(html)
  writeFileSync(join(out, mdRel), `<!-- ${canonical} -->\n\n${body}\n`)
  pages.push({ path, md: `/${mdRel}`, title, description })
}

// The order a newcomer would want them in, not the order readdir found them.
const ORDER = ["/", "/faq", "/docs", "/docs/health-format", "/android-background-limits", "/vs/uptimerobot", "/vs/uptime-kuma"]
const missing = ORDER.filter((p) => !pages.some((x) => x.path === p))
if (missing.length) throw new Error(`no Markdown for ${missing.join(", ")}`)
const extra = pages.filter((p) => !ORDER.includes(p.path))
if (extra.length) throw new Error(`a new page needs a place in llms.txt: ${extra.map((p) => p.path).join(", ")}`)
const byPath = Object.fromEntries(pages.map((p) => [p.path, p]))

const entry = (p) => `- [${byPath[p].title}](${SITE_URL}${byPath[p].md}): ${byPath[p].description}`

const llms = `# Nightbell

> ${SEO.description}

Nightbell is a free Android app, Apache-2.0, for Android ${RELEASE.minAndroid} and newer. The current version is ${RELEASE.version}. There is no server to run and no account to make: the phone runs the checks itself, on a schedule, and pages its owner when something goes down. It has six kinds of check: a status code, a full request with assertions on the response, elements picked by tapping them on a real page, a Prometheus metric, PromQL query or Alertmanager, a service's own health report, and activity on a GitHub repository. Alerts can also go out to webhooks such as Slack, Discord, Teams, ntfy and Gotify, sent from the phone.

Every page below is also served as Markdown at the address listed, and a request for the page itself with \`Accept: text/markdown\` gets the same Markdown back.

## Pages

${["/", "/faq", "/docs"].map(entry).join("\n")}

## Reference

${["/docs/health-format", "/android-background-limits"].map(entry).join("\n")}
- [Health format JSON Schema](${SITE_URL}/docs/health-format.schema.json): the schema a JSON health endpoint is validated against.
- [Full reference on GitHub](${REPO.reference}): every setting, monitor type and alert rule the app has.

## Comparisons

${["/vs/uptimerobot", "/vs/uptime-kuma"].map(entry).join("\n")}

## Getting it

- [F-Droid](${CHANNELS.fdroid.url}): package \`${CHANNELS.fdroid.packageId}\`.
- [GitHub Releases](${REPO.latestRelease}): the signed APK, also what Obtainium follows.
- [Source code](${REPO.url})
- [Issues](${REPO.issues})
`
writeFileSync(join(out, "llms.txt"), llms)
console.log(`markdown: ${pages.length} pages and llms.txt`)
