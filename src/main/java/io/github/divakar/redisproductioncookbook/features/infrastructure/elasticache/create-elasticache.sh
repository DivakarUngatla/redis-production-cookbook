#!/usr/bin/env bash
#
# Create a production-grade Amazon ElastiCache for Redis (Cluster Mode Enabled)
# replication group using the AWS CLI.
#
# This is an EXAMPLE/starting point — it is NOT run or verified against a live AWS
# account in this repo. Fill in the placeholder IDs below, review, and run with an
# AWS profile that has ElastiCache/EC2 permissions. Prefer the Terraform version
# (./terraform/elasticache.tf) for anything you intend to keep.
#
# Mirrors ./terraform/elasticache.tf: subnet group + parameter group + a Multi-AZ,
# encrypted (TLS + KMS), AUTH-protected, 3-shard (1 replica each) cluster.

set -euo pipefail

# ---- EDIT THESE -----------------------------------------------------------
REGION="us-east-1"
NAME="redis-prod"
SUBNET_IDS="subnet-aaaa subnet-bbbb subnet-cccc"   # private subnets, different AZs
SECURITY_GROUP_ID="sg-xxxxxxxx"                      # SG that allows :6379 from your app only
KMS_KEY_ID="arn:aws:kms:us-east-1:111122223333:key/xxxx"
NODE_TYPE="cache.r6g.large"
# Source the AUTH token from a secrets manager in real use; never commit it.
AUTH_TOKEN="${REDIS_AUTH_TOKEN:?set REDIS_AUTH_TOKEN before running}"
# ---------------------------------------------------------------------------

echo ">>> 1/3 Creating subnet group (private subnets across AZs)"
aws elasticache create-cache-subnet-group \
  --region "$REGION" \
  --cache-subnet-group-name "${NAME}-subnets" \
  --cache-subnet-group-description "Private subnets for ${NAME}" \
  --subnet-ids $SUBNET_IDS

echo ">>> 2/3 Creating custom parameter group (cluster on, explicit eviction policy)"
aws elasticache create-cache-parameter-group \
  --region "$REGION" \
  --cache-parameter-group-name "${NAME}-params" \
  --cache-parameter-group-family "redis7" \
  --description "Custom params for ${NAME}"

aws elasticache modify-cache-parameter-group \
  --region "$REGION" \
  --cache-parameter-group-name "${NAME}-params" \
  --parameter-name-values \
    "ParameterName=maxmemory-policy,ParameterValue=allkeys-lru"

echo ">>> 3/3 Creating replication group (Cluster Mode Enabled, Multi-AZ, TLS+KMS+AUTH)"
aws elasticache create-replication-group \
  --region "$REGION" \
  --replication-group-id "$NAME" \
  --replication-group-description "Production Redis (Cluster Mode Enabled, Multi-AZ)" \
  --engine "redis" \
  --engine-version "7.1" \
  --cache-node-type "$NODE_TYPE" \
  --num-node-groups 3 \
  --replicas-per-node-group 1 \
  --cache-parameter-group-name "${NAME}-params" \
  --cache-subnet-group-name "${NAME}-subnets" \
  --security-group-ids "$SECURITY_GROUP_ID" \
  --automatic-failover-enabled \
  --multi-az-enabled \
  --transit-encryption-enabled \
  --at-rest-encryption-enabled \
  --kms-key-id "$KMS_KEY_ID" \
  --auth-token "$AUTH_TOKEN" \
  --snapshot-retention-limit 7 \
  --snapshot-window "03:00-05:00" \
  --preferred-maintenance-window "sun:05:00-sun:07:00" \
  --tags "Key=Environment,Value=production"

echo
echo ">>> Submitted. Watch status until 'available':"
echo "aws elasticache describe-replication-groups --region $REGION --replication-group-id $NAME \\"
echo "  --query 'ReplicationGroups[0].Status'"
echo
echo ">>> Then fetch the configuration endpoint (use a cluster-aware TLS client):"
echo "aws elasticache describe-replication-groups --region $REGION --replication-group-id $NAME \\"
echo "  --query 'ReplicationGroups[0].ConfigurationEndpoint'"
