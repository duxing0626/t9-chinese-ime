#!/bin/bash
# Downloads the source dictionaries into data/raw (pinned versions), for tools/build_data.py.
set -euo pipefail
cd "$(dirname "$0")/.."

CANTONESE=259f0e48bba840c3a2e0d117539e96937f3d89bc   # rime/rime-cantonese
CANGJIE5=e4a4242f518ab0d34066bbd8aa44cb0cefd61db2     # Jackchows/Cangjie5
STROKE=1e8fff9b9494ddec23b0cbc526bcfd8171a6fd48       # rime/rime-stroke
CLDR=b5a68e61ac365706856ea8796585783579c9a3ff         # unicode-org/cldr
EMOJI=18.0.0                                         # Unicode version for emoji-test.txt

GH=https://raw.githubusercontent.com
mkdir -p data/raw
cd data/raw
fetch() { curl -fsSL -o "$1" "$2"; echo "  $1"; }

fetch essay-cantonese.txt          $GH/rime/rime-cantonese/$CANTONESE/essay-cantonese.txt
fetch jyut6ping3.chars.dict.yaml   $GH/rime/rime-cantonese/$CANTONESE/jyut6ping3.chars.dict.yaml
fetch jyut6ping3.words.dict.yaml   $GH/rime/rime-cantonese/$CANTONESE/jyut6ping3.words.dict.yaml
fetch jyut6ping3.phrase.dict.yaml  $GH/rime/rime-cantonese/$CANTONESE/jyut6ping3.phrase.dict.yaml
fetch Cangjie5_HK.txt              $GH/Jackchows/Cangjie5/$CANGJIE5/Cangjie5_HK.txt
fetch stroke.dict.yaml             $GH/rime/rime-stroke/$STROKE/stroke.dict.yaml
fetch cldr-yue.xml                 $GH/unicode-org/cldr/$CLDR/common/annotations/yue.xml
fetch cldr-zh_Hant.xml             $GH/unicode-org/cldr/$CLDR/common/annotations/zh_Hant.xml
fetch emoji-test.txt               https://unicode.org/Public/$EMOJI/emoji/emoji-test.txt
