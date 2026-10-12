#!/usr/bin/env bash
#
# Which crawlers read this site, what they got, and who sent people here.
#
#   NIGHTBELL_HOST=user@host ./deploy/scripts/bots.sh
#   NIGHTBELL_HOST=user@host ./deploy/scripts/bots.sh --days 7
#   NIGHTBELL_HOST=user@host ./deploy/scripts/bots.sh --raw > site.log
#
# ## Why this exists
#
# Until September 2026 nightbell.app had no access log of its own. Fifteen sites
# on this box wrote to one combined log with no $host field, so there was no way
# to answer whether a search or assistant crawler had ever fetched a page here.
# The shared log held 5,435 OAI-SearchBot requests at the time and not one of them
# could be attributed. The two that could be proven were proven only because they
# landed on /download, which has always had a log of its own.
#
# That question stopped being academic when an assistant started sending people.
# Six arrived with utm_source=chatgpt.com on the referrer between 31 August and
# 15 September 2026, on a site with nine Bing impressions and ten Google ones over
# the same period, which means they did not arrive through either engine's
# rankings.
#
# ## The three kinds of crawler, and why they are counted apart
#
# INDEX crawlers build a corpus that can be retrieved from later. A visit means
# the page is eligible to be cited, so this is the number that should move after
# publishing something.
#
# TRAINING crawlers collect text for a future model. A visit changes nothing this
# year.
#
# LIVE crawlers are a person, right now, who asked an assistant to read this page.
# One of these is worth more than a hundred of the others: it is somebody in a
# conversation about uptime monitoring with this URL in front of them.
#
# The split is the whole point of the report. A thousand training hits and no
# index hits looks like heavy crawler traffic and means the site is invisible.
#
# ## One family is missing from the table below
#
# Three user agents belonging to one assistant vendor are not listed, because
# matching them means writing that vendor's name into a tracked file and the
# repo's writing rules forbid it with no exception taken here. They are real and
# they do crawl this site: in the September 2026 shared log they were the second
# busiest family after Google. Their traffic lands in the residual at the bottom
# of this report rather than being lost, and the residual line says so.
#
# Lifting this means a decision about the writing rule, not an edit to this file.
# Until then, read the residual as "the named table plus one family".
#
# ## What none of it can tell you
#
# Not one of these numbers is a person. The log stores no address, deliberately,
# and the reasoning is in the nightbell_site log_format comment in
# deploy/nginx/nightbell.app.conf. A user agent is also a claim rather than a
# fact: anything here can be spoofed, and the residual is reported rather than
# hidden for the same reason downloads.sh reports its own.
set -euo pipefail

HOST="${NIGHTBELL_HOST:-}"
LOG="${NIGHTBELL_SITE_LOG:-/var/log/nginx/nightbell/site.log}"
DAYS=14
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

  NIGHTBELL_HOST=deploy@203.0.113.10 ./deploy/scripts/bots.sh

Not written into this file for the same reason it is not in deploy.sh: the
repository is public and the address of an origin behind a proxy is the one piece
of infrastructure worth not publishing.
MSG
  exit 2
fi

# zcat -f over the live log and every rotation. sudo because logrotate creates
# these 0640 www-data:adm, and assuming every box puts this user in adm is how it
# breaks on the next one. The remote stderr is kept rather than discarded: a sudo
# denial and an empty log look identical otherwise, and the diagnostic below would
# then send the reader after the wrong problem.
err="$(mktemp)"
trap 'rm -f "$err"' EXIT
if ! LINES="$(ssh "$HOST" "sudo zcat -f ${LOG}* || true" 2>"$err")"; then
  echo "could not reach $HOST over ssh" >&2
  sed 's/^/  /' "$err" >&2
  exit 1
fi
if [ -s "$err" ]; then
  echo "the remote read complained, so the numbers below may be short:" >&2
  sed 's/^/  /' "$err" >&2
  echo >&2
fi

if [ -n "$RAW" ]; then
  printf '%s\n' "$LINES"
  exit 0
fi

if [ -z "$LINES" ]; then
  cat >&2 <<MSG
Nothing logged yet, and ${LOG} is empty or absent on $HOST.

This log is new in the change that added bots.sh, so on a box that has not been
deployed since then the file will not exist at all. Check in this order:

  ssh $HOST 'sudo ls -l $(dirname "$LOG")'   # the directory has to exist
  ssh $HOST 'sudo grep -c nightbell_site /etc/nginx/sites-enabled/nightbell.app.conf'
  ssh $HOST 'sudo nginx -t'                  # the format has to be loaded

If the server block is current and the file is still absent after a real request,
the likely cause is that pages are being answered from the edge and the origin is
seeing nothing. curl with a cache buster to check.
MSG
  exit 1
fi

SINCE="$(date -u -d "$DAYS days ago" +%F)"

# Fields: $time_iso8601 $status $http_cf_ipcountry "$request_uri" "$http_referer"
# "$http_user_agent". Splitting on the double quote puts the path in $2, the
# referrer in $4 and the agent in $6, which is why they are logged in that order.
#
# Patterns are matched against a lowercased agent, so the table holds lowercase
# and the log's own capitalisation does not matter. They are matched with `index`
# rather than a regex because every one of them is a literal.
read -r -d '' FAMILIES <<'TABLE' || true
index	oai-searchbot	OpenAI search
index	perplexitybot	Perplexity
index	googlebot	Google
index	bingbot	Bing
index	applebot	Apple
index	duckassistbot	DuckDuckGo
index	youbot	You.com
train	gptbot	OpenAI training
train	google-extended	Google, model training
train	ccbot	Common Crawl
train	bytespider	ByteDance
train	amazonbot	Amazon
train	meta-externalagent	Meta
train	cohere-ai	Cohere
live	chatgpt-user	ChatGPT, a person asked it to read this
live	perplexity-user	Perplexity, a person asked it to read this
seo	ahrefsbot	Ahrefs
seo	semrushbot	Semrush
seo	mj12bot	Majestic
seo	dotbot	Moz
seo	petalbot	Petal
TABLE

# Every pattern in the table above, as one alternation, so the two passes over the
# residual below cannot drift from the table by being edited separately.
NAMED="$(printf '%s\n' "$FAMILIES" | awk -F'\t' 'NF { printf "%s%s", sep, $2; sep = "|" }')"

# Anything that self-identifies as a program. The same list downloads.sh uses,
# which is where the reasoning for each entry is written down.
GENERIC='bot|crawl|spider|slurp|curl|wget|python|aiohttp|scrapy|headless|preview|facebookexternalhit|whatsapp|telegram|discord|monitoring|uptime|go-http-client|okhttp|java/|apache-httpclient|libwww|lwp::|axios|node-fetch|guzzle|httpx|postman|insomnia|powershell|winhttp|zgrab|nuclei|httrack|dart/|ruby'

total=$(printf '%s\n' "$LINES" | grep -c . || true)
# One awk pass rather than `sort | head -1`. Both of the other scripts in this
# directory had that pipeline and both died silently with 141 the month their log
# outgrew the 64k pipe buffer: head leaves after the first line and sort takes the
# broken pipe down with the whole script under pipefail.
first=$(printf '%s\n' "$LINES" | awk 'NF { d = substr($0, 1, 10); if (m == "" || d < m) m = d } END { print m }')

printf 'Nightbell site log, %s on %s\n\n' "$LOG" "$HOST"
printf '  first line        %s\n' "${first:-none}"
printf '  requests          %s\n' "$total"

# ------------------------------------------------------------------ crawlers

printf '\n  crawlers, last %s days\n' "$DAYS"

printf '%s\n' "$LINES" | awk -F'"' -v s="$SINCE" -v table="$FAMILIES" '
  BEGIN {
    n = split(table, rows, "\n")
    for (i = 1; i <= n; i++) {
      if (rows[i] == "") continue
      split(rows[i], f, "\t")
      kind[i] = f[1]; pat[i] = f[2]; label[i] = f[3]
    }
    order["index"] = 1; order["live"] = 2; order["train"] = 3; order["seo"] = 4
  }
  {
    day = substr($1, 1, 10)
    if (day < s) next
    split($1, head, " ")
    status = head[2]
    agent = tolower($6)
    for (i = 1; i <= n; i++) {
      if (pat[i] == "" || index(agent, pat[i]) == 0) continue
      hits[i]++
      # 2xx and 3xx are the crawler working. Anything else is the finding: a
      # crawler collecting 404s is a sitemap or a redirect that is wrong, and it
      # is invisible in a browser because a browser never asks for those paths.
      if (status !~ /^[23]/) bad[i]++
      p = $2; sub(/\?.*/, "", p)
      if (!seen[i FS p]++) pages[i]++
      if (day > last[i]) last[i] = day
      next
    }
  }
  END {
    for (i = 1; i <= n; i++) {
      if (!hits[i]) continue
      printf "%d\t%s\t%s\t%d\t%d\t%d\t%s\n", order[kind[i]], kind[i], label[i], hits[i], pages[i], bad[i] + 0, last[i]
    }
  }' \
  | sort -t"$(printf '\t')" -k1,1n -k4,4nr \
  | awk -F'\t' '
      function tag(k) {
        if (k == "index") return "INDEX, a visit means this page can be cited"
        if (k == "live")  return "LIVE, a person asked an assistant to read this page"
        if (k == "train") return "TRAINING, changes nothing this year"
        return "SEO tools"
      }
      {
        if ($2 != group) {
          group = $2
          printf "\n    %s\n\n", tag(group)
          printf "    %-40s %7s %6s %7s  %s\n", "", "hits", "pages", "not ok", "last seen"
        }
        printf "    %-40s %7d %6d %7d  %s\n", $3, $4, $5, $6, $7
      }
      END { if (!group) printf "\n    nothing in the table above appeared in this window\n" }'

# ---------------------------------------------------------- what they read

printf '\n  pages the index and live crawlers read, last %s days\n\n' "$DAYS"
printf '%s\n' "$LINES" | awk -F'"' -v s="$SINCE" '
  substr($1, 1, 10) >= s {
    a = tolower($6)
    if (a ~ /oai-searchbot|perplexitybot|googlebot|bingbot|applebot|duckassistbot|youbot|chatgpt-user|perplexity-user/) {
      p = $2; sub(/\?.*/, "", p)
      n[p]++
    }
  }
  END {
    if (!length(n)) { print "       0  nothing yet"; exit }
    for (p in n) printf "%8d  %s\n", n[p], p
  }' \
  | sort -rn | sed -n '1,14p' | sed 's/^/    /'

# ------------------------------------------------------------------ referrals

printf '\n  people an assistant sent here, all time\n\n'
refs=$(printf '%s\n' "$LINES" | awk -F'"' -v b="$GENERIC" '
  tolower($6) !~ b && tolower($4) ~ /chatgpt|openai|perplexity|copilot|gemini|bard/ {
    p = $2; sub(/\?.*/, "", p)
    printf "%s  %-28s %s\n", substr($1, 1, 10), p, $4
  }' || true)
if [ -n "$refs" ]; then
  printf '%s\n' "$refs" | sort | sed 's/^/    /'
else
  printf '    none in this log yet\n'
fi

# ------------------------------------------------------------------- the rest
#
# Reported rather than hidden, and it is not a rounding error here. It holds every
# crawler the table does not name, which includes the one family this file is not
# allowed to name, so on this site it is a large and legitimate number rather than
# a residual to ignore.

printf '\n  everything else, last %s days\n\n' "$DAYS"
printf '%s\n' "$LINES" | awk -F'"' -v s="$SINCE" -v named="$NAMED" -v b="$GENERIC" '
  substr($1, 1, 10) >= s {
    a = tolower($6)
    if (a ~ named) next
    if (a ~ b) { programs++; next }
    browsers++
  }
  END {
    printf "    %d requests from a crawler the table above does not name\n", programs + 0
    printf "    %d from something that reads as a browser\n", browsers + 0
  }'

printf '\n    what the browsers asked for\n\n'
printf '%s\n' "$LINES" | awk -F'"' -v s="$SINCE" -v named="$NAMED" -v b="$GENERIC" '
  substr($1, 1, 10) >= s {
    a = tolower($6)
    if (a ~ named || a ~ b) next
    p = $2; sub(/\?.*/, "", p)
    seen[p]++
  }
  END {
    if (!length(seen)) { print "       0  nothing yet"; exit }
    for (p in seen) printf "%8d  %s\n", seen[p], p
  }' \
  | sort -rn | sed -n '1,10p' | sed 's/^/    /'

printf '\n'
