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

const ICON = {
  home: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z"/></svg>',
  check: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12.5 10 17 19 7"/></svg>',
  alert: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 7.5v5.5M12 16.5v.01"/></svg>',
  camera: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 8h3l2-3h6l2 3h3a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z"/><circle cx="12" cy="13.5" r="3.5"/></svg>',
  cross: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path d="M6 6l12 12M18 6 6 18"/></svg>',
  clock: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>',
};

function el(tag, className, text) {
  const e = document.createElement(tag);
  if (className) e.className = className;
  if (text !== undefined) e.textContent = text;
  return e;
}

function iconButton(icon, text, className) {
  const b = el("button", className);
  b.innerHTML = ICON[icon];
  b.append(document.createTextNode(text));
  return b;
}

// шапка позиции: номер-бейдж + название + чипы
function itemHead(lineNo, name, chips) {
  const head = el("div", "item-head");
  head.appendChild(el("div", "item-num", String(lineNo)));
  const main = el("div", "item-main");
  main.appendChild(el("p", "item-title", name));
  const meta = el("div", "item-meta");
  (chips || []).filter(Boolean).forEach(([text, mod]) => meta.appendChild(el("span", "chip" + (mod ? " chip--" + mod : ""), text)));
  if (meta.children.length) main.appendChild(meta);
  head.appendChild(main);
  return head;
}

// шкала «выполнено / претензия» без персональных данных (FR-D5)
function meter(ok, issue, withPhoto) {
  const total = ok + issue;
  const wrap = el("div", "meter");
  const bar = el("div", "meter-bar");
  const okS = el("span", "meter-ok");
  const issueS = el("span", "meter-issue");
  okS.style.width = total ? (ok / total * 100) + "%" : "0";
  issueS.style.width = total ? (issue / total * 100) + "%" : "0";
  bar.append(okS, issueS);
  const cap = el("div", "meter-caption");
  if (!total) cap.textContent = "Пока никто не отметил";
  else {
    const left = el("span"); left.innerHTML = `<b>${issue}</b> из ${total} — есть претензия`;
    cap.append(left, el("span", "", withPhoto !== undefined ? `с фото: ${withPhoto}` : `выполнено: ${ok}`));
  }
  wrap.append(bar, cap);
  return wrap;
}

function actionBar(...buttons) {
  const bar = el("div", "action-bar");
  buttons.filter(Boolean).forEach((b) => bar.appendChild(b));
  return bar;
}

function startParam() {
  if (WA && WA.initDataUnsafe && WA.initDataUnsafe.start_param) return WA.initDataUnsafe.start_param;
  return DEV_START;
}

function showMessage(app, text, isError) {
  app.innerHTML = "";
  const p = document.createElement("p");
  p.className = isError ? "error" : "empty-state";
  p.textContent = text;
  app.appendChild(p);
}

async function start() {
  const app = document.getElementById("app");
  try {
    state.me = await api("/api/me");
  } catch (e) {
    showMessage(app, "Ошибка: " + e.message, true);
    return;
  }
  if (WA) { WA.ready(); WA.expand && WA.expand(); }

  if (!state.me.registered) {
    showMessage(app, "Сначала напишите боту /start");
    return;
  }

  const sp = startParam();
  let actId = state.me.activeActId;
  if (sp && sp.startsWith("act_")) actId = Number(sp.slice(4));
  else if (sp && sp.startsWith("refusal_")) { actId = Number(sp.slice(8)); state.tab = "refusal"; }
  if (!actId) {
    showMessage(app, "Активного акта нет");
    return;
  }

  try {
    state.act = await api(`/api/acts/${actId}`);
  } catch (e) {
    showMessage(app, "Ошибка: " + e.message, true);
    return;
  }
  render();
}

function shortDate(iso) { return formatDate(iso).slice(0, 5); }

function formatDate(iso) {
  const [y, m, d] = iso.slice(0, 10).split("-");
  return `${d}.${m}.${y}`;
}

function renderHeader(act) {
  const header = document.getElementById("header");
  header.innerHTML = "";
  const inner = el("div", "hero-inner");
  header.appendChild(inner);

  const eyebrow = el("p", "app-eyebrow");
  eyebrow.innerHTML = ICON.home;
  eyebrow.append(document.createTextNode(act.houseAddress));
  inner.appendChild(eyebrow);

  inner.appendChild(el("h1", "app-title", `Акт № ${act.number || "без номера"} за ${act.period || "—"}`));
  inner.appendChild(el("span", `status-pill status-${act.status}`, STATUS_RU[act.status] || act.status));

  if (["RECEIVED", "COLLECTING", "REVIEW"].includes(act.status)) {
    const cd = el("div", "countdown");
    cd.appendChild(ringCard(act.daysLeft10, 10, "Срок по приказу", act.daysLeft10 < 0 ? `истёк ${shortDate(act.deadline10)}` : `дн. · до ${shortDate(act.deadline10)}`));
    cd.appendChild(ringCard(act.daysLeft30, 30, "Защитный срок", `дн. · до ${shortDate(act.deadline30)}`));
    inner.appendChild(cd);
    if (act.daysLeft10 < 0) {
      inner.appendChild(el("p", "hero-note", `Срок по приказу истёк ${formatDate(act.deadline10)}, но акт ещё не считается принятым — решение можно принять до ${formatDate(act.deadline30)}.`));
    }
  }
}

// кольцо обратного отсчёта: заполнено на долю оставшихся дней
function ringCard(daysLeft, total, title, sub) {
  const left = Math.max(daysLeft, 0);
  const card = el("div", "ring-card" + (left <= 2 ? " ring-card--danger" : left <= Math.ceil(total / 5) ? " ring-card--warn" : ""));
  const r = 23, c = 2 * Math.PI * r;
  const ring = el("div", "ring");
  ring.innerHTML = `<svg viewBox="0 0 54 54"><circle class="track" cx="27" cy="27" r="${r}"/><circle class="bar" cx="27" cy="27" r="${r}" stroke-dasharray="${c}" stroke-dashoffset="${c}"/></svg>`;
  ring.appendChild(el("div", "ring-value", String(left)));
  requestAnimationFrame(() => { ring.querySelector(".bar").style.strokeDashoffset = c * (1 - Math.min(left / total, 1)); });
  const label = el("div", "ring-label");
  label.appendChild(el("b", "", title));
  label.append(document.createTextNode(sub));
  card.append(ring, label);
  return card;
}

function resultBanner(act) {
  const texts = {
    SIGNED: ["Акт подписан", "Подписанный экземпляр отправлен председателю в чат — он пересылает его в УК."],
    REJECTED: ["Мотивированный отказ направлен", "УК должна оформить новый акт с учётом возражений. Спасибо за отметки!"],
    SILENT: ["Принят молчаливым согласием", "30 дней со дня получения истекли без подписи и отказа (п. 5 Порядка)."],
  };
  const t = texts[act.status];
  if (!t) return null;
  const b = el("div", `result-banner result-${act.status}`);
  const icon = el("div", "result-icon");
  icon.innerHTML = act.status === "SIGNED" ? ICON.check : act.status === "REJECTED" ? ICON.cross : ICON.clock;
  const body = el("div");
  body.append(el("h3", "", t[0]), el("p", "", t[1]));
  b.append(icon, body);
  return b;
}

function render() {
  const app = document.getElementById("app");
  const act = state.act;
  renderHeader(act);
  app.innerHTML = "";

  if (act.status === "RECEIVED") {
    if (act.isChairman) renderCardEditor(app, act);
    else showMessage(app, "Председатель ещё готовит акт к проверке.");
    return;
  }
  if (act.isChairman && (act.status === "COLLECTING" || act.status === "REVIEW")) {
    renderChairmanTabs(app, act);
    return;
  }
  const banner = resultBanner(act);
  if (banner) app.appendChild(banner);
  if (act.isResident) { renderChecklist(app, act); return; }
  showMessage(app, "Статус: " + (STATUS_RU[act.status] || act.status));
}

function renderChairmanTabs(app, act) {
  const hasRefusalTab = act.status === "REVIEW";
  if (state.tab === "refusal" && !hasRefusalTab) state.tab = "checklist";
  if (!["checklist", "remarks", "refusal"].includes(state.tab)) state.tab = "checklist";

  const tabs = document.createElement("div");
  tabs.className = "tabs";
  const checklistTab = document.createElement("button");
  checklistTab.textContent = "Чек-лист";
  const remarksTab = document.createElement("button");
  remarksTab.textContent = "Замечания";
  const issues = act.items.reduce((n, i) => n + i.stats.issue, 0);
  if (issues) remarksTab.appendChild(el("span", "tab-count", String(issues)));
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
  const loading = document.createElement("p");
  loading.className = "loading-state";
  loading.textContent = "Загрузка…";
  app.appendChild(loading);
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
  const card = document.createElement("div");
  card.className = "card";
  const p = document.createElement("p");
  p.className = "hint";
  p.textContent = "Черновик мотивированного отказа ещё не собран.";
  const err = document.createElement("div");
  err.className = "error";
  const btn = document.createElement("button");
  btn.className = "btn btn-primary";
  btn.textContent = "Собрать черновик";
  btn.onclick = async () => {
    err.textContent = "";
    try {
      const dto = await api(`/api/acts/${act.id}/refusal/draft?rebuild=false`, { method: "POST" });
      app.innerHTML = "";
      renderRefusalForm(app, act, dto);
    } catch (e) { err.textContent = "Ошибка: " + e.message; }
  };
  card.append(p, btn);
  app.append(card, err);
}

function renderRefusalForm(app, act, dto) {
  if (dto.confirmedAt) { renderRefusalConfirmed(app, dto); return; }

  const err = document.createElement("div");
  err.className = "error";
  app.appendChild(err);

  const placeCard = document.createElement("div");
  placeCard.className = "card";
  const placeField = field("Место составления", dto.place);
  placeCard.appendChild(placeField.wrap);
  app.appendChild(placeCard);

  const objectionInputs = dto.objections.map((o) => {
    const wrap = document.createElement("div");
    wrap.className = "item-card";

    wrap.appendChild(itemHead(o.lineNo, o.itemName, [
      [`претензий: ${o.issueCount}`, "negative"], [`фото: ${o.photoCount}`, "accent"], [`выполнено: ${o.okCount}`, "positive"],
    ]));

    const factLabel = document.createElement("label");
    factLabel.textContent = "Фактически";
    const factArea = document.createElement("textarea");
    factArea.value = o.fact;
    wrap.append(factLabel, factArea);

    const groundP = el("div", "ground-box");
    groundP.appendChild(el("b", "", "Основание · из справочника"));
    groundP.append(document.createTextNode(`${o.groundText} (${o.groundRef})`));
    wrap.appendChild(groundP);

    const demandLabel = document.createElement("label");
    demandLabel.textContent = "Требование";
    const demandArea = document.createElement("textarea");
    demandArea.value = o.demand;
    wrap.append(demandLabel, demandArea);

    app.appendChild(wrap);
    return { itemId: o.itemId, factArea, demandArea };
  });

  if (dto.noObjectionLineNos.length > 0) {
    const p = document.createElement("p");
    p.className = "hint";
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
  saveBtn.className = "btn btn-secondary";
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
  rebuildBtn.className = "btn btn-secondary";
  rebuildBtn.textContent = "Пересобрать";
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
  confirmBtn.className = "btn btn-primary";
  confirmBtn.textContent = "Подтвердить и сформировать PDF";
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

  rebuildBtn.className = "btn btn-dashed";
  const row = el("div", "action-row");
  row.append(saveBtn, rebuildBtn);
  app.appendChild(actionBar(confirmBtn, row));
}

function renderRefusalConfirmed(app, dto) {
  const card = document.createElement("div");
  card.className = "card";
  const p = document.createElement("p");
  p.className = "hint";
  p.style.margin = "0 0 12px";
  p.textContent = "Документ сформирован и отправлен вам в чат MAX. Перешлите его в УК и нажмите в чате «Отправил исполнителю».";
  const btn = document.createElement("button");
  btn.className = "btn btn-primary";
  btn.textContent = "Вернуться в чат";
  btn.onclick = () => { if (WA && WA.close) WA.close(); };
  card.append(p, btn);
  app.appendChild(card);
}

function renderRemarksSummary(app, act) {
  const err = el("div", "error");
  app.appendChild(err);

  const items = [...act.items].sort((a, b) => b.stats.issue - a.stats.issue);
  items.forEach((item) => app.appendChild(renderRemarksItem(item, err)));

  if (act.status === "COLLECTING") {
    const closeBtn = el("button", "btn btn-primary", "Завершить сбор замечаний");
    closeBtn.onclick = async () => {
      if (!confirm("После завершения сбора жители больше не смогут отмечать позиции.")) return;
      closeBtn.disabled = true;
      err.textContent = "";
      try { state.act = await api(`/api/acts/${act.id}/close-collection`, { method: "POST" }); render(); }
      catch (e) { err.textContent = "Ошибка: " + e.message; closeBtn.disabled = false; }
    };
    app.appendChild(actionBar(closeBtn));
  }
}

function renderRemarksItem(item, err) {
  const card = document.createElement("div");
  card.className = "item-card";

  card.appendChild(itemHead(item.lineNo, item.name, [item.periodicity && [item.periodicity]]));
  card.appendChild(meter(item.stats.ok, item.stats.issue, item.stats.issueWithPhoto));

  (item.remarks || []).filter((r) => r.verdict === "ISSUE").forEach((r) => {
    const box = document.createElement("div");
    box.className = "remark-box";
    const text = document.createElement("p");
    if (r.llmStatus === "PENDING") { text.className = "llm-pending"; text.textContent = "Формулируем замечание…"; }
    else {
      text.textContent = r.formalized || r.text || "";
      if (r.llmStatus === "FAILED") {
        const badge = document.createElement("span");
        badge.className = "llm-badge";
        badge.textContent = " (без обработки)";
        text.appendChild(badge);
      }
    }
    box.appendChild(text);
    const photosDiv = document.createElement("div");
    photosDiv.className = "photos";
    photosDiv.style.marginBottom = "0";
    (r.photos || []).forEach((p) => {
      const img = document.createElement("img");
      img.className = "thumb";
      loadImage(p.url, img);
      photosDiv.appendChild(img);
    });
    box.appendChild(photosDiv);
    card.appendChild(box);
  });

  const decisionRow = el("div", "segmented");
  const acceptBtn = iconButton("check", "Принять");
  const disputeBtn = iconButton("alert", "Оспорить", "negative");
  decisionRow.append(acceptBtn, disputeBtn);
  card.appendChild(decisionRow);

  if (item.stats.issueWithPhoto === 0) {
    disputeBtn.disabled = true;
    const note = el("div", "hint-box");
    note.innerHTML = ICON.alert;
    note.append(document.createTextNode("Нет замечаний с фото — оснований для возражения недостаточно"));
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
  app.appendChild(el("p", "section-title", editable ? "Отметьте каждую работу" : "Сбор замечаний закрыт"));
  act.items.forEach((item) => app.appendChild(renderChecklistItem(item, editable)));
}

function renderChecklistItem(item, editable) {
  const card = document.createElement("div");
  card.className = "item-card";

  card.appendChild(itemHead(item.lineNo, item.name, [item.periodicity && [item.periodicity]]));
  card.appendChild(meter(item.stats.ok, item.stats.issue));

  const my = item.my || { verdict: null, text: null, photos: [] };
  let photos = my.photos || [];

  const btnRow = el("div", "segmented");
  const okBtn = iconButton("check", "Выполнено");
  const issueBtn = iconButton("alert", "Есть претензия", "negative");
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

  const photosDiv = el("div", "photos");
  const fileInput = document.createElement("input");
  fileInput.type = "file";
  fileInput.accept = "image/*";
  fileInput.capture = "environment";
  fileInput.style.display = "none";
  // плитка «+ фото» живёт внутри сетки снимков (до 5 фото на замечание)
  const addPhotoBtn = el("button", "photo-add");
  addPhotoBtn.innerHTML = ICON.camera;
  addPhotoBtn.append(document.createTextNode("Фото"));
  addPhotoBtn.onclick = () => fileInput.click();
  function renderPhotos() {
    photosDiv.innerHTML = "";
    photos.forEach((p) => {
      const img = document.createElement("img");
      img.className = "thumb";
      loadImage(p.url, img);
      photosDiv.appendChild(img);
    });
    if (editable && photos.length < 5) photosDiv.appendChild(addPhotoBtn);
  }
  renderPhotos();
  issueBox.append(photosDiv, fileInput);

  const hint = el("div", "hint-box");
  hint.innerHTML = ICON.camera;
  hint.append(document.createTextNode("Претензия с фото — самый сильный аргумент. Без фото председатель не сможет её заявить."));
  issueBox.appendChild(hint);

  const saveBtn = document.createElement("button");
  saveBtn.className = "btn btn-primary";
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
    addPhotoBtn.disabled = true;
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
    addPhotoBtn.disabled = false;
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
    const card = document.createElement("div");
    card.className = "card";
    const p = document.createElement("p");
    p.className = "hint";
    p.style.margin = "0 0 12px";
    p.textContent = "Акт распознаётся…";
    const refreshBtn = document.createElement("button");
    refreshBtn.className = "btn btn-primary";
    refreshBtn.textContent = "Обновить";
    refreshBtn.onclick = async () => { state.act = await api(`/api/acts/${act.id}`); render(); };
    card.append(p, refreshBtn);
    app.appendChild(card);
    return;
  }

  const err = document.createElement("div");
  err.className = "error";
  app.appendChild(err);

  app.appendChild(el("p", "section-title", "Реквизиты акта"));
  const infoCard = document.createElement("div");
  infoCard.className = "card";
  const numberField = field("Номер", act.number || "");
  const dateField = field("Дата оформления (ГГГГ-ММ-ДД)", act.formedDate || "");
  const periodField = field("Период", act.period || "");
  infoCard.append(numberField.wrap, dateField.wrap, periodField.wrap);
  app.append(infoCard);

  app.appendChild(el("p", "section-title", "Позиции акта"));
  const itemsDiv = document.createElement("div");
  app.appendChild(itemsDiv);

  const items = act.items.map((it) => ({
    id: it.id, name: it.name, periodicity: it.periodicity, volume: it.volume, cost: it.cost, workKind: it.workKind,
  }));

  function renderItems() {
    itemsDiv.innerHTML = "";
    items.forEach((item, idx) => {
      const itemCard = document.createElement("div");
      itemCard.className = "item-card";

      const itemHeader = el("div", "item-head");
      itemHeader.style.alignItems = "center";
      itemHeader.style.marginBottom = "0";
      const itemLabel = el("div", "item-num", String(idx + 1));
      const spacer = el("div", "item-main");
      const delBtn = document.createElement("button");
      delBtn.className = "btn-ghost";
      delBtn.textContent = "Удалить";
      delBtn.onclick = () => { items.splice(idx, 1); renderItems(); };
      itemHeader.append(itemLabel, spacer, delBtn);
      itemCard.appendChild(itemHeader);

      const nameInput = document.createElement("input");
      nameInput.value = item.name;
      nameInput.placeholder = "Наименование";
      nameInput.style.marginTop = "12px";
      nameInput.oninput = () => { item.name = nameInput.value; };
      itemCard.appendChild(nameInput);

      const row = document.createElement("div");
      row.className = "item-row";

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

      row.append(periodicityInput, volumeInput, costInput);
      itemCard.append(row, kindSelect);
      kindSelect.style.marginTop = "8px";
      itemsDiv.appendChild(itemCard);
    });
  }
  renderItems();

  const addBtn = document.createElement("button");
  addBtn.className = "btn btn-dashed";
  addBtn.textContent = "+ Добавить позицию";
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
  saveBtn.className = "btn btn-secondary";
  saveBtn.textContent = "Сохранить";
  saveBtn.onclick = async () => {
    try { await save(); render(); }
    catch (e) { err.textContent = "Ошибка: " + e.message; }
  };

  const openBtn = document.createElement("button");
  openBtn.className = "btn btn-primary";
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
  app.appendChild(actionBar(openBtn, saveBtn));
}

start();
