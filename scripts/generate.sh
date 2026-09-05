#!/bin/bash

# Regenerates the wire layer from the PINNED spec in spec/openapi.yaml.
#
# The generator runs in its official container, so nothing has to be installed
# locally, and it reads the committed spec rather than a URL, so the build is
# reproducible and offline. Refresh the spec with scripts/download-spec.sh, run
# this, and commit both together so a reviewer sees which spec produced which
# client.
#
# The output is COMMITTED: the generator is a container rather than a build
# dependency, so leaving it out of the tree would mean `mvn test` on a fresh
# clone needed docker and a 435 MB image pull.

set -euo pipefail

cd "$(dirname "$0")/.."

GENERATOR_IMAGE="${GENERATOR_IMAGE:-openapitools/openapi-generator-cli:v7.25.0}"

# v2 ONLY. The published spec also carries /api/v1/*, which is a different
# credential vocabulary (`?apikey=<uuid>`) kept alive for a handful of named
# customers; a public client library that offered it would invite everyone else
# onto a contract they cannot get a key for. The whole spec is still pinned, so
# the diff shows what the API published, and this list is what is built from it.
OPERATIONS="listDatabases|downloadDatabaseV2|databaseMetadataV2|databaseChecksumV2|listDownloads"

# `Error` shadows java.lang.Error in any file that imports it, and `Db*` is v1's
# spelling of a noun v2 writes out in full.
RENAMES="DbChecksums=DatabaseChecksums,Error=ErrorBody"

# The three wrapper schemas are inline in the spec, so the generator names them
# after the operation and status code (DatabaseChecksumV2200Response). Only
# --inline-schema-name-mappings reaches an inline schema, and it is keyed by the
# generator's own placeholder rather than by the Java name.
INLINE="listDatabases_200_response=DatabaseList"
INLINE="${INLINE},listDownloads_200_response=DownloadList"
INLINE="${INLINE},databaseChecksumV2_200_response=DatabaseChecksumsResponse"

# Filtering the OPERATIONS does not filter the models: every schema under
# components/ is generated whether an operation reaches it or not, so v1's eight
# would ship as dead classes on a consumer's classpath. This allowlist is keyed
# by the schema's name IN THE SPEC, not by the mapped Java name above - an
# already-renamed entry here silently generates nothing.
MODELS="Database:DatabaseVersion:DatabaseMetadata:DatabaseMetadataColumn"
MODELS="${MODELS}:DbChecksums:Download:Error"
MODELS="${MODELS}:DatabaseList:DownloadList:DatabaseChecksumsResponse"

# openApiNullable=false keeps org.openapitools:jackson-databind-nullable off a
# consumer's classpath. Every `nullable: true` field here is one where null and
# absent mean the same thing (no license, no end date, an unresolved key).
PROPS="groupId=io.internetdata,artifactId=internetdata"
PROPS="${PROPS},invokerPackage=io.internetdata.internal"
PROPS="${PROPS},apiPackage=io.internetdata.api"
PROPS="${PROPS},modelPackage=io.internetdata.model"
PROPS="${PROPS},hideGenerationTimestamp=true,openApiNullable=false"

rm -rf .gen
mkdir -p .gen

docker run --rm \
    -v "$PWD/spec:/spec:ro" \
    -v "$PWD/.gen:/out" \
    "$GENERATOR_IMAGE" generate \
    -i /spec/openapi.yaml \
    -g java --library native \
    -o /out \
    --openapi-normalizer "FILTER=operationId:${OPERATIONS}" \
    --model-name-mappings "$RENAMES" \
    --inline-schema-name-mappings "$INLINE" \
    --global-property "models=${MODELS}" \
    --global-property apis \
    --global-property supportingFiles \
    --additional-properties="$PROPS" \
    >/dev/null

# Only the three source packages are taken. The generator also emits its own
# pom.xml, README.md, .gitignore, gradle wrapper and CI workflow, all of which
# would overwrite ours if the output were unpacked directly over the repo.
for pkg in internal api model ; do
    rm -rf "src/main/java/io/internetdata/${pkg}"
    cp -R ".gen/src/main/java/io/internetdata/${pkg}" "src/main/java/io/internetdata/${pkg}"
done

rm -rf .gen
echo "regenerated src/main/java/io/internetdata/{internal,api,model} from spec/openapi.yaml"
grep -m1 '^  version:' spec/openapi.yaml
