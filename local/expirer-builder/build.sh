#!/usr/bin/env bash
# Builds the reservation expirer Lambda zip inside the one-shot `expirer-builder` compose service,
# so evaluators do not need a local JDK. The zip lands in the shared volume read by aws-bootstrap.
#
# Mode is chosen from the mounted source (EXPIRER_SRC_DIR on the host, /src in the container):
#   - /src/gradlew exists           -> real build: ./gradlew buildLambdaZip
#   - /src/PLACEHOLDER marker exists -> compiles a dependency-free stub handler (validation only)
set -euo pipefail

readonly SRC_DIR="/src"
readonly WORK_DIR="/work"
readonly DIST_DIR="/dist"
readonly ZIP_NAME="ms-reservation-expirer.zip"
readonly GRADLE_ZIP_PATH="applications/app-service/build/distributions/${ZIP_NAME}"

log() {
  printf '[expirer-builder] %s\n' "$*"
}

fail() {
  printf '[expirer-builder] ERROR: %s\n' "$*" >&2
  exit 1
}

# Builds in a private copy so the host checkout never gets root-owned build/ or .gradle/ dirs.
copy_sources() {
  rm -rf "${WORK_DIR}"
  mkdir -p "${WORK_DIR}"
  tar -C "${SRC_DIR}" --exclude='./build' --exclude='*/build' --exclude='./.gradle' -cf - . |
    tar -C "${WORK_DIR}" -xf -
}

build_with_gradle() {
  log "building with Gradle (cache: ${GRADLE_USER_HOME:-~/.gradle})"
  copy_sources
  (cd "${WORK_DIR}" && ./gradlew --no-daemon --console=plain buildLambdaZip)
  [[ -f "${WORK_DIR}/${GRADLE_ZIP_PATH}" ]] || fail "expected ${GRADLE_ZIP_PATH} after buildLambdaZip"
  install -m 0644 "${WORK_DIR}/${GRADLE_ZIP_PATH}" "${DIST_DIR}/${ZIP_NAME}"
}

build_placeholder() {
  log "PLACEHOLDER mode: compiling a stub handler (not the real expirer)"
  copy_sources
  local classes="${WORK_DIR}/classes"
  mkdir -p "${classes}"
  find "${WORK_DIR}" -name '*.java' -print0 | xargs -0 javac --release 25 -d "${classes}"
  rm -f "${DIST_DIR}/${ZIP_NAME}"
  jar --create --file "${DIST_DIR}/${ZIP_NAME}" -C "${classes}" .
  chmod 0644 "${DIST_DIR}/${ZIP_NAME}"
}

main() {
  [[ -d "${SRC_DIR}" ]] || fail "source directory ${SRC_DIR} is not mounted"
  mkdir -p "${DIST_DIR}"
  if [[ -x "${SRC_DIR}/gradlew" || -f "${SRC_DIR}/gradlew" ]]; then
    build_with_gradle
  elif [[ -f "${SRC_DIR}/PLACEHOLDER" ]]; then
    build_placeholder
  else
    fail "no gradlew (or PLACEHOLDER marker) in EXPIRER_SRC_DIR; check the mounted directory"
  fi
  log "artifact ready: ${DIST_DIR}/${ZIP_NAME} ($(du -h "${DIST_DIR}/${ZIP_NAME}" | cut -f1))"
}

main "$@"
