// Writes the two files every release has to change outside the page, from
// RELEASE in src/lib/site.ts, the way the old site's gen-version-manifest.mjs
// and gen-download-redirect.mjs did. A hand-kept copy of either is how an
// install gets told an old version is the newest one, or how /download keeps
// handing out last month's APK.
//
//   public/v1/release.json      what every installed app reads every six hours.
//                               The shape is the old site's byte for byte: the
//                               app parses it, and the census counts its reads.
//   download-target.generated.conf
//                               the `return 302` nginx includes for /download.
//                               Installed by hand into
//                               /etc/nginx/snippets/nightbell/download-target.conf
import { readFileSync, writeFileSync } from "node:fs"

import { RELEASE, REPO } from "../src/lib/site.ts"

const manifest = {
  schema: 1,
  version: RELEASE.version,
  versionCode: RELEASE.versionCode,
  tag: RELEASE.tag,
  url: `${REPO.url}/releases/tag/${RELEASE.tag}`,
  notes: `Nightbell ${RELEASE.version}`,
  apkUrl: RELEASE.apkUrl,
  apkSize: RELEASE.apkBytes,
  apkSha256: RELEASE.apkSha256,
}

const target = `# Written by website/scripts/release-files.mjs from RELEASE in src/lib/site.ts.
#
# 302 rather than 301: the target changes with every version, and a permanent
# redirect would stay cached in browsers past the next release.
#
#   version   ${RELEASE.version}
#   asset     ${RELEASE.apkName}
return 302 ${RELEASE.apkUrl};
`

function write(rel, body) {
  const file = new URL(`../${rel}`, import.meta.url)
  let old = ""
  try {
    old = readFileSync(file, "utf8")
  } catch {}
  writeFileSync(file, body)
  console.log(`${old === body ? "ok   " : "wrote"} ${rel} for ${RELEASE.version}`)
}

write("public/v1/release.json", `${JSON.stringify(manifest, null, 2)}\n`)
write("download-target.generated.conf", target)
