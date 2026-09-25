const WA = window.WebApp;
const params = new URLSearchParams(location.search);
const DEV_USER = params.get("devUser");          // локальная отладка вне MAX (нужен DEV_AUTH=true на бэкенде)
const DEV_START = params.get("startapp");         // локальная отладка стартового параметра, напр. ?startapp=act_1

function authHeaders() {
  const headers = {};
  if (WA && WA.initData) headers["X-Max-Init-Data"] = WA.initData;
  else if (DEV_USER) headers["X-Dev-User-Id"] = DEV_USER;
  return headers;
}

async function api(path, opts = {}) {
  const headers = { ...authHeaders(), ...(opts.headers || {}) };
  if (opts.json !== undefined) { headers["Content-Type"] = "application/json"; opts.body = JSON.stringify(opts.json); }
  const r = await fetch(window.API_BASE + path, { ...opts, headers });
  if (!r.ok) {
    const e = await r.json().catch(() => ({ message: r.statusText }));
    const err = new Error(e.message);
    err.code = e.error;
    throw err;
  }
  return r.status === 204 ? null : r.json();
}

// сервер требует X-Max-Init-Data/X-Dev-User-Id даже на статичные картинки — обычный <img src> их не передаст
async function loadImage(url, imgEl) {
  try {
    const r = await fetch(window.API_BASE + url, { headers: authHeaders() });
    if (!r.ok) return;
    imgEl.src = URL.createObjectURL(await r.blob());
  } catch (e) { /* миниатюра просто не загрузится */ }
}

// уменьшаем фото на клиенте перед загрузкой: длинная сторона ≤1600px, JPEG q=0.8
async function resizeImage(file) {
  const bitmap = await createImageBitmap(file);
  const maxSide = 1600;
  let { width, height } = bitmap;
  if (width > maxSide || height > maxSide) {
    const scale = maxSide / Math.max(width, height);
    width = Math.round(width * scale);
    height = Math.round(height * scale);
  }
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  canvas.getContext("2d").drawImage(bitmap, 0, 0, width, height);
  return new Promise((resolve) => canvas.toBlob(resolve, "image/jpeg", 0.8));
}

const STATUS_RU = {
  RECEIVED: "Получен",
  COLLECTING: "Идёт сбор замечаний",
  REVIEW: "Решение председателя",
  SIGNED: "Подписан",
  REJECTED: "Отказ направлен",
  SILENT: "Принят молчаливым согласием",
};

let state = { me: null, act: null, tab: "checklist" };

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
  let actId = state.me.activeActId;
  if (sp && sp.startsWith("act_")) actId = Number(sp.slice(4));
  else if (sp && sp.startsWith("refusal_")) { actId = Number(sp.slice(8)); state.tab = "refusal"; }
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

function formatDate(iso) {
  const [y, m, d] = iso.slice(0, 10).split("-");
  return `${d}.${m}.${y}`;
}

function render() {
  const header = document.getElementById("header");
  const app = document.getElementById("app");
  const act = state.act;
  const statusRu = STATUS_RU[act.status] || act.status;
  const lines = [
    `${act.houseAddress} — Акт № ${act.number || "без номера"} за ${act.period || "—"}`,
    `Статус: ${statusRu}`,
  ];
  if (["RECEIVED", "COLLECTING", "REVIEW"].includes(act.status)) {
    if (act.daysLeft10 < 0) {
      lines.push(`Срок по приказу истёк ${formatDate(act.deadline10)}, но акт ещё не считается принятым — решение можно принять до ${formatDate(act.deadline30)}.`);
    } else {
      lines.push(`Срок по приказу (10 дней): до ${formatDate(act.deadline10)} — осталось дней: ${act.daysLeft10}`);
    }
    lines.push(`Защитный срок (30 дней): до ${formatDate(act.deadline30)} — осталось дней: ${act.daysLeft30}`);
  }
  header.textContent = lines.join("\n");
  app.innerHTML = "";

  if (act.status === "RECEIVED") {
    if (act.isChairman) renderCardEditor(app, act);
    else app.textContent = "Председатель ещё готовит акт к проверке.";
    return;
  }
  if (act.isChairman && (act.status === "COLLECTING" || act.status === "REVIEW")) {
    renderChairmanTabs(app, act);
    return;
  }
  if (act.isResident) { renderChecklist(app, act); return; }
  app.textContent = "Статус: " + statusRu;
}

function renderChairmanTabs(app, act) {
  const hasRefusalTab = act.status === "REVIEW";
  if (state.tab === "refusal" && !hasRefusalTab) state.tab = "checklist";
  if (!["checklist", "remarks", "refusal"].includes(state.tab)) state.tab = "checklist";

  const tabs = document.createElement("div");
  tabs.className = "tabs";
  const checklistTab = document.createElement("button");
  checklistTab.textContent = "Мой чек-лист";
  const remarksTab = document.createElement("button");
  remarksTab.textContent = "Замечания";
  tabs.append(checklistTab, remarksTab);
  let refusalTab = null;
  if (hasRefusalTab) {
    refusalTab = document.createElement("button");
    refusalTab.textContent = "Отказ";
    tabs.append(refusalTab);
  }
  app.appendChild(tabs);

  const body = document.createElement("div");
  app.appendChild(body);

  function renderBody() {
    checklistTab.classList.toggle("active", state.tab === "checklist");
    remarksTab.classList.toggle("active", state.tab === "remarks");
    if (refusalTab) refusalTab.classList.toggle("active", state.tab === "refusal");
    body.innerHTML = "";
    if (state.tab === "checklist") renderChecklist(body, act);
    else if (state.tab === "remarks") renderRemarksSummary(body, act);
    else renderRefusalTab(body, act);
  }
  checklistTab.onclick = () => { state.tab = "checklist"; renderBody(); };
  remarksTab.onclick = () => { state.tab = "remarks"; renderBody(); };
  if (refusalTab) refusalTab.onclick = () => { state.tab = "refusal"; renderBody(); };
  renderBody();
}

function renderRefusalTab(app, act) {
  app.textContent = "Загрузка…";
  api(`/api/acts/${act.id}/refusal`)
    .then((dto) => { app.innerHTML = ""; renderRefusalForm(app, act, dto); })
    .catch((e) => {
      app.innerHTML = "";
      if (e.code === "no_refusal") renderRefusalEmpty(app, act);
      else {
        const err = document.createElement("div");
        err.className = "error";
        err.textContent = "Ошибка: " + e.message;
        app.appendChild(err);
      }
    });
}

function renderRefusalEmpty(app, act) {
  const err = document.createElement("div");
  err.className = "error";
  const btn = document.createElement("button");
  btn.textContent = "Собрать черновик";
  btn.onclick = async () => {
    err.textContent = "";
    try {
      const dto = await api(`/api/acts/${act.id}/refusal/draft?rebuild=false`, { method: "POST" });
      app.innerHTML = "";
      renderRefusalForm(app, act, dto);
    } catch (e) { err.textContent = "Ошибка: " + e.message; }
  };
  app.append(btn, err);
}

function renderRefusalForm(app, act, dto) {
  if (dto.confirmedAt) { renderRefusalConfirmed(app, dto); return; }

  const err = document.createElement("div");
  err.className = "error";
  app.appendChild(err);

  const placeField = field("Место составления", dto.place);
  app.appendChild(placeField.wrap);

  const objectionInputs = dto.objections.map((o) => {
    const wrap = document.createElement("div");
    wrap.className = "item-card";

    const title = document.createElement("div");
    title.className = "item-title";
    title.textContent = `№${o.lineNo} ${o.itemName}`;
    wrap.appendChild(title);

    const factLabel = document.createElement("label");
    factLabel.textContent = "Фактически";
    const factArea = document.createElement("textarea");
    factArea.value = o.fact;
    wrap.append(factLabel, factArea);

    const groundP = document.createElement("p");
    groundP.className = "hint";
    groundP.textContent = `Основание (из справочника оснований): ${o.groundText} (${o.groundRef})`;
    wrap.appendChild(groundP);

    const demandLabel = document.createElement("label");
    demandLabel.textContent = "Требование";
    const demandArea = document.createElement("textarea");
    demandArea.value = o.demand;
    wrap.append(demandLabel, demandArea);

    const counters = document.createElement("div");
    counters.className = "item-agg";
    counters.textContent = `Выполнено: ${o.okCount}, претензия: ${o.issueCount}, фото: ${o.photoCount}`;
    wrap.appendChild(counters);

    app.appendChild(wrap);
    return { itemId: o.itemId, factArea, demandArea };
  });

  if (dto.noObjectionLineNos.length > 0) {
    const p = document.createElement("p");
    p.textContent = `По позициям № ${dto.noObjectionLineNos.join(", ")} возражений не имеется.`;
    app.appendChild(p);
  }

  function currentEdit() {
    return {
      place: placeField.input.value,
      objections: objectionInputs.map((o) => ({ itemId: o.itemId, fact: o.factArea.value, demand: o.demandArea.value })),
    };
  }

  const saveBtn = document.createElement("button");
  saveBtn.textContent = "Сохранить";
  saveBtn.onclick = async () => {
    err.textContent = "";
    try {
      const updated = await api(`/api/acts/${act.id}/refusal`, { method: "PUT", json: currentEdit() });
      app.innerHTML = "";
      renderRefusalForm(app, act, updated);
    } catch (e) { err.textContent = "Ошибка: " + e.message; }
  };

  const rebuildBtn = document.createElement("button");
  rebuildBtn.textContent = "Пересобрать из замечаний";
  rebuildBtn.onclick = async () => {
    if (!confirm("Ваши правки будут потеряны.")) return;
    err.textContent = "";
    try {
      const updated = await api(`/api/acts/${act.id}/refusal/draft?rebuild=true`, { method: "POST" });
      app.innerHTML = "";
      renderRefusalForm(app, act, updated);
    } catch (e) { err.textContent = "Ошибка: " + e.message; }
  };

  const confirmBtn = document.createElement("button");
  confirmBtn.textContent = "Подтвердить и сформировать документ";
  confirmBtn.onclick = async () => {
    if (!confirm("После подтверждения текст изменить нельзя.")) return;
    err.textContent = "";
    try {
      await api(`/api/acts/${act.id}/refusal`, { method: "PUT", json: currentEdit() });
      const confirmed = await api(`/api/acts/${act.id}/refusal/confirm`, { method: "POST" });
      app.innerHTML = "";
      renderRefusalConfirmed(app, confirmed);
    } catch (e) { err.textContent = "Ошибка: " + e.message; }
  };

  app.append(saveBtn, rebuildBtn, confirmBtn);
}

function renderRefusalConfirmed(app, dto) {
  const p = document.createElement("p");
  p.textContent = "Документ отправлен вам в чат MAX.";
  app.appendChild(p);
  const btn = document.createElement("button");
  btn.textContent = "Вернуться в чат";
  btn.onclick = () => { if (WA && WA.close) WA.close(); };
  app.appendChild(btn);
}

function renderRemarksSummary(app, act) {
  const err = document.createElement("div");
  err.className = "error";
  app.appendChild(err);

  if (act.status === "COLLECTING") {
    const closeBtn = document.createElement("button");
    closeBtn.textContent = "Завершить сбор замечаний";
    closeBtn.onclick = async () => {
      if (!confirm("После завершения сбора жители больше не смогут отмечать позиции.")) return;
      err.textContent = "";
      try { state.act = await api(`/api/acts/${act.id}/close-collection`, { method: "POST" }); render(); }
      catch (e) { err.textContent = "Ошибка: " + e.message; }
    };
    app.appendChild(closeBtn);
  }

  const items = [...act.items].sort((a, b) => b.stats.issue - a.stats.issue);
  items.forEach((item) => app.appendChild(renderRemarksItem(item, err)));
}

function renderRemarksItem(item, err) {
  const card = document.createElement("div");
  card.className = "item-card";

  const title = document.createElement("div");
  title.className = "item-title";
  title.textContent = `№${item.lineNo} ${item.name}`;
  card.appendChild(title);

  const agg = document.createElement("div");
  agg.className = "item-agg";
  agg.textContent = `Выполнено: ${item.stats.ok}, претензия: ${item.stats.issue} (с фото: ${item.stats.issueWithPhoto})`;
  card.appendChild(agg);

  (item.remarks || []).filter((r) => r.verdict === "ISSUE").forEach((r) => {
    const box = document.createElement("div");
    box.className = "remark-box";
    const text = document.createElement("p");
    if (r.llmStatus === "PENDING") text.textContent = "обрабатывается…";
    else text.textContent = (r.formalized || r.text || "") + (r.llmStatus === "FAILED" ? " (без обработки)" : "");
    box.appendChild(text);
    const photosDiv = document.createElement("div");
    photosDiv.className = "photos";
    (r.photos || []).forEach((p) => {
      const img = document.createElement("img");
      img.className = "thumb";
      loadImage(p.url, img);
      photosDiv.appendChild(img);
    });
    box.appendChild(photosDiv);
    card.appendChild(box);
  });

  const decisionRow = document.createElement("div");
  const acceptBtn = document.createElement("button");
  acceptBtn.textContent = "Принять";
  const disputeBtn = document.createElement("button");
  disputeBtn.textContent = "Оспорить";
  decisionRow.append(acceptBtn, disputeBtn);
  card.appendChild(decisionRow);

  if (item.stats.issueWithPhoto === 0) {
    disputeBtn.disabled = true;
    const note = document.createElement("p");
    note.className = "hint";
    note.textContent = "Нет замечаний с фото — возражать нечем";
    card.appendChild(note);
  }

  function refresh() {
    acceptBtn.classList.toggle("active", item.decision === "ACCEPT");
    disputeBtn.classList.toggle("active", item.decision === "DISPUTE");
  }
  refresh();

  async function setDecision(decision) {
    err.textContent = "";
    try {
      const dto = await api(`/api/items/${item.id}/decision`, { method: "PUT", json: { decision } });
      item.decision = dto.decision;
      refresh();
    } catch (e) { err.textContent = "Ошибка: " + e.message; }
  }
  acceptBtn.onclick = () => setDecision("ACCEPT");
  disputeBtn.onclick = () => setDecision("DISPUTE");

  return card;
}

function renderChecklist(app, act) {
  const editable = act.status === "COLLECTING";
  if (!editable) {
    const note = document.createElement("p");
    note.textContent = "Сбор замечаний закрыт.";
    app.appendChild(note);
  }
  act.items.forEach((item) => app.appendChild(renderChecklistItem(item, editable)));
}

function renderChecklistItem(item, editable) {
  const card = document.createElement("div");
  card.className = "item-card";

  const title = document.createElement("div");
  title.className = "item-title";
  title.textContent = `№${item.lineNo} ${item.name}` + (item.periodicity ? ` — ${item.periodicity}` : "");
  card.appendChild(title);

  const agg = document.createElement("div");
  agg.className = "item-agg";
  const total = item.stats.ok + item.stats.issue;
  agg.textContent = total > 0 ? `${item.stats.issue} из ${total} ответивших: есть претензия` : "Пока никто не отметил";
  card.appendChild(agg);

  const my = item.my || { verdict: null, text: null, photos: [] };
  let photos = my.photos || [];

  const btnRow = document.createElement("div");
  const okBtn = document.createElement("button");
  okBtn.textContent = "Выполнено";
  const issueBtn = document.createElement("button");
  issueBtn.textContent = "Есть претензия";
  btnRow.append(okBtn, issueBtn);
  card.appendChild(btnRow);

  const err = document.createElement("div");
  err.className = "error";
  card.appendChild(err);

  const issueBox = document.createElement("div");

  const textarea = document.createElement("textarea");
  textarea.placeholder = "Что именно не так? Например: в подъезде 2 не мыли пол с 10 сентября";
  textarea.value = my.text || "";
  issueBox.appendChild(textarea);

  const photosDiv = document.createElement("div");
  photosDiv.className = "photos";
  function renderPhotos() {
    photosDiv.innerHTML = "";
    photos.forEach((p) => {
      const img = document.createElement("img");
      img.className = "thumb";
      loadImage(p.url, img);
      photosDiv.appendChild(img);
    });
  }
  renderPhotos();
  issueBox.appendChild(photosDiv);

  const fileInput = document.createElement("input");
  fileInput.type = "file";
  fileInput.accept = "image/*";
  fileInput.capture = "environment";
  fileInput.style.display = "none";
  const addPhotoBtn = document.createElement("button");
  addPhotoBtn.textContent = "Добавить фото";
  addPhotoBtn.onclick = () => fileInput.click();
  issueBox.append(addPhotoBtn, fileInput);

  const hint = document.createElement("p");
  hint.className = "hint";
  hint.textContent = "Претензия с фото — самый сильный аргумент для отказа. Без фото председатель не сможет её заявить.";
  issueBox.appendChild(hint);

  const saveBtn = document.createElement("button");
  saveBtn.textContent = "Сохранить";
  issueBox.appendChild(saveBtn);

  card.appendChild(issueBox);

  function setVerdict(v) {
    okBtn.classList.toggle("active", v === "OK");
    issueBtn.classList.toggle("active", v === "ISSUE");
    issueBox.style.display = v === "ISSUE" ? "block" : "none";
  }
  setVerdict(my.verdict);

  if (!editable) {
    okBtn.disabled = true;
    issueBtn.disabled = true;
    textarea.disabled = true;
    addPhotoBtn.style.display = "none";
    saveBtn.style.display = "none";
    return card;
  }

  function applyRemark(dto) {
    my.verdict = dto.verdict;
    my.text = dto.text;
    photos = dto.photos;
    textarea.value = dto.text || "";
    setVerdict(dto.verdict);
    renderPhotos();
  }

  okBtn.onclick = async () => {
    err.textContent = "";
    try { applyRemark(await api(`/api/items/${item.id}/my-remark`, { method: "PUT", json: { verdict: "OK" } })); }
    catch (e) { err.textContent = "Ошибка: " + e.message; }
  };

  issueBtn.onclick = () => setVerdict("ISSUE");

  async function saveText() {
    applyRemark(await api(`/api/items/${item.id}/my-remark`, { method: "PUT", json: { verdict: "ISSUE", text: textarea.value } }));
  }

  saveBtn.onclick = async () => {
    err.textContent = "";
    try { await saveText(); }
    catch (e) { err.textContent = "Ошибка: " + e.message; }
  };

  fileInput.onchange = async () => {
    const file = fileInput.files[0];
    fileInput.value = "";
    if (!file) return;
    err.textContent = "";
    try {
      await saveText(); // фото можно добавить только к уже сохранённому замечанию
      const blob = await resizeImage(file);
      const form = new FormData();
      form.append("photo", blob, "photo.jpg");
      const r = await fetch(window.API_BASE + `/api/items/${item.id}/my-remark/photos`, { method: "POST", headers: authHeaders(), body: form });
      if (!r.ok) { const e = await r.json().catch(() => ({ message: r.statusText })); throw new Error(e.message); }
      photos = [...photos, await r.json()];
      renderPhotos();
    } catch (e) { err.textContent = "Ошибка: " + e.message; }
  };

  return card;
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
