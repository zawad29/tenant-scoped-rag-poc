@param String csrfToken
@param String csrfParameterName
@param boolean loginFailed
@param boolean loggedOut
<!DOCTYPE html>
<html lang="en" class="h-full">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Sign in</title>
  <script src="https://cdn.tailwindcss.com"></script>
</head>
<body class="flex min-h-full items-center justify-center bg-slate-100 p-4 text-slate-800 antialiased">
<main class="w-full max-w-sm rounded-xl border border-slate-200 bg-white p-6 shadow-sm">

  <div class="mb-6 flex items-center gap-3">
    <span class="flex h-9 w-9 items-center justify-center rounded-lg bg-indigo-600 text-sm font-bold text-white">R</span>
    <span>
      <h1 class="text-base font-semibold text-slate-900">Sign in</h1>
      <span class="block text-xs text-slate-500">Document assistant</span>
    </span>
  </div>

  @if(loginFailed)
    <div class="mb-4 rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-800" role="alert">
      Incorrect email or password.
    </div>
  @endif

  @if(loggedOut)
    <div class="mb-4 rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-600" role="status">
      You have been signed out.
    </div>
  @endif

  <form method="post" action="/login" class="space-y-4">
    <input type="hidden" name="${csrfParameterName}" value="${csrfToken}">

    <div>
      <label for="username" class="block text-sm font-medium text-slate-700">Email</label>
      <input id="username" name="username" type="email" autocomplete="username" required autofocus
             class="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2 text-sm shadow-sm focus:border-indigo-500 focus:outline-none focus:ring-1 focus:ring-indigo-500">
    </div>

    <div>
      <label for="password" class="block text-sm font-medium text-slate-700">Password</label>
      <input id="password" name="password" type="password" autocomplete="current-password" required
             class="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2 text-sm shadow-sm focus:border-indigo-500 focus:outline-none focus:ring-1 focus:ring-indigo-500">
    </div>

    <button type="submit"
            class="w-full rounded-md bg-indigo-600 px-4 py-2 text-sm font-medium text-white transition hover:bg-indigo-700">
      Sign in
    </button>
  </form>
</main>
</body>
</html>
