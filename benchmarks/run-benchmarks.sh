#!/bin/bash
# FlowSense Pro — Performance Benchmark Suite
# Run: chmod +x benchmarks/run-benchmarks.sh && ./benchmarks/run-benchmarks.sh
# Requires: curl, jq, bc

BASE_URL="http://localhost:8080"
PROJECT_ID="benchmark-project"
RESULTS_FILE="benchmarks/results-$(date +%Y%m%d-%H%M%S).md"

echo "================================================="
echo "  FlowSense Pro — Benchmark Suite"
echo "  $(date)"
echo "================================================="
echo ""

mkdir -p benchmarks
echo "# FlowSense Benchmark Results" > $RESULTS_FILE
echo "Generated: $(date)" >> $RESULTS_FILE
echo "" >> $RESULTS_FILE

# ── Benchmark 1: API Response Time ────────────────────────────
echo "📊 Benchmark 1: API Health Check"
echo "---"

total=0
count=10
for i in $(seq 1 $count); do
    start=$(date +%s%N)
    curl -s "$BASE_URL/actuator/health" > /dev/null
    end=$(date +%s%N)
    ms=$(( (end - start) / 1000000 ))
    total=$((total + ms))
    echo "  Request $i: ${ms}ms"
done
avg=$((total / count))
echo "  Average: ${avg}ms"
echo ""
echo "## Benchmark 1: Health Check Latency" >> $RESULTS_FILE
echo "Average over $count requests: **${avg}ms**" >> $RESULTS_FILE
echo "" >> $RESULTS_FILE

# ── Benchmark 2: Semantic Search ──────────────────────────────
echo "📊 Benchmark 2: Semantic Search Latency"
echo "---"

queries=("payment processing" "user authentication" "database operations" "REST controller" "Kafka consumer")
total=0
for query in "${queries[@]}"; do
    start=$(date +%s%N)
    curl -s "$BASE_URL/api/search/$PROJECT_ID?q=$(echo $query | sed 's/ /+/g')&limit=5" > /dev/null
    end=$(date +%s%N)
    ms=$(( (end - start) / 1000000 ))
    total=$((total + ms))
    echo "  Query '$query': ${ms}ms"
done
avg=$((total / ${#queries[@]}))
echo "  Average: ${avg}ms"
echo ""
echo "## Benchmark 2: Semantic Search Latency" >> $RESULTS_FILE
echo "Average over ${#queries[@]} queries: **${avg}ms**" >> $RESULTS_FILE
echo "" >> $RESULTS_FILE

# ── Benchmark 3: Graph Query Latency ──────────────────────────
echo "📊 Benchmark 3: Graph Query Latency"
echo "---"

total=0
count=5
for i in $(seq 1 $count); do
    start=$(date +%s%N)
    curl -s "$BASE_URL/api/graph/$PROJECT_ID/stats" > /dev/null
    end=$(date +%s%N)
    ms=$(( (end - start) / 1000000 ))
    total=$((total + ms))
    echo "  Query $i: ${ms}ms"
done
avg=$((total / count))
echo "  Average: ${avg}ms"
echo ""
echo "## Benchmark 3: Graph Query Latency" >> $RESULTS_FILE
echo "Average over $count queries: **${avg}ms**" >> $RESULTS_FILE
echo "" >> $RESULTS_FILE

# ── Benchmark 4: PR Analysis Time ─────────────────────────────
echo "📊 Benchmark 4: PR Impact Analysis Time"
echo "---"

start=$(date +%s%N)
curl -s -X POST "$BASE_URL/api/predict/pr" \
  -H "Content-Type: application/json" \
  -d "{
    \"projectId\": \"$PROJECT_ID\",
    \"prNumber\": 1,
    \"prTitle\": \"Benchmark PR\",
    \"changedFiles\": [\"src/main/java/PaymentService.java\", \"src/main/java/OrderService.java\"]
  }" > /dev/null
end=$(date +%s%N)
ms=$(( (end - start) / 1000000 ))
echo "  PR analysis time: ${ms}ms"
echo ""
echo "## Benchmark 4: PR Analysis Time" >> $RESULTS_FILE
echo "End-to-end PR analysis: **${ms}ms**" >> $RESULTS_FILE
echo "" >> $RESULTS_FILE

# ── Benchmark 5: Health Dashboard ─────────────────────────────
echo "📊 Benchmark 5: Health Dashboard Generation"
echo "---"

start=$(date +%s%N)
curl -s "$BASE_URL/api/health/$PROJECT_ID/summary" > /dev/null
end=$(date +%s%N)
ms=$(( (end - start) / 1000000 ))
echo "  Dashboard generation: ${ms}ms (first call - no cache)"

# Second call should be cached
start=$(date +%s%N)
curl -s "$BASE_URL/api/health/$PROJECT_ID/summary" > /dev/null
end=$(date +%s%N)
ms_cached=$(( (end - start) / 1000000 ))
echo "  Dashboard generation: ${ms_cached}ms (second call - cached)"
echo ""
echo "## Benchmark 5: Health Dashboard" >> $RESULTS_FILE
echo "First call (no cache): **${ms}ms** | Cached: **${ms_cached}ms**" >> $RESULTS_FILE
echo "" >> $RESULTS_FILE

# ── Summary ───────────────────────────────────────────────────
echo "================================================="
echo "  Benchmark Complete!"
echo "  Results saved: $RESULTS_FILE"
echo "================================================="
echo ""
echo "## Summary" >> $RESULTS_FILE
echo "" >> $RESULTS_FILE
echo "| Benchmark | Result |" >> $RESULTS_FILE
echo "|-----------|--------|" >> $RESULTS_FILE
echo "| API Health | Fast response |" >> $RESULTS_FILE
echo "| Semantic Search | pgvector cosine similarity |" >> $RESULTS_FILE
echo "| Graph Query | Neo4j traversal |" >> $RESULTS_FILE
echo "| PR Analysis | Full pipeline |" >> $RESULTS_FILE
echo "| Health Dashboard (cached) | Redis cache |" >> $RESULTS_FILE

cat $RESULTS_FILE
