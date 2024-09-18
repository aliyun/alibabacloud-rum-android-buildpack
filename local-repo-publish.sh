#!/bin/bash
set -o pipefail
set -e

# parser version from build.gradle file
VERSION=$(grep "version =" build.gradle | sed -E "s/.*version = '([^']+)'.*/\1/")
echo "Version: $VERSION"

M2_REPO_PLUGIN_PREFIX="$HOME/.m2/repository/com/aliyun/rum/alibabacloud-android-rum-plugin/${VERSION}/alibabacloud-android-rum-plugin-${VERSION}"
M2_REPO_SDK_PREFIX="$HOME/.m2/repository/com/aliyun/rum/alibabacloud-android-rum-sdk/${VERSION}/alibabacloud-android-rum-sdk-${VERSION}"
WORKING_DIR="$(pwd)/build"
LIBS_DIR="$WORKING_DIR/libs"
REPO_DIR="$WORKING_DIR/repo/com/aliyun/rum/alibabacloud-android-rum-plugin"

# setup env first
if [ -d "$WORKING_DIR" ]; then
  rm -rf "$WORKING_DIR"
fi
mkdir -p "$WORKING_DIR"
mkdir -p "$LIBS_DIR"
mkdir -p "$REPO_DIR"
mkdir -p "$REPO_DIR/$VERSION"

echo "work dir: $WORKING_DIR"
echo "libs dir: $LIBS_DIR"
echo "repo dir: $REPO_DIR"
echo "repo dir: $REPO_DIR"
echo "plugin sdk prefix: $M2_REPO_PLUGIN_PREFIX"
echo "sdk prefix: $M2_REPO_SDK_PREFIX"

# create plugin repo
pushd "$REPO_DIR"
touch maven-metadata-local.xml
touch maven-metadata-local.xml.md5
touch maven-metadata-local.xml.sha1
cd "$VERSION"
cp "$M2_REPO_PLUGIN_PREFIX.jar" .
cp "$M2_REPO_PLUGIN_PREFIX.jar.asc" .
cp "$M2_REPO_PLUGIN_PREFIX.pom" .
cp "$M2_REPO_PLUGIN_PREFIX.pom.asc" .
popd

# create jar & jni lib
pushd "$LIBS_DIR"
unzip -o "$M2_REPO_SDK_PREFIX.aar" -d out
cp "out/classes.jar" "alibabacloud-android-rum-sdk.jar"
cp "out/libs/com.openrum.sdk.jar" "com.openrum.sdk.jar"
cp -r "out/jni/"* .
rm -rf out
popd

# zip archive
pushd "$WORKING_DIR"
zip -r alibabacloud-rum-android-sdk.zip .
popd



