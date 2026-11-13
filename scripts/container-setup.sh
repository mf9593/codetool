#!/bin/bash
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DEBIAN_FRONTEND=noninteractive
export HOME=/root
cd /root
echo "=== setup start $(date) ==="
apt-get update -y
apt-get install -y --no-install-recommends \
  procps nano vim git curl wget ca-certificates \
  python3 python3-pip python3-venv \
  openssh-client rsync tar gzip bzip2 xz-utils \
  build-essential pkg-config file less \
  dnsutils net-tools iproute2
echo "=== setup done $(date) ==="
