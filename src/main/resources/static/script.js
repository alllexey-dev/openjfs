const fileContentWrapper = document.querySelector('.file-content-wrapper');
const fileContentTitle = document.querySelector('.file-content-title');
const fileContent = document.querySelector('.file-content');
const fileList = document.querySelector('.file-list');
const topbarPath = document.querySelector('.topbar-path');
const topbarDownload = document.querySelector('.topbar-download');

// paths are kept decoded (as returned by the API) and encoded only when building URLs
const encodePath = (path) => path.split('/').map(encodeURIComponent).join('/');

const decodePath = (path) => {
    try {
        return decodeURIComponent(path);
    } catch (e) {
        return path; // malformed escape sequence typed by hand
    }
};

const currentWindowPath = () => decodePath(window.location.pathname.substring(4));

let currentRequest = null;

updateData(currentWindowPath());

window.addEventListener('popstate', (event) => {
    updateData(event.state?.path ?? currentWindowPath());
});


function updateData(path) {
    // drop the previous navigation, otherwise a slow response may overwrite the newer one
    currentRequest?.abort();
    const request = new AbortController();
    currentRequest = request;

    fetch(`/list/${encodePath(path)}`, {signal: request.signal})
        .then((response) => {
            if (!response.ok) throw new Error(`${response.status}`);
            return response.json();
        })
        .then((data) => {
            updateContent(data, request.signal)
            updateTopbar(data)
        })
        .catch((error) => {
            if (error.name === 'AbortError') return;
            showError(error.message);
        });
}

function showError(status) {
    fileList.innerHTML = '';
    fileContentWrapper.classList.remove('hidden');
    fileContent.classList.add('hidden');
    fileContentTitle.textContent = status === '404' ? 'Not found.' : `Failed to load (${status}).`;
}

function updateContent(file, signal) {
    fileContentWrapper.classList.toggle('hidden', isDirectory(file));

    if (isDirectory(file)) {
        let files = file['files']

        files = files == null ? [] : files;
        updateFileList(files);
        updateFileContent('');
    } else {
        if (file['size'] <= 128 * 1024) { // max 128KB
            fetch(`/text/${encodePath(file['path'] + file['name'])}`, {signal})
                .then(response => {
                    if (!response.ok) throw new Error(`${response.status}`);
                    return response.text();
                })
                .then(text => {
                    fileContent.classList.remove('hidden');
                    fileContentTitle.textContent = 'File contents:';
                    updateFileContent(text)
                })
                .catch((error) => {
                    if (error.name === 'AbortError') return;
                    fileContent.classList.add('hidden');
                    fileContentTitle.textContent = `Failed to load file contents (${error.message}).`;
                });
        } else {
            fileContentTitle.textContent = 'File is too huge to display.'
            fileContent.classList.add('hidden');
        }

        updateFileList([file]);
    }
}

function updateFileList(files) {
    fileList.innerHTML = '';

    sortFileList(files);
    for (let file of files) {
        let listItem = createListItem(file);
        fileList.appendChild(listItem);
    }
}

function sortFileList(files) {
    files.sort((a, b) => {
        if (isDirectory(a) && !isDirectory(b)) return -1;
        if (!isDirectory(a) && isDirectory(b)) return 1;

        return a.name.toLowerCase().localeCompare(b.name.toLowerCase());
    });
}

function updateFileContent(text) {
    fileContent.value = text;
}

function updateTopbar(data) {
    updateTopbarPath(data);
    updateTopbarButtons(data);
}

function updateTopbarPath(file) {
    let serverNameSegment = topbarPath.querySelector('.server-name').cloneNode(true);
    addLinkListener(serverNameSegment);
    topbarPath.innerHTML = '';
    topbarPath.appendChild(serverNameSegment);
    let fullPath = file['path'] + file['name'];

    let splitPath = fullPath.split('/');
    let pathBuffer = '';

    for (let pathSegmentStr of splitPath) {
        if (pathSegmentStr === '') continue;
        pathBuffer += pathSegmentStr + '/';

        let arrow = document.createElement('img');
        arrow.classList.add('topbar-arrow')
        arrow.src = '/svg/arrow.svg';
        arrow.alt = 'arrow';

        let a = document.createElement('a');
        a.href = '/ui/' + encodePath(pathBuffer);
        a.dataset.path = pathBuffer;
        a.text = pathSegmentStr;
        addLinkListener(a);

        topbarPath.appendChild(arrow);
        topbarPath.appendChild(a);
    }
}

function updateTopbarButtons(file) {
    topbarDownload.classList.toggle('unavailable', isDirectory(file) && !allowDownloadDirs);
}

function addLinkListener(a) {
    a.addEventListener('click', (event) => {
        event.preventDefault();
        const newPath = a.dataset.path ?? '';
        updateData(newPath);
        updateWindowPath(newPath);
    });
}

function updateWindowPath(path) {
    const newUrl = `/ui/${encodePath(path)}`;
    window.history.pushState({path}, '', newUrl);
}

function createListItem(file) {
    let isDir = isDirectory(file);
    let fileName = file['name'];
    let fileLastModifiedMillis = file['lastModifiedMillis'];
    let path = file['path'];
    let fileLastModified = fileLastModifiedMillis < 0 ? '—' : formatDate(fileLastModifiedMillis);
    let fileSize = !isDir && file['size'] >= 0 ? formatBytes(file['size']) : '—';

    const listItem = document.createElement('div');
    listItem.classList.add('file-row');
    if (isDir) {
        listItem.classList.add('dir-row')
    }
    const colName = document.createElement('div');

    colName.classList.add('col', 'name');
    const icon = document.createElement('img');
    icon.classList.add('icon');
    icon.alt = isDir ? 'dir' : 'file';
    icon.src = '/svg/' + getSvgName(file);
    const nameTextNode = document.createTextNode(fileName);
    colName.appendChild(icon);
    colName.appendChild(nameTextNode);
    const colModified = document.createElement('div');

    colModified.classList.add('col', 'modified');
    const modifiedTextNode = document.createTextNode(fileLastModified);
    colModified.appendChild(modifiedTextNode);
    const colSize = document.createElement('div');

    colSize.classList.add('col', 'size');
    const sizeTextNode = document.createTextNode(fileSize);
    colSize.appendChild(sizeTextNode);
    const colActions = document.createElement('div');

    colActions.classList.add('col', 'actions');
    const downloadButton = document.createElement('button');
    const downloadIcon = document.createElement('img');
    if (isDir && !allowDownloadDirs) downloadButton.classList.add('unavailable')
    downloadIcon.classList.add('icon');
    downloadIcon.alt = 'download';
    downloadIcon.src = '/svg/download.svg';
    downloadButton.appendChild(downloadIcon);
    colActions.appendChild(downloadButton);
    const linkButton = document.createElement('button');
    const linkIcon = document.createElement('img');
    linkIcon.classList.add('icon');
    linkIcon.alt = 'link';
    linkIcon.src = '/svg/link.svg';
    linkButton.appendChild(linkIcon);
    colActions.appendChild(linkButton);
    listItem.onclick = () => {
        updateData(path + fileName);
        updateWindowPath(path + fileName);
    }
    linkButton.onclick = (e) => {
        e.stopPropagation();
        navigator.clipboard.writeText(window.location.origin + '/ui/' + encodePath(path + fileName))
    }
    downloadButton.onclick = (e) => {

        e.stopPropagation();
        if (isDir && !allowDownloadDirs) return;
        window.location.href = '/direct/' + encodePath(path + fileName);
    }

    if (fileName.startsWith('.')) {
        colName.classList.add('hidden-file');
    }

    listItem.appendChild(colName);
    listItem.appendChild(colModified);
    listItem.appendChild(colSize);
    listItem.appendChild(colActions);

    return listItem;
}

// I hate this
const getSvgName = (file) => {
    let filename = file['name'];
    if (isDirectory(file)) {
        return file['empty'] ? 'folder-empty.svg' : 'folder.svg';
    }

    const ext = getFileExtension(filename);

    switch (ext) {
        // Spreadsheets
        case 'xls':
        case 'xlsx':
        case 'ods':
        case 'csv':
            return 'file-chart.svg';

        // Documents
        case 'txt':
        case 'doc':
        case 'docx':
        case 'pdf':
        case 'odt':
        case 'rtf':
            return 'file-pencil.svg';

        // Code
        case 'py':
        case 'java':
        case 'js':
        case 'html':
        case 'css':
        case 'cpp':
        case 'c':
        case 'json':
        case 'xml':
        case 'sh':
        case 'bat':
            return 'file-code.svg';

        // Images
        case 'png':
        case 'jpg':
        case 'jpeg':
        case 'gif':
        case 'svg':
        case 'bmp':
        case 'ico':
        case 'tif':
        case 'tiff':
        case 'webp':
            return 'file-alt.svg';

        // Audio
        case 'mp3':
        case 'wav':
        case 'ogg':
        case 'aac':
        case 'wma':
        case 'aif':
        case 'flac':
        case 'midi':
            return 'file-audio.svg';

        // Video
        case 'mp4':
        case 'avi':
        case 'mov':
        case 'wmv':
        case 'mkv':
        case 'flv':
            return 'file-audio.svg';

        // Compressed files
        case 'zip':
        case 'rar':
        case '7z':
        case 'tar':
        case 'gz':
            return 'file-lock.svg';

        // Executable files
        case 'exe':
        case 'msi':
        case 'bin':
            return 'file-exclamation.svg';

        case '':
            return 'file-question.svg';
        // Default case
        default:
            return 'file-alt.svg';
    }
};

const getFileExtension = (filename) => {
    if (typeof filename !== 'string') {
        return '';
    }
    const lastDotIndex = filename.lastIndexOf('.');
    if (lastDotIndex === -1 || lastDotIndex === 0) {
        return '';
    }
    return filename.substring(lastDotIndex + 1).toLowerCase();
};

const formatDate = (millis) => {
    const date = new Date(millis);
    const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    const month = months[date.getMonth()];
    const day = date.getDate();
    const year = date.getFullYear();
    return `${month} ${day}, ${year}`;
};

const formatBytes = (bytes, decimals = 1) => {
    if (bytes === 0) return '0 Bytes';

    const k = 1024;
    const dm = decimals < 0 ? 0 : decimals;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB', 'PB', 'EB', 'ZB', 'YB'];

    const i = Math.floor(Math.log(bytes) / Math.log(k));

    let formattedValue = Math.floor(bytes / Math.pow(k, i) * Math.pow(10, dm)) / Math.pow(10, dm);

    if (formattedValue % 1 === 0) {
        return formattedValue + ' ' + sizes[i];
    } else {
        return formattedValue.toFixed(dm) + ' ' + sizes[i];
    }
}

const isDirectory = (file) => file.type === 'DIRECTORY';

