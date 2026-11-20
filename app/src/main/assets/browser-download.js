(function (token, address, limit) {
    let blob;
    let port;
    let stopped = false;
    const ready = fetch(address).then(function (response) {
        if (!response.ok) throw new Error('blob unavailable');
        return response.blob();
    }).then(function (result) {
        if (!result.size || result.size > limit) throw new Error('blob too large');
        return {blob: result};
    }).catch(function () { return {error: true}; });

    function release() {
        stopped = true;
        blob = null;
        window.removeEventListener('message', connect);
        window.removeEventListener(token, cancel);
    }
    function cancel() {
        release();
        if (port) port.close();
    }
    function connect(event) {
        if (event.data !== token || !event.ports || event.ports.length !== 1) return;
        window.removeEventListener('message', connect);
        port = event.ports[0];
        let offset = 0;
        let reading = false;
        function fail() {
            if (!stopped) port.postMessage(JSON.stringify({type: 'error'}));
            release();
            port.close();
        }
        port.onmessage = function (message) {
            if (message.data !== 'next' || !blob || reading || stopped) return;
            if (offset === blob.size) {
                port.postMessage(JSON.stringify({type: 'end'}));
                release();
                port.close();
                return;
            }
            reading = true;
            const reader = new FileReader();
            reader.onload = function () {
                if (stopped) return;
                const data = String(reader.result).split(',')[1];
                offset += Math.min(65536, blob.size - offset);
                port.postMessage(JSON.stringify({type: 'chunk', data: data}));
                reading = false;
            };
            reader.onerror = fail;
            reader.readAsDataURL(blob.slice(offset, offset + 65536));
        };
        ready.then(function (result) {
            if (stopped) return;
            if (result.error) {
                fail();
                return;
            }
            blob = result.blob;
            port.postMessage(JSON.stringify({type: 'begin', size: blob.size}));
        });
        port.start();
    }
    window.addEventListener('message', connect);
    window.addEventListener(token, cancel, {once: true});
})
