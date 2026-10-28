#!/usr/bin/env bash
# Creates the deployment VM on Google Compute Engine and provisions it with deploy/provision.sh. Safe to run
# again: existing resources are kept and provisioning is idempotent.
#   PROJECT=<gcp-project-id> deploy/gcp-vm.sh                            VM, network, firewall, provisioning
#   PROJECT=<gcp-project-id> GH_RUNNER_TOKEN=<token> deploy/gcp-vm.sh    ... and register the GitHub runner
# Optional: ZONE (us-central1-a), VM_NAME (rf-vm), MACHINE_TYPE (e2-medium).
set -euo pipefail

PROJECT="${PROJECT:?set PROJECT to the GCP project id}"
ZONE="${ZONE:-us-central1-a}"
NAME="${VM_NAME:-rf-vm}"
MACHINE_TYPE="${MACHINE_TYPE:-e2-medium}"   # 4 GB: two JVMs, Postgres, RabbitMQ, nginx and the runner
NETWORK=rf-net
SA="rf-vm-sa@$PROJECT.iam.gserviceaccount.com"
cd "$(dirname "$0")/.."
gc() { gcloud --project "$PROJECT" --quiet "$@"; }

gc services enable compute.googleapis.com iam.googleapis.com

# The VM's own identity: it may only read itself and set its labels (deploy.sh labels the running version).
perms=compute.instances.get,compute.instances.setLabels,compute.zoneOperations.get
if gc iam roles describe rfVmLabeler > /dev/null 2>&1; then
    gc iam roles update rfVmLabeler --permissions "$perms" > /dev/null
else
    gc iam roles create rfVmLabeler --title "RF VM labeler" --permissions "$perms" > /dev/null
fi
if ! gc iam service-accounts describe "$SA" > /dev/null 2>&1; then
    gc iam service-accounts create rf-vm-sa --display-name "RF Alert Manager VM"
fi
# ... and the Ops Agent on it may write logs and metrics.
for role in "projects/$PROJECT/roles/rfVmLabeler" roles/logging.logWriter roles/monitoring.metricWriter; do
    gc projects add-iam-policy-binding "$PROJECT" --member "serviceAccount:$SA" --role "$role" \
        --condition None > /dev/null
done

# A static address, so the VM keeps its IP across stop/start.
gc compute addresses describe "$NAME-ip" --region "${ZONE%-*}" > /dev/null 2>&1 \
    || gc compute addresses create "$NAME-ip" --region "${ZONE%-*}"

# Own network instead of "default" (which also opens RDP): only SSH and HTTP come in.
if ! gc compute networks describe "$NETWORK" > /dev/null 2>&1; then
    gc compute networks create "$NETWORK" --subnet-mode=auto
    gc compute firewall-rules create "$NETWORK-allow-ssh" --network "$NETWORK" --allow tcp:22
    gc compute firewall-rules create "$NETWORK-allow-http" --network "$NETWORK" --allow tcp:80
fi

if ! gc compute instances describe "$NAME" --zone "$ZONE" > /dev/null 2>&1; then
    gc compute instances create "$NAME" --zone "$ZONE" --machine-type "$MACHINE_TYPE" --network "$NETWORK" \
        --address "$NAME-ip" --service-account "$SA" --scopes compute-rw,logging-write,monitoring-write \
        --image-family ubuntu-2404-lts-amd64 --image-project ubuntu-os-cloud \
        --boot-disk-size 20GB --boot-disk-type pd-balanced
    echo "Waiting for SSH..."
    until gc compute ssh "$NAME" --zone "$ZONE" --command true > /dev/null 2>&1; do sleep 5; done
fi

# Provision over SSH, so the runner token is never stored in instance metadata.
gc compute scp deploy/provision.sh "$NAME:/tmp/provision.sh" --zone "$ZONE"
gc compute ssh "$NAME" --zone "$ZONE" --command \
    "sudo GH_RUNNER_TOKEN='${GH_RUNNER_TOKEN:-}' GH_REPO_URL='${GH_REPO_URL:-https://github.com/ofirvard/TriariiHomeProject}' bash /tmp/provision.sh"

ip=$(gc compute instances describe "$NAME" --zone "$ZONE" --format 'value(networkInterfaces[0].accessConfigs[0].natIP)')
echo "VM $NAME is ready: http://$ip (the stack is deployed by the pipeline)"
