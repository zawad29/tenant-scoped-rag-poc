/*
 * Chat streaming: a small progressive enhancement.
 *
 * The form still POSTs to /chat without this file. When it runs, the question
 * goes to /chat/stream and what arrives is applied as it comes: the retrieval
 * panel as an HTML fragment, the answer one delta at a time, the generation
 * timings as a narrower fragment that leaves the panel's open sections alone.
 *
 * EventSource is not used because it can only issue GETs, and both the question
 * and the CSRF token belong in a request body. The server-sent-event frames are
 * therefore parsed from fetch's response stream, which is the whole reason this
 * file exists.
 *
 * The submit handler is delegated from the document because htmx's boosted
 * navigation replaces the page body; listeners bound to the form itself would be
 * thrown away with it.
 */
(function () {
  "use strict";

  if (!window.fetch || !window.TextDecoder) {
    return; // No streaming: leave the plain form submit alone.
  }

  document.addEventListener("submit", function (event) {
    var form = event.target;
    if (!form || form.id !== "chat-form") {
      return;
    }
    event.preventDefault();
    ask(form);
  });

  function ask(form) {
    var conversation = document.getElementById("conversation");
    var panel = document.getElementById("retrieval-panel");
    var input = form.querySelector("textarea[name=question]");
    var button = form.querySelector("button[type=submit]");
    var question = input ? input.value.trim() : "";

    if (!conversation || !panel || !question) {
      return;
    }

    var empty = conversation.querySelector(".empty-conversation");
    if (empty) {
      empty.remove();
    }
    input.value = "";
    if (button) {
      button.disabled = true;
    }

    appendBubble(conversation, "tpl-user-bubble", question);
    var bubble = appendBubble(conversation, "tpl-assistant-bubble", null);
    var answer = bubble.querySelector("[data-role=answer]");
    var pending = bubble.querySelector("[data-role=pending]");
    scrollTo(bubble);

    var failed = false;
    function fail(message) {
      if (failed) {
        return;
      }
      failed = true;
      remove(pending);
      bubble.classList.remove("streaming-cursor");
      var alert = document.createElement("div");
      alert.className = "mt-2 mb-3 rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-800";
      alert.textContent = message;
      bubble.appendChild(alert);

      var timings = panel.querySelector("#generation-timings");
      if (timings) {
        timings.textContent = "No answer was generated.";
      }
      var notice = document.createElement("div");
      notice.className = "mt-2 mb-3 rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-800";
      notice.textContent = message;
      panel.insertBefore(notice, panel.firstChild);
    }

    send(form, question)
      .then(function (response) {
        if (response.redirected) {
          throw new Error("session"); // The request landed on /login.
        }
        if (!response.ok || !response.body) {
          throw new Error("http");
        }
        return read(response.body.getReader(), {
          panel: function (data) {
            panel.innerHTML = data.html;
            scrollTo(bubble);
          },
          delta: function (data) {
            remove(pending);
            bubble.classList.add("streaming-cursor");
            answer.appendChild(document.createTextNode(data.text));
            scrollTo(bubble);
          },
          timings: function (data) {
            var target = document.getElementById("generation-timings");
            if (target) {
              target.innerHTML = data.html;
            }
          },
          error: function (data) {
            fail(data.message);
          }
        });
      })
      .catch(function (error) {
        fail(
          error && error.message === "session"
            ? "Your session expired. Reload the page and sign in again."
            : "The answer could not be streamed. Please try again."
        );
      })
      .then(function () {
        remove(pending);
        bubble.classList.remove("streaming-cursor");
        if (button) {
          button.disabled = false;
        }
        if (input) {
          input.focus();
        }
      });
  }

  function send(form, question) {
    var body = new URLSearchParams();
    body.set("question", question);

    var csrfName = form.getAttribute("data-csrf-name");
    var csrfValue = form.getAttribute("data-csrf-value");
    if (csrfName && csrfValue) {
      body.set(csrfName, csrfValue);
    }

    return fetch(form.getAttribute("data-stream-url") || "/chat/stream", {
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

  function appendBubble(conversation, templateId, text) {
    var template = document.getElementById(templateId);
    var bubble = template
      ? template.content.firstElementChild.cloneNode(true)
      : document.createElement("div");

    if (text !== null) {
      var answer = bubble.querySelector("[data-role=answer]");
      if (answer) {
        answer.textContent = text;
      }
    }
    conversation.appendChild(bubble);
    return bubble;
  }

  function remove(element) {
    if (element && element.parentNode) {
      element.parentNode.removeChild(element);
    }
  }

  function scrollTo(element) {
    if (element && element.scrollIntoView) {
      element.scrollIntoView({ block: "nearest" });
    }
  }
})();
