@param String cidName
@param String email
@param String role
@param boolean isCidAdmin
@param String csrfParameterName
@param String csrfToken
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${cidName}</title>
  <link rel="stylesheet" href="/css/app.css">
</head>
<body>
<header>
  <strong>${cidName}</strong>
  <span class="who">${email} &middot; ${role}</span>
  <form method="post" action="/logout">
    <input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
    <button type="submit" class="link">Sign out</button>
  </form>
</header>

<main>
  <h1>Document assistant</h1>
  <p class="muted">
    This is your organisation's own workspace. It can only ever answer from
    documents uploaded by ${cidName}.
  </p>

  <nav>
    <a href="/chat">Chat &amp; retrieval</a>
    @if(isCidAdmin)
      <a href="/admin/documents">Documents</a>
      <a href="/admin/users">Users</a>
    @endif
    <a href="/cid/profile">Organisation profile</a>
  </nav>
</main>
</body>
</html>
