/*
 * Chat streaming: a small progressive enhancement.
 *
 * The form still POSTs to /chat without this file. When it runs, the question
 * goes to /chat/stream and what arrives is applied as it comes: the retrieval
 * panel as an HTML fragment, the answer one delta at a time.
 *
 * EventSource is not used because it can only issue GETs, and both the question
 * and the CSRF token belong in a request body. The server-sent-event frames are
 * therefore parsed from fetch's response stream, which is the whole reason this
 * file exists.
 */
(function () {
  "use strict";

  var form = document.getElementById("chat-form");
  var conversation = document.getElementById("conversation");
  var panel = document.getElementById("retrieval-panel");

  if (!form || !conversation || !panel || !window.fetch || !window.TextDecoder) {
    return; // No streaming: leave the plain form submit alone.
  }

  var input = form.querySelector("textarea[name=question]");
  var button = form.querySelector("button[type=submit]");
  var streamUrl = form.getAttribute("data-stream-url") || "/chat/stream";
  var csrfName = form.getAttribute("data-csrf-name");
  var csrfValue = form.getAttribute("data-csrf-value");
  var failed = false;

  form.addEventListener("submit", function (event) {
    event.preventDefault();
    ask(input.value.trim());
  });

  function ask(question) {
    if (!question) {
      return;
    }
    failed = false;

    var empty = conversation.querySelector(".empty-conversation");
    if (empty) {
      empty.remove();
    }
    input.value = "";
    button.disabled = true;

    appendBubble("user", question);
    var bubble = appendBubble("assistant", "");
    var answer = bubble.querySelector(".answer");
    var pending = document.createElement("div");
    pending.className = "muted small pending";
    pending.textContent = "Searching your documents…";
    bubble.insertBefore(pending, answer);
    scrollToLatest();

    send(question)
      .then(function (response) {
        if (response.redirected) {
          throw new Error("session");
        }
        if (!response.ok || !response.body) {
          throw new Error("http");
        }
        return read(response.body.getReader(), {
          panel: function (data) {
            panel.innerHTML = data.html;
          },
          delta: function (data) {
            remove(pending);
            answer.appendChild(document.createTextNode(data.text));
            scrollToLatest();
          },
          error: function (data) {
            reportFailure(bubble, data.message);
          }
        });
      })
      .catch(function (error) {
        // A redirect means the session expired and the request landed on /login.
        reportFailure(
          bubble,
          error && error.message === "session"
            ? "Your session expired. Reload the page and sign in again."
            : "The answer could not be streamed. Please try again."
        );
      })
      .then(function () {
        remove(pending);
        bubble.classList.remove("streaming");
        button.disabled = false;
        input.focus();
      });
  }

  function send(question) {
    var body = new URLSearchParams();
    body.set("question", question);
    if (csrfName && csrfValue) {
      body.set(csrfName, csrfValue);
    }
    return fetch(streamUrl, {
      method: "POST",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded",
        Accept: "text/event-stream"
      },
      body: body.toString(),
      credentials: "same-origin"
    });
  }

  /* --- server-sent events ------------------------------------------------ */

  function read(reader, handlers) {
    var decoder = new TextDecoder();
    var buffer = "";

    function pump() {
      return reader.read().then(function (result) {
        if (result.done) {
          dispatch(buffer, handlers);
          return;
        }
        buffer += decoder.decode(result.value, { stream: true });
        var boundary;
        while ((boundary = buffer.indexOf("\n\n")) >= 0) {
          dispatch(buffer.slice(0, boundary), handlers);
          buffer = buffer.slice(boundary + 2);
        }
        return pump();
      });
    }

    return pump();
  }

  function dispatch(block, handlers) {
    if (!block) {
      return;
    }
    var name = "message";
    var data = "";
    block.replace(/\r\n/g, "\n").split("\n").forEach(function (line) {
      if (line.indexOf("event:") === 0) {
        name = line.slice(6).trim();
      } else if (line.indexOf("data:") === 0) {
        data += line.slice(5).replace(/^ /, "");
      }
    });

    var handler = handlers[name];
    if (!handler || !data) {
      return;
    }
    try {
      handler(JSON.parse(data));
    } catch (e) {
      // An unrecognised frame is ignored rather than breaking the stream.
    }
  }

  /* --- dom --------------------------------------------------------------- */

  function appendBubble(kind, text) {
    var bubble = document.createElement("div");
    bubble.className = "bubble " + kind;
    if (kind === "assistant") {
      bubble.classList.add("streaming");
    }
    var span = document.createElement("span");
    span.className = "answer";
    span.textContent = text;
    bubble.appendChild(span);
    conversation.appendChild(bubble);
    return bubble;
  }

  function reportFailure(bubble, message) {
    if (failed) {
      return;
    }
    failed = true;
    remove(bubble.querySelector(".pending"));
    bubble.classList.remove("streaming");
    var alert = document.createElement("div");
    alert.className = "alert";
    alert.textContent = message;
    bubble.appendChild(alert);

    var status = panel.querySelector(".generation-status");
    if (status) {
      status.textContent = "not generated";
      status.classList.remove("muted");
    }
    var notice = document.createElement("p");
    notice.className = "alert";
    notice.textContent = message;
    panel.insertBefore(notice, panel.firstChild);
  }

  function remove(element) {
    if (element && element.parentNode) {
      element.parentNode.removeChild(element);
    }
  }

  function scrollToLatest() {
    conversation.scrollTop = conversation.scrollHeight;
  }
})();
