# Production-grade Amazon ElastiCache for Redis (Cluster Mode Enabled).
#
# This is an EXAMPLE/starting point — it is NOT applied or verified against a live
# AWS account in this repo. Review every value, replace the placeholder VPC / subnet /
# security-group IDs, and run `terraform plan` before `apply`.
#
#   terraform init
#   terraform plan
#   terraform apply
#
# What it creates:
#   - a subnet group spanning private subnets in multiple AZs
#   - a security group allowing Redis (6379) only from your application's SG
#   - a custom parameter group (cluster on, explicit maxmemory-policy)
#   - a Multi-AZ, encrypted (TLS + KMS), AUTH-protected replication group
#     with 3 shards x (1 primary + 1 replica), plus a CloudWatch memory alarm

terraform {
  required_version = ">= 1.5"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.region
}

# ---------------------------------------------------------------------------
# Variables — replace defaults / pass via -var or a tfvars file.
# ---------------------------------------------------------------------------
variable "region" {
  type    = string
  default = "us-east-1"
}

variable "name" {
  type    = string
  default = "redis-prod"
}

variable "vpc_id" {
  type        = string
  description = "VPC the cache lives in"
  # default   = "vpc-xxxxxxxx"
}

variable "private_subnet_ids" {
  type        = list(string)
  description = "Private subnets in different AZs for the subnet group"
  # default   = ["subnet-aaaa", "subnet-bbbb", "subnet-cccc"]
}

variable "app_security_group_id" {
  type        = string
  description = "Security group of the application that may reach Redis"
  # default   = "sg-xxxxxxxx"
}

variable "kms_key_id" {
  type        = string
  description = "KMS key ARN for at-rest encryption"
  # default   = "arn:aws:kms:us-east-1:111122223333:key/xxxx"
}

variable "node_type" {
  type    = string
  default = "cache.r6g.large"
}

# The AUTH token. In real use, source this from AWS Secrets Manager / SSM, not a literal.
variable "auth_token" {
  type      = string
  sensitive = true
}

# ---------------------------------------------------------------------------
# Networking: subnet group + security group (perimeter).
# ---------------------------------------------------------------------------
resource "aws_elasticache_subnet_group" "this" {
  name       = "${var.name}-subnets"
  subnet_ids = var.private_subnet_ids
}

resource "aws_security_group" "redis" {
  name        = "${var.name}-sg"
  description = "Redis access from the application only"
  vpc_id      = var.vpc_id

  ingress {
    description     = "Redis from app SG only"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [var.app_security_group_id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "${var.name}-sg" }
}

# ---------------------------------------------------------------------------
# Parameter group: cluster on, explicit eviction policy (don't ship defaults).
# ---------------------------------------------------------------------------
resource "aws_elasticache_parameter_group" "this" {
  name   = "${var.name}-params"
  family = "redis7"

  parameter {
    name  = "cluster-enabled"
    value = "yes"
  }
  parameter {
    name  = "maxmemory-policy"
    value = "allkeys-lru"
  }
}

# ---------------------------------------------------------------------------
# Replication group: Cluster Mode Enabled, Multi-AZ, TLS + KMS + AUTH.
# 3 shards, each with 1 primary + 1 replica (replicas_per_node_group = 1).
# ---------------------------------------------------------------------------
resource "aws_elasticache_replication_group" "this" {
  replication_group_id = var.name
  description          = "Production Redis (Cluster Mode Enabled, Multi-AZ)"

  engine         = "redis"
  engine_version = "7.1"
  node_type      = var.node_type
  port           = 6379

  # Cluster Mode Enabled topology
  num_node_groups         = 3 # shards
  replicas_per_node_group = 1 # replicas per shard

  parameter_group_name = aws_elasticache_parameter_group.this.name
  subnet_group_name    = aws_elasticache_subnet_group.this.name
  security_group_ids   = [aws_security_group.redis.id]

  # High availability
  automatic_failover_enabled = true
  multi_az_enabled           = true

  # Encryption: TLS in transit + KMS at rest, plus AUTH token
  transit_encryption_enabled = true
  at_rest_encryption_enabled = true
  kms_key_id                 = var.kms_key_id
  auth_token                 = var.auth_token

  # Backups (RDB snapshots to S3)
  snapshot_retention_limit = 7
  snapshot_window          = "03:00-05:00"
  maintenance_window       = "sun:05:00-sun:07:00"

  tags = { Environment = "production" }
}

# ---------------------------------------------------------------------------
# A basic CloudWatch alarm on memory pressure.
# ---------------------------------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "memory" {
  alarm_name          = "${var.name}-high-memory"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "DatabaseMemoryUsagePercentage"
  namespace           = "AWS/ElastiCache"
  period              = 60
  statistic           = "Average"
  threshold           = 80
  alarm_description   = "Redis memory usage above 80%"

  dimensions = {
    ReplicationGroupId = aws_elasticache_replication_group.this.id
  }
}

# ---------------------------------------------------------------------------
# Outputs: the endpoint your application connects to.
# ---------------------------------------------------------------------------
output "configuration_endpoint" {
  description = "Cluster Mode Enabled configuration endpoint (use a cluster-aware, TLS client)"
  value       = aws_elasticache_replication_group.this.configuration_endpoint_address
}
