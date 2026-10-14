@import com.example.ragpoc.chat.ChatTimings
@import com.example.ragpoc.retrieval.model.RetrievalOutcome
@import com.example.ragpoc.retrieval.model.RetrievalTrace
@import com.example.ragpoc.retrieval.model.RetrievedChunk
@import com.example.ragpoc.web.ViewFormat
@param RetrievalOutcome outcome
@param long retrievalMillis
@param ChatTimings timings

<div class="flex flex-wrap items-center gap-2">
  @if(outcome.canAnswer())
    <span class="inline-flex rounded-full bg-emerald-100 px-2.5 py-1 text-xs font-semibold text-emerald-800">Answerable</span>
  @else
    <span class="inline-flex rounded-full bg-rose-100 px-2.5 py-1 text-xs font-semibold text-rose-800">Refused</span>
  @endif
  <span class="text-xs text-slate-500">${outcome.trace().reason()}</span>
</div>

<dl class="mt-4 grid grid-cols-2 gap-2 text-sm">
  <div class="rounded-lg bg-slate-50 px-3 py-2">
    <dt class="text-xs text-slate-500">Dense candidates</dt>
    <dd class="font-medium text-slate-800">${outcome.trace().denseCandidates()}</dd>
  </div>
  <div class="rounded-lg bg-slate-50 px-3 py-2">
    <dt class="text-xs text-slate-500">Reranked</dt>
    <dd class="font-medium text-slate-800">${outcome.trace().rerankedCandidates()}</dd>
  </div>
  <div class="rounded-lg bg-slate-50 px-3 py-2">
    <dt class="text-xs text-slate-500">Selected</dt>
    <dd class="font-medium text-slate-800">${outcome.trace().selectedChunks()}</dd>
  </div>
  <div class="rounded-lg bg-slate-50 px-3 py-2">
    <dt class="text-xs text-slate-500">Best rerank</dt>
    <dd class="font-medium text-slate-800">${ViewFormat.finiteScore(outcome.trace().bestRerankScore())}</dd>
  </div>
</dl>

<div class="mt-5">
  <h3 class="text-xs font-semibold uppercase tracking-wide text-slate-500">Stage timings</h3>
  <dl class="mt-1 divide-y divide-slate-100 text-sm">
    <div class="flex items-center justify-between py-1.5">
      <dt class="text-slate-500">Dense search <span class="text-xs text-slate-400">(includes question embedding)</span></dt>
      <dd class="font-medium text-slate-700">${ViewFormat.ms(outcome.trace().searchMillis())}</dd>
    </div>
    <div class="flex items-center justify-between py-1.5">
      <dt class="text-slate-500">Rerank</dt>
      <dd class="font-medium text-slate-700">${ViewFormat.ms(outcome.trace().rerankMillis())}</dd>
    </div>
    <div class="flex items-center justify-between py-1.5">
      <dt class="text-slate-500">Context assembly</dt>
      <dd class="font-medium text-slate-700">${ViewFormat.ms(outcome.trace().assembleMillis())}</dd>
    </div>
    <div class="flex items-center justify-between py-1.5">
      <dt class="font-medium text-slate-700">Retrieval total</dt>
      <dd class="font-semibold text-slate-900">${ViewFormat.ms(retrievalMillis)}</dd>
    </div>
    <div id="generation-timings">
      @template.chat.timings(timings)
    </div>
  </dl>
  <p class="mt-1 text-xs text-slate-400">
    ${outcome.trace().embeddingModel()} &middot; ${outcome.trace().rerankerModel()} &middot;
    thresholds ${ViewFormat.score(outcome.trace().minRelevance())}/${ViewFormat.score(outcome.trace().minChunkRelevance())}
  </p>
</div>

<details class="mt-5 rounded-lg border border-slate-200" open>
  <summary class="flex cursor-pointer items-center justify-between px-3 py-2 text-sm font-medium text-slate-700">
    <span>Selected context (${outcome.chunks().size()})</span>
    <span class="text-xs text-slate-400">expand</span>
  </summary>
  <div class="space-y-2 border-t border-slate-100 px-3 py-3">
    @if(outcome.chunks().isEmpty())
      <p class="text-sm text-slate-500">Nothing was selected.</p>
    @else
      @for(RetrievedChunk chunk : outcome.chunks())
        <details class="rounded-md bg-slate-50">
          <summary class="flex cursor-pointer flex-wrap items-center gap-2 px-3 py-2 text-sm">
            <span class="rounded bg-indigo-100 px-1.5 py-0.5 text-xs font-semibold text-indigo-700">${chunk.sourceId()}</span>
            <span class="font-medium text-slate-800">${chunk.docTitle()}</span>
            @if(chunk.sectionTitle() != null)
              <span class="text-xs text-slate-500">${chunk.sectionTitle()}</span>
            @endif
            <span class="ml-auto text-xs text-slate-500">relevance ${ViewFormat.relevance(chunk.relevance())}</span>
          </summary>
          <div class="px-3 pb-3">
            <p class="whitespace-pre-wrap text-sm leading-relaxed text-slate-700">${chunk.text()}</p>
            <div class="mt-2 text-xs text-slate-500">
              dense ${ViewFormat.score(chunk.denseScore())} &middot;
              rerank ${ViewFormat.score(chunk.rerankScore())}
            </div>
          </div>
        </details>
      @endfor
    @endif
  </div>
</details>

<details class="mt-3 rounded-lg border border-slate-200" open>
  <summary class="flex cursor-pointer items-center justify-between px-3 py-2 text-sm font-medium text-slate-700">
    <span>Candidates considered (${outcome.trace().candidates().size()})</span>
    <span class="text-xs text-slate-400">dense order</span>
  </summary>
  <div class="space-y-1 border-t border-slate-100 px-3 py-3">
    @if(outcome.trace().candidates().isEmpty())
      <p class="text-sm text-slate-500">No candidates were retrieved.</p>
    @else
      @for(RetrievalTrace.CandidateTrace c : outcome.trace().candidates())
        <details class="rounded-md bg-slate-50">
          <summary class="flex cursor-pointer flex-wrap items-center gap-2 px-3 py-2 text-sm">
            <span class="w-10 shrink-0 text-xs text-slate-500">#${c.denseRank()}</span>
            <span class="w-12 shrink-0 text-xs text-slate-500">
              @if(c.rerankRank() != null)r#${c.rerankRank()}@else&ndash;@endif
            </span>
            <span class="min-w-0 flex-1 truncate font-medium text-slate-800">${c.docTitle()}</span>
            <span class="text-xs text-slate-500">dense ${ViewFormat.score(c.denseScore())}</span>
            <span class="text-xs text-slate-500">rerank ${ViewFormat.score(c.rerankScore())}</span>
            @if(c.selected())
              <span class="rounded bg-emerald-100 px-1.5 py-0.5 text-xs font-medium text-emerald-800">used</span>
            @else
              <span class="rounded bg-slate-200 px-1.5 py-0.5 text-xs font-medium text-slate-600">dropped</span>
            @endif
          </summary>
          <div class="space-y-2 px-3 pb-3">
            <p class="whitespace-pre-wrap text-xs leading-relaxed text-slate-600">${c.text()}</p>
            <div class="text-xs text-slate-500">
              @if(c.selected())
                Selected into the context for the answer.
              @else
                ${c.droppedReason()}
              @endif
            </div>
          </div>
        </details>
      @endfor
    @endif
  </div>
</details>
