#!/usr/bin/env bash
set -euo pipefail

SSH_HOST="${SSH_HOST:-kvm4}"
TARGET_DOMAIN="${TARGET_DOMAIN:-unroll.uranashel.com}"
REMOTE_DIR="/opt/unroll"
EDGE_CADDYFILE="/opt/nengap/infra/Caddyfile"
EDGE_CADDY_CONTAINER="nengap-prod-caddy-1"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SITE_DIR="$ROOT/site"
APK_BUILT="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
APK_SITE="$SITE_DIR/download/Unroll-1.0.0-android-preview.apk"

if [ ! -d "$SITE_DIR" ]; then
  echo "Missing site directory: $SITE_DIR" >&2
  exit 1
fi

if [ -f "$APK_BUILT" ]; then
  mkdir -p "$SITE_DIR/download"
  cp "$APK_BUILT" "$APK_SITE"
fi

if [ ! -f "$APK_SITE" ]; then
  echo "Missing Android APK at $APK_SITE" >&2
  exit 1
fi

echo "==> Staging deploy bundle to $SSH_HOST:$REMOTE_DIR"
ssh "$SSH_HOST" "mkdir -p '$REMOTE_DIR/site.new' '$REMOTE_DIR/site'"
scp -q "$ROOT/deploy/compose.yml" "$ROOT/deploy/Caddyfile" "$SSH_HOST:$REMOTE_DIR/"
COPYFILE_DISABLE=1 tar -C "$SITE_DIR" --exclude=".DS_Store" -czf - . | \
  ssh "$SSH_HOST" "rm -rf '$REMOTE_DIR/site.new' && mkdir -p '$REMOTE_DIR/site.new' && tar -xzf - -C '$REMOTE_DIR/site.new' && rm -rf '$REMOTE_DIR/site.old' && if [ -d '$REMOTE_DIR/site' ]; then mv '$REMOTE_DIR/site' '$REMOTE_DIR/site.old'; fi && mv '$REMOTE_DIR/site.new' '$REMOTE_DIR/site' && rm -rf '$REMOTE_DIR/site.old'"

echo "==> Starting unroll-web container"
ssh "$SSH_HOST" "cd '$REMOTE_DIR' && docker compose up -d --force-recreate && sleep 1 && docker exec unroll-web caddy validate --config /etc/caddy/Caddyfile"

SERVER_IP="$(ssh "$SSH_HOST" "curl -s4 --max-time 5 https://ifconfig.me || hostname -I | awk '{print \$1}'")"
RESOLVED_IP="$(dig @1.1.1.1 +short A "$TARGET_DOMAIN" | tail -n 1 || true)"
if [ -z "$RESOLVED_IP" ]; then
  RESOLVED_IP="$(dig +short A "$TARGET_DOMAIN" | tail -n 1 || true)"
fi

if [ "$RESOLVED_IP" != "$SERVER_IP" ]; then
  echo "==> Skipping edge Caddy TLS activation: $TARGET_DOMAIN resolves to '${RESOLVED_IP:-NXDOMAIN}', expected '$SERVER_IP'."
  echo "    Add an A record '$TARGET_DOMAIN -> $SERVER_IP' in DNS, then rerun $0."
  exit 0
fi

echo "==> Updating edge Caddyfile for $TARGET_DOMAIN ($RESOLVED_IP)"
ssh "$SSH_HOST" TARGET_DOMAIN="$TARGET_DOMAIN" EDGE_CADDYFILE="$EDGE_CADDYFILE" EDGE_CADDY_CONTAINER="$EDGE_CADDY_CONTAINER" bash -s <<'EOF'
set -euo pipefail
BACKUP="${EDGE_CADDYFILE}.bak.$(date +%Y%m%d%H%M%S)"
cp "$EDGE_CADDYFILE" "$BACKUP"

python3 - "$EDGE_CADDYFILE" "$TARGET_DOMAIN" <<'PY'
import re, sys
path, target = sys.argv[1], sys.argv[2]
text = open(path, encoding="utf-8").read()

# Remove legacy unroll.uranashel.cloud block if present
text = re.sub(
    r"\n?# Unroll[^\n]*\nunroll\.uranashel\.cloud\s*\{.*?\n\}\n?",
    "\n",
    text,
    flags=re.S,
)

if not re.search(rf"(^|\s){re.escape(target)}(\s*\{{|\s|,|$)", text, re.M):
    block = f"""
# Unroll landing, legal pages & APK download (/opt/unroll). Remove this block if Unroll moves away.
{target} {{
\tencode zstd gzip
\theader {{
\t\tStrict-Transport-Security "max-age=31536000"
\t\tX-Content-Type-Options "nosniff"
\t\tReferrer-Policy "strict-origin-when-cross-origin"
\t\t-Server
\t}}
\treverse_proxy unroll-web:80
}}
"""
    text = text.rstrip() + "\n" + block

open(path, "w", encoding="utf-8").write(text)
PY

if ! docker exec "$EDGE_CADDY_CONTAINER" caddy validate --config /etc/caddy/Caddyfile; then
  echo "Caddy validation failed, restoring $BACKUP" >&2
  mv "$BACKUP" "$EDGE_CADDYFILE"
  exit 1
fi

docker exec "$EDGE_CADDY_CONTAINER" caddy reload --config /etc/caddy/Caddyfile
echo "Configured $TARGET_DOMAIN and reloaded $EDGE_CADDY_CONTAINER"
EOF

echo "==> Done. Verifying container status:"
ssh "$SSH_HOST" "docker ps --filter name=unroll-web --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}'"
