@import com.example.ragpoc.user.entity.User
@import com.example.ragpoc.user.entity.Role
@param java.util.List<User> users
@param Role[] roles
@param String csrfToken
@param String csrfParameterName
@param String message
@param String error
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Users</title>
  <link rel="stylesheet" href="/css/app.css">
  <link rel="stylesheet" href="/css/admin.css">
</head>
<body>
<header>
  <strong>Users</strong>
  <a href="/">Back</a>
  <form method="post" action="/logout">
    <input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
    <button type="submit" class="link">Sign out</button>
  </form>
</header>

<main>
  <h1>Users</h1>

  @if(message != null)
    <p class="notice" role="status">${message}</p>
  @endif
  @if(error != null)
    <p class="alert" role="alert">${error}</p>
  @endif

  <form method="post" action="/admin/users" class="upload">
    <input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
    <label for="email">Email</label>
    <input id="email" name="email" type="email" required>
    <label for="password">Temporary password</label>
    <input id="password" name="password" type="password" required>
    <label for="role">Role</label>
    <select id="role" name="role">
      @for(Role role : roles)
        <option value="${role.name()}">${role.name()}</option>
      @endfor
    </select>
    <button type="submit">Add user</button>
  </form>

  <h2>Existing users</h2>
  <table>
    <thead>
    <tr><th>Email</th><th>Role</th><th>Enabled</th></tr>
    </thead>
    <tbody>
    @for(User user : users)
      <tr>
        <td>${user.getEmail()}</td>
        <td>${user.getRole().name()}</td>
        <td>${user.isEnabled()}</td>
      </tr>
    @endfor
    </tbody>
  </table>
</main>
</body>
</html>
