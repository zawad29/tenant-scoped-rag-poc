@import com.example.ragpoc.retrieval.model.RetrievalOutcome
@import com.example.ragpoc.retrieval.model.RetrievalTrace
@import com.example.ragpoc.retrieval.model.RetrievedChunk
@import com.example.ragpoc.web.ViewFormat
@param String cidName
@param boolean isCidAdmin
@param String csrfToken
@param String csrfParameterName
@param String question
@param RetrievalOutcome outcome
@param String error
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Chat &middot; ${cidName}</title>
  <link rel="stylesheet" href="/css/app.css">
</head>
<body>
<header>
  <strong>${cidName}</strong>
  <span class="who">Retrieval console</span>
  <a href="/">Home</a>
  @if(isCidAdmin)
    <a href="/admin/documents">Documents</a>
    <a href="/admin/users">Users</a>
  @endif
  <a href="/cid/profile">Profile</a>
  <form method="post" action="/logout">
    <input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
    <button type="submit" class="link">Sign out</button>
  </form>
</header>

<main class="chat-page">
  <section class="chat-column">
    <h1>Chat</h1>
    <p class="muted">
      Chat replies are not implemented yet. Ask a question and the panel on the
      right shows what retrieval found for it.
    </p>

    <form method="post" action="/chat" class="chat-form">
      <input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
      <textarea name="question" rows="3" maxlength="2000" required
                placeholder="Ask a question about your organisation's documents">${question != null ? question : ""}</textarea>
      <button type="submit">Ask</button>
    </form>

    @if(question != null)
      <div class="bubble user">${question}</div>
    @endif

    <div class="bubble assistant disabled" aria-disabled="true">
      <div class="muted small">Assistant replies are disabled.</div>
      @if(outcome != null && !outcome.canAnswer())
        <div>${outcome.refusalMessage()}</div>
      @endif
    </div>
  </section>

  <section class="retrieval-column">
    <h2>Retrieval</h2>

    @if(error != null)
      <p class="alert" role="alert">${error}</p>
    @elseif(outcome == null)
      <p class="muted">Ask a question to see the retrieved candidates.</p>
    @else
      <div class="decision ${outcome.canAnswer() ? "ok" : "refused"}">
        <strong>${outcome.canAnswer() ? "Answerable" : "Refused"}</strong>
        <span class="muted">${outcome.trace().reason()}</span>
      </div>

      <dl class="stats">
        <dt>Dense candidates</dt><dd>${outcome.trace().denseCandidates()}</dd>
        <dt>Reranked</dt><dd>${outcome.trace().rerankedCandidates()}</dd>
        <dt>Selected</dt><dd>${outcome.trace().selectedChunks()}</dd>
        <dt>Best rerank</dt><dd>${ViewFormat.score(outcome.trace().bestRerankScore())}</dd>
        <dt>Thresholds</dt>
        <dd>${ViewFormat.score(outcome.trace().minRelevance())} / ${ViewFormat.score(outcome.trace().minChunkRelevance())}</dd>
        <dt>Models</dt>
        <dd>${outcome.trace().embeddingModel()} &middot; ${outcome.trace().rerankerModel()}</dd>
        <dt>Timings</dt>
        <dd>embed ${outcome.trace().embedMillis()} ms &middot; search ${outcome.trace().searchMillis()} ms &middot; rerank ${outcome.trace().rerankMillis()} ms</dd>
      </dl>

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
  </section>
</main>
</body>
</html>
