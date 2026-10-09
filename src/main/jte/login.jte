@param String csrfToken
@param String csrfParameterName
@param boolean loginFailed
@param boolean loggedOut
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Sign in</title>
  <link rel="stylesheet" href="/css/app.css">
</head>
<body class="auth">
<main>
  <h1>Sign in</h1>

  @if(loginFailed)
    <p class="alert" role="alert">Incorrect email or password.</p>
  @endif

  @if(loggedOut)
    <p class="notice" role="status">You have been signed out.</p>
  @endif

  <form method="post" action="/login">
    <input type="hidden" name="${csrfParameterName}" value="${csrfToken}">

    <label for="username">Email</label>
    <input id="username" name="username" type="email" autocomplete="username" required autofocus>

    <label for="password">Password</label>
    <input id="password" name="password" type="password" autocomplete="current-password" required>

    <button type="submit">Sign in</button>
  </form>
</main>
</body>
</html>
