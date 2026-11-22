#!/usr/bin/env bash
set -euo pipefail

echo "This legacy startup script launched a plain Confidential VM and is disabled." >&2
echo "Use deploy/gcp/confidential-space/launch.sh with a production Confidential Space image." >&2
exit 64
