#!/bin/bash
# Run PUT benchmark with JFR (Java Flight Recorder) profiling
# No external dependencies required - uses built-in JDK profiling
#
# Usage:
#   ./scripts/profile-jfr.sh
#   
# After completion, analyze with:
#   jmc build/profiler/put.jfr
# Or convert to flame graph:
#   jfr print --events jdk.ExecutionSample build/profiler/put.jfr > build/profiler/put-jfr.txt

OUTPUT_DIR="build/profiler"
mkdir -p "$OUTPUT_DIR"

echo "Running PUT benchmark with JFR profiling..."
echo "Output directory: $OUTPUT_DIR"

./gradlew jmh \
  -Pjmh.includes=PutIsolatedBenchmark \
  -Pjmh.iterations=5 \
  -Pjmh.warmupIterations=2 \
  -Pjmh.threads=4 \
  --args="-jvmArgsAppend -XX:StartFlightRecording=duration=60s,filename=$OUTPUT_DIR/put.jfr,settings=profile"

echo ""
echo "Profiling complete! Analyze with:"
echo "  jmc $OUTPUT_DIR/put.jfr"
echo "Or print samples:"
echo "  jfr print --events jdk.ExecutionSample $OUTPUT_DIR/put.jfr"
