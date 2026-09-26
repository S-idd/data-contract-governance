# Ubuntu 24.04 and WSL2 setup

Use an Ubuntu 24.04 x86-64 terminal. If this is WSL2, follow Docker's [WSL 2 backend guide](https://docs.docker.com/desktop/features/wsl/) and enable the Ubuntu distribution under **Settings → Resources → WSL Integration** before running database tests. Do not install a second Docker daemon inside the same WSL distribution when Docker Desktop integration is already enabled.

From the extracted bundle root:

```bash
./scripts/bootstrap-ubuntu.sh --install
sudo apt-get install -y openjdk-21-jdk nodejs npm
java -version
docker version
docker compose version
./scripts/verify-install.sh
./scripts/init-workspace.sh
```

Expected result: Java reports major version 21 and `verify-install.sh` prints `PASS`. The bootstrap installs only ordinary Ubuntu command-line prerequisites. Docker installation is deliberately left to Docker Desktop WSL integration or Docker's official Ubuntu instructions.

The bundle contains Linux x86-64 binaries. It does not run natively on Windows, macOS, Linux ARM64, or WSL on ARM64.

## Native Ubuntu Docker Engine alternative

Use this only when Docker Desktop is not providing Docker to the WSL distribution. These commands follow Docker's [official Ubuntu installation](https://docs.docker.com/engine/install/ubuntu/):

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
sudo tee /etc/apt/sources.list.d/docker.sources >/dev/null <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo docker run --rm hello-world
```

If Docker requires `sudo`, either run the database scripts in a shell configured for Docker's documented non-root access or configure the daemon according to your organization's policy.
