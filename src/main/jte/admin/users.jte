@import com.example.ragpoc.user.entity.User
@import com.example.ragpoc.user.entity.Role
@param java.util.List<User> users
@param Role[] roles
@param String cidName
@param String email
@param String role
@param boolean isCidAdmin
@param String csrfToken
@param String csrfParameterName
@param String active
@param String message
@param String error
@template.layout.app(title = "Users", active = active, cidName = cidName, email = email, role = role, isCidAdmin = isCidAdmin, csrfToken = csrfToken, csrfParameterName = csrfParameterName, content = @`
  <div class="mx-auto max-w-4xl space-y-6">

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
      <h2 class="text-sm font-semibold text-slate-900">Add a user</h2>
      <p class="mt-1 text-sm text-slate-600">
        New users belong to ${cidName} and can only see its documents.
      </p>
      <form method="post" action="/admin/users" class="mt-4 grid gap-4 sm:grid-cols-3">
        <input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
        <div>
          <label for="email" class="block text-sm font-medium text-slate-700">Email</label>
          <input id="email" name="email" type="email" required
                 class="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2 text-sm shadow-sm focus:border-indigo-500 focus:outline-none focus:ring-1 focus:ring-indigo-500">
        </div>
        <div>
          <label for="password" class="block text-sm font-medium text-slate-700">Temporary password</label>
          <input id="password" name="password" type="password" required
                 class="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2 text-sm shadow-sm focus:border-indigo-500 focus:outline-none focus:ring-1 focus:ring-indigo-500">
        </div>
        <div>
          <label for="role" class="block text-sm font-medium text-slate-700">Role</label>
          <select id="role" name="role"
                  class="mt-1 block w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-indigo-500 focus:outline-none focus:ring-1 focus:ring-indigo-500">
            @for(Role option : roles)
              <option value="${option.name()}">${option.name()}</option>
            @endfor
          </select>
        </div>
        <div class="sm:col-span-3">
          <button type="submit"
                  class="inline-flex items-center rounded-md bg-indigo-600 px-4 py-2 text-sm font-medium text-white transition hover:bg-indigo-700">
            Add user
          </button>
        </div>
      </form>
    </section>

    <section class="rounded-xl border border-slate-200 bg-white shadow-sm">
      <div class="border-b border-slate-200 px-6 py-4">
        <h2 class="text-sm font-semibold text-slate-900">Existing users</h2>
      </div>
      <div class="px-6 py-4">
        <div class="overflow-x-auto">
          <table class="w-full text-left text-sm">
            <thead class="text-xs uppercase tracking-wide text-slate-500">
            <tr>
              <th class="py-2 pr-4 font-medium">Email</th>
              <th class="py-2 pr-4 font-medium">Role</th>
              <th class="py-2 font-medium">Enabled</th>
            </tr>
            </thead>
            <tbody class="divide-y divide-slate-100">
            @for(User member : users)
              <tr>
                <td class="py-3 pr-4 text-slate-800">${member.getEmail()}</td>
                <td class="py-3 pr-4 text-slate-600">${member.getRole().name()}</td>
                <td class="py-3">
                  @if(member.isEnabled())
                    <span class="inline-flex rounded-full bg-emerald-100 px-2 py-0.5 text-xs font-medium text-emerald-800">yes</span>
                  @else
                    <span class="inline-flex rounded-full bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-600">no</span>
                  @endif
                </td>
              </tr>
            @endfor
            </tbody>
          </table>
        </div>
      </div>
    </section>

  </div>
`)
