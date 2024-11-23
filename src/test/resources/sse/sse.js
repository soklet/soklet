(() => {
  const EVENT_SOURCE_URL = "http://localhost:8081/examples/abc";
  const FIRE_SERVER_SENT_EVENT_URL =
    "http://localhost:8080/fire-server-sent-event";
  const SHUTDOWN_URL = "http://localhost:8080/shutdown";
  const buttonRegisterEventSource = document.getElementById(
    "button-register-event-source"
  );
  const buttonUnregisterEventSource = document.getElementById(
    "button-unregister-event-source"
  );
  const buttonFireServerSentEvent = document.getElementById(
    "button-fire-server-sent-event"
  );
  const buttonShutDownServer = document.getElementById(
    "button-shut-down-server"
  );
  const listEvents = document.getElementById("list-events");
  let eventSource = undefined;

  const log = (message) => {
    const newElement = document.createElement("li");
    newElement.textContent = `${new Date().toISOString()} ${message}`;

    if (listEvents.firstChild)
      listEvents.insertBefore(newElement, listEvents.firstChild);
    else listEvents.appendChild(newElement);
  };

  buttonShutDownServer.addEventListener("click", (e) => {
    log("Shutting down server...");

    window
      .fetch(SHUTDOWN_URL, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: undefined,
      })
      .then((response) => {
        try {
          response.text();
          log("Shutdown complete.");
        } catch (responseError) {
          log("Unable to read shutdown response.");
        }
      })
      .catch((error) => {
        log("Unable to perform shutdown.");
      });
  });

  buttonFireServerSentEvent.addEventListener("click", (e) => {
    log("Firing server-sent event...");

    window
      .fetch(FIRE_SERVER_SENT_EVENT_URL, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: undefined,
      })
      .then((response) => {
        try {
          response.text();
          log("Fired server-sent event.");
        } catch (responseError) {
          log("Unable to read fire server-sent event response.");
        }
      })
      .catch((error) => {
        log("Unable to fire server-sent event.");
      });
  });

  buttonRegisterEventSource.addEventListener("click", (e) => {
    eventSource = new EventSource(EVENT_SOURCE_URL, {
      withCredentials: true,
    });

    eventSource.addEventListener("open", (e) => {
      log(`EventSource connection opened for ${EVENT_SOURCE_URL}`);
    });

    eventSource.addEventListener("test", (e) => {
      log(`test: ${e.data}`);
      // console.log(JSON.parse(e.data));
    });

    eventSource.addEventListener("error", (e) => {
      log(`EventSource error for ${EVENT_SOURCE_URL}`);
    });

    buttonRegisterEventSource.disabled = true;
    buttonUnregisterEventSource.disabled = false;

    log(`Registered event source at ${EVENT_SOURCE_URL}`);
  });

  buttonUnregisterEventSource.addEventListener("click", (e) => {
    eventSource.close();
    eventSource = undefined;
    buttonRegisterEventSource.disabled = false;
    buttonUnregisterEventSource.disabled = true;

    log(`Unregistered event source at ${EVENT_SOURCE_URL}`);
  });
})();
