#!/usr/bin/env bash
# Send one HL7 file over MLLP and print the ACK.
# Usage: ./samples/send-mllp.sh samples/adt_a01.hl7 [host] [port]
set -euo pipefail
file="${1:?usage: send-mllp.sh FILE [HOST] [PORT]}"
host="${2:-localhost}"
port="${3:-2575}"
payload="$(tr '\n' '\r' < "$file")"
printf '\x0b%s\x1c\x0d' "$payload" | nc -w 3 "$host" "$port" | tr '\r' '\n' | tr -d '\013\034'
