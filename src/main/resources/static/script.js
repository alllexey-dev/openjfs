'use strict';

const config = window.OPENJFS;

const TEXT_PREVIEW_LIMIT = 512 * 1024;
const SEARCH_DEBOUNCE_MS = 250;
const SORT_STORAGE_KEY = 'openjfs.sort';
const DEFAULT_SORT_DIRECTIONS = {name: 1, modified: -1, size: -1};

const el = {
    searchInput: document.querySelector('.search-input'),
    breadcrumbs: document.querySelector('.breadcrumbs'),
    toolbarActions: document.querySelector('.toolbar-actions'),
    currentDownload: document.querySelectorAll('[data-action="download-current"]'),
    browser: document.querySelector('.browser'),
    summaryText: document.querySelector('.summary-text'),
    sortSelect: document.querySelector('.sort-select select'),
    listHeader: document.querySelector('.list-header'),
    list: document.querySelector('.list'),
    viewer: document.querySelector('.viewer'),
    viewerIcon: document.querySelector('.viewer-icon'),
    viewerName: document.querySelector('.viewer-name'),
    viewerMeta: document.querySelector('.viewer-meta'),
    viewerBody: document.querySelector('.viewer-body'),
    state: document.querySelector('.state'),
    sheet: document.querySelector('.sheet'),
    sheetIcon: document.querySelector('.sheet-icon'),
    sheetTitle: document.querySelector('.sheet-title'),
    toast: document.querySelector('.toast'),
};

const state = {
    path: '',
    query: '',
    item: null,
    results: null,
    sort: loadSort(),
    request: null,
    sheetFile: null,
};

// ---------- icons ----------

const FILE_OUTLINE = '<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/>';

const ICONS = {
    folder: '<path d="M3 7.5A2.5 2.5 0 0 1 5.5 5H9l2 2h7.5A2.5 2.5 0 0 1 21 9.5v7a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 16.5z"/>',
    file: FILE_OUTLINE,
    text: FILE_OUTLINE + '<path d="M9 13h6M9 17h4"/>',
    pdf: FILE_OUTLINE + '<path d="M9 13h6M9 17h6"/>',
    code: FILE_OUTLINE + '<path d="m10 12.5-2 2 2 2M14 12.5l2 2-2 2"/>',
    image: '<rect x="3" y="4" width="18" height="16" rx="2.5"/><circle cx="9" cy="10" r="1.8"/><path d="m21 16-5-5-9 9"/>',
    video: '<rect x="3" y="5" width="18" height="14" rx="2.5"/><path d="m10 9.5 5 2.5-5 2.5z"/>',
    audio: '<path d="M9 18V6l11-2v12"/><circle cx="6.5" cy="18" r="2.5"/><circle cx="17.5" cy="16" r="2.5"/>',
    archive: '<rect x="3" y="4" width="18" height="5" rx="1.5"/><path d="M5 9v9a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V9M10 13h4"/>',
    home: '<path d="M4 10.5 12 4l8 6.5V19a1 1 0 0 1-1 1h-4.5v-6h-5v6H5a1 1 0 0 1-1-1z"/>',
    download: '<path d="M12 4v11M7 10.5l5 5 5-5M5 20h14"/>',
    link: '<path d="M10 14a4.5 4.5 0 0 0 6.36 0l2.83-2.83a4.5 4.5 0 0 0-6.36-6.36l-1.06 1.06"/><path d="M14 10a4.5 4.5 0 0 0-6.36 0l-2.83 2.83a4.5 4.5 0 0 0 6.36 6.36l1.06-1.06"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/>',
    chevron: '<path d="m9 6 6 6-6 6"/>',
    more: '<circle cx="12" cy="5.5" r="1.5" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1.5" fill="currentColor" stroke="none"/><circle cx="12" cy="18.5" r="1.5" fill="currentColor" stroke="none"/>',
    open: '<path d="M14 4h6v6M20 4l-9 9"/><path d="M18 14v4a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4"/>',
    sort: '<path d="M7 4v16M3.5 16.5 7 20l3.5-3.5M17 20V4M13.5 7.5 17 4l3.5 3.5"/>',
    arrowUp: '<path d="M12 19V5M6 11l6-6 6 6"/>',
    arrowDown: '<path d="M12 5v14M6 13l6 6 6-6"/>',
    alert: '<circle cx="12" cy="12" r="9"/><path d="M12 7.5v5.5M12 16.5v.01"/>',
    lock: '<rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
};

function icon(name) {
    const template = document.createElement('template');
    template.innerHTML = '<svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" '
        + `stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICONS[name]}</svg>`;
    return template.content.firstChild;
}

function fillIcons(root) {
    root.querySelectorAll('[data-icon]').forEach((node) => node.replaceChildren(icon(node.dataset.icon)));
}

// ---------- file kinds ----------

const KIND_EXTENSIONS = {
    image: ['png', 'jpg', 'jpeg', 'gif', 'webp', 'avif', 'bmp', 'svg', 'ico'],
    video: ['mp4', 'm4v', 'webm', 'mov', 'mkv', 'ogv'],
    audio: ['mp3', 'wav', 'ogg', 'oga', 'opus', 'm4a', 'aac', 'flac'],
    pdf: ['pdf'],
    archive: ['zip', 'rar', '7z', 'tar', 'gz', 'tgz', 'bz2', 'xz', 'zst'],
    code: ['java', 'kt', 'kts', 'js', 'mjs', 'ts', 'tsx', 'jsx', 'py', 'go', 'rs', 'c', 'h', 'cpp', 'hpp', 'cs',
        'rb', 'php', 'swift', 'sh', 'bash', 'zsh', 'bat', 'ps1', 'sql', 'html', 'css', 'scss', 'json', 'xml',
        'yml', 'yaml', 'toml', 'gradle', 'properties', 'dockerfile'],
    text: ['txt', 'md', 'log', 'csv', 'tsv', 'ini', 'conf', 'cfg', 'env', 'srt', 'nfo'],
};

const EXTENSION_KIND = new Map(
    Object.entries(KIND_EXTENSIONS).flatMap(([kind, extensions]) => extensions.map((ext) => [ext, kind]))
);

const isDirectory = (file) => file.type === 'DIRECTORY';

function fileExtension(name) {
    const dot = name.lastIndexOf('.');
    return dot > 0 ? name.substring(dot + 1).toLowerCase() : '';
}

function fileKind(file) {
    if (isDirectory(file)) return 'folder';
    return EXTENSION_KIND.get(fileExtension(file.name)) ?? 'file';
}

function kindColor(kind) {
    return `var(--c-${kind === 'file' ? 'other' : kind})`;
}

// ---------- paths ----------

const encodePath = (path) => path.split('/').map(encodeURIComponent).join('/');

function decodePath(path) {
    try {
        return decodeURIComponent(path);
    } catch (e) {
        return path; // malformed escape sequence typed by hand
    }
}

const normalizePath = (path) => path.replace(/^\/+|\/+$/g, '');
const fullPathOf = (file) => normalizePath(file.path + file.name);
const parentPathOf = (path) => path.includes('/') ? path.substring(0, path.lastIndexOf('/')) : '';
const uiUrl = (path) => '/ui/' + encodePath(path);

function readLocation() {
    return {
        path: normalizePath(decodePath(location.pathname.replace(/^\/ui\/?/, ''))),
        query: new URLSearchParams(location.search).get('q') ?? '',
    };
}

// ---------- formatting ----------

const collator = new Intl.Collator(undefined, {numeric: true, sensitivity: 'base'});
const dateFormat = new Intl.DateTimeFormat(undefined, {day: 'numeric', month: 'short', year: 'numeric'});
const dateTimeFormat = new Intl.DateTimeFormat(undefined, {dateStyle: 'medium', timeStyle: 'short'});

const formatDate = (millis) => millis < 0 ? '—' : dateFormat.format(millis);
const formatDateTime = (millis) => millis < 0 ? '' : dateTimeFormat.format(millis);

function formatBytes(bytes) {
    if (bytes == null || bytes < 0) return '—';
    if (bytes < 1024) return `${bytes} B`;
    const units = ['KB', 'MB', 'GB', 'TB', 'PB'];
    let value = bytes / 1024;
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
        value /= 1024;
        unit++;
    }
    return `${value < 10 ? value.toFixed(1) : Math.round(value)} ${units[unit]}`;
}

function plural(count, word) {
    return `${count} ${word}${count === 1 ? '' : 's'}`;
}

// ---------- loading ----------

class HttpError extends Error {
    constructor(status) {
        super(`HTTP ${status}`);
        this.status = status;
    }
}

async function fetchResponse(url, signal) {
    const response = await fetch(url, {signal});
    if (!response.ok) throw new HttpError(response.status);
    return response;
}

function navigate(path, {query = '', replace = false} = {}) {
    const url = uiUrl(path) + (query ? `?q=${encodeURIComponent(query)}` : '');
    history[replace ? 'replaceState' : 'pushState'](null, '', url);
    load(path, query);
}

async function load(path, query) {
    // drop the previous navigation, otherwise a slow response may overwrite the newer one
    state.request?.abort();
    const request = new AbortController();
    state.request = request;
    state.path = path;
    state.query = query;
    if (el.searchInput.value.trim() !== query) el.searchInput.value = query;
    document.body.classList.add('loading');

    try {
        const item = await (await fetchResponse(`/list/${encodePath(path)}`, request.signal)).json();
        let results = null;
        if (query && isDirectory(item)) {
            const url = `/search/${encodePath(path)}?q=${encodeURIComponent(query)}`;
            results = await (await fetchResponse(url, request.signal)).json();
        }
        state.item = item;
        state.results = results;
        render();
    } catch (error) {
        if (error.name === 'AbortError') return;
        renderError(error);
    } finally {
        if (state.request === request) document.body.classList.remove('loading');
    }
}

// ---------- rendering ----------

function render() {
    const item = state.item;
    const name = state.path ? item.name : '';
    document.title = name ? `${name} · ${config.serverName}` : config.serverName;

    renderBreadcrumbs();
    el.state.classList.add('hidden');
    const downloadable = !isDirectory(item) || config.allowDownloadDirs;
    el.currentDownload.forEach((button) => button.classList.toggle('hidden', !downloadable));
    // the file viewer has its own download and share buttons
    el.toolbarActions.classList.toggle('hidden', !isDirectory(item));

    if (isDirectory(item)) {
        el.viewer.classList.add('hidden');
        el.browser.classList.remove('hidden');
        el.viewerBody.replaceChildren();
        renderList();
    } else {
        el.browser.classList.add('hidden');
        el.viewer.classList.remove('hidden');
        renderViewer(item);
    }
}

function renderBreadcrumbs() {
    const home = crumb('', state.path === '');
    home.replaceChildren(icon('home'));
    home.setAttribute('aria-label', 'Home');
    const nodes = [home];

    let prefix = '';
    const segments = state.path ? state.path.split('/') : [];
    segments.forEach((segment, index) => {
        prefix = prefix ? `${prefix}/${segment}` : segment;
        const separator = document.createElement('span');
        separator.className = 'crumb-separator';
        separator.append(icon('chevron'));
        const link = crumb(prefix, index === segments.length - 1);
        link.textContent = segment;
        nodes.push(separator, link);
    });

    el.breadcrumbs.replaceChildren(...nodes);
    el.breadcrumbs.scrollLeft = el.breadcrumbs.scrollWidth;
}

function crumb(path, current) {
    const link = document.createElement('a');
    link.className = current ? 'crumb current' : 'crumb';
    link.href = uiUrl(path);
    link.dataset.path = path;
    return link;
}

function renderList() {
    const searching = state.results !== null;
    const files = [...(searching ? state.results : state.item.files ?? [])].sort(compareFiles);

    el.summaryText.textContent = searching ? searchSummary(files.length) : folderSummary(files);
    renderSortControls();

    const rows = files.map((file) => createRow(file, searching));
    if (files.length === 0) {
        rows.push(searching
            ? stateNode('search', 'Nothing found', `No files matching “${state.query}” in this folder.`)
            : stateNode('folder', 'This folder is empty', ''));
    } else if (searching && files.length >= config.searchMaxResults) {
        const more = document.createElement('div');
        more.className = 'list-more';
        more.textContent = `Showing the first ${config.searchMaxResults} results, refine your search to see more.`;
        rows.push(more);
    }
    el.list.replaceChildren(...rows);
}

function folderSummary(files) {
    if (files.length === 0) return '';
    const folders = files.filter(isDirectory).length;
    const parts = [];
    if (folders) parts.push(plural(folders, 'folder'));
    if (files.length - folders) parts.push(plural(files.length - folders, 'file'));
    return parts.join(', ');
}

function searchSummary(count) {
    return count ? `${plural(count, 'result')} for “${state.query}”` : '';
}

function createRow(file, searching) {
    const kind = fileKind(file);
    const path = fullPathOf(file);

    const row = document.createElement('div');
    row.className = file.name.startsWith('.') ? 'row muted' : 'row';
    row.setAttribute('role', 'listitem');
    row.style.setProperty('--type-color', kindColor(kind));

    const iconBox = document.createElement('span');
    iconBox.className = 'row-icon';
    iconBox.append(icon(kind === 'folder' ? 'folder' : kind));

    const main = document.createElement('div');
    main.className = 'row-main';
    const link = document.createElement('a');
    link.className = 'row-link row-name';
    link.href = uiUrl(path);
    link.dataset.path = path;
    link.textContent = file.name;
    link.title = file.name;
    main.append(link);

    if (searching) {
        main.append(subLine('row-sub', file.path ? `in ${normalizePath(file.path)}` : 'in the top folder'));
    } else {
        const meta = isDirectory(file)
            ? formatDate(file.lastModifiedMillis)
            : `${formatBytes(file.size)} · ${formatDate(file.lastModifiedMillis)}`;
        main.append(subLine('row-sub row-meta', meta));
    }

    const modified = subLine('row-modified', formatDate(file.lastModifiedMillis));
    modified.title = formatDateTime(file.lastModifiedMillis);
    const size = subLine('row-size', isDirectory(file) ? '—' : formatBytes(file.size));

    row.append(iconBox, main, modified, size, createRowActions(file));
    return row;
}

function subLine(className, text) {
    const node = document.createElement('div');
    node.className = className;
    node.textContent = text;
    return node;
}

function createRowActions(file) {
    const actions = document.createElement('div');
    actions.className = 'row-actions';

    if (!isDirectory(file) || config.allowDownloadDirs) {
        actions.append(actionButton('download', 'Download', () => download(file)));
    }
    actions.append(
        actionButton('share', 'Copy link', () => shareFile(file)),
        actionButton('more', 'More actions', () => openSheet(file)),
    );
    return actions;
}

function actionButton(action, label, onClick) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'icon-button';
    button.dataset.rowAction = action;
    button.title = label;
    button.setAttribute('aria-label', label);
    button.append(icon(action === 'share' ? 'link' : action));
    button.addEventListener('click', onClick);
    return button;
}

function stateNode(iconName, title, text) {
    const node = document.createElement('div');
    node.className = 'state';
    fillState(node, iconName, title, text);
    return node;
}

function fillState(node, iconName, title, text) {
    const iconBox = document.createElement('span');
    iconBox.className = 'state-icon';
    iconBox.append(icon(iconName));
    node.replaceChildren(iconBox, subLine('state-title', title), subLine('state-text', text));
}

function renderError(error) {
    renderBreadcrumbs();
    el.browser.classList.add('hidden');
    el.viewer.classList.add('hidden');
    el.viewerBody.replaceChildren();
    el.toolbarActions.classList.add('hidden');
    el.state.classList.remove('hidden');

    if (error.status === 404) {
        fillState(el.state, 'search', 'Not found', 'This file or folder does not exist or is hidden.');
    } else if (error.status === 403) {
        fillState(el.state, 'lock', 'Access denied', 'You are not allowed to open this location.');
    } else {
        const details = error.status ? `The server responded with ${error.status}.` : 'Check your connection and try again.';
        fillState(el.state, 'alert', 'Something went wrong', details);
    }
}

// ---------- sorting ----------

function loadSort() {
    const [key, direction] = (localStorage.getItem(SORT_STORAGE_KEY) ?? '').split(':');
    return key in DEFAULT_SORT_DIRECTIONS ? {key, direction: Number(direction) || 1} : {key: 'name', direction: 1};
}

function setSort(key, direction) {
    state.sort = {key, direction};
    localStorage.setItem(SORT_STORAGE_KEY, `${key}:${direction}`);
    if (state.item && isDirectory(state.item)) renderList();
}

function compareFiles(a, b) {
    if (isDirectory(a) !== isDirectory(b)) return isDirectory(a) ? -1 : 1; // folders always go first

    const {key, direction} = state.sort;
    let result = 0;
    if (key === 'modified') result = a.lastModifiedMillis - b.lastModifiedMillis;
    if (key === 'size') result = (a.size ?? -1) - (b.size ?? -1);
    if (result === 0 && key !== 'name') return collator.compare(a.name, b.name);
    if (result === 0) result = collator.compare(a.name, b.name);
    return result * direction;
}

function renderSortControls() {
    const {key, direction} = state.sort;
    el.sortSelect.value = `${key}:${direction}`;
    el.listHeader.querySelectorAll('[data-sort]').forEach((button) => {
        const active = button.dataset.sort === key;
        button.classList.toggle('active', active);
        button.querySelector('.icon')?.remove();
        if (active) button.append(icon(direction === 1 ? 'arrowUp' : 'arrowDown'));
    });
}

// ---------- viewer ----------

function renderViewer(file) {
    const kind = fileKind(file);
    el.viewer.style.setProperty('--type-color', kindColor(kind));
    el.viewerIcon.replaceChildren(icon(kind));
    el.viewerName.textContent = file.name;
    el.viewerMeta.textContent = [formatBytes(file.size), formatDateTime(file.lastModifiedMillis)]
        .filter(Boolean).join(' · ');
    el.viewerBody.replaceChildren(createPreview(file, kind));
}

function createPreview(file, kind) {
    const rawUrl = '/raw/' + encodePath(fullPathOf(file));

    if (kind === 'image') {
        const image = document.createElement('img');
        image.src = rawUrl;
        image.alt = file.name;
        image.addEventListener('error', () => image.replaceWith(previewPlaceholder(file, 'Cannot display this image.')));
        return image;
    }
    if (kind === 'video' || kind === 'audio') {
        const media = document.createElement(kind);
        media.controls = true;
        media.preload = 'metadata';
        media.playsInline = true;
        media.src = rawUrl;
        media.addEventListener('error', () => media.replaceWith(
            previewPlaceholder(file, `Your browser cannot play this ${kind} file.`)));
        return media;
    }
    if (kind === 'pdf') {
        // mobile browsers do not render embedded pdf, open it in a new tab instead
        if (!matchMedia('(pointer: fine)').matches) {
            return previewPlaceholder(file, 'PDF preview opens in a new tab.', openButton('Open PDF', rawUrl));
        }
        const frame = document.createElement('iframe');
        frame.src = rawUrl;
        frame.title = file.name;
        return frame;
    }
    if (kind === 'text' || kind === 'code') {
        return file.size <= TEXT_PREVIEW_LIMIT
            ? createTextPreview(file)
            : previewPlaceholder(file, 'This file is too large to preview.');
    }

    const placeholder = previewPlaceholder(file, 'No preview available for this file type.');
    if (file.size <= TEXT_PREVIEW_LIMIT) {
        const showText = textButton('Show as text');
        showText.addEventListener('click', () => placeholder.replaceWith(createTextPreview(file)));
        placeholder.append(showText);
    }
    return placeholder;
}

function createTextPreview(file) {
    const pre = document.createElement('pre');
    pre.textContent = 'Loading…';
    const signal = state.request?.signal;
    fetchResponse('/text/' + encodePath(fullPathOf(file)), signal)
        .then((response) => response.text())
        .then((text) => pre.textContent = text)
        .catch((error) => {
            if (error.name === 'AbortError') return;
            pre.replaceWith(previewPlaceholder(file, 'Failed to load the file contents.'));
        });
    return pre;
}

function previewPlaceholder(file, message, ...extra) {
    const placeholder = document.createElement('div');
    placeholder.className = 'viewer-placeholder';
    placeholder.style.setProperty('--type-color', kindColor(fileKind(file)));
    placeholder.append(subLine('', message), ...extra);
    return placeholder;
}

function textButton(label) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'button';
    button.textContent = label;
    return button;
}

function openButton(label, url) {
    const link = document.createElement('a');
    link.className = 'button primary';
    link.href = url;
    link.target = '_blank';
    link.rel = 'noopener';
    link.append(icon('open'), label);
    return link;
}

// ---------- actions ----------

function download(file) {
    location.href = '/direct/' + encodePath(fullPathOf(file));
}

function shareFile(file) {
    share(location.origin + uiUrl(fullPathOf(file)), file.name);
}

async function share(url, title) {
    // native share sheet on phones, clipboard on desktop
    if (navigator.share && matchMedia('(pointer: coarse)').matches) {
        try {
            await navigator.share({title, url});
        } catch (e) {
            // share sheet was dismissed
        }
        return;
    }
    if (await copyText(url)) {
        showToast('Link copied');
    } else {
        window.prompt('Copy this link:', url);
    }
}

async function copyText(text) {
    try {
        await navigator.clipboard.writeText(text);
        return true;
    } catch (e) {
        // navigator.clipboard is available only on https, fall back to the legacy way
    }
    const textarea = document.createElement('textarea');
    textarea.value = text;
    textarea.style.position = 'fixed';
    textarea.style.opacity = '0';
    document.body.append(textarea);
    textarea.select();
    try {
        return document.execCommand('copy');
    } catch (e) {
        return false;
    } finally {
        textarea.remove();
    }
}

let toastTimer = null;

function showToast(message) {
    el.toast.textContent = message;
    el.toast.classList.add('visible');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.toast.classList.remove('visible'), 2000);
}

function openSheet(file) {
    state.sheetFile = file;
    const kind = fileKind(file);
    el.sheet.style.setProperty('--type-color', kindColor(kind));
    el.sheetIcon.replaceChildren(icon(kind));
    el.sheetTitle.textContent = file.name;
    el.sheet.querySelector('[data-sheet-action="download"]')
        .classList.toggle('hidden', isDirectory(file) && !config.allowDownloadDirs);
    el.sheet.showModal();
}

function handleSheetAction(action) {
    const file = state.sheetFile;
    el.sheet.close();
    if (action === 'open') navigate(fullPathOf(file));
    if (action === 'download') download(file);
    if (action === 'share') shareFile(file);
}

// ---------- events ----------

function isPlainLeftClick(event) {
    return event.button === 0 && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey;
}

document.addEventListener('click', (event) => {
    const link = event.target.closest('a[data-path]');
    if (!link || event.defaultPrevented || !isPlainLeftClick(event)) return;
    event.preventDefault();
    navigate(link.dataset.path);
});

document.querySelectorAll('[data-action="download-current"]').forEach((button) => {
    button.addEventListener('click', () => download(state.item));
});

document.querySelectorAll('[data-action="share-current"]').forEach((button) => {
    button.addEventListener('click', () => share(location.origin + uiUrl(state.path), state.item?.name || config.serverName));
});

el.listHeader.querySelectorAll('[data-sort]').forEach((button) => {
    button.addEventListener('click', () => {
        const key = button.dataset.sort;
        const direction = state.sort.key === key ? -state.sort.direction : DEFAULT_SORT_DIRECTIONS[key];
        setSort(key, direction);
    });
});

el.sortSelect.addEventListener('change', () => {
    const [key, direction] = el.sortSelect.value.split(':');
    setSort(key, Number(direction));
});

let searchTimer = null;

el.searchInput.addEventListener('input', () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => {
        const query = el.searchInput.value.trim();
        if (query === state.query) return;
        // search from a file page looks in its folder
        const scope = state.item && !isDirectory(state.item) ? parentPathOf(state.path) : state.path;
        navigate(scope, {query, replace: state.query !== ''});
    }, SEARCH_DEBOUNCE_MS);
});

el.searchInput.addEventListener('keydown', (event) => {
    if (event.key !== 'Escape') return;
    if (el.searchInput.value) {
        el.searchInput.value = '';
        el.searchInput.dispatchEvent(new Event('input'));
    } else {
        el.searchInput.blur();
    }
});

document.addEventListener('keydown', (event) => {
    const typing = event.target.closest('input, textarea, select');
    if (event.key === '/' && !typing && !el.sheet.open) {
        event.preventDefault();
        el.searchInput.focus();
    }
});

el.sheet.addEventListener('click', (event) => {
    const button = event.target.closest('[data-sheet-action]');
    if (button) {
        handleSheetAction(button.dataset.sheetAction);
        return;
    }
    const rect = el.sheet.getBoundingClientRect();
    const onBackdrop = event.clientY < rect.top || event.clientY > rect.bottom
        || event.clientX < rect.left || event.clientX > rect.right;
    if (onBackdrop) el.sheet.close();
});

window.addEventListener('popstate', () => {
    const {path, query} = readLocation();
    load(path, query);
});

fillIcons(document);
const initial = readLocation();
load(initial.path, initial.query);
