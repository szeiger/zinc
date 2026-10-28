#!/bin/bash

set -eo pipefail

BASE_VERSION="1.10.8"
PATCHLEVEL="4"

TEMP_REPO=$(mktemp -d)
BUILD_VERSION="$BASE_VERSION-bin-db-$PATCHLEVEL-`git rev-parse --short HEAD`"

# Our changes are limited to zinc-core. We don't need to publish the rest.

echo "******************** Publishing $BUILD_VERSION to $TEMP_REPO"
sbt "set ThisBuild / version := \"$BUILD_VERSION\"" "set ThisBuild / publishTo := Some(\"temp\" at \"file:$TEMP_REPO\")" zincCore2_13/clean zincCore2_13/publish

echo "******************** Artifacts in $TEMP_REPO"
ls -lR $TEMP_REPO

echo "******************** Publishing $FULL_VERSION to S3"
(cd ~/universe; ./bazel/artifacts/resolver/publish.py --repo $TEMP_REPO "org.scala-sbt:zinc-core_2.13:$BUILD_VERSION")

rm -rf $TEMP_REPO
