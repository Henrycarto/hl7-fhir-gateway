FROM mcr.microsoft.com/devcontainers/java:1-21-bookworm
# The image's Yarn apt source has no valid signing key, which makes apt-get update
# fail for any feature that installs packages (such as the SSH server).
RUN rm -f /etc/apt/sources.list.d/yarn.list
