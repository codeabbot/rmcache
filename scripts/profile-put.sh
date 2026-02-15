#!/bin/bash
# Run PUT benchmark with async-profiler flame graph output
# Requires: async-profiler (https://github.com/async-profiler/async-profiler)
# 
# Usage:
#   1. Download async-profiler from https://github.com/async-profiler/async-profiler/releases
#   2. Set ASYNC_PROFILER_PATH environment variable or edit the path below
#   3. Run: ./scripts/profile-put.sh

PROFILER_PATH="${ASYNC_PROFILER_PATH:-$HOME/async-profiler}"
OUTPUT_DIR="build/profiler"
mkdir -p "$OUTPUT_DIR"

if [ ! -d "$PROFILER_PATH" ]; then
    echo "Error: async-profiler not found at $PROFILER_PATH"
    echo "Please download from https://github.com/async-profiler/async-profiler/releases"
    echo "Or set ASYNC_PROFILER_PATH environment variable"
    exit 1
fi

echo "Running PUT benchmark with async-profiler..."
echo "Output directory: $OUTPUT_DIR"

./gradlew jmh \
  -Pjmh.includes=PutIsolatedBenchmark \
  -Pjmh.iterations=5 \
  -Pjmh.warmupIterations=2 \
  -Pjmh.threads=4 \
  --args="-jvmArgsAppend -agentpath:$PROFILER_PATH/lib/libasyncProfiler.so=start,event=cpu,file=$OUTPUT_DIR/put-flame.html"

echo ""
echo "Profiling complete! Open flame graph:"
echo "  open $OUTPUT_DIR/put-flame.html"
