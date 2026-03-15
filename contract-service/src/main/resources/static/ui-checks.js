(() => {
  const STATUS_CLASSES = [
    "pill-pass",
    "pill-fail",
    "pill-running",
    "pill-queued",
    "pill-unknown"
  ];
  const STATUS_CLASS_MAP = {
    PASS: "pill-pass",
    FAIL: "pill-fail",
    RUNNING: "pill-running",
    QUEUED: "pill-queued"
  };
  const ACTIVE_STATUSES = new Set(["QUEUED", "RUNNING"]);

  const resolveIntervalMs = () => {
    const value = Number(document.body.dataset.checkPollIntervalMs);
    if (Number.isFinite(value) && value >= 500) {
      return value;
    }
    return 3000;
  };

  const normalizeStatus = (status) => (status || "").trim().toUpperCase();

  const applyStatus = (element, status) => {
    const normalized = normalizeStatus(status) || "UNKNOWN";
    element.textContent = normalized;
    element.dataset.checkStatus = normalized;
    STATUS_CLASSES.forEach((name) => element.classList.remove(name));
    element.classList.add(STATUS_CLASS_MAP[normalized] || "pill-unknown");
  };

  const collectStatusElements = () => {
    const elements = Array.from(
      document.querySelectorAll("[data-check-run-id][data-check-status]")
    );
    const runs = new Map();
    elements.forEach((element) => {
      const runId = element.dataset.checkRunId;
      if (!runId) {
        return;
      }
      const list = runs.get(runId) || [];
      list.push(element);
      runs.set(runId, list);
    });
    return runs;
  };

  const fetchRun = async (runId) => {
    const response = await fetch(`/checks/${encodeURIComponent(runId)}`, {
      headers: { Accept: "application/json" }
    });
    if (!response.ok) {
      return null;
    }
    return response.json();
  };

  const pollStatuses = async (runs) => {
    const activeRuns = Array.from(runs.entries())
      .filter(([, elements]) =>
        elements.some((el) => ACTIVE_STATUSES.has(normalizeStatus(el.dataset.checkStatus)))
      )
      .map(([runId]) => runId);

    if (activeRuns.length === 0) {
      return false;
    }

    await Promise.all(
      activeRuns.map(async (runId) => {
        const data = await fetchRun(runId);
        if (!data || !data.status) {
          return;
        }
        const elements = runs.get(runId) || [];
        elements.forEach((el) => applyStatus(el, data.status));
      })
    );

    return true;
  };

  const resolveRunStatus = (runs, runId) => {
    const elements = runs.get(runId);
    if (!elements || elements.length === 0) {
      return null;
    }
    return normalizeStatus(elements[0].dataset.checkStatus);
  };

  const fetchLogs = async (runId) => {
    const response = await fetch(`/checks/${encodeURIComponent(runId)}/logs`, {
      headers: { Accept: "application/json" }
    });
    if (!response.ok) {
      return null;
    }
    return response.json();
  };

  const renderLogs = (container, logs) => {
    container.innerHTML = "";
    if (!logs || logs.length === 0) {
      const empty = document.createElement("div");
      empty.className = "muted";
      empty.textContent = "No logs yet.";
      container.appendChild(empty);
      return;
    }
    logs.forEach((log) => {
      const row = document.createElement("div");
      row.className = "log-row";

      const time = document.createElement("span");
      time.className = "log-time";
      time.textContent = log.createdAt || "-";

      const level = document.createElement("span");
      const levelValue = (log.level || "INFO").toUpperCase();
      level.className = `log-level log-level-${levelValue.toLowerCase()}`;
      level.textContent = levelValue;

      const message = document.createElement("span");
      message.className = "log-message";
      message.textContent = log.message || "";

      row.append(time, level, message);
      container.appendChild(row);
    });
  };

  const renderLogError = (container) => {
    container.innerHTML = "";
    const error = document.createElement("div");
    error.className = "muted";
    error.textContent = "Unable to load logs right now.";
    container.appendChild(error);
  };

  const pollLogs = async (runs, containers) => {
    let shouldContinue = false;
    for (const container of containers) {
      const runId = container.dataset.checkLogRunId;
      if (!runId) {
        continue;
      }
      const status = resolveRunStatus(runs, runId);
      const isActive = status ? ACTIVE_STATUSES.has(status) : true;
      if (isActive) {
        shouldContinue = true;
      }
      if (!container.dataset.logsLoaded || isActive) {
        try {
          const logs = await fetchLogs(runId);
          if (logs) {
            renderLogs(container, logs);
            container.dataset.logsLoaded = "true";
          } else {
            renderLogError(container);
          }
        } catch (error) {
          renderLogError(container);
        }
      }
    }
    return shouldContinue;
  };

  const startPolling = () => {
    const runs = collectStatusElements();
    const pollInterval = resolveIntervalMs();

    if (runs.size > 0) {
      pollStatuses(runs).catch(() => {});
      const statusTimer = setInterval(() => {
        pollStatuses(runs)
          .then((shouldContinue) => {
            if (!shouldContinue) {
              clearInterval(statusTimer);
            }
          })
          .catch(() => {});
      }, pollInterval);
    }

    const logContainers = Array.from(
      document.querySelectorAll("[data-check-log-run-id]")
    );
    if (logContainers.length > 0) {
      pollLogs(runs, logContainers).catch(() => {});
      const logTimer = setInterval(() => {
        pollLogs(runs, logContainers)
          .then((shouldContinue) => {
            if (!shouldContinue) {
              clearInterval(logTimer);
            }
          })
          .catch(() => {});
      }, pollInterval);
    }
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", startPolling);
  } else {
    startPolling();
  }
})();
