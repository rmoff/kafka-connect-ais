## Build & verify
- `mvn clean package` -> shaded JAR + component ZIP
- `mvn test` -> 15 unit tests (necessary but not sufficient for build changes)

## Smoke test before claiming a build change works
```
docker compose up -d
until curl -sf http://localhost:8083/ >/dev/null; do sleep 2; done
curl -X POST -H 'Content-Type: application/json' \
    --data @connector-ais.json http://localhost:8083/connectors
sleep 30  # connector needs ~5s + record flow time
docker exec broker /opt/kafka/bin/kafka-get-offsets.sh \
    --bootstrap-server broker:29092 --topic ais
# Expect >=200 records and connector status RUNNING
```

## CVE scan
`trivy fs` and `trivy fs <jar>` silently miss bundled JARs.
Use `trivy rootfs` on the unpacked component dir:
```
trivy rootfs --scanners vuln \
  target/components/packages/rmoff-kafka-connect-ais-*/rmoff-kafka-connect-ais-*/
```

## Gotchas already in README
See README "Deploy to Confluent Cloud Custom Connectors" for the
CCAF deployment gotchas (egress allowlist, schema.registry.auto
bug, app-logs as failure surface).
