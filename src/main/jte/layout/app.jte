@import com.example.ragpoc.web.ViewFormat
@param String title
@param String active
@param String cidName
@param String email
@param String role
@param boolean isCidAdmin
@param String csrfToken
@param String csrfParameterName
@param gg.jte.Content content
<!DOCTYPE html>
<html lang="en" class="h-full">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${title} &middot; ${cidName}</title>
  <!--
    Tailwind's Play CDN builds the stylesheet in the browser, so the app ships no
    generated CSS. It needs internet in the browser; the server itself stays
    offline. Swapping it for a compiled stylesheet is a link change.
  -->
  <script src="https://cdn.tailwindcss.com"></script>
  <script src="/js/htmx.min.js" defer></script>
  <script src="/js/chat.js" defer></script>
  <style>
    /* The streaming caret is plain CSS on purpose: chat.js adds it after load,
       and a utility class the CDN has not seen yet would arrive unstyled. */
    .streaming-cursor::after { content: "\258D"; animation: caret 1s step-start infinite; }
    @keyframes caret { 50% { opacity: 0; } }
  </style>
</head>
<body class="h-full bg-slate-100 text-slate-800 antialiased">
<!--
  Classes that chat.js applies to elements it creates. Listing them here (on a
  hidden node) is what makes the Play CDN emit them, since they never appear in
  server-rendered markup.
-->
<div hidden
     class="ml-auto max-w-[85%] rounded-2xl rounded-br-sm bg-indigo-600 px-4 py-2.5 text-sm leading-relaxed text-white max-w-[92%] rounded-2xl rounded-bl-sm border border-slate-200 bg-white px-4 py-2.5 text-sm leading-relaxed text-slate-800 shadow-sm whitespace-pre-wrap streaming-cursor text-slate-500 mt-2 mb-3 rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-800"></div>

<div id="nav-progress" class="htmx-indicator fixed inset-x-0 top-0 z-50 h-0.5 bg-indigo-500"></div>

<div class="flex min-h-full flex-col md:flex-row">

  <!-- Sidebar -->
  <aside class="w-full shrink-0 bg-slate-900 text-slate-100 md:flex md:w-64 md:flex-col">
    <div class="flex items-center gap-3 px-4 py-4">
      <span class="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-indigo-500 text-sm font-bold">R</span>
      <span class="min-w-0">
        <span class="block truncate text-sm font-semibold">${cidName}</span>
        <span class="block text-xs text-slate-400">Document assistant</span>
      </span>
    </div>

    <nav class="flex flex-wrap gap-1 px-3 pb-3 md:flex-1 md:flex-col md:flex-nowrap"
         hx-boost="true" hx-indicator="#nav-progress">
      <a href="/" class="${ViewFormat.navClass(active, "home")}">
        <svg class="h-5 w-5 shrink-0" fill="none" stroke="currentColor" stroke-width="1.6" viewBox="0 0 24 24" aria-hidden="true">
          <path stroke-linecap="round" stroke-linejoin="round" d="M2.25 12l8.954-8.955c.44-.439 1.152-.439 1.591 0L21.75 12M4.5 9.75v10.125c0 .621.504 1.125 1.125 1.125H9.75v-4.875c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125V21h4.125c.621 0 1.125-.504 1.125-1.125V9.75"/>
        </svg>
        <span>Home</span>
      </a>
      <a href="/chat" class="${ViewFormat.navClass(active, "chat")}">
        <svg class="h-5 w-5 shrink-0" fill="none" stroke="currentColor" stroke-width="1.6" viewBox="0 0 24 24" aria-hidden="true">
          <path stroke-linecap="round" stroke-linejoin="round" d="M8.625 12a.375.375 0 11-.75 0 .375.375 0 01.75 0zm0 0H8.25m4.125 0a.375.375 0 11-.75 0 .375.375 0 01.75 0zm0 0H12m4.125 0a.375.375 0 11-.75 0 .375.375 0 01.75 0zm0 0h-.375M21 12c0 4.556-4.03 8.25-9 8.25a9.76 9.76 0 01-2.555-.337A5.97 5.97 0 015.41 20.97a5.97 5.97 0 01-.474-.065 4.48 4.48 0 00.978-2.025c.09-.457-.133-.901-.467-1.226C3.93 16.178 3 14.189 3 12c0-4.556 4.03-8.25 9-8.25s9 3.694 9 8.25z"/>
        </svg>
        <span>Chat</span>
      </a>
      @if(isCidAdmin)
        <a href="/admin/documents" class="${ViewFormat.navClass(active, "documents")}">
          <svg class="h-5 w-5 shrink-0" fill="none" stroke="currentColor" stroke-width="1.6" viewBox="0 0 24 24" aria-hidden="true">
            <path stroke-linecap="round" stroke-linejoin="round" d="M19.5 14.25v-2.625a3.375 3.375 0 00-3.375-3.375h-1.5A1.125 1.125 0 0113.5 7.125v-1.5A3.375 3.375 0 0010.125 2.25H8.25m2.25 0H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 00-9-9z"/>
          </svg>
          <span>Documents</span>
        </a>
        <a href="/admin/users" class="${ViewFormat.navClass(active, "users")}">
          <svg class="h-5 w-5 shrink-0" fill="none" stroke="currentColor" stroke-width="1.6" viewBox="0 0 24 24" aria-hidden="true">
            <path stroke-linecap="round" stroke-linejoin="round" d="M15 19.128a9.38 9.38 0 002.625.372 9.337 9.337 0 004.121-.952 4.125 4.125 0 00-7.533-2.493M15 19.128v-.003c0-1.113-.285-2.16-.786-3.07M15 19.128v.106A12.32 12.32 0 018.624 21c-2.331 0-4.512-.645-6.374-1.766l-.001-.109a6.375 6.375 0 0111.964-3.07M12 6.375a3.375 3.375 0 11-6.75 0 3.375 3.375 0 016.75 0zm8.25 2.25a2.625 2.625 0 11-5.25 0 2.625 2.625 0 015.25 0z"/>
          </svg>
          <span>Users</span>
        </a>
      @endif
      <a href="/cid/profile" class="${ViewFormat.navClass(active, "profile")}">
        <svg class="h-5 w-5 shrink-0" fill="none" stroke="currentColor" stroke-width="1.6" viewBox="0 0 24 24" aria-hidden="true">
          <path stroke-linecap="round" stroke-linejoin="round" d="M3.75 21h16.5M4.5 3h15M5.25 3v18m13.5-18v18M9 6.75h1.5m-1.5 3h1.5m-1.5 3h1.5m3-6H15m-1.5 3H15m-1.5 3H15M9 21v-3.375c0-.621.504-1.125 1.125-1.125h3.75c.621 0 1.125.504 1.125 1.125V21"/>
        </svg>
        <span>Organisation</span>
      </a>
    </nav>

    <div class="border-t border-slate-800 px-4 py-3">
      <div class="truncate text-xs text-slate-300" title="${email}">${email}</div>
      <div class="text-xs text-slate-500">${role}</div>
      <form method="post" action="/logout" class="mt-2">
        <input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
        <button type="submit"
                class="inline-flex w-full items-center gap-2 rounded-md border border-slate-700 px-3 py-1.5 text-xs font-medium text-slate-300 transition hover:border-slate-500 hover:text-white">
          <svg class="h-4 w-4" fill="none" stroke="currentColor" stroke-width="1.6" viewBox="0 0 24 24" aria-hidden="true">
            <path stroke-linecap="round" stroke-linejoin="round" d="M15.75 9V5.25A2.25 2.25 0 0013.5 3h-6a2.25 2.25 0 00-2.25 2.25v13.5A2.25 2.25 0 007.5 21h6a2.25 2.25 0 002.25-2.25V15m3 0l3-3m0 0l-3-3m3 3H9"/>
          </svg>
          Sign out
        </button>
      </form>
    </div>
  </aside>

  <!-- Content -->
  <div class="flex min-w-0 flex-1 flex-col">
    <header class="sticky top-0 z-20 flex items-center gap-3 border-b border-slate-200 bg-white/90 px-4 py-3 backdrop-blur md:px-6">
      <button type="button"
              onclick="if (history.length > 1) { history.back(); } else { window.location.href = '/'; }"
              class="inline-flex items-center gap-1 rounded-md border border-slate-300 bg-white px-2.5 py-1.5 text-sm font-medium text-slate-600 transition hover:bg-slate-50 hover:text-slate-900">
        <svg class="h-4 w-4" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24" aria-hidden="true">
          <path stroke-linecap="round" stroke-linejoin="round" d="M15.75 19.5L8.25 12l7.5-7.5"/>
        </svg>
        Back
      </button>
      <h1 class="truncate text-base font-semibold text-slate-900">${title}</h1>
    </header>

    <main class="flex-1 p-4 md:p-6">
      ${content}
    </main>
  </div>
</div>
</body>
</html>
