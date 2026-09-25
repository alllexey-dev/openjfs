'use strict';

// admin mode: login, uploads and file management; loaded only when the admin password is configured

const adminEl = {
    accountButton: document.querySelector('.account-button'),
    uploadButtons: document.querySelectorAll('[data-action="upload"]'),
    uploadInput: document.querySelector('.upload-input'),
    dropOverlay: document.querySelector('.drop-overlay'),
    dropOverlayText: document.querySelector('.drop-overlay-text'),
    uploads: document.querySelector('.uploads'),
    uploadsTitle: document.querySelector('.uploads-title'),
    uploadsList: document.querySelector('.uploads-list'),
    modal: document.querySelector('.modal'),
    modalForm: document.querySelector('.modal-form'),
    modalTitle: document.querySelector('.modal-title'),
    modalBody: document.querySelector('.modal-body'),
    modalError: document.querySelector('.modal-error'),
    modalSubmit: document.querySelector('.modal-submit'),
    modalCancel: document.querySelector('.modal-actions [data-modal-close]'),
    privacyLabel: document.querySelector('.privacy-label'),
};

const ERROR_MESSAGES = {
    400: 'Invalid name or path.',
    401: 'Your session has expired, log in again.',
    404: 'Not found: it may have been moved or deleted.',
    409: 'Something with this name already exists.',
    413: 'The file is too large for the server.',
    429: 'Too many attempts, try again later.',
    507: 'Not enough disk space on the server.',
};

class UserError extends Error {
}

function errorMessage(error) {
    if (error instanceof UserError) return error.message;
    if (error.status) return ERROR_MESSAGES[error.status] ?? `Request failed (${error.status}).`;
    return 'Network error, check your connection.';
}

const joinPath = (folder, name) => folder ? `${folder}/${name}` : name;

const refresh = () => load(state.path, state.query);

// ---------- requests ----------

let csrf = null;

async function csrfHeaders(forceRefresh = false) {
    if (!csrf || forceRefresh) {
        const session = await (await fetchResponse('/api/session')).json();
        csrf = {header: session.csrfHeader, token: session.csrfToken};
    }
    return {[csrf.header]: csrf.token};
}

// a stale csrf token (403) is refreshed and the request is retried once
async function adminRequest(url, {method = 'POST', json} = {}, retry = true) {
    const headers = await csrfHeaders();
    if (json !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetch(url, {method, headers, body: json === undefined ? undefined : JSON.stringify(json)});
    if (response.status === 403 && retry) {
        await csrfHeaders(true);
        return adminRequest(url, {method, json}, false);
    }
    if (response.status === 401) sessionExpired();
    if (!response.ok) throw new HttpError(response.status);
    return response;
}

function sessionExpired() {
    config.admin = false;
    document.body.classList.remove('is-admin');
    updateAccountButton();
}

// ---------- modal ----------

let modalSubmitHandler = null;

let modalCloseHandler = null;

function openModal({title, body, submitLabel = 'OK', danger = false, onSubmit = null, onClose = null}) {
    if (adminEl.modal.open) closeModal();
    adminEl.modalTitle.textContent = title;
    adminEl.modalBody.replaceChildren(...[body].flat());
    adminEl.modalError.classList.add('hidden');
    adminEl.modalSubmit.textContent = submitLabel;
    adminEl.modalSubmit.classList.toggle('danger', danger);
    adminEl.modalSubmit.classList.toggle('hidden', !onSubmit);
    adminEl.modalSubmit.disabled = false;
    adminEl.modalCancel.textContent = onSubmit ? 'Cancel' : 'Close';
    modalSubmitHandler = onSubmit;
    modalCloseHandler = onClose;
    adminEl.modal.showModal();

    const input = adminEl.modalBody.querySelector('input:not([hidden])');
    input?.focus();
}

// the close handler is taken before closing, so a late 'close' event can't reach the next modal
function closeModal() {
    const handler = modalCloseHandler;
    modalCloseHandler = null;
    adminEl.modal.close();
    handler?.();
}

// closed by Escape
adminEl.modal.addEventListener('close', () => {
    const handler = modalCloseHandler;
    modalCloseHandler = null;
    handler?.();
});

function showModalError(message) {
    adminEl.modalError.textContent = message;
    adminEl.modalError.classList.remove('hidden');
}

adminEl.modalForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!modalSubmitHandler) return;
    adminEl.modalSubmit.disabled = true;
    try {
        await modalSubmitHandler();
        closeModal();
    } catch (error) {
        showModalError(errorMessage(error));
    } finally {
        adminEl.modalSubmit.disabled = false;
    }
});

document.querySelectorAll('[data-modal-close]').forEach((button) => {
    button.addEventListener('click', closeModal);
});

function textField({label, value = '', type = 'text', placeholder = '', autocomplete = 'off'}) {
    const node = document.createElement('label');
    node.className = 'field';
    const caption = document.createElement('span');
    caption.className = 'field-label';
    caption.textContent = label;
    const input = document.createElement('input');
    input.type = type;
    input.value = value;
    input.placeholder = placeholder;
    input.autocomplete = autocomplete;
    input.spellcheck = false;
    node.append(caption, input);
    return {node, input};
}

function paragraph(text) {
    const node = document.createElement('p');
    node.className = 'modal-text';
    node.textContent = text;
    return node;
}

// resolves to true when confirmed, false when the dialog is closed any other way
function confirmModal({title, text, submitLabel, danger = false}) {
    return new Promise((resolve) => {
        let confirmed = false;
        openModal({
            title, body: paragraph(text), submitLabel, danger,
            onSubmit: async () => confirmed = true,
            onClose: () => resolve(confirmed),
        });
    });
}

// a name for a new or renamed item, checked before it is sent to the server
function validName(value) {
    const name = value.trim();
    if (!name || name === '.' || name === '..') throw new UserError('Enter a name.');
    if (name.includes('/') || name.includes('\\')) throw new UserError('A name cannot contain slashes.');
    return name;
}

// ---------- account ----------

function updateAccountButton() {
    adminEl.accountButton.classList.remove('hidden');
    adminEl.accountButton.classList.toggle('active', config.admin);
    adminEl.accountButton.title = config.admin ? 'Admin menu' : 'Log in as admin';
}

adminEl.accountButton.addEventListener('click', () => config.admin ? openAccountMenu() : openLogin());

function openLogin() {
    // lets password managers remember the password for the fixed "admin" user
    const username = document.createElement('input');
    username.type = 'text';
    username.name = 'username';
    username.value = 'admin';
    username.autocomplete = 'username';
    username.hidden = true;
    const password = textField({label: 'Password', type: 'password', autocomplete: 'current-password'});

    openModal({
        title: 'Admin login',
        body: [username, password.node],
        submitLabel: 'Log in',
        onSubmit: async () => {
            const headers = await csrfHeaders(true);
            const response = await fetch('/api/login', {
                method: 'POST',
                headers: {...headers, 'Content-Type': 'application/x-www-form-urlencoded'},
                body: new URLSearchParams({username: 'admin', password: password.input.value}),
            });
            if (response.status === 401) throw new UserError('Wrong password.');
            if (response.status === 429) throw new UserError('Too many failed attempts, try again in 15 minutes.');
            if (!response.ok) throw new HttpError(response.status);
            location.reload();
        },
    });
}

function openAccountMenu() {
    openModal({
        title: 'Admin',
        body: [
            menuButton('trash', 'Trash', openTrash),
            menuButton('logout', 'Log out', logout),
        ],
    });
}

function menuButton(iconName, label, onClick) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'menu-button';
    button.append(icon(iconName), label);
    button.addEventListener('click', onClick);
    return button;
}

async function logout() {
    try {
        await adminRequest('/api/logout');
    } finally {
        location.reload();
    }
}

// ---------- uploads ----------

const uploadQueue = [];
const uploadItems = [];
let uploading = false;

function canUploadHere() {
    return config.admin && state.item && isDirectory(state.item) && state.results === null;
}

function queueUploads(files, folder) {
    for (const file of files) {
        const item = {file, folder, status: 'queued', xhr: null, node: createUploadNode(file)};
        uploadItems.push(item);
        uploadQueue.push(item);
        adminEl.uploadsList.append(item.node.root);
    }
    adminEl.uploads.classList.remove('hidden');
    updateUploadsTitle();
    processUploadQueue();
}

async function processUploadQueue() {
    if (uploading) return;
    uploading = true;
    while (uploadQueue.length) {
        await uploadWithConflictCheck(uploadQueue.shift());
        updateUploadsTitle();
    }
    uploading = false;
}

async function uploadWithConflictCheck(item) {
    let status = await upload(item, false);
    if (status === 409) {
        const replace = await confirmModal({
            title: 'Replace file?',
            text: `“${item.file.name}” already exists in this folder. Replace it?`,
            submitLabel: 'Replace',
            danger: true,
        });
        status = replace ? await upload(item, true) : 'skipped';
    }
    finishUpload(item, status);
}

// resolves to the http status, 0 when cancelled or failed on the network level
async function upload(item, overwrite, retry = true) {
    const headers = await csrfHeaders();
    const status = await new Promise((resolve) => {
        const xhr = new XMLHttpRequest();
        item.xhr = xhr;
        item.status = 'uploading';
        const path = encodePath(joinPath(item.folder, item.file.name));
        xhr.open('PUT', `/api/admin/files/${path}?overwrite=${overwrite}`);
        Object.entries(headers).forEach(([name, value]) => xhr.setRequestHeader(name, value));
        xhr.setRequestHeader('Content-Type', 'application/octet-stream');
        xhr.upload.addEventListener('progress', (event) => {
            if (event.lengthComputable) setUploadProgress(item, event.loaded / event.total);
        });
        xhr.addEventListener('loadend', () => resolve(xhr.status));
        xhr.send(item.file);
    });
    if (status === 403 && retry) {
        await csrfHeaders(true);
        return upload(item, overwrite, false);
    }
    return status;
}

function finishUpload(item, status) {
    item.xhr = null;
    if (status === 201) {
        item.status = 'done';
        setUploadProgress(item, 1);
        item.node.status.textContent = 'Uploaded';
        if (item.folder === state.path && canUploadHere()) refresh();
    } else if (status === 'skipped') {
        item.status = 'skipped';
        item.node.status.textContent = 'Skipped';
    } else if (item.status === 'cancelled') {
        item.node.status.textContent = 'Cancelled';
    } else {
        item.status = 'failed';
        if (status === 401) sessionExpired();
        item.node.status.textContent = errorMessage(status ? new HttpError(status) : new Error());
    }
    item.node.root.dataset.status = item.status;
    item.node.cancel.classList.add('hidden');
}

function createUploadNode(file) {
    const root = document.createElement('div');
    root.className = 'upload-item';
    root.dataset.status = 'queued';
    const name = subLine('upload-name', file.name);
    const status = subLine('upload-status', `Waiting · ${formatBytes(file.size)}`);
    const progress = document.createElement('div');
    progress.className = 'progress';
    const bar = document.createElement('div');
    bar.className = 'progress-bar';
    progress.append(bar);

    const cancel = document.createElement('button');
    cancel.type = 'button';
    cancel.className = 'icon-button';
    cancel.title = 'Cancel';
    cancel.append(icon('close'));
    cancel.addEventListener('click', () => cancelUpload(file));

    const text = document.createElement('div');
    text.className = 'upload-text';
    text.append(name, status, progress);
    root.append(text, cancel);
    return {root, status, bar, cancel};
}

function cancelUpload(file) {
    const item = uploadItems.find((candidate) => candidate.file === file);
    if (!item || item.status === 'done' || item.status === 'failed') return;
    const queued = uploadQueue.indexOf(item);
    if (queued >= 0) uploadQueue.splice(queued, 1);
    const wasQueued = item.status === 'queued';
    item.status = 'cancelled';
    item.xhr?.abort();
    if (wasQueued) finishUpload(item, 0);
    updateUploadsTitle();
}

function setUploadProgress(item, fraction) {
    item.node.bar.style.width = `${Math.round(fraction * 100)}%`;
    if (item.status === 'uploading') {
        item.node.status.textContent = `${Math.round(fraction * 100)}% of ${formatBytes(item.file.size)}`;
    }
}

function updateUploadsTitle() {
    const active = uploadItems.filter((item) => item.status === 'queued' || item.status === 'uploading').length;
    const failed = uploadItems.filter((item) => item.status === 'failed').length;
    const done = uploadItems.filter((item) => item.status === 'done').length;
    if (active) {
        adminEl.uploadsTitle.textContent = `Uploading ${plural(active, 'file')}…`;
    } else {
        adminEl.uploadsTitle.textContent = [done && `${plural(done, 'file')} uploaded`, failed && `${failed} failed`]
            .filter(Boolean).join(', ') || 'Uploads';
    }
}

document.querySelector('[data-action="close-uploads"]').addEventListener('click', () => {
    adminEl.uploads.classList.add('hidden');
    // keep only unfinished uploads in the list
    for (const item of [...uploadItems]) {
        if (item.status === 'queued' || item.status === 'uploading') continue;
        item.node.root.remove();
        uploadItems.splice(uploadItems.indexOf(item), 1);
    }
});

adminEl.uploadButtons.forEach((button) => button.addEventListener('click', () => adminEl.uploadInput.click()));

adminEl.uploadInput.addEventListener('change', () => {
    if (adminEl.uploadInput.files.length) queueUploads([...adminEl.uploadInput.files], state.path);
    adminEl.uploadInput.value = '';
});

// ---------- drag and drop ----------

let dragDepth = 0;

const isFileDrag = (event) => event.dataTransfer?.types.includes('Files');

window.addEventListener('dragenter', (event) => {
    if (!isFileDrag(event) || !canUploadHere()) return;
    event.preventDefault();
    dragDepth++;
    adminEl.dropOverlayText.textContent = `Drop files to upload to ${state.path ? state.item.name : 'the top folder'}`;
    adminEl.dropOverlay.classList.remove('hidden');
});

window.addEventListener('dragover', (event) => {
    if (isFileDrag(event) && canUploadHere()) event.preventDefault();
});

window.addEventListener('dragleave', () => {
    dragDepth = Math.max(0, dragDepth - 1);
    if (dragDepth === 0) adminEl.dropOverlay.classList.add('hidden');
});

window.addEventListener('drop', (event) => {
    if (!isFileDrag(event)) return;
    event.preventDefault();
    dragDepth = 0;
    adminEl.dropOverlay.classList.add('hidden');
    if (!canUploadHere()) return;

    const entries = [...event.dataTransfer.items].map((item) => item.webkitGetAsEntry?.());
    const files = [...event.dataTransfer.files].filter((file, index) => !entries[index]?.isDirectory);
    if (files.length < event.dataTransfer.files.length) {
        showToast('Folders are skipped, only files can be uploaded');
    }
    if (files.length) queueUploads(files, state.path);
});

// ---------- file actions from the sheet ----------

document.addEventListener('openjfs:render', () => {
    const allowed = canUploadHere();
    adminEl.uploadButtons.forEach((button) => button.classList.toggle('hidden', !allowed));
});

document.addEventListener('openjfs:sheet', (event) => {
    const {file} = event.detail;
    const isRoot = fullPathOf(file) === '';
    const isFolder = isDirectory(file);
    const show = (action, visible) => el.sheet.querySelector(`[data-sheet-action="${action}"]`)
        .classList.toggle('hidden', !visible);
    show('new-folder', isFolder);
    show('rename', !isRoot);
    show('move', !isRoot);
    show('delete', !isRoot);
    show('privacy', isFolder);
    adminEl.privacyLabel.textContent = file.private ? 'Make public' : 'Make private';
});

document.addEventListener('openjfs:sheet-action', (event) => {
    const {action, file, current} = event.detail;
    const handlers = {
        'new-folder': createFolder,
        rename: renameItem,
        move: moveItem,
        privacy: togglePrivacy,
        delete: deleteItem,
    };
    handlers[action]?.(file, current);
});

function createFolder(file) {
    const name = textField({label: 'Folder name', placeholder: 'New folder'});
    openModal({
        title: 'New folder',
        body: name.node,
        submitLabel: 'Create',
        onSubmit: async () => {
            const path = joinPath(fullPathOf(file), validName(name.input.value));
            await adminRequest('/api/admin/folders', {json: {path}});
            showToast('Folder created');
            refresh();
        },
    });
}

function renameItem(file, current) {
    const name = textField({label: 'New name', value: file.name});
    openModal({
        title: 'Rename',
        body: name.node,
        submitLabel: 'Rename',
        onSubmit: async () => {
            const newName = validName(name.input.value);
            if (newName === file.name) return;
            const to = joinPath(parentPathOf(fullPathOf(file)), newName);
            await adminRequest('/api/admin/move', {json: {from: fullPathOf(file), to}});
            showToast('Renamed');
            afterMove(current, to);
        },
    });
    // select the name without extension, like file managers do
    const dot = file.name.lastIndexOf('.');
    name.input.setSelectionRange(0, dot > 0 && !isDirectory(file) ? dot : file.name.length);
}

function moveItem(file, current) {
    const folder = textField({
        label: 'Move to folder',
        value: parentPathOf(fullPathOf(file)),
        placeholder: 'Empty for the top folder',
    });
    openModal({
        title: `Move “${file.name}”`,
        body: [folder.node, paragraph('Enter the path of an existing folder, for example: mods/client')],
        submitLabel: 'Move',
        onSubmit: async () => {
            const to = joinPath(normalizePath(folder.input.value.trim()), file.name);
            await adminRequest('/api/admin/move', {json: {from: fullPathOf(file), to}});
            showToast('Moved');
            afterMove(current, to);
        },
    });
}

function afterMove(current, newPath) {
    if (current) navigate(newPath, {replace: true});
    else refresh();
}

async function togglePrivacy(file) {
    try {
        await adminRequest('/api/admin/privacy', {json: {path: fullPathOf(file), private: !file.private}});
        showToast(file.private ? 'Folder is now public' : 'Folder is now private');
        refresh();
    } catch (error) {
        showToast(errorMessage(error));
    }
}

async function deleteItem(file, current) {
    const confirmed = await confirmModal({
        title: 'Move to trash?',
        text: `“${file.name}” will be moved to the trash. You can restore it from the admin menu.`,
        submitLabel: 'Move to trash',
        danger: true,
    });
    if (!confirmed) return;
    try {
        await adminRequest(`/api/admin/files/${encodePath(fullPathOf(file))}`, {method: 'DELETE'});
        showToast('Moved to trash');
        if (current) navigate(parentPathOf(fullPathOf(file)), {replace: true});
        else refresh();
    } catch (error) {
        showToast(errorMessage(error));
    }
}

// ---------- trash ----------

function openTrash() {
    const list = document.createElement('div');
    list.className = 'trash-list';
    list.textContent = 'Loading…';
    openModal({title: 'Trash', body: list});
    renderTrash(list);
}

async function renderTrash(list) {
    let entries;
    try {
        entries = await (await adminRequest('/api/admin/trash', {method: 'GET'})).json();
    } catch (error) {
        list.textContent = errorMessage(error);
        return;
    }
    if (entries.length === 0) {
        list.replaceChildren(stateNode('trash', 'Trash is empty', ''));
        return;
    }

    const rows = entries.map((entry) => createTrashRow(entry, list));
    const emptyButton = armedButton('Empty trash', 'Delete everything forever?', async () => {
        await adminRequest('/api/admin/trash', {method: 'DELETE'});
        showToast('Trash emptied');
        renderTrash(list);
    });
    emptyButton.classList.add('trash-empty');
    list.replaceChildren(...rows, emptyButton);
}

function createTrashRow(entry, list) {
    const file = {name: entry.name, type: entry.type};
    const kind = fileKind(file);
    const row = document.createElement('div');
    row.className = 'trash-row';
    row.style.setProperty('--type-color', kindColor(kind));

    const iconBox = document.createElement('span');
    iconBox.className = 'row-icon';
    iconBox.append(icon(kind));

    const folder = parentPathOf(entry.originalPath);
    const details = [
        folder ? `from ${folder}` : 'from the top folder',
        formatDate(entry.deletedAtMillis),
        entry.size >= 0 ? formatBytes(entry.size) : '',
    ].filter(Boolean).join(' · ');
    const text = document.createElement('div');
    text.className = 'row-main';
    text.append(subLine('trash-name', entry.name), subLine('row-sub', details));

    const restore = document.createElement('button');
    restore.type = 'button';
    restore.className = 'icon-button';
    restore.title = 'Restore';
    restore.append(icon('restore'));
    restore.addEventListener('click', async () => {
        try {
            await adminRequest(`/api/admin/trash/${entry.id}/restore`);
            showToast(`Restored to ${folder || 'the top folder'}`);
            renderTrash(list);
            refresh();
        } catch (error) {
            showModalError(error.status === 409
                ? `Cannot restore “${entry.name}”: something with this name is already in its folder.`
                : errorMessage(error));
        }
    });

    const remove = armedButton('', 'Delete forever?', async () => {
        await adminRequest(`/api/admin/trash/${entry.id}`, {method: 'DELETE'});
        renderTrash(list);
    });
    remove.classList.add('icon-button');
    remove.title = 'Delete forever';
    remove.prepend(icon('trash'));

    const actions = document.createElement('div');
    actions.className = 'trash-actions';
    actions.append(restore, remove);
    row.append(iconBox, text, actions);
    return row;
}

// destructive button: the first click asks for confirmation, the second one runs the action
function armedButton(label, confirmLabel, action) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'armed-button';
    const text = document.createElement('span');
    text.textContent = label;
    button.append(text);
    button.addEventListener('click', async () => {
        if (!button.classList.contains('armed')) {
            button.classList.add('armed');
            text.textContent = confirmLabel;
            setTimeout(() => {
                button.classList.remove('armed');
                text.textContent = label;
            }, 4000);
            return;
        }
        button.disabled = true;
        try {
            await action();
        } catch (error) {
            showModalError(errorMessage(error));
            button.disabled = false;
        }
    });
    return button;
}

updateAccountButton();
