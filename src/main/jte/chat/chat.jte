@import com.example.ragpoc.chat.ChatTimings
@import com.example.ragpoc.retrieval.model.RetrievalOutcome
@param String cidName
@param String email
@param String role
@param boolean isCidAdmin
@param String csrfToken
@param String csrfParameterName
@param String active
@param String question
@param RetrievalOutcome outcome
@param long retrievalMillis
@param ChatTimings timings
@param String answer
@param String error
@template.layout.app(title = "Chat", active = active, cidName = cidName, email = email, role = role, isCidAdmin = isCidAdmin, csrfToken = csrfToken, csrfParameterName = csrfParameterName, content = @`
  <div class="grid gap-6 xl:grid-cols-[minmax(0,1fr)_minmax(0,28rem)]">

    <!-- Conversation -->
    <section class="flex min-h-[70vh] flex-col rounded-xl border border-slate-200 bg-white shadow-sm">
      <div id="conversation" class="flex-1 space-y-4 overflow-y-auto p-4" aria-live="polite">
        @if(question != null)
          <div class="ml-auto max-w-[85%] rounded-2xl rounded-br-sm bg-indigo-600 px-4 py-2.5 text-sm leading-relaxed text-white">${question}</div>
        @endif
        @if(answer != null)
          <div class="max-w-[92%] rounded-2xl rounded-bl-sm border border-slate-200 bg-white px-4 py-2.5 text-sm leading-relaxed text-slate-800 shadow-sm">
            <span class="whitespace-pre-wrap">${answer}</span>
          </div>
        @endif
        @if(question == null)
          <div class="empty-conversation flex h-full min-h-[16rem] items-center justify-center px-6 text-center text-sm text-slate-500">
            Ask a question to see the retrieved candidates, the selected context and the timing of each stage.
          </div>
        @endif
      </div>

      <form id="chat-form" class="border-t border-slate-200 p-4" method="post" action="/chat"
            data-stream-url="/chat/stream"
            data-csrf-name="${csrfParameterName}" data-csrf-value="${csrfToken}">
        <input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
        <label for="question" class="sr-only">Question</label>
        <textarea id="question" name="question" rows="3" maxlength="2000" required
                  placeholder="Ask a question about your organisation's documents"
                  class="block w-full resize-y rounded-lg border border-slate-300 px-3 py-2 text-sm shadow-sm focus:border-indigo-500 focus:outline-none focus:ring-1 focus:ring-indigo-500">${question != null ? question : ""}</textarea>
        <div class="mt-3 flex flex-wrap items-center gap-3">
          <button type="submit"
                  class="inline-flex items-center gap-2 rounded-md bg-indigo-600 px-4 py-2 text-sm font-medium text-white transition hover:bg-indigo-700 disabled:opacity-60">
            Ask
          </button>
          <span class="text-xs text-slate-500">
            The answer streams in as it is written. Without JavaScript the form still
            works, it just waits for the whole answer.
          </span>
        </div>
      </form>
    </section>

    <!-- Retrieval panel -->
    <section class="min-w-0">
      @if(error != null)
        <div class="mb-4 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800" role="alert">
          ${error}
        </div>
      @endif
      <div class="rounded-xl border border-slate-200 bg-white shadow-sm">
        <div class="border-b border-slate-200 px-4 py-3">
          <h2 class="text-sm font-semibold text-slate-900">Retrieval &amp; timings</h2>
        </div>
        <div id="retrieval-panel" class="p-4">
          @if(outcome != null)
            @template.chat.panel(outcome, retrievalMillis, timings)
          @elseif(error == null)
            <p class="py-6 text-center text-sm text-slate-500">
              Ask a question to see the retrieved candidates, the selected context
              and the timing of each stage.
            </p>
          @endif
        </div>
      </div>
    </section>
  </div>

  <template id="tpl-user-bubble">
    <div class="ml-auto max-w-[85%] rounded-2xl rounded-br-sm bg-indigo-600 px-4 py-2.5 text-sm leading-relaxed text-white">
      <span data-role="answer"></span>
    </div>
  </template>

  <template id="tpl-assistant-bubble">
    <div class="max-w-[92%] rounded-2xl rounded-bl-sm border border-slate-200 bg-white px-4 py-2.5 text-sm leading-relaxed text-slate-800 shadow-sm">
      <div data-role="pending" class="text-xs text-slate-500">Searching your documents&hellip;</div>
      <span data-role="answer" class="whitespace-pre-wrap"></span>
    </div>
  </template>
`)
