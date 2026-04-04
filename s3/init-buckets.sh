#!/bin/sh
# Initialize MinIO buckets

set -e

AWS_BIN="$(command -v aws || true)"
MINIO_ENDPOINT="${MINIO_ENDPOINT:-http://minio:9000}"

if [ -z "$AWS_BIN" ]; then
  echo "aws CLI is required to initialize MinIO buckets" >&2
  exit 1
fi

export AWS_ACCESS_KEY_ID=minioadmin
export AWS_SECRET_ACCESS_KEY=minioadmin123
export AWS_DEFAULT_REGION=us-east-1

echo "Creating buckets in MinIO..."

# Wait a bit for MinIO to be fully ready
sleep 2

ensure_bucket() {
  bucket="$1"

  if "$AWS_BIN" s3api head-bucket --bucket "$bucket" --endpoint-url "$MINIO_ENDPOINT" >/dev/null 2>&1; then
    echo "Bucket $bucket already exists"
  else
    "$AWS_BIN" s3 mb "s3://$bucket" --endpoint-url "$MINIO_ENDPOINT"
  fi
}

ensure_bucket benji-test
ensure_bucket benji-test-versioning
ensure_bucket benji-test-ceph

echo "MinIO buckets initialized successfully!"
