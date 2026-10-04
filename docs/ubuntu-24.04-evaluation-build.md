# Build and accept the Ubuntu 24.04 evaluation bundle

This is the maintainer sequence for a new Ubuntu 24.04 x86-64 WSL2 installation. It creates new source, build, assembly, extraction, and evidence directories. It never edits an accepted archive. Run one fenced block at a time; do not remove spaces around assignments or command arguments.

The Docker Desktop WSL integration and native Docker Engine approaches are alternatives. For Docker Desktop, enable this Ubuntu distribution under **Settings → Resources → WSL Integration**. Do not install a second daemon in the distribution.

## 1. Host prerequisites

```bash
set -euo pipefail

test "$(uname -s)" = Linux
test "$(uname -m)" = x86_64
grep -qi microsoft /proc/sys/kernel/osrelease
. /etc/os-release
test "$ID" = ubuntu
test "$VERSION_ID" = 24.04

sudo apt-get update
sudo apt-get install -y \
  build-essential ca-certificates clang cmake curl git jq lsof maven \
  nodejs npm openssl pkg-config python3 tar unzip zip libssl-dev

docker version
docker compose version
```

If Docker is not already supplied by Docker Desktop, follow Docker's official Ubuntu Engine instructions before continuing.

## 2. Check out frozen sources in the Linux filesystem

```bash
set -euo pipefail

export SOURCE_ROOT="$HOME/dcg-ubuntu-evaluation-source"
test ! -e "$SOURCE_ROOT"
mkdir -p "$SOURCE_ROOT"

git clone --branch codex/ubuntu-24.04-evaluation-bundle --single-branch \
  https://github.com/S-idd/data-contract-governance.git \
  "$SOURCE_ROOT/data-contract-governance"

git clone https://github.com/S-idd/dcgaimodel.git "$SOURCE_ROOT/dcgaimodel"
git -C "$SOURCE_ROOT/dcgaimodel" checkout --detach \
  32ca579095ed5b91749b8c33999556624e58758f

git clone https://github.com/S-idd/iems.git "$SOURCE_ROOT/iems"
git -C "$SOURCE_ROOT/iems" checkout --detach \
  6ae4970712f6f58150e935d95b71524a7c675d40

test -z "$(git -C "$SOURCE_ROOT/data-contract-governance" status --porcelain --untracked-files=all)"
test -z "$(git -C "$SOURCE_ROOT/dcgaimodel" status --porcelain --untracked-files=all)"
test -z "$(git -C "$SOURCE_ROOT/iems" status --porcelain --untracked-files=all)"

git -C "$SOURCE_ROOT/data-contract-governance" rev-parse HEAD
git -C "$SOURCE_ROOT/dcgaimodel" rev-parse HEAD
git -C "$SOURCE_ROOT/iems" rev-parse HEAD
```

Do not build from `/mnt/c`. The WSL Linux filesystem avoids file-mode and performance problems.

## 3. Install the pinned Temurin JDK and Rust toolchain

```bash
set -euo pipefail

export TOOLCHAIN_ROOT="$HOME/dcg-toolchains"
export JDK_ARCHIVE="$TOOLCHAIN_ROOT/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz"
mkdir -p "$TOOLCHAIN_ROOT/jdk21"

curl --fail --location \
  'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz' \
  --output "$JDK_ARCHIVE"

printf '%s  %s\n' \
  '3623232f33a9c3baadf304480b2535f9a3cba8a58d42ecbb438ba267315d9998' \
  "$JDK_ARCHIVE" | sha256sum --check

tar -xzf "$JDK_ARCHIVE" -C "$TOOLCHAIN_ROOT/jdk21"
export JAVA_HOME="$TOOLCHAIN_ROOT/jdk21/jdk-21.0.12.1+1"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
java -version 2>&1 | grep -F 'Temurin-21.0.12.1+1'

curl --proto '=https' --tlsv1.2 --fail --silent --show-error \
  https://sh.rustup.rs | sh -s -- -y --profile minimal --default-toolchain 1.96.0
source "$HOME/.cargo/env"
rustc +1.96.0 -Vv
cargo +1.96.0 -V
```

## 4. Build the current Linux package and run its 13-check acceptance

```bash
set -euo pipefail

export JAVA_REPO="$SOURCE_ROOT/data-contract-governance"
export RUST_REPO="$SOURCE_ROOT/dcgaimodel"
export BUILD_ROOT="$HOME/dcg-ubuntu-linux-build-$(date -u +%Y%m%dT%H%M%SZ)"

python3 "$JAVA_REPO/scripts/release/build-development-linux-wsl2.py" \
  --java-repo "$JAVA_REPO" \
  --rust-repo "$RUST_REPO" \
  --java-home "$JAVA_HOME" \
  --jdk-archive "$JDK_ARCHIVE" \
  --work-dir "$BUILD_ROOT" \
  --machine-description 'Ubuntu 24.04 x86-64 under WSL2'

jq -e '.status == "PASS" and .reproducibility == "PASS" and .acceptance == "PASS"' \
  "$BUILD_ROOT/workflow-summary.json"

jq -e '.status == "PASS" and (.checks | length == 13) and ([.checks[].status] | all(. == "PASS"))' \
  "$BUILD_ROOT/acceptance/acceptance-report.json"
```

## 5. Build IEMS from its frozen commit

```bash
set -euo pipefail

cd "$JAVA_REPO"
./mvnw -B -ntp -pl contract-validation-spring-boot-starter -am -DskipTests install

cd "$SOURCE_ROOT/iems"
mvn -B -ntp -DskipTests clean package

export IEMS_JAR="$SOURCE_ROOT/iems/target/inclusive-education-management-system-1.0.0-SNAPSHOT.jar"
test -f "$IEMS_JAR"
test -z "$(git -C "$SOURCE_ROOT/iems" status --porcelain --untracked-files=all)"
```

## 6. Assemble the immutable evaluator archive

```bash
set -euo pipefail

export DCG_ACCEPTANCE_REPORT="$BUILD_ROOT/acceptance/acceptance-report.json"
export DCG_ARCHIVE="$BUILD_ROOT/acceptance-handoff/$(jq -r '.archive' "$DCG_ACCEPTANCE_REPORT")"
export EVALUATION_STAGE="$HOME/dcg-ubuntu-evaluation-stage-$(date -u +%Y%m%dT%H%M%SZ)"
export EVALUATION_DIR="$EVALUATION_STAGE/dcg-ubuntu-24.04-evaluation"
export EVALUATION_ARCHIVE="$EVALUATION_STAGE/dcg-ubuntu-24.04-evaluation.tar.gz"

mkdir -p "$EVALUATION_STAGE"

python3 "$JAVA_REPO/scripts/release/assemble-ubuntu-evaluation.py" \
  --dcg-archive "$DCG_ARCHIVE" \
  --dcg-acceptance-report "$DCG_ACCEPTANCE_REPORT" \
  --iems-jar "$IEMS_JAR" \
  --iems-root "$SOURCE_ROOT/iems" \
  --output "$EVALUATION_DIR" \
  --archive "$EVALUATION_ARCHIVE"

cd "$EVALUATION_STAGE"
sha256sum --check dcg-ubuntu-24.04-evaluation.tar.gz.sha256
```

## 7. Simulate a new download, then run the complete acceptance

```bash
set -euo pipefail

export DOWNLOAD_TEST="$HOME/dcg-ubuntu-download-test-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$DOWNLOAD_TEST"
cp "$EVALUATION_ARCHIVE" "$EVALUATION_ARCHIVE.sha256" "$DOWNLOAD_TEST/"

cd "$DOWNLOAD_TEST"
sha256sum --check dcg-ubuntu-24.04-evaluation.tar.gz.sha256
tar -xzf dcg-ubuntu-24.04-evaluation.tar.gz
cd dcg-ubuntu-24.04-evaluation

./scripts/verify-install.sh
export FINAL_EVIDENCE="$PWD/workspace/evidence/ubuntu-wsl2-$(date -u +%Y%m%dT%H%M%SZ)"
python3 scripts/ubuntu-wsl2-acceptance.py --evidence "$FINAL_EVIDENCE"

jq -e '.status == "PASS" and ([.checks[].status] | all(. == "PASS"))' \
  "$FINAL_EVIDENCE/results.json"

ss -ltnp | grep -E ':(8080|8081|8090|54329|33069)\b' && exit 1 || true
```

## 8. Freeze the upload pair and private acceptance evidence

```bash
set -euo pipefail

export FINAL_HANDOFF="$HOME/dcg-ubuntu-24.04-final-handoff"
test ! -e "$FINAL_HANDOFF"
mkdir -p "$FINAL_HANDOFF/private-acceptance-evidence"

cp "$EVALUATION_ARCHIVE" "$EVALUATION_ARCHIVE.sha256" "$FINAL_HANDOFF/"
cp "$BUILD_ROOT/workflow-summary.json" \
  "$BUILD_ROOT/acceptance/acceptance-report.json" \
  "$FINAL_EVIDENCE/results.json" \
  "$FINAL_HANDOFF/private-acceptance-evidence/"

cd "$FINAL_HANDOFF"
sha256sum --check dcg-ubuntu-24.04-evaluation.tar.gz.sha256
sha256sum private-acceptance-evidence/*.json \
  > private-acceptance-evidence/SHA256SUMS
sha256sum --check private-acceptance-evidence/SHA256SUMS
```

Upload only these two files for the guide:

```text
dcg-ubuntu-24.04-evaluation.tar.gz
dcg-ubuntu-24.04-evaluation.tar.gz.sha256
```

Keep `private-acceptance-evidence/` private. It is review evidence, not part of the evaluator download.
