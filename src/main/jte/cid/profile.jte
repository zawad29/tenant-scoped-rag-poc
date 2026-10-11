@import com.example.ragpoc.cid.entity.Cid
@param Cid cid
@param String csrfParameterName
@param String csrfToken
@param String message
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Organisation profile</title>
  <link rel="stylesheet" href="/css/app.css">
</head>
<body>
<header>
  <strong>${cid.getName()}</strong>
  <a href="/">Back</a>
  <form method="post" action="/logout">
    <input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
    <button type="submit" class="link">Sign out</button>
  </form>
</header>

<main>
  <h1>Organisation profile</h1>

  @if(message != null)
    <p class="notice" role="status">${message}</p>
  @endif

  <form method="post" action="/cid/profile">
    <input type="hidden" name="${csrfParameterName}" value="${csrfToken}">

    <label for="name">Name</label>
    <input id="name" name="name" type="text" value="${cid.getName()}" required>

    <label for="email">Email</label>
    <input id="email" name="email" type="email" value="${cid.getEmail() != null ? cid.getEmail() : ""}">

    <label for="address">Address</label>
    <textarea id="address" name="address" rows="3">${cid.getAddress() != null ? cid.getAddress() : ""}</textarea>

    <button type="submit">Save</button>
  </form>
</main>
</body>
</html>
