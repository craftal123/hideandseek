#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="${ROOT_DIR}/local-paper-server"
PAPER_VERSION="1.20.6"
PAPER_BUILD="151"
PAPER_JAR="paper-${PAPER_VERSION}-${PAPER_BUILD}.jar"

JAVA_BIN="${JAVA_HOME:-}/bin/java"
if [[ ! -x "${JAVA_BIN}" ]]; then
  JAVA_BIN="java"
fi

mkdir -p "${SERVER_DIR}/plugins"

if [[ ! -f "${SERVER_DIR}/${PAPER_JAR}" ]]; then
  echo "Downloading Paper ${PAPER_VERSION} build ${PAPER_BUILD}..."
  curl -fsSL "https://api.papermc.io/v2/projects/paper/versions/${PAPER_VERSION}/builds/${PAPER_BUILD}/downloads/${PAPER_JAR}" -o "${SERVER_DIR}/${PAPER_JAR}"
fi

pushd "${ROOT_DIR}" >/dev/null
if [[ -x "./gradlew" ]]; then
  ./gradlew clean build
else
  gradle clean build
fi
cp "build/libs/hideandseek-0.1.0.jar" "${SERVER_DIR}/plugins/"
popd >/dev/null

if [[ ! -f "${SERVER_DIR}/eula.txt" ]]; then
  echo "eula=true" > "${SERVER_DIR}/eula.txt"
fi

pushd "${SERVER_DIR}" >/dev/null
"${JAVA_BIN}" -Xms2G -Xmx2G -jar "${PAPER_JAR}" --nogui
popd >/dev/null
