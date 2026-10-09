# DontFall Yocto/OpenSTLinux (scarthgap) build container.
#
# Ubuntu 22.04 is the distro ST validates the OpenSTLinux 6.x ecosystem
# against. Build (usually via init.sh):
#
#   docker build -t dontfall-yocto \
#       --build-arg UID=$(id -u) --build-arg GID=$(id -g) .
FROM ubuntu:22.04

ARG UID=1000
ARG GID=1000
ARG USERNAME=yocto

ENV DEBIAN_FRONTEND=noninteractive

# Yocto scarthgap host essentials + OpenSTLinux/kernel extras + wic tooling.
# (qemu graphics deps like libsdl1.2-dev are intentionally left out.)
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential chrpath cpio debianutils diffstat file gawk gcc git \
        iputils-ping locales lz4 socat texinfo unzip wget xz-utils zstd acl \
        python3 python3-distutils python3-git python3-jinja2 python3-pexpect \
        python3-subunit python-is-python3 \
        bc bison flex libssl-dev libncurses-dev libyaml-dev \
        parted dosfstools mtools rsync \
        ca-certificates curl sudo xterm \
    && rm -rf /var/lib/apt/lists/*

# repo(1) launcher - upstream's recommended install, stays current
RUN curl -fsSL https://storage.googleapis.com/git-repo-downloads/repo \
        -o /usr/local/bin/repo \
    && chmod a+rx /usr/local/bin/repo

# BitBake requires a UTF-8 locale
RUN locale-gen en_US.UTF-8
ENV LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8

# BitBake refuses to run as root. UID/GID must match the host user so files
# written to the bind-mounted workspace keep the right ownership - pass
# --build-arg UID=$(id -u) GID=$(id -g) when they differ from 1000.
RUN getent group "$GID" >/dev/null || groupadd -g "$GID" "$USERNAME"; \
    useradd -m -s /bin/bash -u "$UID" -g "$GID" "$USERNAME"; \
    echo "$USERNAME ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/$USERNAME

USER $USERNAME
WORKDIR /home/$USERNAME

# Load the bitbake environment before running any command (see script).
COPY --chmod=755 docker-entrypoint.sh /usr/local/bin/df-entrypoint
ENTRYPOINT ["/usr/local/bin/df-entrypoint"]
CMD ["bash"]
