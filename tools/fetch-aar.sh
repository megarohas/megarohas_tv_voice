#!/bin/bash
# Downloads an AAR from Maven Central, checks it against the published SHA-1 and
# unpacks classes.jar + the arm64 native libraries into the given directory.
#   tools/fetch-aar.sh <url-of-.aar> <dir>
set -euo pipefail
url=$1
dir=$2
aar="libs/$(basename "$url")"
mkdir -p libs "$dir"
curl -fsSL -o "$aar" "$url"
want=$(curl -fsSL "$url.sha1" | cut -c1-40)
have=$(shasum "$aar" | cut -d' ' -f1)
if [ "$want" != "$have" ]; then
  echo "SHA-1 mismatch for $aar: $have, Maven says $want" >&2
  rm -f "$aar"
  exit 1
fi
(cd "$dir" && unzip -oq "../$(basename "$aar")" classes.jar 'jni/arm64-v8a/*')
