#!/usr/bin/env bash
#
# Build the site and put it on the VPS, atomically.
#
#   ./deploy/scripts/deploy.sh                     # build, upload, switch
#   ./deploy/scripts/deploy.sh --dry-run           # show what would change
#   NIGHTBELL_HOST=user@host ./deploy/scripts/deploy.sh
#
# ## Atomic, and why it has to be
#
# The obvious deploy is rsync straight into the document root, and it has a window
# in it. For the second or two that rsync is mid-transfer, the root holds the new
# index.html alongside the old hashed assets it does not reference yet, so a
# request landing in that window gets a document whose stylesheet 404s. On a site
# whose entire stylesheet is inlined into the document that particular failure is
# invisible, which is worse, because the next release that splits the CSS out
# reintroduces it silently.
#
# So each build goes into its own timestamped directory and a symlink is moved
# once it is complete. Moving a symlink with `mv -T` is a single rename syscall:
# there is no moment where `current` points at nothing, and no request can observe
# a half-written root. Rollback is the same operation pointed at the previous
# directory, which is why the old ones are kept.
#
# Requires: rsync and ssh on this machine, rsync on the VPS, and a key that can
# write to /var/www/nightbell.app. Nothing else, and no agent on the server.
set -euo pipefail

HOST="${NIGHTBELL_HOST:-}"
BASE="${NIGHTBELL_BASE:-/var/www/nightbell.app}"
KEEP="${NIGHTBELL_KEEP:-5}"
DRY=""

for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY="--dry-run" ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

if [ -z "$HOST" ]; then
  cat >&2 <<'MSG'
Set NIGHTBELL_HOST to the ssh destination, for example:

  NIGHTBELL_HOST=deploy@203.0.113.10 ./deploy/scripts/deploy.sh

It is not written into this file on purpose. The repository is public, and the
address of the origin behind a Cloudflare proxy is the one piece of infrastructure
worth not publishing: knowing it is the difference between having to go through
the edge and being able to skip it.
MSG
  exit 2
fi

SITE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$SITE"

# ---------------------------------------------------------------- build first
#
# `verify` and not `build`: it checks the assets are in sync, builds, runs the
# validator, then writes the precompressed files. A deploy that skips the
# validator is how a canonical pointing at the wrong host reaches production, and
# one that skips precompress silently quadruples the bytes on the wire.
echo "==> building"
npm run verify

if [ ! -f dist/index.html ] || [ ! -f dist/404.html ]; then
  echo "dist/ is missing index.html or 404.html, refusing to deploy" >&2
  exit 1
fi

if [ ! -f dist/index.html.gz ]; then
  echo "dist/index.html.gz is missing, so gzip_static would have nothing to serve" >&2
  exit 1
fi

# ------------------------------------------------------------------- upload
STAMP="$(date -u +%Y%m%d-%H%M%S)"
TARGET="$BASE/releases/$STAMP"

echo "==> uploading to $HOST:$TARGET"

# shellcheck disable=SC2029
ssh "$HOST" "mkdir -p '$BASE/releases'"

# --checksum rather than the default size-and-mtime comparison. Astro rewrites
# every file on every build, so mtimes are always new and the default would
# re-upload all 9 MB of video each time. The hashed asset names make the content
# comparison cheap and correct.
rsync -az --checksum --delete $DRY \
  --info=stats1 \
  ./dist/ "$HOST:$TARGET/"

if [ -n "$DRY" ]; then
  echo "==> dry run, nothing switched"
  ssh "$HOST" "rm -rf '$TARGET'" || true
  exit 0
fi

# ------------------------------------------------------------------- switch
#
# `ln -sfnT` then `mv -T` is the careful spelling. `ln -sfn current` on an
# existing symlink-to-a-directory can create the link *inside* the target
# directory instead of replacing it, which leaves `current` pointing at the old
# release and the new link buried one level down. Building the link under a
# temporary name and renaming it over the top has no such case.
echo "==> switching current -> releases/$STAMP"
ssh "$HOST" "
  set -e
  ln -sfnT '$TARGET' '$BASE/.current.new'
  mv -T '$BASE/.current.new' '$BASE/current'
  test -f '$BASE/current/index.html'
"

# open_file_cache in the server block holds metadata for up to 30 seconds, so the
# switch is picked up within that without a reload. A reload is still cheap and
# removes the wait, and Nginx reloads without dropping a connection.
echo "==> reloading nginx"
ssh "$HOST" "sudo nginx -t && sudo systemctl reload nginx"

# ------------------------------------------------------------------- prune
echo "==> keeping the last $KEEP releases"
# shellcheck disable=SC2029
ssh "$HOST" "cd '$BASE/releases' && ls -1dt */ | tail -n +$((KEEP + 1)) | xargs -r rm -rf"

echo "==> done: $STAMP is live"
echo
echo "    roll back with:"
echo "      ssh $HOST \"ln -sfnT $BASE/releases/<stamp> $BASE/.current.new && mv -T $BASE/.current.new $BASE/current\""
