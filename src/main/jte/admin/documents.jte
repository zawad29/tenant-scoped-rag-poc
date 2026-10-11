@import com.example.ragpoc.document.DocumentSummary
@import com.example.ragpoc.web.ViewFormat
@param java.util.List<DocumentSummary> rows
@param String cidName
@param String csrfToken
@param String csrfParameterName
@param String message
@param String error
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Documents &middot; ${cidName}</title>
  <link rel="stylesheet" href="/css/app.css">
  <link rel="stylesheet" href="/css/admin.css">
</head>
<body>
<header>
  <strong>${cidName}</strong>
  <span class="who">Documents</span>
  <a href="/">Back</a>
  <form method="post" action="/logout">
    <input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
    <button type="submit" class="link">Sign out</button>
  </form>
</header>

<main>
  <h1>Documents</h1>
  <p class="muted">
    Uploaded PDFs are indexed for your organisation only. Indexing runs while the
    upload is in progress, so the list below is already up to date.
  </p>

  @if(message != null)
    <p class="notice" role="status">${message}</p>
  @endif
  @if(error != null)
    <p class="alert" role="alert">${error}</p>
  @endif

  <form method="post" action="/admin/documents" enctype="multipart/form-data" class="upload">
    <input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
    <label for="title">Title (optional)</label>
    <input id="title" name="title" type="text" placeholder="Leave blank to use the file name">
    <label for="file">PDF file</label>
    <input id="file" name="file" type="file" accept="application/pdf,.pdf" required>
    <button type="submit">Upload</button>
  </form>

  <h2>Indexed documents</h2>
  @if(rows.isEmpty())
    <p class="muted">No documents yet. Upload a PDF to get started.</p>
  @else
    <table>
      <thead>
      <tr>
        <th>Title</th>
        <th>Status</th>
        <th>Version</th>
        <th>Pages</th>
        <th>Chunks</th>
        <th>Updated</th>
        <th></th>
      </tr>
      </thead>
      <tbody>
      @for(DocumentSummary row : rows)
        <tr>
          <td>
            <strong>${row.title()}</strong>
            <div class="muted small">${row.originalFilename()}</div>
          </td>
          <td><span class="${ViewFormat.statusClass(row.status())}">${row.status()}</span></td>
          <td>${ViewFormat.version(row.currentVersion())}</td>
          <td>${ViewFormat.count(row.pageCount())}</td>
          <td>${ViewFormat.count(row.chunkCount())}</td>
          <td class="muted small">${ViewFormat.time(row.updatedAt())}</td>
          <td class="actions">
            <form method="post" action="/admin/documents/${ViewFormat.id(row.id())}/versions" enctype="multipart/form-data">
              <input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
              <input type="file" name="file" accept="application/pdf,.pdf" required>
              <button type="submit">Replace</button>
            </form>
            <form method="post" action="/admin/documents/${ViewFormat.id(row.id())}/delete"
                  onsubmit="return confirm('Delete this document and all of its indexed content?')">
              <input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
              <button type="submit" class="danger">Delete</button>
            </form>
          </td>
        </tr>
      @endfor
      </tbody>
    </table>
  @endif
</main>
</body>
</html>
