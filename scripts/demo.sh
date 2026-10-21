#!/usr/bin/env bash
# Walks through what the gateway does, against a running gateway and FHIR server.
#   Terminal 1: docker compose up -d && mvn spring-boot:run
#   Terminal 2: ./scripts/demo.sh
# Override endpoints with GATEWAY_URL and FHIR_URL.
set -euo pipefail

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
FHIR_URL="${FHIR_URL:-http://localhost:8090/fhir}"
MRN_SYSTEM="https://example.org/fhir/sid/mrn"
DIR="$(cd "$(dirname "$0")/.." && pwd)"
PAUSE="${DEMO_PAUSE:-1.5}"

bold() { printf '\n\033[1m%s\033[0m\n' "$*"; }
step() { printf '\033[35m> %s\033[0m\n' "$*"; sleep "$PAUSE"; }

send() {
  # Prints the MSA segment and the duplicate flag from the response headers
  local file="$1"
  local headers body
  headers="$(mktemp)"
  body="$(curl -s -D "$headers" -X POST "$GATEWAY_URL/api/hl7" \
          -H 'Content-Type: text/plain' --data-binary @"$DIR/samples/$file")"
  local msa dup
  msa="$(printf '%s' "$body" | tr '\r' '\n' | grep '^MSA' || true)"
  dup="$(grep -i '^X-HL7-Duplicate' "$headers" | tr -d '\r' | awk '{print $2}')"
  rm -f "$headers"
  printf '  ACK: %s   duplicate: %s\n' "$msa" "${dup:-n/a}"
}

fhir_total() {
  curl -s "$FHIR_URL/$1" | grep -o '"total" *: *[0-9]*' | grep -o '[0-9]*$' || echo 0
}

wait_for_delivery() {
  for _ in $(seq 1 30); do
    if curl -s "$GATEWAY_URL/api/outbox/summary" | grep -q '"pending" *: *0'; then
      printf '  outbox: %s\n' "$(curl -s "$GATEWAY_URL/api/outbox/summary")"
      return
    fi
    sleep 1
  done
  echo "  outbox still pending after 30 s; is the FHIR server up at $FHIR_URL?"
  exit 1
}

bold "1. A hospital system admits a patient (ADT^A01)"
step "POST samples/adt_a01.hl7"
send adt_a01.hl7

bold "2. The lab sends a blood count (ORU^R01, 4 results)"
step "POST samples/oru_r01.hl7"
send oru_r01.hl7

bold "3. The interface engine times out and resends the admission"
step "POST samples/adt_a01.hl7 again"
send adt_a01.hl7

bold "4. An unsupported message type is refused, not silently dropped"
step "POST samples/adt_a40_unsupported.hl7"
send adt_a40_unsupported.hl7

bold "5. The outbox delivers to FHIR in arrival order"
step "waiting for the delivery worker"
wait_for_delivery

bold "6. What the FHIR server now holds"
step "GET Patient, Encounter, Observation for MRN 4711"
printf '  Patients:     %s\n' "$(fhir_total "Patient?identifier=$MRN_SYSTEM%7C4711&_summary=count")"
printf '  Encounters:   %s\n' "$(fhir_total "Encounter?_summary=count")"
printf '  Observations: %s\n' "$(fhir_total "Observation?_summary=count")"

bold "7. Discharge (ADT^A03) updates the same encounter"
step "POST samples/adt_a03.hl7"
send adt_a03.hl7
wait_for_delivery
printf '  Encounters:          %s\n' "$(fhir_total "Encounter?_summary=count")"
printf '  Finished encounters: %s\n' "$(fhir_total "Encounter?status=finished&_summary=count")"

bold "Done: one patient, one encounter, four results. The resend changed nothing."
