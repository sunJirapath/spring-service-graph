#!/bin/sh
# Re-creates the benchmark clones under bench/ (git-ignored) at pinned commits.
# Idempotent: skips any folder that already exists. Clone/read only; never build these.
# Usage: sh scripts/bench-clone.sh   (run from anywhere)
set -e
B="$(cd "$(dirname "$0")/.." && pwd)/bench"
mkdir -p "$B"
# args: folder repo sha
clone() {
  [ -d "$B/$1" ] && { echo "skip $1"; return; }
  git init -q "$B/$1"
  git -C "$B/$1" config core.longpaths true # Windows: some repos have paths over 260 chars
  git -C "$B/$1" remote add origin "https://github.com/$2"
  git -C "$B/$1" fetch -q --depth 1 origin "$3"
  git -C "$B/$1" checkout -q FETCH_HEAD
}
clone petclinic          spring-petclinic/spring-petclinic-microservices        1d76b00d683e86b62867a6bf59530f8ab301244f # main, LICENSE
clone petclinic-config   spring-petclinic/spring-petclinic-microservices-config 323993ce2519c6d02df63e08bf4458d123d3b611 # main, LICENSE
clone piggymetrics       sqshq/piggymetrics                                     6bb2cf9ddbca980b664d3edbb6ff775d75369278 # master, LICENCE
clone microservice-kafka ewolff/microservice-kafka                              103de22da497c631708f0541a0cad0e662d04aa5 # master, LICENSE
# messy real-world code bases (field-test style; see docs/benchmark.md)
clone yudao-cloud        YunaiV/yudao-cloud                                     0757f0597e44357d5f2777a2f9e1370cc1534d5f # master, MIT
clone dolphinscheduler   apache/dolphinscheduler                                c70003c2aa325a143e033e6b753ac7de30ec31a9 # dev, Apache-2.0
clone fineract           apache/fineract                                        71681f2d8828f377e7a5b93dba3721384db1daaf # develop, Apache-2.0
