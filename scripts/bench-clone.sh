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
clone mall-swarm         macrozheng/mall-swarm                                  04c442fe318356bae4445a109dde7af297ce1e84 # master, Apache-2.0
clone paascloud-master   paascloud/paascloud-master                             781281a9503332ed3cef44ea618349d14230a127 # master, Apache-2.0
clone pig                pig-mesh/pig                                           a0d273afd3adc6678b065c0f2d25433541343ad7 # master, Apache-2.0
clone ftgo-application   microservices-patterns/ftgo-application                558dfc53b11d30a5f1d995c0c6d58d5106c28189 # master, LICENSE.md
clone shopizer           shopizer-ecommerce/shopizer                            6a4a0a65a3408ee8f62597b51d1b3aac24b77dee # 3.2.7, Apache-2.0
clone jeecgboot          jeecgboot/JeecgBoot                                    4998e645db547ff614b6568df3895bf5586effbf # main, Apache-2.0
clone metersphere        metersphere/metersphere                                53df526e2fb08bdf933662acf7eb5ae0f1bd5e3a # v3.x, LICENSE
clone thingsboard        thingsboard/thingsboard                                4dc94037fa1f58e87e028cf9480514a6ea0ce565 # master, LICENSE
