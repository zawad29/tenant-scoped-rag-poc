@param String cidName
@param String email
@param String role
@param boolean isCidAdmin
@param String csrfToken
@param String csrfParameterName
@param String active
@template.layout.app(title = "Home", active = active, cidName = cidName, email = email, role = role, isCidAdmin = isCidAdmin, csrfToken = csrfToken, csrfParameterName = csrfParameterName, content = @`
  <div class="mx-auto max-w-3xl space-y-6">

    <section class="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
      <h2 class="text-lg font-semibold text-slate-900">Ask ${cidName}'s documents</h2>
      <p class="mt-2 text-sm leading-relaxed text-slate-600">
        Answers are written only from documents uploaded by ${cidName}, and every
        claim is cited. If the documents do not support an answer, the assistant
        says so instead of guessing.
      </p>
      <div class="mt-4 flex flex-wrap gap-3">
        <a href="/chat"
           class="inline-flex items-center gap-2 rounded-md bg-indigo-600 px-4 py-2 text-sm font-medium text-white transition hover:bg-indigo-700">
          Open chat
        </a>
        @if(isCidAdmin)
          <a href="/admin/documents"
             class="inline-flex items-center gap-2 rounded-md border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 transition hover:bg-slate-50">
            Manage documents
          </a>
        @endif
      </div>
    </section>

    <section class="grid gap-4 sm:grid-cols-3">
      <div class="rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
        <div class="text-xs font-semibold uppercase tracking-wide text-indigo-600">Scoped</div>
        <p class="mt-1 text-sm text-slate-600">
          Every query carries the cid, and results are checked against it before
          they are used.
        </p>
      </div>
      <div class="rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
        <div class="text-xs font-semibold uppercase tracking-wide text-indigo-600">Grounded</div>
        <p class="mt-1 text-sm text-slate-600">
          A relevance gate runs before the model. Weak evidence becomes a refusal,
          not a guess.
        </p>
      </div>
      <div class="rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
        <div class="text-xs font-semibold uppercase tracking-wide text-indigo-600">Visible</div>
        <p class="mt-1 text-sm text-slate-600">
          The chat panel shows what was retrieved and how long each stage took.
        </p>
      </div>
    </section>

    <section class="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
      <h2 class="text-sm font-semibold text-slate-900">Signed in as</h2>
      <dl class="mt-3 grid grid-cols-[max-content_1fr] gap-x-6 gap-y-1 text-sm">
        <dt class="text-slate-500">Email</dt><dd class="text-slate-800">${email}</dd>
        <dt class="text-slate-500">Role</dt><dd class="text-slate-800">${role}</dd>
        <dt class="text-slate-500">Organisation</dt><dd class="text-slate-800">${cidName}</dd>
      </dl>
    </section>

  </div>
`)
