@import com.example.ragpoc.document.DocumentRepository.DocumentSummary
@import com.example.ragpoc.web.ViewFormat
@param java.util.List<DocumentSummary> rows
@param boolean anyInProgress
@param String csrfParameterName
@param String csrfToken
<div id="doc-rows"
     hx-get="/admin/documents/rows"
     hx-trigger="every 2s [document.querySelector('#doc-rows .in-progress')]"
     hx-swap="innerHTML">

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
        <th>Ingestion</th>
        <th>Updated</th>
        <th></th>
      </tr>
      </thead>
      <tbody>
      @for(DocumentSummary row : rows)
        <tr class="${row.inProgress() ? "in-progress" : ""}">
          <td>
            <strong>${row.title()}</strong>
            <div class="muted small">${row.originalFilename()}</div>
          </td>
          <td><span class="${ViewFormat.statusClass(row.status())}">${row.status()}</span></td>
          <td>${ViewFormat.version(row.currentVersion())}</td>
          <td>${ViewFormat.count(row.pageCount())}</td>
          <td>${ViewFormat.count(row.chunkCount())}</td>
          <td>
            ${ViewFormat.jobLabel(row.jobState())}
            @if(row.jobError() != null)
              <div class="error small">${row.jobError()}</div>
            @endif
          </td>
          <td class="muted small">${ViewFormat.time(row.updatedAt())}</td>
          <td class="actions">
            <form method="post" action="/admin/documents/${ViewFormat.id(row.id())}/versions" enctype="multipart/form-data">
              <input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
              <input type="file" name="file" accept="application/pdf,.pdf" required>
              <button type="submit">Replace</button>
            </form>
            <form method="post" action="/admin/documents/${ViewFormat.id(row.id())}/delete">
              <input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
                  onsubmit="return confirm('Delete this document and all of its indexed content?')">
              <button type="submit" class="danger">Delete</button>
            </form>
          </td>
        </tr>
      @endfor
      </tbody>
    </table>
  @endif
</div>
