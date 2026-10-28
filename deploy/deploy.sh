#!/usr/bin/env bash
# Deploys one image tag on the VM. Run by the runner from a checkout of the commit being deployed.
#   deploy/deploy.sh <tag>          deploy that tag (commit SHA or version, e.g. v0.3.0)
#   deploy/deploy.sh --previous     go back to the tag that ran before the last deploy
# The running tag is IMAGE_TAG in .env; the one before it is kept in previous-tag for rollback.
set -euo pipefail

DEPLOY_DIR="${DEPLOY_DIR:-/opt/rf-alert-manager}"
REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$DEPLOY_DIR" || exit 1

current=$(sed -n 's/^IMAGE_TAG=//p' .env)
if [[ "${1:-}" == "--previous" ]]; then
    [[ -s previous-tag ]] || { echo "No previous deployment to roll back to" >&2; exit 1; }
    tag=$(cat previous-tag)
else
    tag="${1:?usage: deploy.sh <tag> | --previous}"
fi

# The compose file and nginx config come from the checkout, so they always match the code being deployed.
mkdir -p deploy
cp "$REPO_DIR/docker-compose.yml" docker-compose.yml
cp "$REPO_DIR/deploy/nginx.conf" deploy/nginx.conf

echo "Deploying $tag (running: ${current:-none})"
sed -i "s/^IMAGE_TAG=.*/IMAGE_TAG=$tag/" .env
[[ "$current" != "$tag" ]] && echo "$current" > previous-tag

docker compose pull --quiet
docker compose up -d --no-build --wait --remove-orphans
docker image prune -f > /dev/null   # old tags stay in the registry; keep the disk clean
echo "Deployed $tag"

# On GCP, show the running version as the VM's app-version label (label values allow no dots).
md() { curl -fsS --max-time 2 -H Metadata-Flavor:Google "http://metadata.google.internal/computeMetadata/v1/instance/$1"; }
if zone=$(md zone 2> /dev/null); then
    gcloud compute instances add-labels "$(md name)" --zone "${zone##*/}" --labels "app-version=${tag//./-}" \
        --quiet || echo "Could not set the app-version label" >&2
fi
