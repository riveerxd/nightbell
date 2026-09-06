#!/usr/bin/env bash
#
# How many copies of Nightbell are running.
#
#   NIGHTBELL_HOST=user@host ./deploy/scripts/census.sh
#   NIGHTBELL_HOST=user@host ./deploy/scripts/census.sh --days 7
#   NIGHTBELL_HOST=user@host ./deploy/scripts/census.sh --raw > census.log
#
# ## The two numbers, and which one to trust
#
# NEW INSTALLS is exact. An install's first ever successful check appends `new` to
# its User-Agent and no later check ever does, so this is a count of installs and
# not of anything else. No identifier makes that work: one install sends the word
# once in its life, which is why nothing has to be recognised to count it. It
# overcounts in one direction only, when somebody clears the app's data or
# reinstalls, and the alternative to that overcount is a durable identifier, which
# is the thing this whole design exists to avoid.
#
# ACTIVE INSTALLS is a floor, and deliberately computed as one. The app checks at
# most once every six hours (`AppUpdate.CHECK_INTERVAL_MS`), so no install can
# produce more than four scheduled checks in a day, so scheduled checks divided by
# four cannot be higher than the number of installs that were awake. Phones in
# Doze and phones in tunnels push the real figure above it, never below. "At least
# this many" is a thing worth saying; a point estimate from a guessed divisor is
# not, so the rate is printed with the answer and can be overridden with --rate
# once there is a release day to calibrate against.
#
# **Scheduled** is doing real work in that sentence. "Check now" in Settings
# passes `force` and skips the interval entirely, so one person pressing it forty
# times in an afternoon would have read as ten extra installs and the floor would
# not have been a floor. Those checks arrive marked `tap` and are excluded from the
# division below. They still count toward the version histogram, because they are
# real checks by real installs.
#
# Neither number can tell you anything about a person. The log stores no address
# and no hash of one, on purpose, and the reasoning is written out in the
# nightbell_census log_format comment in deploy/nginx/nightbell.app.conf.
set -euo pipefail

HOST="${NIGHTBELL_HOST:-}"
LOG="${NIGHTBELL_CENSUS_LOG:-/var/log/nginx/nightbell/census.log}"
DAYS=14
RATE=4
RAW=""

while [ $# -gt 0 ]; do
  case "$1" in
    --days) DAYS="${2:?--days needs a number}"; shift 2 ;;
    --rate) RATE="${2:?--rate needs a number}"; shift 2 ;;
    --raw) RAW="1"; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ -z "$HOST" ]; then
  cat >&2 <<'MSG'
Set NIGHTBELL_HOST to the ssh destination, the same one deploy.sh uses:

  NIGHTBELL_HOST=deploy@203.0.113.10 ./deploy/scripts/census.sh

Not written into this file for the same reason it is not in deploy.sh: the
repository is public and the address of an origin behind a proxy is the one piece
of infrastructure worth not publishing.
MSG
  exit 2
fi

# zcat -f over the live log and every rotation, so this is a total since the
# manifest went live rather than since the last rotation. sudo because logrotate
# creates these 0640 www-data:adm, and assuming every box puts this user in adm is
# how it breaks on the next one.
# `|| true` only on the zcat, so a missing rotation is not an error, and the
# remote stderr is kept rather than discarded. Swallowing both meant a sudo denial
# or a wrong path produced an empty result, which then printed the "nothing logged
# yet, check nginx" diagnostic below and sent the reader after entirely the wrong
# problem.
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

Every install checks every six hours, so an empty log more than six hours after
a release means something in the chain is not wired. Check in this order:

  curl -sI https://nightbell.app/v1/release.json   # expect 200 and no-store
  ssh $HOST 'sudo nginx -t'                        # the location has to be loaded
  ssh $HOST 'sudo ls -l $(dirname "$LOG")'         # the directory has to exist

A 200 with a Cache-Control that is not no-store means Cloudflare is answering and
the origin never sees the request, which is the one failure that leaves the app
working perfectly while the count stays at zero.
MSG
  exit 1
fi

# Fields: $time_iso8601 $status "$http_user_agent". Splitting on the double quote
# puts the agent in $2, which is why it is the only quoted field.
#
# The app is the only client whose agent starts with Nightbell/. Everything else
# hitting this path is a crawler, a scanner or somebody curling it, and it is
# subtracted rather than filtered out of sight: robots.txt disallows /v1/, which
# is a request and not a control, so the residual is worth seeing.
SINCE="$(date -u -d "$DAYS days ago" +%F)"

app_lines=$(printf '%s\n' "$LINES" | awk -F'"' '$2 ~ /^Nightbell\//' | grep -c . || true)
total_lines=$(printf '%s\n' "$LINES" | grep -c . || true)
other=$((total_lines - app_lines))
# `; new` appears in a tapped first check too, as `(Android; new; tap)`, so this
# matches the word rather than the end of the string.
new_total=$(printf '%s\n' "$LINES" | awk -F'"' '$2 ~ /^Nightbell\/.*; new[;)]/' | grep -c . || true)
tapped=$(printf '%s\n' "$LINES" | awk -F'"' '$2 ~ /^Nightbell\/.*; tap\)/' | grep -c . || true)
bad_status=$(printf '%s\n' "$LINES" | awk '$2 != 200' | grep -c . || true)
first=$(printf '%s\n' "$LINES" | awk 'NF {print substr($0, 1, 10)}' | sort | head -1)

printf 'Nightbell census, %s on %s\n\n' "$LOG" "$HOST"
printf '  first line        %s\n' "${first:-none}"
printf '  app requests      %s\n' "$app_lines"
printf '  new installs      %s   exact, one per install ever\n' "$new_total"
if [ "$other" -gt 0 ]; then
  printf '  not the app       %s   crawlers, scanners, hand written curls\n' "$other"
fi
if [ "$tapped" -gt 0 ]; then
  printf '  Check now taps    %s   real checks, left out of the floor below\n' "$tapped"
fi
if [ "$bad_status" -gt 0 ]; then
  # Not a missing manifest: `error_page 404` redirects that into the site's
  # ordinary access log and this file gets no line at all. See the log_format
  # comment in nightbell.app.conf.
  printf '  not 200           %s   a 5xx or a 304 that should not be here\n' "$bad_status"
fi

printf '\n  per day, last %s days (installs is a floor: scheduled checks / %s)\n\n' "$DAYS" "$RATE"
printf '    %-12s %8s %6s %10s\n' date checks new installs
printf '%s\n' "$LINES" \
  | awk -F'"' -v s="$SINCE" -v rate="$RATE" '
      $2 ~ /^Nightbell\// {
        day = substr($1, 1, 10)
        if (day < s) next
        checks[day]++
        if ($2 ~ /; new[;)]/) fresh[day]++
        # Only scheduled checks divide into installs. A tapped one is a real check
        # by a real install and says nothing about the cadence.
        if ($2 !~ /; tap\)/) scheduled[day]++
      }
      END {
        # Piped to sort rather than sorted in here. asorti is a gawk extension and
        # this awk runs wherever the maintainer is standing, which is not always
        # the same distro as the origin.
        for (d in checks) {
          printf "    %-12s %8d %6d %10d\n", d, checks[d], fresh[d] + 0, int((scheduled[d] + 0) / rate)
        }
      }' \
  | sort

printf '\n  versions seen, last %s days\n\n' "$DAYS"
printf '%s\n' "$LINES" \
  | awk -F'"' -v s="$SINCE" '
      $2 ~ /^Nightbell\// && substr($1, 1, 10) >= s {
        split($2, parts, /[\/ ]/)
        seen[parts[2]]++
        all++
      }
      END {
        for (v in seen) printf "    %-12s %8d  %5.1f%%\n", v, seen[v], 100 * seen[v] / all
      }' \
  | sort -k2 -nr
