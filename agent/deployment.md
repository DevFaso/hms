# deployment.md

**Environment:** CI/CD via GitHub Actions. Backend and frontend deployed on Railway with Docker.
**Scaling:** Railway handles container orchestration and scaling.
**Monitoring:** Grafana Cloud for observability (OTLP metrics, Faro RUM). Prometheus endpoint exposed via Actuator. Alerts on 5xx errors or test failures.
**Retraining:** Collect failing cases into a test suite for future agent improvements or model fine-tuning.
