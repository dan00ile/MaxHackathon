const WA = window.WebApp;
const params = new URLSearchParams(location.search);
const DEV_USER = params.get("devUser");          // локальная отладка вне MAX (нужен DEV_AUTH=true на бэкенде)
const DEV_START = params.get("startapp");         // локальная отладка стартового параметра, напр. ?startapp=act_1

async function api(path, opts = {}) {
  const headers = { ...(opts.headers || {}) };
  if (WA && WA.initData) headers["X-Max-Init-Data"] = WA.initData;
  else if (DEV_USER) headers["X-Dev-User-Id"] = DEV_USER;
  if (opts.json !== undefined) { headers["Content-Type"] = "application/json"; opts.body = JSON.stringify(opts.json); }
  const r = await fetch(window.API_BASE + path, { ...opts, headers });
  if (!r.ok) { const e = await r.json().catch(() => ({ message: r.statusText })); throw new Error(e.message); }
  return r.status === 204 ? null : r.json();
}

const STATUS_RU = {
  RECEIVED: "Получен",
  COLLECTING: "Идёт сбор замечаний",
  REVIEW: "Решение председателя",
  SIGNED: "Подписан",
  REJECTED: "Отказ направлен",
  SILENT: "Принят молчаливым согласием",
};

let state = { me: null, act: null };

function startParam() {
  if (WA && WA.initDataUnsafe && WA.initDataUnsafe.start_param) return WA.initDataUnsafe.start_param;
  return DEV_START;
}

async function start() {
  const app = document.getElementById("app");
  try {
    state.me = await api("/api/me");
  } catch (e) {
    app.textContent = "Ошибка: " + e.message;
    return;
  }
  if (WA) { WA.ready(); WA.expand && WA.expand(); }

  if (!state.me.registered) {
    app.textContent = "Сначала напишите боту /start";
    return;
  }

  const sp = startParam();
  const actId = sp && sp.startsWith("act_") ? Number(sp.slice(4)) : state.me.activeActId;
  if (!actId) {
    app.textContent = "Активного акта нет";
    return;
  }

  try {
    state.act = await api(`/api/acts/${actId}`);
  } catch (e) {
    app.textContent = "Ошибка: " + e.message;
    return;
  }
  render();
}

function render() {
  const header = document.getElementById("header");
  const app = document.getElementById("app");
  const act = state.act;
  const statusRu = STATUS_RU[act.status] || act.status;
  header.textContent = `${act.houseAddress} — Акт № ${act.number || "без номера"} за ${act.period || "—"} — ${statusRu}`;
  app.innerHTML = "";

  if (act.status === "RECEIVED") {
    if (act.isChairman) renderCardEditor(app, act);
    else app.textContent = "Председатель ещё готовит акт к проверке.";
    return;
  }
  app.textContent = "Статус: " + statusRu;
}

function field(label, value) {
  const wrap = document.createElement("div");
  wrap.className = "field";
  const lbl = document.createElement("label");
  lbl.textContent = label;
  const input = document.createElement("input");
  input.value = value;
  wrap.append(lbl, input);
  return { wrap, input };
}

function renderCardEditor(app, act) {
  if (act.recognition === "PENDING") {
    const p = document.createElement("p");
    p.textContent = "Акт распознаётся…";
    app.appendChild(p);
    const refreshBtn = document.createElement("button");
    refreshBtn.textContent = "Обновить";
    refreshBtn.onclick = async () => { state.act = await api(`/api/acts/${act.id}`); render(); };
    app.appendChild(refreshBtn);
    return;
  }

  const err = document.createElement("div");
  err.className = "error";
  app.appendChild(err);

  const numberField = field("Номер", act.number || "");
  const dateField = field("Дата оформления (ГГГГ-ММ-ДД)", act.formedDate || "");
  const periodField = field("Период", act.period || "");
  app.append(numberField.wrap, dateField.wrap, periodField.wrap);

  const itemsDiv = document.createElement("div");
  app.appendChild(itemsDiv);

  const items = act.items.map((it) => ({
    id: it.id, name: it.name, periodicity: it.periodicity, volume: it.volume, cost: it.cost, workKind: it.workKind,
  }));

  function renderItems() {
    itemsDiv.innerHTML = "";
    items.forEach((item, idx) => {
      const row = document.createElement("div");
      row.className = "item-row";

      const nameInput = document.createElement("input");
      nameInput.value = item.name;
      nameInput.placeholder = "Наименование";
      nameInput.oninput = () => { item.name = nameInput.value; };

      const periodicityInput = document.createElement("input");
      periodicityInput.value = item.periodicity;
      periodicityInput.placeholder = "Периодичность";
      periodicityInput.oninput = () => { item.periodicity = periodicityInput.value; };

      const volumeInput = document.createElement("input");
      volumeInput.value = item.volume;
      volumeInput.placeholder = "Ед. изм./объём";
      volumeInput.oninput = () => { item.volume = volumeInput.value; };

      const costInput = document.createElement("input");
      costInput.value = item.cost;
      costInput.placeholder = "Стоимость, руб.";
      costInput.oninput = () => { item.cost = costInput.value; };

      const kindSelect = document.createElement("select");
      act.workKinds.forEach((wk) => {
        const opt = document.createElement("option");
        opt.value = wk.code;
        opt.textContent = wk.title;
        if (wk.code === item.workKind) opt.selected = true;
        kindSelect.appendChild(opt);
      });
      kindSelect.onchange = () => { item.workKind = kindSelect.value; };

      const delBtn = document.createElement("button");
      delBtn.textContent = "удалить";
      delBtn.onclick = () => { items.splice(idx, 1); renderItems(); };

      row.append(nameInput, periodicityInput, volumeInput, costInput, kindSelect, delBtn);
      itemsDiv.appendChild(row);
    });
  }
  renderItems();

  const addBtn = document.createElement("button");
  addBtn.textContent = "+ позиция";
  addBtn.onclick = () => {
    items.push({ id: null, name: "", periodicity: "", volume: "", cost: "", workKind: "OTHER" });
    renderItems();
  };
  app.appendChild(addBtn);

  function cardInput() {
    return {
      number: numberField.input.value || null,
      formedDate: dateField.input.value || null,
      period: periodField.input.value || null,
      items: items.map((it, idx) => ({
        id: it.id, lineNo: idx + 1, name: it.name, periodicity: it.periodicity, volume: it.volume, cost: it.cost, workKind: it.workKind,
      })),
    };
  }

  async function save() {
    err.textContent = "";
    state.act = await api(`/api/acts/${act.id}/card`, { method: "PUT", json: cardInput() });
  }

  const saveBtn = document.createElement("button");
  saveBtn.textContent = "Сохранить";
  saveBtn.onclick = async () => {
    try { await save(); render(); }
    catch (e) { err.textContent = "Ошибка: " + e.message; }
  };
  app.appendChild(saveBtn);

  const openBtn = document.createElement("button");
  openBtn.textContent = "Открыть сбор замечаний";
  openBtn.onclick = async () => {
    if (!confirm("После открытия позиции нельзя будет менять.")) return;
    try {
      await save();
      state.act = await api(`/api/acts/${act.id}/open-collection`, { method: "POST" });
      render();
    } catch (e) {
      err.textContent = "Ошибка: " + e.message;
    }
  };
  app.appendChild(openBtn);
}

start();
