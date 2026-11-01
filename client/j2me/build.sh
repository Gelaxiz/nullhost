#!/bin/bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NULLHOST_ROOT="$(cd "$DIR/../.." && pwd)"
TOOLS_DIR="$NULLHOST_ROOT/tools"
CLIENT_DIR="$NULLHOST_ROOT/client"
J2ME_DIR="$CLIENT_DIR/j2me"
BUILD_TMP="/tmp/j2me_build_$$"

mkdir -p "$BUILD_TMP/classes"

cat << 'MANIFEST_EOF' > "$BUILD_TMP/Manifest.mf"
Manifest-Version: 1.0
MIDlet-Name: Nullhost
MIDlet-Version: 1.9.8
MIDlet-Vendor: Gelaxiz
MIDlet-Icon: /icon.png
MIDlet-1: Nullhost, /icon.png, org.gelaxiz.nullhost.NullhostMIDlet
MIDlet-Description: Anonymous file & photo hosting with storage explorer
MicroEdition-Configuration: CLDC-1.1
MicroEdition-Profile: MIDP-2.0
MIDlet-Permissions-Opt: javax.microedition.io.Connector.http, javax.microedition.io.Connector.file.read, javax.microedition.io.Connector.file.write
MANIFEST_EOF

echo "==> Compiling Java sources with javac (Java 1.4 target)..."
"$TOOLS_DIR/jdk8/bin/javac" -source 1.4 -target 1.4 \
  -cp "$TOOLS_DIR/microemulator.jar:$TOOLS_DIR/microemu-jsr75.jar" \
  -d "$BUILD_TMP/classes" \
  "$J2ME_DIR/src/"*.java

cp "$J2ME_DIR/res/icon.png" "$BUILD_TMP/classes/"

echo "==> Creating initial JAR..."
"$TOOLS_DIR/jdk8/bin/jar" cfm "$BUILD_TMP/raw_nullhost.jar" "$BUILD_TMP/Manifest.mf" -C "$BUILD_TMP/classes" .

echo "==> Preverifying with ProGuard (CLDC 1.1 StackMap generation)..."
rm -f "$CLIENT_DIR/Nullhost.jar"
"$TOOLS_DIR/jdk8/bin/java" -cp "$TOOLS_DIR/proguard.jar" proguard.ProGuard \
  -injars "$BUILD_TMP/raw_nullhost.jar" \
  -outjars "$CLIENT_DIR/Nullhost.jar" \
  -libraryjars "$TOOLS_DIR/jdk8/jre/lib/rt.jar" \
  -libraryjars "$TOOLS_DIR/microemulator.jar" \
  -libraryjars "$TOOLS_DIR/microemu-jsr75.jar" \
  -microedition \
  -target 1.4 \
  -dontshrink \
  -dontoptimize \
  -dontobfuscate \
  -dontwarn \
  -keep "public class * extends javax.microedition.midlet.MIDlet"

JAR_SIZE=$(stat -c%s "$CLIENT_DIR/Nullhost.jar")
echo "==> Generated Nullhost.jar size: $JAR_SIZE bytes"

sed -i "s/^MIDlet-Version:.*/MIDlet-Version: 1.9.8/" "$CLIENT_DIR/Nullhost.jad"
sed -i "s/^MIDlet-Jar-Size:.*/MIDlet-Jar-Size: $JAR_SIZE/" "$CLIENT_DIR/Nullhost.jad"

rm -rf "$BUILD_TMP"
echo "==> Build complete and verified for Nokia S60 / Symbian^3 / S40!"
