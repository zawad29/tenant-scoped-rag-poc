@import com.example.ragpoc.cid.entity.Cid
@param Cid cid
@param String cidName
@param String email
@param String role
@param boolean isCidAdmin
@param String csrfParameterName
@param String csrfToken
@param String active
@param String message
@template.layout.app(title = "Organisation profile", active = active, cidName = cidName, email = email, role = role, isCidAdmin = isCidAdmin, csrfToken = csrfToken, csrfParameterName = csrfParameterName, content = @`
  <div class="mx-auto max-w-2xl space-y-6">

    @if(message != null)
      <div class="rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-800" role="status">
        ${message}
      </div>
    @endif

    <section class="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
      <h2 class="text-sm font-semibold text-slate-900">Organisation details</h2>
      <p class="mt-1 text-sm text-slate-600">
        These details are shown to your users and identify the workspace.
      </p>

      <form method="post" action="/cid/profile" class="mt-4 space-y-4">
        <input type="hidden" name="${csrfParameterName}" value="${csrfToken}">

        <div>
          <label for="name" class="block text-sm font-medium text-slate-700">Name</label>
          <input id="name" name="name" type="text" value="${cid.getName()}" required
                 class="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2 text-sm shadow-sm focus:border-indigo-500 focus:outline-none focus:ring-1 focus:ring-indigo-500">
        </div>

        <div>
          <label for="email" class="block text-sm font-medium text-slate-700">Contact email</label>
          <input id="email" name="email" type="email"
                 value="${cid.getEmail() != null ? cid.getEmail() : ""}"
                 class="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2 text-sm shadow-sm focus:border-indigo-500 focus:outline-none focus:ring-1 focus:ring-indigo-500">
        </div>

        <div>
          <label for="address" class="block text-sm font-medium text-slate-700">Address</label>
          <textarea id="address" name="address" rows="3"
                    class="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2 text-sm shadow-sm focus:border-indigo-500 focus:outline-none focus:ring-1 focus:ring-indigo-500">${cid.getAddress() != null ? cid.getAddress() : ""}</textarea>
        </div>

        <button type="submit"
                class="inline-flex items-center rounded-md bg-indigo-600 px-4 py-2 text-sm font-medium text-white transition hover:bg-indigo-700">
          Save
        </button>
      </form>
    </section>

  </div>
`)
