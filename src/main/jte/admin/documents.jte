@import com.example.ragpoc.document.DocumentRepository.DocumentSummary
@param java.util.List<DocumentSummary> rows
@param boolean anyInProgress
@param String tenantName
@param String csrfToken
@param String csrfParameterName
@param String message
@param String error
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Documents &middot; ${tenantName}</title>
  <link rel="stylesheet" href="/css/app.css">
  <link rel="stylesheet" href="/css/admin.css">
  <script src="/js/htmx.min.js" defer></script>
</head>
<body>
<header>
  <strong>${tenantName}</strong>
  <span class="who">Documents</span>
  <a href="/">Back to chat</a>
  <form method="post" action="/logout">
    <input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
    <button type="submit" class="link">Sign out</button>
  </form>
</header>

<main>
  <h1>Documents</h1>
  <p class="muted">
    Uploaded PDFs are indexed for your organisation only. Ingestion runs in the
    background; the table refreshes itself while work is in progress.
  </p>

  @if(message != null)
    <p class="notice" role="status">${message}</p>
  @endif
  @if(error != null)
    <p class="alert" role="alert">${error}</p>
  @endif

  <!--
    The hidden token works because MultipartConfig registers MultipartFilter
    ahead of the security chain, so the body is already parsed into request
    parameters by the time CsrfFilter looks for it. See docs/decisions.md D16.
  -->
  <form method="post" action="/admin/documents" enctype="multipart/form-data" class="upload">
    <input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
    <label for="title">Title (optional)</label>
    <input id="title" name="title" type="text" placeholder="Leave blank to use the file name">
    <label for="file">PDF file</label>
    <input id="file" name="file" type="file" accept="application/pdf,.pdf" required>
    <button type="submit">Upload</button>
  </form>

  <h2>Indexed documents</h2>
  @template.admin.documentRows(rows = rows, anyInProgress = anyInProgress, csrfParameterName = csrfParameterName, csrfToken = csrfToken)
</main>
</body>
</html>
