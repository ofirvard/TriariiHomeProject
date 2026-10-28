#!/usr/bin/env bash
# Shows the running version as the VM's app-version label in the GCP console. Does nothing off GCP.
#   deploy/label-version.sh <version>       e.g. v0.4.0 (shown as v0-4-0: label values allow no dots)
#   deploy/label-version.sh <commit SHA>    shown as the version tagged on it, or "unreleased" until Verify tags it
set -euo pipefail

version="${1:?usage: label-version.sh <version | commit SHA>}"
if [[ "$version" != v* ]]; then
    version=$(git ls-remote --tags "${GH_REPO_URL:-https://github.com/ofirvard/TriariiHomeProject}" \
        | awk -v sha="$version" '$1 == sha { sub("refs/tags/", "", $2); sub(/\^\{\}$/, "", $2); print $2 }' \
        | tail -1)
    version="${version:-unreleased}"
fi

md() { curl -fsS --max-time 2 -H Metadata-Flavor:Google "http://metadata.google.internal/computeMetadata/v1/instance/$1"; }
zone=$(md zone 2> /dev/null) || exit 0
gcloud compute instances add-labels "$(md name)" --zone "${zone##*/}" --labels "app-version=${version//./-}" --quiet
