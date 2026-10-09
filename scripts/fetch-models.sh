#!/usr/bin/env bash
#
# Fetch the ONNX models used by the embedding and reranking adapters.
#
# Everything lands in ./models (gitignored). The application is then fully
# offline: adapters are configured with file: URIs only, so there is no
# first-call download and no outbound network traffic at runtime.
#
# Usage:
#   scripts/fetch-models.sh              # default set (embedding + reranker)
#   scripts/fetch-models.sh embedding    # only the embedding model
#   scripts/fetch-models.sh reranker     # only the reranker
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODELS_DIR="${MODELS_DIR:-$ROOT/models}"
HF_BASE="${HF_BASE:-https://huggingface.co}"

# --- Model definitions -------------------------------------------------------
#
# Embedding: BAAI bge-base-en-v1.5, 768 dimensions.
#   Pooling matters: bge uses CLS pooling (1_Pooling/config.json says
#   pooling_mode_cls_token=true). Spring AI's TransformersEmbeddingModel hard
#   codes MEAN pooling, which would silently degrade retrieval, which is why we
#   run our own ONNX session instead.
#
# Reranker: ms-marco-MiniLM-L-6-v2 cross-encoder (~22M params, 91 MB fp32).
#   Chosen over bge-reranker-base (1.1 GB fp32 / 279 MB int8) for CPU latency;
#   bge-reranker-base stays a drop-in alternative via config.
#
EMBED_REPO="Xenova/bge-base-en-v1.5"
EMBED_DIR="bge-base-en-v1.5"
EMBED_FILES=(
  "onnx/model.onnx"
  "tokenizer.json"
  "tokenizer_config.json"
  "special_tokens_map.json"
  "config.json"
)

RERANK_REPO="Xenova/ms-marco-MiniLM-L-6-v2"
RERANK_DIR="ms-marco-MiniLM-L-6-v2"
RERANK_FILES=(
  "onnx/model.onnx"
  "tokenizer.json"
  "tokenizer_config.json"
  "special_tokens_map.json"
  "config.json"
)

# --- Helpers ----------------------------------------------------------------

fetch_repo() {
  local repo="$1" dir="$2"
  shift 2
  local files=("$@")
  local dest="$MODELS_DIR/$dir"

  echo "==> $repo -> models/$dir"
  mkdir -p "$dest"

  for f in "${files[@]}"; do
    local out="$dest/$(basename "$f")"
    local url="$HF_BASE/$repo/resolve/main/$f"

    if [[ -s "$out" ]]; then
      local remote_size
      remote_size="$(curl -sIL -m 30 "$url" | awk 'BEGIN{IGNORECASE=1} /^content-length:/{v=$2} END{gsub(/\r/,"",v); print v}')"
      local local_size
      local_size="$(stat -c%s "$out")"
      if [[ -n "$remote_size" && "$remote_size" == "$local_size" ]]; then
        echo "    [skip] $(basename "$f") ($(numfmt --to=iec "$local_size"))"
        continue
      fi
      echo "    [redo] $(basename "$f") size mismatch (local=$local_size remote=${remote_size:-?})"
    fi

    echo "    [get ] $f"
    # -C - resumes a partial download; --retry handles flaky links on big blobs.
    curl -L --fail --retry 5 --retry-delay 3 -C - -m 1800 \
      -o "$out" "$url" \
      || { echo "    !! failed: $url" >&2; return 1; }
    echo "          -> $(numfmt --to=iec "$(stat -c%s "$out")")"
  done
}

verify() {
  local path="$1"
  [[ -s "$path" ]] || { echo "MISSING: $path" >&2; return 1; }
  echo "ok: ${path#"$ROOT"/} ($(numfmt --to=iec "$(stat -c%s "$path")"))"
}

# --- Main -------------------------------------------------------------------

target="${1:-all}"

mkdir -p "$MODELS_DIR"

case "$target" in
  all)
    fetch_repo "$EMBED_REPO" "$EMBED_DIR" "${EMBED_FILES[@]}"
    fetch_repo "$RERANK_REPO" "$RERANK_DIR" "${RERANK_FILES[@]}"
    ;;
  embedding) fetch_repo "$EMBED_REPO" "$EMBED_DIR" "${EMBED_FILES[@]}" ;;
  reranker)  fetch_repo "$RERANK_REPO" "$RERANK_DIR" "${RERANK_FILES[@]}" ;;
  *)
    echo "usage: $0 [all|embedding|reranker]" >&2
    exit 2
    ;;
esac

echo
echo "==> Verification"
status=0
if [[ "$target" == "all" || "$target" == "embedding" ]]; then
  verify "$MODELS_DIR/$EMBED_DIR/model.onnx"  || status=1
  verify "$MODELS_DIR/$EMBED_DIR/tokenizer.json" || status=1
fi
if [[ "$target" == "all" || "$target" == "reranker" ]]; then
  verify "$MODELS_DIR/$RERANK_DIR/model.onnx" || status=1
  verify "$MODELS_DIR/$RERANK_DIR/tokenizer.json" || status=1
fi

echo
if [[ $status -eq 0 ]]; then
  echo "Models ready under $MODELS_DIR"
  echo "Runtime config (application-local.yml or env) uses plain paths:"
  echo "  rag.embedding.model-path=$MODELS_DIR/$EMBED_DIR/model.onnx"
  echo "  rag.embedding.tokenizer-path=$MODELS_DIR/$EMBED_DIR/tokenizer.json"
  echo "  rag.rerank.model-path=$MODELS_DIR/$RERANK_DIR/model.onnx"
  echo "  rag.rerank.tokenizer-path=$MODELS_DIR/$RERANK_DIR/tokenizer.json"
else
  echo "Model fetch incomplete." >&2
fi
exit $status
