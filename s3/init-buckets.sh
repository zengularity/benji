#!/bin/sh
# Initialize MinIO buckets

set -e

export AWS_ACCESS_KEY_ID=minioadmin
export AWS_SECRET_ACCESS_KEY=minioadmin123
export AWS_DEFAULT_REGION=us-east-1

echo "Creating buckets in MinIO..."

# Wait a bit for MinIO to be fully ready
sleep 2

/usr/local/bin/aws s3 mb s3://benji-test --endpoint-url http://minio:9000 || echo "Bucket benji-test already exists"
/usr/local/bin/aws s3 mb s3://benji-test-versioning --endpoint-url http://minio:9000 || echo "Bucket benji-test-versioning already exists"
/usr/local/bin/aws s3 mb s3://benji-test-ceph --endpoint-url http://minio:9000 || echo "Bucket benji-test-ceph already exists"

echo "MinIO buckets initialized successfully!"
