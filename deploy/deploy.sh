#!/usr/bin/env bash
# Deploys one image tag on the VM. Run by the runner from a checkout of the commit being deployed.
#   deploy/deploy.sh <tag>          deploy that tag (commit SHA or version, e.g. v0.3.0)
#   deploy/deploy.sh --previous     go back to the last healthy deployment before the running one
# The running tag is IMAGE_TAG in .env. history lists the tags that came up healthy, newest last; a rollback
# walks back through it, so a failed build never becomes a rollback target.
set -euo pipefail

DEPLOY_DIR="${DEPLOY_DIR:-/opt/rf-alert-manager}"
REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$DEPLOY_DIR" || exit 1

current=$(sed -n 's/^IMAGE_TAG=//p' .env)
touch history
if [[ "${1:-}" == "--previous" ]]; then
    # A running tag that came up healthy is the newest entry: drop it. One that never did (a failed deploy)
    # is not in history, so the newest entry is the deployment to return to.
    [[ "$(tail -1 history)" == "$current" ]] && sed -i '$d' history
    tag=$(tail -1 history)
    [[ -n "$tag" ]] || { echo "No previous deployment to roll back to" >&2; exit 1; }
else
    tag="${1:?usage: deploy.sh <tag> | --previous}"
fi

# The compose file and nginx config come from the checkout, so they always match the code being deployed.
mkdir -p deploy
cp "$REPO_DIR/docker-compose.yml" docker-compose.yml
cp "$REPO_DIR/deploy/nginx.conf" deploy/nginx.conf

echo "Deploying $tag (running: ${current:-none})"
sed -i "s/^IMAGE_TAG=.*/IMAGE_TAG=$tag/" .env

docker compose pull --quiet
docker compose up -d --no-build --wait --remove-orphans
if [[ "$(tail -1 history)" != "$tag" ]]; then
    echo "$tag" >> history
    tail -n 20 history > history.new && mv history.new history
fi
docker image prune -f > /dev/null   # old tags stay in the registry; keep the disk clean
echo "Deployed $tag"
bash "$REPO_DIR/deploy/label-version.sh" "$tag" || echo "Could not set the app-version label" >&2
