# Contributor guidelines

## Reporting Issues

If you wish to report an issue for Benji, please ensure you have done the following things:

Before making a contribution, it is important to make sure that the change you wish to make and the approach you wish to take will likely be accepted, otherwise you may end up doing a lot of work for nothing.  If the change is only small, for example, if it's a documentation change or a simple bugfix, then it's likely to be accepted with no prior discussion.  However, new features, or bigger refactorings should first be discussed. 

## Procedure

This is the process for a contributor (that is, a non core developer) to contribute to Benji.

1. Ensure that your contribution meets the following guidelines:
    1. Live up to the current code standard:
        - Not violate [DRY](http://programmer.97things.oreilly.com/wiki/index.php/Don%27t_Repeat_Yourself).
        - [Boy Scout Rule](http://programmer.97things.oreilly.com/wiki/index.php/The_Boy_Scout_Rule) needs to have been applied.
    2. Regardless of whether the code introduces new features or fixes bugs or regressions, it must have comprehensive tests. This includes when modifying existing code that isn't tested.
    3. The code must be well documented in the standard documentation format. Each API change must have the corresponding documentation change.
    4. Implementation-wise, the following things should be avoided as much as possible:
        * Global state
        * Public mutable state
        * Implicit conversions
        * ThreadLocal
        * Locks
        * Casting
        * Introducing new, heavy external dependencies
    5. New files must:
       * Not use ``@author`` tags since it does not encourage [Collective Code Ownership](http://www.extremeprogramming.org/rules/collective.html).
2. Submit a pull request.

If the pull request does not meet the above requirements then the code should **not** be merged into master, or even reviewed - regardless of how good or important it is. No exceptions.

The pull request will be reviewed according to the [implementation decision process](https://playframework.com/community-process#Implementation-decisions).

---

## Testing with Google Cloud Storage (Benji Google Module)

### Overview

The Benji Google module tests (`google/src/test/scala/`) use [storage-testbench](https://github.com/googleapis/storage-testbench), Google's official GCS testing tool. This allows running integration tests locally and in CI without requiring access to real Google Cloud Storage.

### Prerequisites

**Important**: When `BENJI_USE_TESTBENCH=true` is set, storage-testbench **must be running** before starting tests. The tests do NOT auto-start testbench—they expect it to be already available on `TESTBENCH_HOST:TESTBENCH_PORT`.

### Local Setup

#### Option A: Using Docker (Recommended)

1. **Build Docker image directly from GitHub**
   ```bash
   # Docker can build directly from git URL with automatic caching
   docker build -t storage-testbench:v0.62.0 \
     https://github.com/googleapis/storage-testbench.git#v0.62.0
   ```

2. **Start storage-testbench**
   ```bash
   docker run -d \
     --name benji-storage-testbench \
     -p 9000:9000 \
     storage-testbench:v0.62.0
   ```

3. **Verify testbench is running**
   ```bash
   curl http://localhost:9000
   # Expected output: OK
   ```

4. **Run Google tests**
   ```bash
   export BENJI_USE_TESTBENCH=true
   export TESTBENCH_HOST=localhost
   export TESTBENCH_PORT=9000
   
   sbt "project google" test
   ```

5. **Stop testbench when done**
   ```bash
   docker stop benji-storage-testbench
   docker rm benji-storage-testbench
   ```

#### Option B: Using Docker Compose

1. **Start testbench with docker-compose** (from Benji repository root)
   ```bash
   docker-compose -f google/docker-compose.testbench.yml up -d
   ```
   This will automatically build from the GitHub repository and cache layers.

2. **Run tests**
   ```bash
   export BENJI_USE_TESTBENCH=true
   export TESTBENCH_HOST=localhost
   export TESTBENCH_PORT=9000
   
   sbt "project google" test
   ```

3. **Stop testbench**
   ```bash
   docker-compose -f google/docker-compose.testbench.yml down
   ```

#### Option C: Using Python Package (Advanced)

If you prefer not to use Docker:

1. **Clone storage-testbench**
   ```bash
   git clone https://github.com/googleapis/storage-testbench.git
   cd storage-testbench
   ```

2. **Install dependencies**
   ```bash
   python3 -m venv venv
   source venv/bin/activate  # On Windows: venv\Scripts\activate
   pip install -e .
   ```

3. **Start testbench**
   ```bash
   python3 testbench_run.py localhost 9000 10 &
   ```

4. **Run tests (in a new terminal)**
   ```bash
   export BENJI_USE_TESTBENCH=true
   export TESTBENCH_HOST=localhost
   export TESTBENCH_PORT=9000
   
   sbt "project google" test
   ```

5. **Stop testbench**
   ```bash
   pkill -f testbench_run.py
   ```

### Troubleshooting

#### "connection refused" when running tests

**Problem**: Tests fail with connection refused to localhost:9000

**Solution**: Ensure testbench is running and accessible
```bash
# Verify testbench is running
curl http://localhost:9000

# If not running, start it
docker run -d -p 9000:9000 \
  $(docker build -q https://github.com/googleapis/storage-testbench.git#v0.62.0)
```

#### Certificate verification errors

**Problem**: `javax.net.ssl.SSLHandshakeException: PKIX path building failed` or connection errors

**Solutions**:
- **Connection errors**: storage-testbench uses plain HTTP on port 9000, not HTTPS. Verify:
  - `BENJI_USE_TESTBENCH=true` is set
  - Tests reach `http://localhost:9000` (not https)
  - Testbench is running: `curl http://localhost:9000`
  - Port 9000 is not blocked by firewall

- **Docker build caching**: Docker automatically caches layers when building from GitHub. To rebuild without cache:
  ```bash
  docker build --no-cache -t storage-testbench:v0.62.0 \
    https://github.com/googleapis/storage-testbench.git#v0.62.0
  ```

#### Tests timeout waiting for testbench

**Problem**: Tests hang for ~30 seconds then fail

**Solution**: Testbench may not have started in time
```bash
# Build image from GitHub (first build will take longer)
docker build -t storage-testbench:v0.62.0 \
  https://github.com/googleapis/storage-testbench.git#v0.62.0

# Start with explicit timeout
docker run -d --name benji-testbench -p 9000:9000 storage-testbench:v0.62.0

# Give it time to start
sleep 3

# Verify it's ready
curl http://localhost:9000

# Then run tests
sbt "project google" test
```

### Configuration

The testbench integration is configured via environment variables and `google/src/test/resources/tests.conf`:

| Variable | Description | Default | Notes |
|----------|-------------|---------|-------|
| `BENJI_USE_TESTBENCH` | Enable/disable testbench | `false` | Set to `true` to use testbench |
| `TESTBENCH_HOST` | Testbench hostname | `localhost` | Use service name in Docker Compose |
| `TESTBENCH_PORT` | Testbench port | `9000` | Must match testbench startup |
| `google.storage.projectId` | GCS project ID (in `local.conf`) | `test-project-123` | For testing, any ID works |

### CI/CD Integration

#### CircleCI

The CircleCI configuration (`.circleci/config.yml`) automatically uses testbench for Google module tests. For each test job, the pipeline:

1. Installs Python venv support in the primary container
2. Clones `storage-testbench` from GitHub and installs it in a virtual environment
3. Starts testbench as a Python process on `localhost:9023`
4. Waits for health check on http://localhost:9023 (max 30 seconds)
5. Runs tests with `BENJI_USE_TESTBENCH=true`

MinIO remains available on `localhost:9000` for the S3 integration tests, so CircleCI keeps the Google testbench and MinIO on separate ports.

**No action required** - tests will use testbench automatically on CircleCI.

#### Local CI Simulation

To simulate CI behavior locally:

```bash
# Option 1: Use docker-compose (recommended)
docker-compose -f google/docker-compose.testbench.yml up --abort-on-container-exit

# Option 2: Manual Docker build and tests
docker build -t storage-testbench:v0.62.0 \
  https://github.com/googleapis/storage-testbench.git#v0.62.0

docker run -d --name benji-testbench -p 9000:9000 storage-testbench:v0.62.0

# Wait for health
for i in {1..30}; do
  if curl -s http://localhost:9000 2>/dev/null | grep -q "OK"; then
    break
  fi
  sleep 1
done

# Run tests
export BENJI_USE_TESTBENCH=true
sbt "project google" test

# Cleanup
docker stop benji-testbench
docker rm benji-testbench
```

### Testing Against Real GCS (Advanced)

If you need to test against real Google Cloud Storage:

1. **Disable testbench**
   ```bash
   unset BENJI_USE_TESTBENCH
   # or explicitly
   export BENJI_USE_TESTBENCH=false
   ```

2. **Set up real GCS credentials**
   - Create a GCP service account
   - Download JSON key file
   - Place in `google/src/test/resources/gcs-test.json`
   - Update `google/src/test/resources/local.conf` with project ID

3. **Run tests**
   ```bash
   sbt "project google" test
   ```

**Warning**: Real GCS tests will create/delete actual GCS resources. Use a test project to avoid accidentally modifying production data.

### Implementation Details

The testbench integration consists of:

- **StorageTestbench.scala**: Process manager - starts/stops testbench container
- **TestUtils.scala**: Configures GoogleTransport to use testbench endpoint
- **tests.conf**: Configuration for testbench host/port/enabled flag
- **GoogleTransport.scala**: Extended to support `baseRestUrl` URI parameter

For details, see:
- `google/src/test/scala/StorageTestbench.scala` - Process management
- `google/src/test/scala/TestUtils.scala` - Test initialization
- `google/src/test/resources/tests.conf` - Configuration
