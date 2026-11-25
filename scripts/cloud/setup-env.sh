#!/bin/bash
# Toolchain check for cloud sessions of this repository (docs/backlog/README.md, "Environment").
#
# Runs by itself: `.claude/settings.json` calls it as a SessionStart hook at the start of every cloud session. It does
# nothing outside the cloud (CLAUDE_CODE_REMOTE != true) and is idempotent (about a second on a second run). The
# prompts of the backlog runs call it again as their first step, in case the hook did not run.
#
# The project needs JDK 21 (Gradle toolchain 21) and Gradle 8.13 from the wrapper. The cloud image already has
# JDK 21, so this script only checks it, points Gradle's toolchain at it (foojay, the toolchain auto-download, is not
# on the allowlist of a Limited environment) and fetches the Gradle distribution once (services.gradle.org is).
# Fallback when the image has no JDK 21: Ubuntu's openjdk-21-jdk-headless (archive.ubuntu.com).
# Docker is optional: without it the two Testcontainers tests skip themselves locally; CI runs them.
set -u

if [ -n "${CLAUDE_PROJECT_DIR:-}" ] && [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi
[ "$(uname -s)" = "Linux" ] || exit 0

LOG=/tmp/setup-env.log
ROOT="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"
SUDO=""; [ "$(id -u)" -ne 0 ] && command -v sudo >/dev/null && SUDO="sudo"

find_jdk21() {
  local j
  for j in "${JAVA_HOME:-}" /usr/lib/jvm/* /opt/jdk*; do
    [ -x "$j/bin/javac" ] || continue
    "$j/bin/java" -version 2>&1 | head -1 | grep -qE '"21[."]' && { echo "$j"; return 0; }
  done
  return 1
}

{
echo "setup-env: start $(date -u +%FT%TZ)"
JDK=$(find_jdk21)
if [ -z "$JDK" ]; then
  echo "setup-env: no JDK 21 in the image, trying apt openjdk-21-jdk-headless"
  $SUDO apt-get update -qq && $SUDO apt-get install -y -qq openjdk-21-jdk-headless || true
  JDK=$(find_jdk21)
fi
echo "setup-env: JDK 21 = ${JDK:-MISSING}"

if [ -n "$JDK" ]; then
  mkdir -p "$HOME/.gradle"
  cat > "$HOME/.gradle/gradle.properties" <<EOF
org.gradle.java.installations.paths=$JDK
org.gradle.java.installations.auto-download=false
EOF
  # Fetch the wrapper's Gradle distribution once; later runs find it in ~/.gradle/wrapper.
  if [ ! -d "$HOME/.gradle/wrapper/dists" ] || [ -z "$(ls "$HOME/.gradle/wrapper/dists" 2>/dev/null)" ]; then
    (cd "$ROOT" && JAVA_HOME="$JDK" ./gradlew --version --console=plain) || echo "setup-env: gradle wrapper failed"
  fi
fi
echo "setup-env: done $(date -u +%FT%TZ)"
} >> "$LOG" 2>&1

# Every run (also when everything is already in place): commits made in the cloud carry Aika's name and her GitHub
# no-reply address, not an AI identity (CLAUDE.md: no AI attribution). The attribution trailers themselves are off
# in .claude/settings.json.
GIT_NAME="Aika"
GIT_EMAIL="52049894+Aika8@users.noreply.github.com"
git config --global user.name "$GIT_NAME" 2>/dev/null || true
git config --global user.email "$GIT_EMAIL" 2>/dev/null || true

JDK=$(find_jdk21)
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  { [ -n "$JDK" ] && echo "export JAVA_HOME=$JDK"
    echo "export GIT_AUTHOR_NAME='$GIT_NAME' GIT_AUTHOR_EMAIL='$GIT_EMAIL'"
    echo "export GIT_COMMITTER_NAME='$GIT_NAME' GIT_COMMITTER_EMAIL='$GIT_EMAIL'"; } >> "$CLAUDE_ENV_FILE"
fi
GRADLE=$(ls "$HOME/.gradle/wrapper/dists" 2>/dev/null | head -1)
DOCKER=$(docker info --format '{{.ServerVersion}}' 2>/dev/null | tr -d '\n')
echo "setup-env: java ${JDK:+21 at }${JDK:-MISSING}, git author $(git config --global user.name 2>/dev/null)," \
     "gradle ${GRADLE:-MISSING}," \
     "docker ${DOCKER:-absent} (log $LOG)"
exit 0
