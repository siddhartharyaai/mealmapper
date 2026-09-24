#!/usr/bin/env bash
# Downloads the source tables into tools/fooddb/raw/ (not committed). Then run: python3 tools/fooddb/build.py
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p raw/ifct
INDB=https://raw.githubusercontent.com/lindsayjaacks/Indian-Nutrient-Databank-INDB-/main
for f in INDB.xlsx recipes.xlsx UK_fct.xlsx; do curl -sSfL -o "raw/$f" "$INDB/$f"; done
curl -sSfL https://registry.npmjs.org/@ifct2017/compositions/-/compositions-2.0.9.tgz | tar xz -C raw/ifct --strip-components=1 package/index.csv
echo "Downloaded to $(pwd)/raw"
