#!/bin/sh
set -o pipefail
set -e

./gradlew clean
./gradlew assembleRelease
./gradlew publishToMavenLocal