@import com.example.ragpoc.chat.ChatTimings
@import com.example.ragpoc.retrieval.model.RetrievalOutcome
@param String cidName
@param boolean isCidAdmin
@param String csrfToken
@param String csrfParameterName
@param String question
@param RetrievalOutcome outcome
@param long retrievalMillis
@param ChatTimings timings
@param String answer
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
  <span class="who">Document assistant</span>
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
      Answers are written from ${cidName}'s own documents and cite them. The
      panel beside the conversation shows exactly what was retrieved and how
      long each stage took.
    </p>

    <div id="conversation" class="conversation" aria-live="polite">
      @if(question != null)
        <div class="bubble user">${question}</div>
      @endif
      @if(answer != null)
        <div class="bubble assistant"><span class="answer">${answer}</span></div>
      @endif
      @if(question == null)
        <p class="muted empty-conversation">Ask the first question to get started.</p>
      @endif
    </div>

    <form id="chat-form" class="chat-form" method="post" action="/chat"
          data-stream-url="/chat/stream"
          data-csrf-name="${csrfParameterName}" data-csrf-value="${csrfToken}">
      <input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
      <textarea name="question" rows="3" maxlength="2000" required
                placeholder="Ask a question about your organisation's documents">${question != null ? question : ""}</textarea>
      <div class="chat-actions">
        <button type="submit">Ask</button>
        <span class="muted small">
          The answer streams in as it is written. Without JavaScript the form
          still works, it just waits for the whole answer.
        </span>
      </div>
    </form>
  </section>

  <section class="retrieval-column">
    <h2>Retrieval &amp; timings</h2>

    @if(error != null)
      <p class="alert" role="alert">${error}</p>
    @endif

    <div id="retrieval-panel">
      @if(outcome != null)
        @template.chat.panel(outcome, retrievalMillis, timings)
      @elseif(error == null)
        <p class="muted">
          Ask a question to see the retrieved candidates, the selected context
          and the timing of each stage.
        </p>
      @endif
    </div>
  </section>
</main>
<script src="/js/chat.js" defer></script>
</body>
</html>
