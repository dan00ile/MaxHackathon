const WA = window.WebApp;
const params = new URLSearchParams(location.search);
const DEV_USER = params.get("devUser");          // локальная отладка вне MAX (нужен DEV_AUTH=true на бэкенде)
const DEV_START = params.get("startapp");         // локальная отладка стартового параметра, напр. ?startapp=act_1
const POLL_MS = 10000;                            // как часто подтягиваем чужие отметки

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

// сервер требует X-Max-Init-Data/X-Dev-User-Id даже на статичные картинки — обычный <img src> их не передаст.
// Кэш blob-URL: карточки теперь перерисовываются чаще, не качаем одно фото дважды
const imageCache = new Map();
async function loadImage(url, imgEl) {
  try {
    if (!imageCache.has(url)) {
      imageCache.set(url, fetch(window.API_BASE + url, { headers: authHeaders() })
        .then((r) => (r.ok ? r.blob() : Promise.reject()))
        .then((b) => URL.createObjectURL(b)));
    }
    imgEl.src = await imageCache.get(url);
  } catch (e) { imageCache.delete(url); /* миниатюра просто не загрузится */ }
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
const FINAL = ["SIGNED", "REJECTED", "SILENT"];

let state = { me: null, view: "list", house: null, act: null, tab: "checklist" };
// Живые обновления: каждая отрисованная карточка кладёт сюда функцию «перечитай item и обнови себя».
// Вызываются после ответа сервера на своё действие и после фонового опроса
let live = [];
function syncLive() { live.forEach((f) => f()); }

const ICON = {
  home: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z"/></svg>',
  check: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12.5 10 17 19 7"/></svg>',
  alert: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 7.5v5.5M12 16.5v.01"/></svg>',
  camera: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 8h3l2-3h6l2 3h3a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z"/><circle cx="12" cy="13.5" r="3.5"/></svg>',
  cross: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path d="M6 6l12 12M18 6 6 18"/></svg>',
  clock: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>',
  pen: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 20h4L19 9l-4-4L4 16z"/><path d="m13.5 6.5 4 4"/></svg>',
  back: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M15 5 8 12l7 7"/></svg>',
  chevron: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="m9 5 7 7-7 7"/></svg>',
  doc: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5M9 13h6M9 17h4"/></svg>',
};

function el(tag, className, text) {
  const e = document.createElement(tag);
  if (className) e.className = className;
  if (text !== undefined) e.textContent = text;
  return e;
}

function iconButton(icon, text, className) {
  const b = el("button", className);
  b.type = "button";
  b.innerHTML = ICON[icon];
  b.append(document.createTextNode(text));
  return b;
}

function button(text, className, onclick) {
  const b = el("button", className, text);
  b.type = "button";
  if (onclick) b.onclick = onclick;
  return b;
}

// кнопка в состоянии «идёт запрос»: блокируем повторные нажатия и показываем спиннер
async function busy(btn, fn) {
  btn.disabled = true;
  btn.classList.add("is-busy");
  try { return await fn(); }
  finally { btn.disabled = false; btn.classList.remove("is-busy"); }
}

function showError(errEl, e) {
  errEl.textContent = "Ошибка: " + e.message;
  errEl.scrollIntoView({ block: "nearest", behavior: "smooth" });
}

// короткое подтверждение «сохранено» внизу экрана
function toast(text) {
  let t = document.getElementById("toast");
  if (!t) {
    t = el("div", "toast");
    t.id = "toast";
    t.setAttribute("role", "status");
    document.body.appendChild(t);
  }
  t.innerHTML = ICON.check;
  t.append(document.createTextNode(text));
  t.classList.remove("show");
  void t.offsetWidth; // перезапуск анимации, если тост уже на экране
  t.classList.add("show");
  clearTimeout(t.hideTimer);
  t.hideTimer = setTimeout(() => t.classList.remove("show"), 2600);
}

// Шторка снизу вместо системного confirm(): свой заголовок, пояснение и сколько угодно вариантов.
// Резолвится значением нажатого варианта; тап мимо или Esc — null
function sheet(title, text, actions) {
  return new Promise((resolve) => {
    const overlay = el("div", "overlay");
    const box = el("div", "sheet");
    box.setAttribute("role", "dialog");
    box.setAttribute("aria-modal", "true");
    box.appendChild(el("h3", "", title));
    if (text) box.appendChild(el("p", "", text));
    const onKey = (e) => { if (e.key === "Escape") close(null); };
    function close(value) {
      document.removeEventListener("keydown", onKey);
      overlay.remove();
      resolve(value);
    }
    actions.forEach(([label, cls, value]) => box.appendChild(button(label, "btn " + cls, () => close(value))));
    overlay.onclick = (e) => { if (e.target === overlay) close(null); };
    document.addEventListener("keydown", onKey);
    overlay.appendChild(box);
    document.body.appendChild(overlay);
    box.querySelector("button").focus({ preventScroll: true });
  });
}

function ask(title, text, okLabel, okClass = "btn-primary") {
  return sheet(title, text, [[okLabel, okClass, true], ["Отмена", "btn-plain", false]]);
}

// Просмотр фото на весь экран: листание стрелками, свайпом и клавишами, закрытие — крестик, тап по фону, Esc
function openViewer(photos, index) {
  const overlay = el("div", "viewer");
  overlay.setAttribute("role", "dialog");
  overlay.setAttribute("aria-modal", "true");
  overlay.setAttribute("aria-label", "Просмотр фото");
  const img = el("img", "viewer-img");
  img.alt = "Фото к замечанию";
  const count = el("div", "viewer-count");
  const closeBtn = iconButton("cross", "", "viewer-btn viewer-close");
  closeBtn.setAttribute("aria-label", "Закрыть");
  overlay.append(img, count, closeBtn);

  function show(i) {
    index = (i + photos.length) % photos.length;
    img.removeAttribute("src");
    loadImage(photos[index].url, img);
    count.textContent = photos.length > 1 ? `${index + 1} из ${photos.length}` : "";
  }
  if (photos.length > 1) {
    const prev = iconButton("back", "", "viewer-btn viewer-prev");
    const next = iconButton("chevron", "", "viewer-btn viewer-next");
    prev.setAttribute("aria-label", "Предыдущее фото");
    next.setAttribute("aria-label", "Следующее фото");
    prev.onclick = () => show(index - 1);
    next.onclick = () => show(index + 1);
    overlay.append(prev, next);
    let x0 = null;
    overlay.addEventListener("pointerdown", (e) => { x0 = e.clientX; });
    overlay.addEventListener("pointerup", (e) => {
      if (x0 !== null && Math.abs(e.clientX - x0) > 50) show(index + (e.clientX < x0 ? 1 : -1));
      x0 = null;
    });
  }
  const onKey = (e) => {
    if (e.key === "Escape") close();
    else if (e.key === "ArrowLeft" && photos.length > 1) show(index - 1);
    else if (e.key === "ArrowRight" && photos.length > 1) show(index + 1);
  };
  function close() {
    document.removeEventListener("keydown", onKey);
    overlay.remove();
  }
  closeBtn.onclick = close;
  overlay.addEventListener("click", (e) => { if (e.target === overlay) close(); });
  document.addEventListener("keydown", onKey);
  document.body.appendChild(overlay);
  show(index);
  closeBtn.focus({ preventScroll: true });
}

// миниатюра, которая открывает просмотр всей серии фото с этого места
function photoThumb(photos, index, alt) {
  const b = el("button", "thumb-btn");
  b.type = "button";
  b.setAttribute("aria-label", `${alt}: открыть на весь экран`);
  const img = el("img", "thumb");
  img.alt = alt;
  loadImage(photos[index].url, img);
  b.appendChild(img);
  b.onclick = () => openViewer(photos, index);
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

// шкала «выполнено / претензия» без персональных данных (FR-D5); обновляется на месте через setMeter
function meter(stats, withPhoto) {
  const wrap = el("div", "meter");
  const bar = el("div", "meter-bar");
  bar.append(el("span", "meter-ok"), el("span", "meter-issue"));
  wrap.append(bar, el("div", "meter-caption"));
  wrap.withPhoto = withPhoto;
  setMeter(wrap, stats);
  return wrap;
}

function setMeter(wrap, { ok, issue, issueWithPhoto }) {
  const key = `${ok}/${issue}/${issueWithPhoto}`;
  if (wrap.key === key) return;
  const changed = wrap.key !== undefined;
  wrap.key = key;
  const total = ok + issue;
  wrap.querySelector(".meter-ok").style.width = total ? (ok / total * 100) + "%" : "0";
  wrap.querySelector(".meter-issue").style.width = total ? (issue / total * 100) + "%" : "0";
  const cap = wrap.querySelector(".meter-caption");
  cap.innerHTML = "";
  if (!total) cap.textContent = "Пока никто не отметил";
  else {
    const left = el("span");
    left.innerHTML = `<b class="meter-num--ok">${ok}</b> выполнено · <b class="meter-num--issue">${issue}</b> с претензией`;
    cap.append(left, el("span", "", wrap.withPhoto ? `с фото: ${issueWithPhoto}` : `всего ${total}`));
  }
  if (changed) { // подсветка: цифры изменились прямо сейчас
    wrap.classList.remove("bump");
    void wrap.offsetWidth;
    wrap.classList.add("bump");
  }
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
  app.appendChild(el("p", isError ? "error" : "empty-state", text));
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

  // ссылка из уведомления ведёт сразу в акт; меню бота и всё остальное — в список актов дома
  const sp = startParam() || "";
  if (sp.startsWith("act_")) await openAct(Number(sp.slice(4)));
  else if (sp.startsWith("refusal_")) await openAct(Number(sp.slice(8)), "refusal");
  else await showList();

  if (WA && WA.BackButton) WA.BackButton.onClick(goToList);
  setInterval(() => { if (!document.hidden) refresh(); }, POLL_MS);
  document.addEventListener("visibilitychange", () => { if (!document.hidden) refresh(); });
}

async function openAct(id, tab = "checklist") {
  try {
    state.act = await api(`/api/acts/${id}`);
  } catch (e) {
    // старая ссылка на архивный/чужой акт — не тупик: показываем список и объясняем
    await showList(`Не удалось открыть акт: ${e.message}`);
    return;
  }
  state.view = "act";
  state.tab = tab;
  if (WA && WA.BackButton) WA.BackButton.show();
  render();
  window.scrollTo(0, 0);
}

// выход из акта в список — через защиту от потери несохранённых правок (редактор карточки)
async function goToList() {
  if (state.leaveGuard && !(await state.leaveGuard())) return;
  await showList();
}

async function showList(notice) {
  state.leaveGuard = null;
  if (WA && WA.BackButton) WA.BackButton.hide();
  state.view = "list";
  state.act = null;
  live = [];
  try {
    state.house = await api("/api/acts");
  } catch (e) {
    document.getElementById("header").innerHTML = "";
    showMessage(document.getElementById("app"), "Ошибка: " + e.message, true);
    return;
  }
  renderList(notice);
  window.scrollTo(0, 0);
}

function refresh() {
  if (state.view === "list") refreshList();
  else refreshAct();
}

async function refreshList() {
  try {
    const fresh = await api("/api/acts");
    if (state.view !== "list" || JSON.stringify(fresh) === JSON.stringify(state.house)) return;
    state.house = fresh;
    const y = window.scrollY;
    renderList();
    window.scrollTo(0, y);
  } catch (e) { /* повторим на следующем тике */ }
}

// Фоновый опрос: отметки других жителей приходят без перезагрузки. Карточки обновляются на месте,
// чтобы не сбить набранный текст и прокрутку; полная перерисовка — только при смене статуса акта
let refreshing = false;
async function refreshAct() {
  const act = state.act;
  if (!act || refreshing || FINAL.includes(act.status)) return;
  if (act.status === "RECEIVED" && act.recognition !== "PENDING") return; // не трогаем редактор карточки
  refreshing = true;
  try {
    const fresh = await api(`/api/acts/${act.id}`);
    if (state.act !== act) return;
    if (fresh.status !== act.status || fresh.recognition !== act.recognition) {
      state.act = fresh;
      render();
      return;
    }
    fresh.items.forEach((f) => { const it = act.items.find((i) => i.id === f.id); if (it) Object.assign(it, f); });
    syncLive();
  } catch (e) { /* сеть моргнула — повторим на следующем тике */ }
  finally { refreshing = false; }
}

function shortDate(iso) { return formatDate(iso).slice(0, 5); }

function formatDate(iso) {
  const [y, m, d] = iso.slice(0, 10).split("-");
  return `${d}.${m}.${y}`;
}

function renderHeader(act) {
  const inner = heroShell();
  const back = iconButton("back", "Все акты", "hero-back");
  back.onclick = () => busy(back, goToList);
  inner.append(back, eyebrow(act.houseAddress));

  inner.appendChild(el("h1", "app-title", act.recognition === "PENDING" ? "Новый акт" : `Акт № ${act.number || "без номера"} за ${act.period || "—"}`));
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

  if (act.isChairman) {
    const del = button("Удалить акт", "hero-back hero-delete");
    del.onclick = async () => {
      if (!await ask("Удалить акт?", "Он пропадёт из списка актов, напоминаний по нему не будет. Замечания жителей и документы сохранятся в архиве.", "Удалить", "btn-negative")) return;
      busy(del, async () => {
        try {
          await api(`/api/acts/${act.id}/delete`, { method: "POST" });
          await showList();
          toast("Акт удалён");
        } catch (e) { toast(e.message); }
      });
    };
    inner.appendChild(del);
  }
}

function heroShell() {
  const header = document.getElementById("header");
  header.innerHTML = "";
  const inner = el("div", "hero-inner");
  header.appendChild(inner);
  return inner;
}

function eyebrow(address) {
  const p = el("p", "app-eyebrow");
  p.innerHTML = ICON.home;
  p.append(document.createTextNode(address));
  return p;
}

function plural(n, one, few, many) {
  const m10 = n % 10, m100 = n % 100;
  if (m10 === 1 && m100 !== 11) return one;
  if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return few;
  return many;
}

// ───── Список актов дома ─────
function renderList(notice) {
  const { houseAddress, isChairman, acts } = state.house;
  const inner = heroShell();
  inner.appendChild(eyebrow(houseAddress));
  inner.appendChild(el("h1", "app-title", "Акты дома"));
  const active = acts.filter((a) => !FINAL.includes(a.status));
  const done = acts.filter((a) => FINAL.includes(a.status));
  inner.appendChild(el("p", "hero-sub", acts.length
    ? `${active.length} в работе · ${done.length} ${plural(done.length, "завершён", "завершено", "завершено")}`
    : "Пока ни одного акта"));

  const app = document.getElementById("app");
  app.innerHTML = "";
  if (notice) app.appendChild(el("div", "error", notice));
  if (!acts.length) {
    showMessage(app, isChairman
      ? "Пришлите акт боту в чат файлом — PDF или фото. Можно несколько: каждый акт проверяется отдельно."
      : "По дому пока нет актов. Бот пришлёт ссылку, когда председатель откроет сбор замечаний.");
    return;
  }
  if (active.length) {
    app.appendChild(el("p", "section-title", "В работе"));
    active.forEach((a) => app.appendChild(actRow(a, isChairman)));
  }
  if (done.length) {
    app.appendChild(el("p", "section-title", "Завершённые"));
    done.forEach((a) => app.appendChild(actRow(a, isChairman)));
  }
}

function actRow(a, isChairman) {
  const row = el("button", `act-row act-row--${a.status}`);
  row.type = "button";
  row.onclick = () => busy(row, () => openAct(a.id));

  const top = el("div", "act-row-top");
  top.append(el("b", "act-row-title", `Акт № ${a.number || "без номера"}`), el("span", `act-status act-status--${a.status}`, STATUS_RU[a.status] || a.status));
  const sub = el("p", "act-row-sub", `за ${a.period || "—"} · получен ${shortDate(a.receivedAt)}`);

  const chips = el("div", "item-meta");
  const chip = (text, mod) => chips.appendChild(el("span", "chip" + (mod ? " chip--" + mod : ""), text));
  if (a.status === "RECEIVED" && a.recognition === "PENDING") chip("распознаётся…");
  else if (a.status === "RECEIVED" && isChairman) chip("проверьте позиции и откройте сбор", "accent");
  if (a.myPending > 0) chip(`отметьте ${a.myPending} ${plural(a.myPending, "позицию", "позиции", "позиций")}`, "accent");
  else if (a.myPending === 0) chip("вы отметили всё", "positive");
  if (!FINAL.includes(a.status)) {
    const left = Math.max(a.daysLeft30, 0);
    chip(`${left} ${plural(left, "день", "дня", "дней")} до ${shortDate(a.deadline30)}`, left <= 5 ? "negative" : null);
  }
  if (a.issueCount) chip(`${a.issueCount} ${plural(a.issueCount, "претензия", "претензии", "претензий")}`, "negative");

  const main = el("div", "act-row-main");
  main.append(top, sub);
  if (chips.children.length) main.appendChild(chips);
  const chevron = el("span", "act-row-chevron");
  chevron.innerHTML = ICON.chevron;
  row.append(main, chevron);
  return row;
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

// карточка-итог: иконка + заголовок + пояснение (+ кнопка)
function resultCard(kind, icon, title, text, btn) {
  const b = el("div", `result-banner result-${kind}`);
  const ic = el("div", "result-icon");
  ic.innerHTML = ICON[icon];
  const body = el("div", "result-body");
  body.append(el("h3", "", title), el("p", "", text));
  if (btn) body.appendChild(btn);
  b.append(ic, body);
  return b;
}

function backToChatButton() {
  if (!(WA && WA.close)) return null;
  return button("Вернуться в чат", "btn btn-secondary btn-inline", () => WA.close());
}

function resultBanner(act) {
  const texts = {
    SIGNED: ["check", "Акт принят без возражений", "Файл акта отправлен председателю в чат — он пересылает его в УК. " +
      "Электронной подписи на файле нет: Госключ пока не подключён."],
    REJECTED: ["cross", "Мотивированный отказ направлен", "УК должна оформить новый акт с учётом возражений. Спасибо за отметки!"],
    SILENT: ["clock", "Принят молчаливым согласием", "30 дней со дня получения истекли без подписи и отказа (п. 5 Порядка)."],
  };
  const t = texts[act.status];
  return t ? resultCard(act.status, ...t) : null;
}

function render() {
  const app = document.getElementById("app");
  const act = state.act;
  live = [];
  state.leaveGuard = null;
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

  const tabs = el("div", "tabs");
  tabs.setAttribute("role", "tablist");
  const defs = [["checklist", "Мои отметки"], ["remarks", "Решение"]];
  if (hasRefusalTab) defs.push(["refusal", "Отказ"]);
  const buttons = defs.map(([key, title]) => {
    const b = button(title);
    b.setAttribute("role", "tab");
    b.onclick = () => { state.tab = key; renderBody(); };
    tabs.appendChild(b);
    return [key, b];
  });
  const remarksTab = buttons[1][1];
  const count = el("span", "tab-count");
  remarksTab.appendChild(count);
  function updateCount() {
    const issues = act.items.reduce((n, i) => n + i.stats.issue, 0);
    count.textContent = String(issues);
    count.hidden = !issues;
  }
  app.appendChild(tabs);

  const body = el("div", "tab-body");
  app.appendChild(body);

  function renderBody() {
    buttons.forEach(([key, b]) => {
      b.classList.toggle("active", state.tab === key);
      b.setAttribute("aria-selected", String(state.tab === key));
    });
    live = [updateCount];
    updateCount();
    body.innerHTML = "";
    if (state.tab === "checklist") renderChecklist(body, act);
    else if (state.tab === "remarks") renderRemarksSummary(body, act, (key) => { state.tab = key; renderBody(); window.scrollTo(0, 0); });
    else renderRefusalTab(body, act);
  }
  renderBody();
}

function renderRefusalTab(app, act) {
  app.appendChild(el("p", "loading-state", "Загрузка…"));
  api(`/api/acts/${act.id}/refusal`)
    .then((dto) => { app.innerHTML = ""; renderRefusalForm(app, act, dto); })
    .catch((e) => {
      app.innerHTML = "";
      if (e.code === "no_refusal") renderRefusalEmpty(app, act);
      else app.appendChild(el("div", "error", "Ошибка: " + e.message));
    });
}

function renderRefusalEmpty(app, act) {
  const err = el("div", "error");
  const btn = button("Собрать черновик", "btn btn-primary btn-inline");
  btn.onclick = () => busy(btn, async () => {
    err.textContent = "";
    try {
      const dto = await api(`/api/acts/${act.id}/refusal/draft?rebuild=false`, { method: "POST" });
      app.innerHTML = "";
      renderRefusalForm(app, act, dto);
    } catch (e) { showError(err, e); }
  });
  app.append(resultCard("DRAFT", "doc", "Черновик отказа ещё не собран",
    "Соберём его из оспоренных позиций: факты — из замечаний жителей, основания — из справочника.", btn), err);
}

function renderRefusalForm(app, act, dto) {
  if (dto.confirmedAt) { renderRefusalConfirmed(app); return; }

  const err = el("div", "error");
  app.appendChild(err);

  app.appendChild(el("p", "hint", "Черновик собран по шаблону мотивированного отказа. Проверьте по каждой позиции " +
    "«По данным опроса жильцов» и «Возражение» — реквизиты УК, акта и дома подставятся в PDF сами."));

  const placeCard = el("div", "card");
  const placeField = field("Место составления", dto.place);
  placeCard.appendChild(placeField.wrap);
  app.appendChild(placeCard);

  // поля карточки повторяют блок позиции в документе: указано в акте → опрос жильцов → возражение
  const objectionInputs = dto.objections.map((o) => {
    const wrap = el("div", "item-card");
    wrap.appendChild(itemHead(o.lineNo, o.itemName, [
      [`претензий: ${o.issueCount}`, "negative"], [`фото: ${o.photoCount}`, "accent"], [`выполнено: ${o.okCount}`, "positive"],
    ]));

    const inAct = el("div", "ground-box");
    inAct.appendChild(el("b", "", "Указано в акте"));
    inAct.append(document.createTextNode(o.actWording || `«${o.itemName}»`));
    const fact = textField("По данным опроса жильцов", o.fact);
    fact.wrap.appendChild(el("span", "field-note", `В документе добавится: отметили выполнение — ${o.okCount} из ${o.okCount + o.issueCount} опрошенных`));
    const demand = textField("Возражение", o.demand);
    wrap.append(inAct, fact.wrap, demand.wrap);

    app.appendChild(wrap);
    return { itemId: o.itemId, factArea: fact.input, demandArea: demand.input };
  });

  if (dto.noObjectionLineNos.length > 0) {
    app.appendChild(el("p", "hint", `По позициям № ${dto.noObjectionLineNos.join(", ")} возражений не имеется.`));
  }

  function currentEdit() {
    return {
      place: placeField.input.value,
      objections: objectionInputs.map((o) => ({ itemId: o.itemId, fact: o.factArea.value, demand: o.demandArea.value })),
    };
  }

  function rerender(updated, message) {
    const y = window.scrollY;
    app.innerHTML = "";
    renderRefusalForm(app, act, updated);
    window.scrollTo(0, y);
    toast(message);
  }

  const saveBtn = button("Сохранить", "btn btn-secondary");
  saveBtn.onclick = () => busy(saveBtn, async () => {
    err.textContent = "";
    try { rerender(await api(`/api/acts/${act.id}/refusal`, { method: "PUT", json: currentEdit() }), "Черновик сохранён"); }
    catch (e) { showError(err, e); }
  });

  const rebuildBtn = button("Пересобрать", "btn btn-dashed");
  rebuildBtn.onclick = async () => {
    if (!await ask("Пересобрать черновик?", "Черновик соберётся заново из решений и замечаний — ваши правки текста будут потеряны.", "Пересобрать", "btn-negative")) return;
    busy(rebuildBtn, async () => {
      err.textContent = "";
      try { rerender(await api(`/api/acts/${act.id}/refusal/draft?rebuild=true`, { method: "POST" }), "Черновик пересобран"); }
      catch (e) { showError(err, e); }
    });
  };

  const confirmBtn = button("Подтвердить и сформировать PDF", "btn btn-primary");
  confirmBtn.onclick = async () => {
    if (!await ask("Подтвердить отказ?", "Сформируем PDF и отправим вам в чат. После подтверждения текст изменить нельзя.", "Подтвердить")) return;
    busy(confirmBtn, async () => {
      err.textContent = "";
      try {
        await api(`/api/acts/${act.id}/refusal`, { method: "PUT", json: currentEdit() });
        await api(`/api/acts/${act.id}/refusal/confirm`, { method: "POST" });
        app.innerHTML = "";
        renderRefusalConfirmed(app);
        window.scrollTo({ top: 0, behavior: "smooth" });
      } catch (e) { showError(err, e); }
    });
  };

  const row = el("div", "action-row");
  row.append(saveBtn, rebuildBtn);
  app.appendChild(actionBar(confirmBtn, row));
}

function renderRefusalConfirmed(app) {
  app.appendChild(resultCard("SIGNED", "check", "Отказ сформирован",
    "PDF отправлен вам в чат MAX. Перешлите его в УК и нажмите в чате «Отправил исполнителю».", backToChatButton()));
}

function renderRemarksSummary(app, act, goTab) {
  const err = el("div", "error");
  app.append(decisionSummary(act), err);

  const items = [...act.items].sort((a, b) => b.stats.issue - a.stats.issue);
  items.forEach((item) => app.appendChild(liveRemarksItem(item, err)));

  // главное действие зависит от решений: есть оспоренные — к отказу, нет — подписывать
  const bar = actionBar();
  app.appendChild(bar);
  function updateBar() {
    const disputed = act.items.filter((i) => i.decision === "DISPUTE").length;
    const signBtn = button(act.status === "COLLECTING" ? "Подписать без возражений" : "Подписать акт",
      disputed || act.status === "COLLECTING" ? "btn btn-secondary" : "btn btn-primary");
    signBtn.onclick = () => signAct(act, signBtn, err);
    if (act.status === "COLLECTING") {
      const closeBtn = button("Завершить сбор замечаний", "btn btn-primary");
      closeBtn.onclick = async () => {
        if (!await ask("Завершить сбор замечаний?", "Жители больше не смогут отмечать позиции. Дальше — решение по каждой позиции.", "Завершить сбор")) return;
        busy(closeBtn, async () => {
          err.textContent = "";
          try { state.act = await api(`/api/acts/${act.id}/close-collection`, { method: "POST" }); render(); }
          catch (e) { showError(err, e); }
        });
      };
      bar.replaceChildren(closeBtn, signBtn);
    } else if (disputed) {
      bar.replaceChildren(button("Перейти к мотивированному отказу", "btn btn-primary", () => goTab("refusal")), signBtn);
    } else {
      bar.replaceChildren(signBtn);
    }
  }
  updateBar();
  live.push(updateBar);
}

// сводка решений председателя и подсказка, что делать дальше
function decisionSummary(act) {
  const card = el("div", "summary-card");
  const stats = el("div", "summary-stats");
  const hint = el("p", "summary-hint");
  card.append(stats, hint);
  function stat(n, label, mod) {
    const s = el("div", "summary-stat summary-stat--" + mod);
    s.append(el("b", "", String(n)), el("span", "", label));
    return s;
  }
  function update() {
    const count = (d) => act.items.filter((i) => i.decision === d).length;
    const disputed = count("DISPUTE"), accepted = count("ACCEPT"), pending = act.items.length - disputed - accepted;
    stats.replaceChildren(stat(disputed, "оспорено", "issue"), stat(accepted, "принято", "ok"), stat(pending, "без решения", "pending"));
    let text;
    if (pending) text = `Решите по ${pending} ${plural(pending, "работе", "работам", "работам")}: каждую можно принять без возражений или оспорить. Оспорить можно только работу, к которой жители приложили фото.`;
    else if (disputed) text = `Оспорено ${disputed} ${plural(disputed, "работа", "работы", "работ")} — сформируйте мотивированный отказ или подпишите акт без возражений.`;
    else text = "Все работы приняты — акт можно подписывать.";
    if (act.status === "COLLECTING") text = "Сбор ещё идёт — жители могут добавлять отметки. " + text;
    hint.textContent = text;
  }
  update();
  live.push(update);
  return card;
}

async function signAct(act, btn, err) {
  const disputed = act.items.filter((i) => i.decision === "DISPUTE").length;
  const text = (act.status === "COLLECTING" ? "Сбор замечаний будет завершён. " : "") +
    (disputed ? `Оспорено позиций: ${disputed} — эти замечания в документ не попадут. ` : "") +
    "Файл акта придёт вам в чат — перешлите его в УК. Электронной подписи на нём нет: Госключ пока не подключён.";
  if (!await ask("Подписать акт без возражений?", text, "Подписать")) return;
  busy(btn, async () => {
    err.textContent = "";
    try {
      state.act = await api(`/api/acts/${act.id}/sign`, { method: "POST" });
      render();
      window.scrollTo({ top: 0, behavior: "smooth" });
      toast("Решение зафиксировано — файл акта в чате");
    } catch (e) { showError(err, e); }
  });
}

// карточка замечаний пересобирается целиком, когда у позиции появились новые отметки/формулировки LLM.
// Своё решение председателя перерисовки не вызывает — кнопки уже обновлены на месте
function liveRemarksItem(item, err) {
  const sig = () => JSON.stringify([item.stats, item.remarks, item.decision]);
  const onDecided = () => { last = sig(); syncLive(); };
  let card = renderRemarksItem(item, err, onDecided);
  let last = sig();
  live.push(() => {
    const s = sig();
    if (s === last) return;
    last = s;
    const next = renderRemarksItem(item, err, onDecided);
    next.classList.add("item-card--fresh");
    card.replaceWith(next);
    card = next;
  });
  return card;
}

function renderRemarksItem(item, err, onDecided) {
  const card = el("div", "item-card");

  card.appendChild(itemHead(item.lineNo, item.name, [item.periodicity && [item.periodicity]]));
  card.appendChild(meter(item.stats, true));

  (item.remarks || []).filter((r) => r.verdict === "ISSUE").forEach((r) => {
    const box = el("div", "remark-box");
    const text = el("p");
    if (r.llmStatus === "PENDING") { text.className = "llm-pending"; text.textContent = "Формулируем замечание…"; }
    else {
      text.textContent = r.formalized || r.text || "";
      if (r.llmStatus === "FAILED") text.appendChild(el("span", "llm-badge", " (без обработки)"));
    }
    box.appendChild(text);
    const photosDiv = el("div", "photos photos--inset");
    (r.photos || []).forEach((p, i) => photosDiv.appendChild(photoThumb(r.photos, i, "Фото к замечанию")));
    box.appendChild(photosDiv);
    card.appendChild(box);
  });

  const decisionRow = el("div", "segmented");
  const acceptBtn = iconButton("check", "Принять работу");
  const disputeBtn = iconButton("alert", "Оспорить работу", "negative");
  decisionRow.append(acceptBtn, disputeBtn);
  card.appendChild(decisionRow);

  const noPhoto = item.stats.issueWithPhoto === 0;
  if (noPhoto) {
    const note = el("div", "hint-box");
    note.innerHTML = ICON.alert;
    note.append(document.createTextNode("Нет замечаний с фото — оснований для возражения недостаточно"));
    card.appendChild(note);
  }

  function paint() {
    acceptBtn.classList.toggle("active", item.decision === "ACCEPT");
    disputeBtn.classList.toggle("active", item.decision === "DISPUTE");
    acceptBtn.setAttribute("aria-pressed", String(item.decision === "ACCEPT"));
    disputeBtn.setAttribute("aria-pressed", String(item.decision === "DISPUTE"));
    disputeBtn.disabled = noPhoto;
    card.classList.toggle("item-card--ok", item.decision === "ACCEPT");
    card.classList.toggle("item-card--issue", item.decision === "DISPUTE");
  }
  paint();

  function setDecision(btn, decision) {
    if (item.decision === decision) return;
    busy(btn, async () => {
      err.textContent = "";
      try {
        Object.assign(item, await api(`/api/items/${item.id}/decision`, { method: "PUT", json: { decision } }));
        onDecided();
      } catch (e) { showError(err, e); }
    }).then(paint);
  }
  acceptBtn.onclick = () => setDecision(acceptBtn, "ACCEPT");
  disputeBtn.onclick = () => setDecision(disputeBtn, "DISPUTE");

  return card;
}

function renderChecklist(app, act) {
  const editable = act.status === "COLLECTING";
  if (act.isChairman) app.appendChild(el("p", "summary-hint",
    "Здесь вы отмечаете работы как житель дома — наравне с соседями. Решение по акту — во вкладке «Решение»."));
  if (act.isResident && editable) app.appendChild(progressCard(act));
  else app.appendChild(el("p", "section-title", editable ? "Отметьте каждую работу" : "Сбор замечаний закрыт"));
  act.items.forEach((item) => app.appendChild(renderChecklistItem(item, editable)));
}

// сколько позиций житель уже отметил; когда все — показываем итог, чтобы было понятно, что всё готово
function progressCard(act) {
  const card = el("div", "progress-card");
  const top = el("div", "progress-top");
  const title = el("b");
  const counter = el("span", "progress-count");
  top.append(title, counter);
  const bar = el("div", "progress-bar");
  const fill = el("span");
  bar.appendChild(fill);
  const note = el("p", "progress-note");
  card.append(top, bar, note);

  function update() {
    const total = act.items.length;
    const done = act.items.filter((i) => i.my && i.my.verdict).length;
    const complete = total > 0 && done === total;
    card.classList.toggle("progress-card--done", complete);
    title.textContent = complete ? "Все работы отмечены" : "Отметьте каждую работу";
    counter.textContent = `${done} из ${total}`;
    fill.style.width = total ? (done / total * 100) + "%" : "0";
    note.textContent = complete
      ? "Спасибо! Председатель увидит отметки сразу. Изменить их можно до закрытия сбора."
      : "Выполнено — если работа сделана. Есть претензия — если нет или с недостатками.";
  }
  update();
  live.push(update);
  return card;
}

// Карточка позиции для жителя. Режимы: null (не отмечено) → OK | DRAFT (пишет претензию) → ISSUE (сохранена)
function renderChecklistItem(item, editable) {
  const card = el("div", "item-card");
  card.appendChild(itemHead(item.lineNo, item.name, [item.periodicity && [item.periodicity]]));
  const m = meter(item.stats);
  card.appendChild(m);
  live.push(() => setMeter(m, item.stats));

  const btnRow = el("div", "segmented");
  const okBtn = iconButton("check", "Выполнено");
  const issueBtn = iconButton("alert", "Есть претензия", "negative");
  btnRow.append(okBtn, issueBtn);
  const err = el("div", "error");
  const body = el("div", "item-body");
  card.append(btnRow, err, body);

  const saved = () => (item.my && item.my.verdict) || null;
  let mode = saved();
  let draft = null; // набранный, но не сохранённый текст — переживает переключения режимов

  function paint() {
    const v = mode === "DRAFT" ? "ISSUE" : mode;
    okBtn.classList.toggle("active", v === "OK");
    issueBtn.classList.toggle("active", v === "ISSUE");
    okBtn.setAttribute("aria-pressed", String(v === "OK"));
    issueBtn.setAttribute("aria-pressed", String(v === "ISSUE"));
    card.classList.toggle("item-card--ok", saved() === "OK");
    card.classList.toggle("item-card--issue", saved() === "ISSUE");
    body.innerHTML = "";
    if (mode === "DRAFT") body.appendChild(issueForm());
    else if (mode === "ISSUE") body.appendChild(savedIssue());
  }

  function apply(dto) {
    Object.assign(item, dto);
    mode = saved();
    paint();
    syncLive();
  }

  function issueForm() {
    const wrap = el("div", "issue-form");
    const ta = el("textarea");
    ta.placeholder = "Что именно не так? Например: в подъезде 2 не мыли пол с 10 сентября";
    ta.maxLength = 1000;
    ta.value = draft ?? (saved() === "ISSUE" ? item.my.text || "" : "");
    const foot = el("div", "form-foot");
    const counter = el("span", "char-count");
    foot.append(el("span", "", "Фото добавите следующим шагом"), counter);

    const cancelBtn = button("Отмена", "btn btn-secondary", () => { draft = null; mode = saved(); paint(); });
    const saveBtn = button("Сохранить", "btn btn-primary");
    const row = el("div", "action-row");
    row.append(cancelBtn, saveBtn);

    function sync() {
      const len = ta.value.trim().length;
      counter.textContent = `${ta.value.length}/1000`;
      saveBtn.disabled = len < 3;
    }
    ta.oninput = () => { draft = ta.value; sync(); };
    sync();

    saveBtn.onclick = () => busy(saveBtn, async () => {
      err.textContent = "";
      try {
        apply(await api(`/api/items/${item.id}/my-remark`, { method: "PUT", json: { verdict: "ISSUE", text: ta.value } }));
        draft = null;
        toast("Замечание сохранено");
      } catch (e) { showError(err, e); }
    }).then(() => { if (saveBtn.isConnected) sync(); });

    wrap.append(ta, foot, row);
    requestAnimationFrame(() => ta.focus({ preventScroll: true }));
    return wrap;
  }

  // сохранённая претензия: текст + фото; отсюда можно дозагрузить снимки или вернуться к редактированию
  function savedIssue() {
    const photos = item.my.photos || [];
    const wrap = el("div", "saved-issue");
    const head = el("div", "saved-head");
    const badge = el("span", "saved-badge");
    badge.innerHTML = ICON.check;
    badge.append(document.createTextNode(editable ? "Замечание сохранено" : "Ваше замечание"));
    head.appendChild(badge);
    if (editable) {
      const editBtn = iconButton("pen", "Изменить", "btn-link");
      editBtn.onclick = () => { mode = "DRAFT"; paint(); };
      head.appendChild(editBtn);
    }
    wrap.append(head, el("p", "saved-text", item.my.text || ""));

    const grid = el("div", "photos");
    photos.forEach((p, i) => grid.appendChild(photoThumb(photos, i, "Ваше фото")));
    if (editable && photos.length < 5) grid.appendChild(photoAddTile());
    wrap.appendChild(grid);

    if (editable && !photos.length) {
      const hint = el("div", "hint-box");
      hint.innerHTML = ICON.camera;
      hint.append(document.createTextNode("Добавьте фото — без него председатель не сможет заявить претензию в УК."));
      wrap.appendChild(hint);
    }
    return wrap;
  }

  function photoAddTile() {
    const fileInput = el("input");
    fileInput.type = "file";
    fileInput.accept = "image/*";
    fileInput.hidden = true;
    const tile = el("label", "photo-add"); // label сам открывает выбор файла — без вложенной кнопки
    tile.innerHTML = ICON.camera;
    tile.append(document.createTextNode("Фото"), fileInput);
    fileInput.onchange = () => {
      const file = fileInput.files[0];
      if (!file) return;
      busy(tile, async () => {
        err.textContent = "";
        try {
          const form = new FormData();
          form.append("photo", await resizeImage(file), "photo.jpg");
          const r = await fetch(window.API_BASE + `/api/items/${item.id}/my-remark/photos`, { method: "POST", headers: authHeaders(), body: form });
          if (!r.ok) { const e = await r.json().catch(() => ({ message: r.statusText })); throw new Error(e.message); }
          apply(await r.json());
          toast("Фото добавлено");
        } catch (e) { showError(err, e); }
      });
    };
    return tile;
  }

  paint();

  if (!editable) {
    okBtn.disabled = true;
    issueBtn.disabled = true;
    return card;
  }

  okBtn.onclick = async () => {
    if (saved() === "OK") { mode = "OK"; draft = null; paint(); return; }
    const photos = saved() === "ISSUE" ? item.my.photos.length : 0;
    if (saved() === "ISSUE" && !await ask("Отметить как выполненное?", photos ? "Ваше замечание и приложенные фото будут удалены." : "Ваше замечание будет удалено.", "Да, выполнено")) return;
    busy(okBtn, async () => {
      err.textContent = "";
      try { apply(await api(`/api/items/${item.id}/my-remark`, { method: "PUT", json: { verdict: "OK" } })); draft = null; }
      catch (e) { showError(err, e); }
    });
  };

  issueBtn.onclick = () => {
    if (mode === "DRAFT" || mode === "ISSUE") return;
    mode = "DRAFT";
    paint();
  };

  return card;
}

function field(label, value) {
  const wrap = el("div", "field");
  const lbl = el("label", "", label);
  const input = el("input");
  input.value = value;
  lbl.appendChild(input);
  wrap.appendChild(lbl);
  return { wrap, input };
}

function textField(label, value) {
  const wrap = el("label", "", label);
  const input = el("textarea");
  input.value = value;
  wrap.appendChild(input);
  return { wrap, input };
}

function renderCardEditor(app, act) {
  if (act.recognition === "PENDING") {
    const refreshBtn = button("Обновить", "btn btn-secondary btn-inline");
    refreshBtn.onclick = () => busy(refreshBtn, async () => { state.act = await api(`/api/acts/${act.id}`); render(); });
    app.appendChild(resultCard("DRAFT", "doc", "Акт распознаётся…", "Читаем реквизиты и позиции — обычно меньше минуты. Страница обновится сама.", refreshBtn));
    // скелетон будущих позиций: видно, что именно сейчас появится
    app.appendChild(el("p", "section-title", "Позиции акта"));
    for (let i = 0; i < 3; i++) {
      const sk = el("div", "item-card skeleton");
      sk.setAttribute("aria-hidden", "true");
      sk.innerHTML = '<div class="sk-head"><span class="sk sk-num"></span><span class="sk sk-line"></span></div><span class="sk sk-line sk-short"></span><div class="sk-row"><span class="sk"></span><span class="sk"></span><span class="sk"></span></div>';
      app.appendChild(sk);
    }
    return;
  }

  const err = el("div", "error");
  app.appendChild(err);

  app.appendChild(el("p", "section-title", "Реквизиты акта"));
  const infoCard = el("div", "card");
  const numberField = field("Номер", act.number || "");
  const dateField = field("Дата оформления", act.formedDate || "");
  dateField.input.type = "date";
  const periodField = field("Период", act.period || "");
  infoCard.append(numberField.wrap, dateField.wrap, periodField.wrap);
  app.append(infoCard);

  app.appendChild(el("p", "section-title", "Позиции акта"));
  const itemsDiv = el("div");
  app.appendChild(itemsDiv);

  const items = act.items.map((it) => ({
    id: it.id, name: it.name, periodicity: it.periodicity, volume: it.volume, cost: it.cost, workKind: it.workKind,
  }));

  function input(item, key, placeholder) {
    const i = el("input");
    i.value = item[key];
    i.placeholder = placeholder;
    i.setAttribute("aria-label", placeholder);
    i.oninput = () => { item[key] = i.value; };
    return i;
  }

  function renderItems() {
    itemsDiv.innerHTML = "";
    items.forEach((item, idx) => {
      const itemCard = el("div", "item-card");

      const itemHeader = el("div", "item-head item-head--editor");
      const delBtn = button("Удалить", "btn-ghost", () => { items.splice(idx, 1); renderItems(); });
      itemHeader.append(el("div", "item-num", String(idx + 1)), el("div", "item-main"), delBtn);

      const nameInput = input(item, "name", "Наименование работы");
      nameInput.classList.add("input-name");

      const row = el("div", "item-row");
      row.append(input(item, "periodicity", "Периодичность"), input(item, "volume", "Ед. изм./объём"), input(item, "cost", "Стоимость, руб."));

      const kindSelect = el("select");
      kindSelect.setAttribute("aria-label", "Вид работ");
      act.workKinds.forEach((wk) => {
        const opt = el("option", "", wk.title);
        opt.value = wk.code;
        if (wk.code === item.workKind) opt.selected = true;
        kindSelect.appendChild(opt);
      });
      kindSelect.onchange = () => { item.workKind = kindSelect.value; };

      itemCard.append(itemHeader, nameInput, row, kindSelect);
      itemsDiv.appendChild(itemCard);
    });
  }
  renderItems();

  const addBtn = button("+ Добавить позицию", "btn btn-dashed", () => {
    items.push({ id: null, name: "", periodicity: "", volume: "", cost: "", workKind: "OTHER" });
    renderItems();
    itemsDiv.lastChild.querySelector(".input-name").focus();
  });
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

  const saveBtn = button("Сохранить", "btn btn-secondary");
  saveBtn.onclick = () => busy(saveBtn, async () => {
    try {
      await save();
      const y = window.scrollY;
      render();
      window.scrollTo(0, y);
      toast("Карточка акта сохранена");
    } catch (e) { showError(err, e); }
  });

  // уход из редактора с несохранёнными правками: спрашиваем, а не теряем молча
  const snapshot = JSON.stringify(cardInput());
  state.leaveGuard = async () => {
    if (JSON.stringify(cardInput()) === snapshot) return true;
    const choice = await sheet("Сохранить изменения?", "В карточке акта есть несохранённые правки.", [
      ["Сохранить и выйти", "btn-primary", "save"], ["Выйти без сохранения", "btn-secondary", "drop"], ["Остаться", "btn-plain", null],
    ]);
    if (choice === "drop") return true;
    if (choice !== "save") return false;
    try { await save(); toast("Карточка акта сохранена"); return true; }
    catch (e) { showError(err, e); return false; }
  };

  const openBtn = button("Открыть сбор замечаний", "btn btn-primary");
  openBtn.onclick = async () => {
    if (!await ask("Открыть сбор замечаний?", "Жители получат ссылку, а позиции акта больше нельзя будет менять.", "Открыть сбор")) return;
    busy(openBtn, async () => {
      try {
        await save();
        state.act = await api(`/api/acts/${act.id}/open-collection`, { method: "POST" });
        render();
        window.scrollTo({ top: 0, behavior: "smooth" });
        toast("Сбор открыт — жители получили ссылку");
      } catch (e) { showError(err, e); }
    });
  };
  app.appendChild(actionBar(openBtn, saveBtn));
}

start();
