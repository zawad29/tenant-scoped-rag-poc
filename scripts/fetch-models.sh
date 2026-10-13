#!/usr/bin/env bash
#
# Export the tokenizer and model into ONNX for Spring AI's
# TransformersEmbeddingModel.
#
# Spring AI's prerequisites are:
#
#   python3 -m venv venv
#   source ./venv/bin/activate
#   pip install --upgrade pip
#   pip install optimum onnx onnxruntime sentence-transformers
#   optimum-cli export onnx --model sentence-transformers/all-MiniLM-L6-v2 <out>
#
# The exported folder contains tokenizer.json and model.onnx, which is exactly
# what the embedding model loads. This script wraps that flow for the two models
# the application needs, and is idempotent: a model that already has model.onnx
# and tokenizer.json is skipped.
#
# Two small additions to the documented commands, both required:
#   * optimum-onnx is installed, because Optimum 2.x moved `export onnx` into a
#     separate package. Without it `optimum-cli export onnx` fails with
#     "unrecognized arguments: onnx".
#   * the cross-encoder is exported with --task text-classification, because
#     auto-detection picks `text-ranking`, which the BERT exporter rejects.
#
# Usage:
#   scripts/fetch-models.sh              # both models
#   scripts/fetch-models.sh embedding    # only the embedding model
#   scripts/fetch-models.sh reranker     # only the reranker
#   scripts/fetch-models.sh --force      # re-export even if present
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODELS_DIR="${MODELS_DIR:-$ROOT/models}"
VENV_DIR="${MODELS_VENV:-$ROOT/.venv-models}"
PYTHON="${PYTHON:-python3}"

# The application expects these directory names. The embedding is the model from
# the Spring AI documentation; the reranker is a cross-encoder that emits one
# relevance logit per query/passage pair.
EMBED_REPO="sentence-transformers/all-MiniLM-L6-v2"
EMBED_DIR="all-MiniLM-L6-v2"
RERANK_REPO="cross-encoder/ms-marco-MiniLM-L-6-v2"
RERANK_DIR="ms-marco-MiniLM-L-6-v2"

FORCE=false
target="all"

# --- argument parsing -------------------------------------------------------

for arg in "$@"; do
  case "$arg" in
    --force) FORCE=true ;;
    all | embedding | reranker) target="$arg" ;;
    *)
      echo "usage: $0 [all|embedding|reranker] [--force]" >&2
      exit 2
      ;;
  esac
done

want_embedding=false
want_reranker=false
[[ "$target" == "all" || "$target" == "embedding" ]] && want_embedding=true
[[ "$target" == "all" || "$target" == "reranker" ]] && want_reranker=true

# --- helpers ----------------------------------------------------------------

is_exported() {
  local dir="$1"
  [[ -f "$MODELS_DIR/$dir/model.onnx" && -f "$MODELS_DIR/$dir/tokenizer.json" ]]
}

ensure_venv() {
  if [[ ! -x "$VENV_DIR/bin/optimum-cli" ]]; then
    echo "==> python3 -m venv ${VENV_DIR#"$ROOT"/}"
    "$PYTHON" -m venv "$VENV_DIR"
  fi

  "$VENV_DIR/bin/pip" install --upgrade pip

  # CPU-only torch: exporting runs on CPU, and the default linux wheel drags in
  # several gigabytes of CUDA libraries that are never used. Harmless to omit.
  if ! "$VENV_DIR/bin/python" -c "import torch" >/dev/null 2>&1; then
    "$VENV_DIR/bin/pip" install --index-url https://download.pytorch.org/whl/cpu torch
  fi

  echo "==> pip install optimum onnx onnxruntime sentence-transformers"
  "$VENV_DIR/bin/pip" install --upgrade optimum onnx onnxruntime sentence-transformers
  # See the header: required for `optimum-cli export onnx` on Optimum 2.x.
  "$VENV_DIR/bin/pip" install --upgrade optimum-onnx
}

export_model() {
  local repo="$1" dir="$2"
  shift 2
  local dest="$MODELS_DIR/$dir"

  if is_exported "$dir" && [[ "$FORCE" != "true" ]]; then
    echo "==> $repo -> models/$dir [skip: already exported]"
    return
  fi

  echo "==> optimum-cli export onnx --model $repo${*:+ $*} models/$dir"
  # optimum-cli writes into a folder it creates.
  rm -rf "$dest"
  mkdir -p "$MODELS_DIR"
  "$VENV_DIR/bin/optimum-cli" export onnx --model "$repo" "$@" "$dest"

  # Some architectures export under a different file name; the application always
  # loads model.onnx.
  if [[ ! -f "$dest/model.onnx" ]]; then
    local found
    found="$(find "$dest" -maxdepth 1 -name '*.onnx' | head -1)"
    if [[ -n "$found" ]]; then
      echo "    [note] renaming $(basename "$found") to model.onnx"
      mv "$found" "$dest/model.onnx"
    fi
  fi
}

verify() {
  local dir="$1" status=0
  for file in model.onnx tokenizer.json; do
    if [[ -s "$MODELS_DIR/$dir/$file" ]]; then
      echo "ok: models/$dir/$file ($(numfmt --to=iec "$(stat -c%s "$MODELS_DIR/$dir/$file")"))"
    else
      echo "MISSING: models/$dir/$file" >&2
      status=1
    fi
  done
  return $status
}

# --- main -------------------------------------------------------------------

needs_export=false
if [[ "$want_embedding" == true ]] && ! { is_exported "$EMBED_DIR" && [[ "$FORCE" != "true" ]]; }; then
  needs_export=true
fi
if [[ "$want_reranker" == true ]] && ! { is_exported "$RERANK_DIR" && [[ "$FORCE" != "true" ]]; }; then
  needs_export=true
fi

if [[ "$needs_export" == true ]]; then
  ensure_venv
fi

status=0
if [[ "$want_embedding" == true ]]; then
  # The documented command, verbatim.
  export_model "$EMBED_REPO" "$EMBED_DIR"
fi
if [[ "$want_reranker" == true ]]; then
  export_model "$RERANK_REPO" "$RERANK_DIR" --task text-classification
fi

echo
echo "==> Verification"
if [[ "$want_embedding" == true ]]; then
  verify "$EMBED_DIR" || status=1
fi
if [[ "$want_reranker" == true ]]; then
  verify "$RERANK_DIR" || status=1
fi

echo
if [[ $status -eq 0 ]]; then
  echo "Models ready under $MODELS_DIR"
  echo "Runtime config (application.yml defaults):"
  echo "  rag.embedding.transformers.model-path=$MODELS_DIR/$EMBED_DIR/model.onnx"
  echo "  rag.embedding.transformers.tokenizer-path=$MODELS_DIR/$EMBED_DIR/tokenizer.json"
  echo "  rag.rerank.onnx.model-path=$MODELS_DIR/$RERANK_DIR/model.onnx"
  echo "  rag.rerank.onnx.tokenizer-path=$MODELS_DIR/$RERANK_DIR/tokenizer.json"
else
  echo "Model export incomplete." >&2
fi
exit $status
