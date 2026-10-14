@import com.example.ragpoc.document.DocumentSummary
@import com.example.ragpoc.web.ViewFormat
@param java.util.List<DocumentSummary> rows
@param String cidName
@param String email
@param String role
@param boolean isCidAdmin
@param String csrfToken
@param String csrfParameterName
@param String active
@param String message
@param String error
@template.layout.app(title = "Documents", active = active, cidName = cidName, email = email, role = role, isCidAdmin = isCidAdmin, csrfToken = csrfToken, csrfParameterName = csrfParameterName, content = @`
  <div class="mx-auto max-w-5xl space-y-6">

    @if(message != null)
      <div class="rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-800" role="status">
        ${message}
      </div>
    @endif
    @if(error != null)
      <div class="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800" role="alert">
        ${error}
      </div>
    @endif

    <section class="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
      <h2 class="text-sm font-semibold text-slate-900">Upload a document</h2>
      <p class="mt-1 text-sm text-slate-600">
        PDFs are indexed for ${cidName} only. Indexing runs while the upload is in
        progress, so the list below is up to date when the page returns.
      </p>
      <form method="post" action="/admin/documents" enctype="multipart/form-data"
            class="mt-4 grid gap-4 sm:grid-cols-2">
        <input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
        <div>
          <label for="title" class="block text-sm font-medium text-slate-700">Title</label>
          <input id="title" name="title" type="text" placeholder="Leave blank to use the file name"
                 class="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2 text-sm shadow-sm focus:border-indigo-500 focus:outline-none focus:ring-1 focus:ring-indigo-500">
        </div>
        <div>
          <label for="file" class="block text-sm font-medium text-slate-700">PDF file</label>
          <input id="file" name="file" type="file" accept="application/pdf,.pdf" required
                 class="mt-1 block w-full text-sm text-slate-600 file:mr-3 file:rounded-md file:border-0 file:bg-slate-100 file:px-3 file:py-1.5 file:text-sm file:font-medium file:text-slate-700 hover:file:bg-slate-200">
        </div>
        <div class="sm:col-span-2">
          <button type="submit"
                  class="inline-flex items-center rounded-md bg-indigo-600 px-4 py-2 text-sm font-medium text-white transition hover:bg-indigo-700">
            Upload
          </button>
        </div>
      </form>
    </section>

    <section class="rounded-xl border border-slate-200 bg-white shadow-sm">
      <div class="flex items-center justify-between gap-3 border-b border-slate-200 px-6 py-4">
        <h2 class="text-sm font-semibold text-slate-900">Indexed documents</h2>
        <button type="button"
                hx-get="/admin/documents" hx-select="#documents-table" hx-target="#documents-table"
                hx-swap="outerHTML" hx-indicator="#nav-progress"
                class="inline-flex items-center gap-1.5 rounded-md border border-slate-300 bg-white px-3 py-1.5 text-xs font-medium text-slate-600 transition hover:bg-slate-50">
          <svg class="h-3.5 w-3.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24" aria-hidden="true">
            <path stroke-linecap="round" stroke-linejoin="round" d="M16.023 9.348h4.992V4.356m0 4.992l-3.181-3.183a8.25 8.25 0 00-13.803 3.7M2.985 19.644v-4.992m0 0h4.992m-4.992 0l3.181 3.183a8.25 8.25 0 0013.803-3.7"/>
          </svg>
          Refresh
        </button>
      </div>

      <div id="documents-table" class="px-6 py-4">
        @if(rows.isEmpty())
          <p class="py-6 text-center text-sm text-slate-500">No documents yet. Upload a PDF to get started.</p>
        @else
          <div class="overflow-x-auto">
            <table class="w-full text-left text-sm">
              <thead class="text-xs uppercase tracking-wide text-slate-500">
              <tr>
                <th class="py-2 pr-4 font-medium">Title</th>
                <th class="py-2 pr-4 font-medium">Status</th>
                <th class="py-2 pr-4 font-medium">Version</th>
                <th class="py-2 pr-4 font-medium">Pages</th>
                <th class="py-2 pr-4 font-medium">Chunks</th>
                <th class="py-2 pr-4 font-medium">Updated</th>
                <th class="py-2 font-medium"></th>
              </tr>
              </thead>
              <tbody class="divide-y divide-slate-100">
              @for(DocumentSummary row : rows)
                <tr class="align-top">
                  <td class="py-3 pr-4">
                    <div class="font-medium text-slate-800">${row.title()}</div>
                    <div class="text-xs text-slate-500">${row.originalFilename()}</div>
                  </td>
                  <td class="py-3 pr-4">
                    <span class="${ViewFormat.statusPill(row.status())}">${row.status()}</span>
                  </td>
                  <td class="py-3 pr-4 text-slate-600">${ViewFormat.version(row.currentVersion())}</td>
                  <td class="py-3 pr-4 text-slate-600">${ViewFormat.count(row.pageCount())}</td>
                  <td class="py-3 pr-4 text-slate-600">${ViewFormat.count(row.chunkCount())}</td>
                  <td class="py-3 pr-4 text-xs text-slate-500">${ViewFormat.time(row.updatedAt())}</td>
                  <td class="py-3">
                    <div class="flex flex-wrap items-center gap-2">
                      <form method="post" action="/admin/documents/${ViewFormat.id(row.id())}/versions"
                            enctype="multipart/form-data" class="flex items-center gap-2">
                        <input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
                        <input type="file" name="file" accept="application/pdf,.pdf" required
                               class="w-40 text-xs text-slate-600 file:mr-2 file:rounded file:border-0 file:bg-slate-100 file:px-2 file:py-1 file:text-xs file:font-medium file:text-slate-700 hover:file:bg-slate-200">
                        <button type="submit"
                                class="rounded-md border border-slate-300 px-3 py-1.5 text-xs font-medium text-slate-700 transition hover:bg-slate-50">
                          Replace
                        </button>
                      </form>
                      <form method="post" action="/admin/documents/${ViewFormat.id(row.id())}/delete"
                            onsubmit="return confirm('Delete this document and all of its indexed content?')">
                        <input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
                        <button type="submit"
                                class="rounded-md border border-rose-200 px-3 py-1.5 text-xs font-medium text-rose-700 transition hover:bg-rose-50">
                          Delete
                        </button>
                      </form>
                    </div>
                  </td>
                </tr>
              @endfor
              </tbody>
            </table>
          </div>
        @endif
      </div>
    </section>

  </div>
`)
