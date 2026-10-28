#!/usr/bin/env bash
# Provisions the deployment VM (Ubuntu 24.04). Run as root by deploy/gcp-vm.sh; safe to run again.
#   - Docker Engine + compose plugin, started on boot, container logs capped
#   - host firewall: only SSH and HTTP (the GCP firewall allows the same)
#   - Google Cloud Ops Agent: logs (including every container's) and metrics in Cloud Logging / Monitoring
#   - /opt/rf-alert-manager with a .env of passwords generated here (never in the repository)
#   - a GitHub Actions runner as a service, which runs the Deploy, Verify and Rollback jobs
set -euo pipefail

DEPLOY_DIR=/opt/rf-alert-manager
RUNNER_USER=runner
RUNNER_DIR=/home/$RUNNER_USER/actions-runner
export DEBIAN_FRONTEND=noninteractive

# Docker Engine from Docker's apt repository.
if ! command -v docker > /dev/null; then
    apt-get update -q
    apt-get install -yq ca-certificates curl git ufw
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc]" \
        "https://download.docker.com/linux/ubuntu $(sed -n "s/^VERSION_CODENAME=//p" /etc/os-release) stable" \
        > /etc/apt/sources.list.d/docker.list
    apt-get update -q
    apt-get install -yq docker-ce docker-ce-cli containerd.io docker-compose-plugin
fi

# Bounded logs: every container (compose sets the same limit) and the journal.
cat > /etc/docker/daemon.json <<'EOF'
{ "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3", "labels": "com.docker.compose.service" } }
EOF
mkdir -p /etc/systemd/journald.conf.d
printf '[Journal]\nSystemMaxUse=200M\n' > /etc/systemd/journald.conf.d/size.conf
systemctl restart systemd-journald
systemctl enable docker
systemctl restart docker

# Google Cloud Ops Agent: host metrics and logs, plus every container's log, in Cloud Logging. The compose
# service name is added to each container log line (json-file "labels" option above).
if ! dpkg -s google-cloud-ops-agent > /dev/null 2>&1; then
    curl -fsSL https://dl.google.com/cloudagents/add-google-cloud-ops-agent-repo.sh | bash -s -- --also-install
fi
cat > /etc/google-cloud-ops-agent/config.yaml <<'EOF'
logging:
  receivers:
    docker:
      type: files
      include_paths: [/var/lib/docker/containers/*/*.log]
  processors:
    docker_json:
      type: parse_json
  service:
    pipelines:
      docker:
        receivers: [docker]
        processors: [docker_json]
EOF
systemctl restart google-cloud-ops-agent

# Only SSH and HTTP. Docker publishes ports past ufw, but only nginx publishes one.
ufw allow 22/tcp
ufw allow 80/tcp
ufw --force enable

# The user the runner runs as; it may use Docker but has no sudo.
id "$RUNNER_USER" > /dev/null 2>&1 || useradd -m -s /bin/bash "$RUNNER_USER"
usermod -aG docker "$RUNNER_USER"

# Deployment directory. Secrets are generated once, on the VM.
mkdir -p "$DEPLOY_DIR"
if [[ ! -f "$DEPLOY_DIR/.env" ]]; then
    cat > "$DEPLOY_DIR/.env" <<EOF
DB_USER=rf
DB_PASSWORD=$(openssl rand -hex 16)
RABBITMQ_USER=rf
RABBITMQ_PASSWORD=$(openssl rand -hex 16)
IMAGE_TAG=latest
EOF
    chmod 600 "$DEPLOY_DIR/.env"
fi
chown -R "$RUNNER_USER:$RUNNER_USER" "$DEPLOY_DIR"

# GitHub Actions runner. It connects out to GitHub, so the VM needs no inbound port for CI/CD.
if [[ -f "$RUNNER_DIR/.runner" ]]; then
    echo "Runner already registered"
elif [[ -z "${GH_RUNNER_TOKEN:-}" ]]; then
    echo "GH_RUNNER_TOKEN not set: runner not registered. Re-run: GH_RUNNER_TOKEN=<token> deploy/gcp-vm.sh"
else
    version=$(curl -fsSL https://api.github.com/repos/actions/runner/releases/latest \
        | sed -nE 's/.*"tag_name": *"v([^"]+)".*/\1/p')
    sudo -u "$RUNNER_USER" mkdir -p "$RUNNER_DIR"
    curl -fsSL "https://github.com/actions/runner/releases/download/v$version/actions-runner-linux-x64-$version.tar.gz" \
        | sudo -u "$RUNNER_USER" tar xz -C "$RUNNER_DIR"
    "$RUNNER_DIR/bin/installdependencies.sh"
    sudo -u "$RUNNER_USER" "$RUNNER_DIR/config.sh" --unattended --replace \
        --url "$GH_REPO_URL" --token "$GH_RUNNER_TOKEN" --name rf-vm --labels rf-vm
    (cd "$RUNNER_DIR" && ./svc.sh install "$RUNNER_USER" && ./svc.sh start)
fi

echo "Provisioned. The stack is deployed by the pipeline (or: Actions -> Rollback -> Run workflow)."
