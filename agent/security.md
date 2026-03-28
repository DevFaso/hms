# security.md
**Data Privacy:** All PHI must remain encrypted/in transit.  
**Secrets:** Store in secure vault (e.g. AWS Secrets Manager); CI should fail on detected secrets.  
**Human-in-Loop:** Require peer review for any high-impact changes (e.g. auth, billing). Use manual gates for deployments in production.
