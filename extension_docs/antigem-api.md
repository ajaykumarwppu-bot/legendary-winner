# chrome.antigem — AntiGem native API

These are app additions, not Chrome APIs. Every call requires the manifest permission **`antigemFiles`**, including automation. Every method takes one object and returns a Promise; callback overloads are not supported. Errors reject. Page automation additionally checks the selected tab's host permission/activeTab access.

## automation({tabId,busy?,locked?,tap?})

Returns `{success:true}` after the native operation.

- `tabId`: numeric ID from chrome.tabs, required.
- `busy:true`: acquires or renews this extension's automation lease. The app shows text-only activity feedback, suppresses keyboard/automatic focus zoom and locks zoom while the lease exists. Keep renewing at intervals below60 seconds (Flow uses15 seconds).
- `busy:false`: releases this extension's lease. Original keyboard/zoom settings return when all owners finish.
- `locked:true|false`: controls the tab's separate user-interaction lock for this extension. Locks do not expire with the busy heartbeat; release explicitly.
- `tap:{fx,fy}`: trusted Android touch at fractions of the visible WebView viewport, each in `[0,1]`. The app converts to native pixels. It must have an attached, visible WebView. Do not pass page/document CSS coordinates directly.

A stable element rectangle can be converted using the page's current visual viewport:

```js
const [frame] = await chrome.scripting.executeScript({
  target: {tabId}, world: 'MAIN', func: () => {
    const button = document.querySelector('button[type=submit]');
    if (!button || button.disabled) throw new Error('Submit unavailable');
    const r = button.getBoundingClientRect(), v = visualViewport;
    return {fx:(r.left+r.width/2-(v?.offsetLeft||0))/(v?.width||innerWidth),
            fy:(r.top+r.height/2-(v?.offsetTop||0))/(v?.height||innerHeight)};
  }
});
await chrome.antigem.automation({tabId, tap:frame.result});
```

Use try/finally around leases. Disabling/reloading through the app manager releases ownership. A crashed worker's busy lease expires after60 seconds; explicit interaction locks need manager release. Do not hold the input lock after a finished job. Website acceptance/network results still need checking: a successful native tap only means the touch was dispatched.

## Local paths and bytes

Paths may be absolute inside the local terminal home, `~/...`, or `/sdcard/...` when Android grants access. Relative paths resolve against the Node host working directory (normally terminal home). File APIs are not per-extension private sandboxes: `antigemFiles` permits access within these roots. For private extension data use storage.local. File methods do not open Android's system document picker; ZIP installation is an app settings feature.

| Method | Arguments → result |
| --- | --- |
| `antigem.stat` | `{path}` → `{path:absolute,size:bytes,modified:epochMs,isFile:boolean}`. Missing files reject. |
| `antigem.readFile` | `{path,encoding?:"utf8"|"base64"}` → string. Entire file is read; use sensible file sizes. Default UTF8. |
| `antigem.writeFile` | `{path,data:string,encoding?:"utf8"|"base64",append?:boolean}` → `{path:absolute}`. Creates parents; overwrite by default. |
| `antigem.listFiles` | `{path,recursive?:boolean,limit?:number}` → `{name,path,isFile,isDirectory,size,modified}[]`. Default limit500, capped2000; includes directories; skips symlink entries. No ordering guarantee or pagination. |
| `antigem.copyFile` | `{from,to}` → `{path:absoluteDestination}`. Creates parents, overwrites destination; same source/destination is a no-op. |
| `antigem.download` | `{url,path,reuse?:boolean}` → `{path:absolute}` after completion. With reuse, an existing nonempty file is reused. Otherwise streams to a temporary file then atomically replaces destination. Deadline180 seconds. Fetch is Node-side and does not inherit WebView auth cookies. |
| `antigem.mediaInfo` | `{path}` → `{duration:numberSeconds}` using Android MediaMetadataRetriever. Local supported media file required. |
| `antigem.mediaFrame` | `{path,at_seconds?:number}` → `{path:absolutePng}`. Omit time for a frame near the video's end (duration minus50ms). Explicit time is clamped to clip bounds. PNG is written beside source as `frame_<stem>_<milliseconds>.png`; nearest decoded frame is used, so timestamp accuracy depends on codec. |

To reuse generated image media as a Flow reference, pass its `media_id` to a Flow tool. Calling readFile/upload again is unnecessary. Local file uploads are cached by canonical stat path, size and modification time for the life of the Flow worker.
