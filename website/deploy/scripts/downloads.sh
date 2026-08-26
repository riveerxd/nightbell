#!/usr/bin/env bash
#
# How many people downloaded Nightbell.
#
#   NIGHTBELL_HOST=user@host ./deploy/scripts/downloads.sh
#   NIGHTBELL_HOST=user@host ./deploy/scripts/downloads.sh --days 7
#   NIGHTBELL_HOST=user@host ./deploy/scripts/downloads.sh --raw > clicks.log
#
# ## Two numbers, and why neither is the answer on its own
#
# CLICKS come from /var/log/nginx/nightbell/download.log on the origin, one line
# per press of a download button on the site. That log is the only analytics this
# site has: no JavaScript, no third party, no cookie. It knows the referrer, so it
# is the only thing that can answer "did that Reddit thread do anything", and it
# cannot know whether the file actually arrived.
#
# DOWNLOADS come from GitHub's own asset counter over the public API, which is the
# only party that sees the bytes move. It counts every request for the asset from
# anywhere, including people who never touched this site, F-Droid style mirrors,
# CI, and crawlers that fetch the whole release. It has no referrer and no dates.
#
# So clicks above downloads means people are cancelling, and downloads far above
# clicks means the release is being fetched from somewhere that is not the site,
# which for a project posted to Reddit and awaiting an F-Droid listing is the
# expected shape and the more encouraging one.
#
# Neither number counts people. The log deliberately stores no IP (see the
# log_format comment in deploy/nginx/nightbell.app.conf), so nothing here can
# deduplicate a person from a person who clicked twice.
set -euo pipefail

HOST="${NIGHTBELL_HOST:-}"
LOG="${NIGHTBELL_DOWNLOAD_LOG:-/var/log/nginx/nightbell/download.log}"
DAYS=30
RAW=""

while [ $# -gt 0 ]; do
  case "$1" in
    --days) DAYS="${2:?--days needs a number}"; shift 2 ;;
    --raw) RAW="1"; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ -z "$HOST" ]; then
  cat >&2 <<'MSG'
Set NIGHTBELL_HOST to the ssh destination, the same one deploy.sh uses:

  NIGHTBELL_HOST=deploy@203.0.113.10 ./deploy/scripts/downloads.sh

Not written into this file for the same reason it is not written into deploy.sh:
the repository is public and the origin address behind a Cloudflare proxy is the
one piece of infrastructure worth not publishing.
MSG
  exit 2
fi

SITE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$SITE"

# ------------------------------------------------------------------ the log
#
# `zcat -f` over the live log and every rotation, so this is a total since the
# first deploy and not since the last rotation. -f passes uncompressed files
# through unchanged, which covers download.log and the delaycompress-ed .1.
#
# sudo because logrotate creates these 0640 www-data:adm. A user in the adm group
# can read them without it, but assuming that of every box is how this breaks on
# the next one. The same passwordless sudo deploy.sh already uses for `nginx -t`.
#
# The whole file is pulled back and processed locally: it is one short line per
# click, and it means the awk below is edited here rather than inside a quoted
# heredoc on the far side of an ssh.
if ! CLICKS="$(ssh "$HOST" "sudo zcat -f ${LOG}* 2>/dev/null || true")"; then
  echo "could not read ${LOG}* on $HOST" >&2
  exit 1
fi

if [ -n "$RAW" ]; then
  printf '%s\n' "$CLICKS"
  exit 0
fi

if [ -z "$CLICKS" ]; then
  cat >&2 <<MSG
No clicks logged yet, and ${LOG} is empty or absent on $HOST.

If the redirect went live more than a few minutes ago, check in this order:

  ssh $HOST 'sudo ls -l $(dirname "$LOG")'    # the directory has to exist
  ssh $HOST 'sudo nginx -t'                   # the location has to be loaded
  curl -sI https://nightbell.app/download     # expect 302 to a GitHub URL

MSG
  exit 1
fi

# The fields are: $time_iso8601 $status $country "$referer" "$user_agent"
# Splitting on the double quote gives the referrer in $2 and the agent in $4,
# which is why they are logged in that order and quoted.
#
# An unset variable is written as a bare `-` by nginx and not as an empty string,
# which is why the referrer test below accepts both. A referrer of `-` is the
# normal case for a pasted link, a QR code or a client that suppresses it, so it
# is labelled rather than dropped.
SINCE="$(date -u -d "$DAYS days ago" +%F)"

# Anything self-identifying as automation. It will not catch a crawler that lies,
# and the residual is reported rather than hidden, because a bot filter that
# silently rewrites the headline number is worse than no filter.
BOTS='bot|crawl|spider|slurp|curl|wget|python|scrapy|headless|preview|facebookexternalhit|whatsapp|telegram|discord|monitoring|uptime'

total=$(printf '%s\n' "$CLICKS" | grep -c . || true)
humans=$(printf '%s\n' "$CLICKS" | awk -F'"' -v b="$BOTS" 'tolower($4) !~ b' | grep -c . || true)
recent=$(printf '%s\n' "$CLICKS" | awk -F'"' -v b="$BOTS" -v s="$SINCE" \
  'tolower($4) !~ b && substr($1,1,10) >= s' | grep -c . || true)
first=$(printf '%s\n' "$CLICKS" | head -1 | cut -c1-10)
odd=$(printf '%s\n' "$CLICKS" | awk '$2 != 302' | grep -c . || true)

echo
echo "CLICKS on /download        (nginx, since $first)"
echo "  $humans excluding bots, $total including them"
echo "  $recent in the last $DAYS days"
# An `if` and not `[ ... ] && echo`, which under `set -e` makes the whole script
# exit here on the happy path where there is nothing to report.
if [ "$odd" -gt 0 ]; then
  echo "  $odd did not answer 302, so the count is not measuring what it thinks"
fi

echo
echo "  where they came from"
printf '%s\n' "$CLICKS" | awk -F'"' -v b="$BOTS" 'tolower($4) !~ b {print ($2 == "" || $2 == "-" ? "(direct or pasted link)" : $2)}' \
  | sort | uniq -c | sort -rn | head -10 | sed 's/^/    /'

echo
echo "  countries"
printf '%s\n' "$CLICKS" | awk -F'"' -v b="$BOTS" 'tolower($4) !~ b {print $1}' \
  | awk '{print ($3 == "" || $3 == "-" ? "??" : $3)}' | sort | uniq -c | sort -rn | head -10 | sed 's/^/    /'

# ---------------------------------------------------------------- and GitHub
#
# Unauthenticated, so it is rate limited to 60 requests an hour per IP, which is
# fifty-nine more than this needs. The repo slug is read out of site.config.mjs
# rather than written here twice.
echo
SLUG="$(node -e 'import("./site.config.mjs").then((m) => console.log(m.REPO.owner + "/" + m.REPO.name))')"

if ! command -v jq >/dev/null 2>&1; then
  echo "DOWNLOADS from GitHub      (needs jq, which is not installed)"
  echo "  https://api.github.com/repos/$SLUG/releases"
else
  echo "DOWNLOADS from GitHub      (every source, not just this site)"
  curl -sf "https://api.github.com/repos/$SLUG/releases?per_page=100" \
    | jq -r '.[] | .tag_name as $t | .assets[] | select(.name | endswith(".apk"))
             | "    \(.download_count)\t\($t)\t\(.name)"' \
    | sort -rn -k1 | head -10 \
    || echo "    could not reach the GitHub API"

  curl -sf "https://api.github.com/repos/$SLUG/releases?per_page=100" \
    | jq -r '[.[].assets[] | select(.name | endswith(".apk")) | .download_count] | add // 0
             | "  \(.) across every release"' \
    || true
fi
echo
