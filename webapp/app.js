const WA = window.WebApp;
const params = new URLSearchParams(location.search);
const DEV_USER = params.get("devUser");          // локальная отладка вне MAX (нужен DEV_AUTH=true на бэкенде)

async function api(path, opts = {}) {
  const headers = { ...(opts.headers || {}) };
  if (WA && WA.initData) headers["X-Max-Init-Data"] = WA.initData;
  else if (DEV_USER) headers["X-Dev-User-Id"] = DEV_USER;
  if (opts.json !== undefined) { headers["Content-Type"] = "application/json"; opts.body = JSON.stringify(opts.json); }
  const r = await fetch(window.API_BASE + path, { ...opts, headers });
  if (!r.ok) { const e = await r.json().catch(() => ({ message: r.statusText })); throw new Error(e.message); }
  return r.status === 204 ? null : r.json();
}

let state = { me: null, act: null, tab: "items" };

async function start() {
  try { state.me = await api("/api/me"); render(); }
  catch (e) { document.getElementById("app").textContent = "Ошибка: " + e.message; }
  if (WA) { WA.ready(); WA.expand && WA.expand(); }
}

function render() {
  const app = document.getElementById("app");
  app.innerHTML = "";

  const info = document.createElement("div");
  info.textContent = `userId: ${state.me.userId}, имя: ${state.me.name}, роли: ${state.me.roles.join(", ") || "нет"}`;
  app.appendChild(info);

  const fileInput = document.createElement("input");
  fileInput.type = "file";
  fileInput.accept = "image/*";
  app.appendChild(fileInput);
}

start();
