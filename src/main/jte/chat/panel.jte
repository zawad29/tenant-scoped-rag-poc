@import com.example.ragpoc.chat.ChatTimings
@import com.example.ragpoc.retrieval.model.RetrievalOutcome
@import com.example.ragpoc.retrieval.model.RetrievalTrace
@import com.example.ragpoc.retrieval.model.RetrievedChunk
@import com.example.ragpoc.web.ViewFormat
@param RetrievalOutcome outcome
@param long retrievalMillis
@param ChatTimings timings
<div class="decision ${outcome.canAnswer() ? "ok" : "refused"}">
  <strong>${outcome.canAnswer() ? "Answerable" : "Refused"}</strong>
  <span class="muted">${outcome.trace().reason()}</span>
</div>

<dl class="stats">
  <dt>Dense candidates</dt>
  <dd>${outcome.trace().denseCandidates()}</dd>
  <dt>Reranked</dt>
  <dd>${outcome.trace().rerankedCandidates()}</dd>
  <dt>Selected</dt>
  <dd>${outcome.trace().selectedChunks()}</dd>
  <dt>Best rerank</dt>
  <dd>${ViewFormat.finiteScore(outcome.trace().bestRerankScore())}</dd>
  <dt>Thresholds</dt>
  <dd>${ViewFormat.score(outcome.trace().minRelevance())} / ${ViewFormat.score(outcome.trace().minChunkRelevance())}</dd>
  <dt>Embedding model</dt>
  <dd>${outcome.trace().embeddingModel()}</dd>
  <dt>Reranker</dt>
  <dd>${outcome.trace().rerankerModel()}</dd>
</dl>

<h3>Stage timings</h3>
<table class="timings">
  <thead>
  <tr><th>Stage</th><th>Time</th></tr>
  </thead>
  <tbody>
  <tr>
    <td>Dense search <span class="muted small">(includes question embedding)</span></td>
    <td>${ViewFormat.ms(outcome.trace().searchMillis())}</td>
  </tr>
  <tr>
    <td>Rerank</td>
    <td>${ViewFormat.ms(outcome.trace().rerankMillis())}</td>
  </tr>
  <tr>
    <td>Context assembly</td>
    <td>${ViewFormat.ms(outcome.trace().assembleMillis())}</td>
  </tr>
  <tr class="total">
    <td>Retrieval total</td>
    <td>${ViewFormat.ms(retrievalMillis)}</td>
  </tr>
  @if(timings != null)
    <tr>
      <td>First token</td>
      <td>${ViewFormat.msOrDash(timings.firstTokenMillis())}</td>
    </tr>
    <tr>
      <td>Generation</td>
      <td>${ViewFormat.msOrDash(timings.generationMillis())}</td>
    </tr>
    <tr class="total">
      <td>Request total</td>
      <td>${ViewFormat.ms(timings.totalMillis())}</td>
    </tr>
  @else
    <tr>
      <td>Generation</td>
      <td class="muted generation-status">streaming&hellip;</td>
    </tr>
  @endif
  </tbody>
</table>
@if(timings != null && timings.refused())
  <p class="muted small">No model call: the relevance gate refused before generation.</p>
@endif

<h3>Selected context</h3>
@if(outcome.chunks().isEmpty())
  <p class="muted">Nothing was selected.</p>
@else
  @for(RetrievedChunk chunk : outcome.chunks())
    <article class="source">
      <header class="source-head">
        <strong>${chunk.sourceId()}</strong>
        <span>${chunk.docTitle()}</span>
      </header>
      @if(chunk.sectionTitle() != null)
        <div class="muted small">${chunk.sectionTitle()}</div>
      @endif
      <p>${chunk.text()}</p>
      <div class="muted small">
        dense ${ViewFormat.score(chunk.denseScore())} &middot;
        rerank ${ViewFormat.score(chunk.rerankScore())} &middot;
        relevance ${ViewFormat.relevance(chunk.relevance())}
      </div>
    </article>
  @endfor
@endif

<h3>All candidates (dense order)</h3>
@if(outcome.trace().candidates().isEmpty())
  <p class="muted">No candidates were retrieved.</p>
@else
  <table class="candidates">
    <thead>
    <tr>
      <th>Dense&nbsp;#</th>
      <th>Rerank&nbsp;#</th>
      <th>Document</th>
      <th>Dense score</th>
      <th>Rerank score</th>
      <th>Used</th>
    </tr>
    </thead>
    <tbody>
    @for(RetrievalTrace.CandidateTrace c : outcome.trace().candidates())
      <tr class="${c.selected() ? "selected" : ""}">
        <td>${c.denseRank()}</td>
        <td>
          @if(c.rerankRank() != null)${c.rerankRank()}@else-@endif
        </td>
        <td>${c.docTitle()}</td>
        <td>${ViewFormat.score(c.denseScore())}</td>
        <td>${ViewFormat.score(c.rerankScore())}</td>
        <td>
          @if(c.selected())yes@else${c.droppedReason()}@endif
        </td>
      </tr>
    @endfor
    </tbody>
  </table>
@endif
