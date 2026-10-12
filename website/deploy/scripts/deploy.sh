#!/usr/bin/env bash
#
# Put the Next build on the VPS with no window where a visitor can see a broken
# site. The same shape as the Astro site's deploy it replaced in 3.18.0's week:
# each build goes to releases/<stamp>/ and `current` is moved onto it with one
# rename, so no request can see a half-written root, and rollback is the same
# rename pointed at the previous directory.
#
#   NIGHTBELL_HOST=user@host ./deploy/scripts/deploy.sh           # config, then files
#   NIGHTBELL_HOST=user@host ./deploy/scripts/deploy.sh --files   # files only
#
# ## Why the config goes first
#
# The CSP for this build carries the hashes of its own inline scripts. Installed
# after the switch, the first visitors would get the new pages under the old
# policy, which blocks every one of those scripts. Installed before, the old pages
# run under a policy that only adds allowances to the one they were built for, so
# both builds work under it and the order leaves no gap.
#
# The config is installed with a backup of /etc/nginx taken first, and if
# `nginx -t` fails the backup is restored before anything reloads. This box serves
# fifteen sites and a config that does not parse takes all of them down.
set -euo pipefail

HOST="${NIGHTBELL_HOST:?set NIGHTBELL_HOST to the ssh destination, see website/deploy/README.md}"
BASE="${NIGHTBELL_BASE:-/var/www/nightbell.app}"
KEEP="${NIGHTBELL_KEEP:-5}"
FILES_ONLY=""
[ "${1:-}" = "--files" ] && FILES_ONLY=1

DEPLOY="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$DEPLOY/.."

# Every log this site writes is kept free of addresses on purpose: no IP, no
# hash of one, so nothing in them is personal data. The Astro site's validator
# enforced that; this is the same check, before anything is built or uploaded.
if grep -E '^\s*log_format\s+nightbell_' "$DEPLOY/nginx/nightbell.app.conf" | grep -qE 'remote_addr|realip_remote_addr|http_x_forwarded_for|http_cf_connecting_ip|http_x_real_ip'; then
  echo "a nightbell log_format records an address, refusing to deploy" >&2
  exit 1
fi

echo "==> building"
npm run build

for f in out/index.html out/404.html out/index.html.gz out/v1/release.json csp.generated.conf download-target.generated.conf; do
  [ -s "$f" ] || { echo "missing $f, refusing to deploy" >&2; exit 1; }
done

# RELEASE in site.ts against the tag GitHub calls latest. A block left behind is
# how the site once handed out a three-version-old APK for sixteen days. No answer
# from GitHub warns rather than stops: a deploy must not depend on it being up.
have="$(node -e 'console.log(require("./out/v1/release.json").tag)')"
latest="$(curl -fsS -m 8 https://api.github.com/repos/riveerxd/nightbell/releases/latest 2>/dev/null \
  | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{console.log(JSON.parse(s).tag_name||"")}catch{}})')" || true
if [ -z "$latest" ]; then
  echo "warn: GitHub did not say what the latest tag is; RELEASE says $have, confirm it by hand" >&2
elif [ "$latest" != "$have" ]; then
  echo "RELEASE in src/lib/site.ts says $have but GitHub's latest release is $latest, refusing to deploy" >&2
  exit 1
fi

STAMP="$(date -u +%Y%m%d-%H%M%S)"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

if [ -z "$FILES_ONLY" ]; then
  echo "==> staging config"
  # The live snippet with only its CSP line swapped for this build's.
  grep -v '^add_header Content-Security-Policy ' "$DEPLOY/nginx/snippets/nightbell/security-headers.conf" > "$STAGE/security-headers.conf"
  cat csp.generated.conf >> "$STAGE/security-headers.conf"
  cp "$DEPLOY/nginx/nightbell.app.conf" "$STAGE/"
  cp download-target.generated.conf "$STAGE/download-target.conf"
  cp "$DEPLOY/logrotate/nightbell-census" "$DEPLOY/logrotate/nightbell-download" "$DEPLOY/logrotate/nightbell-site" "$STAGE/"
  rsync -a "$STAGE/" "$HOST:/tmp/nightbell-deploy-$STAMP/"

  echo "==> installing config"
  # shellcheck disable=SC2029
  ssh "$HOST" "
    set -e
    S=/tmp/nightbell-deploy-$STAMP
    B=/root/nginx-backup-$STAMP.tgz
    sudo tar czf \$B /etc/nginx
    sudo install -m 644 \$S/nightbell.app.conf /etc/nginx/sites-available/nightbell.app.conf
    sudo install -m 644 \$S/security-headers.conf /etc/nginx/snippets/nightbell/security-headers.conf
    sudo install -m 644 \$S/download-target.conf /etc/nginx/snippets/nightbell/download-target.conf
    sudo install -m 644 \$S/nightbell-census \$S/nightbell-download \$S/nightbell-site /etc/logrotate.d/
    if [ ! -e /var/log/nginx/nightbell/site.log ]; then
      sudo install -o www-data -g adm -m 640 /dev/null /var/log/nginx/nightbell/site.log
    fi
    if ! sudo nginx -t; then
      echo 'nginx -t failed, restoring the backup' >&2
      sudo tar xzf \$B -C /
      sudo nginx -t
      exit 1
    fi
    sudo systemctl reload nginx
    rm -rf \$S
    echo backup kept at \$B
  "
fi

echo "==> uploading to $HOST:$BASE/releases/$STAMP"
# shellcheck disable=SC2029
ssh "$HOST" "mkdir -p '$BASE/releases'"
# screens-hd/ holds the 1440 originals the exports are cut from. Nothing on the
# site links to them, so they stay in the repository and off the server.
rsync -az --checksum --delete --exclude screens-hd/ --info=stats1 ./out/ "$HOST:$BASE/releases/$STAMP/"

echo "==> switching current -> releases/$STAMP"
# shellcheck disable=SC2029
ssh "$HOST" "
  set -e
  ln -sfnT '$BASE/releases/$STAMP' '$BASE/.current.new'
  mv -T '$BASE/.current.new' '$BASE/current'
  test -f '$BASE/current/index.html'
  sudo nginx -t && sudo systemctl reload nginx
  cd '$BASE/releases' && ls -1dt */ | tail -n +$((KEEP + 1)) | xargs -r rm -rf
"

echo "==> done: $STAMP is live"
echo "    roll back: ssh \$NIGHTBELL_HOST \"ln -sfnT $BASE/releases/<stamp> $BASE/.current.new && mv -T $BASE/.current.new $BASE/current\""
