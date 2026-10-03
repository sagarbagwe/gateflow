# AWS production architecture

## Recommended baseline

Route 53 and ACM terminate public naming/TLS at CloudFront + AWS WAF. CloudFront serves the SPA from private S3 and forwards `/api/*` to an internal ECS Fargate service through an Application Load Balancer. Fargate runs separate API and worker tasks from the same immutable ECR image. Aurora PostgreSQL-compatible (Multi-AZ), ElastiCache Redis with TLS/auth, and Amazon MQ for RabbitMQ preserve the application’s current contracts. SES is enabled only after domain verification and bounce/complaint handling. Secrets Manager supplies runtime credentials; KMS encrypts data, snapshots, queues, cache, logs, and secrets.

Private subnets contain tasks and data services. Security groups allow only ALB→API and tasks→required dependencies. No database, cache, or broker public endpoint. VPC endpoints reduce NAT exposure for ECR, CloudWatch, S3, and Secrets Manager.

## Reliability and recovery

- Aurora automated backups with point-in-time recovery; quarterly restore drill.
- Multi-AZ service deployment across at least two Availability Zones.
- Minimum two API tasks; worker desired count scales independently on queue age/depth.
- ECS deployment circuit breaker with health-based rollback; Flyway runs as a one-shot controlled task before compatible application rollout.
- RPO target: 5 minutes; RTO target: 60 minutes. These are objectives until drills prove them.
- Cross-region recovery is documented but not enabled initially; restore backups, provision from IaC, rotate secrets, validate migrations, then shift Route 53.

## Cost and rollout

Start with small Fargate tasks, one Aurora writer plus reader only when read load justifies it, cache/broker nodes sized from measurements, 30-day hot logs, and S3 lifecycle for retained exports. Budgets and anomaly detection are mandatory. Deploy dev → staging → production, use backward-compatible migrations, canary one task, smoke critical auth/workflow paths, expand, and retain the previous image for rollback. No production deployment is performed by repository CI.
