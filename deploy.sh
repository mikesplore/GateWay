#!/usr/bin/env bash
set -euo pipefail

# Configuration
REPO="mikesplore/gateway"
BRANCH="master"
TARGET_DIR="/home/ubuntu/gateway/pro"
JAR_PATH="${TARGET_DIR}/GateWay.jar"
SERVICE_NAME="gateway"
SERVICE_FILE="/etc/systemd/system/${SERVICE_NAME}.service"
ENV_FILE="${TARGET_DIR}/.env"
HEALTH_URL="http://127.0.0.1:8080/api/ready"
HEALTH_RETRIES=12
HEALTH_RETRY_DELAY=5

if ! command -v gh >/dev/null 2>&1; then
  echo "Error: GitHub CLI ('gh') is required to fetch workflow artifacts." >&2
  exit 1
fi

sudo mkdir -p "${TARGET_DIR}"

echo "==> Finding latest successful build on ${BRANCH}..."
LATEST_RUN_ID="$(gh run list \
  --repo "${REPO}" \
  --branch "${BRANCH}" \
  --status success \
  --limit 1 \
  --json databaseId \
  --jq '.[0].databaseId // empty')"

if [ -z "${LATEST_RUN_ID}" ]; then
  echo "Error: No successful workflow run found on ${BRANCH}." >&2
  exit 1
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

echo "==> Downloading artifacts from run ${LATEST_RUN_ID}..."
gh run download "${LATEST_RUN_ID}" --repo "${REPO}" --dir "${TMP_DIR}"

mapfile -t JARS < <(find "${TMP_DIR}" -type f -name '*.jar' -print)
if [ "${#JARS[@]}" -eq 0 ]; then
  echo "Error: No .jar file found in the downloaded artifacts." >&2
  exit 1
elif [ "${#JARS[@]}" -gt 1 ]; then
  echo "Error: Found multiple .jar files; refusing to choose one automatically:" >&2
  printf '  %s\n' "${JARS[@]}" >&2
  exit 1
fi

sudo install -m 755 "${JARS[0]}" "${JAR_PATH}"
echo "==> Updated ${JAR_PATH}"

if [ ! -f "${SERVICE_FILE}" ]; then
  echo "==> Creating systemd service ${SERVICE_FILE}..."
  UNIT_TMP="$(mktemp)"
  trap 'rm -rf "${TMP_DIR}"; rm -f "${UNIT_TMP}"' EXIT
  cat >"${UNIT_TMP}" <<EOF
[Unit]
Description=GateWay payment gateway
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=${TARGET_DIR}
ExecStart=/usr/bin/java -jar ${JAR_PATH}
EnvironmentFile=${ENV_FILE}
Restart=on-failure
RestartSec=5
SuccessExitStatus=143
EOF
  cat >>"${UNIT_TMP}" <<EOF

[Install]
WantedBy=multi-user.target
EOF
  sudo install -m 644 "${UNIT_TMP}" "${SERVICE_FILE}"
elif ! sudo grep -Fqx "EnvironmentFile=${ENV_FILE}" "${SERVICE_FILE}"; then
  echo "==> Adding ${ENV_FILE} to existing systemd service..."
  sudo sed -i "/^\[Service\]/a EnvironmentFile=${ENV_FILE}" "${SERVICE_FILE}"
fi

echo "==> Reloading systemd and enabling/restarting ${SERVICE_NAME}..."
sudo systemctl daemon-reload
sudo systemctl enable "${SERVICE_NAME}"
sudo systemctl restart "${SERVICE_NAME}"
sudo systemctl status "${SERVICE_NAME}" --no-pager

echo "==> Checking Gateway readiness at ${HEALTH_URL}..."
for ((attempt = 1; attempt <= HEALTH_RETRIES; attempt++)); do
  if curl --fail --silent --show-error "${HEALTH_URL}" >/dev/null 2>&1; then
    echo "==> Gateway health check passed."
    break
  fi

  if (( attempt == HEALTH_RETRIES )); then
    echo "Error: Gateway did not become ready after $((HEALTH_RETRIES * HEALTH_RETRY_DELAY)) seconds." >&2
    sudo journalctl -u "${SERVICE_NAME}" -n 50 --no-pager || true
    exit 1
  fi

  echo "    Not ready yet (${attempt}/${HEALTH_RETRIES}); retrying in ${HEALTH_RETRY_DELAY}s..."
  sleep "${HEALTH_RETRY_DELAY}"
done

echo "==> Deployment complete!"
